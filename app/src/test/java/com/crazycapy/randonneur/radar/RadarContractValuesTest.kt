/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.radar

import es.jjrh.bikeradar.ipc.RadarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The contract files are copied from another project and can be re-copied.
 * These are the values this app depends on, written as literals so a drifted
 * re-copy fails here rather than on a rider's radar.
 */
class RadarContractValuesTest {

    @Test
    fun theWireVersionThisBuildReadsIsFixed() {
        // Read before registering. If this moves, the parcel layout moved with
        // it, so a partial re-copy would widen what this app accepts.
        assertEquals(1, RadarContract.VERSION)
    }

    @Test
    fun theTailLightValuesWeWriteAreFixed() {
        assertEquals(2, RadarContract.LIGHT_MODE_SOLID)
        assertEquals(4, RadarContract.LIGHT_MODE_OFF)
    }

    @Test
    fun theCapabilityBitsAreFixedAndDistinct() {
        assertEquals(1, RadarContract.HAS_CLOSING_SPEED)
        assertEquals(2, RadarContract.HAS_LATERAL)
        assertEquals(8, RadarContract.HAS_VEHICLE_SIZE)
        val bits = setOf(
            RadarContract.HAS_CLOSING_SPEED,
            RadarContract.HAS_LATERAL,
            RadarContract.HAS_VEHICLE_SIZE,
        )
        assertEquals(3, bits.size)
    }

    @Test
    fun theSizeCodesAreFixed() {
        assertEquals(0, RadarContract.RADAR_SIZE_CAR)
        assertEquals(1, RadarContract.RADAR_SIZE_TRUCK)
        assertEquals(2, RadarContract.RADAR_SIZE_BIKE)
    }

    @Test
    fun theAppWeBindToIsFixed() {
        assertEquals("es.jjrh.bikeradar", RadarContract.PACKAGE)
        assertEquals("es.jjrh.bikeradar.action.RADAR_SERVICE", RadarContract.ACTION)
        assertEquals("es.jjrh.bikeradar.permission.RADAR", RadarContract.PERMISSION)
        assertEquals("es.jjrh.bikeradar.action.REQUEST_RADAR_ACCESS", RadarContract.Consent.ACTION)
    }

    @Test
    fun theConsentAnswerIsReadUnderTheNameItIsSentWith() {
        // Read by name in onConsentResult, so a renamed extra would leave this
        // app reading "not granted" for ever with every test still green.
        assertEquals("es.jjrh.bikeradar.extra.READ", RadarContract.Consent.EXTRA_READ)
    }

    @Test
    fun eachRefusalTheRiderCanActOnIsExplained() {
        // The codes are literals for the same reason as everything else here:
        // reading them from the contract would let a re-copy that swapped two
        // of them explain the wrong refusal, with this test still green.
        assertEquals("Bike Radar is mid-ride: ask again once it ends", consentStatus(1))
        assertEquals("Bike Radar could not save that answer", consentStatus(3))
        assertEquals("Bike Radar could not identify this app", consentStatus(2))
    }

    @Test
    fun thoseCodesAreTheOnesTheContractSends() {
        assertEquals(1, RadarContract.Consent.RESULT_RIDE_IN_PROGRESS)
        assertEquals(2, RadarContract.Consent.RESULT_CALLER_UNKNOWN)
        assertEquals(3, RadarContract.Consent.RESULT_NOT_STORED)
    }

    @Test
    fun aPlainCancelNeedsNoExplanation() {
        assertNull(consentStatus(0))
    }
}
