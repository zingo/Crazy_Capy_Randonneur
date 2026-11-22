/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

enum class PreparePhase { MAP, PREVIEWS, DONE }

data class PrepareState(
    val routeId: String = "",
    val routeName: String = "",
    val running: Boolean = false,
    val paused: Boolean = false,
    val phase: PreparePhase = PreparePhase.MAP,
    val mapSkipped: Boolean = false,
    val previewsSkipped: Boolean = false,
    val done: Boolean = false,
    val cancelled: Boolean = false,
    val previewsStatus: String? = null,
    val strategy: OfflineDetailStrategy = OfflineDetailStrategy.STANDARD,
    val bothThemes: Boolean = false,
)

object PrepareTransitions {

    fun started(
        routeId: String,
        routeName: String,
        strategy: OfflineDetailStrategy,
        bothThemes: Boolean,
    ): PrepareState = PrepareState(
        routeId = routeId,
        routeName = routeName,
        running = true,
        phase = PreparePhase.MAP,
        strategy = strategy,
        bothThemes = bothThemes,
    )

    fun paused(state: PrepareState): PrepareState =
        if (!state.running || state.paused) state else state.copy(running = false, paused = true)

    fun resumed(state: PrepareState): PrepareState =
        if (state.running || !state.paused) {
            state
        } else {
            state.copy(running = true, paused = false, done = false, cancelled = false)
        }

    fun cancelled(state: PrepareState): PrepareState =
        state.copy(running = false, paused = false, cancelled = true)

    fun mapReady(state: PrepareState, skipped: Boolean): PrepareState =
        state.copy(mapSkipped = state.mapSkipped || skipped)

    fun previewsStarted(state: PrepareState): PrepareState =
        state.copy(phase = PreparePhase.PREVIEWS)

    fun completed(
        state: PrepareState,
        previewsSkipped: Boolean,
        previewsStatus: String?,
    ): PrepareState = state.copy(
        phase = PreparePhase.DONE,
        running = false,
        paused = false,
        done = true,
        previewsSkipped = state.previewsSkipped || previewsSkipped,
        previewsStatus = previewsStatus,
    )
}
