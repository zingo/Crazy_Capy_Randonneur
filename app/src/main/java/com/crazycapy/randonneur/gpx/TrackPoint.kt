/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.gpx

/** A single point of a route track. */
data class TrackPoint(val lat: Double, val lon: Double, val ele: Double? = null)