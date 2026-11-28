/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CscMeasurementTest {

    @Test
    fun parsesCrankOnly16BitRevolutions() {
        val sample = CscMeasurementParser.parse(
            byteArrayOf(0x02, 0x0C, 0x00, 0x00, 0x04),
        )
        assertEquals(12, sample?.crankRevolutions)
        assertEquals(1024, sample?.crankEventTime)
    }

    @Test
    fun ignoresWheelFieldsButParsesFollowingCrankData() {
        val sample = CscMeasurementParser.parse(
            byteArrayOf(
                0x03,
                0x01, 0x00, 0x00, 0x00, 0x02, 0x00,
                0x1E, 0x00, 0x00, 0x04,
            ),
        )
        assertEquals(30, sample?.crankRevolutions)
        assertEquals(1024, sample?.crankEventTime)
    }

    @Test
    fun rejectsWheelOnlyFrames() {
        assertNull(
            CscMeasurementParser.parse(
                byteArrayOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x00),
            ),
        )
    }

    @Test
    fun rejectsMissingOrTruncatedData() {
        assertNull(CscMeasurementParser.parse(ByteArray(0)))
        assertNull(CscMeasurementParser.parse(byteArrayOf(0x00)))
        assertNull(CscMeasurementParser.parse(byteArrayOf(0x01, 0x00, 0x00)))
        assertNull(CscMeasurementParser.parse(byteArrayOf(0x02, 0x00, 0x00, 0x00)))
        assertNull(
            CscMeasurementParser.parse(
                byteArrayOf(0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00),
            ),
        )
    }

    @Test
    fun calculatorNeedsTwoSamples() {
        val calculator = CscCalculator()
        val first = calculator.update(CscSample(crankRevolutions = 100, crankEventTime = 0))
        assertNull(first.cadenceRpm)
    }

    @Test
    fun computesCadenceAndHandlesCrankWrap() {
        val calculator = CscCalculator()
        calculator.update(CscSample(crankRevolutions = 65535, crankEventTime = 0))
        val reading = calculator.update(CscSample(crankRevolutions = 0, crankEventTime = 512))
        assertEquals(120.0, reading.cadenceRpm!!, 0.0001)
    }

    @Test
    fun zeroTimeDeltaIsIgnoredAndDoesNotAdvanceState() {
        val calculator = CscCalculator()
        calculator.update(CscSample(crankRevolutions = 5, crankEventTime = 100))
        val stale = calculator.update(CscSample(crankRevolutions = 6, crankEventTime = 100))
        assertNull(stale.cadenceRpm)
        val next = calculator.update(CscSample(crankRevolutions = 7, crankEventTime = 612))
        assertEquals(240.0, next.cadenceRpm!!, 0.0001)
    }

    @Test
    fun stationaryCrankReportsZeroCadence() {
        val calculator = CscCalculator()
        calculator.update(CscSample(crankRevolutions = 10, crankEventTime = 0))
        val reading = calculator.update(CscSample(crankRevolutions = 10, crankEventTime = 512))
        assertEquals(0.0, reading.cadenceRpm!!, 0.0001)
    }
}
