/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

enum class OfflineThemeMode { OFF, ALWAYS, ASK }

enum class OfflineCapDecision { FITS, EXCEEDS, UNLIMITED }

object OfflineLimits {

    const val MILLIS_PER_DAY = 86_400_000L
    const val BYTES_PER_MB = 1024L * 1024L

    fun decideCap(currentTotalBytes: Long, estimateBytes: Long, capMb: Int): OfflineCapDecision {
        if (capMb <= 0) return OfflineCapDecision.UNLIMITED
        val cap = capMb.toLong().saturatedMultiply(BYTES_PER_MB)
        val after = currentTotalBytes.saturatedAdd(estimateBytes)
        return if (after > cap) OfflineCapDecision.EXCEEDS else OfflineCapDecision.FITS
    }

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
