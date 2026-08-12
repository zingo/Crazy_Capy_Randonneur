/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RouteCache — pre-renders turn previews and warms corridor tiles
 *
 * At load time:
 *   1. Walk every turn in the track
 *   2. Render a MapSnapshotter bitmap for each (PNG in app cache dir)
 *   3. Record lat/lon anchor points for bilinear projection
 *   4. Warm corridor tile cache so the main map has fewer fetches
 *
 * Fallback: if no cached image exists, NextTurnCard uses live snapshotting.
 */
package com.crazycapy.randonneur.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crazycapy.randonneur.CACHED_IMG_PX
import com.crazycapy.randonneur.STYLE_DARK
import com.crazycapy.randonneur.STYLE_LIGHT
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.nav.TurnFinder
import com.crazycapy.randonneur.voice.Phrases
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A cached turn-preview image plus its projection anchors. */
data class CachedTurn(
    val bitmap: Bitmap,
    val anchors: List<Anchor>,
)

/**
 * Offline materials for a saved route, prepared ahead of a ride ("home + charger")
 * so the battery-heavy work happens before hitting the road:
 *
 *  - **Turn preview images**: a small MapSnapshotter render of the corridor around
 *    each detected turn, saved as a PNG with a 3×3 grid of lat/lon -> pixel anchor
 *    samples. The HUD preview then draws those cached bitmaps instead of running a
 *    live offscreen snapshot at ride time.
 *  - **Corridor tile warming**: light snapshots along the whole route (thrown away)
 *    that pull the surrounding tiles into MapLibre's shared disk cache, so the main
 *    map and any live fallback snapshots fetch fewer tiles mid-ride. That tile cache
 *    is global across routes ("warm once, ride many").
 *
 * Everything lives under `filesDir/cache/routecache/<routeId>/`. Misses are fine: the
 * HUD falls back to a live snapshot; only the cached pieces save battery and network.
 */
object RouteCache {

    /** Human-readable progress headline while a pre-cache job runs, or null when idle. */
    var status: String? by mutableStateOf(null)

    /** Fraction 0..1 of the current route's turns rendered, or null when idle. */
    var progress: Float? by mutableStateOf(null)

    /** Route id currently being pre-cached, or null. */
    var activeRouteId: String? by mutableStateOf(null)

    private var cancelRequested = AtomicBoolean(false)

    private fun cacheDir(context: Context): File =
        File(context.filesDir, "cache/routecache").apply { mkdirs() }

    /** Directory holding one route's cached turn images (create if missing). */
    private fun routeDir(context: Context, routeId: String): File =
        File(cacheDir(context), routeId).apply { mkdirs() }

    private fun turnFile(context: Context, routeId: String, dark: Boolean, turnIndex: Int): File =
        File(routeDir(context, routeId), "turn-${if (dark) "dark" else "light"}-$turnIndex")

    private fun anchorFile(context: Context, routeId: String, dark: Boolean, turnIndex: Int): File =
        File(routeDir(context, routeId), "turn-${if (dark) "dark" else "light"}-$turnIndex-anchors")

    // ---- Lookup (HUD side) ----

    /** Load a pre-rendered turn preview, or null if not cached yet. */
    fun loadTurn(context: Context, routeId: String, dark: Boolean, turnIndex: Int): CachedTurn? = runCatching {
        val img = turnFile(context, routeId, dark, turnIndex)
        val anc = anchorFile(context, routeId, dark, turnIndex)
        if (!img.exists() || !anc.exists()) return null
        val bmp = BitmapFactory.decodeFile(img.absolutePath) ?: return null
        val anchors = anchorFile(context, routeId, dark, turnIndex)
            .readLines()
            .mapNotNull { line ->
                val p = line.split(' ')
                if (p.size != 4) null
                else Anchor(p[0].toDoubleOrNull() ?: return@mapNotNull null, p[1].toDoubleOrNull() ?: return@mapNotNull null, p[2].toFloatOrNull() ?: return@mapNotNull null, p[3].toFloatOrNull() ?: return@mapNotNull null)
            }
        if (anchors.size < TurnProjection.GRID * TurnProjection.GRID) return null
        CachedTurn(bmp, anchors)
    }.getOrNull()

    /** True when all detected turns of [track] have cached preview images for [dark]. */
    fun isCached(context: Context, routeId: String, track: Track?, dark: Boolean): Boolean {
        val t = track ?: return false
        val turns = TurnFinder.find(t).size
        if (turns == 0) return true
        var cached = 0
        for (i in 0 until turns) {
            if (turnFile(context, routeId, dark, i).exists()) cached++
        }
        return cached == turns
    }

    // ---- Sizing & cleanup (routes/settings UI) ----

    /** Total bytes on disk for one route's cached turn previews. */
    fun routeBytes(context: Context, routeId: String): Long =
        routeDir(context, routeId).listFiles()
            ?.filter { it.isFile && it.name.startsWith("turn-") }
            ?.sumOf { it.length() } ?: 0L

    /** Total bytes across all cached routes. */
    fun totalBytes(context: Context): Long =
        cacheDir(context).listFiles()?.filter { it.isDirectory }?.sumOf { routeBytes(context, it.name) } ?: 0L

    /** Delete one route's cached previews. */
    fun deleteRoute(context: Context, routeId: String) {
        runCatching { routeDir(context, routeId).deleteRecursively() }
    }

    /** Delete every cached route. */
    fun deleteAll(context: Context) {
        runCatching { cacheDir(context).deleteRecursively() }
    }

    // ---- Generation ----

    /** Cancel an in-flight pre-cache job (e.g. a ride just started). */
    fun cancel() {
        cancelRequested.set(true)
    }

    /**
     * Prepare a route's turn previews (and warm its corridor tiles) in the
     * background. Cheap to call repeatedly: misses or already-cached turns are
     * skipped. The job yields byte for byte to MapLibre's global tile cache so a
     * second route overlapping the same area warms a second time for free.
     */
    fun preCache(context: Context, track: Track) {
        val routeId = RouteStore.routeId(track.name)
        if (activeRouteId != null) cancel()
        cancelRequested.set(false)
        activeRouteId = routeId

        Thread(Runnable {
            Thread.currentThread().name = "route-precache"
            try {
                MapLibre.getInstance(context.applicationContext)
                val turns = TurnFinder.find(track)
                val darkBase = RideStore.darkMap
                for (dark in listOf(false, true)) {
                    val style = if (dark) STYLE_DARK else STYLE_LIGHT
                    var done = 0
                    for ((i, turn) in turns.withIndex()) {
                        if (cancelRequested.get() || RideStore.active) break
                        if (!turnFile(context, routeId, dark, i).exists()) {
                            status = "Pre-caching ${track.name} · turn ${i + 1}/${turns.size} (${if (dark) "dark" else "light"})"
                            renderAndStore(context, track, turn.distAlongM, dark, style, i, boundsForTurn(track, turn.distAlongM))
                        }
                        done++
                        progress = (done.toFloat() + (if (dark) turns.size else 0)) / (turns.size * 2f)
                    }
                    if (cancelRequested.get() || RideStore.active) break
                }
                if (!cancelRequested.get() && !RideStore.active) {
                    warmCorridor(context, track, if (darkBase) STYLE_DARK else STYLE_LIGHT)
                    status = "Pre-cached ${track.name} ✓"
                }
            } catch (_: Exception) {
                if (!cancelRequested.get()) status = "Pre-cache failed for ${track.name}"
            } finally {
                progress = null
                activeRouteId = null
                cancelRequested.set(false)
            }
        }).start()
    }

    /** Corridor around a turn (up to 55 m past it), for the preview bounds. */
    private fun boundsForTurn(track: Track, atM: Double): LatLngBounds {
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

    private fun renderAndStore(
        context: Context,
        track: Track,
        atM: Double,
        dark: Boolean,
        style: String,
        turnIndex: Int,
        bounds: LatLngBounds,
    ) {
        runCatching {
            val snap = MapSnapshotter(
                context.applicationContext,
                MapSnapshotter.Options(CACHED_IMG_PX, CACHED_IMG_PX)
                    .withStyle(style)
                    .withRegion(bounds)
                    .withPadding(CACHED_IMG_PX / 7, CACHED_IMG_PX / 7, CACHED_IMG_PX / 7, CACHED_IMG_PX / 7)
                    .withLogo(false)
                    .withAttribution(false),
            )
            if (dark) snap.brightenDarkRoads()
            snapshotSynchronous(snap)?.let { shot ->
                val routeId = RouteStore.routeId(track.name)
                val png = turnFile(context, routeId, dark, turnIndex)
                shot.bitmap.compress(Bitmap.CompressFormat.PNG, 100, png.outputStream())
                val anchors = TurnProjection.gridLatLon(
                    bounds.latitudeSouth, bounds.longitudeWest,
                    bounds.latitudeNorth, bounds.longitudeEast,
                ).map { (lat, lon) ->
                    val p = shot.pixelForLatLng(LatLng(lat, lon))
                    Anchor(lat, lon, p.x, p.y)
                }
                anchorFile(context, routeId, dark, turnIndex)
                    .writeText(anchors.joinToString("\n") { "${it.lat} ${it.lon} ${it.x} ${it.y}" })
                if (cancelRequested.get()) png.delete()
            }
        }
    }

    private fun snapshotSynchronous(snap: MapSnapshotter): org.maplibre.android.snapshotter.MapSnapshot? {
        var result: org.maplibre.android.snapshotter.MapSnapshot? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        snap.start(
            { s -> result = s; latch.countDown() },
            { latch.countDown() },
        )
        runCatching { latch.await(45, java.util.concurrent.TimeUnit.SECONDS) }
        snap.cancel()
        return result
    }

    /** Cheap coast-to-coast snapshots along the route that only populate the shared tile cache. */
    private fun warmCorridor(context: Context, track: Track, style: String) {
        if (RideStore.active || cancelRequested.get()) return
        var d = 0.0
        var fired = 0
        while (d < track.lengthMeters && fired < MAX_CORRIDOR && !cancelRequested.get() && !RideStore.active) {
            if (fired % 2 == 0) {
                status = "Warming tiles ${Phrases.formatDistance(d)} of ${Phrases.formatDistance(track.lengthMeters)}"
            }
            val builder = LatLngBounds.Builder()
            var dd = (d - 120.0).coerceAtLeast(0.0)
            while (dd <= (d + 120.0).coerceAtMost(track.lengthMeters)) {
                val p = track.pointAtDistance(dd)
                builder.include(LatLng(p.lat, p.lon))
                dd += 20.0
            }
            val snap = MapSnapshotter(
                context.applicationContext,
                MapSnapshotter.Options(CACHED_IMG_PX, CACHED_IMG_PX)
                    .withStyle(style)
                    .withRegion(builder.build())
                    .withLogo(false)
                    .withAttribution(false),
            )
            snapshotSynchronous(snap)
            d += CORRIDOR_STEP_M
            fired++
        }
    }

    private const val CORRIDOR_STEP_M = 1000.0
    private const val MAX_CORRIDOR = 90
}