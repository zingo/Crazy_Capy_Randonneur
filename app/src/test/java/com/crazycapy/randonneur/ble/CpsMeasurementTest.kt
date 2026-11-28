/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpsMeasurementTest {

    @Test
    fun parsesInstantaneousPower() {
        val sample = CpsMeasurementParser.parse(byteArrayOf(0x00, 0x00, 0xFA.toByte(), 0x00))
        assertEquals(250, sample?.powerWatts)
        assertNull(sample?.crankRevolutions)
    }

    @Test
    fun parsesPowerWithWheelAndCrankData() {
        val sample = CpsMeasurementParser.parse(
            byteArrayOf(
                0x30, 0x00,
                0x10, 0x01,
                0xE8.toByte(), 0x03, 0x00, 0x00, 0x00, 0x02,
                0x0C, 0x00, 0x00, 0x01,
            ),
        )
        assertEquals(272, sample?.powerWatts)
        assertEquals(12, sample?.crankRevolutions)
        assertEquals(256, sample?.crankEventTime)
    }

    @Test
    fun skipsBalanceAndTorqueBeforeWheelData() {
        val sample = CpsMeasurementParser.parse(
            byteArrayOf(
                0x15, 0x00,
                0x2C, 0x01,
                0x32,
                0x00, 0x40,
                0x01, 0x00, 0x00, 0x00, 0x00, 0x04,
            ),
        )
        assertEquals(300, sample?.powerWatts)
    }

    @Test
    fun keepsPowerWhenOptionalFieldsAreTruncated() {
        val sample = CpsMeasurementParser.parse(
            byteArrayOf(0x10, 0x00, 0x10, 0x00, 0x01, 0x00),
        )
        assertEquals(16, sample?.powerWatts)
        assertNull(sample?.crankRevolutions)
    }

    @Test
    fun rejectsShortFrames() {
        assertNull(CpsMeasurementParser.parse(ByteArray(0)))
        assertNull(CpsMeasurementParser.parse(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun derivesCadenceFromCrankRevolutions() {
        val calculator = CpsCalculator()
        val first = calculator.update(CpsSample(200, crankRevolutions = 10, crankEventTime = 0))
        assertEquals(200, first.powerWatts)
        assertNull(first.cadenceRpm)
        val second = calculator.update(CpsSample(210, crankRevolutions = 11, crankEventTime = 1024))
        assertEquals(210, second.powerWatts)
        assertEquals(60.0, second.cadenceRpm!!, 0.0001)
    }

    @Test
    fun ignoresZeroCadenceTimeDelta() {
        val calculator = CpsCalculator()
        calculator.update(CpsSample(200, crankRevolutions = 10, crankEventTime = 100))
        val stale = calculator.update(CpsSample(201, crankRevolutions = 11, crankEventTime = 100))
        assertNull(stale.cadenceRpm)
    }

    @Test
    fun handlesCadenceWrap() {
        val calculator = CpsCalculator()
        calculator.update(CpsSample(200, crankRevolutions = 65535, crankEventTime = 0))
        val reading = calculator.update(CpsSample(200, crankRevolutions = 0, crankEventTime = 512))
        assertEquals(120.0, reading.cadenceRpm!!, 0.0001)
    }
}
