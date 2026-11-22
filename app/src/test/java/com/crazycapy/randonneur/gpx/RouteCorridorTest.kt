/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCorridorTest {

    private fun track(vararg points: Pair<Double, Double>) = Track(
        "test",
        points.map { (lat, lon) -> TrackPoint(lat, lon) },
    )

    @Test
    fun around_padsTrackBoundsByMeters() {
        val box = RouteCorridor.around(
            track(50.0 to 10.0, 50.1 to 10.1),
            padMeters = 1500.0,
        )
        assertEquals(49.9865, box.south, 1e-3)
        assertEquals(50.1135, box.north, 1e-3)
        assertEquals(9.9790, box.west, 1e-3)
        assertEquals(10.1210, box.east, 1e-3)
    }

    @Test
    fun around_zeroPaddingKeepsExactExtents() {
        val box = RouteCorridor.around(
            track(48.2 to -3.4, 48.9 to 1.2),
            padMeters = 0.0,
        )
        assertEquals(48.2, box.south, 1e-9)
        assertEquals(48.9, box.north, 1e-9)
        assertEquals(-3.4, box.west, 1e-9)
        assertEquals(1.2, box.east, 1e-9)
    }

    @Test
    fun around_longitudePaddingScalesWithLatitude() {
        val equator = RouteCorridor.around(track(0.0 to 0.0, 0.0 to 1.0), 1500.0)
        val north = RouteCorridor.around(track(60.0 to 0.0, 60.0 to 1.0), 1500.0)
        assertTrue(north.east - north.west > equator.east - equator.west)
    }

    @Test
    fun sampleDistancesCoverTheWholeRouteInOneRun() {
        val distances = RouteCorridor.sampleDistances(250_000.0, 1000.0)
        assertEquals(250, distances.size)
        assertEquals(0.0, distances.first(), 0.0)
        assertEquals(249_000.0, distances.last(), 0.0)
        assertTrue(distances.size > 90)
    }

    @Test
    fun sampleDistancesHandleEmptyAndShortRoutes() {
        assertTrue(RouteCorridor.sampleDistances(0.0, 1000.0).isEmpty())
        assertTrue(RouteCorridor.sampleDistances(500.0, 1000.0).isEmpty().not())
        assertEquals(listOf(0.0), RouteCorridor.sampleDistances(500.0, 1000.0))
        assertTrue(RouteCorridor.sampleDistances(1000.0, 0.0).isEmpty())
    }
}
