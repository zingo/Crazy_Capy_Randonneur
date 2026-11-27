/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.state

enum class SensorSource {
    BLE,
    COROS,
    DEBUG;

    val priority: Int
        get() = when (this) {
            BLE -> 3
            DEBUG -> 2
            COROS -> 1
        }
}
