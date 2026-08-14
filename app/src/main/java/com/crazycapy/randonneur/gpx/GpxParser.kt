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
 *   GPX 1.0 / 1.1  (trkseg / trkpt / wpt, rte / rtept fallback)
 *   TCX (Course / Activity Track / Trackpoint / Position)
 *   KML (Placemark LineString coordinates)
 */
package com.crazycapy.randonneur.gpx

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

/**
 * Multipurpose GPS track parser: reads GPX (`<trk><trkseg><trkpt>` or
 * `<rte><rtept>` with `<wpt>` POIs), TCX (`<Trackpoint><Position>`), and KML
 * (`<LineString><coordinates>`). Based on KXmlParser so it works in both the
 * Android runtime and plain JVM unit tests; tag matching is case-insensitive
 * (TCX uses capitalized tags).
 */
class GpxParser {

    class ParseException(message: String) : Exception(message)

    private val points = ArrayList<TrackPoint>(4096)
    private val waypoints = ArrayList<Waypoint>(64)
    private var trackName: String? = null

    private var inTrk = false
    private var inTrkSeg = false
    private var inRte = false
    private var inPoint = false
    private var inWpt = false
    private var inWptName = false
    private var inTrackpoint = false
    private var inPosition = false
    private var inLineString = false

    private var lat = Double.NaN
    private var lon = Double.NaN
    private var ele: Double? = null
    private var wptLat = 0.0
    private var wptLon = 0.0
    private var wptNameBuilder: StringBuilder? = null
    private var wptDescBuilder: StringBuilder? = null
    private var inWptDesc = false

    /** Raw text of a numeric leaf (ele, LatitudeDegrees, AltitudeMeters…). */
    private var doubleText: StringBuilder? = null

    /** Raw text of a KML `<coordinates>` blob. */
    private var coordsText: StringBuilder? = null

    fun parse(name: String?, input: java.io.InputStream): Track {
        points.clear()
        waypoints.clear()
        trackName = null
        reset()

        val root = KXmlParser()
        root.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        root.setInput(input, "UTF-8")

        var event = root.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val tag = root.name?.lowercase()
            when (event) {
                XmlPullParser.START_TAG -> when (tag) {
                    "trk" -> inTrk = true
                    "trkseg" -> if (inTrk) inTrkSeg = true
                    "rte" -> inRte = true
                    "wpt" -> {
                        wptLat = num(root, "lat")
                        wptLon = num(root, "lon")
                        wptNameBuilder = null
                        wptDescBuilder = null
                        inWptDesc = false
                        inWpt = true
                    }
                    "name" -> if (inWpt) {
                        inWptName = true
                        wptNameBuilder = StringBuilder()
                    } else if (trackName == null) {
                        trackName = root.nextText()
                    }
                    "desc", "cmt" -> if (inWpt && !inWptDesc) {
                        inWptDesc = true
                        wptDescBuilder = StringBuilder()
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
                    "ele" -> if (!inWpt) beginDouble()
                    "trackpoint" -> {
                        lat = Double.NaN
                        lon = Double.NaN
                        ele = null
                        inPosition = false
                        inTrackpoint = true
                    }
                    "position" -> if (inTrackpoint) {
                        lat = Double.NaN
                        lon = Double.NaN
                        inPosition = true
                    }
                    "latitudedegrees" -> if (inPosition) beginDouble()
                    "longitudedegrees" -> if (inPosition) beginDouble()
                    "altitudemeters" -> if (inTrackpoint) beginDouble()
                    "linestring" -> inLineString = true
                    "coordinates" -> if (inLineString) coordsText = StringBuilder()
                }
                XmlPullParser.TEXT -> {
                    if (inWptName) wptNameBuilder?.append(root.text)
                    else if (inWptDesc) wptDescBuilder?.append(root.text)
                    else if (doubleText != null) doubleText!!.append(root.text)
                    else if (coordsText != null) coordsText!!.append(root.text)
                }
                XmlPullParser.END_TAG -> when (tag) {
                    "ele" -> if (!inWpt) ele = endDouble()
                    "latitudedegrees" -> if (inPosition) endDouble()?.let { lat = it }
                    "longitudedegrees" -> if (inPosition) endDouble()?.let { lon = it }
                    "altitudemeters" -> if (inTrackpoint) endDouble()?.let { ele = it }
                    "trkpt", "rtept" -> if (inPoint) {
                        points.add(TrackPoint(lat, lon, ele))
                        inPoint = false
                    }
                    "trackpoint" -> if (inTrackpoint) {
                        if (lat.isFinite() && lon.isFinite()) points.add(TrackPoint(lat, lon, ele))
                        inTrackpoint = false
                        inPosition = false
                    }
                    "position" -> inPosition = false
                    "coordinates" -> if (inLineString) {
                        parseCoordinates()
                        coordsText = null
                    }
                    "name" -> if (inWpt) inWptName = false
                    "desc", "cmt" -> if (inWpt) inWptDesc = false
                    "wpt" -> {
                        val n = wptNameBuilder?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        val d = wptDescBuilder?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        waypoints.add(Waypoint(n ?: "Waypoint", wptLat, wptLon, d))
                        wptNameBuilder = null
                        wptDescBuilder = null
                        inWptDesc = false
                        inWpt = false
                    }
                    "trk" -> inTrk = false
                    "trkseg" -> inTrkSeg = false
                    "rte" -> inRte = false
                    "linestring" -> inLineString = false
                }
            }
            event = root.next()
        }

        if (points.size < 2) throw ParseException("No track with at least two points found")
        return Track(trackName ?: name ?: "Route", points, waypoints)
    }

    private fun reset() {
        inTrk = false
        inTrkSeg = false
        inRte = false
        inPoint = false
        inWpt = false
        inWptName = false
        inTrackpoint = false
        inPosition = false
        inLineString = false
        lat = Double.NaN
        lon = Double.NaN
        ele = null
        wptLat = 0.0
        wptLon = 0.0
        wptNameBuilder = null
        wptDescBuilder = null
        inWptDesc = false
        doubleText = null
        coordsText = null
    }

    private fun beginDouble() {
        doubleText = StringBuilder()
    }

    private fun endDouble(): Double? {
        val v = doubleText?.toString()?.trim()?.toDoubleOrNull()
        doubleText = null
        return v
    }

    /** Parse a KML `<coordinates>` blob: space-separated `lon,lat[,alt]` triples. */
    private fun parseCoordinates() {
        val text = coordsText?.toString() ?: return
        text.split(Regex("\\s+")).forEach { tuple ->
            val parts = tuple.split(",")
            if (parts.size >= 2) {
                val lonV = parts[0].toDoubleOrNull()
                val latV = parts[1].toDoubleOrNull()
                val alt = parts.getOrNull(2)?.toDoubleOrNull()
                if (lonV != null && latV != null) points.add(TrackPoint(latV, lonV, alt))
            }
        }
    }

    private fun num(root: XmlPullParser, attr: String): Double =
        root.getAttributeValue(null, attr)?.toDoubleOrNull()
            ?: throw ParseException("Missing/invalid $attr attribute")
}