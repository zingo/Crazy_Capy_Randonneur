/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.gpx

/** A named point of interest that sits on or near the route (a GPX `<wpt>`). */
data class Waypoint(
    val name: String,
    val lat: Double,
    val lon: Double,
)