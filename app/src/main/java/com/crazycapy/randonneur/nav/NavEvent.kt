/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * NavEvent — sealed class for all navigation events emitted by NavEngine
 *
 *   OnTrack(turn, distToTurn, distRemaining)  — regular fix update
 *   TurnApproachAt(turn, seconds, meters)     — timed advance notice
 *   TurnNear(turn, meters, nextTurn?, meters) — close enough to see the turn
 *   TurnNow(turn)                              — "turn now"
 *   TurnPassed(turn)                            — rider just went past it
 *   GoStraight(meters)                         — "go on for X.X km"
 *   OffRoute(meters)                           — strayed off the polyline
 *   OffRouteStill()                             — still off route, repeat reminder
 *   BackOnRoute()                              — returned to the polyline
 *   Arrived()                                  — at the final point
 *
 * These flow from NavEngine -> NavigationService -> UI + Voice layers.
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