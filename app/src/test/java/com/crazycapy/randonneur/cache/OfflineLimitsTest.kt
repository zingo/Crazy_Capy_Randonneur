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

class OfflineLimitsTest {

    @Test
    fun unlimitedCapAlwaysFits() {
        assertEquals(
            OfflineCapDecision.UNLIMITED,
            OfflineLimits.decideCap(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, 0),
        )
        assertNull(OfflineLimits.capBytes(0))
    }

    @Test
    fun capFitsAtBoundary() {
        val cap = 10
        val capBytes = cap * OfflineLimits.BYTES_PER_MB
        assertEquals(
            OfflineCapDecision.FITS,
            OfflineLimits.decideCap(capBytes / 2, capBytes / 2, cap),
        )
    }

    @Test
    fun capExceeds() {
        val cap = 10
        val capBytes = cap * OfflineLimits.BYTES_PER_MB
        assertEquals(
            OfflineCapDecision.EXCEEDS,
            OfflineLimits.decideCap(capBytes, 1L, cap),
        )
    }

    @Test
    fun expiryOffNeverExpires() {
        val old = 1_000L
        assertFalse(OfflineLimits.isExpired(old, 0, Long.MAX_VALUE / 2))
        assertNull(OfflineLimits.ageDays(null, old))
    }

    @Test
    fun expiredWhenOlderThanLimit() {
        val now = 100L * OfflineLimits.MILLIS_PER_DAY
        val created = now - 31L * OfflineLimits.MILLIS_PER_DAY
        assertTrue(OfflineLimits.isExpired(created, 30, now))
        assertEquals(31L, OfflineLimits.ageDays(created, now))
    }

    @Test
    fun notExpiredAtBoundary() {
        val now = 100L * OfflineLimits.MILLIS_PER_DAY
        val created = now - 30L * OfflineLimits.MILLIS_PER_DAY
        assertFalse(OfflineLimits.isExpired(created, 30, now))
    }
}
