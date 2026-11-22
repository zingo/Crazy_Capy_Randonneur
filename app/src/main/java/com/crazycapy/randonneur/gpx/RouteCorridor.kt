/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.gpx

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

data class GeoBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

object RouteCorridor {

    private const val METERS_PER_DEGREE_LAT = 111_320.0
    private const val DEFAULT_PAD_METERS = 1500.0

    fun around(track: Track, padMeters: Double = DEFAULT_PAD_METERS): GeoBox {
        val pad = padMeters.coerceAtLeast(0.0)
        var south = Double.MAX_VALUE
        var west = Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        for (point in track.points) {
            south = min(south, point.lat)
            north = max(north, point.lat)
            west = min(west, point.lon)
            east = max(east, point.lon)
        }
        val centerLat = (south + north) / 2.0
        val latPad = pad / METERS_PER_DEGREE_LAT
        val lonScale = max(1.0e-6, cos(Math.toRadians(centerLat)))
        val lonPad = pad / (METERS_PER_DEGREE_LAT * lonScale)
        return GeoBox(
            south = (south - latPad).coerceAtLeast(-90.0),
            west = (west - lonPad).coerceAtLeast(-180.0),
            north = (north + latPad).coerceAtMost(90.0),
            east = (east + lonPad).coerceAtMost(180.0),
        )
    }

    fun sampleDistances(lengthMeters: Double, stepMeters: Double): List<Double> {
        if (lengthMeters <= 0.0 || stepMeters <= 0.0) return emptyList()
        val distances = ArrayList<Double>()
        var d = 0.0
        while (d < lengthMeters) {
            distances.add(d)
            d += stepMeters
        }
        return distances
    }
}
