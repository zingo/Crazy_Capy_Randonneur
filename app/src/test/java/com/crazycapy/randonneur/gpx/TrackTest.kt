/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackTest {

    @Test
    fun lengthMetersApproximatesSumOfChunks() {
        val track = Track(
            "test",
            listOf(
                TrackPoint(50.0, 10.0),
                TrackPoint(50.0, 10.001), // ~71.6 m
                TrackPoint(50.0, 10.002),
            ),
        )
        val seg = 71.55
        assertTrue(track.lengthMeters in (2 * seg - 2)..(2 * seg + 2))
    }

    @Test
    fun pointAtDistanceInterpolates() {
        val track = Track(
            "test",
            listOf(
                TrackPoint(50.0, 10.0),
                TrackPoint(50.0, 10.001), // ~71.55 m
            ),
        )
        val mid = track.pointAtDistance(track.lengthMeters / 2)
        assertEquals(50.0, mid.lat, 1e-6)
        assertTrue(mid.lon in 10.0004..10.0006)

        // Clamped at both ends
        assertEquals(50.0, track.pointAtDistance(-5.0).lat, 1e-9)
        assertEquals(10.0, track.pointAtDistance(-5.0).lon, 1e-9)
        assertEquals(50.0, track.pointAtDistance(1e9).lat, 1e-9)
        assertEquals(10.001, track.pointAtDistance(1e9).lon, 1e-6)
    }

    @Test
    fun distanceIsMonotonic() {
        val track = Track(
            "test",
            listOf(
                TrackPoint(50.0, 10.0),
                TrackPoint(50.1, 10.0),
                TrackPoint(50.1, 10.1),
            ),
        )
        val d0 = track.distanceAt(0)
        val d1 = track.distanceAt(1)
        val d2 = track.distanceAt(2)
        assertTrue(d0 < d1)
        assertTrue(d1 < d2)
    }
}