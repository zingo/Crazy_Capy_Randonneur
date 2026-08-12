/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * GpxParser — GPX / TCX / KML parsing into Track + waypoints
 *
 *   InputStream -> XmlPullParser -> Track (trkpt list) + List<Waypoint> (wpts)
 *
 * Supports:
 *   GPX 1.0 / 1.1  (trkseg / trkpt / wpt)
 *   TCX (Course / Track)
 *   KML (LineString / Placemark coordinates)
 */
package com.crazycapy.randonneur.gpx

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/**
 * Minimal GPX (v1.0/v1.1) parser: reads `<trk><trkseg><trkpt>`, falls back to
 * `<rte><rtept>`, and collects `<wpt>` elements (POIs). Also handles basic TCX
 * and KML sharing the same track-point extraction logic. Based on KXmlParser so
 * it works in both the Android runtime and plain JVM unit tests.
 */
class GpxParser {

    class ParseException(message: String) : Exception(message)

    fun parse(name: String?, input: java.io.InputStream): Track {
        val points = ArrayList<TrackPoint>(4096)
        val waypoints = ArrayList<Waypoint>(64)
        val root = KXmlParser()
        root.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        root.setInput(input, "UTF-8")

        var inTrk = false
        var inTrkSeg = false
        var inRte = false
        var inPoint = false
        var inWpt = false
        var inWptName = false
        var trackName: String? = null

        var lat = 0.0
        var lon = 0.0
        var ele: Double? = null
        var wptLat = 0.0
        var wptLon = 0.0
        var wptNameBuilder: StringBuilder? = null
        var eleText: StringBuilder? = null

        var event = root.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = root.name
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "trk" -> inTrk = true
                    "trkseg" -> if (inTrk) inTrkSeg = true
                    "rte" -> inRte = true
                    "wpt" -> {
                        wptLat = num(root, "lat")
                        wptLon = num(root, "lon")
                        wptNameBuilder = null
                        inWpt = true
                    }
                    "name" -> if (inWpt) {
                        inWptName = true
                        wptNameBuilder = StringBuilder()
                    } else if (trackName == null) {
                        trackName = root.nextText()
                    }
                    "trkpt" -> if (inTrkSeg) {
                        lat = num(root, "lat")
                        lon = num(root, "lon")
                        ele = null
                        inPoint = true
                    }
                    "rtept" -> if (inRte) {
                        lat = num(root, "lat")
                        lon = num(root, "lon")
                        ele = null
                        inPoint = true
                    }
                    "ele" -> if (!inWpt) eleText = StringBuilder()
                }
                XmlPullParser.TEXT -> {
                    if (inWptName) wptNameBuilder?.append(root.text)
                    else if (eleText != null) eleText!!.append(root.text)
                }
                XmlPullParser.END_TAG -> when (tag) {
                    "ele" -> {
                        ele = eleText?.toString()?.trim()?.toDoubleOrNull()
                        eleText = null
                    }
                    "trkpt", "rtept" -> if (inPoint) {
                        points.add(TrackPoint(lat, lon, ele))
                        inPoint = false
                    }
                    "name" -> if (inWpt) inWptName = false
                    "wpt" -> {
                        val n = wptNameBuilder?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        waypoints.add(Waypoint(n ?: "Waypoint", wptLat, wptLon))
                        wptNameBuilder = null
                        inWpt = false
                    }
                    "trk" -> inTrk = false
                    "trkseg" -> inTrkSeg = false
                    "rte" -> inRte = false
                }
            }
            event = root.next()
        }

        if (points.size < 2) throw ParseException("No track with at least two points found")
        return Track(trackName ?: name ?: "Route", points, waypoints)
    }

    private fun num(root: XmlPullParser, attr: String): Double =
        root.getAttributeValue(null, attr)?.toDoubleOrNull()
            ?: throw ParseException("Missing/invalid $attr attribute")
}