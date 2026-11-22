/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

enum class OfflineThemeMode { OFF, ALWAYS, ASK }

enum class OfflineCapDecision { FITS, EXCEEDS, UNLIMITED }

data class OfflinePackSizing(
    val routeId: String?,
    val trackHash: String?,
    val strategy: OfflineDetailStrategy,
    val theme: String,
    val bytes: Long,
)

data class OfflineExpiryGroup(
    val ids: List<Long>,
    val createdAt: Long?,
)

object OfflineLimits {

    const val MILLIS_PER_DAY = 86_400_000L
    const val BYTES_PER_MB = 1024L * 1024L

    fun decideCap(currentTotalBytes: Long, estimateBytes: Long, capMb: Int): OfflineCapDecision =
        decideCap(currentTotalBytes, estimateBytes, 0L, capMb)

    fun decideCap(
        currentTotalBytes: Long,
        estimateBytes: Long,
        reclaimableBytes: Long,
        capMb: Int,
    ): OfflineCapDecision {
        if (capMb <= 0) return OfflineCapDecision.UNLIMITED
        val cap = capMb.toLong().saturatedMultiply(BYTES_PER_MB)
        val remaining = (currentTotalBytes - reclaimableBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
        val after = remaining.saturatedAdd(estimateBytes)
        return if (after > cap) OfflineCapDecision.EXCEEDS else OfflineCapDecision.FITS
    }

    fun reclaimableBytes(
        packs: List<OfflinePackSizing>,
        routeId: String,
        trackHash: String,
        strategy: OfflineDetailStrategy,
        themes: Set<String>,
    ): Long = packs.filter { pack ->
        pack.strategy == strategy &&
            pack.theme in themes &&
            (
                pack.routeId == routeId ||
                    (pack.trackHash != null && pack.trackHash == trackHash)
                )
    }.sumOf { it.bytes.coerceAtLeast(0L) }

    fun capBytes(capMb: Int): Long? = if (capMb <= 0) null else capMb.toLong().saturatedMultiply(BYTES_PER_MB)

    fun ageDays(createdAtMs: Long?, nowMs: Long): Long? {
        if (createdAtMs == null || createdAtMs <= 0L) return null
        return ((nowMs - createdAtMs).coerceAtLeast(0L)) / MILLIS_PER_DAY
    }

    fun isExpired(createdAtMs: Long?, expiryDays: Int, nowMs: Long): Boolean {
        if (expiryDays <= 0 || createdAtMs == null || createdAtMs <= 0L) return false
        val limit = expiryDays.toLong().saturatedMultiply(MILLIS_PER_DAY)
        return nowMs - createdAtMs > limit
    }

    fun expiredGroupIds(
        groups: List<OfflineExpiryGroup>,
        autoDelete: Boolean,
        expiryDays: Int,
        nowMs: Long,
    ): Set<Long> {
        if (!autoDelete) return emptySet()
        return groups.asSequence()
            .filter { isExpired(it.createdAt, expiryDays, nowMs) }
            .flatMap { it.ids.asSequence() }
            .toSet()
    }

    private fun Long.saturatedMultiply(other: Long): Long {
        if (this == 0L || other == 0L) return 0L
        val result = this * other
        return if (result / other != this) Long.MAX_VALUE else result
    }

    private fun Long.saturatedAdd(other: Long): Long {
        val result = this + other
        return if (result < this) Long.MAX_VALUE else result
    }
}
