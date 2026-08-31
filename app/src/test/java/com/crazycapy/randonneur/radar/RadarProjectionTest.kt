/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.radar

import com.crazycapy.randonneur.nav.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarProjectionTest {

    private val behind = RadarVehicle(
        id = 1,
        distanceM = 100,
        closingKmh = 20,
        size = RadarVehicleSize.CAR,
        lateralPos = 0f,
        rangeXm = 0f,
        isAhead = false,
    )

    @Test
    fun placesBehindTargetAlongReverseBearing() {
        // Rider heading east from (50,10); a target 100 m behind sits ~100 m west.
        val p = RadarProjection.project(behind, 50.0, 10.0, 90.0)
        val dist = Geo.distanceMeters(50.0, 10.0, p.lat, p.lon)
        assertTrue("target ~100 m behind, was ${dist}m", dist in 99.0..101.0)
        assertTrue("target is west of the rider", p.lon < 10.0)
    }

    @Test
    fun placesBehindTargetOffsetToRight() {
        val right = behind.copy(rangeXm = 3.0f)
        val p = RadarProjection.project(right, 50.0, 10.0, 0.0) // riding north
        // Lateral offset to the rider's right = east.
        assertTrue(p.lon > 10.0)
    }

    @Test
    fun placesAheadTargetAlongBearing() {
        val ahead = behind.copy(isAhead = true, distanceM = 50)
        val p = RadarProjection.project(ahead, 50.0, 10.0, 0.0) // riding north
        assertTrue("ahead target is north", p.lat > 50.0)
        val dist = Geo.distanceMeters(50.0, 10.0, p.lat, p.lon)
        assertTrue("target ~50 m ahead, was ${dist}m", dist in 49.0..51.0)
    }

    @Test
    fun keepsIdentityFields() {
        val p = RadarProjection.project(behind, 50.0, 10.0, 90.0)
        assertEquals(behind.id, p.id)
        assertEquals(behind.distanceM, p.distanceM)
        assertEquals(behind.closingKmh, p.closingKmh)
        assertEquals(behind.size, p.size)
        assertEquals(behind.isAhead, p.isAhead)
    }
}
