/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackHash

object RouteIdentity {

    fun of(track: Track): String =
        track.storageId?.takeIf { it.isNotBlank() } ?: contentId(TrackHash.of(track))

    fun contentId(trackHash: String): String = "track-$trackHash"

    fun legacyNameId(name: String): String =
        "route-" + (name.trim().hashCode() and 0x7fffffff).toString(16)

    fun relatesTo(packRouteId: String?, packTrackHash: String?, routeId: String, trackHash: String): Boolean =
        packRouteId == routeId || (packTrackHash != null && packTrackHash == trackHash)

    fun needsMigration(packRouteId: String?, routeId: String): Boolean =
        packRouteId != null && packRouteId != routeId
}
