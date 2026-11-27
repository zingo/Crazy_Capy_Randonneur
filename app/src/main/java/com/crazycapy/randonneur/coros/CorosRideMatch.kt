/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import kotlin.math.abs
import kotlin.math.min

data class CorosMatch(
    val record: CorosSportRecord,
    val activityStartSec: Long,
    val activityEndSec: Long,
    val overlapSec: Long,
)

object CorosRideMatcher {
    const val MIN_OVERLAP_SEC = 300L

    fun select(
        rideStartSec: Long,
        rideEndSec: Long,
        records: List<CorosSportRecord>,
    ): CorosMatch? {
        if (rideEndSec <= rideStartSec) return null
        val rideLength = rideEndSec - rideStartSec
        return records.asSequence()
            .mapNotNull { record ->
                val window = window(record) ?: return@mapNotNull null
                val overlap = overlapSec(
                    rideStartSec,
                    rideEndSec,
                    window.first,
                    window.second,
                )
                if (!isMeaningful(overlap, rideLength, window.second - window.first)) {
                    return@mapNotNull null
                }
                CorosMatch(record, window.first, window.second, overlap)
            }
            .sortedWith(
                compareByDescending<CorosMatch> { it.overlapSec }
                    .thenBy { abs(it.activityStartSec - rideStartSec) }
                    .thenByDescending { it.activityStartSec },
            )
            .firstOrNull()
    }

    fun window(record: CorosSportRecord): Pair<Long, Long>? {
        val start = record.startTimestampSec ?: return null
        val duration = record.durationSec?.toLong()?.coerceAtLeast(0L) ?: 0L
        val end = record.endTimestampSec ?: (start + duration).takeIf { duration > 0 } ?: return null
        if (end <= start) return null
        return start to end
    }

    fun overlapSec(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Long =
        (min(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0L)

    fun isMeaningful(overlapSec: Long, rideLengthSec: Long, activityLengthSec: Long): Boolean {
        if (overlapSec <= 0L) return false
        if (overlapSec >= MIN_OVERLAP_SEC) return true
        val shorter = min(rideLengthSec, activityLengthSec)
        return shorter > 0L && overlapSec * 2 >= shorter
    }
}
