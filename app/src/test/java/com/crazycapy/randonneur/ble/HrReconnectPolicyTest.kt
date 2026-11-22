/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrReconnectPolicyTest {

    @Test
    fun backsOffWithIncreasingDelays() {
        val delays = (0..3).map { HrReconnectPolicy.delayForAttempt(it) }
        assertArrayEquals(
            arrayOf<Long?>(5_000L, 15_000L, 30_000L, 60_000L),
            delays.toTypedArray(),
        )
    }

    @Test
    fun stopsAfterLastAttempt() {
        assertNull(HrReconnectPolicy.delayForAttempt(4))
        assertNull(HrReconnectPolicy.delayForAttempt(100))
        assertNull(HrReconnectPolicy.delayForAttempt(-1))
    }

    @Test
    fun firstAttemptIsQuickButNotTight() {
        assertEquals(5_000L, HrReconnectPolicy.delayForAttempt(0))
    }
}
