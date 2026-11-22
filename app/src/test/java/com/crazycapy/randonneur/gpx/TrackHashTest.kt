/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TrackHashTest {

    private fun track(vararg points: TrackPoint) = Track("test", points.toList())

    @Test
    fun sameTrackHasSameHash() {
        val first = track(
            TrackPoint(50.0, 10.0, 12.5),
            TrackPoint(50.1, 10.1, 34.0),
        )
        val second = track(
            TrackPoint(50.0, 10.0, 12.5),
            TrackPoint(50.1, 10.1, 34.0),
        )
        assertEquals(TrackHash.of(first), TrackHash.of(second))
    }

    @Test
    fun coordinateChangeChangesHash() {
        val base = track(TrackPoint(50.0, 10.0), TrackPoint(50.1, 10.1))
        val moved = track(TrackPoint(50.0, 10.0), TrackPoint(50.1001, 10.1))
        assertNotEquals(TrackHash.of(base), TrackHash.of(moved))
    }

    @Test
    fun elevationChangeChangesHash() {
        val base = track(TrackPoint(50.0, 10.0, 12.5), TrackPoint(50.1, 10.1, 34.0))
        val higher = track(TrackPoint(50.0, 10.0, 13.5), TrackPoint(50.1, 10.1, 34.0))
        assertNotEquals(TrackHash.of(base), TrackHash.of(higher))
    }

    @Test
    fun missingElevationDiffersFromPresent() {
        val flat = track(TrackPoint(50.0, 10.0), TrackPoint(50.1, 10.1))
        val elevated = track(TrackPoint(50.0, 10.0, 0.0), TrackPoint(50.1, 10.1))
        assertNotEquals(TrackHash.of(flat), TrackHash.of(elevated))
    }

    @Test
    fun reversedTrackHasDifferentHash() {
        val forward = track(TrackPoint(50.0, 10.0), TrackPoint(50.1, 10.1))
        val reverse = track(TrackPoint(50.1, 10.1), TrackPoint(50.0, 10.0))
        assertNotEquals(TrackHash.of(forward), TrackHash.of(reverse))
    }
}
