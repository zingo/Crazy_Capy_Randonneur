/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePackGroupsTest {

    private fun pack(
        id: Long,
        routeId: String?,
        name: String = "Route",
        theme: String = "dark",
        tuning: OfflineTuning = OfflineTuning.DEFAULT,
        bytes: Long = 1L,
    ): OfflinePackInfo = OfflinePackInfo(
        id = id,
        routeName = name,
        routeId = routeId,
        trackHash = "hash-$routeId",
        style = "style-$theme",
        theme = theme,
        bytes = bytes,
        createdAt = 1L,
        status = OfflinePackStatus.COMPLETE,
        completedBytes = 0L,
        completedResources = 0L,
        requiredResources = 0L,
        strategy = OfflineDetailStrategy.STANDARD,
        tuning = tuning,
        segmentIndex = 0,
        segmentCount = 1,
    )

    @Test
    fun groupingSeparatesTuningThemeAndLegacyRegions() {
        val packs = listOf(
            pack(1L, "route-a"),
            pack(2L, "route-a", tuning = OfflineTuning.DEFAULT.copy(segmentMeters = 5_000.0)),
            pack(3L, "route-a", theme = "light"),
            pack(4L, null, name = "Old pack"),
        )
        val groups = groupOfflinePacks(packs, nowMs = 100L, expiryDays = 0)
        assertEquals(4, groups.size)
        assertEquals(3, groups.count { !it.legacy })
        assertEquals(1, groups.count { it.legacy })
    }

    @Test
    fun onlyLibraryRoutesAreRefreshable() {
        val packs = listOf(pack(1L, "route-a"), pack(2L, null))
        val groups = groupOfflinePacks(packs, nowMs = 100L, expiryDays = 0)
        val route = groups.first { it.routeId == "route-a" }
        val legacy = groups.first { it.legacy }
        assertTrue(OfflineRefresh.canRefresh(route, setOf("route-a")))
        assertFalse(OfflineRefresh.canRefresh(route, emptySet()))
        assertFalse(OfflineRefresh.canRefresh(legacy, setOf("route-a")))
    }

    @Test
    fun refreshReclaimsOnlyTheSelectedGroupAndRespectsCap() {
        val mb = OfflineLimits.BYTES_PER_MB
        val packs = listOf(
            pack(1L, "route-a", bytes = 7 * mb),
            pack(2L, "route-b", name = "Other", theme = "light", bytes = 2 * mb),
        )
        val groups = groupOfflinePacks(packs, nowMs = 100L, expiryDays = 0)
        val target = groups.first { it.routeId == "route-a" }
        val total = groups.sumOf { it.bytes }
        val estimate = OfflineEstimate(
            bytes = 7 * mb,
            segments = 1,
            themes = 1,
            tiles = 1L,
            bytesLow = 6 * mb,
            bytesHigh = 8 * mb,
        )
        val fits = OfflineRefresh.plan(target, total, estimate, capMb = 10)
        assertEquals(target.ids, fits.ids)
        assertEquals(7 * mb, fits.reclaimableBytes)
        assertTrue(fits.allowed)

        val exceeds = OfflineRefresh.plan(target, total, estimate, capMb = 9)
        assertFalse(exceeds.allowed)
    }
}
