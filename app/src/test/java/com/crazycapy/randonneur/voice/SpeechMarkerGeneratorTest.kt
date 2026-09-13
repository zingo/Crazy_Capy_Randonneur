/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.voice

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackPoint
import com.crazycapy.randonneur.nav.Geo
import com.crazycapy.randonneur.nav.NavEvent
import com.crazycapy.randonneur.nav.Turn
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechMarkerGeneratorTest {

    @Test
    fun generatesMarkersWithText() {
        val track = zigzagTrack()
        val markers = SpeechMarkerGenerator().generate(track, speedKmh = 28.0, stepMeters = 3.0)
        assertTrue("expected spoken markers on a turning route", markers.isNotEmpty())
        markers.forEach { m ->
            assertTrue("marker text must not be blank", m.text.isNotBlank())
            assertTrue("seq must be ordered", m.seq >= 0)
        }
    }

    @Test
    fun spokenTextIsNullForSilentEvents() {
        val turn = Turn(0, 0.0, 0.0, 0.0, 30.0)
        assertNull(spokenText(NavEvent.OnTrack(50.0, 10.0, 0.0, 0.0, null, null)))
        assertNull(spokenText(NavEvent.TurnPassed(turn)))
    }

    @Test
    fun spokenTextNonNullForSpeakingEvents() {
        val turn = Turn(0, 0.0, 0.0, 90.0, 90.0)
        assertNotNull(spokenText(NavEvent.TurnNow(turn)))
        assertNotNull(spokenText(NavEvent.TurnApproachAt(turn, 30, 500.0)))
        assertNotNull(spokenText(NavEvent.GoStraight(1500.0)))
        assertNotNull(spokenText(NavEvent.Arrived(50.0, 10.0)))
        assertNotNull(spokenText(NavEvent.OffRoute(50.0, 10.0, 50.0 to 10.0, 80.0)))
    }

    private fun zigzagTrack(): Track {
        val pts = mutableListOf<TrackPoint>()
        var lat = 50.0
        var lon = 10.0
        pts.add(TrackPoint(lat, lon))
        val legs = listOf(0.0, 90.0, 0.0, 270.0, 0.0, 90.0, 0.0)
        for (bearing in legs) {
            for (i in 1..60) {
                val (la, lo) = Geo.destinationMeters(lat, lon, bearing, i * 6.0)
                pts.add(TrackPoint(la, lo))
            }
            val (la, lo) = Geo.destinationMeters(lat, lon, bearing, 360.0)
            lat = la
            lon = lo
        }
        return Track("zigzag", pts)
    }
}
