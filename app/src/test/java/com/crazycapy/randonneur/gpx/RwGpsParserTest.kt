/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RwGpsParserTest {

    private fun json(trackPoints: String, pois: String = "") =
        """
        {
          "name": "300K Lund 2022",
          "distance": 301768.0,
          "track_points": [$trackPoints],
          "points_of_interest": [$pois]
        }
        """.trimIndent()

    private val twoPoints = """{"x":13.20362,"y":55.72064,"e":65.2},{"x":13.2036,"y":55.72056,"e":65.0}"""

    @Test
    fun buildsTrackFromTrackPoints() {
        val track = RwGpsParser.parse(json(twoPoints))
        assertNotNull(track)
        assertEquals("300K Lund 2022", track!!.name)
        assertEquals(2, track.points.size)
        assertEquals(55.72064, track.points[0].lat, 1e-9)
        assertEquals(13.20362, track.points[0].lon, 1e-9)
        assertEquals(65.2, track.points[0].ele!!, 1e-9)
    }

    @Test
    fun liftsNamedControlsIntoWaypoints() {
        val control = """{"lng":14.4958,"lat":56.1704,"name":"Näsum kyrka 125K","description":"control","poi_type_name":"control"}"""
        val track = RwGpsParser.parse(json(twoPoints, control))
        assertNotNull(track)
        assertEquals(1, track!!.waypoints.size)
        val w = track.waypoints.first()
        assertEquals("Näsum kyrka 125K", w.name)
        assertEquals(56.1704, w.lat, 1e-9)
        assertEquals("control", w.description)
    }

    @Test
    fun unnamedOrUnlocatedPoisAreSkipped() {
        val odd = """{"lng":14.1,"lat":56.2},{"name":"no coords"}"""
        val track = RwGpsParser.parse(json(twoPoints, odd))
        assertNotNull(track)
        assertEquals(0, track!!.waypoints.size)
    }

    @Test
    fun rejectsJunkAndDegenerateTracks() {
        assertNull(RwGpsParser.parse("not json at all"))
        assertNull(RwGpsParser.parse(json("""{"x":13.2,"y":55.7}""")))
    }

    @Test
    fun parsesUserRouteList() {
        val list = RwGpsParser.parseRouteList(
            """""" +
                """[{"id":56494755,"name":"Ystad-Önneköpinge-Harlösa","distance":170815.0},""" +
                """{"id":56494486,"name":"Ystad-Trelleborg","distance":168537.0}]"""
        )
        assertEquals(2, list.size)
        assertEquals("56494755", list[0].id)
        assertEquals("Ystad-Önneköpinge-Harlösa", list[0].name)
        assertEquals(170815.0, list[0].distanceM, 0.0)
    }

    @Test
    fun ignoresMalformedListEntries() {
        val list = RwGpsParser.parseRouteList("""[{"name":"no id"},{"id":1}]""")
        assertEquals(1, list.size)
        assertEquals("1", list[0].id)
    }
}
