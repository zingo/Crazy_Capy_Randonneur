/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.voice

import com.crazycapy.randonneur.nav.NavEvent
import com.crazycapy.randonneur.nav.maneuverFor

/**
 * One spoken guidance clip, stamped with where the rider was when it fired.
 * Feeds the "speech markers" map layer so spoken output can be audited against
 * the route without listening in real time.
 */
data class SpeechMarker(
    val lat: Double,
    val lon: Double,
    val text: String,
    val seq: Int,
)

/**
 * The exact spoken text for a [NavEvent], or null when that event is silent.
 * Shared by the live ride and the headless [SpeechMarkerGenerator] so they can
 * never drift apart.
 */
fun spokenText(event: NavEvent): String? = when (event) {
    is NavEvent.OnTrack -> null
    is NavEvent.TurnApproachAt -> Phrases.turnApproachAt(maneuverFor(event.turn.degrees), event.distanceM)
    is NavEvent.TurnNear -> Phrases.turnNear(
        maneuverFor(event.turn.degrees),
        event.distanceM,
        event.nextTurnAfter?.let { maneuverFor(it.degrees) },
        event.metersToNextAfter,
    )
    is NavEvent.TurnNow -> Phrases.turnNow(maneuverFor(event.turn.degrees))
    is NavEvent.TurnPassed -> null
    is NavEvent.GoStraight -> Phrases.goOn(event.distanceToTurnM)
    is NavEvent.OffRoute -> Phrases.offRoute(event.distanceM)
    is NavEvent.OffRouteStill -> Phrases.offRouteStill()
    is NavEvent.BackOnRoute -> Phrases.backOnRoute()
    is NavEvent.Arrived -> Phrases.arrived()
}
