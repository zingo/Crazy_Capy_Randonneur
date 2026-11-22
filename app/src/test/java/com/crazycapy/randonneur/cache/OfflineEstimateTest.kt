/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import com.crazycapy.randonneur.gpx.GeoBox
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineEstimateTest {

    private fun spec(box: GeoBox, minZoom: Double = 8.0, maxZoom: Double = 14.0): OfflineRegionSpec =
        OfflineRegionSpec(
            index = 0,
            count = 1,
            strategy = OfflineDetailStrategy.STANDARD,
            box = box,
            minZoom = minZoom,
            maxZoom = maxZoom,
        )

    private fun track(points: List<TrackPoint>): Track = Track("estimate", points)

    @Test
    fun moreAreaIsNotSmaller() {
        val small = listOf(spec(GeoBox(55.0, 10.0, 55.1, 10.1)))
        val big = listOf(spec(GeoBox(54.0, 9.0, 56.0, 11.0)))
        assertTrue(
            OfflineEstimateCalculator.estimate(big, 1).bytes >=
                OfflineEstimateCalculator.estimate(small, 1).bytes,
        )
    }

    @Test
    fun moreZoomIsNotSmaller() {
        val box = GeoBox(55.0, 10.0, 55.2, 10.2)
        val low = OfflineEstimateCalculator.estimate(listOf(spec(box, 8.0, 12.0)), 1)
        val high = OfflineEstimateCalculator.estimate(listOf(spec(box, 8.0, 14.0)), 1)
        assertTrue(high.bytes >= low.bytes)
        assertTrue(high.tiles >= low.tiles)
    }

    @Test
    fun moreThemesIsNotSmaller() {
        val specs = listOf(spec(GeoBox(55.0, 10.0, 55.2, 10.2)))
        val one = OfflineEstimateCalculator.estimate(specs, 1)
        val two = OfflineEstimateCalculator.estimate(specs, 2)
        assertTrue(two.bytes >= one.bytes)
        assertEquals(one.segments * 2, two.segments)
        assertEquals(2, two.themes)
    }

    @Test
    fun higherTuningZoomIsNotSmaller() {
        val route = track(
            listOf(
                TrackPoint(55.0, 10.0),
                TrackPoint(55.05, 10.05),
                TrackPoint(55.1, 10.1),
            ),
        )
        val low = OfflineEstimateCalculator.estimate(
            route,
            OfflineDetailStrategy.STANDARD,
            1,
            OfflineTuning.DEFAULT.copy(maxZoom = 14),
        )
        val high = OfflineEstimateCalculator.estimate(
            route,
            OfflineDetailStrategy.STANDARD,
            1,
            OfflineTuning.DEFAULT.copy(maxZoom = 16),
        )
        assertTrue(high.bytes >= low.bytes)
        assertTrue(high.bytesHigh >= high.bytesLow)
    }

    @Test
    fun widerTuningCorridorIsNotSmaller() {
        val route = track(listOf(TrackPoint(55.0, 10.0), TrackPoint(55.1, 10.1)))
        val narrow = OfflineEstimateCalculator.estimate(
            route,
            OfflineDetailStrategy.STANDARD,
            1,
            OfflineTuning.DEFAULT.copy(corridorPadMeters = 500.0),
        )
        val wide = OfflineEstimateCalculator.estimate(
            route,
            OfflineDetailStrategy.STANDARD,
            1,
            OfflineTuning.DEFAULT.copy(corridorPadMeters = 5_000.0),
        )
        assertTrue(wide.bytes >= narrow.bytes)
    }

    @Test
    fun widerTuningTurnBoxIsNotSmaller() {
        val route = track(
            listOf(
                TrackPoint(53.0, 10.0),
                TrackPoint(53.0, 10.0035),
                TrackPoint(53.0008, 10.0035),
                TrackPoint(53.0012, 10.0035),
            ),
        )
        val small = OfflineEstimateCalculator.estimate(
            route,
            OfflineDetailStrategy.TURN_FOCUSED,
            1,
            OfflineTuning.DEFAULT.copy(turnPadMeters = 250.0),
        )
        val large = OfflineEstimateCalculator.estimate(
            route,
            OfflineDetailStrategy.TURN_FOCUSED,
            1,
            OfflineTuning.DEFAULT.copy(turnPadMeters = 5_000.0),
        )
        assertTrue(large.bytes >= small.bytes)
    }

    @Test
    fun tinyRouteDoesNotCrash() {
        val tiny = track(listOf(TrackPoint(55.0, 10.0), TrackPoint(55.00001, 10.00001)))
        val estimate = OfflineEstimateCalculator.estimate(tiny, OfflineDetailStrategy.STANDARD, 2)
        assertTrue(estimate.bytes > 0L)
        assertTrue(estimate.segments >= 2)
        assertTrue(estimate.themes == 2)
    }

    @Test
    fun noSpecsIsZero() {
        val estimate = OfflineEstimateCalculator.estimate(emptyList(), 2)
        assertEquals(0L, estimate.bytes)
        assertEquals(0, estimate.segments)
        assertEquals(0L, estimate.tiles)
    }
}
