/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

interface HrProvider {
    fun currentHr(): Int?
}

object StubHrProvider : HrProvider {
    override fun currentHr(): Int? = null
}
