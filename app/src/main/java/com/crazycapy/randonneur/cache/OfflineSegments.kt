/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import com.crazycapy.randonneur.gpx.GeoBox
import com.crazycapy.randonneur.gpx.RouteCorridor
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.nav.TurnFinder
import kotlin.math.max
import kotlin.math.min

enum class OfflineDetailStrategy { STANDARD, TURN_FOCUSED }

data class OfflineRegionSpec(
    val index: Int,
    val count: Int,
    val strategy: OfflineDetailStrategy,
    val box: GeoBox,
    val minZoom: Double,
    val maxZoom: Double,
)

object OfflineSegments {

    const val CHUNK_METERS = 10_000.0
    const val CORRIDOR_PAD_METERS = 1_500.0
    const val TURN_PAD_METERS = 1_000.0
    const val TURN_CROSS_PAD_METERS = 250.0
    const val STANDARD_MIN_ZOOM = 8.0
    const val STANDARD_MAX_ZOOM = 14.0
    const val FOCUSED_LOW_MAX_ZOOM = 12.0
    const val FOCUSED_HIGH_MIN_ZOOM = 13.0
    const val FOCUSED_HIGH_MAX_ZOOM = 14.0

    private const val MERGE_OVERLAP_RATIO = 0.8
    private const val MERGE_UNION_RATIO = 1.2

    fun regionSpecs(track: Track, strategy: OfflineDetailStrategy): List<OfflineRegionSpec> {
        val corridors = corridorBoxes(track)
        val focused = strategy == OfflineDetailStrategy.TURN_FOCUSED
        val lowMax = if (focused) FOCUSED_LOW_MAX_ZOOM else STANDARD_MAX_ZOOM
        val low = corridors.map { box ->
            OfflineRegionSpec(
                index = 0,
                count = 0,
                strategy = strategy,
                box = box,
                minZoom = STANDARD_MIN_ZOOM,
                maxZoom = lowMax,
            )
        }
        val high = if (focused) {
            turnBoxes(track).map { box ->
                OfflineRegionSpec(
                    index = 0,
                    count = 0,
                    strategy = strategy,
                    box = box,
                    minZoom = FOCUSED_HIGH_MIN_ZOOM,
                    maxZoom = FOCUSED_HIGH_MAX_ZOOM,
                )
            }
        } else {
            emptyList()
        }
        val all = low + high
        return all.mapIndexed { i, spec -> spec.copy(index = i, count = all.size) }
    }

    fun corridorBoxes(track: Track, padMeters: Double = CORRIDOR_PAD_METERS): List<GeoBox> {
        val length = track.lengthMeters
        if (length <= 0.0) return listOf(RouteCorridor.around(track, padMeters))
        val raw = ArrayList<GeoBox>()
        var start = 0.0
        while (start < length) {
            val end = min(start + CHUNK_METERS, length)
            raw.add(RouteCorridor.between(track, start, end, padMeters))
            start = end
        }
        return mergeBoxes(raw)
    }

    fun turnBoxes(track: Track): List<GeoBox> {
        val turns = TurnFinder.find(track)
        val boxes = turns.map { turn ->
            val start = (turn.distAlongM - TURN_PAD_METERS).coerceAtLeast(0.0)
            val end = (turn.distAlongM + TURN_PAD_METERS).coerceAtMost(track.lengthMeters)
            RouteCorridor.between(track, start, end, TURN_CROSS_PAD_METERS)
        }
        return mergeBoxes(boxes)
    }

    fun mergeBoxes(boxes: List<GeoBox>): List<GeoBox> {
        val merged = ArrayList<GeoBox>()
        for (box in boxes) {
            var candidate = box
            var i = 0
            while (i < merged.size) {
                if (shouldMerge(merged[i], candidate)) {
                    candidate = union(merged[i], candidate)
                    merged.removeAt(i)
                    i = 0
                } else {
                    i++
                }
            }
            merged.add(candidate)
        }
        return merged
    }

    private fun shouldMerge(a: GeoBox, b: GeoBox): Boolean {
        val minArea = min(area(a), area(b))
        val intersection = intersectionArea(a, b)
        if (minArea > 0.0 && intersection >= minArea * MERGE_OVERLAP_RATIO) return true
        return area(union(a, b)) <= max(area(a), area(b)) * MERGE_UNION_RATIO
    }

    private fun area(box: GeoBox): Double =
        (box.north - box.south) * (box.east - box.west)

    private fun intersectionArea(a: GeoBox, b: GeoBox): Double {
        val height = min(a.north, b.north) - max(a.south, b.south)
        val width = min(a.east, b.east) - max(a.west, b.west)
        return if (height <= 0.0 || width <= 0.0) 0.0 else height * width
    }

    private fun union(a: GeoBox, b: GeoBox): GeoBox = GeoBox(
        south = min(a.south, b.south),
        west = min(a.west, b.west),
        north = max(a.north, b.north),
        east = max(a.east, b.east),
    )
}
