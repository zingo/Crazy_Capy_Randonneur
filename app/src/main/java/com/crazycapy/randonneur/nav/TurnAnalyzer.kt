/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * TurnAnalyzer — turn detection from track geometry
 *
 *   Track points -> bearing change per vertex -> Turn data class -> sorted list
 *
 * A "turn" is any vertex where the absolute bearing change exceeds the
 * configured threshold. The result is a sorted list of Turn objects used
 * by NavEngine for approach/near/now/passed events.
 */
package com.crazycapy.randonneur.nav

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackPoint

/** A detected maneuver vertex on the track. */
data class Turn(
    val index: Int,
    val distAlongM: Double,
    val bearingIn: Double,
    val bearingOut: Double,
    /** Signed turn angle in degrees, positive = right. */
    val degrees: Double,
    /** Position in the sorted turns list (set by TurnFinder). */
    val position: Int = 0,
) {
    /** Roughly a 180 degree reversal. */
    val isUTurn get() = kotlin.math.abs(degrees) >= 160.0
    val isSharp get() = kotlin.math.abs(degrees) >= 100
}

object TurnFinder {
    /** Minimum signed magnitude to count as a maneuver. */
    const val MIN_TURN_DEGREES = 25.0

    /**
     * Detect turns from track geometry. Returns turns sorted by distance along the track.
     */
    fun find(track: Track): List<Turn> {
        val pts = track.points
        val candidateTurns = ArrayList<Turn>()
        for (i in 1 until pts.size - 1) {
            val prev = pts[i - 1]
            val curr = pts[i]
            val next = pts[i + 1]
            val inBearing = Geo.bearingDegrees(prev.lat, prev.lon, curr.lat, curr.lon)
            val outBearing = Geo.bearingDegrees(curr.lat, curr.lon, next.lat, next.lon)
            val turnDegrees = Geo.turnDegrees(inBearing, outBearing)
            if (kotlin.math.abs(turnDegrees) >= MIN_TURN_DEGREES) {
                candidateTurns.add(Turn(i, track.distanceAt(i), inBearing, outBearing, turnDegrees))
            }
        }
        return candidateTurns.mapIndexed { pos, t -> t.copy(position = pos) }
    }
}