/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.sim

import com.crazycapy.randonneur.nav.Geo
import com.crazycapy.randonneur.radar.RadarVehicle
import com.crazycapy.randonneur.radar.RadarVehicleSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RadarSimulatorTest {

    private fun runTicks(
        sim: RadarSimulator,
        count: Int,
        riderSpeedKmh: Double = 28.0,
        dtSec: Double = 1.0,
        bearing: Double = 0.0,
        lat: Double = 50.0,
        lon: Double = 10.0,
    ): List<RadarVehicle> {
        var result: List<RadarVehicle> = emptyList()
        var rLat = lat
        var rLon = lon
        repeat(count) {
            result = sim.tick(rLat, rLon, bearing, riderSpeedKmh, dtSec)
            // Rider advances so a target can actually pass.
            val (nl, no) = Geo.destinationMeters(rLat, rLon, bearing, riderSpeedKmh / 3.6 * dtSec)
            rLat = nl
            rLon = no
        }
        return result
    }

    @Test
    fun spawnsTrafficBehindAtMaxRange() {
        val sim = RadarSimulator(Random(42))
        var out = emptyList<RadarVehicle>()
        repeat(200) {
            out = runTicks(sim, 1, riderSpeedKmh = 20.0)
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
            val out = runTicks(sim, 1, riderSpeedKmh = 55.0)
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
        // Force a fresh spawn by running until one appears, then let it pass.
        var distanceBefore = -1
        var passed = false
        repeat(600) {
            val out = runTicks(sim, 1, riderSpeedKmh = 10.0, dtSec = 1.0)
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
        repeat(100) {
            target = runTicks(sim, 1, riderSpeedKmh = 15.0).firstOrNull()
            if (target != null) return@repeat
        }
        val t = target ?: throw AssertionError("no target spawned")
        // Rider riding north from (50,10); a target behind must be near (50,10).
        val gap = Geo.distanceMeters(50.0, 10.0, t.lat, t.lon)
        assertTrue("target should be ~${t.distanceM}m behind, was ${gap}m", gap in t.distanceM - 4.0..t.distanceM + 4.0)
        // A target on the rider's right (positive lateral) must be east of the road line.
        if (t.rangeXm > 0) {
            assertTrue(t.lon >= 10.0 - 1e-6)
        } else {
            assertTrue(t.lon <= 10.0 + 1e-6)
        }
    }

    @Test
    fun lateralPosSaturatesAtFullScale() {
        val sim = RadarSimulator(Random(3))
        var out = emptyList<RadarVehicle>()
        repeat(300) {
            out = runTicks(sim, 1, riderSpeedKmh = 12.0)
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
            if (runTicks(sim, 1, riderSpeedKmh = 18.0).isNotEmpty()) {
                spawned = true
                return@repeat
            }
        }
        assertTrue(spawned)
        sim.reset()
        assertEquals(emptyList<RadarVehicle>(), runTicks(sim, 1, riderSpeedKmh = 18.0))
    }

    @Test
    fun speedBandsMatchSize() {
        val sim = RadarSimulator(Random(11))
        var sawCar = false
        var sawTruck = false
        var sawBike = false
        val riderSpeed = 8.0
        repeat(500) {
            val out = runTicks(sim, 1, riderSpeedKmh = riderSpeed)
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
