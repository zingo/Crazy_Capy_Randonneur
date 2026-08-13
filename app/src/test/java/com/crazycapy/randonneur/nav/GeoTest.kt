/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun distanceMeters_isSymmetricAndApproximatelyRight() {
        val d = Geo.distanceMeters(52.52, 13.405, 52.52, 13.425)
        assertTrue(d in 1350.0..1450.0)

        val d2 = Geo.distanceMeters(52.0, 13.0, 53.0, 13.0)
        assertTrue(d2 in 111_000.0..112_000.0)

        assertEquals(0.0, Geo.distanceMeters(1.0, 1.0, 1.0, 1.0), 1e-9)
    }

    @Test
    fun bearingDegreesEast() {
        assertEquals(90.0, Geo.bearingDegrees(50.0, 10.0, 50.0, 10.1), 0.5)
    }

    @Test
    fun bearingDegreesNorthAndWrap() {
        assertEquals(0.0, Geo.bearingDegrees(50.0, 10.0, 50.1, 10.0), 0.5)
        assertEquals(270.0, Geo.bearingDegrees(50.0, 10.1, 50.0, 10.0), 0.5)
        val west = Geo.bearingDegrees(50.0, 10.0, 50.0, 9.9)
        assertTrue(west in 269.0..271.0)
    }

    @Test
    fun turnDegreesNormalizesToSigned() {
        assertEquals(90.0, Geo.turnDegrees(0.0, 90.0), 1e-9)
        assertEquals(-90.0, Geo.turnDegrees(90.0, 0.0), 1e-9)
        assertEquals(179.0, Geo.turnDegrees(0.0, 179.0), 1e-9)
        assertEquals(-179.0, Geo.turnDegrees(0.0, 181.0), 1e-9)
        // Cob of 0 -> straight
        assertEquals(0.0, Geo.turnDegrees(45.0, 45.0), 1e-9)
    }

    @Test
    fun pointSegmentDistance() {
        // Point exactly on a segment
        assertEquals(0.0, Geo.pointSegmentDistance(0.0, 0.0, 0.0, 0.0, 0.0, 1.0), 1e-9)

        // ~3.3 m perpendicular to a horizontal segment ~.001 deg long
        val d = Geo.pointSegmentDistance(0.0, 0.0, -0.1, 0.0, 0.1, 0.0)
        assertEquals(0.0, d, 1e-9) // on the line
        val d2 = Geo.pointSegmentDistance(0.0, 3e-5, -0.1, 0.0, 0.1, 0.0)
        assertTrue(d2 in 3.0..3.6)

        // Beyond the segment end => distance to endpoint
        val e = Geo.pointSegmentDistance(0.2, 0.0, -0.1, 0.0, 0.1, 0.0)
        assertTrue(e in 11100.0..11200.0) // ~0.1 deg * 111.32 km
    }

    @Test
    fun pointSegmentDistance_usesLatitudeForLonScale() {
        // Point on the north-south segment: 0 regardless of scale.
        assertEquals(0.0, Geo.pointSegmentDistance(50.0001, 0.0, 50.0, 0.0, 50.001, 0.0), 1e-9)

        // ~0.0001 deg east of a north-south segment at lat 50 must scale by cos(50°):
        // 111320 * 0.0001 * cos(50°) ≈ 7.2 m. A cos(lon) bug returns ~11.1 m here.
        val d = Geo.pointSegmentDistance(50.0, 0.0001, 50.0, 0.0, 50.001, 0.0)
        assertTrue(d in 6.9..7.5)
    }
}