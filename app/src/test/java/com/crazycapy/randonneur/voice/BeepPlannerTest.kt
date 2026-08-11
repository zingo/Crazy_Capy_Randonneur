/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeepPlannerTest {

    @Test
    fun silentWhileFarAway() {
        assertNull(BeepPlanner.signal(45.0, 401.0))
        assertNull(BeepPlanner.signal(-45.0, BeepPlanner.WINDOW_M + 1.0))
    }

    @Test
    fun straightAheadIsSilent() {
        // Near-zero angles are not real turns, so no cue for either side.
        assertNull(BeepPlanner.signal(0.0, 150.0))
        assertNull(BeepPlanner.signal(10.0, 150.0))
        assertNull(BeepPlanner.signal(-10.0, 150.0))
    }

    @Test
    fun rightTurnIsASingleHighBeep() {
        val s = BeepPlanner.signal(45.0, 150.0)!!
        assertEquals(BeepTone.RIGHT_HIGH, s.tone)
        assertEquals(1, s.repeat)
    }

    @Test
    fun leftTurnIsADoubleLowBeep() {
        val s = BeepPlanner.signal(-45.0, 150.0)!!
        assertEquals(BeepTone.LEFT_LOW, s.tone)
        assertEquals(2, s.repeat)
    }

    @Test
    fun beepsGrowShorterAndFasterAsTurnNears() {
        val far = BeepPlanner.signal(-45.0, 350.0)!!
        val near = BeepPlanner.signal(-45.0, 50.0)!!
        // Closer = shorter bursts, shorter gaps, shorter interval.
        assertTrue("burst should shorten, was ${far.burstMs} -> ${near.burstMs}", near.burstMs < far.burstMs)
        assertTrue("gap should shorten, was ${far.gapMs} -> ${near.gapMs}", near.gapMs < far.gapMs)
        assertTrue("interval should shorten, was ${far.intervalMs} -> ${near.intervalMs}", near.intervalMs < far.intervalMs)
    }

    @Test
    fun clampedAtTheTurn() {
        val s = BeepPlanner.signal(45.0, 0.0)!!
        assertEquals(BeepPlanner.TONE_RIGHT, s.tone)
        assertTrue("burst should be short at the turn, was ${s.burstMs}", s.burstMs <= 130)
        assertTrue("interval should stay calm at the turn, was ${s.intervalMs}", s.intervalMs >= 1000)
    }
}
