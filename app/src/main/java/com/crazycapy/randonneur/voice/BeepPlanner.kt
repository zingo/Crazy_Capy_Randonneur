/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.voice

import kotlin.math.roundToInt

/** Which audible cue a beep burst should use. */
enum class BeepTone { LEFT_LOW, RIGHT_HIGH }

/**
 * One beep event for a turn: how often it repeats, how long each burst lasts and
 * the gap between bursts, plus how long until the next beep event. As the turn
 * gets closer, bursts get shorter and come faster ("growing" urgency).
 */
data class BeepSignal(
    val tone: BeepTone,
    val repeat: Int,
    val burstMs: Int,
    val gapMs: Int,
    val intervalMs: Int,
)

/**
 * Decides when and how to beep for the next turn. Pure Kotlin so it can be
 * unit-tested headlessly. Left turns are a low double-beep, right turns a high
 * single beep; the closer the turn, the shorter and more frequent the beeps.
 */
object BeepPlanner {

    /** Beeps only start inside this window (≈ the first turn announcement). */
    const val WINDOW_M = 400.0

    val TONE_LEFT = BeepTone.LEFT_LOW
    val TONE_RIGHT = BeepTone.RIGHT_HIGH

    /**
     * Beep pacing far away vs right at the turn. Deliberately gentle even at the
     * closest point: the interval never drops below ~1.2s so the cues stay
     * calm instead of feeling like an alarm.
     */
    private const val INTERVAL_FAR_MS = 3200
    private const val INTERVAL_NEAR_MS = 1200

    /** Single-burst length far away vs right at the turn. */
    private const val BURST_FAR_MS = 170
    private const val BURST_NEAR_MS = 120

    /** Gap between a left turn's two bursts, far vs near. */
    private const val GAP_FAR_MS = 160
    private const val GAP_NEAR_MS = 110

    /**
     * @param degrees signed turn angle, positive = right.
     * @param distToTurnM distance to the turn along the route.
     * @return the beep to play now, or null when outside the beep window or the
     *         angle is too small to be a real turn (matches [Maneuver.STRAIGHT]).
     */
    fun signal(degrees: Double, distToTurnM: Double): BeepSignal? {
        val d = distToTurnM.coerceAtLeast(0.0)
        if (d > WINDOW_M) return null
        // Not a real turn (e.g. a ~straight road); don't cue either side.
        if (kotlin.math.abs(degrees) < 25.0) return null
        val u = ((WINDOW_M - d) / WINDOW_M).coerceIn(0.0, 1.0)
        val right = degrees >= 0.0
        return BeepSignal(
            tone = if (right) TONE_RIGHT else TONE_LEFT,
            repeat = if (right) 1 else 2,
            burstMs = lerp(BURST_FAR_MS, BURST_NEAR_MS, u),
            gapMs = lerp(GAP_FAR_MS, GAP_NEAR_MS, u),
            intervalMs = lerp(INTERVAL_FAR_MS, INTERVAL_NEAR_MS, u),
        )
    }

    private fun lerp(a: Int, b: Int, u: Double): Int = (a + (b - a) * u).roundToInt().coerceAtLeast(1)
}
