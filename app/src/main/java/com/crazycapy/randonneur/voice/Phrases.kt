/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * Phrases — human-readable distance/speed/maneuver for TTS and display
 *
 *   formatDistance(m)    -> "2.3 km"  or  "450 m"
 *   formatShort(m)       -> "2.3k"  or "450"
 *   formatManeuver(m)    -> "Turn left"  /  "Keep right"
 *   formatNextTurn(...)  -> "In 300 m, turn right onto Main St"
 *
 * Pure Kotlin, no Android deps. Every method unit-testable.
 */
package com.crazycapy.randonneur.voice

import com.crazycapy.randonneur.nav.Maneuver
import kotlin.math.roundToInt

/** Builds spoken instructions. Pure Kotlin so it can be unit-tested headlessly. */
object Phrases {

    private const val METERS: String = "m"
    private const val KILOMETERS: String = "km"

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

    /** Human friendly distance: e.g. 2537 -> "2.5 km". */
    fun formatDistance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return if (m < 1000) {
            val rounded = when {
                m <= 0.0 -> 0
                else -> ((m / 10).roundToInt().coerceAtLeast(1)) * 10
            }
            "$rounded $METERS"
        } else {
            formatKilometers(m / 1000) + " " + KILOMETERS
        }
    }

    /** Compact distance for tight UI/notification space: e.g. 2537 -> "2.5 km". */
    fun formatShort(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return if (m < 1000) "${m.roundToInt()} $METERS"
        else formatKilometers(m / 1000) + " " + KILOMETERS
    }

    /** Rounds kilometers to one decimal, dropping the fraction when whole. */
    private fun formatKilometers(km: Double): String {
        val tenths = (km * 10).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}" else (tenths / 10.0).toString()
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