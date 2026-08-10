/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.gpx

import java.io.Writer

/**
 * Serializes a [Track] back out to GPX 1.1 XML round-trip (points + waypoints),
 * so routes can be stored on disk and re-loaded later. Pure Kotlin.
 */
object GpxWriter {

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun write(track: Track, out: Writer) {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.write("<gpx version=\"1.1\" creator=\"Crazy Capy Randonneur\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        out.write("  <metadata><name>${esc(track.name)}</name></metadata>\n")
        track.waypoints.forEach { w ->
            out.write("  <wpt lat=\"${w.lat}\" lon=\"${w.lon}\"><name>${esc(w.name)}</name></wpt>\n")
        }
        out.write("  <trk><name>${esc(track.name)}</name><trkseg>\n")
        track.points.forEach { p ->
            out.write("    <trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
            if (p.ele != null) out.write("<ele>${p.ele}</ele>")
            out.write("</trkpt>\n")
        }
        out.write("  </trkseg></trk>\n")
        out.write("</gpx>\n")
    }
}