/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.nav

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavEngineTest {

    // L-shaped route: east, then north (a left turn at P2).
    private val track = Track(
        "L-shape",
        listOf(
            TrackPoint(53.0, 10.00000),
            TrackPoint(53.0, 10.00370), // ~201 m east
            TrackPoint(53.00060, 10.00370), // ~66.8 m north  (turn vertex)
            TrackPoint(53.00090, 10.00370), // ~33.4 m north
        ),
    )

    private lateinit var engine: NavEngine
    private val events = ArrayList<NavEvent>()

    @Before
    fun setUp() {
        engine = NavEngine(track)
        engine.addListener { events.add(it) }
    }

    @Test
    fun ridesTheRouteAndFiresTurnEvents() {
        // Simulate a fine-grained ride (like the ghost ride) at 5 m intervals.
        var d = 0.0
        while (d <= track.lengthMeters) {
            val p = track.pointAtDistance(d)
            engine.update(p.lat, p.lon)
            d += 5.0
        }

        val onTrack = events.filterIsInstance<NavEvent.OnTrack>()
        val near = events.filterIsInstance<NavEvent.TurnNear>()
        val now = events.filterIsInstance<NavEvent.TurnNow>()
        val passed = events.filterIsInstance<NavEvent.TurnPassed>()
        val arrived = events.filterIsInstance<NavEvent.Arrived>()

        assertTrue(near.isNotEmpty())
        assertTrue(now.isNotEmpty())
        assertEquals(1, near.size)
        assertEquals(1, now.size)
        assertTrue(near.first().turn.degrees < 0) // left turn
        assertTrue(passed.isNotEmpty())
        assertTrue(arrived.isNotEmpty())
        assertTrue(onTrack.isNotEmpty())
    }

    @Test
    fun speedBasedApproachesFire() {
        // Ride at a steady 36 km/h along the track; the L-turn is ~201 m in.
        var d = 0.0
        while (d <= track.lengthMeters) {
            val p = track.pointAtDistance(d)
            engine.update(p.lat, p.lon, speedKmh = 36.0)
            d += 5.0
        }

        val approachAt = events.filterIsInstance<NavEvent.TurnApproachAt>()
        // With 36 km/h, 20s lead ≈ 200 m, 50s lead ≈ 500 m. Both must have fired.
        assertTrue("expected time-based approach notices", approachAt.isNotEmpty())
        assertTrue(
            "expected both 50s and 20s leads in order",
            approachAt.map { it.leadSeconds }.run { contains(50) && contains(20) },
        )
        assertEquals(2, approachAt.size)
    }

    @Test
    fun noneZeroSpeedMeansNoSpeedLeads() {
        var d = 0.0
        while (d <= track.lengthMeters) {
            val p = track.pointAtDistance(d)
            engine.update(p.lat, p.lon, speedKmh = 0.0)
            d += 5.0
        }
        assertTrue(events.none { it is NavEvent.TurnApproachAt })
    }

    @Test
    fun detectsOffRouteAndBackOnRoute() {
        // Wildly off to the east
        engine.update(53.0, 10.00500)
        assertTrue(events.any { it is NavEvent.OffRoute })

        // Come back
        engine.update(track.points[0].lat, track.points[0].lon)
        assertTrue(events.any { it is NavEvent.BackOnRoute })
    }

    @Test
    fun monotonicDistanceAndRemaining() {
        var prev = 0.0
        for (p in track.points) {
            engine.update(p.lat, p.lon)
            assertTrue(engine.distanceAlongM >= prev - 1e-9)
            prev = engine.distanceAlongM
        }
        assertTrue(engine.remainingM >= 0.0)
    }

    @Test
    fun turnNowFiresCloseToTheTurn() {
        // Ride at 36 km/h (10 m/s): the final "turn now" should come ~3 s ahead,
        // i.e. within ~30 m, not the old fixed 60 m window.
        var nowAtDist: Double? = null
        engine = NavEngine(track)
        engine.addListener { e ->
            if (e is NavEvent.TurnNow) nowAtDist = engine.distanceToNextTurn
        }
        var d = 0.0
        while (d <= track.lengthMeters) {
            val p = track.pointAtDistance(d)
            engine.update(p.lat, p.lon, speedKmh = 36.0)
            d += 5.0
        }
        assertNotNull("expected a TurnNow event", nowAtDist)
        assertTrue("TurnNow fired too early: ${nowAtDist} m ahead", nowAtDist!! <= 40.0)
        assertTrue(nowAtDist!! >= 0.0)
    }

    @Test
    fun approachesBeforeNowWindow() {
        // Snap to a point partway along the first (straight) segment, before the turn.
        val nearStart = track.pointAtDistance(50.0)
        engine.update(nearStart.lat, nearStart.lon)
        val turn = engine.peekNextTurn()!!
        val dist = engine.distanceToNextTurn!!
        assertTrue(dist > 60.0)
        assertEquals(track.lengthMeters - engine.distanceAlongM, engine.remainingM, 0.01)
    }

    @Test
    fun goStraightFiresOnLongHauls() {
        // A 3 km straight then a right turn: while far ahead, GoStraight heads-up fires.
        val longTrack = Track(
            "Long-straight",
            listOf(
                TrackPoint(53.0, 10.00000),
                TrackPoint(53.0, 10.01000), // ~1113 m east
                TrackPoint(53.0, 10.03700), // ~3003 m east
                TrackPoint(53.00060, 10.03700), // right turn vertex
                TrackPoint(53.00090, 10.03700),
            ),
        )
        val e = NavEngine(longTrack)
        val got = ArrayList<NavEvent>()
        e.addListener { got.add(it) }

        var d = 0.0
        while (d <= longTrack.lengthMeters) {
            val p = longTrack.pointAtDistance(d)
            e.update(p.lat, p.lon, speedKmh = 36.0)
            d += 10.0
        }

        val goStraight = got.filterIsInstance<NavEvent.GoStraight>()
        assertTrue("expected periodic GoStraight notices", goStraight.isNotEmpty())
        // Every chunk of progress beyond the far window while still far ahead.
        assertTrue(goStraight.size >= 1)
        assertTrue(goStraight.first().distanceToTurnM >= 0.0)
    }

    @Test
    fun offRouteReportsDistanceAndPeriodicReminders() {
        val e = NavEngine(track)
        val got = ArrayList<NavEvent>()
        e.addListener { got.add(it) }
        e.offRouteRepeatEveryFixes = 3

        // Far off to the north-east.
        val far = track.points[0].let { TrackPoint(it.lat + 0.02, it.lon + 0.02) }
        for (i in 0 until 7) e.update(far.lat, far.lon)

        val offs = got.filterIsInstance<NavEvent.OffRoute>()
        val stills = got.filterIsInstance<NavEvent.OffRouteStill>()
        assertEquals(1, offs.size)
        assertTrue("expected a meaningful off-route distance", offs.first().distanceM >= 1500.0)
        assertNotEquals(far.lat, offs.first().snapped.first, 1e-6)
        // Every `offRouteRepeatEveryFixes` fixes while still off -> 2 reminders.
        assertEquals(2, stills.size)
        assertTrue(e.isOffRoute)
    }

    @Test
    fun backOnRouteClearsOffRoute() {
        engine.update(track.points[0].lat, track.points[0].lon + 0.02)
        assertTrue(engine.isOffRoute)
        engine.update(track.points[0].lat, track.points[0].lon)
        assertTrue(!engine.isOffRoute)
        assertTrue(events.any { it is NavEvent.BackOnRoute })
    }

    @Test
    fun wrongWayDetourThenSnapBackResumes() {
        engine.update(track.points[0].lat, track.points[0].lon)
        // Detour far to the east.
        engine.update(track.points[0].lat, track.points[0].lon + 0.006)
        assertTrue(engine.isOffRoute)
        // Return near the route at ~120 m along the first segment.
        val back = track.pointAtDistance(120.0)
        engine.update(back.lat, back.lon)
        assertTrue(!engine.isOffRoute)
        assertTrue("engine should resume at the snapped position", engine.distanceAlongM >= 110.0)
    }

    @Test
    fun reverseRideReachesStartOfOriginal() {
        val e = NavEngine(track)
        val got = ArrayList<NavEvent>()
        e.addListener { got.add(it) }
        e.setReverse(true)
        assertTrue(e.reverse)

        val rev = track.reversed()
        var d = 0.0
        while (d <= track.lengthMeters) {
            val p = rev.pointAtDistance(d)
            e.update(p.lat, p.lon)
            d += 5.0
        }
        assertEquals(0.0, e.remainingM, 10.0)
        assertTrue(got.any { it is NavEvent.Arrived })
        assertTrue("expected maneuvers on the reversed route", got.any { it is NavEvent.TurnNear })
    }

    @Test
    fun reverseCanBeToggledBack() {
        engine.setReverse(true)
        assertTrue(engine.reverse)
        engine.setReverse(false)
        assertTrue(!engine.reverse)
        // Cursor was reset for the new direction; remaining is full length again.
        assertEquals(track.lengthMeters, engine.remainingM, 1e-6)
    }

    @Test
    fun midRideResumeSeedsCursorAheadOfPassedTurn() {
        val turnPos = TurnFinder.find(track).first().distAlongM
        val resumeAt = turnPos + 40.0
        val e = NavEngine(track)
        val got = ArrayList<NavEvent>()
        e.addListener { got.add(it) }
        e.seedAlong(resumeAt)
        assertEquals(resumeAt, e.distanceAlongM, 1e-6)

        // Ride the remaining tail: nothing already behind may be re-announced.
        var d = resumeAt
        while (d <= track.lengthMeters) {
            val p = track.pointAtDistance(d)
            e.update(p.lat, p.lon)
            d += 5.0
        }
        assertTrue(got.any { it is NavEvent.Arrived })
        assertTrue(
            "passed turns must not be re-announced after resume",
            got.none {
                it is NavEvent.TurnNear || it is NavEvent.TurnNow ||
                    it is NavEvent.TurnApproachAt || it is NavEvent.TurnPassed
            },
        )
    }

    @Test
    fun upcomingRouteSamplesAheadOfCursor() {
        val pts = engine.upcomingRoute(metersAhead = 200.0, stepM = 20.0, maxPoints = 6)
        assertTrue(pts.size >= 2)
        assertTrue(pts.size <= 6)
        assertEquals(track.points.first().lat, pts.first().lat, 1e-6)
        assertEquals(track.points.first().lon, pts.first().lon, 1e-6)
    }
}