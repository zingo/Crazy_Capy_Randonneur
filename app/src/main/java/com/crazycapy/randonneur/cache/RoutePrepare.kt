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

object RoutePrepare {

    var state: PrepareState by mutableStateOf(PrepareState())
        private set

    private var generation = 0L
    private var activeTrack: Track? = null
    private var activeStrategy = OfflineDetailStrategy.STANDARD
    private var activeBothThemes = false

    fun start(
        context: Context,
        track: Track,
        strategy: OfflineDetailStrategy = RideStore.offlineDetailStrategy,
        bothThemes: Boolean = false,
    ) {
        val routeId = RouteStore.routeId(track.name)
        if (state.running && state.routeId == routeId && state.strategy == strategy && state.bothThemes == bothThemes) return
        if (state.running || state.paused) cancel()
        launch(context, track, strategy, bothThemes)
    }

    fun resume(context: Context) {
        val track = activeTrack ?: return
        if (state.running || !state.paused) return
        launch(context, track, activeStrategy, activeBothThemes)
    }

    private fun launch(
        context: Context,
        track: Track,
        strategy: OfflineDetailStrategy,
        bothThemes: Boolean,
    ) {
        val appContext = context.applicationContext
        generation++
        val gen = generation
        activeTrack = track
        activeStrategy = strategy
        activeBothThemes = bothThemes
        state = PrepareTransitions.started(
            routeId = RouteStore.routeId(track.name),
            routeName = track.name,
            strategy = strategy,
            bothThemes = bothThemes,
        )
        RouteOfflinePack.findComplete(appContext, track, strategy, bothThemes) { lookup ->
            if (gen != generation) return@findComplete
            if (lookup.complete) {
                state = PrepareTransitions.mapReady(state, skipped = true)
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
        state = PrepareTransitions.previewsStarted(state)
        if (previewsComplete(context, routeId, track)) {
            state = PrepareTransitions.completed(state, previewsSkipped = true, previewsStatus = null)
            return
        }
        RouteCache.preCache(context, track) {
            if (gen != generation) return@preCache
            state = PrepareTransitions.completed(
                state,
                previewsSkipped = false,
                previewsStatus = RouteCache.status,
            )
        }
    }

    private fun previewsComplete(context: Context, routeId: String, track: Track): Boolean =
        RouteCache.isCached(context, routeId, track, false) &&
            RouteCache.isCached(context, routeId, track, true) &&
            RouteCache.hasOverview(context, routeId, track)

    fun pause() {
        if (!state.running || state.paused) return
        generation++
        if (state.phase == PreparePhase.MAP && !state.mapSkipped) {
            RouteOfflinePack.pause()
        }
        RouteCache.pause()
        state = PrepareTransitions.paused(state)
    }

    fun cancel() {
        generation++
        if (state.phase == PreparePhase.MAP && !state.mapSkipped && !RouteOfflinePack.state.done) {
            RouteOfflinePack.cancel()
        }
        RouteCache.cancel()
        activeTrack = null
        state = PrepareTransitions.cancelled(state)
    }
}
