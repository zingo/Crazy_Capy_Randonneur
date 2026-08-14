/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * PoiTracker — POI / waypoint tracker.
 *
 * Projects each named waypoint from the loaded GPX onto the route
 * polyline so the [NavigationService] can announce them as the rider
 * approaches.  Does NOT emit events itself — the caller polls `next()`
 * in each fix cycle.
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

    /** Waypoint projected onto the route polyline */
    private data class ProjectedWaypoint(val name: String, val alongM: Double)

    private val placed: List<ProjectedWaypoint> = track.waypoints
        .mapNotNull { waypoint ->
            track.routeDistanceTo(waypoint.lat, waypoint.lon)
                ?.let { ProjectedWaypoint(waypoint.name, it) }
        }
        .sortedBy { it.alongM }

    /** The next waypoint strictly ahead of `alongM`, or null. */
    fun next(alongM: Double): Pair<String, Double>? {
        for (p in placed) {
            if (p.alongM > alongM + 5.0) return p.name to (p.alongM - alongM)
        }
        return null
    }

    /** 1-based index of the next waypoint strictly ahead of `alongM`, or null. */
    fun nextIndex(alongM: Double): Int? {
        val placed = placed
        for (i in placed.indices) {
            if (placed[i].alongM > alongM + 5.0) return i + 1
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