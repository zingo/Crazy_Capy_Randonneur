/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * Shared rear-radar model.
 *
 * One shape feeds the map layer whether a target comes from the ghost-ride
 * simulator (Phase 0) or a live stream consumed from android-bike-radar-overlay
 * (Phase 1). The fields mirror the wire format of a Varia-class rear radar, so
 * the real stream maps onto this model with no translation.
 */
package com.crazycapy.randonneur.radar

/** Kind of rear-radar target. The real radar only reports cars and trucks;
 *  the ghost-ride simulator adds bikes so the layer's colour coding is proven. */
enum class RadarVehicleSize { CAR, TRUCK, BIKE }

/**
 * One target behind (or, rarely, ahead of) the rider, plus its projected
 * [lat]/[lon] position on the map.
 *
 * @param distanceM distance from the rider along the road in metres. By
 *   default this is the distance BEHIND the rider; when [isAhead] is true it
 *   instead means the distance AHEAD.
 * @param closingKmh positive while approaching = target speed minus rider speed.
 * @param lateralPos normalized -1..1 (radar lane scale).
 * @param rangeXm signed lateral offset in metres (+ = rider's right).
 * @param isAhead true when the target has passed the rider and is now ahead of
 *   the bike (the radar sits on the rear and is mostly "looking back", so such
 *   a reading is less reliable and should be shown to the user with care).
 * @param closingKnown false when [closingKmh] is a default rather than a
 *   reading. A range-only radar reports distance and nothing else.
 * @param lateralKnown false when [lateralPos] and [rangeXm] are defaults. A
 *   zero offset then means "not measured", not "dead centre behind the rider".
 * @param sizeKnown false when [size] is a default rather than a classification.
 *
 * The three known flags default to true because the ghost-ride simulator makes
 * every value up on purpose and its targets are complete by construction. Only
 * the live stream can carry an unmeasured field.
 */
data class RadarVehicle(
    val id: Int,
    val distanceM: Int,
    val closingKmh: Int,
    val size: RadarVehicleSize,
    val lateralPos: Float,
    val rangeXm: Float,
    val isAhead: Boolean,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val closingKnown: Boolean = true,
    val lateralKnown: Boolean = true,
    val sizeKnown: Boolean = true,
)
