/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * Track — route model for navigation
 *
 *   Ordered list of TrackPoint  +  cumulativeMetersulative distance array
 *   pointAtDistance(m)          +  reverse()  +  waypoints
 *
 * The core data structure that both NavEngine and the GPX loader/writer
 * operate on. Distances are pre-computed on construction for fast lookup.
 */
package com.crazycapy.randonneur.gpx

import com.crazycapy.randonneur.nav.Geo

/**
 * A route understood by the navigation engine: an ordered list of points.
 * All distances/bearings are computed lazily once and cached.
 */
class Track(
    val name: String,
    val points: List<TrackPoint>,
    val waypoints: List<Waypoint> = emptyList(),
) {
    init {
        require(points.size >= 2) { "Track needs at least two points" }
    }

    private val cumulativeMeters: DoubleArray by lazy {
        DoubleArray(points.size).also { arr ->
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                arr[i] = arr[i - 1] + Geo.distanceMeters(a.lat, a.lon, b.lat, b.lon)
            }
        }
    }

    /** Cumulative arc distance along the track, in meters. */
    fun distanceAt(index: Int): Double = cumulativeMeters[index]

    /** Total length in meters. */
    val lengthMeters: Double get() = cumulativeMeters[points.size - 1]

    /** Endpoint of segment [index]->[index+1]. */
    fun segmentEnd(index: Int): TrackPoint = points[index + 1]

    /** Point at `dist` meters along the track (linear interpolation). */
    fun pointAtDistance(dist: Double): TrackPoint {
        if (dist <= 0.0) return points.first()
        if (dist >= lengthMeters) return points.last()
        var lo = 0
        var hi = points.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (cumulativeMeters[mid] <= dist) lo = mid else hi = mid
        }
        val segDist = dist - cumulativeMeters[lo]
        val segLen = cumulativeMeters[lo + 1] - cumulativeMeters[lo]
        val f = if (segLen <= 0) 0.0 else segDist / segLen
        val a = points[lo]
        val b = points[lo + 1]
        return TrackPoint(
            a.lat + (b.lat - a.lat) * f,
            a.lon + (b.lon - a.lon) * f,
        )
    }

    /**
     * The same geometry traversed in the opposite direction. Turning the points
     * and waypoints around lets the engine/simulator ride the route backwards
     * with no special-casing of the geometry math.
     */
    fun reversed(): Track =
        Track("$name (reverse)", points.asReversed(), waypoints.asReversed())

    /**
     * Closest distance along the route in meters to a raw coordinate.
     * Returns -1 if nothing projects within sensible range (used for off-track waypoints).
     */
    fun routeDistanceTo(lat: Double, lon: Double, maxHitMeters: Double = 2000.0): Double? {
        var best = Double.MAX_VALUE
        var bestAlong: Double? = null
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val d = Geo.pointSegmentDistance(lat, lon, a.lat, a.lon, b.lat, b.lon)
            if (d < best) {
                best = d
                val xScale = 111_320.0 * kotlin.math.cos(Math.toRadians(a.lat))
                val yScale = 111_320.0
                val ax = (lon - a.lon) * xScale
                val ay = (lat - a.lat) * yScale
                val abx = (b.lon - a.lon) * xScale
                val aby = (b.lat - a.lat) * yScale
                val len2 = abx * abx + aby * aby
                val t = if (len2 < 1e-12) 0.0 else ((ax * abx + ay * aby) / len2).coerceIn(0.0, 1.0)
                best = d
                bestAlong = distanceAt(i) + t * (distanceAt(i + 1) - distanceAt(i))
            }
        }
        return if (best < maxHitMeters) bestAlong else null
    }
}