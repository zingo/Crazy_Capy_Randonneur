/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crazycapy.randonneur.STYLE_DARK
import com.crazycapy.randonneur.STYLE_LIGHT
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackHash
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

data class OfflinePackState(
    val routeName: String = "",
    val running: Boolean = false,
    val done: Boolean = false,
    val cancelled: Boolean = false,
    val error: String? = null,
    val completedBytes: Long = 0L,
    val completedResources: Long = 0L,
    val requiredResources: Long = 0L,
    val percent: Float? = null,
    val regionId: Long? = null,
    val strategy: OfflineDetailStrategy = OfflineDetailStrategy.STANDARD,
    val theme: String = "",
    val themeIndex: Int = 0,
    val themeCount: Int = 0,
    val segmentIndex: Int = 0,
    val segmentCount: Int = 0,
    val completedSegments: Int = 0,
)

data class PackLookup(
    val complete: Boolean,
    val staleRegionIds: List<Long> = emptyList(),
)

enum class OfflinePackStatus { COMPLETE, DOWNLOADING, INCOMPLETE }

data class OfflinePackInfo(
    val id: Long,
    val routeName: String,
    val routeId: String?,
    val trackHash: String?,
    val style: String,
    val theme: String,
    val bytes: Long?,
    val createdAt: Long?,
    val status: OfflinePackStatus,
    val completedBytes: Long,
    val completedResources: Long,
    val requiredResources: Long,
    val strategy: OfflineDetailStrategy = OfflineDetailStrategy.STANDARD,
    val segmentIndex: Int = 0,
    val segmentCount: Int = 1,
)

object RouteOfflinePack {

    private const val LOG_TAG = "RouteOfflinePack"

    var state: OfflinePackState by mutableStateOf(OfflinePackState())
        private set

    private var region: OfflineRegion? = null
    private var generation = 0L
    private val runRegions: MutableMap<Long, OfflineRegion> = Collections.synchronizedMap(LinkedHashMap())

    private fun themeFor(style: String): String = if (style == STYLE_DARK) "dark" else "light"

    private fun currentStyle(): String = if (RideStore.darkMap) STYLE_DARK else STYLE_LIGHT

    private fun stylesFor(bothThemes: Boolean): List<String> {
        val current = currentStyle()
        if (!bothThemes) return listOf(current)
        val other = if (current == STYLE_DARK) STYLE_LIGHT else STYLE_DARK
        return listOf(current, other)
    }

    private fun legacyNameOf(region: OfflineRegion): String? =
        region.metadata?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("{") }

    fun start(
        context: Context,
        track: Track,
        strategy: OfflineDetailStrategy = OfflineDetailStrategy.STANDARD,
        bothThemes: Boolean = false,
        onDone: (() -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        generation++
        val gen = generation
        releasePartialRegions(delete = true)
        val specs = OfflineSegments.regionSpecs(track, strategy)
        val styles = stylesFor(bothThemes)
        val routeId = RouteStore.routeId(track.name)
        val trackHash = TrackHash.of(track)
        state = OfflinePackState(
            routeName = track.name,
            running = true,
            strategy = strategy,
            theme = themeFor(styles.first()),
            themeCount = styles.size,
            themeIndex = 1,
            segmentCount = specs.size * styles.size,
        )

        MapLibre.getInstance(appContext)
        Log.i(
            LOG_TAG,
            "start route=${track.name} strategy=${strategy.name} segments=${specs.size} themes=${styles.joinToString(",")}",
        )
        planStyle(appContext, track, routeId, trackHash, strategy, specs, styles, 0, 0L, 0, gen, onDone)
    }

    private fun planStyle(
        context: Context,
        track: Track,
        routeId: String,
        trackHash: String,
        strategy: OfflineDetailStrategy,
        specs: List<OfflineRegionSpec>,
        styles: List<String>,
        stylePos: Int,
        baseBytes: Long,
        baseCompleted: Int,
        gen: Long,
        onDone: (() -> Unit)?,
    ) {
        if (gen != generation) return
        if (stylePos >= styles.size) {
            completeRun(baseBytes, gen, onDone)
            return
        }
        val styleUrl = styles[stylePos]
        val theme = themeFor(styleUrl)
        try {
            OfflineManager.getInstance(context).listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(regions: Array<OfflineRegion>?) {
                        if (gen != generation) return
                        planExisting(
                            regions.orEmpty().toList(),
                            track.name,
                            routeId,
                            trackHash,
                            styleUrl,
                            strategy,
                            specs,
                        ) { plan ->
                            if (gen != generation) return@planExisting
                            if (plan.staleRegionIds.isNotEmpty()) {
                                deleteRegionsById(context, plan.staleRegionIds)
                            }
                            state = state.copy(
                                theme = theme,
                                themeCount = styles.size,
                                themeIndex = stylePos + 1,
                                segmentCount = specs.size * styles.size,
                                completedSegments = baseCompleted + plan.reusedCount,
                                completedBytes = baseBytes + plan.reusedBytes,
                            )
                            if (plan.missing.isEmpty()) {
                                planStyle(
                                    context, track, routeId, trackHash, strategy, specs, styles,
                                    stylePos + 1,
                                    baseBytes + plan.reusedBytes,
                                    (stylePos + 1) * specs.size,
                                    gen,
                                    onDone,
                                )
                            } else {
                                downloadNext(
                                    context, track, styleUrl, routeId, trackHash, strategy, specs, styles,
                                    stylePos, plan.missing, 0,
                                    baseBytes, plan.reusedBytes, plan.reusedCount, 0L,
                                    gen, onDone,
                                )
                            }
                        }
                    }

                    override fun onError(message: String) {
                        if (gen != generation) return
                        Log.e(LOG_TAG, "list failed: $message")
                        state = state.copy(running = false, error = message)
                        onDone?.invoke()
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "start failed", e)
            state = state.copy(running = false, error = e.message ?: e.javaClass.simpleName)
            onDone?.invoke()
        }
    }

    fun findComplete(
        context: Context,
        track: Track,
        strategy: OfflineDetailStrategy = OfflineDetailStrategy.STANDARD,
        bothThemes: Boolean = false,
        onResult: (PackLookup) -> Unit,
    ) {
        val appContext = context.applicationContext
        val routeId = RouteStore.routeId(track.name)
        val trackHash = TrackHash.of(track)
        val specs = OfflineSegments.regionSpecs(track, strategy)
        val styles = stylesFor(bothThemes)
        MapLibre.getInstance(appContext)
        checkStyles(
            appContext,
            track.name,
            routeId,
            trackHash,
            strategy,
            specs,
            styles,
            0,
            HashSet(),
            onResult,
        )
    }

    private fun checkStyles(
        context: Context,
        trackName: String,
        routeId: String,
        trackHash: String,
        strategy: OfflineDetailStrategy,
        specs: List<OfflineRegionSpec>,
        styles: List<String>,
        stylePos: Int,
        stale: MutableSet<Long>,
        onResult: (PackLookup) -> Unit,
    ) {
        if (stylePos >= styles.size) {
            if (stale.isNotEmpty()) deleteRegionsById(context, stale)
            onResult(PackLookup(true, stale.toList()))
            return
        }
        val desiredStyle = styles[stylePos]
        try {
            OfflineManager.getInstance(context).listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(regions: Array<OfflineRegion>?) {
                        planExisting(
                            regions.orEmpty().toList(),
                            trackName,
                            routeId,
                            trackHash,
                            desiredStyle,
                            strategy,
                            specs,
                        ) { plan ->
                            stale.addAll(plan.staleRegionIds)
                            if (plan.missing.isNotEmpty()) {
                                onResult(PackLookup(false, stale.toList()))
                            } else {
                                checkStyles(
                                    context, trackName, routeId, trackHash, strategy, specs, styles,
                                    stylePos + 1, stale, onResult,
                                )
                            }
                        }
                    }

                    override fun onError(message: String) {
                        Log.w(LOG_TAG, "list failed: $message")
                        onResult(PackLookup(false, stale.toList()))
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "list failed", e)
            onResult(PackLookup(false, stale.toList()))
        }
    }

    fun listPacks(context: Context, onResult: (List<OfflinePackInfo>) -> Unit) {
        val appContext = context.applicationContext
        MapLibre.getInstance(appContext)
        try {
            OfflineManager.getInstance(appContext).listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(regions: Array<OfflineRegion>?) {
                        val all = regions.orEmpty()
                        if (all.isEmpty()) {
                            onResult(emptyList())
                            return
                        }
                        val remaining = AtomicInteger(all.size)
                        val result = Collections.synchronizedList(ArrayList<OfflinePackInfo>())
                        val finish: () -> Unit = {
                            onResult(
                                result.sortedWith(
                                    compareByDescending<OfflinePackInfo> { it.createdAt ?: 0L }
                                        .thenBy { it.routeName }
                                        .thenBy { it.segmentIndex },
                                ),
                            )
                        }
                        for (offlineRegion in all) {
                            offlineRegion.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                                override fun onStatus(status: OfflineRegionStatus?) {
                                    result.add(infoFor(offlineRegion, status))
                                    if (remaining.decrementAndGet() == 0) finish()
                                }

                                override fun onError(error: String?) {
                                    Log.w(LOG_TAG, "status failed: $error")
                                    result.add(infoFor(offlineRegion, null))
                                    if (remaining.decrementAndGet() == 0) finish()
                                }
                            })
                        }
                    }

                    override fun onError(message: String) {
                        Log.w(LOG_TAG, "list failed: $message")
                        onResult(emptyList())
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "list failed", e)
            onResult(emptyList())
        }
    }

    fun totalBytes(context: Context, onResult: (Long) -> Unit) {
        listPacks(context) { packs -> onResult(packs.sumOf { it.bytes ?: it.completedBytes }) }
    }

    fun deleteRoute(context: Context, routeId: String, onDone: () -> Unit) {
        val appContext = context.applicationContext
        MapLibre.getInstance(appContext)
        try {
            OfflineManager.getInstance(appContext).listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(regions: Array<OfflineRegion>?) {
                        val targets = regions.orEmpty().filter {
                            OfflinePackMetadata.parse(it.metadata)?.routeId == routeId
                        }
                        deleteRegions(targets, onDone)
                    }

                    override fun onError(message: String) {
                        Log.w(LOG_TAG, "delete route list failed: $message")
                        onDone()
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "delete route failed", e)
            onDone()
        }
    }

    fun deletePacks(context: Context, ids: Collection<Long>?, onDone: () -> Unit) {        val appContext = context.applicationContext
        MapLibre.getInstance(appContext)
        try {
            OfflineManager.getInstance(appContext).listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(regions: Array<OfflineRegion>?) {
                        val targets = regions.orEmpty().filter { ids == null || it.id in ids }
                        deleteRegions(targets, onDone)
                    }

                    override fun onError(message: String) {
                        Log.w(LOG_TAG, "delete list failed: $message")
                        onDone()
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "delete failed", e)
            onDone()
        }
    }

    private fun deleteRegionsById(context: Context, ids: Set<Long>) {
        if (ids.isEmpty()) return
        deletePacks(context, ids) {}
    }

    private fun deleteRegions(regions: List<OfflineRegion>, onDone: (() -> Unit)? = null) {
        if (regions.isEmpty()) {
            onDone?.invoke()
            return
        }
        val remaining = AtomicInteger(regions.size)
        val finish: () -> Unit = {
            if (remaining.decrementAndGet() == 0) onDone?.invoke()
        }
        for (offlineRegion in regions) {
            offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    finish()
                }

                override fun onError(message: String) {
                    Log.w(LOG_TAG, "delete failed: $message")
                    finish()
                }
            })
        }
    }

    private fun planExisting(
        regions: List<OfflineRegion>,
        trackName: String,
        routeId: String,
        trackHash: String,
        style: String,
        strategy: OfflineDetailStrategy,
        specs: List<OfflineRegionSpec>,
        onResult: (ExistingPlan) -> Unit,
    ) {
        val related = regions.filter { offlineRegion ->
            val meta = OfflinePackMetadata.parse(offlineRegion.metadata)
            meta?.routeId == routeId || (meta == null && legacyNameOf(offlineRegion) == trackName)
        }
        val candidates = related.filter { offlineRegion ->
            val meta = OfflinePackMetadata.parse(offlineRegion.metadata)
            meta == null || (meta.trackHash == trackHash && meta.style == style && meta.strategy == strategy)
        }
        if (candidates.isEmpty()) {
            onResult(ExistingPlan(specs, 0L, emptySet(), 0))
            return
        }
        val remaining = AtomicInteger(candidates.size)
        val checks = Collections.synchronizedList(ArrayList<RegionCheck>())
        val finish: () -> Unit = {
            val used = HashSet<Long>()
            val missing = ArrayList<OfflineRegionSpec>()
            var bytes = 0L
            for (spec in specs) {
                val match = checks.firstOrNull { check ->
                    !used.contains(check.region.id) && check.complete && matchesSpec(check.meta, spec, trackHash, style)
                }
                if (match != null) {
                    used.add(match.region.id)
                    bytes += match.completedBytes
                } else {
                    missing.add(spec)
                }
            }
            val stale = checks.map { it.region.id }.filter { it !in used }.toSet()
            onResult(ExistingPlan(missing, bytes, stale, specs.size - missing.size))
        }
        for (offlineRegion in candidates) {
            val meta = OfflinePackMetadata.parse(offlineRegion.metadata)
            offlineRegion.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    checks.add(
                        RegionCheck(
                            region = offlineRegion,
                            meta = meta,
                            complete = status?.isComplete == true,
                            completedBytes = status?.completedResourceSize ?: 0L,
                        ),
                    )
                    if (remaining.decrementAndGet() == 0) finish()
                }

                override fun onError(error: String?) {
                    Log.w(LOG_TAG, "status failed: $error")
                    checks.add(RegionCheck(offlineRegion, meta, false, 0L))
                    if (remaining.decrementAndGet() == 0) finish()
                }
            })
        }
    }

    private fun matchesSpec(meta: OfflinePackMetadata?, spec: OfflineRegionSpec, trackHash: String, style: String): Boolean =
        meta != null &&
            meta.schema >= OfflinePackMetadata.SCHEMA &&
            meta.trackHash == trackHash &&
            meta.style == style &&
            meta.strategy == spec.strategy &&
            meta.segmentIndex == spec.index &&
            meta.segmentCount == spec.count

    private fun downloadNext(
        context: Context,
        track: Track,
        styleUrl: String,
        routeId: String,
        trackHash: String,
        strategy: OfflineDetailStrategy,
        specs: List<OfflineRegionSpec>,
        styles: List<String>,
        stylePos: Int,
        missing: List<OfflineRegionSpec>,
        pos: Int,
        baseBytes: Long,
        styleReusedBytes: Long,
        styleReusedCount: Int,
        styleDownloadedBytes: Long,
        gen: Long,
        onDone: (() -> Unit)?,
    ) {
        if (gen != generation) return
        if (pos >= missing.size) {
            planStyle(
                context, track, routeId, trackHash, strategy, specs, styles,
                stylePos + 1,
                baseBytes + styleReusedBytes + styleDownloadedBytes,
                (stylePos + 1) * specs.size,
                gen,
                onDone,
            )
            return
        }
        val spec = missing[pos]
        val totalSegments = specs.size * styles.size
        val segmentOffset = stylePos * specs.size
        val styleBaseBytes = baseBytes + styleReusedBytes
        val bounds = LatLngBounds.Builder()
            .include(LatLng(spec.box.south, spec.box.west))
            .include(LatLng(spec.box.north, spec.box.east))
            .build()
        val pixelRatio = context.resources.displayMetrics.density
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            spec.minZoom,
            spec.maxZoom,
            pixelRatio,
        )
        val metadata = OfflinePackMetadata(
            routeId = routeId,
            routeName = track.name,
            trackHash = trackHash,
            style = styleUrl,
            theme = themeFor(styleUrl),
            bytes = null,
            createdAt = System.currentTimeMillis(),
            strategy = strategy,
            segmentIndex = spec.index,
            segmentCount = spec.count,
        )
        state = state.copy(
            running = true,
            done = false,
            cancelled = false,
            percent = null,
            completedBytes = styleBaseBytes + styleDownloadedBytes,
            completedResources = 0L,
            requiredResources = 0L,
            strategy = strategy,
            theme = themeFor(styleUrl),
            themeCount = styles.size,
            themeIndex = stylePos + 1,
            segmentIndex = segmentOffset + spec.index + 1,
            segmentCount = totalSegments,
            completedSegments = segmentOffset + styleReusedCount + pos,
        )
        Log.i(
            LOG_TAG,
            "create segment ${spec.index + 1}/${spec.count} theme=${themeFor(styleUrl)} route=${track.name} zoom=${spec.minZoom}-${spec.maxZoom} bounds=${spec.box}",
        )
        try {
            OfflineManager.getInstance(context).createOfflineRegion(
                definition,
                metadata.toJson(),
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        if (gen != generation) {
                            offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                override fun onDelete() {}
                                override fun onError(message: String) {}
                            })
                            return
                        }
                        region = offlineRegion
                        runRegions[offlineRegion.id] = offlineRegion
                        state = state.copy(regionId = offlineRegion.id)
                        var finished = false
                        Log.i(
                            LOG_TAG,
                            "created region id=${offlineRegion.id} segment=${spec.index + 1}/${spec.count}",
                        )
                        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                if (gen != generation) return
                                val required = status.requiredResourceCount
                                val percent = if (required > 0) {
                                    (status.completedResourceCount.toFloat() / required).coerceIn(0f, 1f)
                                } else {
                                    null
                                }
                                state = state.copy(
                                    running = !status.isComplete,
                                    error = if (status.isComplete) null else state.error,
                                    completedBytes = styleBaseBytes + styleDownloadedBytes + status.completedResourceSize,
                                    completedResources = status.completedResourceCount,
                                    requiredResources = required,
                                    percent = percent,
                                )
                                if (status.isComplete && !finished) {
                                    finished = true
                                    val completed = metadata.copy(bytes = status.completedResourceSize)
                                    offlineRegion.updateMetadata(
                                        completed.toJson(),
                                        object : OfflineRegion.OfflineRegionUpdateMetadataCallback {
                                            override fun onUpdate(metadata: ByteArray) {}
                                            override fun onError(message: String) {
                                                Log.w(LOG_TAG, "metadata update failed: $message")
                                            }
                                        },
                                    )
                                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    runRegions.remove(offlineRegion.id)
                                    if (region === offlineRegion) region = null
                                    val nextDownloaded = styleDownloadedBytes + status.completedResourceSize
                                    Log.i(
                                        LOG_TAG,
                                        "complete segment=${spec.index + 1}/${spec.count} region=${offlineRegion.id} bytes=${status.completedResourceSize}",
                                    )
                                    state = state.copy(
                                        completedSegments = (segmentOffset + styleReusedCount + pos + 1)
                                            .coerceAtMost(totalSegments),
                                        completedBytes = styleBaseBytes + nextDownloaded,
                                        percent = 1f,
                                    )
                                    downloadNext(
                                        context,
                                        track,
                                        styleUrl,
                                        routeId,
                                        trackHash,
                                        strategy,
                                        specs,
                                        styles,
                                        stylePos,
                                        missing,
                                        pos + 1,
                                        baseBytes,
                                        styleReusedBytes,
                                        styleReusedCount,
                                        nextDownloaded,
                                        gen,
                                        onDone,
                                    )
                                }
                            }

                            override fun onError(error: OfflineRegionError) {
                                if (gen != generation) return
                                Log.w(LOG_TAG, "error reason=${error.reason} message=${error.message}")
                                state = state.copy(error = "${error.reason}: ${error.message}")
                            }

                            override fun mapboxTileCountLimitExceeded(limit: Long) {
                                if (gen != generation) return
                                Log.w(LOG_TAG, "tile count limit exceeded: $limit")
                                state = state.copy(
                                    running = false,
                                    error = "Tile limit exceeded ($limit)",
                                )
                                onDone?.invoke()
                            }
                        })
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }

                    override fun onError(message: String) {
                        if (gen != generation) return
                        Log.e(LOG_TAG, "create failed: $message")
                        state = state.copy(running = false, error = message)
                        onDone?.invoke()
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "segment start failed", e)
            state = state.copy(running = false, error = e.message ?: e.javaClass.simpleName)
            onDone?.invoke()
        }
    }

    private fun completeRun(bytes: Long, gen: Long, onDone: (() -> Unit)?) {
        if (gen != generation) return
        region = null
        state = state.copy(
            running = false,
            done = true,
            cancelled = false,
            error = null,
            completedBytes = bytes,
            completedSegments = state.segmentCount,
            segmentIndex = state.segmentCount,
            themeIndex = state.themeCount,
            percent = 1f,
        )
        onDone?.invoke()
    }

    private fun infoFor(region: OfflineRegion, status: OfflineRegionStatus?): OfflinePackInfo {
        val meta = OfflinePackMetadata.parse(region.metadata)
        val legacyName = if (meta == null) legacyNameOf(region) else null
        val style = meta?.style ?: region.definition.styleURL ?: STYLE_LIGHT
        val packStatus = when {
            status?.isComplete == true -> OfflinePackStatus.COMPLETE
            status?.downloadState == OfflineRegion.STATE_ACTIVE -> OfflinePackStatus.DOWNLOADING
            else -> OfflinePackStatus.INCOMPLETE
        }
        return OfflinePackInfo(
            id = region.id,
            routeName = meta?.routeName?.takeIf { it.isNotBlank() } ?: legacyName ?: "Offline pack",
            routeId = meta?.routeId,
            trackHash = meta?.trackHash,
            style = style,
            theme = meta?.theme ?: themeFor(style),
            bytes = meta?.bytes,
            createdAt = meta?.createdAt,
            status = packStatus,
            completedBytes = status?.completedResourceSize ?: 0L,
            completedResources = status?.completedResourceCount ?: 0L,
            requiredResources = status?.requiredResourceCount ?: 0L,
            strategy = meta?.strategy ?: OfflineDetailStrategy.STANDARD,
            segmentIndex = meta?.segmentIndex ?: 0,
            segmentCount = meta?.segmentCount ?: 1,
        )
    }

    fun cancel() {
        generation++
        releasePartialRegions(delete = true)
        state = state.copy(
            running = false,
            done = false,
            cancelled = true,
            percent = null,
        )
        Log.i(LOG_TAG, "cancelled run")
    }

    fun pause() {
        generation++
        val current = region
        region = null
        val partials = synchronized(runRegions) { runRegions.values.toList() }
        for (offlineRegion in partials) {
            runCatching { offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE) }
        }
        if (current != null && partials.none { it.id == current.id }) {
            runCatching { current.setDownloadState(OfflineRegion.STATE_INACTIVE) }
        }
        state = state.copy(running = false, percent = null)
        Log.i(LOG_TAG, "paused run")
    }

    private fun releasePartialRegions(delete: Boolean) {
        val current = region
        region = null
        val partials = synchronized(runRegions) {
            val values = runRegions.values.toList()
            runRegions.clear()
            values
        }
        for (offlineRegion in partials) {
            runCatching { offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE) }
            if (delete) {
                offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() {}
                    override fun onError(message: String) {
                        Log.w(LOG_TAG, "delete failed: $message")
                    }
                })
            }
        }
        if (delete && current != null && partials.none { it.id == current.id }) {
            runCatching { current.setDownloadState(OfflineRegion.STATE_INACTIVE) }
            current.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {}
                override fun onError(message: String) {
                    Log.w(LOG_TAG, "delete failed: $message")
                }
            })
        }
    }

    private data class RegionCheck(
        val region: OfflineRegion,
        val meta: OfflinePackMetadata?,
        val complete: Boolean,
        val completedBytes: Long,
    )

    private data class ExistingPlan(
        val missing: List<OfflineRegionSpec>,
        val reusedBytes: Long,
        val staleRegionIds: Set<Long>,
        val reusedCount: Int,
    )
}
