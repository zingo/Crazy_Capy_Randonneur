/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.sim

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimulatorTest {

    private val track = Track(
        "long-train",
        (0..20).map { TrackPoint(53.0 + it * 0.001, 10.0) },
    )

    @Test
    fun yieldsFasterThanRealTime() {
        val sim = RouteSimulator(track, speedKmh = 25.0, timeScale = 60.0)
        val realSeconds = sim.realTimeSeconds
        val wallClockSeconds = track.lengthMeters / (25.0 * 1000.0 / 3600.0)
        assertTrue(realSeconds > 0)
        assertTrue("simulated should be much shorter than wall clock", realSeconds < wallClockSeconds / 50)
    }

    @Test
    fun emitsPointsAlongTheTrack() {
        val sim = RouteSimulator(track, timeScale = 600.0)
        val out = ArrayList<Triple<Double, Double, Double>>()
        sim.run(onPoint = { lat, lon, d -> out.add(Triple(lat, lon, d)) }, sleeper = {})
        assertTrue(out.size >= 7)

        // Ends at the track's length
        assertEquals(track.lengthMeters, out.last().third, 5.0)

        // Sanity: first point is the track start
        assertEquals(track.points.first().lat, out.first().first, 1e-6)

        // Monotonic along-distance
        for (i in 1 until out.size) {
            assertTrue(out[i].third >= out[i - 1].third)
        }
    }

    @Test
    fun respectsStopCondition() {
        val sim = RouteSimulator(track, timeScale = 600.0)
        var count = 0
        sim.run(onPoint = { _, _, _ -> count++ }, sleeper = {}, shouldStop = { count >= 3 })
        assertEquals(3, count)
    }

    @Test
    fun startsMidRouteForResume() {
        val sim = RouteSimulator(track, timeScale = 600.0, startMeters = 60.0)
        val out = ArrayList<Triple<Double, Double, Double>>()
        sim.run(onPoint = { lat, lon, d -> out.add(Triple(lat, lon, d)) }, sleeper = {})
        assertTrue(out.size >= 7)
        assertEquals(60.0, out.first().third, 1e-6)
        assertEquals(track.pointAtDistance(60.0).lat, out.first().first, 1e-6)
    }

    @Test
    fun ridesReversedTrackFromEndTowardStart() {
        val rev = track.reversed()
        val sim = RouteSimulator(rev, speedKmh = 25.0, timeScale = 600.0)
        val out = ArrayList<Triple<Double, Double, Double>>()
        sim.run(onPoint = { lat, lon, d -> out.add(Triple(lat, lon, d)) }, sleeper = {})
        // Reversed length equals the original length.
        assertEquals(track.lengthMeters, out.last().third, 5.0)
        // First point is the original end.
        assertEquals(track.points.last().lat, out.first().first, 1e-6)
        assertEquals(track.points.last().lon, out.first().second, 1e-6)
    }
}