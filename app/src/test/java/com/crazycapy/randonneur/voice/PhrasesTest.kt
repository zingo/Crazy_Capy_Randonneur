/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.voice

import com.crazycapy.randonneur.nav.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhrasesTest {

    @Test
    fun formatDistanceShort() {
        assertEquals("50 m", Phrases.formatDistance(10.0))
        assertEquals("100 m", Phrases.formatDistance(120.0))
        assertEquals("500 m", Phrases.formatDistance(480.0))
        assertEquals("900 m", Phrases.formatDistance(870.0))
    }

    @Test
    fun formatDistanceLong() {
        assertEquals("2 km", Phrases.formatDistance(2000.0))
        assertEquals("2.5 km", Phrases.formatDistance(2537.0))
        assertEquals("10 km", Phrases.formatDistance(9995.0))
    }

    @Test
    fun formatDistanceNeverNegative() {
        assertEquals("0 m", Phrases.formatDistance(-10.0))
        assertEquals("0 m", Phrases.formatDistance(0.0))
    }

    @Test
    fun formatShortCompact() {
        assertEquals("10 m", Phrases.formatShort(10.0))
        assertEquals("95 m", Phrases.formatShort(95.0))
        assertEquals("260 m", Phrases.formatShort(260.0))
        assertEquals("902 m", Phrases.formatShort(902.0))
        assertEquals("2 km", Phrases.formatShort(2000.0))
        assertEquals("2.5 km", Phrases.formatShort(2537.0))
        assertEquals("10 km", Phrases.formatShort(9995.0))
        assertEquals("0 m", Phrases.formatShort(-4.0))
    }

    @Test
    fun maneuversWorded() {
        assertTrue(Phrases.maneuverWord(Maneuver.TURN_LEFT).contains("left"))
        assertTrue(Phrases.maneuverWord(Maneuver.TURN_RIGHT).contains("right"))
        assertTrue(Phrases.maneuverWord(Maneuver.U_TURN).contains("U"))
    }

    @Test
    fun turnApproachComposes() {
        assertEquals(
            "turn right in 100 m",
            Phrases.turnApproachAt(Maneuver.TURN_RIGHT, 108.0),
        )
        assertEquals("turn left now", Phrases.turnNow(Maneuver.TURN_LEFT))
        assertEquals("You have arrived at your destination", Phrases.arrived())
        assertEquals("Back on the route", Phrases.backOnRoute())
        assertEquals("Continue straight ahead", Phrases.keepStraight())
    }

    @Test
    fun turnNearComposes() {
        assertEquals(
            "turn right in 100 m, then turn left in 500 m",
            Phrases.turnNear(Maneuver.TURN_RIGHT, 183.0, Maneuver.TURN_LEFT, 487.0),
        )
        // Without a known following turn, just the near-turn phrase.
        assertEquals(
            "turn right in 100 m",
            Phrases.turnNear(Maneuver.TURN_RIGHT, 183.0, null, null),
        )
    }

    @Test
    fun goOnComposes() {
        assertEquals("Go on for 2.5 km", Phrases.goOn(2460.0))
        assertEquals("Go on for 900 m", Phrases.goOn(870.0))
    }

    @Test
    fun offRouteWording() {
        assertTrue(Phrases.offRoute(120.0).contains("off the route"))
        assertTrue(Phrases.offRoute(120.0).contains("100 m"))
        assertTrue(Phrases.offRoute(80.0).contains("50 m"))
        assertEquals("Still off the route", Phrases.offRouteStill())
        assertEquals("Portions of the route reversed", Phrases.routeReversed())
        assertEquals("Riding the original direction", Phrases.routeOriginalDirection())
    }

    @Test
    fun nearTurnDropTheFollowingManeuverFormat() {
        // The combined "then" form uses the maneuver-first distance wording.
        assertEquals(
            "turn right in 100 m, then turn left in 500 m",
            Phrases.turnNear(Maneuver.TURN_RIGHT, 183.0, Maneuver.TURN_LEFT, 487.0),
        )
    }
}