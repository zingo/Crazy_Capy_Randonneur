/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineTuningTest {

    @Test
    fun defaultsMatchLegacyPackGeometry() {
        val tuning = OfflineTuning.fromSettings(10.0, 1.5, 1.0, 14)
        assertEquals(OfflineTuning.DEFAULT, tuning)
        assertEquals(10_000.0, tuning.segmentMeters, 0.0)
        assertEquals(1_500.0, tuning.corridorPadMeters, 0.0)
        assertEquals(1_000.0, tuning.turnPadMeters, 0.0)
        assertEquals(14, tuning.maxZoom)
    }

    @Test
    fun signatureRoundTrips() {
        val tuning = OfflineTuning.fromSettings(12.0, 2.5, 0.5, 16)
        assertEquals(tuning, OfflineTuning.parseSignature(tuning.signature))
        assertNull(OfflineTuning.parseSignature(null))
        assertNull(OfflineTuning.parseSignature(""))
        assertNull(OfflineTuning.parseSignature("bad"))
        assertNull(OfflineTuning.parseSignature("1000|1500|1000|14"))
        assertNull(OfflineTuning.parseSignature("10000|1500|1000|25"))
    }

    @Test
    fun tuningChangesChangeSignature() {
        val base = OfflineTuning.DEFAULT
        assertNotEquals(base.signature, base.copy(segmentMeters = 5_000.0).signature)
        assertNotEquals(base.signature, base.copy(corridorPadMeters = 2_000.0).signature)
        assertNotEquals(base.signature, base.copy(turnPadMeters = 2_000.0).signature)
        assertNotEquals(base.signature, base.copy(maxZoom = 16).signature)
    }

    @Test
    fun invalidValuesFallBackToDefaults() {
        val tuning = OfflineTuning.fromSettings(
            segmentKm = Double.NaN,
            corridorPadKm = -1.0,
            turnPadKm = 0.0,
            maxZoom = 99,
        )
        assertEquals(OfflineTuning.DEFAULT, tuning)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val tuning = OfflineTuning.fromSettings(100.0, 20.0, 10.0, 12)
        assertEquals(50_000.0, tuning.segmentMeters, 0.0)
        assertEquals(10_000.0, tuning.corridorPadMeters, 0.0)
        assertEquals(5_000.0, tuning.turnPadMeters, 0.0)
        assertEquals(14, tuning.maxZoom)
    }
}
