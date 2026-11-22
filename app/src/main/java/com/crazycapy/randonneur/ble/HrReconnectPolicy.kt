/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

object HrReconnectPolicy {
    private val delaysMs = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)

    fun delayForAttempt(attempt: Int): Long? =
        if (attempt in delaysMs.indices) delaysMs[attempt] else null
}
