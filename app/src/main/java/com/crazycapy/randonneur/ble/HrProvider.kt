/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * HrProvider — BLE heart-rate sensor abstraction
 *
 *   HrProvider (interface):
 *     isConnected  +  heartRate (flow)  +  connect()  +  disconnect()
 *
 *   StubHrProvider: always disconnected, emits 0 bpm
 *   (Coros BLE + standard BLE HR Service 0x180D land here later)
 */
package com.crazycapy.randonneur.ble

/**
 * Heart-rate source abstraction. Coros BLE + standard BLE HR Service (0x180D)
 * land here later; for now a no-sensor [StubHrProvider] keeps the HUD wire-up
 * in place and testable.
 */
interface HrProvider {
    /** Latest heart rate in bpm, or null when none is available. */
    fun currentHr(): Int?
}

/** No sensor connected yet — always reports "no HR". */
object StubHrProvider : HrProvider {
    override fun currentHr(): Int? = null
}