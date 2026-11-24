/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.state

import java.time.Instant

data class ExportPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val eleM: Double? = null,
    val hr: Int? = null,
    val cadence: Int? = null,
)

object RideExport {

    fun gpx(name: String, startedAtMs: Long, points: List<RidePoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Crazy Capy Randonneur\" xmlns=\"http://www.topografix.com/GPX/1/1\"")
        append(" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n")
        append("  <metadata><time>").append(Instant.ofEpochMilli(startedAtMs)).append("</time></metadata>\n")
        append("  <trk><name>").append(esc(name)).append("</name><type>cycling</type><trkseg>\n")
        points.forEach { p ->
            append("    <trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">")
            p.eleM?.let { append("<ele>").append(it).append("</ele>") }
            append("<time>").append(Instant.ofEpochMilli(p.timeMs)).append("</time>")
            if (p.hr != null || p.cadence != null) {
                append("<extensions><gpxtpx:TrackPointExtension>")
                p.hr?.let { append("<gpxtpx:hr>").append(it).append("</gpxtpx:hr>") }
                p.cadence?.let { append("<gpxtpx:cad>").append(it).append("</gpxtpx:cad>") }
                append("</gpxtpx:TrackPointExtension></extensions>")
            }
            append("</trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    fun tcx(
        name: String,
        startedAtMs: Long,
        totalSec: Long,
        distanceM: Double,
        points: List<ExportPoint>,
    ): String = buildString {
        val iso = { ms: Long -> Instant.ofEpochMilli(ms).toString() }
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\"><Activities><Activity Sport=\"Biking\"><Id>")
        append(esc(name)).append("</Id><Lap StartTime=\"").append(iso(startedAtMs))
        append("\"><TotalTimeSeconds>").append(totalSec)
        append("</TotalTimeSeconds><DistanceMeters>").append(distanceM)
        append("</DistanceMeters><Calories>0</Calories><Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod><Track>")
        points.forEach { p ->
            append("<Trackpoint><Time>").append(iso(p.timeMs)).append("</Time><Position><LatitudeDegrees>")
            append(p.lat).append("</LatitudeDegrees><LongitudeDegrees>").append(p.lon)
            append("</LongitudeDegrees></Position>")
            p.eleM?.let { append("<AltitudeMeters>").append(it).append("</AltitudeMeters>") }
            p.hr?.let { append("<HeartRateBpm><Value>").append(it).append("</Value></HeartRateBpm>") }
            p.cadence?.let { append("<Cadence>").append(it).append("</Cadence>") }
            append("</Trackpoint>")
        }
        append("</Track></Lap></Activity></Activities></TrainingCenterDatabase>\n")
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
