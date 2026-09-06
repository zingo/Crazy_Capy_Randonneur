/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.radar

import es.jjrh.bikeradar.ipc.RadarStateParcel
import es.jjrh.bikeradar.ipc.RadarVehicleParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capability bits and size codes are literals here rather than the
 * contract's constants: they are the wire, and a test that reads them from the
 * same file it checks would follow a change instead of catching one.
 */
class RadarWireTest {

    private val closingSpeed = 1
    private val lateral = 2
    private val vehicleClass = 8
    private val everything = closingSpeed or lateral or vehicleClass

    private fun target(
        id: Int = 7,
        distanceM: Int = 40,
        closingKmh: Int = 25,
        size: Int = 0,
        lateralPos: Float = 0.5f,
        rangeXm: Float = 1.5f,
        isAhead: Boolean = false,
        lateralKnown: Boolean = true,
    ) = RadarVehicleParcel(
        id = id,
        distanceM = distanceM,
        closingKmh = closingKmh,
        size = size,
        lateralPos = lateralPos,
        rangeXm = rangeXm,
        isAhead = isAhead,
        lateralKnown = lateralKnown,
    )

    private fun snapshot(capabilities: Int, vararg vehicles: RadarVehicleParcel) = RadarStateParcel(
        timestamp = 1L,
        vehicles = vehicles.toList(),
        bikeSpeedMs = 5f,
        riderSpeedKnown = true,
        streamLive = true,
        isClear = false,
        capabilities = capabilities,
    )

    @Test
    fun rangeOnlyStreamMarksEveryDerivedFieldUnmeasured() {
        val v = snapshot(0, target()).toDomain().single()
        assertFalse(v.closingKnown)
        assertFalse(v.lateralKnown)
        assertFalse(v.sizeKnown)
    }

    @Test
    fun capableStreamMarksThemMeasured() {
        val v = snapshot(everything, target(size = 1)).toDomain().single()
        assertTrue(v.closingKnown)
        assertTrue(v.lateralKnown)
        assertTrue(v.sizeKnown)
        assertEquals(RadarVehicleSize.TRUCK, v.size)
    }

    @Test
    fun aFrameWithNoLateralReadIsUnknownOnACapableStream() {
        val v = snapshot(everything, target(lateralKnown = false)).toDomain().single()
        assertFalse(v.lateralKnown)
        assertTrue(v.closingKnown)
    }

    @Test
    fun classIsADefaultWhenTheStreamCannotMeasureIt() {
        val v = snapshot(closingSpeed or lateral, target(size = 1)).toDomain().single()
        assertFalse(v.sizeKnown)
        assertEquals(RadarVehicleSize.CAR, v.size)
    }

    @Test
    fun aSizeCodeThisBuildDoesNotKnowIsNotAMeasurement() {
        val v = snapshot(everything, target(size = 3)).toDomain().single()
        assertFalse(v.sizeKnown)
        assertEquals(RadarVehicleSize.CAR, v.size)
    }

    @Test
    fun theReservedBikeCodeIsNotPresentedAsAMeasurement() {
        // The contract reserves 2 and never sends it, so a bike arriving on the
        // live stream must not colour the map as a bike the radar saw.
        val v = snapshot(everything, target(size = 2)).toDomain().single()
        assertFalse(v.sizeKnown)
        assertEquals(RadarVehicleSize.CAR, v.size)
    }

    @Test
    fun eachBitIsReadOnItsOwn() {
        val lateralOnly = snapshot(lateral, target()).toDomain().single()
        assertTrue(lateralOnly.lateralKnown)
        assertFalse(lateralOnly.closingKnown)
        assertFalse(lateralOnly.sizeKnown)

        val closingOnly = snapshot(closingSpeed, target()).toDomain().single()
        assertTrue(closingOnly.closingKnown)
        assertFalse(closingOnly.lateralKnown)
        assertFalse(closingOnly.sizeKnown)

        val classOnly = snapshot(vehicleClass, target()).toDomain().single()
        assertTrue(classOnly.sizeKnown)
        assertFalse(classOnly.closingKnown)
        assertFalse(classOnly.lateralKnown)
    }

    @Test
    fun carriesTheMeasurementsThrough() {
        val v = snapshot(everything, target()).toDomain().single()
        assertEquals(7, v.id)
        assertEquals(40, v.distanceM)
        assertEquals(25, v.closingKmh)
        assertEquals(0.5f, v.lateralPos, 0f)
        assertEquals(1.5f, v.rangeXm, 0f)
        assertFalse(v.isAhead)
    }

    @Test
    fun everyTargetKeepsItsOwnValues() {
        val targets = snapshot(
            everything,
            target(),
            target(id = 9, distanceM = 12, closingKmh = 3, size = 1, lateralPos = -0.25f, rangeXm = -2.5f, isAhead = true),
        ).toDomain()

        assertEquals(2, targets.size)
        assertEquals(7, targets[0].id)
        assertEquals(40, targets[0].distanceM)
        assertEquals(RadarVehicleSize.CAR, targets[0].size)
        assertFalse(targets[0].isAhead)

        assertEquals(9, targets[1].id)
        assertEquals(12, targets[1].distanceM)
        assertEquals(3, targets[1].closingKmh)
        assertEquals(-0.25f, targets[1].lateralPos, 0f)
        assertEquals(-2.5f, targets[1].rangeXm, 0f)
        assertEquals(RadarVehicleSize.TRUCK, targets[1].size)
        assertTrue(targets[1].isAhead)
    }

    @Test
    fun anEmptySnapshotMapsToNoTargets() {
        assertTrue(snapshot(everything).toDomain().isEmpty())
    }
}
