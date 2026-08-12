/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * TurnProjection — pure 3x3 bilinear anchor interpolation
 *
 *   Given a cached turn-preview bitmap with 3x3 lat/lon anchor grid,
 *   project a live GPS lat/lon onto the pixel coordinate of the bitmap
 *   via bilinear interpolation between the four nearest anchors.
 *
 * Pure Kotlin, no Android deps. Unit-testable.
 */
package com.crazycapy.randonneur.cache

import kotlin.math.floor

/**
 * A measured lat/lon -> pixel anchor on a pre-rendered turn-preview bitmap.
 * Samples are taken from the live [org.maplibre.android.snapshotter.MapSnapshot]
 * at generation time (via `pixelForLatLng`) so the route line and rider arrow can
 * be redrawn on the cached image without a MapSnapshot object at ride time.
 */
data class Anchor(val lat: Double, val lon: Double, val x: Float, val y: Float)

/**
 * Pure bilinear projection over an evenly spaced, row-major grid of [Anchor]s
 * ordered from bottom (min-lat) to top (max-lat), each row from min-lon to
 * max-lon. Exactly reproduces the pixels inside each grid cell, and the mercator
 * bend across a ~300 m window is far below pixel resolution.
 */
object TurnProjection {

    const val GRID = 3 // 3x3 = 9 anchors

    /** Project [lat],[lon] onto the cached bitmap's pixel space. */
    fun project(anchors: List<Anchor>, lat: Double, lon: Double): Pair<Float, Float> {
        if (anchors.size < GRID * GRID) return 0f to 0f
        val lonMin = anchors[0].lon
        val lonMax = anchors[GRID - 1].lon
        val latMin = anchors[0].lat
        val latMax = anchors[GRID * (GRID - 1)].lat
        val cols = GRID
        val rows = GRID
        val tf = (lon - lonMin) / (lonMax - lonMin)
        val sf = (lat - latMin) / (latMax - latMin)
        if (!tf.isFinite() || !sf.isFinite()) return 0f to 0f
        val cc = floor(tf.coerceIn(0.0, 1.0) * (cols - 1)).toInt().coerceIn(0, cols - 2)
        val rc = floor(sf.coerceIn(0.0, 1.0) * (rows - 1)).toInt().coerceIn(0, rows - 2)
        val c0 = anchors[rc * cols + cc]
        val c1 = anchors[rc * cols + cc + 1]
        val c2 = anchors[(rc + 1) * cols + cc]
        val c3 = anchors[(rc + 1) * cols + cc + 1]
        val tc = ((tf * (cols - 1) - cc).coerceIn(0.0, 1.0)).toFloat()
        val ts = ((sf * (rows - 1) - rc).coerceIn(0.0, 1.0)).toFloat()
        val topX = c0.x + (c1.x - c0.x) * tc
        val topY = c0.y + (c1.y - c0.y) * tc
        val botX = c2.x + (c3.x - c2.x) * tc
        val botY = c2.y + (c3.y - c2.y) * tc
        return (topX + (botX - topX) * ts) to (topY + (botY - topY) * ts)
    }

    /** Build an evenly spaced grid of query lat/lon for [bounds]-derived corners. */
    fun gridLatLon(latMin: Double, lonMin: Double, latMax: Double, lonMax: Double): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(GRID * GRID)
        for (r in 0 until GRID) {
            val lat = latMin + (latMax - latMin) * (r / (GRID - 1.0))
            for (c in 0 until GRID) {
                val lon = lonMin + (lonMax - lonMin) * (c / (GRID - 1.0))
                out.add(lat to lon)
            }
        }
        return out
    }
}