/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxParserTest {

    @Test fun parsesTrackPointsWithElevation() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1" creator="test">
              <wpt lat="53.0012" lon="10.002"><name>Canal Bridge</name></wpt>
              <wpt lat="53.0018" lon="10.002"><name>Summit</name></wpt>
              <trk>
                <name>Morning Loop</name>
                <trkseg>
                  <trkpt lat="53.001" lon="10.002"><ele>45.2</ele></trkpt>
                  <trkpt lat="53.002" lon="10.002"><ele>50.0</ele></trkpt>
                  <trkpt lat="53.003" lon="10.002"><ele>48.5</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val track = GpxParser().parse("fallback", gpx.byteInputStream())
        assertEquals(3, track.points.size)
        assertEquals("Morning Loop", track.name)
        assertEquals(53.001, track.points[0].lat, 1e-9)
        assertEquals(10.002, track.points[0].lon, 1e-9)
        assertEquals(45.2, track.points[0].ele!!, 1e-9)
        assertTrue(track.lengthMeters > 200.0)

        // Waypoints collected as POIs
        assertEquals(2, track.waypoints.size)
        assertEquals("Canal Bridge", track.waypoints[0].name)
        assertEquals("Summit", track.waypoints[1].name)
    }

    @Test fun fallsBackToRouteTypeAndFileName() {
        val gpx = """
            <gpx version="1.0">
              <rte>
                <rtept lat="52.0" lon="7.0"/>
                <rtept lat="52.01" lon="7.0"/>
              </rte>
            </gpx>
        """.trimIndent()

        val track = GpxParser().parse("my-file", gpx.byteInputStream())
        assertEquals("my-file", track.name)
        assertEquals(2, track.points.size)
        assertTrue(track.lengthMeters > 900.0)
    }

    @Test fun rejectsTrackWithFewerThanTwoPoints() {
        val gpx = """
            <gpx><trk><trkseg><trkpt lat="52.0" lon="7.0"/></trkseg></trk></gpx>
        """.trimIndent()
        try {
            GpxParser().parse(null, gpx.byteInputStream())
            assertTrue("expected ParseException", false)
        } catch (expected: GpxParser.ParseException) {
            // ok
        }
    }
}