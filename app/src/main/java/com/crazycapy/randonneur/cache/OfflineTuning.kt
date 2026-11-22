/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import kotlin.math.roundToInt

data class OfflineTuning(
    val segmentMeters: Double = DEFAULT_SEGMENT_METERS,
    val corridorPadMeters: Double = DEFAULT_CORRIDOR_PAD_METERS,
    val turnPadMeters: Double = DEFAULT_TURN_PAD_METERS,
    val maxZoom: Int = DEFAULT_MAX_ZOOM,
) {
    val signature: String
        get() = "${segmentMeters.roundToInt()}|${corridorPadMeters.roundToInt()}|" +
            "${turnPadMeters.roundToInt()}|$maxZoom"

    companion object {
        const val MIN_SEGMENT_KM = 2.0
        const val MAX_SEGMENT_KM = 50.0
        const val DEFAULT_SEGMENT_KM = 10.0
        const val MIN_CORRIDOR_PAD_KM = 0.5
        const val MAX_CORRIDOR_PAD_KM = 10.0
        const val DEFAULT_CORRIDOR_PAD_KM = 1.5
        const val MIN_TURN_PAD_KM = 0.25
        const val MAX_TURN_PAD_KM = 5.0
        const val DEFAULT_TURN_PAD_KM = 1.0
        const val MIN_MAX_ZOOM = 13
        const val MAX_MAX_ZOOM = 16
        const val DEFAULT_MAX_ZOOM = 14

        const val DEFAULT_SEGMENT_METERS = 10_000.0
        const val DEFAULT_CORRIDOR_PAD_METERS = 1_500.0
        const val DEFAULT_TURN_PAD_METERS = 1_000.0

        private const val METERS_PER_KM = 1_000.0

        val DEFAULT = OfflineTuning()

        fun fromSettings(
            segmentKm: Double,
            corridorPadKm: Double,
            turnPadKm: Double,
            maxZoom: Int,
        ): OfflineTuning = OfflineTuning(
            segmentMeters = sanitizeSegmentKm(segmentKm) * METERS_PER_KM,
            corridorPadMeters = sanitizeCorridorPadKm(corridorPadKm) * METERS_PER_KM,
            turnPadMeters = sanitizeTurnPadKm(turnPadKm) * METERS_PER_KM,
            maxZoom = sanitizeMaxZoom(maxZoom),
        )

        fun sanitizeSegmentKm(value: Double): Double =
            sanitizeKm(value, MIN_SEGMENT_KM, MAX_SEGMENT_KM, DEFAULT_SEGMENT_KM)

        fun sanitizeCorridorPadKm(value: Double): Double =
            sanitizeKm(value, MIN_CORRIDOR_PAD_KM, MAX_CORRIDOR_PAD_KM, DEFAULT_CORRIDOR_PAD_KM)

        fun sanitizeTurnPadKm(value: Double): Double =
            sanitizeKm(value, MIN_TURN_PAD_KM, MAX_TURN_PAD_KM, DEFAULT_TURN_PAD_KM)

        fun sanitizeMaxZoom(value: Int): Int =
            if (value in MIN_MAX_ZOOM..MAX_MAX_ZOOM) value else DEFAULT_MAX_ZOOM

        fun parseSignature(raw: String?): OfflineTuning? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('|')
            if (parts.size != 4) return null
            val segment = parts[0].toDoubleOrNull() ?: return null
            val corridor = parts[1].toDoubleOrNull() ?: return null
            val turn = parts[2].toDoubleOrNull() ?: return null
            val zoom = parts[3].toIntOrNull() ?: return null
            if (segment !in MIN_SEGMENT_METERS..MAX_SEGMENT_METERS) return null
            if (corridor !in MIN_CORRIDOR_PAD_METERS..MAX_CORRIDOR_PAD_METERS) return null
            if (turn !in MIN_TURN_PAD_METERS..MAX_TURN_PAD_METERS) return null
            if (zoom !in MIN_MAX_ZOOM..MAX_MAX_ZOOM) return null
            return OfflineTuning(segment, corridor, turn, zoom)
        }

        private const val MIN_SEGMENT_METERS = 2_000.0
        private const val MAX_SEGMENT_METERS = 50_000.0
        private const val MIN_CORRIDOR_PAD_METERS = 500.0
        private const val MAX_CORRIDOR_PAD_METERS = 10_000.0
        private const val MIN_TURN_PAD_METERS = 250.0
        private const val MAX_TURN_PAD_METERS = 5_000.0

        private fun sanitizeKm(value: Double, min: Double, max: Double, default: Double): Double =
            if (!value.isFinite() || value <= 0.0) default else value.coerceIn(min, max)
    }
}
