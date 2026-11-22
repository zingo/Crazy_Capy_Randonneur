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
import com.crazycapy.randonneur.gpx.RouteCorridor
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
)

object RouteOfflinePack {

    private const val LOG_TAG = "RouteOfflinePack"
    private const val MIN_ZOOM = 8.0
    private const val MAX_ZOOM = 14.0
    private const val PADDING_METERS = 1500.0

    var state: OfflinePackState by mutableStateOf(OfflinePackState())
        private set

    private var region: OfflineRegion? = null
    private var generation = 0L

    private fun themeFor(style: String): String = if (style == STYLE_DARK) "dark" else "light"

    private fun currentStyle(): String = if (RideStore.darkMap) STYLE_DARK else STYLE_LIGHT

    fun start(
        context: Context,
        track: Track,
        staleRegionIds: List<Long> = emptyList(),
        onDone: (() -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        generation++
        val gen = generation
        if (state.running) {
            releaseRegion(delete = true)
        } else {
            region = null
        }
        state = OfflinePackState(routeName = track.name, running = true)

        val box = RouteCorridor.around(track, PADDING_METERS)
        val bounds = LatLngBounds.Builder()
            .include(LatLng(box.south, box.west))
            .include(LatLng(box.north, box.east))
            .build()
        val styleUrl = currentStyle()
        val pixelRatio = appContext.resources.displayMetrics.density
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            MIN_ZOOM,
            MAX_ZOOM,
            pixelRatio,
        )
        val routeId = RouteStore.routeId(track.name)
        val trackHash = TrackHash.of(track)
        val metadata = OfflinePackMetadata(
            routeId = routeId,
            routeName = track.name,
            trackHash = trackHash,
            style = styleUrl,
            theme = themeFor(styleUrl),
            bytes = null,
            createdAt = System.currentTimeMillis(),
        )

        MapLibre.getInstance(appContext)
        Log.i(LOG_TAG, "start route=${track.name} style=$styleUrl bounds=$box")
        try {
            OfflineManager.getInstance(appContext).createOfflineRegion(
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
                        state = state.copy(regionId = offlineRegion.id)
                        Log.i(LOG_TAG, "created region id=${offlineRegion.id} route=${track.name}")
                        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                            override fun onStatusChanged(status: OfflineRegionStatus) {
                                if (gen != generation) return
                                val wasDone = state.done
                                val required = status.requiredResourceCount
                                val percent = if (required > 0) {
                                    (status.completedResourceCount.toFloat() / required).coerceIn(0f, 1f)
                                } else {
                                    null
                                }
                                state = state.copy(
                                    running = !status.isComplete,
                                    done = status.isComplete,
                                    error = if (status.isComplete) null else state.error,
                                    completedBytes = status.completedResourceSize,
                                    completedResources = status.completedResourceCount,
                                    requiredResources = required,
                                    percent = percent,
                                )
                                if (status.isComplete) {
                                    Log.i(
                                        LOG_TAG,
                                        "complete region=${offlineRegion.id} bytes=${status.completedResourceSize} resources=${status.completedResourceCount}",
                                    )
                                    if (!wasDone) {
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
                                    }
                                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    if (gen == generation) region = null
                                    if (!wasDone && gen == generation) onDone?.invoke()
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
                        deleteRegionsById(appContext, staleRegionIds.filter { it != offlineRegion.id }.toSet())
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
            Log.e(LOG_TAG, "start failed", e)
            state = state.copy(running = false, error = e.message ?: e.javaClass.simpleName)
            onDone?.invoke()
        }
    }

    /** Report whether a completed offline region already matches [track]. */
    fun findComplete(context: Context, track: Track, onResult: (PackLookup) -> Unit) {
        val appContext = context.applicationContext
        val routeId = RouteStore.routeId(track.name)
        val trackHash = TrackHash.of(track)
        val desiredStyle = currentStyle()
        val desiredTheme = themeFor(desiredStyle)
        MapLibre.getInstance(appContext)
        try {
            OfflineManager.getInstance(appContext).listOfflineRegions(
                object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(regions: Array<OfflineRegion>?) {
                        val related = regions.orEmpty().filter { region ->
                            val meta = OfflinePackMetadata.parse(region.metadata)
                            val legacyName = if (meta == null) {
                                region.metadata?.toString(Charsets.UTF_8)?.trim()
                            } else {
                                null
                            }
                            meta?.routeId == routeId || (meta == null && legacyName == track.name)
                        }
                        if (related.isEmpty()) {
                            onResult(PackLookup(false))
                            return
                        }
                        val remaining = AtomicInteger(related.size)
                        val checks = java.util.Collections.synchronizedList(ArrayList<RegionCheck>())
                        val finish: () -> Unit = {
                            val complete = checks.filter { it.exact && it.complete }
                            if (complete.isNotEmpty()) {
                                val keep = complete.first().region
                                deleteRegions(checks.map { it.region }.filter { it.id != keep.id })
                                onResult(PackLookup(true))
                            } else {
                                onResult(PackLookup(false, checks.map { it.region.id }))
                            }
                        }
                        for (match in related) {
                            val meta = OfflinePackMetadata.parse(match.metadata)
                            val style = meta?.style ?: match.definition.styleURL ?: STYLE_LIGHT
                            val theme = meta?.theme ?: themeFor(style)
                            val exact = meta?.trackHash == trackHash && style == desiredStyle && theme == desiredTheme
                            match.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                                override fun onStatus(status: OfflineRegionStatus?) {
                                    checks.add(RegionCheck(match, exact, status?.isComplete == true))
                                    if (remaining.decrementAndGet() == 0) finish()
                                }

                                override fun onError(error: String?) {
                                    Log.w(LOG_TAG, "status failed: $error")
                                    checks.add(RegionCheck(match, exact, false))
                                    if (remaining.decrementAndGet() == 0) finish()
                                }
                            })
                        }
                    }

                    override fun onError(message: String) {
                        Log.w(LOG_TAG, "list failed: $message")
                        onResult(PackLookup(false))
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "list failed", e)
            onResult(PackLookup(false))
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
                        val result = java.util.Collections.synchronizedList(ArrayList<OfflinePackInfo>())
                        val finish: () -> Unit = {
                            onResult(
                                result.sortedWith(
                                    compareByDescending<OfflinePackInfo> { it.createdAt ?: 0L }
                                        .thenBy { it.routeName },
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

    fun deletePacks(context: Context, ids: Collection<Long>?, onDone: () -> Unit) {
        val appContext = context.applicationContext
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

    private fun infoFor(region: OfflineRegion, status: OfflineRegionStatus?): OfflinePackInfo {
        val meta = OfflinePackMetadata.parse(region.metadata)
        val legacyName = if (meta == null) {
            region.metadata?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("{") }
        } else {
            null
        }
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
        )
    }

    fun cancel() {
        generation++
        releaseRegion(delete = true)
        state = state.copy(
            running = false,
            done = false,
            cancelled = true,
            percent = null,
        )
        Log.i(LOG_TAG, "cancelled region")
    }

    private fun releaseRegion(delete: Boolean) {
        val current = region ?: return
        region = null
        runCatching { current.setDownloadState(OfflineRegion.STATE_INACTIVE) }
        if (delete) {
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
        val exact: Boolean,
        val complete: Boolean,
    )
}
