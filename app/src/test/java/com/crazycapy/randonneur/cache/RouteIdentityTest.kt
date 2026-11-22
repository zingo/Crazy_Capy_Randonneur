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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteIdentityTest {

    private fun track(name: String, storageId: String? = null) = Track(
        name = name,
        points = listOf(TrackPoint(59.0, 10.0), TrackPoint(59.1, 10.1)),
        storageId = storageId,
    )

    @Test
    fun storedIdSurvivesRename() {
        val before = track("Morning loop", "route-abc")
        val after = track("Evening loop", "route-abc")
        assertEquals("route-abc", RouteIdentity.of(before))
        assertEquals("route-abc", RouteIdentity.of(after))
    }

    @Test
    fun adHocIdentityComesFromGeometryNotName() {
        val first = track("One name")
        val second = track("Another name")
        assertEquals(TrackHash.of(first), TrackHash.of(second))
        assertEquals(RouteIdentity.of(first), RouteIdentity.of(second))
        assertEquals(RouteIdentity.contentId(TrackHash.of(first)), RouteIdentity.of(first))
    }

    @Test
    fun changedGeometryChangesContentIdentity() {
        val first = track("Same name")
        val second = Track(
            name = "Same name",
            points = listOf(TrackPoint(59.0, 10.0), TrackPoint(59.2, 10.2)),
        )
        assertNotEquals(RouteIdentity.of(first), RouteIdentity.of(second))
    }

    @Test
    fun legacyPackWithOldNameIdRelatesByHash() {
        val current = track("Renamed", "route-abc")
        val hash = TrackHash.of(current)
        val oldId = RouteIdentity.legacyNameId("Original")
        assertTrue(RouteIdentity.relatesTo(oldId, hash, "route-abc", hash))
        assertTrue(RouteIdentity.needsMigration(oldId, "route-abc"))
    }

    @Test
    fun unrelatedPackIsNotRelatedOrMigrated() {
        val hash = TrackHash.of(track("Mine"))
        val otherHash = TrackHash.of(
            Track(
                name = "Elsewhere",
                points = listOf(TrackPoint(10.0, 20.0), TrackPoint(10.1, 20.1)),
            ),
        )
        assertFalse(RouteIdentity.relatesTo("route-other", otherHash, "route-abc", hash))
        assertFalse(RouteIdentity.needsMigration(null, "route-abc"))
        assertEquals("route-abc", RouteIdentity.of(track("Mine", "route-abc")))
    }
}
