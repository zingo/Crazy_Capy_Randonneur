/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

data class OfflinePackGroup(
    val name: String,
    val strategy: OfflineDetailStrategy,
    val style: String,
    val theme: String,
    val segments: Int,
    val bytes: Long,
    val createdAt: Long?,
    val ageDays: Long?,
    val expired: Boolean,
    val status: OfflinePackStatus,
    val ids: List<Long>,
)

fun groupOfflinePacks(
    packs: List<OfflinePackInfo>,
    nowMs: Long,
    expiryDays: Int,
): List<OfflinePackGroup> =
    packs.groupBy { pack ->
        val routeId = pack.routeId
        if (routeId == null) {
            "legacy:${pack.id}"
        } else {
            "$routeId|${pack.trackHash}|${pack.strategy.name}|${pack.style}|${pack.theme}"
        }
    }.values.map { group ->
        val first = group.first()
        val status = when {
            group.all { it.status == OfflinePackStatus.COMPLETE } -> OfflinePackStatus.COMPLETE
            group.any { it.status == OfflinePackStatus.DOWNLOADING } -> OfflinePackStatus.DOWNLOADING
            else -> OfflinePackStatus.INCOMPLETE
        }
        val createdAt = group.maxOfOrNull { it.createdAt ?: 0L }?.takeIf { it > 0L }
        OfflinePackGroup(
            name = first.routeName,
            strategy = first.strategy,
            style = first.style,
            theme = first.theme,
            segments = group.size,
            bytes = group.sumOf { it.bytes ?: it.completedBytes },
            createdAt = createdAt,
            ageDays = OfflineLimits.ageDays(createdAt, nowMs),
            expired = OfflineLimits.isExpired(createdAt, expiryDays, nowMs),
            status = status,
            ids = group.map { it.id },
        )
    }.sortedWith(
        compareByDescending<OfflinePackGroup> { it.createdAt ?: 0L }.thenBy { it.name },
    )
