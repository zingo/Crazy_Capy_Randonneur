/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.nav

/** Events produced by the [NavEngine] for the UI/voice layer. */
sealed class NavEvent {
    /** Snapped position on the track. */
    data class OnTrack(
        val lat: Double,
        val lon: Double,
        val distanceAlongM: Double,
        val distanceByRouteM: Double,
        val nextTurn: Turn?,
        val distanceToNextTurnM: Double?,
    ) : NavEvent()

    data class TurnApproachAt(val turn: Turn, val leadSeconds: Int, val distanceM: Double) : NavEvent()
    data class TurnNear(val turn: Turn, val distanceM: Double, val nextTurnAfter: Turn?, val metersToNextAfter: Double?) : NavEvent()
    data class TurnNow(val turn: Turn) : NavEvent()
    data class TurnPassed(val turn: Turn) : NavEvent()
    data class GoStraight(val distanceToTurnM: Double) : NavEvent()

    /** Rider first left the route corridor; [distanceM] is how far off the route. */
    data class OffRoute(
        val lat: Double,
        val lon: Double,
        val snapped: Pair<Double, Double>,
        val distanceM: Double,
    ) : NavEvent()

    /** Periodic reminder (every N fixes) that the rider is still off the route. */
    data class OffRouteStill(
        val lat: Double,
        val lon: Double,
        val snapped: Pair<Double, Double>,
        val distanceM: Double,
    ) : NavEvent()

    data class BackOnRoute(val lat: Double, val lon: Double) : NavEvent()
    data class Arrived(val lat: Double, val lon: Double) : NavEvent()
}