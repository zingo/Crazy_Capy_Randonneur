/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorosPollPolicyTest {

    @Test
    fun pollsOnlyWhileRideIsActiveAndMoving() {
        assertTrue(CorosPollPolicy.shouldPoll(rideActive = true, paused = false))
        assertFalse(CorosPollPolicy.shouldPoll(rideActive = true, paused = true))
        assertFalse(CorosPollPolicy.shouldPoll(rideActive = false, paused = false))
    }

    @Test
    fun backsOffOnRepeatedFailuresAndCaps() {
        assertEquals(30_000L, CorosPollPolicy.nextDelayMs(0))
        assertEquals(60_000L, CorosPollPolicy.nextDelayMs(1))
        assertEquals(120_000L, CorosPollPolicy.nextDelayMs(2))
        assertEquals(240_000L, CorosPollPolicy.nextDelayMs(3))
        assertEquals(300_000L, CorosPollPolicy.nextDelayMs(4))
        assertEquals(300_000L, CorosPollPolicy.nextDelayMs(50))
    }

    @Test
    fun windowIsLastFiveMinutes() {
        val window = CorosPollPolicy.liveWindow(
            nowSec = 1_800_000_000,
            activityStartSec = 1_799_999_000,
            activityEndSec = null,
        )

        assertEquals(1_799_999_700L, window?.startSec)
        assertEquals(1_800_000_000L, window?.endSec)
    }

    @Test
    fun windowStartsAtActivityStart() {
        val window = CorosPollPolicy.liveWindow(
            nowSec = 1_800_000_000,
            activityStartSec = 1_799_999_900,
            activityEndSec = null,
        )

        assertEquals(1_799_999_900L, window?.startSec)
    }

    @Test
    fun windowEndsAtActivityEnd() {
        val window = CorosPollPolicy.liveWindow(
            nowSec = 1_800_000_000,
            activityStartSec = 1_799_999_000,
            activityEndSec = 1_799_999_500,
        )

        assertEquals(1_799_999_200L, window?.startSec)
        assertEquals(1_799_999_500L, window?.endSec)
    }

    @Test
    fun emptyWindowIsRejected() {
        val window = CorosPollPolicy.liveWindow(
            nowSec = 1_800_000_000,
            activityStartSec = 1_800_000_000,
            activityEndSec = null,
        )

        assertNull(window)
    }
}
