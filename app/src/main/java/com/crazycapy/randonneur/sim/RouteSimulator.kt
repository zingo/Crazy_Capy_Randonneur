/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RouteSimulator — ghost-ride driver for the emulator
 *
 *   Loads a Track -> walks its points faster than real time ->
 *   emits synthetic Location fixes at configurable speed multiplier
 *
 * Used in both unit tests (headless) and the instrumented ghost-ride
 * test suite that validates the full NavigationService pipeline.
 */
package com.crazycapy.randonneur.sim

import com.crazycapy.randonneur.gpx.Track

/**
 * Rides a [Track] faster than real time, emitting synthetic GPS fixes.
 *
 * [timeScale] is the speed-up factor: 30 means the whole ride takes
 * `trackTime / 30` real seconds (a 10 km, 25 km/h ride -> 48 s).
 * Pure Kotlin; [sleeper] can be a no-op for instant test runs.
 */
class RouteSimulator(
    val track: Track,
    @Volatile var speedKmh: Double = 25.0,
    @Volatile var timeScale: Double = 30.0,
    val stepMeters: Double = 3.0,
    /** Start riding at `startMeters` along the track (mid-route resume). */
    val startMeters: Double = 0.0,
) {
    /** Wall-clock seconds the simulated ride takes. */
    val realTimeSeconds: Double
        get() = track.lengthMeters / (speedKmh * 1000.0 / 3600.0) / timeScale

    val sampleCount: Int get() = (track.lengthMeters / stepMeters).toInt() + 1

    /**
     * Emits points (lat, lon, distanceAlong) along the track until finished or
     * [shouldStop] returns true. Blocking; run it on a background thread.
     * [speedKmh] and [timeScale] may change between points (live adjustments).
     */
    fun run(
        onPoint: (Double, Double, Double) -> Unit,
        shouldStop: () -> Boolean = { false },
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
    ) {
        var d = startMeters.coerceIn(0.0, track.lengthMeters)
        while (d <= track.lengthMeters + stepMeters) {
            if (shouldStop()) break
            val p = track.pointAtDistance(d.coerceAtMost(track.lengthMeters))
            onPoint(p.lat, p.lon, d.coerceAtMost(track.lengthMeters))
            val speedMps = speedKmh * 1000.0 / 3600.0
            val stepTimeMs = (stepMeters / speedMps * 1000.0 / timeScale).coerceAtLeast(0.5)
            sleeper(stepTimeMs.toLong())
            d += stepMeters
        }
    }
}