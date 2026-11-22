/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import com.crazycapy.randonneur.gpx.GeoBox
import com.crazycapy.randonneur.gpx.Track
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

data class OfflineEstimate(
    val bytes: Long,
    val segments: Int,
    val themes: Int,
    val tiles: Long,
)

object OfflineEstimateCalculator {

    const val ASSUMED_TILE_BYTES = 128L * 1024L
    private const val MAX_ZOOM = 22
    private const val MERCATOR_LAT_LIMIT = 85.05112878

    fun estimate(
        track: Track,
        strategy: OfflineDetailStrategy,
        themes: Int,
    ): OfflineEstimate = estimate(OfflineSegments.regionSpecs(track, strategy), themes)

    fun estimate(specs: List<OfflineRegionSpec>, themes: Int): OfflineEstimate {
        val themeCount = themes.coerceAtLeast(1)
        var tiles = 0L
        for (spec in specs) {
            tiles += tileCount(spec.box, spec.minZoom, spec.maxZoom)
        }
        val totalTiles = tiles.saturatedMultiply(themeCount.toLong())
        return OfflineEstimate(
            bytes = totalTiles.saturatedMultiply(ASSUMED_TILE_BYTES),
            segments = specs.size * themeCount,
            themes = themeCount,
            tiles = totalTiles,
        )
    }

    fun tileCount(box: GeoBox, minZoom: Double, maxZoom: Double): Long {
        val first = minZoom.toInt().coerceIn(0, MAX_ZOOM)
        val last = maxZoom.toInt().coerceIn(first, MAX_ZOOM)
        var total = 0L
        for (zoom in first..last) {
            total += tileCountAtZoom(box, zoom)
        }
        return total
    }

    private fun tileCountAtZoom(box: GeoBox, zoom: Int): Long {
        val edge = 1L shl zoom
        val west = tileX(box.west, zoom)
        val east = tileX(box.east, zoom)
        val north = tileY(box.north, zoom)
        val south = tileY(box.south, zoom)
        val width = (max(west, east) - minOf(west, east) + 1).coerceIn(1L, edge)
        val height = (max(north, south) - minOf(north, south) + 1).coerceIn(1L, edge)
        return width.saturatedMultiply(height)
    }

    private fun tileX(lon: Double, zoom: Int): Long {
        val edge = 1L shl zoom
        val normalized = ((lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0)
        return (normalized * edge).toLong().coerceIn(0L, edge - 1)
    }

    private fun tileY(lat: Double, zoom: Int): Long {
        val edge = 1L shl zoom
        val clamped = lat.coerceIn(-MERCATOR_LAT_LIMIT, MERCATOR_LAT_LIMIT)
        val radians = clamped * PI / 180.0
        val normalized = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
        return (normalized * edge).toLong().coerceIn(0L, edge - 1)
    }

    private fun Long.saturatedMultiply(other: Long): Long {
        if (this == 0L || other == 0L) return 0L
        val result = this * other
        return if (result / other != this) Long.MAX_VALUE else result
    }
}
