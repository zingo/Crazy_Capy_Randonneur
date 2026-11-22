/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

data class HrSample(
    val bpm: Int,
    val contactSupported: Boolean,
    val contactDetected: Boolean,
)

object HrMeasurementParser {
    fun parse(data: ByteArray): HrSample? {
        if (data.size < 2) return null
        val flags = data[0].toInt() and 0xFF
        val wide = flags and 0x01 != 0
        val contactSupported = flags and 0x04 != 0
        val contactDetected = flags and 0x08 != 0
        val energyPresent = flags and 0x10 != 0
        val rrPresent = flags and 0x20 != 0

        var offset = 1
        val bpm = if (wide) {
            if (data.size < offset + 2) return null
            val value = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            value
        } else {
            val value = data[offset].toInt() and 0xFF
            offset += 1
            value
        }
        if (bpm <= 0 || bpm > 0xFFFF) return null

        if (energyPresent) {
            if (data.size < offset + 2) return null
            offset += 2
        }
        if (rrPresent) {
            if ((data.size - offset) % 2 != 0) return null
        }
        return HrSample(
            bpm = bpm,
            contactSupported = contactSupported,
            contactDetected = contactDetected,
        )
    }
}
