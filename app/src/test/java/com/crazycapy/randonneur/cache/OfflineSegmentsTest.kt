/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import com.crazycapy.randonneur.gpx.GeoBox
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSegmentsTest {

    private fun lineTrack(count: Int, stepDegrees: Double = 0.01): Track =
        Track(
            "line",
            (0 until count).map { TrackPoint(50.0 + it * stepDegrees, 10.0) },
        )

    private fun lTrack(): Track = Track(
        "L",
        listOf(
            TrackPoint(53.0, 10.0),
            TrackPoint(53.0, 10.0035),
            TrackPoint(53.0008, 10.0035),
            TrackPoint(53.0012, 10.0035),
        ),
    )

    private fun contains(box: GeoBox, point: TrackPoint): Boolean =
        point.lat in box.south..box.north && point.lon in box.west..box.east

    @Test
    fun standardSegmentsSplitLongRouteAndIncludeLastPartial() {
        val track = lineTrack(26)
        assertTrue(track.lengthMeters > 25_000.0)
        val specs = OfflineSegments.regionSpecs(track, OfflineDetailStrategy.STANDARD)
        assertEquals(3, specs.size)
        assertEquals(listOf(0, 1, 2), specs.map { it.index })
        assertTrue(specs.all { it.count == 3 })
        assertTrue(specs.all { it.strategy == OfflineDetailStrategy.STANDARD })
        assertTrue(specs.all { it.minZoom == 8.0 && it.maxZoom == 14.0 })
        assertTrue(contains(specs.first().box, track.points.first()))
        assertTrue(contains(specs.last().box, track.points.last()))
        assertFalse(contains(specs.first().box, track.points.last()))
    }

    @Test
    fun shortRouteIsOneStandardSegment() {
        val specs = OfflineSegments.regionSpecs(lineTrack(5), OfflineDetailStrategy.STANDARD)
        assertEquals(1, specs.size)
        assertEquals(8.0, specs[0].minZoom, 0.0)
        assertEquals(14.0, specs[0].maxZoom, 0.0)
    }

    @Test
    fun mergeBoxesSkipsContainedAndMergesLargeOverlap() {
        val big = GeoBox(0.0, 0.0, 10.0, 10.0)
        val contained = GeoBox(2.0, 2.0, 4.0, 4.0)
        val overlapping = GeoBox(1.0, 1.0, 10.0, 10.0)
        val disjoint = GeoBox(20.0, 20.0, 30.0, 30.0)
        assertEquals(1, OfflineSegments.mergeBoxes(listOf(big, contained)).size)
        assertEquals(1, OfflineSegments.mergeBoxes(listOf(big, overlapping)).size)
        assertEquals(2, OfflineSegments.mergeBoxes(listOf(big, disjoint)).size)
        val merged = OfflineSegments.mergeBoxes(listOf(big, overlapping)).first()
        assertEquals(0.0, merged.south, 0.0)
        assertEquals(0.0, merged.west, 0.0)
        assertEquals(10.0, merged.north, 0.0)
        assertEquals(10.0, merged.east, 0.0)
    }

    @Test
    fun mergeBoxesKeepsAdjacentSegmentsWhenUnionIsCostly() {
        val first = GeoBox(55.40, 13.70, 55.46, 13.80)
        val second = GeoBox(55.44, 13.70, 55.50, 13.80)
        assertEquals(2, OfflineSegments.mergeBoxes(listOf(first, second)).size)
    }

    @Test
    fun mergeBoxesJoinsCheapUnion() {
        val a = GeoBox(0.0, 0.0, 10.0, 10.0)
        val b = GeoBox(0.0, 0.0, 10.0, 10.5)
        val merged = OfflineSegments.mergeBoxes(listOf(a, b))
        assertEquals(1, merged.size)
        assertEquals(10.5, merged.first().east, 0.0)
    }

    @Test
    fun turnFocusedAddsHighZoomBoxNearTurn() {
        val track = lTrack()
        val standard = OfflineSegments.regionSpecs(track, OfflineDetailStrategy.STANDARD)
        assertEquals(1, standard.size)
        assertEquals(8.0, standard[0].minZoom, 0.0)
        assertEquals(14.0, standard[0].maxZoom, 0.0)

        val focused = OfflineSegments.regionSpecs(track, OfflineDetailStrategy.TURN_FOCUSED)
        assertEquals(2, focused.size)
        assertEquals(OfflineDetailStrategy.TURN_FOCUSED, focused[0].strategy)
        assertTrue(focused[0].minZoom == 8.0 && focused[0].maxZoom == 12.0)
        assertTrue(focused[1].minZoom == 13.0 && focused[1].maxZoom == 14.0)
        assertTrue(contains(focused[1].box, TrackPoint(53.0, 10.0035)))
        assertEquals(listOf(0, 1), focused.map { it.index })
        assertTrue(focused.all { it.count == 2 })
    }

    @Test
    fun turnFocusedStraightRouteStaysLowZoom() {
        val specs = OfflineSegments.regionSpecs(lineTrack(26), OfflineDetailStrategy.TURN_FOCUSED)
        assertEquals(3, specs.size)
        assertTrue(specs.all { it.maxZoom == 12.0 && it.minZoom == 8.0 })
    }

    @Test
    fun turnBoxesIncludeDetectedTurnAndMergeOverlaps() {
        val boxes = OfflineSegments.turnBoxes(lTrack())
        assertEquals(1, boxes.size)
        assertTrue(contains(boxes[0], TrackPoint(53.0, 10.0035)))
        assertTrue(contains(boxes[0], TrackPoint(53.0008, 10.0035)))
    }
}
