/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorosMergeTest {

    private val coros = CorosEnrichment(
        activityId = "a",
        activityName = "Ride",
        activityDate = "2026-11-27",
        activityStartSec = 1000,
        activityEndSec = 4000,
        overlapSec = 2800,
        avgHr = 150,
        maxHr = 190,
        avgCadence = 88,
        maxCadence = 104,
        avgPowerW = 240,
        maxPowerW = 520,
        trainingLoad = 120.0,
        aerobicTrainingEffect = 3.2,
        anaerobicTrainingEffect = 1.1,
        calories = 900.0,
        elevationGainM = 800.0,
        syncedAtMs = 5000,
    )

    @Test
    fun appHeartRateWinsOverCoros() {
        val merged = CorosMerge.merge(listOf(100, 120, 110), 250.0, 700.0, coros)

        assertEquals(MergedValue(110, MetricSource.APP), merged.avgHr)
        assertEquals(MergedValue(120, MetricSource.APP), merged.maxHr)
    }

    @Test
    fun corosHeartRateFillsWhenAppHasNoHr() {
        val merged = CorosMerge.merge(emptyList(), null, null, coros)

        assertEquals(MergedValue(150, MetricSource.COROS), merged.avgHr)
        assertEquals(MergedValue(190, MetricSource.COROS), merged.maxHr)
    }

    @Test
    fun corosCadenceAndPowerFillAppGaps() {
        val merged = CorosMerge.merge(emptyList(), null, null, coros)

        assertEquals(MergedValue(88, MetricSource.COROS), merged.avgCadence)
        assertEquals(MergedValue(104, MetricSource.COROS), merged.maxCadence)
        assertEquals(MergedValue(240, MetricSource.COROS), merged.avgPowerW)
        assertEquals(MergedValue(520, MetricSource.COROS), merged.maxPowerW)
        assertEquals(MergedValue(120.0, MetricSource.COROS), merged.trainingLoad)
        assertEquals(MergedValue(3.2, MetricSource.COROS), merged.aerobicTrainingEffect)
        assertEquals(MergedValue(1.1, MetricSource.COROS), merged.anaerobicTrainingEffect)
    }

    @Test
    fun appElevationAndCaloriesWinWhenPresent() {
        val merged = CorosMerge.merge(emptyList(), 0.0, 650.0, coros)

        assertEquals(MergedValue(0.0, MetricSource.APP), merged.elevationGainM)
        assertEquals(MergedValue(650.0, MetricSource.APP), merged.calories)
    }

    @Test
    fun corosElevationAndCaloriesFillWhenAppHasNone() {
        val merged = CorosMerge.merge(emptyList(), null, null, coros)

        assertEquals(MergedValue(800.0, MetricSource.COROS), merged.elevationGainM)
        assertEquals(MergedValue(900.0, MetricSource.COROS), merged.calories)
    }

    @Test
    fun noEnrichmentLeavesCorosOnlyFieldsEmpty() {
        val merged = CorosMerge.merge(listOf(100, 120), null, null, null)

        assertEquals(MergedValue(110, MetricSource.APP), merged.avgHr)
        assertEquals(MergedValue(120, MetricSource.APP), merged.maxHr)
        assertNull(merged.avgCadence)
        assertNull(merged.avgPowerW)
        assertNull(merged.elevationGainM)
        assertNull(merged.calories)
    }

    @Test
    fun tcxSummaryUsesAppHrWithCorosCadenceAndPower() {
        val tcx = CorosMerge.tcx(
            CorosMerge.merge(listOf(100, 120), null, null, coros),
        )

        assertEquals(110, tcx.avgHr)
        assertEquals(120, tcx.maxHr)
        assertEquals(88, tcx.cadence)
        assertEquals(240, tcx.avgPowerW)
        assertEquals(520, tcx.maxPowerW)
        assertEquals(900.0, tcx.calories ?: 0.0, 0.001)
    }

    @Test
    fun tcxSummaryFallsBackToCorosHrWhenAppLacksIt() {
        val tcx = CorosMerge.tcx(
            CorosMerge.merge(emptyList(), null, null, coros),
        )

        assertEquals(150, tcx.avgHr)
        assertEquals(190, tcx.maxHr)
    }
}
