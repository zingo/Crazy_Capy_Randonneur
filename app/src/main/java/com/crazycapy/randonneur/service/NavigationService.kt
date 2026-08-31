/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * NavigationService — foreground service lifecycle
 *
 *   onCreate()
 *       |
 *       v
 *   startRide(track) ---> acquire wake lock
 *       |                     |
 *       v                     v
 *   ticker loop ----------> GPS / ghost-ride driver
 *       |                     |
 *       v                     v
 *   turn events ----------> NavEngine.onGpsFix()
 *       |                     |
 *       v                     v
 *   TTS / beeps ----------> RideStore update
 *       |
 *       v
 *   stopRide() ---> release wake lock ---> onDestroy()
 */
package com.crazycapy.randonneur.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.crazycapy.randonneur.KMH_TO_MS
import com.crazycapy.randonneur.R
import com.crazycapy.randonneur.ble.HrProvider
import com.crazycapy.randonneur.ble.StubHrProvider
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.nav.Geo
import com.crazycapy.randonneur.nav.NavEngine
import com.crazycapy.randonneur.nav.NavEvent
import com.crazycapy.randonneur.nav.PoiTracker
import com.crazycapy.randonneur.nav.maneuverFor
import com.crazycapy.randonneur.radar.RadarClient
import com.crazycapy.randonneur.sim.RadarSimulator
import com.crazycapy.randonneur.sim.RouteSimulator
import com.crazycapy.randonneur.state.RideMode
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.state.RouteStore
import com.crazycapy.randonneur.voice.BeepPlanner
import com.crazycapy.randonneur.voice.BeepSignal
import com.crazycapy.randonneur.voice.BeepTone
import com.crazycapy.randonneur.voice.Phrases
import com.crazycapy.randonneur.voice.TurnSummary
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Foreground navigation service.
 *
 * Owns the GPS fix stream (or the ghost-ride simulator), the [NavEngine], the
 * training stats and the TTS voice, so spoken guidance keeps working when the
 * screen is off and the app is not on top. The Activity only observes
 * [RideStore] and redraws the map while the screen is on and the app is visible.
 */
class NavigationService : Service() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var engine: NavEngine? = null
    private var poiTracker: PoiTracker? = null
    private var hrProvider: HrProvider = StubHrProvider
    private var driverThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val arrived = AtomicBoolean(false)

    /** Bumped every time a ghost-sim thread is (re)started; guards the stop-post
     *  of a superseded thread from killing a freshly restarted one. */
    private val ghostGeneration = AtomicInteger(0)

    private var startRealtimeMs = 0L
    private var ticker: Runnable? = null
    private var sim: RouteSimulator? = null
    private var radarSim: RadarSimulator? = null
    private var tickCounter = 0

    // Turn beeps (left/right cue that shortens as the turn nears).
    private var tone: ToneGenerator? = null
    private var beeping = false
    private var lastBeepAtMs = 0L

    /** True while a real turn is being approached; drives beeps independently of the popup UI. */
    private var turnActive = false

    // Last notification text built, to skip rendering duplicates (battery).
    private var lastNotificationText: String? = null

    // GPS speed estimation state
    private var lastFixAtMs: Long? = null
    private var lastFixLat = 0.0
    private var lastFixLon = 0.0
    private var liveCoveredM = 0.0

    // Audio focus so music/podcasts pause during guidance announcements.
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = abandonTransientFocus()
        override fun onError(utteranceId: String?) = abandonTransientFocus()
        override fun onError(utteranceId: String?, errorCode: Int) = abandonTransientFocus()
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            feedFix(location.latitude, location.longitude)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(utteranceListener)
                tts?.setAudioAttributes(speechAudioAttributes())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GPS -> startRide(RideMode.GPS)
            ACTION_START_GHOST -> startRide(RideMode.GHOST)
            ACTION_TOGGLE_REVERSE -> toggleReverse()
            else -> stopRide("Navigation stopped")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRide(mode: RideMode) {
        val track = RideStore.track
        if (track == null) {
            stopRide("No route loaded")
            return
        }
        if (running.get()) {
            stopRide("Ride restarted")
        }
        RouteCache.cancel()

        running.set(true)
        arrived.set(false)
        lastFixAtMs = null
        liveCoveredM = 0.0
        startRealtimeMs = SystemClock.elapsedRealtime()
        tickCounter = 0

        RideStore.active = true
        RideStore.mode = mode
        RideStore.remainingM = track.lengthMeters
        RideStore.coveredM = 0.0
        RideStore.totalM = track.lengthMeters
        RideStore.speedKmh = 0.0
        RideStore.bearing = null
        RideStore.avgSpeedKmh = 0.0
        RideStore.elapsedSec = 0L
        RideStore.hr = null
        RideStore.nextPoiName = null
        RideStore.nextPoiM = null
        RideStore.nextPoiIndex = null
        RideStore.offRouteActive = false
        RideStore.offRouteM = 0.0
        RideStore.offRouteAcknowledged = false
        RideStore.nextTurnDegrees = null
        RideStore.nextTurnM = null
        RideStore.nextTurnAfterDegrees = null
        RideStore.nextTurnAfterM = null
        RideStore.nextTurnIndex = null
        RideStore.upcomingRoute = emptyList()
        RideStore.nextTurnPopupVisible = false
        RideStore.radarTargets = emptyList()
        radarSim = null

        startForeground(NOTIF_ID, buildNotification("Preparing ${track.name}…"))

        val nav = NavEngine(track)
        val reverse = RideStore.reverse
        if (reverse) nav.setReverse(true)
        val resume = RideStore.resumeAlongM
        if (resume != null && resume > 10.0 && resume < track.lengthMeters - 10.0) {
            nav.seedAlong(resume)
            RideStore.remainingM = nav.remainingM
        }
        nav.addListener { event -> handleNavEvent(event) }
        engine = nav
        refreshPoiTracker()

        acquireWakeLock()
        startTicker()

        when (mode) {
            RideMode.GPS -> {
                startGps(track, nav)
                RadarClient.start(this)
            }
            RideMode.GHOST -> startGhost(track, nav, reverse, resume)
            RideMode.IDLE -> Unit
        }
    }

    private fun startGps(track: Track, nav: NavEngine) {
        RideStore.status = "Navigating · ${Phrases.formatDistance(track.lengthMeters)}"
        updateNotification("Navigating · ${Phrases.formatDistance(track.lengthMeters)}")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // No permission: fall back to a slow ghost so the session still makes sound.
            startGhost(track, nav, RideStore.reverse, null)
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = lm
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                GPS_INTERVAL_MS,
                0f,
                locationListener,
                Looper.getMainLooper(),
            )
        }
    }

    private fun startGhost(track: Track, nav: NavEngine, reverse: Boolean, resumeFrom: Double?) {
        val rideTrack = if (reverse) track.reversed() else track
        val startAt = resumeFrom?.coerceIn(0.0, rideTrack.lengthMeters) ?: 0.0
        val s = RouteSimulator(
            rideTrack,
            speedKmh = RideStore.ghostSpeedKmh,
            timeScale = RideStore.ghostTimeScale,
            startMeters = startAt,
        )
        sim = s
        val direction = if (reverse) " (reverse)" else ""
        RideStore.status = "Ghost ride$direction · ${Phrases.formatDistance(RideStore.remainingM ?: rideTrack.lengthMeters)}"
        val gen = ghostGeneration.incrementAndGet()
        val thread = Thread {
            try {
                s.run(
                    onPoint = { lat, lon, _ -> feedFix(lat, lon) },
                    shouldStop = { !running.get() },
                )
            } catch (_: InterruptedException) {
            } finally {
                mainHandler.post {
                    if (running.get() && gen == ghostGeneration.get() && !arrived.get()) {
                        stopRide("Ghost ride stopped")
                    }
                }
            }
        }
        driverThread = thread
        thread.start()
    }

    /** Flip the riding direction while a ride is active (in-ride menu). */
    private fun toggleReverse() {
        engine ?: return
        val t = RideStore.track ?: return
        val wasReversed = RideStore.reverse
        val curAlong = engine!!.distanceAlongM
        val alongOriginal = if (wasReversed) t.lengthMeters - curAlong else curAlong
        val newReverse = !wasReversed
        RideStore.reverse = newReverse
        engine!!.setReverse(newReverse)
        val restartFrom = if (newReverse) t.lengthMeters - alongOriginal else alongOriginal
        engine!!.seedAlong(restartFrom)
        refreshPoiTracker()
        if (RideStore.mode == RideMode.GHOST) {
            restartGhostSim(restartFrom)
        }
        RideStore.status = navSummary()
    }

    /** Re-project waypoints onto the active direction's route (original or reversed). */
    private fun refreshPoiTracker() {
        val t = RideStore.track ?: return
        poiTracker = PoiTracker(if (RideStore.reverse) t.reversed() else t)
    }

    /** Stop the running ghost simulator and restart it from [startFrom] (new direction). */
    private fun restartGhostSim(startFrom: Double) {
        val t = RideStore.track ?: return
        runCatching {
            driverThread?.interrupt()
            driverThread?.join(500)
        }
        driverThread = null
        val s = RouteSimulator(
            if (RideStore.reverse) t.reversed() else t,
            speedKmh = RideStore.ghostSpeedKmh,
            timeScale = RideStore.ghostTimeScale,
            startMeters = startFrom,
        )
        sim = s
        val gen = ghostGeneration.incrementAndGet()
        val thread = Thread {
            try {
                s.run(
                    onPoint = { lat, lon, _ -> feedFix(lat, lon) },
                    shouldStop = { !running.get() },
                )
            } catch (_: InterruptedException) {
            } finally {
                mainHandler.post {
                    if (running.get() && gen == ghostGeneration.get() && !arrived.get()) {
                        stopRide("Ghost ride stopped")
                    }
                }
            }
        }
        driverThread = thread
        thread.start()
    }

    /**
     * Central fix entry point. GPS fixes arrive on the main thread; ghost-sim
     * fixes arrive on the simulator thread (Compose snapshot state is
     * cross-thread safe and TTS accepts any caller thread).
     */
    private fun feedFix(lat: Double, lon: Double) {
        if (!running.get()) return
        val nav = engine ?: return
        val nowMs = SystemClock.elapsedRealtime()

        val moved = lastFixAtMs?.let { Geo.distanceMeters(lastFixLat, lastFixLon, lat, lon) } ?: 0.0

        if (RideStore.mode == RideMode.GHOST) {
            // Ghost ride: speed is the simulated cruise speed (may change live).
            RideStore.speedKmh = RideStore.ghostSpeedKmh
        } else {
            val lastAt = lastFixAtMs
            if (lastAt != null && moved > 0.5) {
                val dt = (nowMs - lastAt) / 1000.0
                if (dt > 0.2) {
                    val s = moved / dt * 3.6
                    if (s < 120.0) {
                        RideStore.speedKmh = s
                        liveCoveredM += moved
                    }
                }
            }
        }

        // Course from the movement between fixes (works for GPS and the ghost sim).
        if (moved > 1.0) {
            RideStore.bearing = Geo.bearingDegrees(lastFixLat, lastFixLon, lat, lon)
        }
        lastFixAtMs = nowMs
        lastFixLat = lat
        lastFixLon = lon

        nav.update(lat, lon, RideStore.speedKmh)
        RideStore.lat = lat
        RideStore.lon = lon
        if (RideStore.mode == RideMode.GHOST) {
            RideStore.coveredM = nav.distanceAlongM
        } else {
            RideStore.coveredM = max(liveCoveredM, nav.distanceAlongM)
        }
        updateAvgSpeed()
        updatePoi()
        updateRadarSim(lat, lon, moved)
    }

    /**
     * Advance the ghost-ride rear-radar traffic sim. In GPS rides the live
     * [RadarClient] owns radarTargets instead, so nothing is cleared here.
     */
    private fun updateRadarSim(lat: Double, lon: Double, movedM: Double) {
        if (RideStore.mode != RideMode.GHOST) return
        if (!RideStore.radarSimEnabled) {
            if (RideStore.radarTargets.isNotEmpty()) RideStore.radarTargets = emptyList()
            return
        }
        val course = RideStore.bearing ?: return
        val speed = RideStore.ghostSpeedKmh
        if (speed <= 0.0) return
        val dtSec = movedM / (speed / KMH_TO_MS)
        val sim = radarSim ?: RadarSimulator().also { radarSim = it }
        RideStore.radarTargets = sim.tick(lat, lon, course, speed, dtSec)
    }

    private fun updateAvgSpeed() {
        val elapsed = (SystemClock.elapsedRealtime() - startRealtimeMs) / 1000.0
        if (elapsed > 2.0 && RideStore.coveredM > 0.0) {
            RideStore.avgSpeedKmh = RideStore.coveredM / elapsed * 3.6
        } else if (RideStore.mode == RideMode.GHOST) {
            RideStore.avgSpeedKmh = RideStore.ghostSpeedKmh
        }
    }

    private fun updatePoi() {
        val poi = poiTracker?.next(max(RideStore.coveredM, 0.0))
        if (poi != null) {
            RideStore.nextPoiName = poi.first
            RideStore.nextPoiM = poi.second
            RideStore.nextPoiIndex = poiTracker?.nextIndex(max(RideStore.coveredM, 0.0))
        } else {
            RideStore.nextPoiName = null
            RideStore.nextPoiM = null
            RideStore.nextPoiIndex = null
        }
    }

    private fun startTicker() {
        ticker?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (!running.get()) return
                RideStore.elapsedSec = (SystemClock.elapsedRealtime() - startRealtimeMs) / 1000
                RideStore.hr = hrProvider.currentHr()
                updateAvgSpeed()
                // Live ghost controls: pick up speed/scale changes from the UI.
                sim?.timeScale = RideStore.ghostTimeScale
                sim?.speedKmh = RideStore.ghostSpeedKmh
                if (RideStore.notificationEnabled) {
                    val s = notifSummary()
                    // Skip redundant renders while the summary is unchanged (e.g.
                    // stopped at a light) — saves notification-system battery.
                    if (s != lastNotificationText) {
                        lastNotificationText = s
                        updateNotification(s)
                    }
                }
                if (++tickCounter % 30 == 0) saveLastRideState()
                mainHandler.postDelayed(this, 1000)
            }
        }
        ticker = runnable
        mainHandler.postDelayed(runnable, 1000)
    }

    private fun stopTicker() {
        ticker?.let { mainHandler.removeCallbacks(it) }
        ticker = null
    }

    private fun handleNavEvent(event: NavEvent) {
        when (event) {
            is NavEvent.OnTrack -> {
                RideStore.remainingM = event.distanceByRouteM
                val t = event.nextTurn
                RideStore.nextTurnDegrees = t?.degrees
                val dist = event.distanceToNextTurnM
                RideStore.nextTurnM = dist
                // Keep the "then …" (next-next turn) heads-up current, so the
                // notification and popup stay correct even after a mid-route resume.
                val nextNext = engine?.peekNextNextTurn()
                val nextDist = t?.distAlongM
                RideStore.nextTurnAfterDegrees = nextNext?.degrees
                RideStore.nextTurnAfterM = if (nextDist != null && nextNext != null) {
                    nextNext.distAlongM - nextDist
                } else {
                    null
                }
                // Popup stays up while a turn is near (it was raised by a turn notice);
                // resumes mid-route raise it here as a fallback since voice notices
                // for that turn already fired.
                val popupWasVisible = RideStore.nextTurnPopupVisible
                if (!popupWasVisible && dist != null && dist <= (engine?.nearWindowM ?: 200.0)) {
                    RideStore.nextTurnPopupVisible = true
                }
                // Generate the junction preview once, when the popup is first raised,
                // and keep it static while approaching so the card doesn't redraw or
                // rotate on every fix; only the distance countdown keeps updating.
                if (!popupWasVisible && RideStore.nextTurnPopupVisible) {
                    val nav = engine
                    if (nav != null) {
                        RideStore.upcomingRoute = nav.turnPreview().map { it.lat to it.lon }
                        RideStore.nextTurnIndex = nav.peekNextTurn()?.position
                    }
                }
            }
            is NavEvent.TurnApproachAt -> {
                RideStore.nextTurnDegrees = event.turn.degrees
                RideStore.nextTurnM = event.distanceM
                RideStore.nextTurnPopupVisible = true
                RideStore.nextTurnIndex = event.turn.position
                speak(Phrases.turnApproachAt(maneuverFor(event.turn.degrees), event.distanceM))
                turnActive = true
                startBeeps()
                updateNotification(notifSummary())
            }
            is NavEvent.TurnNear -> {
                val next = event.nextTurnAfter?.let { maneuverFor(it.degrees) }
                RideStore.nextTurnDegrees = event.turn.degrees
                RideStore.nextTurnM = event.distanceM
                RideStore.nextTurnAfterDegrees = event.nextTurnAfter?.degrees
                RideStore.nextTurnAfterM = event.metersToNextAfter
                RideStore.nextTurnPopupVisible = true
                RideStore.nextTurnIndex = event.turn.position
                speak(Phrases.turnNear(
                    maneuverFor(event.turn.degrees),
                    event.distanceM,
                    next,
                    event.metersToNextAfter,
                ))
                turnActive = true
                startBeeps()
                updateNotification(notifSummary())
            }
            is NavEvent.TurnNow -> {
                RideStore.nextTurnDegrees = event.turn.degrees
                RideStore.nextTurnM = 0.0
                RideStore.nextTurnPopupVisible = true
                RideStore.nextTurnIndex = event.turn.position
                speak(Phrases.turnNow(maneuverFor(event.turn.degrees)))
                turnActive = true
                startBeeps()
                updateNotification(notifSummary())
            }
            is NavEvent.TurnPassed -> {
                RideStore.nextTurnDegrees = null
                RideStore.nextTurnM = null
                RideStore.nextTurnAfterDegrees = null
                RideStore.nextTurnAfterM = null
                RideStore.nextTurnPopupVisible = false
                RideStore.nextTurnIndex = null
                turnActive = false
                stopBeeps()
                updateNotification(notifSummary())
            }
            is NavEvent.GoStraight -> speak(Phrases.goOn(event.distanceToTurnM))
            is NavEvent.OffRoute -> {
                RideStore.offRouteActive = true
                RideStore.offRouteM = event.distanceM
                RideStore.offRouteAcknowledged = false
                speak(Phrases.offRoute(event.distanceM))
                updateNotification(notifSummary())
            }
            is NavEvent.OffRouteStill -> {
                if (RideStore.offRouteAcknowledged) return
                RideStore.offRouteM = event.distanceM
                speak(Phrases.offRouteStill())
            }
            is NavEvent.BackOnRoute -> {
                RideStore.offRouteActive = false
                RideStore.offRouteM = 0.0
                RideStore.offRouteAcknowledged = false
                speak(Phrases.backOnRoute())
                updateNotification(notifSummary())
            }
            is NavEvent.Arrived -> {
                arrived.set(true)
                turnActive = false
                stopBeeps()
                speak(Phrases.arrived())
                updateNotification(notifSummary())
                mainHandler.postDelayed({
                    if (running.get()) stopRide("Route finished. Nice ride!")
                }, 3000)
            }
        }
        RideStore.status = navSummary()
    }

    private fun navSummary(): String {
        val base = if (RideStore.mode == RideMode.GHOST) "Ghost ride" else "Navigating"
        val remaining = RideStore.remainingM
            ?: RideStore.track?.lengthMeters
            ?: 0.0
        val speed = RideStore.speedKmh
        val speedTxt = if (speed > 0) " · ${((speed * 10).toInt()) / 10.0} km/h" else ""
        return "$base · ${Phrases.formatDistance(remaining)}$speedTxt${nextTurnSummary()}"
    }

    private fun nextTurnSummary(): String {
        val deg = RideStore.nextTurnDegrees ?: return ""
        val m = RideStore.nextTurnM ?: return ""
        return " · ${Phrases.maneuverWord(maneuverFor(deg))} in ${Phrases.formatDistance(m.coerceAtLeast(0.0))}"
    }

    /** Compact next + next-next turn guidance for the lock-screen notification. */
    private fun notifSummary(): String {
        if (RideStore.offRouteActive) {
            return "Off route · ${Phrases.formatDistance(RideStore.offRouteM)}"
        }
        val (title, text) = TurnSummary.lines(
            nextDegrees = RideStore.nextTurnDegrees,
            nextM = RideStore.nextTurnM,
            nextNextDegrees = RideStore.nextTurnAfterDegrees,
            nextNextM = RideStore.nextTurnAfterM,
            remainingM = RideStore.remainingM,
            speedKmh = RideStore.speedKmh,
        )
        return if (text.isEmpty()) title else "$title\n$text"
    }

    // ---- Turn beeps ----

    /** The frequency of a left (low) vs right (high) cue burst. */
    private val beepToneType: (BeepTone) -> Int = { t ->
        when (t) {
            BeepTone.LEFT_LOW -> ToneGenerator.TONE_DTMF_1
            BeepTone.RIGHT_HIGH -> ToneGenerator.TONE_DTMF_9
        }
    }

    private fun startBeeps() {
        val volume = RideStore.beepVolume
        if (volume <= 0 || beeping || !turnActive) return
        // Recreate the tone generator so a volume change mid-ride takes effect.
        tone?.release()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, volume) }.getOrNull()
        beeping = tone != null
        lastBeepAtMs = 0L
        if (beeping) mainHandler.post(beepRunnable)
    }

    private fun stopBeeps() {
        beeping = false
        mainHandler.removeCallbacks(beepRunnable)
    }

    private val beepRunnable = object : Runnable {
        override fun run() {
            if (!running.get() || !beeping || !turnActive) {
                beeping = false
                return
            }
            val deg = RideStore.nextTurnDegrees
            val dist = RideStore.nextTurnM
            if (deg == null || dist == null || dist > BeepPlanner.WINDOW_M) {
                // Turn still far or gone; re-check shortly.
                mainHandler.postDelayed(this, 200)
                return
            }
            val signal = BeepPlanner.signal(deg, dist)
            if (signal != null) {
                val now = SystemClock.elapsedRealtime()
                if (lastBeepAtMs == 0L || now - lastBeepAtMs >= signal.intervalMs) {
                    lastBeepAtMs = now
                    playBeep(signal)
                }
            }
            mainHandler.postDelayed(this, 120)
        }
    }

    /** Play one beep event: single burst for right, a low double burst for left. */
    private fun playBeep(signal: BeepSignal) {
        val tg = tone ?: return
        val type = beepToneType(signal.tone)
        tg.startTone(type, signal.burstMs)
        if (signal.repeat >= 2) {
            mainHandler.postDelayed({
                // Ignore the second burst if the ride stopped or the generator
                // was recreated (released) since — startTone would throw.
                if (running.get() && tone === tg) tg.startTone(type, signal.burstMs)
            }, signal.gapMs.toLong())
        }
    }

    private fun speechAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun acquireTransientFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val fr = focusRequest
                ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(speechAudioAttributes())
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                    .also { focusRequest = it }
            am.requestAudioFocus(fr)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    private fun abandonTransientFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun speak(text: String) {
        val volume = RideStore.navVolume
        if (volume <= 0) return
        val t = tts
        if (t != null && ttsReady) {
            if (RideStore.duckMusicEnabled) acquireTransientFocus()
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume / 100f)
            }
            t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "crazycapy-nav")
        }
    }

    private fun saveLastRideState() {
        val nav = engine ?: return
        val t = RideStore.track ?: return
        RouteStore.saveLastRide(this, t.name, RideStore.reverse, nav.distanceAlongM, RideStore.elapsedSec, RideStore.mode)
        // Mirror into the store so the resume banner shows without a restart.
        RideStore.resumeAlongM = nav.distanceAlongM
        RideStore.resumeElapsedSec = RideStore.elapsedSec
        RideStore.resumeReversed = RideStore.reverse
        RideStore.resumeMode = RideStore.mode
        RideStore.resumeRouteName = t.name
    }

    private fun stopRide(message: String) {
        if (!arrived.get()) {
            saveLastRideState()
        } else {
            RouteStore.clearLastRide(this)
            RideStore.resumeAlongM = null
            RideStore.resumeElapsedSec = null
            RideStore.resumeRouteName = null
            RideStore.resumeReversed = false
            RideStore.resumeMode = RideMode.GPS
        }
        running.set(false)
        stopTicker()
        turnActive = false
        stopBeeps()
        tone?.release()
        tone = null
        locationManager?.removeUpdates(locationListener)
        driverThread?.interrupt()
        driverThread = null
        sim = null
        releaseWakeLock()
        // TODO clearing RideStore should probably be refactored to it's own function to make this more readable. 
        RideStore.status = message
        RideStore.active = false
        RideStore.mode = RideMode.IDLE
        RideStore.lat = null
        RideStore.lon = null
        RideStore.bearing = null
        RideStore.speedKmh = 0.0
        RideStore.remainingM = null
        RideStore.nextPoiName = null
        RideStore.nextPoiM = null
        RideStore.nextPoiIndex = null
        RideStore.hr = null
        RideStore.offRouteActive = false
        RideStore.offRouteM = 0.0
        RideStore.offRouteAcknowledged = false
        RideStore.nextTurnDegrees = null
        RideStore.nextTurnM = null
        RideStore.nextTurnAfterDegrees = null
        RideStore.nextTurnAfterM = null
        RideStore.nextTurnIndex = null
        RideStore.upcomingRoute = emptyList()
        RideStore.nextTurnPopupVisible = false
        RideStore.radarTargets = emptyList() //TODO radar data/showing is not part or a ride and should probably not be cleared on stopRide
        radarSim = null
        RadarClient.stop(this)
        lastNotificationText = null
        updateNotification(message)
        // Guard the late foreground-stop so a ride restarted within this window
        // (e.g. quick restart after a finish/stop) is not torn down by it.
        mainHandler.postDelayed({
            if (!running.get()) stopForegroundCompat()
        }, 2500)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "crazycapy:nav",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        running.set(false)
        stopTicker()
        turnActive = false
        stopBeeps()
        tone?.release()
        tone = null
        locationManager?.removeUpdates(locationListener)
        driverThread?.interrupt()
        releaseWakeLock()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---- Notification ----

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Navigation",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Turn-by-turn ride status and guidance" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val title: String
        val body: String
        val nl = text.indexOf('\n')
        if (nl >= 0) {
            title = text.substring(0, nl)
            body = text.substring(nl + 1)
        } else {
            title = text
            body = ""
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_notification)
            .setContentTitle(title)
            .setContentText(body.ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: SecurityException) {
            // Notifications permission not granted; guidance voice still works.
        }
    }

    companion object {
        private const val ACTION_START_GPS = "com.crazycapy.randonneur.action.START_GPS"
        private const val ACTION_START_GHOST = "com.crazycapy.randonneur.action.START_GHOST"
        private const val ACTION_TOGGLE_REVERSE = "com.crazycapy.randonneur.action.TOGGLE_REVERSE"
        private const val ACTION_STOP = "com.crazycapy.randonneur.action.STOP"
        private const val CHANNEL_ID = "navigation"
        private const val NOTIF_ID = 1
        private const val GPS_INTERVAL_MS = 3000L

        fun startGps(context: Context) =
            context.startForegroundService(Intent(context, NavigationService::class.java).setAction(ACTION_START_GPS))

        fun startGhost(context: Context) =
            context.startForegroundService(Intent(context, NavigationService::class.java).setAction(ACTION_START_GHOST))

        fun toggleReverse(context: Context) =
            context.startService(Intent(context, NavigationService::class.java).setAction(ACTION_TOGGLE_REVERSE))

        fun stop(context: Context) =
            context.startService(Intent(context, NavigationService::class.java).setAction(ACTION_STOP))
    }
}
