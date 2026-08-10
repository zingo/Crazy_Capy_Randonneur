/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.nav

/** Category of a maneuver, used for phrasing. */
enum class Maneuver {
    STRAIGHT, KEEP_LEFT, KEEP_RIGHT, TURN_LEFT, TURN_RIGHT, SHARP_LEFT, SHARP_RIGHT, U_TURN;
}

fun maneuverFor(degrees: Double): Maneuver {
    val a = kotlin.math.abs(degrees)
    return if (a < 25.0) Maneuver.STRAIGHT
    else if (a >= 160) Maneuver.U_TURN
    else if (degrees > 0) {
        if (a >= 100) Maneuver.SHARP_RIGHT else if (a >= 45) Maneuver.TURN_RIGHT else Maneuver.KEEP_RIGHT
    } else {
        if (a >= 100) Maneuver.SHARP_LEFT else if (a >= 45) Maneuver.TURN_LEFT else Maneuver.KEEP_LEFT
    }
}