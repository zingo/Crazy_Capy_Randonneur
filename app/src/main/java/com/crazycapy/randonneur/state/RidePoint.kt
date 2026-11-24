/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.state

data class RidePoint(
    val lat: Double,
    val lon: Double,
    val timeMs: Long,
    val eleM: Double? = null,
    val hr: Int? = null,
    val cadence: Int? = null,
)
