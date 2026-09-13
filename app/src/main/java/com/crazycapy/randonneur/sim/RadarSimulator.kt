/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RadarSimulator — fake rear-radar traffic for ghost rides
 *
 *   Rides the route's path behind the rider (range 175 m max) ->
 *   spawns cars/trucks/bikes faster than the rider ->
 *   they close in, pass the rider and disappear (a rear radar only looks back).
 *   Each target follows the same path geometry as the rider but is offset
 *   sideways by up to ±10 m, as if in an adjacent lane / parallel track.
 *
 * Pure Kotlin and unit-tested. Produces the same [RadarVehicle] model a live
 * rear-radar stream is mapped onto, so the map layer built here can be fed the
 * real stream from android-bike-radar-overlay later.
 */
package com.crazycapy.randonneur.sim

import com.crazycapy.randonneur.KMH_TO_MS
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.nav.Geo
import com.crazycapy.randonneur.radar.RadarVehicle
import com.crazycapy.randonneur.radar.RadarVehicleSize
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Spawns and advances fake overtaking traffic behind a rider.
 *
 * Each [tick] takes the rider's distance along the [track], the rider's speed,
 * and the real-world seconds that fix step represents, advances the active
 * targets' gap (a target only closes in when it is faster than the rider),
 * prunes passed/too-slow targets, probabilistically spawns a new one 175 m
 * behind, and positions each target along the route (gap back along the path,
 * lateral offset perpendicular to the path of up to ±10 m).
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
        track: Track,
        riderDistanceM: Double,
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
            val (lat, lon) = position(track, riderDistanceM, t.gapM, t.lateralM)
            RadarVehicle(
                id = t.id,
                distanceM = t.gapM.toInt(),
                closingKmh = (t.speedKmh - riderSpeedKmh).roundToInt(),
                size = t.size,
                lateralPos = (t.lateralM / LATERAL_FULL_M).toFloat().coerceIn(-1f, 1f),
                rangeXm = t.lateralM.toFloat(),
                isAhead = false,
                lat = lat,
                lon = lon,
            )
        }
    }

    /**
     * Place a target [gapM] metres behind the rider along the path, offset
     * [lateralM] metres to the rider's right (negative = left).
     */
    private fun position(track: Track, riderDistanceM: Double, gapM: Double, lateralM: Double): Pair<Double, Double> {
        val pathDist = (riderDistanceM - gapM).coerceAtLeast(0.0)
        val base = track.pointAtDistance(pathDist)
        val bearing = trackBearingDegrees(track, pathDist)
        return Geo.destinationMeters(base.lat, base.lon, bearing + 90.0, lateralM)
    }

    /** Tangent bearing of the [track] at [d] metres along it. */
    private fun trackBearingDegrees(track: Track, d: Double): Double {
        val a = track.pointAtDistance((d - 1.0).coerceAtLeast(0.0))
        val b = track.pointAtDistance((d + 1.0).coerceAtMost(track.lengthMeters))
        return Geo.bearingDegrees(a.lat, a.lon, b.lat, b.lon)
    }

    private fun spawn(riderSpeedKmh: Double) {
        val size = pickSize()
        val speed = pickSpeedKmh(size)
        if (speed <= riderSpeedKmh) return
        targets.add(Target(nextId++, MAX_RANGE_M, pickLateralM(), speed, size))
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

    private fun pickLateralM(): Double =
        random.nextDouble() * (LATERAL_MAX_M - LATERAL_MIN_M) + LATERAL_MIN_M

    companion object {
        /** Max rear-radar range the sim spawns at (metres). */
        const val MAX_RANGE_M = 175.0

        /** Lateral metre distance that maps to lateralPos ±1.0 (radar lane-scale). */
        const val LATERAL_FULL_M = 10.0

        private const val MAX_ACTIVE = 3
        private const val SPAWN_PROBABILITY = 0.3

        private const val CAR_MIN_KMH = 30.0
        private const val CAR_MAX_KMH = 100.0
        private const val TRUCK_MIN_KMH = 30.0
        private const val TRUCK_MAX_KMH = 90.0
        private const val BIKE_MIN_KMH = 20.0
        private const val BIKE_MAX_KMH = 45.0

        // Lateral offset to either side of the path (metres), negative = left.
        private const val LATERAL_MIN_M = -10.0
        private const val LATERAL_MAX_M = 10.0
    }
}
