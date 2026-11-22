/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

data class OfflineRefreshPlan(
    val ids: List<Long>,
    val reclaimableBytes: Long,
    val estimateBytes: Long,
    val capDecision: OfflineCapDecision,
) {
    val allowed: Boolean get() = capDecision != OfflineCapDecision.EXCEEDS
}

object OfflineRefresh {

    fun canRefresh(group: OfflinePackGroup, libraryRouteIds: Set<String>): Boolean =
        !group.legacy && group.routeId != null && group.routeId in libraryRouteIds

    fun plan(
        group: OfflinePackGroup,
        totalBytes: Long,
        estimate: OfflineEstimate,
        capMb: Int,
    ): OfflineRefreshPlan {
        val reclaimable = group.bytes.coerceAtLeast(0L)
        val estimateBytes = estimate.bytesHigh
        return OfflineRefreshPlan(
            ids = group.ids,
            reclaimableBytes = reclaimable,
            estimateBytes = estimateBytes,
            capDecision = OfflineLimits.decideCap(totalBytes, estimateBytes, reclaimable, capMb),
        )
    }
}
