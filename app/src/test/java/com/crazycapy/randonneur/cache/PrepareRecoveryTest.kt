/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackHash
import com.crazycapy.randonneur.gpx.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepareRecoveryTest {

    private fun track(name: String = "Morning loop", storageId: String? = "route-abc") = Track(
        name = name,
        points = listOf(TrackPoint(59.0, 10.0), TrackPoint(59.1, 10.1)),
        storageId = storageId,
    )

    private fun state(track: Track = track()) = PrepareRecoveryState(
        routeId = RouteIdentity.of(track),
        trackHash = TrackHash.of(track),
        routeName = track.name,
        strategy = OfflineDetailStrategy.TURN_FOCUSED,
        bothThemes = true,
    )

    @Test
    fun encodeDecodeRoundTrip() {
        val original = state()
        assertEquals(original, PrepareRecoveryState.decode(original.encode()))
    }

    @Test
    fun invalidOrIncompleteStateIsRejected() {
        assertNull(PrepareRecoveryState.decode(null))
        assertNull(PrepareRecoveryState.decode(""))
        assertNull(
            PrepareRecoveryState.decode(
                state().encode().replace("version=1", "version=2"),
            ),
        )
        assertNull(
            PrepareRecoveryState.decode(
                state().encode().lineSequence().filterNot { it.startsWith("trackHash=") }.joinToString("\n"),
            ),
        )
        assertNull(
            PrepareRecoveryState.decode(
                state().encode().replace("strategy=TURN_FOCUSED", "strategy=UNKNOWN"),
            ),
        )
        assertNull(
            PrepareRecoveryState.decode(
                state().encode().replace("bothThemes=true", "bothThemes=maybe"),
            ),
        )
    }

    @Test
    fun stateMatchesOnlyTheSameStoredRouteAndGeometry() {
        val original = track()
        assertTrue(state(original).matches(original))
        assertFalse(state(original).matches(track(name = "Renamed", storageId = "route-other")))
        assertFalse(
            state(original).matches(
                Track(
                    name = original.name,
                    points = listOf(TrackPoint(59.0, 10.0), TrackPoint(59.2, 10.2)),
                    storageId = "route-abc",
                ),
            ),
        )
    }
}
