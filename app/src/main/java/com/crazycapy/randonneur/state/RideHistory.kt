/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.state

import android.content.Context
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-device library of completed rides (summary + recorded path as GPX). */
object RideHistory {

    data class SavedRide(
        val id: String,
        val name: String,
        val startedAtMs: Long,
        val durationSec: Long,
        val distanceM: Double,
        val avgKmh: Double,
        val maxKmh: Double = 0.0,
    )

    private fun dir(context: Context): File = File(context.filesDir, "rides").apply { mkdirs() }
    private fun indexFile(context: Context) = File(dir(context), "index.txt")

    fun list(context: Context): List<SavedRide> = runCatching {
        val f = indexFile(context)
        if (!f.exists()) emptyList()
        else f.readLines().mapNotNull { line ->
            val p = line.split("\t")
            if (p.size < 6) null
            else SavedRide(
                p[0], p[1],
                p[2].toLongOrNull() ?: return@mapNotNull null,
                p[3].toLongOrNull() ?: return@mapNotNull null,
                p[4].toDoubleOrNull() ?: return@mapNotNull null,
                p[5].toDoubleOrNull() ?: return@mapNotNull null,
                p.getOrNull(6)?.toDoubleOrNull() ?: 0.0,
            )
        }.sortedByDescending { it.startedAtMs }
    }.getOrDefault(emptyList())

    fun save(
        context: Context,
        points: List<Pair<Double, Double>>,
        startedAtMs: Long,
        durationSec: Long,
        distanceM: Double,
        maxKmh: Double = 0.0,
    ): SavedRide? {
        if (points.size < 2 || distanceM < 20.0) return null
        val id = "ride-$startedAtMs"
        val name = "Ride " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(startedAtMs))
        val gpx = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"Crazy Capy Randonneur\"><trk><name>")
            append(name.replace("&", "&amp;").replace("<", "&lt;"))
            append("</name><trkseg>\n")
            points.forEach { (lat, lon) ->
                append("<trkpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\"/>\n")
            }
            append("</trkseg></trk></gpx>\n")
        }
        runCatching { File(dir(context), "$id.gpx").writeText(gpx) }.getOrElse { return null }
        val avg = if (durationSec > 0) distanceM / durationSec * 3.6 else 0.0
        val ride = SavedRide(id, name, startedAtMs, durationSec, distanceM, avg, maxKmh)
        runCatching {
            val old = indexFile(context).takeIf { it.exists() }?.readLines().orEmpty()
            indexFile(context).writeText((listOf(ride.toLine()) + old).joinToString("\n"))
        }
        return ride
    }

    fun loadTrack(context: Context, id: String): Track? = runCatching {
        val f = File(dir(context), "$id.gpx")
        if (!f.exists()) return null
        TrackLoader.loadString(f.readText(), f.nameWithoutExtension)
    }.getOrNull()

    fun delete(context: Context, id: String) {
        runCatching { File(dir(context), "$id.gpx").delete() }
        runCatching {
            val kept = indexFile(context).readLines().filterNot { it.startsWith("$id\t") }
            indexFile(context).writeText(kept.joinToString("\n"))
        }
    }

    private fun SavedRide.toLine() =
        listOf(id, name, startedAtMs.toString(), durationSec.toString(), distanceM.toString(), avgKmh.toString(), maxKmh.toString()).joinToString("\t")
}
