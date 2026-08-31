/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.radar

import com.crazycapy.randonneur.nav.Geo

/**
 * Projects a radar-space [RadarVehicle] (range + lateral offset relative to the
 * rider) onto absolute lat/lon for map rendering. Shared by the ghost-ride
 * simulator and the live stream consumer so both feed the same layer.
 */
object RadarProjection {

    fun project(vehicle: RadarVehicle, riderLat: Double, riderLon: Double, riderBearing: Double): RadarVehicle {
        // An ahead target lies along the rider's heading; anything else is
        // behind, i.e. back along the reverse heading.
        val alongBearing = if (vehicle.isAhead) riderBearing else riderBearing + 180.0
        val (baseLat, baseLon) = Geo.destinationMeters(riderLat, riderLon, alongBearing, vehicle.distanceM.toDouble())
        val (lat, lon) = Geo.destinationMeters(baseLat, baseLon, riderBearing + 90.0, vehicle.rangeXm.toDouble())
        return vehicle.copy(lat = lat, lon = lon)
    }
}
