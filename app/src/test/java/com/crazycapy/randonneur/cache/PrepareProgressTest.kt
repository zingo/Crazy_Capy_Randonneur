/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepareProgressTest {

    private fun input(
        phase: PreparePhase = PreparePhase.MAP,
        paused: Boolean = false,
        mapSkipped: Boolean = false,
        previewsSkipped: Boolean = false,
        mapPercent: Float? = null,
        completedSegments: Int = 0,
        segmentCount: Int = 0,
        completedBytes: Long = 0,
        completedResources: Long = 0,
        requiredResources: Long = 0,
        previewProgress: Float? = null,
        previewStatus: String? = null,
    ) = PrepareProgressInput(
        routeName = "Test route",
        phase = phase,
        paused = paused,
        mapSkipped = mapSkipped,
        previewsSkipped = previewsSkipped,
        mapPercent = mapPercent,
        completedSegments = completedSegments,
        segmentCount = segmentCount,
        completedBytes = completedBytes,
        completedResources = completedResources,
        requiredResources = requiredResources,
        previewProgress = previewProgress,
        previewStatus = previewStatus,
    )

    @Test
    fun mapPercentCombinesFinishedSegmentsAndCurrentSegment() {
        val percent = PrepareProgress.percent(
            input(completedSegments = 2, segmentCount = 4, mapPercent = 0.5f),
        )
        assertEquals(63, percent)
    }

    @Test
    fun mapPercentWithoutCurrentSegmentProgressUsesFinishedSegments() {
        val percent = PrepareProgress.percent(input(completedSegments = 3, segmentCount = 4))
        assertEquals(75, percent)
    }

    @Test
    fun mapPercentStartsAtZeroAndIsUnknownWithoutSegments() {
        assertEquals(0, PrepareProgress.percent(input(completedSegments = 0, segmentCount = 4)))
        assertNull(PrepareProgress.percent(input(completedSegments = 0, segmentCount = 0)))
    }

    @Test
    fun mapPercentIsUnknownWhenMapWasAlreadyCached() {
        assertNull(
            PrepareProgress.percent(
                input(mapSkipped = true, completedSegments = 4, segmentCount = 4, mapPercent = 1f),
            ),
        )
    }

    @Test
    fun previewPercentUsesPreviewProgress() {
        assertEquals(
            40,
            PrepareProgress.percent(input(phase = PreparePhase.PREVIEWS, previewProgress = 0.4f)),
        )
        assertNull(PrepareProgress.percent(input(phase = PreparePhase.PREVIEWS)))
    }

    @Test
    fun doneIsAlwaysComplete() {
        assertEquals(100, PrepareProgress.percent(input(phase = PreparePhase.DONE)))
    }

    @Test
    fun pausedSummaryCarriesPhaseDetail() {
        val summary = PrepareProgress.summary(
            input(
                phase = PreparePhase.PREVIEWS,
                paused = true,
                previewProgress = 0.5f,
                previewStatus = "Pre-caching route · turn 5/10",
            ),
        )
        assertTrue(summary.startsWith("Paused · "))
        assertTrue(summary.contains("turn 5/10"))
        assertEquals(
            "Offline preparation paused",
            PrepareProgress.title(input(paused = true)),
        )
    }

    @Test
    fun startPauseResumeTransitions() {
        val started = PrepareTransitions.started(
            routeId = "route",
            routeName = "Route",
            strategy = OfflineDetailStrategy.STANDARD,
            bothThemes = false,
        )
        assertTrue(started.running)
        assertFalse(started.paused)

        val paused = PrepareTransitions.paused(started)
        assertFalse(paused.running)
        assertTrue(paused.paused)
        assertEquals(paused, PrepareTransitions.paused(paused))

        val resumed = PrepareTransitions.resumed(paused)
        assertTrue(resumed.running)
        assertFalse(resumed.paused)
        assertEquals(resumed, PrepareTransitions.resumed(resumed))
    }

    @Test
    fun cancelAndCompleteClearRunningState() {
        val started = PrepareTransitions.started(
            routeId = "route",
            routeName = "Route",
            strategy = OfflineDetailStrategy.TURN_FOCUSED,
            bothThemes = true,
        )
        val cancelled = PrepareTransitions.cancelled(PrepareTransitions.paused(started))
        assertFalse(cancelled.running)
        assertFalse(cancelled.paused)
        assertTrue(cancelled.cancelled)

        val done = PrepareTransitions.completed(started, previewsSkipped = true, previewsStatus = null)
        assertFalse(done.running)
        assertFalse(done.paused)
        assertTrue(done.done)
        assertTrue(done.previewsSkipped)
        assertEquals(PreparePhase.DONE, done.phase)
    }
}
