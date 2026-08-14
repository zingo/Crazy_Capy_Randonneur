/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RwGpsParser — ridewithgps route JSON -> Track
 *
 *   route JSON (GET /routes/<id>.json) -> Track + waypoints
 *
 * ridewithgps stores brevet controls as "POIs" (poi_type_name "control"),
 * but its GPX export omits them unless you have premium. This parser restores
 * them: the route description/geometry is rebuilt from track_points and every
 * named POI becomes a Waypoint so checkpoints reappear.
 *
 * Pure Kotlin, no Android deps.
 */
package com.crazycapy.randonneur.gpx

import com.google.gson.JsonParser

/** Turns the public ridewithgps route JSON into a [Track] (or null if unparseable). */
object RwGpsParser {

    /** A single entry from a user's public route list. */
    data class RouteSummary(val id: String, val name: String, val distanceM: Double)

    fun parse(json: String): Track? = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val name = root.get("name")?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: "RideWithGPS route"
        val trackArr = root.getAsJsonArray("track_points")
        val points = ArrayList<TrackPoint>(trackArr.size())
        for (el in trackArr) {
            val o = el.asJsonObject
            val lon = o.get("x")?.asDouble
            val lat = o.get("y")?.asDouble
            val ele = o.get("e")?.asDouble
            if (lon == null || lat == null) continue
            points.add(TrackPoint(lat, lon, ele))
        }
        if (points.size < 2) return null

        val waypoints = ArrayList<Waypoint>()
        val pois = root.getAsJsonArray("points_of_interest")
        if (pois != null) {
            for (el in pois) {
                val o = el.asJsonObject
                val poiName = o.get("name")?.asString?.trim().takeIf { !it.isNullOrEmpty() } ?: continue
                val lat = o.get("lat")?.asDouble
                val lon = o.get("lng")?.asDouble
                if (lat == null || lon == null) continue
                val desc = o.get("description")?.asString?.takeIf { it.isNotBlank() }
                waypoints.add(Waypoint(poiName, lat, lon, desc))
            }
        }
        Track(name, points, waypoints)
    }.getOrNull()

    /** Parses a user's public route list (`users/<id>/routes.json`) into summaries. */
    fun parseRouteList(json: String): List<RouteSummary> = runCatching {
        val arr = JsonParser.parseString(json).asJsonArray
        val out = ArrayList<RouteSummary>(arr.size())
        for (el in arr) {
            val o = el.asJsonObject
            val id = o.get("id")?.asLong?.toString() ?: continue
            val name = o.get("name")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: "Route $id"
            val distance = o.get("distance")?.asDouble ?: 0.0
            out.add(RouteSummary(id, name, distance))
        }
        out
    }.getOrDefault(emptyList())
}
