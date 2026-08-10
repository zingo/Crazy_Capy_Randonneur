/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.nav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small, dependency-free geo helpers. */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val s1 = sin(dLat / 2)
        val s2 = sin(dLon / 2)
        val a = s1 * s1 + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * s2 * s2
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** Initial bearing from p1 to p2, degrees in [0,360). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    /** Normalized signed angle out-in in [-180,180]. Positive = right turn. */
    fun turnDegrees(bearingIn: Double, bearingOut: Double): Double {
        val d = bearingOut - bearingIn
        return (d + 540.0) % 360.0 - 180.0
    }

    /** Distance in meters from point p to the line segment a->b (equirectangular approx). */
    fun pointSegmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val xScale = 111_320.0 * cos(Math.toRadians(ay))
        val yScale = 111_320.0
        val pX = (px - ax) * xScale
        val pY = (py - ay) * yScale
        val abX = (bx - ax) * xScale
        val abY = (by - ay) * yScale
        val len2 = abX * abX + abY * abY
        val t = if (len2 < 1e-12) 0.0 else ((pX * abX + pY * abY) / len2).coerceIn(0.0, 1.0)
        val projX = t * abX
        val projY = t * abY
        val dx = pX - projX
        val dy = pY - projY
        return sqrt(dx * dx + dy * dy)
    }
}