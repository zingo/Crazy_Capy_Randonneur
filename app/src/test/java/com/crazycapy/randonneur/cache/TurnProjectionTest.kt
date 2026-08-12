/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnProjectionTest {

    @Test
    fun `project lat lon to pixel with perfect grid`() {
        val anchors = TurnProjection.gridLatLon(10.0, 20.0, 11.0, 21.0)
            .map { (lat, lon) ->
                // Simulate a perfect linear mapping: x=lon*100, y=(lat-10)*100
                Anchor(lat, lon, ((lon - 20.0) * 100).toFloat(), ((lat - 10.0) * 100).toFloat())
            }
        // Center of the grid
        val (x, y) = TurnProjection.project(anchors, 10.5, 20.5)
        assertEquals(50f, x, 0.5f)
        assertEquals(50f, y, 0.5f)
    }

    @Test
    fun `project southwest corner`() {
        val anchors = TurnProjection.gridLatLon(10.0, 20.0, 11.0, 21.0)
            .map { (lat, lon) ->
                Anchor(lat, lon, ((lon - 20.0) * 100).toFloat(), ((lat - 10.0) * 100).toFloat())
            }
        val (x, y) = TurnProjection.project(anchors, 10.0, 20.0)
        assertEquals(0f, x, 0.5f)
        assertEquals(0f, y, 0.5f)
    }

    @Test
    fun `project northeast corner`() {
        val anchors = TurnProjection.gridLatLon(10.0, 20.0, 11.0, 21.0)
            .map { (lat, lon) ->
                Anchor(lat, lon, ((lon - 20.0) * 100).toFloat(), ((lat - 10.0) * 100).toFloat())
            }
        val (x, y) = TurnProjection.project(anchors, 11.0, 21.0)
        assertEquals(100f, x, 0.5f)
        assertEquals(100f, y, 0.5f)
    }

    @Test
    fun `grid lat lon returns 9 points`() {
        val grid = TurnProjection.gridLatLon(1.0, 2.0, 3.0, 4.0)
        assertEquals(9, grid.size)
        assertEquals(1.0 to 2.0, grid[0])
        assertEquals(1.0 to 4.0, grid[2])
        assertEquals(3.0 to 2.0, grid[6])
        assertEquals(3.0 to 4.0, grid[8])
    }

    @Test
    fun `project handles out of bounds gracefully`() {
        val anchors = TurnProjection.gridLatLon(10.0, 20.0, 11.0, 21.0)
            .map { (lat, lon) ->
                Anchor(lat, lon, ((lon - 20.0) * 100).toFloat(), ((lat - 10.0) * 100).toFloat())
            }
        // Point outside the grid (clamped)
        val (x, y) = TurnProjection.project(anchors, 9.0, 19.0)
        assertEquals(0f, x, 0.5f)
        assertEquals(0f, y, 0.5f)
    }
}
