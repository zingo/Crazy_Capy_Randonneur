/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HrMeasurementTest {

    @Test
    fun parsesEightBitRate() {
        val sample = HrMeasurementParser.parse(byteArrayOf(0x00, 72))
        assertEquals(72, sample?.bpm)
        assertFalse(sample!!.contactSupported)
        assertFalse(sample.contactDetected)
    }

    @Test
    fun parsesSixteenBitRate() {
        val sample = HrMeasurementParser.parse(byteArrayOf(0x01, 0x2C, 0x01))
        assertEquals(300, sample?.bpm)
    }

    @Test
    fun parsesContactBits() {
        val sample = HrMeasurementParser.parse(byteArrayOf(0x06, 65))
        assertEquals(65, sample?.bpm)
        assertTrue(sample!!.contactSupported)
        assertFalse(sample.contactDetected)
    }

    @Test
    fun detectsContactWhenBitThreeIsSet() {
        val sample = HrMeasurementParser.parse(byteArrayOf(0x0E, 65))
        assertTrue(sample!!.contactSupported)
        assertTrue(sample.contactDetected)
    }

    @Test
    fun skipsEnergyExpendedAndIgnoresRrIntervals() {
        val sample = HrMeasurementParser.parse(
            byteArrayOf(0x30, 80, 0x10, 0x00, 0x00, 0x04, 0x04, 0x04),
        )
        assertEquals(80, sample?.bpm)
    }

    @Test
    fun parsesWideFrameWithEnergyAndRr() {
        val sample = HrMeasurementParser.parse(
            byteArrayOf(0x31, 0x2C, 0x01, 0x20, 0x00, 0x00, 0x04),
        )
        assertEquals(300, sample?.bpm)
    }

    @Test
    fun rejectsShortFrames() {
        assertNull(HrMeasurementParser.parse(byteArrayOf()))
        assertNull(HrMeasurementParser.parse(byteArrayOf(0x00)))
        assertNull(HrMeasurementParser.parse(byteArrayOf(0x01, 0x2C)))
    }

    @Test
    fun rejectsMissingEnergyOrOddRrFrames() {
        assertNull(HrMeasurementParser.parse(byteArrayOf(0x10, 70, 0x00)))
        assertNull(HrMeasurementParser.parse(byteArrayOf(0x20, 70, 0x00, 0x01, 0x00)))
    }

    @Test
    fun rejectsZeroRate() {
        assertNull(HrMeasurementParser.parse(byteArrayOf(0x00, 0)))
        assertNull(HrMeasurementParser.parse(byteArrayOf(0x01, 0x00, 0x00)))
    }
}
