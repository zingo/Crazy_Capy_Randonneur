/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * Waypoint — a named POI along the route (GPX <wpt>)
 *
 *   name  +  lat  +  lon  +  optional description (<desc>/<cmt>)
 *
 * Used by PoiTracker for proximity alerts (e.g. "water fountain ahead") and by
 * the map for checkpoint markers whose text is shown on tap.
 */
package com.crazycapy.randonneur.gpx

/** A named point of interest that sits on or near the route (a GPX `<wpt>`). */
data class Waypoint(
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Checkpoint info to show in the map popup (`<desc>`, else `<cmt>`). */
    val description: String? = null,
)
