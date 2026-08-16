/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RadarSimulator — fake rear-radar traffic for ghost rides
 *
 *   Rides a road behind the rider (range 175 m max) ->
 *   spawns cars/trucks/bikes faster than the rider ->
 *   they close in, pass the rider and disappear (a rear radar only looks back)
 *
 * Pure Kotlin and unit-tested. Data shapes mirror the real Varia-class radar
 * (distance behind, closing km/h, lateral offset, size) so the map layer built
 * here can be fed the live stream from android-bike-radar-overlay later.
 */
package com.crazycapy.randonneur.sim

import com.crazycapy.randonneur.KMH_TO_MS
import com.crazycapy.randonneur.nav.Geo
import kotlin.math.roundToInt
import kotlin.random.Random

/** Kind of fake traffic target, mirroring the rear radar's CAR/TRUCK split plus bikes. */
enum class RadarTargetType { CAR, TRUCK, BIKE }

/**
 * One simulated target, positioned relative to the rider's current fix.
 *
 * @param gapM distance behind the rider along the road (radar range).
 * @param lateralM signed lateral offset in metres (+ = rider's right).
 * @param lateralPos normalized -1..1, same convention as the real radar.
 * @param closingKmh positive while approaching = speed minus rider speed.
 */
data class RadarTarget(
    val id: Int,
    val lat: Double,
    val lon: Double,
    val gapM: Double,
    val lateralM: Double,
    val lateralPos: Float,
    val speedKmh: Double,
    val type: RadarTargetType,
    val closingKmh: Int,
)

/**
 * Spawns and advances fake overtaking traffic behind a rider.
 *
 * Each [tick] takes the rider's latest position/course/speed and the real-world
 * seconds that fix step represents, advances the active targets' gap (a target
 * only closes in when it is faster than the rider), prunes passed/too-slow
 * targets, probabilistically spawns a new one 175 m behind, and projects each
 * target to a lat/lon (gap back along the road, lateral offset to the rider's
 * right).
 */
class RadarSimulator(
    private val random: Random = Random.Default,
) {

    private data class Target(
        val id: Int,
        var gapM: Double,
        val lateralM: Double,
        val speedKmh: Double,
        val type: RadarTargetType,
    )

    private val targets = mutableListOf<Target>()
    private var nextId = 1

    fun reset() {
        targets.clear()
        nextId = 1
    }

    fun tick(
        riderLat: Double,
        riderLon: Double,
        riderBearing: Double,
        riderSpeedKmh: Double,
        dtSec: Double,
    ): List<RadarTarget> {
        if (dtSec > 0.0) {
            val iter = targets.iterator()
            while (iter.hasNext()) {
                val t = iter.next()
                if (t.speedKmh <= riderSpeedKmh) {
                    iter.remove()
                    continue
                }
                t.gapM -= (t.speedKmh - riderSpeedKmh) / KMH_TO_MS * dtSec
                if (t.gapM <= 0.0) iter.remove()
            }
        }

        if (targets.size < MAX_ACTIVE && random.nextDouble() < SPAWN_PROBABILITY) {
            spawn(riderSpeedKmh)
        }

        if (targets.isEmpty()) return emptyList()

        val rightOfTravel = riderBearing + 90.0
        return targets.map { t ->
            val (behindLat, behindLon) = Geo.destinationMeters(riderLat, riderLon, riderBearing + 180.0, t.gapM)
            val (lat, lon) = Geo.destinationMeters(behindLat, behindLon, rightOfTravel, t.lateralM)
            RadarTarget(
                id = t.id,
                lat = lat,
                lon = lon,
                gapM = t.gapM,
                lateralM = t.lateralM,
                lateralPos = (t.lateralM / LATERAL_FULL_M).toFloat().coerceIn(-1f, 1f),
                speedKmh = t.speedKmh,
                type = t.type,
                closingKmh = (t.speedKmh - riderSpeedKmh).roundToInt(),
            )
        }
    }

    private fun spawn(riderSpeedKmh: Double) {
        val type = pickType()
        val speed = pickSpeedKmh(type)
        if (speed <= riderSpeedKmh) return
        targets.add(Target(nextId++, MAX_RANGE_M, pickLateralM(type), speed, type))
    }

    private fun pickType(): RadarTargetType {
        val r = random.nextDouble()
        return when {
            r < 0.6 -> RadarTargetType.CAR
            r < 0.75 -> RadarTargetType.TRUCK
            else -> RadarTargetType.BIKE
        }
    }

    private fun pickSpeedKmh(type: RadarTargetType): Double = when (type) {
        RadarTargetType.CAR -> random.nextDouble() * (CAR_MAX_KMH - CAR_MIN_KMH) + CAR_MIN_KMH
        RadarTargetType.TRUCK -> random.nextDouble() * (TRUCK_MAX_KMH - TRUCK_MIN_KMH) + TRUCK_MIN_KMH
        RadarTargetType.BIKE -> random.nextDouble() * (BIKE_MAX_KMH - BIKE_MIN_KMH) + BIKE_MIN_KMH
    }

    private fun pickLateralM(type: RadarTargetType): Double = when (type) {
        RadarTargetType.CAR -> random.nextDouble() * (CAR_MAX_LATERAL_M - CAR_MIN_LATERAL_M) + CAR_MIN_LATERAL_M
        RadarTargetType.TRUCK -> random.nextDouble() * (TRUCK_MAX_LATERAL_M - TRUCK_MIN_LATERAL_M) + TRUCK_MIN_LATERAL_M
        RadarTargetType.BIKE -> random.nextDouble() * (BIKE_MAX_LATERAL_M - BIKE_MIN_LATERAL_M) + BIKE_MIN_LATERAL_M
    }

    companion object {
        /** Max rear-radar range the sim spawns at (metres). */
        const val MAX_RANGE_M = 175.0

        /** Lateral metre distance that maps to lateralPos ±1.0 (radar lane-scale). */
        const val LATERAL_FULL_M = 4.0

        private const val MAX_ACTIVE = 3
        private const val SPAWN_PROBABILITY = 0.3

        private const val CAR_MIN_KMH = 30.0
        private const val CAR_MAX_KMH = 100.0
        private const val TRUCK_MIN_KMH = 30.0
        private const val TRUCK_MAX_KMH = 90.0
        private const val BIKE_MIN_KMH = 20.0
        private const val BIKE_MAX_KMH = 45.0

        // Negative = rider's left (overtaking), positive = right.
        private const val CAR_MIN_LATERAL_M = -3.5
        private const val CAR_MAX_LATERAL_M = 1.5
        private const val TRUCK_MIN_LATERAL_M = -4.0
        private const val TRUCK_MAX_LATERAL_M = -2.0
        private const val BIKE_MIN_LATERAL_M = -1.0
        private const val BIKE_MAX_LATERAL_M = 2.0
    }
}
