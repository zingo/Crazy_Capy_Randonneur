/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * SpeechMarkerGenerator — headless dry run of spoken guidance.
 *
 *   Walks a Track through NavEngine as fast as the CPU allows (no sleeping,
 *   no TTS, no map) and records every spoken clip with its trigger location.
 *   The result is a list of SpeechMarker for the "speech markers" map layer,
 *   so a rider can audit what the app would say without riding in real time.
 *
 * Pure Kotlin (NavEngine + Phrases + Track only) and unit-testable.
 */
package com.crazycapy.randonneur.voice

import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.nav.NavEngine
import com.crazycapy.randonneur.nav.NavEvent

class SpeechMarkerGenerator {

    /**
     * Replay the [track] through a fresh [NavEngine] at [stepMeters] resolution
     * and collect every spoken clip. [speedKmh] drives the timed lead notices
     * ("turn left in 500 m" fires at a distance that depends on speed).
     */
    fun generate(track: Track, speedKmh: Double = 28.0, stepMeters: Double = 5.0): List<SpeechMarker> {
        val markers = mutableListOf<SpeechMarker>()
        val engine = NavEngine(track)
        var currentLat = track.points.first().lat
        var currentLon = track.points.first().lon
        engine.addListener { event ->
            // Track the rider's snapped position so turn events (which carry
            // no coordinates) are stamped with where they actually happened.
            val (lat, lon) = eventLocation(event, currentLat, currentLon)
            currentLat = lat
            currentLon = lon
            val text = spokenText(event) ?: return@addListener
            markers.add(SpeechMarker(lat, lon, text, markers.size))
        }

        var d = 0.0
        while (d <= track.lengthMeters) {
            val p = track.pointAtDistance(d.coerceAtMost(track.lengthMeters))
            engine.update(p.lat, p.lon, speedKmh)
            d += stepMeters
        }
        val last = track.points.last()
        engine.update(last.lat, last.lon, speedKmh)
        return markers
    }

    private fun eventLocation(event: NavEvent, fallbackLat: Double, fallbackLon: Double): Pair<Double, Double> = when (event) {
        is NavEvent.OnTrack -> event.lat to event.lon
        is NavEvent.OffRoute -> event.lat to event.lon
        is NavEvent.OffRouteStill -> event.lat to event.lon
        is NavEvent.BackOnRoute -> event.lat to event.lon
        is NavEvent.Arrived -> event.lat to event.lon
        else -> fallbackLat to fallbackLon
    }
}
