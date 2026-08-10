/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.nav

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An "L" shape: east, then north (single left turn). */
class TurnFinderTest {

    private val track = Track(
        "L-shape",
        listOf(
            TrackPoint(53.0, 10.0),
            TrackPoint(53.0, 10.0035), // ~234.5 m east
            TrackPoint(53.0008, 10.0035), // ~88.9 m north -> left turn at vertex 1
            TrackPoint(53.0012, 10.0035), // continue straight north
        ),
    )

    @Test
    fun findsLeftTurn() {
        val turns = TurnFinder.find(track)
        assertEquals(1, turns.size)
        val t = turns[0]
        assertEquals(1, t.index)
        assertTrue(t.degrees < 0)
        assertTrue(t.degrees < -45)
    }

    @Test
    fun turnDistanceIsAtVertex() {
        val turns = TurnFinder.find(track)
        assertEquals(track.distanceAt(1), turns[0].distAlongM, 0.01)
    }

    @Test
    fun ignoresTinyWiggles() {
        val straight = Track(
            "small",
            listOf(
                TrackPoint(53.0, 10.0),
                TrackPoint(53.0, 10.00002),
                TrackPoint(53.0, 10.00004),
                TrackPoint(53.0, 10.00006),
            ),
        )
        assertEquals(0, TurnFinder.find(straight).size)
    }
}