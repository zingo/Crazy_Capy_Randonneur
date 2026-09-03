/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RadarClient — consumer of the android-bike-radar-overlay bound service.
 *
 * Binds to the overlay app's exported service, subscribes to ~5 Hz radar
 * snapshots once the rider has granted access, projects them onto the rider's
 * position and feeds RideStore.radarTargets (the same layer the ghost-ride
 * simulator uses).
 *
 * Degrades at each step: the overlay app absent, too new for the contract this
 * build speaks, bound with no grant, or registered and then gone quiet.
 * RideStore.radarAvailable and RideStore.radarGranted carry which, nothing is
 * rendered without a grant, and the ghost-ride simulator is unaffected
 * throughout.
 */
package com.crazycapy.randonneur.radar

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.crazycapy.randonneur.state.RideMode
import com.crazycapy.randonneur.state.RideStore
import es.jjrh.bikeradar.ipc.IRadarListener
import es.jjrh.bikeradar.ipc.IRadarService
import es.jjrh.bikeradar.ipc.RadarContract
import es.jjrh.bikeradar.ipc.RadarStateParcel
import java.util.concurrent.Executors

/** How long a stream may go quiet before its targets stop being drawn. */
private const val FRAME_STALE_MS = 10_000L

private const val WATCHDOG_MS = 5_000L

private const val BATTERY_INTERVAL_MS = 10_000L

/**
 * What to tell the rider when the overlay app declines to record a grant, or
 * null when the answer needs no explanation.
 *
 * Separate from [RadarClient] so it can be tested: touching that object loads a
 * binder stub, which a JVM unit test cannot do.
 */
internal fun consentStatus(resultCode: Int): String? = when (resultCode) {
    RadarContract.Consent.RESULT_RIDE_IN_PROGRESS -> "Bike Radar is mid-ride: ask again once it ends"
    RadarContract.Consent.RESULT_NOT_STORED -> "Bike Radar could not save that answer"
    RadarContract.Consent.RESULT_CALLER_UNKNOWN -> "Bike Radar could not identify this app"
    else -> null
}

object RadarClient {

    @Volatile
    private var service: IRadarService? = null

    @Volatile
    private var bound = false

    /** The overlay app speaks a contract version this build cannot read. */
    @Volatile
    private var contractRefused = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastBatteryAtMs = 0L

    @Volatile
    private var lastFrameAtMs = 0L

    /** The rider asked us to hide the overlay, as opposed to it being hidden now. */
    @Volatile
    private var overlayHiddenByRider = false

    // setRadarLightMode waits for the radar to answer over the air, which is
    // longer than the input-dispatch window allows. One thread also keeps two
    // quick taps from racing at the radio.
    private val lightWrites = Executors.newSingleThreadExecutor()

    // Off the main thread: the watchdog re-registers, and that call reaches the
    // other app's package manager and grant store.
    private val watchdogHandler = Handler(
        HandlerThread("radar-watchdog").apply { start() }.looper,
    )

    // Last rider fix the targets were projected against; survives a ride stop so
    // live targets can keep showing on the map while idle.
    @Volatile
    private var lastLat: Double? = null

    @Volatile
    private var lastLon: Double? = null

    @Volatile
    private var lastBearing: Double? = null

    private val watchdog = object : Runnable {
        override fun run() {
            expireStaleStream()
            if (bound) watchdogHandler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IRadarService.Stub.asInterface(binder)
            service = svc
            val version = runCatching { svc.getContractVersion() }.getOrDefault(0)
            if (version !in 1..RadarContract.VERSION) {
                refuseContract()
                return
            }
            contractRefused = false
            // Always subscribe to the stream so targets flow even when idle; the
            // snapshot handler skips ghost rides (the simulator drives those).
            registerListener()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            clearLiveState()
        }

        override fun onBindingDied(name: ComponentName?) {
            // Fires instead of onServiceDisconnected when the overlay app is
            // updated, and nothing rebinds on its own.
            val context = appContext
            unbind()
            if (context != null) refreshAvailability(context)
        }

        override fun onNullBinding(name: ComponentName?) {
            unbind()
        }
    }

    private val listener = object : IRadarListener.Stub() {
        override fun onRadarState(state: RadarStateParcel) {
            onSnapshot(state)
        }
    }

    /**
     * Re-check whether the overlay feature is available (package present, its
     * permission held, its service resolvable), update
     * [RideStore.radarAvailable], and bind so the always-visible controls work
     * even outside a ride. Binding grants nothing on its own;
     * [requestAccessIntent] is what asks the rider.
     */
    fun refreshAvailability(context: Context) {
        if (!RideStore.radarIntegrationEnabled) {
            markUnavailable()
            return
        }
        if (!isOverlayServiceAvailable(context)) {
            markUnavailable()
            return
        }
        RideStore.radarAvailable = true
        ensureBound(context)
        // The rider may have granted us in the overlay app's own settings
        // rather than through our prompt, and nothing tells us when they do.
        if (bound && RideStore.radarGranted != true) registerListener()
    }

    /**
     * Toggle the master integration switch. Disabling unbinds and stops the
     * stream (battery); enabling re-checks availability so a later install of
     * the overlay app is picked up without a restart.
     */
    fun setIntegrationEnabled(context: Context, enabled: Boolean) {
        RideStore.radarIntegrationEnabled = enabled
        if (enabled) {
            refreshAvailability(context)
        } else {
            unbind()
            markUnavailable()
        }
    }

    /** Open the overlay app (launcher activity), if it is installed. */
    fun launchOverlayApp(context: Context) {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(RadarContract.PACKAGE)
        }.getOrNull() ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * The rider's access screen in the overlay app. Launch it for a result and
     * feed the answer back through [onConsentResult].
     *
     * The result is what tells the overlay app which app is asking, so nothing
     * is granted without one. Opening it again when a grant already exists
     * shows its current state, so the same screen covers changing their mind.
     */
    fun requestAccessIntent(): Intent =
        Intent(RadarContract.Consent.ACTION).setPackage(RadarContract.PACKAGE)

    /** The answer from [requestAccessIntent]. */
    fun onConsentResult(context: Context, resultCode: Int, data: Intent?) {
        val ok = resultCode == Activity.RESULT_OK
        if (ok && data?.getBooleanExtra(RadarContract.Consent.EXTRA_READ, false) == true) {
            // The bind may not have landed, or may have died while the rider was
            // on the other app's screen; registering into nothing answers
            // nothing. refreshAvailability registers once it is bound.
            refreshAvailability(context)
            return
        }
        RideStore.radarGranted = false
        consentStatus(resultCode)?.let { RideStore.status = it }
    }

    /** Ensure the overlay service is bound (the stream is registered on
     *  connect so live targets flow even when idle). */
    fun start(context: Context) {
        refreshAvailability(context)
    }

    /** Ride stop: keep the service bound (so the always-visible controls and
     *  idle map targets keep working) and refresh status. */
    fun stop(context: Context) {
        refreshStatus()
    }

    /**
     * Hand the overlay back while this app is not drawing its own map, without
     * forgetting that the rider asked for it hidden.
     *
     * Nothing on the overlay app's side lifts a hold for an app that is merely
     * backgrounded, so without this a rider whose screen blanks is left with no
     * collision display at all.
     */
    fun releaseOverlay() {
        if (RideStore.radarOverlayVisible) return
        overlayHiddenByRider = true
        setOverlayVisible(true)
    }

    /** Re-hide the overlay if that is what the rider had asked for. */
    fun restoreOverlay() {
        if (!overlayHiddenByRider) return
        // Keep the wish until it has actually been carried out: coming back to
        // a bind that died in the meantime must not quietly forget it.
        if (setOverlayVisible(false)) overlayHiddenByRider = false
    }

    private fun ensureBound(context: Context) {
        if (bound) return
        val application = context.applicationContext
        appContext = application
        val ok = runCatching {
            application.bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) {
            // Bind rejected (e.g. IPC permission not granted): treat as unavailable.
            runCatching { application.unbindService(connection) }
            markUnavailable()
            return
        }
        bound = true
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun unbind() {
        runCatching { service?.unregisterTargetListener(listener) }
        service = null
        contractRefused = false
        if (bound) {
            bound = false
            runCatching { appContext?.unbindService(connection) }
        }
        watchdogHandler.removeCallbacks(watchdog)
        // The overlay app lifts every hold once its last consumer unbinds, so
        // there is nothing left for the rider's wish to apply to.
        overlayHiddenByRider = false
        RideStore.radarGranted = null
        clearLiveState()
        lastLat = null
        lastLon = null
        lastBearing = null
    }

    private fun refuseContract() {
        service = null
        if (bound) {
            bound = false
            runCatching { appContext?.unbindService(connection) }
        }
        watchdogHandler.removeCallbacks(watchdog)
        contractRefused = true
        markUnavailable()
    }

    /**
     * Turn the radar's tail light on (solid) or off.
     *
     * Off the caller's thread, and the toggle latches only once the radar has
     * taken the mode: a refusal is a rider who granted reading but not control,
     * or a radar that is not linked. The state here is what this app last set,
     * not a reading, and the overlay app sets its own modes too.
     */
    fun setTailLight(on: Boolean) {
        val svc = service ?: return
        val mode = if (on) RadarContract.LIGHT_MODE_SOLID else RadarContract.LIGHT_MODE_OFF
        lightWrites.execute {
            val ok = runCatching { svc.setRadarLightMode(mode) }.getOrDefault(false)
            if (ok) RideStore.radarLightOn = on
        }
    }

    /**
     * Hiding needs the rider's control grant; showing is always allowed.
     * Returns whether the overlay app took it.
     */
    fun setOverlayVisible(visible: Boolean): Boolean {
        val svc = service ?: return false
        val ok = runCatching { svc.setOverlayVisible(visible) }.getOrDefault(false)
        if (ok) RideStore.radarOverlayVisible = visible
        return ok
    }

    private fun registerListener(pollStatus: Boolean = true) {
        val svc = service ?: return
        val granted = runCatching { svc.registerTargetListener(listener) }.getOrDefault(false)
        RideStore.radarGranted = granted
        if (granted) {
            if (pollStatus) refreshStatus()
            return
        }
        clearLiveState()
    }

    private fun markUnavailable() {
        RideStore.radarAvailable = false
        RideStore.radarGranted = null
        overlayHiddenByRider = false
        clearLiveState()
    }

    private fun clearLiveState() {
        lastFrameAtMs = 0L
        RideStore.radarConnected = false
        RideStore.radarBatteryPercent = null
        RideStore.radarTargets = emptyList()
    }

    /**
     * A registration can stop delivering with the binder still up, because the
     * rider revoked us or the radar link stalled. Neither is reported, so a
     * quiet stream must not leave its last targets drawn as though they were
     * current. Re-registering is idempotent and its return value is the only
     * way to ask whether the grant is still there.
     */
    private fun expireStaleStream() {
        val last = lastFrameAtMs
        if (last == 0L || System.currentTimeMillis() - last < FRAME_STALE_MS) return
        // Re-stamp rather than clear, so the next quiet spell is caught too.
        lastFrameAtMs = System.currentTimeMillis()
        RideStore.radarConnected = false
        RideStore.radarTargets = emptyList()
        // Do not take the connected state from a poll of the very stream this
        // has just judged unreliable; only a frame may set it.
        registerListener(pollStatus = false)
    }

    private fun refreshStatus() {
        val svc = service ?: return
        RideStore.radarConnected = runCatching { svc.isConnected() }.getOrDefault(false)
        refreshBattery()
    }

    private fun refreshBattery() {
        val svc = service ?: return
        val now = System.currentTimeMillis()
        if (now - lastBatteryAtMs < BATTERY_INTERVAL_MS) return
        lastBatteryAtMs = now
        val percent = runCatching { svc.getBatteryPercent() }.getOrDefault(-1)
        RideStore.radarBatteryPercent = if (percent >= 0) percent else null
    }

    /** Feed the rider's current/last known position so live targets can be
     *  projected even when not navigating. [bearing] is the course in degrees
     *  or null when unknown; passing null keeps the previous heading. */
    fun updateRiderPosition(lat: Double, lon: Double, bearing: Double?) {
        lastLat = lat
        lastLon = lon
        if (bearing != null) lastBearing = bearing
    }

    private fun onSnapshot(state: RadarStateParcel) {
        // Stamped before the ghost-ride exit: the stream is alive either way,
        // and the watchdog must not read a ghost ride as a dead one.
        lastFrameAtMs = System.currentTimeMillis()
        if (RideStore.mode == RideMode.GHOST) return
        // An app with no radar attached reports no targets, which is the same
        // shape as a radar reporting an empty road. streamLive tells those
        // apart, and reading the second as the first is the worst thing this
        // could get wrong.
        RideStore.radarConnected = state.streamLive
        refreshBattery()
        if (!state.streamLive) {
            RideStore.radarTargets = emptyList()
            return
        }
        // Project against the current ride fix, or the last known one, so live
        // targets keep showing on the map even when not navigating. With no fix
        // at all there is nowhere to draw them.
        val lat = RideStore.lat ?: lastLat
        val lon = RideStore.lon ?: lastLon
        val bearing = RideStore.bearing ?: lastBearing
        if (lat == null || lon == null || bearing == null) {
            RideStore.radarTargets = emptyList()
            return
        }
        lastLat = lat
        lastLon = lon
        lastBearing = bearing
        RideStore.radarTargets = state.toDomain().map { RadarProjection.project(it, lat, lon, bearing) }
    }

    /** True only when the package is installed, grants the IPC permission, and
     *  answers the contract's service action (so an older overlay version
     *  without the service, a revoked permission, and a version this build
     *  cannot read all count as unavailable). */
    private fun isOverlayServiceAvailable(context: Context): Boolean {
        if (contractRefused) return false
        if (!isOverlayInstalled(context)) return false
        val granted = context.checkSelfPermission(RadarContract.PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        return runCatching {
            context.packageManager.resolveService(
                serviceIntent(),
                PackageManager.ResolveInfoFlags.of(0L),
            ) != null
        }.getOrDefault(false)
    }

    private fun serviceIntent(): Intent =
        Intent(RadarContract.ACTION).setPackage(RadarContract.PACKAGE)

    private fun isOverlayInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(RadarContract.PACKAGE, 0)
        }.isSuccess
}
