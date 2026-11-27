/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorosRideMatchTest {

    private fun activity(
        id: String,
        start: Long,
        end: Long? = null,
        duration: Double? = null,
    ) = CorosSportRecord(id, 200, null, start, end, null, duration)

    @Test
    fun matchesExactOverlap() {
        val match = CorosRideMatcher.select(1000, 2000, listOf(activity("a", 1000, 2000)))

        assertEquals("a", match?.record?.labelId)
        assertEquals(1000L, match?.overlapSec)
    }

    @Test
    fun matchesWhenWatchStartsEarlierAndEndsLater() {
        val match = CorosRideMatcher.select(1000, 2000, listOf(activity("a", 900, 2200)))

        assertEquals("a", match?.record?.labelId)
        assertEquals(1000L, match?.overlapSec)
        assertEquals(900L, match?.activityStartSec)
        assertEquals(2200L, match?.activityEndSec)
    }

    @Test
    fun matchesPartialOverlap() {
        val match = CorosRideMatcher.select(0, 1200, listOf(activity("a", 600, 1800)))

        assertEquals("a", match?.record?.labelId)
        assertEquals(600L, match?.overlapSec)
    }

    @Test
    fun picksLargestOverlapFromMultipleCandidates() {
        val match = CorosRideMatcher.select(
            0,
            2000,
            listOf(
                activity("small", 0, 900),
                activity("large", 200, 1900),
                activity("medium", 100, 1000),
            ),
        )

        assertEquals("large", match?.record?.labelId)
        assertEquals(1700L, match?.overlapSec)
    }

    @Test
    fun tieBreaksByNearestStart() {
        val match = CorosRideMatcher.select(
            0,
            1000,
            listOf(
                activity("early", -1000, 1000),
                activity("onTime", 0, 2000),
            ),
        )

        assertEquals("onTime", match?.record?.labelId)
    }

    @Test
    fun returnsNullWhenNothingOverlaps() {
        assertNull(CorosRideMatcher.select(0, 1000, listOf(activity("a", 2000, 3000))))
        assertNull(CorosRideMatcher.select(0, 1000, emptyList()))
    }

    @Test
    fun acceptsOverlapAtFiveMinuteBoundary() {
        val match = CorosRideMatcher.select(0, 1000, listOf(activity("a", 700, 1700)))

        assertEquals("a", match?.record?.labelId)
        assertEquals(300L, match?.overlapSec)
    }

    @Test
    fun rejectsJustBelowFiveMinutesWhenBelowHalfOfShorterRide() {
        assertNull(CorosRideMatcher.select(0, 1000, listOf(activity("a", 701, 1700))))
    }

    @Test
    fun acceptsHalfOfAShortRide() {
        val match = CorosRideMatcher.select(0, 400, listOf(activity("a", 200, 800)))

        assertEquals("a", match?.record?.labelId)
        assertEquals(200L, match?.overlapSec)
    }

    @Test
    fun rejectsJustBelowHalfOfAShortRide() {
        assertNull(CorosRideMatcher.select(0, 400, listOf(activity("a", 201, 800))))
    }

    @Test
    fun derivesActivityEndFromDurationWhenEndMissing() {
        val match = CorosRideMatcher.select(0, 1000, listOf(activity("a", 0, null, 1200.0)))

        assertEquals("a", match?.record?.labelId)
        assertEquals(1000L, match?.overlapSec)
    }

    @Test
    fun ignoresRecordsWithoutTiming() {
        assertNull(CorosRideMatcher.select(0, 1000, listOf(activity("a", 0, null, null))))
        assertNull(CorosRideMatcher.select(1000, 1000, listOf(activity("a", 0, 2000))))
    }
}
