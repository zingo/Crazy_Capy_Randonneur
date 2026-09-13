/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.sim

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackPoint
import com.crazycapy.randonneur.nav.Geo
import com.crazycapy.randonneur.radar.RadarVehicle
import com.crazycapy.randonneur.radar.RadarVehicleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class RadarSimulatorTest {

    private val track: Track = northTrack()

    private fun northTrack(): Track {
        val pts = (0..200).map { TrackPoint(50.0 + it * 0.001, 10.0) }
        return Track("north", pts)
    }

    private fun cornerTrack(): Track {
        val pts = mutableListOf<TrackPoint>()
        for (i in 0..300) {
            val (la, lo) = Geo.destinationMeters(50.0, 10.0, 0.0, i.toDouble())
            pts.add(TrackPoint(la, lo))
        }
        val (cLat, cLon) = Geo.destinationMeters(50.0, 10.0, 0.0, 300.0)
        for (i in 1..300) {
            val (la, lo) = Geo.destinationMeters(cLat, cLon, 90.0, i.toDouble())
            pts.add(TrackPoint(la, lo))
        }
        return Track("corner", pts)
    }

    private fun runTicks(
        sim: RadarSimulator,
        count: Int,
        riderSpeedKmh: Double = 28.0,
        dtSec: Double = 1.0,
    ): Pair<List<RadarVehicle>, Double> {
        var result: List<RadarVehicle> = emptyList()
        var riderDistanceM = 0.0
        repeat(count) {
            result = sim.tick(track, riderDistanceM, riderSpeedKmh, dtSec)
            riderDistanceM += riderSpeedKmh / 3.6 * dtSec
        }
        return result to riderDistanceM
    }

    @Test
    fun spawnsTrafficBehindAtMaxRange() {
        val sim = RadarSimulator(Random(42))
        var out = emptyList<RadarVehicle>()
        repeat(200) {
            out = runTicks(sim, 1, riderSpeedKmh = 20.0).first
            if (out.isNotEmpty()) return@repeat
        }
        assertTrue("expected a spawned target", out.isNotEmpty())
        out.forEach { t ->
            assertTrue("range should start near max range, was ${t.distanceM}", t.distanceM <= RadarSimulator.MAX_RANGE_M.toInt() + 1)
            assertTrue("target must be faster than the rider", t.closingKmh > 0)
        }
    }

    @Test
    fun neverSpawnsTrafficSlowerThanOrEqualTheRider() {
        val sim = RadarSimulator(Random(7))
        var sawAny = false
        repeat(100) {
            val out = runTicks(sim, 1, riderSpeedKmh = 55.0).first
            if (out.isNotEmpty()) {
                sawAny = true
                out.forEach { assertTrue("slow target leaked", it.closingKmh > 0) }
            }
        }
        assertTrue(sawAny)
    }

    @Test
    fun targetsCloseInAndDisappearAfterPassing() {
        val sim = RadarSimulator(Random(1))
        var distanceBefore = -1
        var passed = false
        repeat(600) {
            val out = runTicks(sim, 1, riderSpeedKmh = 10.0, dtSec = 1.0).first
            if (out.isNotEmpty()) distanceBefore = out.first().distanceM
            if (distanceBefore > 0 && out.isEmpty()) {
                passed = true
                return@repeat
            }
        }
        assertTrue("target should pass and vanish", passed)
    }

    @Test
    fun targetsArePositionedBehindAndLateral() {
        val sim = RadarSimulator(Random(99))
        var target: RadarVehicle? = null
        var riderDistanceM = 0.0
        repeat(100) {
            val pair = runTicks(sim, 1, riderSpeedKmh = 15.0)
            riderDistanceM = pair.second
            target = pair.first.firstOrNull()
            if (target != null) return@repeat
        }
        val t = target ?: throw AssertionError("no target spawned")
        // Rider rides north; the target must be behind (lower latitude).
        val riderLat = track.pointAtDistance(riderDistanceM).lat
        assertTrue("target should be behind the rider", t.lat < riderLat)
        // A target on the rider's right (positive lateral) must be east of the road line.
        if (t.rangeXm > 0) {
            assertTrue(t.lon >= 10.0 - 1e-6)
        } else {
            assertTrue(t.lon <= 10.0 + 1e-6)
        }
    }

    @Test
    fun targetsFollowPathAroundCorner() {
        // A target spawned behind a rider past the corner must lie on the
        // north-going segment, not straight back along the rider's (east) heading.
        val ct = cornerTrack()
        val sim = RadarSimulator(Random(1234))
        var target: RadarVehicle? = null
        for (i in 0 until 60) {
            val out = sim.tick(ct, 400.0, 0.0, 0.0)
            target = out.firstOrNull()
            if (target != null) break
        }
        val t = target ?: throw AssertionError("no target spawned")
        assertTrue("target should stay on the path within the ±10 m lane, was lon ${t.lon}", abs(t.lon - 10.0) < 0.001)
        assertTrue("target should still be on the north segment (south of the corner)", t.lat < 50.003)
    }

    @Test
    fun simulatedTrafficCountsAsMeasured() {
        val sim = RadarSimulator(Random(23))
        var out = emptyList<RadarVehicle>()
        for (i in 0 until 300) {
            out = runTicks(sim, 1, riderSpeedKmh = 14.0).first
            if (out.isNotEmpty()) break
        }
        assertTrue("expected a spawned target", out.isNotEmpty())
        out.forEach { t ->
            assertTrue("closing speed", t.closingKnown)
            assertTrue("lateral offset", t.lateralKnown)
            assertTrue("vehicle class", t.sizeKnown)
        }
    }

    @Test
    fun lateralPosSaturatesAtFullScale() {
        val sim = RadarSimulator(Random(3))
        var out = emptyList<RadarVehicle>()
        repeat(300) {
            out = runTicks(sim, 1, riderSpeedKmh = 12.0).first
            if (out.isNotEmpty()) return@repeat
        }
        out.forEach { t ->
            assertTrue(t.lateralPos in -1f..1f)
        }
    }

    @Test
    fun resetClearsAllTargets() {
        val sim = RadarSimulator(Random(5))
        var spawned = false
        repeat(100) {
            if (runTicks(sim, 1, riderSpeedKmh = 18.0).first.isNotEmpty()) {
                spawned = true
                return@repeat
            }
        }
        assertTrue(spawned)
        sim.reset()
        assertEquals(emptyList<RadarVehicle>(), runTicks(sim, 1, riderSpeedKmh = 18.0).first)
    }

    @Test
    fun speedBandsMatchSize() {
        val sim = RadarSimulator(Random(11))
        var sawCar = false
        var sawTruck = false
        var sawBike = false
        val riderSpeed = 8.0
        repeat(500) {
            val out = runTicks(sim, 1, riderSpeedKmh = riderSpeed).first
            for (t in out) {
                when (t.size) {
                    RadarVehicleSize.CAR -> { sawCar = true; assertTrue("car band", t.closingKmh in 22..92) }
                    RadarVehicleSize.TRUCK -> { sawTruck = true; assertTrue("truck band", t.closingKmh in 22..82) }
                    RadarVehicleSize.BIKE -> { sawBike = true; assertTrue("bike band", t.closingKmh in 12..37) }
                }
            }
            if (sawCar && sawTruck && sawBike) return@repeat
        }
        assertTrue("expected to see all three sizes", sawCar && sawTruck && sawBike)
    }
}
