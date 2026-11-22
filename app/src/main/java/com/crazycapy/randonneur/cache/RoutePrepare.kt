/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore

enum class PreparePhase { MAP, PREVIEWS, DONE }

data class PrepareState(
    val routeId: String = "",
    val routeName: String = "",
    val running: Boolean = false,
    val phase: PreparePhase = PreparePhase.MAP,
    val mapSkipped: Boolean = false,
    val previewsSkipped: Boolean = false,
    val done: Boolean = false,
    val cancelled: Boolean = false,
    val previewsStatus: String? = null,
    val strategy: OfflineDetailStrategy = OfflineDetailStrategy.STANDARD,
    val bothThemes: Boolean = false,
)

object RoutePrepare {

    var state: PrepareState by mutableStateOf(PrepareState())
        private set

    private var generation = 0L

    fun start(
        context: Context,
        track: Track,
        strategy: OfflineDetailStrategy = RideStore.offlineDetailStrategy,
        bothThemes: Boolean = false,
    ) {
        val routeId = RouteStore.routeId(track.name)
        if (state.running && state.routeId == routeId && state.strategy == strategy && state.bothThemes == bothThemes) return
        if (state.running) cancel()
        val appContext = context.applicationContext
        generation++
        val gen = generation
        state = PrepareState(
            routeId = routeId,
            routeName = track.name,
            running = true,
            phase = PreparePhase.MAP,
            strategy = strategy,
            bothThemes = bothThemes,
        )
        RouteOfflinePack.findComplete(appContext, track, strategy, bothThemes) { lookup ->
            if (gen != generation) return@findComplete
            if (lookup.complete) {
                state = state.copy(mapSkipped = true)
                startPreviews(appContext, track, gen)
            } else {
                RouteOfflinePack.start(appContext, track, strategy, bothThemes) {
                    if (gen == generation) startPreviews(appContext, track, gen)
                }
            }
        }
    }

    private fun startPreviews(context: Context, track: Track, gen: Long) {
        val routeId = RouteStore.routeId(track.name)
        state = state.copy(phase = PreparePhase.PREVIEWS)
        if (previewsComplete(context, routeId, track)) {
            state = state.copy(
                phase = PreparePhase.DONE,
                previewsSkipped = true,
                running = false,
                done = true,
            )
            return
        }
        RouteCache.preCache(context, track) {
            if (gen != generation) return@preCache
            state = state.copy(
                phase = PreparePhase.DONE,
                running = false,
                done = true,
                previewsStatus = RouteCache.status,
            )
        }
    }

    private fun previewsComplete(context: Context, routeId: String, track: Track): Boolean =
        RouteCache.isCached(context, routeId, track, false) &&
            RouteCache.isCached(context, routeId, track, true) &&
            RouteCache.hasOverview(context, routeId, track)

    fun cancel() {
        generation++
        if (state.phase == PreparePhase.MAP && !state.mapSkipped && !RouteOfflinePack.state.done) {
            RouteOfflinePack.cancel()
        }
        RouteCache.cancel()
        state = state.copy(running = false, cancelled = true)
    }
}
