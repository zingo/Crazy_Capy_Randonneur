/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.debug

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.crazycapy.randonneur.CACHED_IMG_PX
import com.crazycapy.randonneur.STYLE_DARK
import com.crazycapy.randonneur.STYLE_LIGHT
import com.crazycapy.randonneur.cache.ReusedSnapshotter
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.nav.TurnFinder
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.io.File

/**
 * Debug-build-only micro-benchmark: repeatedly renders turn snapshots ("live")
 * or decodes + draws stored previews ("cached"), or idles, writing wall/CPU
 * timings to `files/bench.done`. Never compiled into release builds.
 */
object BenchRunner {

    fun run(context: Context, track: Track, mode: String, iterations: Int, durationSec: Int = 0, sleepMs: Long = 0, targetRps: Int = 0) {
        val marker = File(context.filesDir, "bench.done")
        marker.delete()
        Thread {
            val wall0 = System.nanoTime()
            val cpu0 = cpuMs()
            val deadline = if (durationSec > 0) wall0 + durationSec * 1_000_000_000L else Long.MAX_VALUE
            val nextAt = longArrayOf(System.nanoTime())
            var ok = 0
            var done = 0
            try {
                val dark = RideStore.darkMap
                val turns = TurnFinder.find(track)
                val routeId = RouteStore.routeId(track.name)
                if (turns.isEmpty()) {
                    write(marker, "$mode 0 0 0 0 no-turns")
                    return@Thread
                }
                if (mode == "idle") {
                    while (running(done, iterations, durationSec, deadline)) {
                        Thread.sleep(sleepMs.coerceAtLeast(1))
                        done++
                    }
                } else if (mode == "cached") {
                    while (running(done, iterations, durationSec, deadline)) {
                        val idx = idxFor(done, turns.size)
                        val ct = RouteCache.loadTurn(context, routeId, dark, idx)
                        if (ct != null) {
                            val dst = Bitmap.createBitmap(CACHED_IMG_PX, CACHED_IMG_PX, Bitmap.Config.ARGB_8888)
                            Canvas(dst).drawBitmap(ct.bitmap, 0f, 0f, null)
                            dst.recycle()
                            ok++
                        }
                        done++
                        if (sleepMs > 0) Thread.sleep(sleepMs)
                        pace(nextAt, targetRps)
                    }
                } else {
                    val renderer = ReusedSnapshotter(context)
                    renderer.create()
                    val style = if (dark) STYLE_DARK else STYLE_LIGHT
                    try {
                        while (running(done, iterations, durationSec, deadline)) {
                            val idx = idxFor(done, turns.size)
                            val bounds = boundsFor(track, turns[idx].distAlongM)
                            if (renderer.render(style, dark, bounds, CACHED_IMG_PX, CACHED_IMG_PX, CACHED_IMG_PX / 7, 20_000) != null) ok++
                            done++
                            if (sleepMs > 0) Thread.sleep(sleepMs)
                            pace(nextAt, targetRps)
                        }
                    } finally {
                        renderer.dispose()
                    }
                }
                val wallMs = (System.nanoTime() - wall0) / 1_000_000
                write(marker, "$mode $done $wallMs ${cpuMs() - cpu0} ${turns.size} ok=$ok")
            } catch (e: Exception) {
                Log.e("BenchRunner", "bench failed", e)
                write(marker, "$mode $done -1 -1 0 fail=${e.javaClass.simpleName}")
            }
        }.start()
    }

    private fun running(done: Int, iterations: Int, durationSec: Int, deadline: Long): Boolean =
        if (durationSec > 0) System.nanoTime() < deadline else done < iterations

    private fun pace(nextAt: LongArray, targetRps: Int) {
        if (targetRps <= 0) return
        nextAt[0] += 1_000_000_000L / targetRps
        val waitNs = nextAt[0] - System.nanoTime()
        if (waitNs > 0) Thread.sleep(waitNs / 1_000_000L) else nextAt[0] = System.nanoTime()
    }

    private fun idxFor(k: Int, n: Int): Int {
        if (n <= 1) return 0
        var stride = 97
        while (gcd(stride, n) != 1) stride++
        return ((k.toLong() * stride) % n).toInt()
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    private fun boundsFor(track: Track, atM: Double): LatLngBounds {
        val builder = LatLngBounds.Builder()
        val start = (atM - 150.0).coerceAtLeast(0.0)
        val end = (atM + 55.0).coerceAtMost(track.lengthMeters)
        var d = start
        var guard = 0
        while (d <= end && guard < 100) {
            val p = track.pointAtDistance(d)
            builder.include(LatLng(p.lat, p.lon))
            d += 10.0
            guard++
        }
        return builder.build()
    }

    private fun cpuMs(): Long = runCatching {
        val parts = File("/proc/self/stat").readText().trim().substringAfterLast(") ").split(" ")
        (parts[11].toLong() + parts[12].toLong()) * 10
    }.getOrDefault(0L)

    private fun write(file: File, line: String) {
        runCatching { file.writeText(line) }
        Log.i("BenchRunner", line)
    }
}
