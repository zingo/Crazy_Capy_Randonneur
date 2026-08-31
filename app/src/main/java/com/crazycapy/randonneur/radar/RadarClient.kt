/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RadarClient — consumer of the android-bike-radar-overlay bound service.
 *
 * Binds to the overlay app's exported, permission-gated AIDL service, subscribes
 * to ~5 Hz radar snapshots, projects them onto the rider's position and feeds
 * RideStore.radarTargets (the same layer the ghost-ride simulator uses).
 *
 * Auto-detects the overlay app and degrades gracefully when it is absent, an
 * older version without the IPC service, or the IPC permission is not granted:
 * RideStore.radarAvailable stays false, no status bar is drawn, no targets are
 * rendered, and nothing is bound. The pure ghost-ride simulator is unaffected.
 */
package com.crazycapy.randonneur.radar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.crazycapy.randonneur.state.RideMode
import com.crazycapy.randonneur.state.RideStore
import es.jjrh.bikeradar.ipc.IRadarService
import es.jjrh.bikeradar.ipc.IRadarTargetListener
import es.jjrh.bikeradar.ipc.RadarStateParcel
import es.jjrh.bikeradar.ipc.RadarVehicleParcel

object RadarClient {

    const val OVERLAY_PACKAGE = "es.jjrh.bikeradar"
    const val OVERLAY_SERVICE = "es.jjrh.bikeradar.RadarIpcService"
    const val OVERLAY_PERMISSION = "es.jjrh.bikeradar.permission.RADAR"

    @Volatile
    private var service: IRadarService? = null

    @Volatile
    private var bound = false

    private var lastBatteryAtMs = 0L

    // Last rider fix the targets were projected against; survives a ride stop so
    // live targets can keep showing on the map while idle.
    @Volatile
    private var lastLat: Double? = null

    @Volatile
    private var lastLon: Double? = null

    @Volatile
    private var lastBearing: Double? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IRadarService.Stub.asInterface(binder)
            // Always subscribe to the stream so targets flow even when idle; the
            // snapshot handler skips ghost rides (the simulator drives those).
            runCatching { service?.registerTargetListener(listener) }
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            RideStore.radarConnected = false
            RideStore.radarTargets = emptyList()
        }
    }

    private val listener = object : IRadarTargetListener.Stub() {
        override fun onRadarState(state: RadarStateParcel) {
            onSnapshot(state)
        }
    }

    /**
     * Re-check whether the overlay feature is available (package present and its
     * IPC service resolvable), update [RideStore.radarAvailable], and bind the
     * service so the always-visible controls work even outside a ride. Call on
     * app start and whenever availability may have changed (install/uninstall/
     * update); the snapshot stream is only registered during GPS rides.
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
            unbind(context)
            markUnavailable()
        }
    }

    /** Open the overlay app (launcher activity), if it is installed. */
    fun launchOverlayApp(context: Context) {
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(OVERLAY_PACKAGE)
        }.getOrNull() ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Ensure the overlay service is bound (the stream is registered on
     *  connect so live targets flow even when idle). */
    fun start(context: Context) {
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
    }

    /** Ride stop: keep the service bound (so the always-visible controls and
     *  idle map targets keep working) and refresh status. */
    fun stop(context: Context) {
        refreshStatus()
    }

    private fun ensureBound(context: Context) {
        if (bound) return
        val intent = Intent().setComponent(ComponentName(OVERLAY_PACKAGE, OVERLAY_SERVICE))
        val ok = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) {
            // Bind rejected (e.g. IPC permission not granted): treat as unavailable.
            markUnavailable()
            return
        }
        bound = true
    }

    private fun unbind(context: Context) {
        runCatching { service?.unregisterTargetListener(listener) }
        service = null
        if (bound) {
            bound = false
            runCatching { context.unbindService(connection) }
        }
        RideStore.radarConnected = false
        RideStore.radarBatteryPercent = null
        RideStore.radarTargets = emptyList()
        lastLat = null
        lastLon = null
        lastBearing = null
    }

    fun setRadarLightMode(mode: Int) {
        runCatching { service?.setRadarLightMode(mode) }
    }

    fun setOverlayVisible(visible: Boolean) {
        RideStore.radarOverlayVisible = visible
        runCatching { service?.setOverlayVisible(visible) }
    }

    private fun markUnavailable() {
        RideStore.radarAvailable = false
        RideStore.radarConnected = false
        RideStore.radarBatteryPercent = null
        RideStore.radarTargets = emptyList()
    }

    private fun refreshStatus() {
        val svc = service ?: return
        val connected = runCatching { svc.isConnected() }.getOrDefault(false)
        RideStore.radarConnected = connected
        refreshBattery()
    }

    private fun refreshBattery() {
        val svc = service ?: return
        val now = System.currentTimeMillis()
        if (now - lastBatteryAtMs < 10_000L) return
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
        if (RideStore.mode == RideMode.GHOST) return
        // Project against the current ride fix, or the last known one, so live
        // targets keep showing on the map even when not navigating.
        val lat = RideStore.lat ?: lastLat
        val lon = RideStore.lon ?: lastLon
        val bearing = RideStore.bearing ?: lastBearing
        if (lat == null || lon == null || bearing == null) return
        lastLat = lat
        lastLon = lon
        lastBearing = bearing
        RideStore.radarTargets = state.toDomain().map { RadarProjection.project(it, lat, lon, bearing) }
        refreshStatus()
    }

    /** True only when the package is installed, grants the IPC permission, and
     *  exposes the IPC service (so an older overlay version without the service
     *  or a revoked permission both count as unavailable). */
    private fun isOverlayServiceAvailable(context: Context): Boolean {
        if (!isOverlayInstalled(context)) return false
        val granted = context.checkSelfPermission(OVERLAY_PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        return runCatching {
            context.packageManager.getServiceInfo(ComponentName(OVERLAY_PACKAGE, OVERLAY_SERVICE), 0) != null
        }.getOrDefault(false)
    }

    private fun isOverlayInstalled(context: Context): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(OVERLAY_PACKAGE, 0)
        }.isSuccess
}
