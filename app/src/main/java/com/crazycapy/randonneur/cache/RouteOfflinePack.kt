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
import com.crazycapy.randonneur.state.RideStore
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

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

object RouteOfflinePack {

    private const val LOG_TAG = "RouteOfflinePack"
    private const val MIN_ZOOM = 8.0
    private const val MAX_ZOOM = 14.0
    private const val PADDING_METERS = 1500.0

    var state: OfflinePackState by mutableStateOf(OfflinePackState())
        private set

    private var region: OfflineRegion? = null
    private var generation = 0L

    fun start(context: Context, track: Track) {
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
        val styleUrl = if (RideStore.darkMap) STYLE_DARK else STYLE_LIGHT
        val pixelRatio = appContext.resources.displayMetrics.density
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            MIN_ZOOM,
            MAX_ZOOM,
            pixelRatio,
        )
        val metadata = track.name.toByteArray(Charsets.UTF_8)

        MapLibre.getInstance(appContext)
        Log.i(LOG_TAG, "start route=${track.name} style=$styleUrl bounds=$box")
        try {
            OfflineManager.getInstance(appContext).createOfflineRegion(
                definition,
                metadata,
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
                                    offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                                    if (gen == generation) region = null
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
                            }
                        })
                        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    }

                    override fun onError(message: String) {
                        if (gen != generation) return
                        Log.e(LOG_TAG, "create failed: $message")
                        state = state.copy(running = false, error = message)
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "start failed", e)
            state = state.copy(running = false, error = e.message ?: e.javaClass.simpleName)
        }
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
}
