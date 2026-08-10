/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.voice

import com.crazycapy.randonneur.nav.Maneuver
import kotlin.math.roundToInt

/** Builds spoken instructions. Pure Kotlin so it can be unit-tested headlessly. */
object Phrases {

    private const val METERS: String = "meters"
    private const val KILOMETERS: String = "kilometers"

    fun maneuverWord(m: Maneuver): String = when (m) {
        Maneuver.STRAIGHT -> "continue straight"
        Maneuver.KEEP_LEFT -> "keep left"
        Maneuver.KEEP_RIGHT -> "keep right"
        Maneuver.TURN_LEFT -> "turn left"
        Maneuver.TURN_RIGHT -> "turn right"
        Maneuver.SHARP_LEFT -> "make a sharp left"
        Maneuver.SHARP_RIGHT -> "make a sharp right"
        Maneuver.U_TURN -> "make a U-turn"
    }

    /** Human friendly distance: e.g. 2537 -> "2.5 kilometers". */
    fun formatDistance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return if (m < 1000) {
            val rounded = when {
                m < 90 -> 50
                m < 200 -> 100
                m < 500 -> ((m + 50) / 100).roundToInt() * 100
                else -> ((m + 50) / 100).roundToInt() * 100
            }
            "$rounded $METERS"
        } else {
            val km = m / 1000
            val tenths = (km * 10).roundToInt()
            if (tenths % 10 == 0) "${tenths / 10} $KILOMETERS"
            else (tenths / 10.0).toString() + " " + KILOMETERS
        }
    }

    fun turnApproachAt(m: Maneuver, meters: Double): String =
        "${maneuverWord(m)} in ${formatDistance(meters)}"

    /**
     * Near-turn notice: the maneuver plus, when known, the distance/gap to the
     * following maneuver so the rider knows what comes next.
     */
    fun turnNear(m: Maneuver, meters: Double, nextAfter: Maneuver?, metersAfter: Double?): String {
        val turn = turnApproachAt(m, meters)
        if (nextAfter != null && metersAfter != null) {
            return "$turn, then ${turnApproachAt(nextAfter, metersAfter)}"
        }
        return turn
    }

    fun turnNow(m: Maneuver): String = "${maneuverWord(m)} now"

    /** "Go on for 2.4 kilometers" style heads-up when the next turn is far ahead. */
    fun goOn(distanceToTurnM: Double): String = "Go on for ${formatDistance(distanceToTurnM)}"

    fun arrived(): String = "You have arrived at your destination"

    fun backOnRoute(): String = "Back on the route"

    fun keepStraight(): String = "Continue straight ahead"

    /** First warning that the rider has left the route corridor. */
    fun offRoute(distanceM: Double): String =
        "You are off the route. ${formatDistance(distanceM.coerceAtLeast(0.0))} from the route."

    /** Periodic reminder spoken while still off the route. */
    fun offRouteStill(): String = "Still off the route"

    fun routeReversed(): String = "Portions of the route reversed"

    fun routeOriginalDirection(): String = "Riding the original direction"
}