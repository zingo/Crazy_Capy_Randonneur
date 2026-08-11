/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSummaryTest {

    @Test
    fun noTurnShowsRideStats() {
        val (title, text) = TurnSummary.lines(null, null, null, null, 12000.0, 24.0)
        assertEquals("Navigating", title)
        assertEquals("12 km left · 24.0 km/h", text)
    }

    @Test
    fun nextTurnUpFrontThenFollowingTurn() {
        val (title, text) = TurnSummary.lines(
            nextDegrees = 45.0,
            nextM = 150.0,
            nextNextDegrees = -60.0,
            nextNextM = 800.0,
            remainingM = 20000.0,
            speedKmh = 27.0,
        )
        assertEquals("Turn right in 150 m", title)
        assertEquals("20 km left · 27.0 km/h · then turn left in 800 m", text)
    }

    @Test
    fun dropsFollowingTurnWhenTooFar() {
        val (_, text) = TurnSummary.lines(45.0, 200.0, -30.0, 9000.0, 20000.0, 27.0)
        assertTrue(!text.contains("then"))
    }

    @Test
    fun leftTurnWording() {
        val (title, _) = TurnSummary.lines(-45.0, 90.0, null, null, null, 0.0)
        assertEquals("Turn left in 90 m", title)
    }

    @Test
    fun statsHandlesUnknownRemaining() {
        assertEquals("-- left", TurnSummary.stats(null, 0.0))
        assertEquals("2 km left", TurnSummary.stats(2000.0, 0.0))
    }
}
