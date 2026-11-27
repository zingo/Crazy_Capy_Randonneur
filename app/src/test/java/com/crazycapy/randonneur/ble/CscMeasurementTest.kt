/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CscMeasurementTest {

    @Test
    fun parsesWheelOnly32BitRevolutions() {
        val sample = CscMeasurementParser.parse(
            byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x00),
        )
        assertEquals(256L, sample?.wheelRevolutions)
        assertEquals(2, sample?.wheelEventTime)
        assertNull(sample?.crankRevolutions)
    }

    @Test
    fun parsesCrankOnly16BitRevolutions() {
        val sample = CscMeasurementParser.parse(
            byteArrayOf(0x02, 0x0C, 0x00, 0x00, 0x04),
        )
        assertEquals(12, sample?.crankRevolutions)
        assertEquals(1024, sample?.crankEventTime)
        assertNull(sample?.wheelRevolutions)
    }

    @Test
    fun parsesBothWheelAndCrank() {
        val sample = CscMeasurementParser.parse(
            byteArrayOf(
                0x03,
                0x01, 0x00, 0x00, 0x00, 0x02, 0x00,
                0x1E, 0x00, 0x00, 0x04,
            ),
        )
        assertEquals(1L, sample?.wheelRevolutions)
        assertEquals(2, sample?.wheelEventTime)
        assertEquals(30, sample?.crankRevolutions)
        assertEquals(1024, sample?.crankEventTime)
    }

    @Test
    fun rejectsMissingOrTruncatedData() {
        assertNull(CscMeasurementParser.parse(ByteArray(0)))
        assertNull(CscMeasurementParser.parse(byteArrayOf(0x00)))
        assertNull(CscMeasurementParser.parse(byteArrayOf(0x01, 0x00, 0x00)))
        assertNull(CscMeasurementParser.parse(byteArrayOf(0x02, 0x00, 0x00, 0x00)))
    }

    @Test
    fun calculatorNeedsTwoSamples() {
        val calculator = CscCalculator()
        val first = calculator.update(CscSample(100L, 0), 2096)
        assertNull(first.speedKmh)
        assertNull(first.cadenceRpm)
    }

    @Test
    fun computesSpeedFromCircumference() {
        val calculator = CscCalculator()
        calculator.update(CscSample(100L, 0), 2096)
        val reading = calculator.update(CscSample(101L, 512), 2096)
        assertEquals(15.0912, reading.speedKmh!!, 0.0001)
    }

    @Test
    fun circumferenceChangesSpeed() {
        val calculator = CscCalculator()
        calculator.update(CscSample(0L, 0), 2096)
        val reading = calculator.update(CscSample(1L, 1024), 2000)
        assertEquals(7.2, reading.speedKmh!!, 0.0001)
    }

    @Test
    fun computesCadenceAndHandlesCrankWrap() {
        val calculator = CscCalculator()
        calculator.update(CscSample(crankRevolutions = 65535, crankEventTime = 0), 2096)
        val reading = calculator.update(CscSample(crankRevolutions = 0, crankEventTime = 512), 2096)
        assertEquals(120.0, reading.cadenceRpm!!, 0.0001)
    }

    @Test
    fun handlesWheelRevolutions32BitWrap() {
        val calculator = CscCalculator()
        calculator.update(CscSample(0xFFFFFFFFL, 0), 2096)
        val reading = calculator.update(CscSample(0L, 1024), 2096)
        assertEquals(7.5456, reading.speedKmh!!, 0.0001)
    }

    @Test
    fun zeroTimeDeltaIsIgnoredAndDoesNotAdvanceState() {
        val calculator = CscCalculator()
        calculator.update(CscSample(10L, 100, 5, 100), 2096)
        val stale = calculator.update(CscSample(11L, 100, 6, 100), 2096)
        assertNull(stale.speedKmh)
        assertNull(stale.cadenceRpm)
        val next = calculator.update(CscSample(12L, 612, 7, 612), 2096)
        assertNotNull(next.speedKmh)
        assertNotNull(next.cadenceRpm)
    }

    @Test
    fun stationaryEventReportsZeroSpeed() {
        val calculator = CscCalculator()
        calculator.update(CscSample(10L, 0), 2096)
        val reading = calculator.update(CscSample(10L, 512), 2096)
        assertEquals(0.0, reading.speedKmh!!, 0.0001)
    }
}
