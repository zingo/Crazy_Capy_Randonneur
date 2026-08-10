/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
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
        val pre = ArrayList<Turn>()
        for (i in 1 until pts.size - 1) {
            val a = pts[i - 1]
            val b = pts[i]
            val c = pts[i + 1]
            val inBearing = Geo.bearingDegrees(a.lat, a.lon, b.lat, b.lon)
            val outBearing = Geo.bearingDegrees(b.lat, b.lon, c.lat, c.lon)
            val d = Geo.turnDegrees(inBearing, outBearing)
            if (kotlin.math.abs(d) >= MIN_TURN_DEGREES) {
                pre.add(Turn(i, track.distanceAt(i), inBearing, outBearing, d))
            }
        }
        return pre.mapIndexed { pos, t -> t.copy(position = pos) }
    }
}