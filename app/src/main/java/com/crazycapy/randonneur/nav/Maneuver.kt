/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * Maneuver — turn-degree-to-maneuver label mapping
 *
 *   turn degrees -> STRAIGHT | KEEP_LEFT | KEEP_RIGHT | TURN_LEFT |
 *                   TURN_RIGHT | SHARP_LEFT | SHARP_RIGHT | U_TURN
 *
 * Used by Phrases and TurnSummary to produce spoken + displayed instructions.
 */
package com.crazycapy.randonneur.nav

// Thresholds mirror TurnAnalyzer.MIN_TURN_DEGREES
private const val MIN_TURN_DEG = 25.0
private const val KEEP_TURN_DEG = 45.0
private const val SHARP_TURN_DEG = 100.0
private const val U_TURN_DEG = 160.0

/** Category of a maneuver, used for phrasing. */
enum class Maneuver {
    STRAIGHT, KEEP_LEFT, KEEP_RIGHT, TURN_LEFT, TURN_RIGHT, SHARP_LEFT, SHARP_RIGHT, U_TURN;
}

fun maneuverFor(turnDegrees: Double): Maneuver {
    val absDegrees = kotlin.math.abs(turnDegrees)
    return if (absDegrees < MIN_TURN_DEG) Maneuver.STRAIGHT
    else if (absDegrees >= U_TURN_DEG) Maneuver.U_TURN
    else if (turnDegrees > 0) {
        if (absDegrees >= SHARP_TURN_DEG) Maneuver.SHARP_RIGHT else if (absDegrees >= KEEP_TURN_DEG) Maneuver.TURN_RIGHT else Maneuver.KEEP_RIGHT
    } else {
        if (absDegrees >= SHARP_TURN_DEG) Maneuver.SHARP_LEFT else if (absDegrees >= KEEP_TURN_DEG) Maneuver.TURN_LEFT else Maneuver.KEEP_LEFT
    }
}