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
        return expand(GeoBox(south, west, north, east), padMeters)
    }

    fun between(track: Track, startM: Double, endM: Double, padMeters: Double = DEFAULT_PAD_METERS): GeoBox {
        val lo = min(startM, endM).coerceAtLeast(0.0)
        val hi = max(startM, endM).coerceAtMost(track.lengthMeters)
        var south = Double.MAX_VALUE
        var west = Double.MAX_VALUE
        var north = -Double.MAX_VALUE
        var east = -Double.MAX_VALUE
        fun include(point: TrackPoint) {
            south = min(south, point.lat)
            north = max(north, point.lat)
            west = min(west, point.lon)
            east = max(east, point.lon)
        }
        include(track.pointAtDistance(lo))
        include(track.pointAtDistance(hi))
        for (i in track.points.indices) {
            if (track.distanceAt(i) in lo..hi) include(track.points[i])
        }
        return expand(GeoBox(south, west, north, east), padMeters)
    }

    fun expand(box: GeoBox, padMeters: Double): GeoBox {
        val pad = padMeters.coerceAtLeast(0.0)
        val centerLat = (box.south + box.north) / 2.0
        val latPad = pad / METERS_PER_DEGREE_LAT
        val lonScale = max(1.0e-6, cos(Math.toRadians(centerLat)))
        val lonPad = pad / (METERS_PER_DEGREE_LAT * lonScale)
        return GeoBox(
            south = (box.south - latPad).coerceAtLeast(-90.0),
            west = (box.west - lonPad).coerceAtLeast(-180.0),
            north = (box.north + latPad).coerceAtMost(90.0),
            east = (box.east + lonPad).coerceAtMost(180.0),
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
