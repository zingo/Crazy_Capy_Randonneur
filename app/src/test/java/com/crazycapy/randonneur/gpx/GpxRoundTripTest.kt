/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.gpx

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class GpxRoundTripTest {

    @Test
    fun roundTripsPointsAndWaypoints() {
        val track = Track(
            "Loop",
            listOf(
                TrackPoint(50.1, 8.1),
                TrackPoint(50.2, 8.2, 120.0),
                TrackPoint(50.3, 8.15),
            ),
            waypoints = listOf(Waypoint("Cafe", 50.15, 8.1, "Free coffee for riders")),
        )

        val sw = StringWriter()
        GpxWriter.write(track, sw)
        val parsed = GpxParser().parse("Loop", sw.toString().byteInputStream())

        assertEquals("Loop", parsed.name)
        assertEquals(track.points.size, parsed.points.size)
        for (i in track.points.indices) {
            assertEquals(track.points[i].lat, parsed.points[i].lat, 1e-9)
            assertEquals(track.points[i].lon, parsed.points[i].lon, 1e-9)
        }
        assertEquals(1, parsed.waypoints.size)
        assertEquals("Cafe", parsed.waypoints.first().name)
        assertEquals(50.15, parsed.waypoints.first().lat, 1e-9)
        assertEquals("Free coffee for riders", parsed.waypoints.first().description)

        // Geometry must survive: no length drift.
        assertEquals(track.lengthMeters, parsed.lengthMeters, 1e-6)
    }

    @Test
    fun reversedTrackWritesAndReads() {
        val original = Track(
            "A->B",
            listOf(TrackPoint(50.0, 8.0), TrackPoint(50.5, 8.5), TrackPoint(51.0, 8.4)),
        )
        val rev = original.reversed()
        val sw = StringWriter()
        GpxWriter.write(rev, sw)
        val parsed = GpxParser().parse(null, sw.toString().byteInputStream())
        assertEquals(rev.points.first().lat, parsed.points.first().lat, 1e-9)
        assertEquals(original.lengthMeters, parsed.lengthMeters, 1e-6)
    }
}