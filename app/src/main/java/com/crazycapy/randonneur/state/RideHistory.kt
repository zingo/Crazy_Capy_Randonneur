/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.state

import android.content.Context
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    private fun metaFile(context: Context) = File(dir(context), "inprogress.meta")
    private fun rideFile(context: Context, id: String) = File(dir(context), "$id.gpx")

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

    /** Periodic crash-safe snapshot; does not touch the index until final save. */
    fun checkpoint(
        context: Context,
        points: List<Pair<Double, Double>>,
        startedAtMs: Long,
        durationSec: Long,
        distanceM: Double,
        maxKmh: Double,
    ) {
        if (points.size < 2 || distanceM < 20.0) return
        val ride = buildRide(context, points, startedAtMs, durationSec, distanceM, maxKmh) ?: return
        atomicWrite(metaFile(context), ride.toLine() + "\n")
    }

    fun clearCheckpoint(context: Context) {
        runCatching { metaFile(context).delete() }
    }

    /** Promote an unfinished snapshot (e.g. after a crash) into the history. */
    fun recoverInProgress(context: Context): SavedRide? = runCatching {
        val m = metaFile(context)
        if (!m.exists()) return null
        val ride = m.readLines().firstOrNull()?.let(::parseLine) ?: return null
        if (rideFile(context, ride.id).exists() && !indexFile(context).let { f -> f.exists() && f.readLines().any { it.startsWith(ride.id + "\t") } }) {
            val old = indexFile(context).takeIf { it.exists() }?.readLines().orEmpty()
            atomicWrite(indexFile(context), (listOf(ride.toLine()) + old).joinToString("\n"))
        }
        m.delete()
        ride
    }.getOrNull()

    fun save(
        context: Context,
        points: List<Pair<Double, Double>>,
        startedAtMs: Long,
        durationSec: Long,
        distanceM: Double,
        maxKmh: Double = 0.0,
    ): SavedRide? {
        val ride = buildRide(context, points, startedAtMs, durationSec, distanceM, maxKmh) ?: return null
        runCatching {
            val old = indexFile(context).takeIf { it.exists() }?.readLines()?.filterNot { it.startsWith(ride.id + "\t") }.orEmpty()
            atomicWrite(indexFile(context), (listOf(ride.toLine()) + old).joinToString("\n"))
        }
        clearCheckpoint(context)
        return ride
    }

    private fun buildRide(
        context: Context,
        points: List<Pair<Double, Double>>,
        startedAtMs: Long,
        durationSec: Long,
        distanceM: Double,
        maxKmh: Double,
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
        runCatching { atomicWrite(rideFile(context, id), gpx) }.getOrElse { return null }
        val avg = if (durationSec > 0) distanceM / durationSec * 3.6 else 0.0
        return SavedRide(id, name, startedAtMs, durationSec, distanceM, avg, maxKmh)
    }

    fun loadTrack(context: Context, id: String): Track? = runCatching {
        val f = rideFile(context, id)
        if (!f.exists()) return null
        TrackLoader.loadString(f.readText(), f.nameWithoutExtension)
    }.getOrNull()

    fun delete(context: Context, id: String) {
        runCatching { rideFile(context, id).delete() }
        runCatching {
            val kept = indexFile(context).readLines().filterNot { it.startsWith("$id\t") }
            indexFile(context).writeText(kept.joinToString("\n"))
        }
    }

    private fun parseLine(line: String): SavedRide? {
        val p = line.split("\t")
        if (p.size < 6) return null
        return SavedRide(
            p[0], p[1],
            p[2].toLongOrNull() ?: return null,
            p[3].toLongOrNull() ?: return null,
            p[4].toDoubleOrNull() ?: return null,
            p[5].toDoubleOrNull() ?: return null,
            p.getOrNull(6)?.toDoubleOrNull() ?: 0.0,
        )
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        runCatching {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun SavedRide.toLine() =
        listOf(id, name, startedAtMs.toString(), durationSec.toString(), distanceM.toString(), avgKmh.toString(), maxKmh.toString()).joinToString("\t")
}
