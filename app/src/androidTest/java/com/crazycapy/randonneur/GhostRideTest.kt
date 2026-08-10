/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.crazycapy.randonneur.gpx.TrackLoader
import com.crazycapy.randonneur.nav.NavEngine
import com.crazycapy.randonneur.nav.NavEvent
import com.crazycapy.randonneur.nav.PoiTracker
import com.crazycapy.randonneur.sim.RouteSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented "ghost ride" test. Imports a real GPX file from the test app's
 * assets, rides it faster than real-time via [RouteSimulator], feeds the synthetic
 * fixes into the [NavEngine], and asserts the ride drives the engine to arrival.
 *
 * Runs on both the emulator and a connected device.
 */
@RunWith(AndroidJUnit4::class)
class GhostRideTest {

    @Test
    fun fullGhostRideCompletes() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context

        val track = TrackLoader.loadAsset(context, "ghost_ride.gpx")
        assertTrue("track should have many points", track.points.size > 50)
        assertTrue("track should be non-trivial length", track.lengthMeters > 1000.0)
        assertTrue("track should carry waypoints", track.waypoints.size >= 2)

        val engine = NavEngine(track)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val sawTurn = java.util.concurrent.atomic.AtomicBoolean(false)
        engine.addListener { event ->
            when (event) {
                is NavEvent.TurnApproachAt -> sawTurn.set(true)
                is NavEvent.Arrived -> completed.set(true)
                else -> {}
            }
        }

        val sim = RouteSimulator(track, speedKmh = 25.0, timeScale = 90.0, stepMeters = 8.0)
        sim.run(
            onPoint = { lat, lon, _ -> engine.update(lat, lon, speedKmh = 25.0) },
            sleeper = {}, // instant, faster than real-time
        )

        val last = track.points.last()
        engine.update(last.lat, last.lon)

        assertTrue("simulator should report arrival", completed.get())
        assertTrue("at least one turn approach should have fired", sawTurn.get())

        // Waypoints must project onto the route and be discoverable mid-ride.
        val poi = PoiTracker(track)
        assertTrue("route should have projected waypoints", poi.count == track.waypoints.size)
        val next = poi.next(0.0)
        assertTrue("a next POI should be findable", next != null)
        assertTrue("next POI should be ahead of start", next!!.second > 0.0)
    }

    @Test
    fun reverseGhostRideCompletes() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context

        val track = TrackLoader.loadAsset(context, "ghost_ride.gpx")
        val engine = NavEngine(track)
        engine.setReverse(true)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val sawTurnRight = java.util.concurrent.atomic.AtomicBoolean(false)
        engine.addListener { event ->
            when (event) {
                is NavEvent.TurnApproachAt -> if (event.turn.degrees >= 25.0) sawTurnRight.set(true)
                is NavEvent.Arrived -> completed.set(true)
                else -> {}
            }
        }

        val rev = track.reversed()
        val sim = RouteSimulator(rev, speedKmh = 25.0, timeScale = 90.0, stepMeters = 8.0)
        sim.run(
            onPoint = { lat, lon, _ -> engine.update(lat, lon, speedKmh = 25.0) },
            sleeper = {},
        )
        engine.update(rev.points.last().lat, rev.points.last().lon)

        assertTrue("reversed ride should arrive at the original start", completed.get())
        assertTrue("reversed ride should log turns", sawTurnRight.get())
        assertEquals(0.0, engine.remainingM, 10.0)
        assertTrue(engine.reverse)
    }

    @Test
    fun midRouteResumeRidesOn() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context

        val track = TrackLoader.loadAsset(context, "ghost_ride.gpx")
        val engine = NavEngine(track)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val sawApproach = java.util.concurrent.atomic.AtomicBoolean(false)
        engine.addListener { event ->
            when (event) {
                is NavEvent.TurnApproachAt -> sawApproach.set(true)
                is NavEvent.Arrived -> completed.set(true)
                else -> {}
            }
        }

        // Resume partway along the route instead of from the start.
        val resumeAt = track.lengthMeters * 0.35
        engine.seedAlong(resumeAt)
        val sim = RouteSimulator(track, speedKmh = 25.0, timeScale = 90.0, stepMeters = 8.0, startMeters = resumeAt)
        sim.run(
            onPoint = { lat, lon, _ -> engine.update(lat, lon, speedKmh = 25.0) },
            sleeper = {},
        )
        engine.update(track.points.last().lat, track.points.last().lon)

        assertTrue("reseeding mid-route should still arrive", completed.get())
        assertTrue("turns after the resume point should still announce", sawApproach.get())
    }
}