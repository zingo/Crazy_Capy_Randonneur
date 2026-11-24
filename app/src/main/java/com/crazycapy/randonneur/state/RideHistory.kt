/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.state

import android.content.Context
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackLoader
import com.crazycapy.randonneur.nav.Geo
import java.time.Instant
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device library of completed rides. While riding, an append-only log
 * (one point per line) plus a tiny atomic meta line is checkpointed every few
 * minutes; on Stop/recovery the log becomes a standard GPX 1.1 file with time,
 * elevation and Garmin TrackPointExtension heart rate.
 */
object RideHistory {

    data class SavedRide(
        val id: String,
        val name: String,
        val startedAtMs: Long,
        val durationSec: Long,
        val distanceM: Double,
        val avgKmh: Double,
        val maxKmh: Double = 0.0,
        val ascentM: Double = 0.0,
        val descentM: Double = 0.0,
        val totalSec: Long = durationSec,
        val stops: Int = 0,
        val longestStopSec: Long = 0,
    )

    private fun dir(context: Context): File = File(context.filesDir, "rides").apply { mkdirs() }
    private fun indexFile(context: Context) = File(dir(context), "index.txt")
    private fun metaFile(context: Context) = File(dir(context), "inprogress.meta")
    private fun logFile(context: Context) = File(dir(context), "inprogress.log")
    private fun rideFile(context: Context, id: String) = File(dir(context), "$id.gpx")

    fun list(context: Context): List<SavedRide> = runCatching {
        val f = indexFile(context)
        if (!f.exists()) emptyList()
        else f.readLines().mapNotNull(::parseLine).sortedByDescending { it.startedAtMs }
    }.getOrDefault(emptyList())

    /** Append new points + refresh the tiny meta line. Cheap (append-only). */
    fun checkpoint(
        context: Context,
        startedAtMs: Long,
        newPoints: List<RidePoint>,
        durationSec: Long,
        distanceM: Double,
        maxKmh: Double,
    ) {
        if (newPoints.isNotEmpty()) {
            val sb = StringBuilder()
            newPoints.forEach { p ->
                sb.append(p.timeMs).append(',')
                    .append(p.lat).append(',')
                    .append(p.lon).append(',')
                    .append(p.eleM?.toString() ?: "").append(',')
                    .append(p.hr?.toString() ?: "").append(',')
                    .append(p.cadence?.toString() ?: "").append('\n')
            }
            runCatching { logFile(context).appendText(sb.toString()) }
        }
        val avg = if (durationSec > 0) distanceM / durationSec * 3.6 else 0.0
        val name = defaultName(startedAtMs)
        val ride = SavedRide("ride-$startedAtMs", name, startedAtMs, durationSec, distanceM, avg, maxKmh)
        atomicWrite(metaFile(context), ride.toLine() + "\n")
    }

    fun abort(context: Context) {
        runCatching { logFile(context).delete() }
        runCatching { metaFile(context).delete() }
    }

    /** Turn the appended log into the final standard GPX + history entry. */
    fun finalizeRide(
        context: Context,
        fallbackStartedAtMs: Long,
        fallbackDurationSec: Long,
        fallbackDistanceM: Double,
        fallbackMaxKmh: Double,
        fallbackTotalSec: Long = 0L,
    ): SavedRide? {
        val meta = metaFile(context).takeIf { it.exists() }
            ?.readLines()?.firstOrNull()?.let(::parseLine)
        val log = logFile(context)
        if (!log.exists()) { metaFile(context).delete(); return null }
        val points = runCatching { log.readLines().mapNotNull(::parseLogLine) }.getOrDefault(emptyList())
        if (points.size < 2) { abort(context); return null }

        val started = meta?.startedAtMs ?: fallbackStartedAtMs.takeIf { it > 0 } ?: points.first().timeMs
        val dist = (meta?.distanceM ?: fallbackDistanceM).takeIf { it > 20.0 } ?: Geo.run {
            points.zipWithNext().sumOf { (a, b) -> distanceMeters(a.lat, a.lon, b.lat, b.lon) }
        }
        if (dist < 20.0) { abort(context); return null }
        val dur = (meta?.durationSec ?: fallbackDurationSec).takeIf { it > 0 }
            ?: ((points.last().timeMs - points.first().timeMs) / 1000).coerceAtLeast(1)
        val max = (meta?.maxKmh ?: fallbackMaxKmh).coerceAtLeast(0.0)
        val name = meta?.name ?: defaultName(started)
        val avg = dist / dur * 3.6
        var ascent = 0.0
        var descent = 0.0
        var prevEle: Double? = null
        points.forEach { pt ->
            val e = pt.eleM
            if (e != null) {
                prevEle?.let { pe ->
                    val d = e - pe
                    if (d >= 1.0) ascent += d else if (d <= -1.0) descent -= d
                }
                prevEle = e
            }
        }
        val total = (meta?.totalSec ?: fallbackTotalSec).takeIf { it > 0 } ?: dur
        var stops = 0
        var longestStop = 0L
        points.zipWithNext().forEach { (a, b) ->
            val gap = (b.timeMs - a.timeMs) / 1000
            if (gap >= 10) {
                stops++
                if (gap > longestStop) longestStop = gap
            }
        }
        val ride = SavedRide("ride-$started", name, started, dur, dist, avg, max, ascent, descent, total, stops, longestStop)

        runCatching { atomicWrite(rideFile(context, ride.id), RideExport.gpx(name, started, points)) }.getOrElse { return null }
        runCatching {
            val old = indexFile(context).takeIf { it.exists() }?.readLines()
                ?.filterNot { it.startsWith(ride.id + "\t") }.orEmpty()
            atomicWrite(indexFile(context), (listOf(ride.toLine()) + old).joinToString("\n"))
        }
        abort(context)
        return ride
    }

    /** Promote an unfinished snapshot (e.g. after a crash) into the history. */
    fun recoverInProgress(context: Context): SavedRide? {
        if (!logFile(context).exists()) { metaFile(context).delete(); return null }
        return finalizeRide(context, 0L, 0L, 0.0, 0.0, 0L)
    }

    data class SeriesPoint(val tMs: Long, val lat: Double, val lon: Double, val eleM: Double?, val hr: Int?, val speedKmh: Double, val cadence: Int? = null)

    /** Parse a saved ride's GPX into a time/elevation/HR/speed series for charts. */
    fun loadSeries(context: Context, id: String): List<SeriesPoint> = runCatching {
        val f = rideFile(context, id)
        if (!f.exists()) return emptyList()
        val text = f.readText()
        val rx = Regex("<trkpt[^>]*lat=\"([^\"]+)\"[^>]*lon=\"([^\"]+)\"[^>]*>(.*?)</trkpt>", RegexOption.DOT_MATCHES_ALL)
        val out = ArrayList<SeriesPoint>()
        var prevLat: Double? = null
        var prevLon: Double? = null
        var prevT: Long? = null
        for (m in rx.findAll(text)) {
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            val body = m.groupValues[3]
            val ele = Regex("<ele>([-0-9.]+)</ele>").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            val t = Regex("<time>([^<]+)</time>").find(body)?.groupValues?.get(1)
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: continue
            val hr = Regex("<gpxtpx:hr>(\\d+)</gpxtpx:hr>").find(body)?.groupValues?.get(1)?.toIntOrNull()
            val cadence = Regex("<gpxtpx:cad>(\\d+)</gpxtpx:cad>").find(body)?.groupValues?.get(1)?.toIntOrNull()
            var speed = 0.0
            if (prevLat != null && prevLon != null && prevT != null && t > prevT!!) {
                speed = Geo.distanceMeters(prevLat!!, prevLon!!, lat, lon) / ((t - prevT!!) / 1000.0) * 3.6
            }
            out.add(SeriesPoint(t, lat, lon, ele, hr, speed.coerceIn(0.0, 120.0), cadence))
            prevLat = lat; prevLon = lon; prevT = t
        }
        out
    }.getOrDefault(emptyList())

    fun exportTcx(context: Context, id: String): File? = runCatching {
        val ride = list(context).firstOrNull { it.id == id } ?: return null
        val pts = loadSeries(context, id)
        if (pts.size < 2) return null
        val xml = RideExport.tcx(
            name = ride.name,
            startedAtMs = ride.startedAtMs,
            totalSec = ride.totalSec,
            distanceM = ride.distanceM,
            points = pts.map {
                ExportPoint(it.tMs, it.lat, it.lon, it.eleM, it.hr, it.cadence)
            },
        )
        val f = File(dir(context), "$id.tcx")
        atomicWrite(f, xml)
        f
    }.getOrNull()

    fun loadTrack(context: Context, id: String): Track? = runCatching {
        val f = rideFile(context, id)
        if (!f.exists()) return null
        TrackLoader.loadString(f.readText(), f.nameWithoutExtension)
    }.getOrNull()

    fun fileFor(context: Context, id: String): File? = rideFile(context, id).takeIf { it.exists() }

    fun renameRide(context: Context, id: String, newName: String) {
        val name = newName.trim().ifBlank { return }
        val rides = list(context)
        val ride = rides.firstOrNull { it.id == id } ?: return
        val updated = ride.copy(name = name)
        val lines = indexFile(context).readLines().map { line ->
            if (line.startsWith("$id\t")) updated.toLine() else line
        }
        atomicWrite(indexFile(context), lines.joinToString("\n"))
        val f = rideFile(context, id)
        if (f.exists()) {
            val xml = f.readText().replaceFirst(
                Regex("<name>.*?</name>"),
                "<name>" + esc(name) + "</name>",
            )
            atomicWrite(f, xml)
        }
    }

    fun delete(context: Context, id: String) {
        runCatching { rideFile(context, id).delete() }
        runCatching {
            val kept = indexFile(context).readLines().filterNot { it.startsWith("$id\t") }
            indexFile(context).writeText(kept.joinToString("\n"))
        }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun defaultName(startedAtMs: Long): String =
        "Ride " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(startedAtMs))

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
            p.getOrNull(7)?.toDoubleOrNull() ?: 0.0,
            p.getOrNull(8)?.toDoubleOrNull() ?: 0.0,
            p.getOrNull(9)?.toLongOrNull() ?: (p[3].toLongOrNull() ?: 0L),
            p.getOrNull(10)?.toIntOrNull() ?: 0,
            p.getOrNull(11)?.toLongOrNull() ?: 0L,
        )
    }

    private fun parseLogLine(line: String): RidePoint? {
        val p = line.split(",", limit = 6)
        if (p.size < 3) return null
        return RidePoint(
            lat = p[1].toDoubleOrNull() ?: return null,
            lon = p[2].toDoubleOrNull() ?: return null,
            timeMs = p[0].toLongOrNull() ?: return null,
            eleM = p.getOrNull(3)?.toDoubleOrNull(),
            hr = p.getOrNull(4)?.toIntOrNull(),
            cadence = p.getOrNull(5)?.toIntOrNull(),
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
        listOf(
            id, name, startedAtMs.toString(), durationSec.toString(), distanceM.toString(),
            avgKmh.toString(), maxKmh.toString(), ascentM.toString(), descentM.toString(),
            totalSec.toString(), stops.toString(), longestStopSec.toString(),
        ).joinToString("\t")
}
