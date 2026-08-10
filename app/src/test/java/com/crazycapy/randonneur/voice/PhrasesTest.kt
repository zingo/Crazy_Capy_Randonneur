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
        assertEquals("50 meters", Phrases.formatDistance(10.0))
        assertEquals("100 meters", Phrases.formatDistance(120.0))
        assertEquals("500 meters", Phrases.formatDistance(480.0))
        assertEquals("900 meters", Phrases.formatDistance(870.0))
    }

    @Test
    fun formatDistanceLong() {
        assertEquals("2 kilometers", Phrases.formatDistance(2000.0))
        assertEquals("2.5 kilometers", Phrases.formatDistance(2537.0))
        assertEquals("10 kilometers", Phrases.formatDistance(9995.0))
    }

    @Test
    fun formatDistanceNeverNegative() {
        assertEquals("50 meters", Phrases.formatDistance(-10.0))
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
            "turn right in 100 meters",
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
            "turn right in 100 meters, then turn left in 500 meters",
            Phrases.turnNear(Maneuver.TURN_RIGHT, 183.0, Maneuver.TURN_LEFT, 487.0),
        )
        // Without a known following turn, just the near-turn phrase.
        assertEquals(
            "turn right in 100 meters",
            Phrases.turnNear(Maneuver.TURN_RIGHT, 183.0, null, null),
        )
    }

    @Test
    fun goOnComposes() {
        assertEquals("Go on for 2.5 kilometers", Phrases.goOn(2460.0))
        assertEquals("Go on for 900 meters", Phrases.goOn(870.0))
    }

    @Test
    fun offRouteWording() {
        assertTrue(Phrases.offRoute(120.0).contains("off the route"))
        assertTrue(Phrases.offRoute(120.0).contains("100 meters"))
        assertTrue(Phrases.offRoute(80.0).contains("50 meters"))
        assertEquals("Still off the route", Phrases.offRouteStill())
        assertEquals("Portions of the route reversed", Phrases.routeReversed())
        assertEquals("Riding the original direction", Phrases.routeOriginalDirection())
    }

    @Test
    fun nearTurnDropTheFollowingManeuverFormat() {
        // The combined "then" form uses the maneuver-first distance wording.
        assertEquals(
            "turn right in 100 meters, then turn left in 500 meters",
            Phrases.turnNear(Maneuver.TURN_RIGHT, 183.0, Maneuver.TURN_LEFT, 487.0),
        )
    }
}