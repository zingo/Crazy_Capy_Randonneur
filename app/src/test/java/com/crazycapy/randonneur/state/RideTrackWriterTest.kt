/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.state

import org.junit.Assert.assertTrue
import org.junit.Test

class RideTrackWriterTest {

    private val points = listOf(
        RidePoint(
            lat = 55.1,
            lon = 14.2,
            timeMs = 1_700_000_000_000,
            eleM = 12.5,
            hr = 142,
            cadence = 86,
            powerWatts = 214,
        ),
    )

    @Test
    fun gpxWritesSensorExtensionsInValidNamespace() {
        val xml = RideTrackWriter.gpx("Test ride", 1_700_000_000_000, points)
        assertTrue(xml.contains("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v2\""))
        assertTrue(xml.contains("<gpxtpx:hr>142</gpxtpx:hr>"))
        assertTrue(xml.contains("<gpxtpx:cad>86</gpxtpx:cad>"))
        assertTrue(xml.contains("<gpxtpx:power>214</gpxtpx:power>"))
        assertTrue(xml.contains("<ele>12.5</ele>"))
        assertTrue(xml.contains("<time>2023-11-14T22:13:20Z</time>"))
    }

    @Test
    fun gpxOmitsExtensionsWhenNoSensorData() {
        val xml = RideTrackWriter.gpx(
            "Plain ride",
            1_700_000_000_000,
            listOf(RidePoint(55.1, 14.2, 1_700_000_000_000)),
        )
        assertTrue(!xml.contains("<extensions>"))
    }

    @Test
    fun tcxWritesCadenceAndWatts() {
        val xml = RideTrackWriter.tcx("Test ride", 1_700_000_000_000, 3600, 42_000.0, points)
        assertTrue(xml.contains("<Cadence>86</Cadence>"))
        assertTrue(
            xml.contains(
                "<Extensions><TPX xmlns=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\"><Watts>214</Watts></TPX></Extensions>",
            ),
        )
        assertTrue(xml.contains("<HeartRateBpm><Value>142</Value></HeartRateBpm>"))
        assertTrue(xml.contains("<TotalTimeSeconds>3600</TotalTimeSeconds>"))
    }

    @Test
    fun tcxOmitsSensorFieldsWhenAbsent() {
        val xml = RideTrackWriter.tcx(
            "Plain ride",
            1_700_000_000_000,
            60,
            1000.0,
            listOf(RidePoint(55.1, 14.2, 1_700_000_000_000)),
        )
        assertTrue(!xml.contains("<Cadence>"))
        assertTrue(!xml.contains("<Watts>"))
    }
}
