/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.voice

import com.crazycapy.randonneur.nav.maneuverFor
import kotlin.math.roundToInt

/**
 * Builds the compact, lock-screen friendly turn guidance text for the ongoing
 * navigation notification: the next turn up front, the following turn as a
 * "then …" when it is reasonably close, and ride stats on the second line.
 * Pure Kotlin so it can be unit-tested headlessly.
 */
object TurnSummary {

    /** Only include the next-next turn when it is closer than this, to stay condensed. */
    const val NEXT_NEXT_WINDOW_M = 5000.0

    /**
     * @return (title, text) for the notification. The title is the next maneuver,
     * the text the ride stats plus the following turn heads-up when known.
     */
    fun lines(
        nextDegrees: Double?,
        nextM: Double?,
        nextNextDegrees: Double?,
        nextNextM: Double?,
        remainingM: Double?,
        speedKmh: Double,
    ): Pair<String, String> {
        val stats = stats(remainingM, speedKmh)
        val deg = nextDegrees
        val m = nextM
        if (deg == null || m == null) return "Navigating" to stats

        val maneuver = Phrases.maneuverWord(maneuverFor(deg))
        val title = maneuver.replaceFirstChar { it.uppercase() } + " in " + Phrases.formatShort(m)
        val after = nextNextDegrees
        val afterM = nextNextM
        val then = if (after != null && afterM != null && afterM <= NEXT_NEXT_WINDOW_M) {
            val w = Phrases.maneuverWord(maneuverFor(after))
            " · then $w in " + Phrases.formatShort(afterM)
        } else {
            ""
        }
        return title to (stats + then)
    }

    fun stats(remainingM: Double?, speedKmh: Double): String {
        val left = remainingM?.let { Phrases.formatDistance(it) } ?: "--"
        val speed = if (speedKmh > 0) " · " + ((speedKmh * 10).roundToInt()) / 10.0 + " km/h" else ""
        return "$left left$speed"
    }

    /** Convenience so callers without a Maneuver import can map raw degrees. */
    fun maneuverWord(degrees: Double): String = Phrases.maneuverWord(maneuverFor(degrees))
}
