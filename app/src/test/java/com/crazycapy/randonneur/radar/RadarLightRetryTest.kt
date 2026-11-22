/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarLightRetryTest {

    @Test
    fun theScheduleStartsImmediatelyAndStaysBounded() {
        assertEquals(listOf(0L, 500L, 1_000L, 2_000L), LIGHT_RETRY_DELAYS_MS.toList())
        assertTrue(LIGHT_RETRY_DELAYS_MS.all { it >= 0L })
        assertTrue(LIGHT_RETRY_DELAYS_MS.sum() <= 4_000L)
    }

    @Test
    fun successStopsTheRetries() {
        assertFalse(shouldRetryLightMode(succeeded = true, sameService = true, nextAttempt = 0))
        assertFalse(shouldRetryLightMode(succeeded = true, sameService = true, nextAttempt = 3))
    }

    @Test
    fun aFalseAnswerRetriesWhileTheServiceStaysConnected() {
        assertTrue(shouldRetryLightMode(succeeded = false, sameService = true, nextAttempt = 1))
        assertTrue(shouldRetryLightMode(succeeded = false, sameService = true, nextAttempt = 3))
    }

    @Test
    fun aServiceThatWentAwayStopsTheRetries() {
        assertFalse(shouldRetryLightMode(succeeded = false, sameService = false, nextAttempt = 1))
    }

    @Test
    fun theRetriesEndAfterTheLastScheduledAttempt() {
        assertFalse(shouldRetryLightMode(succeeded = false, sameService = true, nextAttempt = 4))
        assertFalse(shouldRetryLightMode(succeeded = false, sameService = true, nextAttempt = 99))
    }
}
