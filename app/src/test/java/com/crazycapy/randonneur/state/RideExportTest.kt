/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RideExportTest {

    private val started = Instant.parse("2026-11-24T08:00:00Z").toEpochMilli()
    private val points = listOf(
        RidePoint(50.1, 8.1, started, 120.0, 142, 86),
        RidePoint(50.2, 8.2, started + 10_000, 121.0, 145, 88),
    )

    @Test
    fun gpxWritesHeartRateAndCadence() {
        val xml = RideExport.gpx("Morning Ride", started, points)

        assertTrue(xml.contains("<gpxtpx:hr>142</gpxtpx:hr>"))
        assertTrue(xml.contains("<gpxtpx:cad>86</gpxtpx:cad>"))
        assertTrue(xml.contains("<gpxtpx:cad>88</gpxtpx:cad>"))
    }

    @Test
    fun gpxOmitsEmptyExtension() {
        val xml = RideExport.gpx(
            "Plain Ride",
            started,
            listOf(RidePoint(50.1, 8.1, started)),
        )

        assertFalse(xml.contains("<extensions>"))
        assertFalse(xml.contains("gpxtpx:cad"))
        assertFalse(xml.contains("gpxtpx:hr"))
    }

    @Test
    fun tcxWritesHeartRateAndCadence() {
        val xml = RideExport.tcx(
            name = "Morning Ride",
            startedAtMs = started,
            totalSec = 600,
            distanceM = 4200.0,
            points = points.map {
                ExportPoint(it.timeMs, it.lat, it.lon, it.eleM, it.hr, it.cadence)
            },
        )

        assertTrue(xml.contains("<Cadence>86</Cadence>"))
        assertTrue(xml.contains("<Cadence>88</Cadence>"))
        assertTrue(xml.contains("<HeartRateBpm><Value>142</Value></HeartRateBpm>"))
        assertTrue(xml.contains("<TotalTimeSeconds>600</TotalTimeSeconds>"))
    }
}
