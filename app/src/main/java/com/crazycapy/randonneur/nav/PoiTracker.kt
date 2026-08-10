/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.nav

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.Waypoint

/**
 * Pure-Kotlin helper that projects a track's waypoints (POIs) onto the route and
 * answers "what is the next POI, and how far away is it?" for an arbitrary
 * position along the route.
 */
class PoiTracker(track: Track) {

    private data class Placed(val name: String, val alongM: Double)

    private val placed: List<Placed> = track.waypoints
        .mapNotNull { wpt ->
            track.routeDistanceTo(wpt.lat, wpt.lon)
                ?.let { Placed(wpt.name, it) }
        }
        .sortedBy { it.alongM }

    /** The next waypoint strictly ahead of `alongM`, or null. */
    fun next(alongM: Double): Pair<String, Double>? {
        for (p in placed) {
            if (p.alongM > alongM + 5.0) return p.name to (p.alongM - alongM)
        }
        return null
    }

    /** Number of waypoints that successfully projected onto the route. */
    val count: Int get() = placed.size

    companion object {
        /** Convenience for tests/nav: reuse the pure logic through a Track. */
        fun project(track: Track, lat: Double, lon: Double): Double? =
            track.routeDistanceTo(lat, lon)
    }
}