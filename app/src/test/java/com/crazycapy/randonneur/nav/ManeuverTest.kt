/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverTest {

    @Test
    fun thresholds() {
        assertEquals(Maneuver.STRAIGHT, maneuverFor(0.0))
        assertEquals(Maneuver.KEEP_RIGHT, maneuverFor(30.0))
        assertEquals(Maneuver.KEEP_LEFT, maneuverFor(-30.0))
        assertEquals(Maneuver.TURN_RIGHT, maneuverFor(45.0))
        assertEquals(Maneuver.TURN_LEFT, maneuverFor(-90.0))
        assertEquals(Maneuver.SHARP_RIGHT, maneuverFor(100.0))
        assertEquals(Maneuver.SHARP_LEFT, maneuverFor(-135.0))
        assertEquals(Maneuver.U_TURN, maneuverFor(170.0))
        assertEquals(Maneuver.U_TURN, maneuverFor(-179.0))
    }

    @Test
    fun turnClassification() {
        val t = Turn(1, 100.0, 90.0, 0.0, -90.0)
        assertEquals(false, t.isSharp)
        assertEquals(false, t.isUTurn)
        val u = Turn(1, 100.0, 0.0, 180.0, 180.0)
        assertEquals(true, u.isUTurn)
    }
}