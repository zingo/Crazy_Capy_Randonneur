/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crazycapy.randonneur.gpx.Track

/** Ride mode: what is driving navigation right now. */
enum class RideMode { IDLE, GPS, GHOST }

/**
 * App-level state shared between the Activity (share target), the foreground
 * [com.crazycapy.randonneur.service.NavigationService] and the Compose UI.
 *
 * Write frequency is low (a few Hz) and it is only observed when the screen is
 * on and the app is in front, so it costs nothing while the display is off.
 */
object RideStore {
    var track: Track? by mutableStateOf(null)

    /** Human-readable one-liner for the bottom bar / notification. */
    var status: String? by mutableStateOf(null)

    var active: Boolean by mutableStateOf(false)
    var mode: RideMode by mutableStateOf(RideMode.IDLE)

    // ---- Ride configuration ----

    /** Riding the route from its end back to its start. */
    var reverse: Boolean by mutableStateOf(false)

    /** Ghost-ride speed-up factor (1x = real time). */
    var ghostTimeScale: Double by mutableStateOf(90.0)

    /** Ghost-ride cruise speed in km/h. */
    var ghostSpeedKmh: Double by mutableStateOf(28.0)

    /** Where the last ride stopped along the route (resume point), or null. */
    var resumeAlongM: Double? by mutableStateOf(null)

    /** Elapsed seconds of the last ride, for the resume prompt. */
    var resumeElapsedSec: Long? by mutableStateOf(null)

    /** Reverse flag of the last ride, for the resume prompt. */
    var resumeReversed: Boolean by mutableStateOf(false)

    /** Name of the route the last ride used. */
    var resumeRouteName: String? by mutableStateOf(null)

    // ---- Configurable behavior (settings menu) ----

    /** Show the next-turn corner popup while riding. */
    var nextTurnPopupEnabled: Boolean by mutableStateOf(true)

    /** Keep refreshing the notification every second while riding. */
    var notificationEnabled: Boolean by mutableStateOf(true)

    /** Request audio focus so other apps pause while guidance speaks. */
    var duckMusicEnabled: Boolean by mutableStateOf(true)

    // ---- Live off-route state ----

    var offRouteActive: Boolean by mutableStateOf(false)
    var offRouteM: Double by mutableStateOf(0.0)

    /** Rider acknowledged they are off route; suppress repeat reminders. */
    var offRouteAcknowledged: Boolean by mutableStateOf(false)

    // ---- Next-turn corner popup cache (updated by the service per fix) ----

    var nextTurnDegrees: Double? by mutableStateOf(null)
    var nextTurnM: Double? by mutableStateOf(null)
    var nextTurnAfterDegrees: Double? by mutableStateOf(null)
    var nextTurnAfterM: Double? by mutableStateOf(null)
    var upcomingRoute: List<Pair<Double, Double>> by mutableStateOf(emptyList())

    /** Latest known rider position (the snapped-to-route fix if available). */
    var lat: Double? by mutableStateOf(null)
    var lon: Double? by mutableStateOf(null)

    /** Course over ground, degrees from north (clockwise), null while stationary/unknown. */
    var bearing: Double? by mutableStateOf(null)

    /** Distance in metres still to ride. */
    var remainingM: Double? by mutableStateOf(null)

    // ---- Training stats (kept current only while a ride is active) ----

    /** Distance already covered along the route, in metres. */
    var coveredM: Double by mutableStateOf(0.0)

    /** Total route length, in metres (convenience mirror of track.lengthMeters). */
    var totalM: Double by mutableStateOf(0.0)

    /** Instantaneous speed in km/h (0 while standing still). */
    var speedKmh: Double by mutableStateOf(0.0)

    /** Rolling average speed since ride start, km/h. */
    var avgSpeedKmh: Double by mutableStateOf(0.0)

    /** Wall-clock seconds elapsed since ride start. */
    var elapsedSec: Long by mutableStateOf(0L)

    /** Heart rate in bpm, or null when no sensor connected. */
    var hr: Int? by mutableStateOf(null)

    // ---- POI / waypoint guidance ----

    /** Name of the next waypoint along the route, if any. */
    var nextPoiName: String? by mutableStateOf(null)

    /** Distance along the route to the next waypoint, metres. */
    var nextPoiM: Double? by mutableStateOf(null)

    /** True while the map should keep drawing updates (screen on + app in front). */
    var mapVisible: Boolean by mutableStateOf(false)

    /** Preferred map style: dark by default (OLED screens drain less). */
    var darkMap: Boolean by mutableStateOf(true)

    fun reset() {
        active = false
        mode = RideMode.IDLE
        lat = null
        lon = null
        bearing = null
        remainingM = null
        coveredM = 0.0
        totalM = 0.0
        speedKmh = 0.0
        avgSpeedKmh = 0.0
        elapsedSec = 0L
        hr = null
        nextPoiName = null
        nextPoiM = null
        status = null
        offRouteActive = false
        offRouteM = 0.0
        offRouteAcknowledged = false
        nextTurnDegrees = null
        nextTurnM = null
        nextTurnAfterDegrees = null
        nextTurnAfterM = null
        upcomingRoute = emptyList()
    }
}