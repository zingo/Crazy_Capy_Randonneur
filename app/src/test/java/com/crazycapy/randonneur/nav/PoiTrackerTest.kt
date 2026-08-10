/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.nav

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackPoint
import com.crazycapy.randonneur.gpx.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoiTrackerTest {

    // A straight ~300 m north-running track with two waypoints placed on it.
    private val track = Track(
        "straight",
        points = listOf(
            TrackPoint(53.0, 10.0),
            TrackPoint(53.001, 10.0),
            TrackPoint(53.002, 10.0),
            TrackPoint(53.003, 10.0),
        ),
        waypoints = listOf(
            Waypoint("Midway", 53.0015, 10.0),
            Waypoint("Finish Café", 53.0028, 10.0),
        ),
    )

    @Test
    fun findsNextWaypointInOrder() {
        val tracker = PoiTracker(track)
        assertEquals(2, tracker.count)

        // Before the first: next is Midway
        val first = tracker.next(0.0)
        assertEquals("Midway", first!!.first)
        assertTrue(first.second > 150.0)

        // Between the two: next is Finish Café
        val mid = tracker.next(180.0)
        assertEquals("Finish Café", mid!!.first)

        // Past the last: nothing
        assertNull(tracker.next(400.0))
    }

    @Test
    fun ignoresWaypointsFarOffRoute() {
        val offTrack = Track(
            "straight",
            points = listOf(
                TrackPoint(53.0, 10.0),
                TrackPoint(53.003, 10.0),
            ),
            waypoints = listOf(
                Waypoint("Somewhere Else", 55.0, 14.0), // ~300 km away
            ),
        )
        val tracker = PoiTracker(offTrack)
        assertEquals(0, tracker.count)
        assertNull(tracker.next(0.0))
    }

    @Test
    fun routeDistanceProjectsClosestRoutePoint() {
        val along = track.routeDistanceTo(53.001, 10.0)
        assertTrue(along != null)
        // 53.001 is exactly the second track point.
        assertEquals(track.distanceAt(1), along!!, 5.0)
    }
}