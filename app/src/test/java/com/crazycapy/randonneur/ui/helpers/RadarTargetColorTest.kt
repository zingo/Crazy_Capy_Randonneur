/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.helpers

import com.crazycapy.randonneur.radar.RadarVehicle
import com.crazycapy.randonneur.radar.RadarVehicleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RadarTargetColorTest {

    private val car = RadarVehicle(
        id = 1,
        distanceM = 60,
        closingKmh = 20,
        size = RadarVehicleSize.CAR,
        lateralPos = 0f,
        rangeXm = 0f,
        isAhead = false,
    )

    @Test
    fun measuredTargetsAreColouredByClass() {
        assertEquals("#FF9800", targetColor(car))
        assertEquals("#E53935", targetColor(car.copy(size = RadarVehicleSize.TRUCK)))
        assertEquals("#42A5F5", targetColor(car.copy(size = RadarVehicleSize.BIKE)))
    }

    @Test
    fun anUnmeasuredLanePositionIsNotColouredAsAClass() {
        val grey = targetColor(car.copy(lateralKnown = false))
        assertNotEquals("#FF9800", grey)
        assertEquals("#9E9E9E", grey)
    }

    @Test
    fun anUnmeasuredClassIsNotColouredAsAClass() {
        val grey = targetColor(car.copy(size = RadarVehicleSize.TRUCK, sizeKnown = false))
        assertNotEquals("#E53935", grey)
        assertEquals("#9E9E9E", grey)
    }

    @Test
    fun anUnmeasuredClosingSpeedAloneChangesNothingOnTheMap() {
        // The three capability bits are independent, and the map draws neither
        // closing speed nor anything derived from it.
        assertEquals("#FF9800", targetColor(car.copy(closingKnown = false)))
    }

    @Test
    fun greyIsDistinctFromEveryClassColour() {
        val colours = setOf(
            targetColor(car),
            targetColor(car.copy(size = RadarVehicleSize.TRUCK)),
            targetColor(car.copy(size = RadarVehicleSize.BIKE)),
            targetColor(car.copy(sizeKnown = false)),
        )
        assertEquals(4, colours.size)
    }
}
