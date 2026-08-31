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
 * Pure Kotlin and unit-tested. Produces the same [RadarVehicle] model a live
 * rear-radar stream is mapped onto, so the map layer built here can be fed the
 * real stream from android-bike-radar-overlay later.
 */
package com.crazycapy.randonneur.sim

import com.crazycapy.randonneur.KMH_TO_MS
import com.crazycapy.randonneur.radar.RadarProjection
import com.crazycapy.randonneur.radar.RadarVehicle
import com.crazycapy.randonneur.radar.RadarVehicleSize
import kotlin.math.roundToInt
import kotlin.random.Random

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
        val size: RadarVehicleSize,
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
    ): List<RadarVehicle> {
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

        return targets.map { t ->
            RadarProjection.project(
                RadarVehicle(
                    id = t.id,
                    distanceM = t.gapM.toInt(),
                    closingKmh = (t.speedKmh - riderSpeedKmh).roundToInt(),
                    size = t.size,
                    lateralPos = (t.lateralM / LATERAL_FULL_M).toFloat().coerceIn(-1f, 1f),
                    rangeXm = t.lateralM.toFloat(),
                    isAhead = false,
                ),
                riderLat,
                riderLon,
                riderBearing,
            )
        }
    }

    private fun spawn(riderSpeedKmh: Double) {
        val size = pickSize()
        val speed = pickSpeedKmh(size)
        if (speed <= riderSpeedKmh) return
        targets.add(Target(nextId++, MAX_RANGE_M, pickLateralM(size), speed, size))
    }

    private fun pickSize(): RadarVehicleSize {
        val r = random.nextDouble()
        return when {
            r < 0.6 -> RadarVehicleSize.CAR
            r < 0.75 -> RadarVehicleSize.TRUCK
            else -> RadarVehicleSize.BIKE
        }
    }

    private fun pickSpeedKmh(size: RadarVehicleSize): Double = when (size) {
        RadarVehicleSize.CAR -> random.nextDouble() * (CAR_MAX_KMH - CAR_MIN_KMH) + CAR_MIN_KMH
        RadarVehicleSize.TRUCK -> random.nextDouble() * (TRUCK_MAX_KMH - TRUCK_MIN_KMH) + TRUCK_MIN_KMH
        RadarVehicleSize.BIKE -> random.nextDouble() * (BIKE_MAX_KMH - BIKE_MIN_KMH) + BIKE_MIN_KMH
    }

    private fun pickLateralM(size: RadarVehicleSize): Double = when (size) {
        RadarVehicleSize.CAR -> random.nextDouble() * (CAR_MAX_LATERAL_M - CAR_MIN_LATERAL_M) + CAR_MIN_LATERAL_M
        RadarVehicleSize.TRUCK -> random.nextDouble() * (TRUCK_MAX_LATERAL_M - TRUCK_MIN_LATERAL_M) + TRUCK_MIN_LATERAL_M
        RadarVehicleSize.BIKE -> random.nextDouble() * (BIKE_MAX_LATERAL_M - BIKE_MIN_LATERAL_M) + BIKE_MIN_LATERAL_M
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
