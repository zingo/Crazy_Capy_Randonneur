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
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
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
import com.crazycapy.randonneur.roadBrightenOverrides
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
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

    /** Bumped on every pre-cache start; guards a stale job's finally from
     *  clearing the shared status/progress/activeRouteId of a newer job. */
    private val jobGeneration = java.util.concurrent.atomic.AtomicLong(0)

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
        val gen = jobGeneration.incrementAndGet()

        val thread = HandlerThread("route-precache")
        thread.start()
        Handler(thread.looper).post {
            val renderer = ReusedSnapshotter(context)
            try {
                renderer.create()
                val turns = TurnFinder.find(track)
                val darkBase = RideStore.darkMap
                val colorName = { dark: Boolean -> if (dark) "dark" else "light" }
                for (dark in listOf(false, true)) {
                    if (cancelRequested.get() || RideStore.active) break
                    val style = if (dark) STYLE_DARK else STYLE_LIGHT
                    var done = 0
                    val missed = ArrayList<Int>()
                    for ((i, turn) in turns.withIndex()) {
                        if (cancelRequested.get() || RideStore.active) break
                        if (!turnFile(context, routeId, dark, i).exists()) {
                            status = "Pre-caching ${track.name} · turn ${i + 1}/${turns.size} (${colorName(dark)})"
                            if (!renderAndStore(context, renderer, track, turn.distAlongM, dark, style, i, boundsForTurn(track, turn.distAlongM))) {
                                missed.add(i)
                            }
                        }
                        done++
                        progress = (done.toFloat() + (if (dark) turns.size else 0)) / (turns.size * 2f)
                    }
                    // Give turns that timed out a second chance in the same run: the
                    // interactive map's tile prefetch often settles by then.
                    for (i in missed) {
                        if (cancelRequested.get() || RideStore.active) break
                        status = "Retrying turn ${i + 1}/${turns.size} (${colorName(dark)})"
                        renderAndStore(context, renderer, track, turns[i].distAlongM, dark, style, i, boundsForTurn(track, turns[i].distAlongM))
                    }
                }
                if (!cancelRequested.get() && !RideStore.active) {
                    warmCorridor(renderer, track, if (darkBase) STYLE_DARK else STYLE_LIGHT)
                    generateOverview(context, renderer, track, if (darkBase) STYLE_DARK else STYLE_LIGHT)
                    val missing = turns.indices.count { i ->
                        !turnFile(context, routeId, false, i).exists() ||
                            !turnFile(context, routeId, true, i).exists()
                    }
                    status = if (missing == 0) {
                        "Pre-cached ${track.name} ✓"
                    } else {
                        val cached = turns.size - missing
                        "Pre-cached ${cached}/${turns.size} turns · rest on next load"
                    }
                }
            } catch (e: Exception) {
                if (!cancelRequested.get()) {
                    Log.e("RouteCache", "pre-cache failed", e)
                    status = "Pre-cache failed: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                renderer.dispose()
                if (jobGeneration.get() == gen) {
                    progress = null
                    activeRouteId = null
                    cancelRequested.set(false)
                }
                thread.quitSafely()
            }
        }
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
        renderer: ReusedSnapshotter,
        track: Track,
        atM: Double,
        dark: Boolean,
        style: String,
        turnIndex: Int,
        bounds: LatLngBounds,
    ): Boolean {
        val shot = renderer.render(style, dark, bounds, CACHED_IMG_PX, CACHED_IMG_PX, CACHED_IMG_PX / 7, TURN_RENDER_TIMEOUT_MS)
            ?: return false
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
        return true
    }

    /** Cheap coast-to-coast snapshots along the route that only populate the shared tile cache. */
    private fun warmCorridor(renderer: ReusedSnapshotter, track: Track, style: String) {
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
            renderer.render(style, dark = false, builder.build(), CACHED_IMG_PX, CACHED_IMG_PX, 0, CORRIDOR_TIMEOUT_MS)
            d += CORRIDOR_STEP_M
            fired++
        }
    }

    // ── Route overview thumbnail ──────────────────────────────────────

    /** Path to the full-route overview thumbnail image. */
    private fun overviewFile(context: Context, routeId: String): File =
        File(routeDir(context, routeId), "overview.png")

    /** Generate a small overview map of the full route and save it as PNG. */
    private fun generateOverview(context: Context, renderer: ReusedSnapshotter, track: Track, styleUrl: String) {
        val routeId = RouteStore.routeId(track.name)
        val png = overviewFile(context, routeId)
        if (png.exists()) return
        status = "Rendering overview  ${Phrases.formatDistance(track.lengthMeters)}"
        val builder = LatLngBounds.Builder()
        for (p in track.points) builder.include(LatLng(p.lat, p.lon))
        val bounds = builder.build()
        val shot = renderer.render(styleUrl, dark = false, bounds, OVERVIEW_W, OVERVIEW_H, 8, TURN_RENDER_TIMEOUT_MS) ?: return
        // Draw the route line on the snapshot bitmap
        val bmp = shot.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0x33, 0x99, 0xFF)
            strokeWidth = 4f
            strokeCap = android.graphics.Paint.Cap.ROUND
            this.style = android.graphics.Paint.Style.STROKE
        }
        val path = android.graphics.Path()
        for (i in track.points.indices) {
            val p = shot.pixelForLatLng(LatLng(track.points[i].lat, track.points[i].lon))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        canvas.drawPath(path, paint)
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, png.outputStream())
        bmp.recycle()
    }

    /** Load the route overview thumbnail, or null if not generated yet. */
    fun loadOverview(context: Context, routeId: String): android.graphics.Bitmap? = runCatching {
        val f = overviewFile(context, routeId)
        if (!f.exists()) null else android.graphics.BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    private const val OVERVIEW_W = 240
    private const val OVERVIEW_H = 160
    private const val CORRIDOR_STEP_M = 1000.0
    private const val MAX_CORRIDOR = 90
    private const val TURN_RENDER_TIMEOUT_MS = 90_000L
    private const val CORRIDOR_TIMEOUT_MS = 45_000L
}

/**
 * One long-lived offscreen renderer retargeted per turn. Creating a fresh
 * MapSnapshotter per snapshot leaks an EGL context each time (until GC) and
 * runs straight into the platform's context budget after ~16 renders, which
 * shows up as "call to OpenGL ES API with no current context" and 45/90 s
 * timeouts. A single reusable instance avoids that entirely.
 */
private class ReusedSnapshotter(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gen = java.util.concurrent.atomic.AtomicInteger(0)
    private val targetDark = java.util.concurrent.atomic.AtomicBoolean(false)
    private var snap: MapSnapshotter? = null
    private var currentStyle: String? = null
    private var currentWidth = -1
    private var currentHeight = -1
    private var currentPadding = -1

    /** Create the renderer on the UI thread (MapLibre requires it). */
    fun create() {
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            try {
                MapLibre.getInstance(appContext)
                val s = MapSnapshotter(
                    appContext,
                    MapSnapshotter.Options(CACHED_IMG_PX, CACHED_IMG_PX)
                        .withStyle(STYLE_LIGHT)
                        .withLogo(false)
                        .withAttribution(false),
                )
                s.setObserver(object : MapSnapshotter.Observer {
                    override fun onDidFinishLoadingStyle() {
                        if (targetDark.get()) {
                            for ((id, color) in roadBrightenOverrides) {
                                (s.getLayer(id) as? LineLayer)
                                    ?.setProperties(PropertyFactory.lineColor(color))
                            }
                        }
                    }
                    override fun onStyleImageMissing(name: String) {}
                })
                snap = s
            } catch (e: Exception) {
                Log.e("RouteCache", "snapshotter create failed", e)
            } finally {
                latch.countDown()
            }
        }
        runCatching { latch.await(15, java.util.concurrent.TimeUnit.SECONDS) }
    }

    /** Render one frame of [bounds] and return the bitmap, or null on timeout. */
    fun render(
        style: String,
        dark: Boolean,
        bounds: LatLngBounds,
        width: Int,
        height: Int,
        padding: Int,
        timeoutMs: Long,
    ): org.maplibre.android.snapshotter.MapSnapshot? {
        val g = gen.incrementAndGet()
        var result: org.maplibre.android.snapshotter.MapSnapshot? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        targetDark.set(dark)
        mainHandler.post {
            val s = snap
            if (s == null) {
                if (gen.get() == g) latch.countDown()
                return@post
            }
            try {
                s.cancel()
                if (style != currentStyle) {
                    s.setStyleUrl(style)
                    currentStyle = style
                }
                if (width != currentWidth || height != currentHeight) {
                    s.setSize(width, height)
                    currentWidth = width
                    currentHeight = height
                }
                if (padding != currentPadding) {
                    s.setPadding(padding, padding, padding, padding)
                    currentPadding = padding
                }
                s.setRegion(bounds)
                s.start(
                    { shot -> if (gen.get() == g) { result = shot; latch.countDown() } },
                    { if (gen.get() == g) latch.countDown() },
                )
            } catch (e: Exception) {
                Log.e("RouteCache", "snapshot render failed", e)
                if (gen.get() == g) latch.countDown()
            }
        }
        val ok = runCatching { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        if (!ok) {
            Log.w("RouteCache", "snapshot timed out after ${timeoutMs / 1000}s (${if (dark) "dark" else "light"})")
            mainHandler.post { runCatching { snap?.cancel() } }
            return null
        }
        return result
    }

    /** Cancel the pending render and release the native side. */
    fun dispose() {
        gen.incrementAndGet()
        mainHandler.post {
            runCatching { snap?.cancel() }
            snap = null
        }
    }
}