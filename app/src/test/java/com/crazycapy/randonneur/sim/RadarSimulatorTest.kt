/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.sim

import com.crazycapy.randonneur.nav.Geo
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
    ): List<RadarTarget> {
        var result: List<RadarTarget> = emptyList()
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
        var out = emptyList<RadarTarget>()
        repeat(200) {
            out = runTicks(sim, 1, riderSpeedKmh = 20.0)
            if (out.isNotEmpty()) return@repeat
        }
        assertTrue("expected a spawned target", out.isNotEmpty())
        out.forEach { t ->
            assertTrue("gap should start near max range, was ${t.gapM}", t.gapM <= RadarSimulator.MAX_RANGE_M + 0.01)
            assertTrue("target must be faster than the rider", t.speedKmh > 20.0)
            assertTrue("closing speed is positive while approaching", t.closingKmh > 0)
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
                out.forEach { assertTrue("slow target leaked: ${it.speedKmh}", it.speedKmh > 55.0) }
            }
        }
        assertTrue(sawAny)
    }

    @Test
    fun targetsCloseInAndDisappearAfterPassing() {
        val sim = RadarSimulator(Random(1))
        // Force a fresh spawn by running until one appears, then let it pass.
        var gapBefore = -1.0
        var passed = false
        repeat(600) {
            val out = runTicks(sim, 1, riderSpeedKmh = 10.0, dtSec = 1.0)
            if (out.isNotEmpty()) gapBefore = out.first().gapM
            if (gapBefore > 0 && out.isEmpty()) {
                passed = true
                return@repeat
            }
        }
        assertTrue("target should pass and vanish", passed)
    }

    @Test
    fun targetsArePositionedBehindAndLateral() {
        val sim = RadarSimulator(Random(99))
        var target: RadarTarget? = null
        repeat(100) {
            target = runTicks(sim, 1, riderSpeedKmh = 15.0).firstOrNull()
            if (target != null) return@repeat
        }
        val t = target ?: throw AssertionError("no target spawned")
        // Rider riding north from (50,10); a target behind must be south-west/near (50,10).
        val gap = Geo.distanceMeters(50.0, 10.0, t.lat, t.lon)
        assertTrue("target should be ~${t.gapM}m behind, was ${gap}m", gap in t.gapM - 4.0..t.gapM + 4.0)
        // A target on the rider's right (positive lateral) must be east of the road line.
        if (t.lateralM > 0) {
            assertTrue(t.lon >= 10.0 - 1e-6)
        } else {
            assertTrue(t.lon <= 10.0 + 1e-6)
        }
    }

    @Test
    fun lateralPosSaturatesAtFullScale() {
        val sim = RadarSimulator(Random(3))
        var out = emptyList<RadarTarget>()
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
        assertEquals(emptyList<RadarTarget>(), runTicks(sim, 1, riderSpeedKmh = 18.0))
    }

    @Test
    fun speedBandsMatchType() {
        val sim = RadarSimulator(Random(11))
        var sawCar = false
        var sawTruck = false
        var sawBike = false
        repeat(500) {
            val out = runTicks(sim, 1, riderSpeedKmh = 8.0)
            for (t in out) {
                when (t.type) {
                    RadarTargetType.CAR -> { sawCar = true; assertTrue(t.speedKmh in 30.0..100.0) }
                    RadarTargetType.TRUCK -> { sawTruck = true; assertTrue(t.speedKmh in 30.0..90.0) }
                    RadarTargetType.BIKE -> { sawBike = true; assertTrue(t.speedKmh in 20.0..45.0) }
                }
            }
            if (sawCar && sawTruck && sawBike) return@repeat
        }
        assertTrue("expected to see all three types", sawCar && sawTruck && sawBike)
    }
}
