/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * TrackPoint — a single lat/lon waypoint on the route
 *
 *   lat  +  lon  +  optional elevation (ele)
 *
 * Building block for the Track polyline. Immutable data class.
 */
package com.crazycapy.randonneur.gpx

/** A single point of a route track. */
data class TrackPoint(val lat: Double, val lon: Double, val ele: Double? = null)