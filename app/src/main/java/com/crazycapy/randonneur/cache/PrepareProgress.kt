/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import kotlin.math.roundToInt

data class PrepareProgressInput(
    val routeName: String,
    val phase: PreparePhase,
    val paused: Boolean,
    val mapSkipped: Boolean,
    val previewsSkipped: Boolean,
    val mapPercent: Float?,
    val completedSegments: Int,
    val segmentCount: Int,
    val completedBytes: Long,
    val completedResources: Long,
    val requiredResources: Long,
    val previewProgress: Float?,
    val previewStatus: String?,
)

object PrepareProgress {

    fun percent(input: PrepareProgressInput): Int? = when (input.phase) {
        PreparePhase.MAP -> mapPercent(input)
        PreparePhase.PREVIEWS -> input.previewProgress?.let(::toPercent)
        PreparePhase.DONE -> 100
    }

    fun title(input: PrepareProgressInput): String = when {
        input.paused -> "Offline preparation paused"
        input.phase == PreparePhase.DONE -> "Offline preparation complete"
        else -> "Preparing ${input.routeName}"
    }

    fun summary(input: PrepareProgressInput): String {
        val line = when (input.phase) {
            PreparePhase.MAP -> mapSummary(input)
            PreparePhase.PREVIEWS -> previewSummary(input)
            PreparePhase.DONE -> {
                if (input.previewsSkipped) "Turn previews already cached" else "Route ready offline"
            }
        }
        return if (input.paused) "Paused · $line" else line
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${kb.toInt()} kB"
        return "%.1f MB".format(kb / 1024.0)
    }

    private fun mapPercent(input: PrepareProgressInput): Int? {
        if (input.mapSkipped) return null
        if (input.segmentCount > 0) {
            val current = (input.mapPercent ?: 0f).coerceIn(0f, 1f)
            val finished = input.completedSegments.coerceIn(0, input.segmentCount)
            return toPercent((finished + current) / input.segmentCount)
        }
        return input.mapPercent?.let(::toPercent)
    }

    private fun mapSummary(input: PrepareProgressInput): String {
        if (input.mapSkipped) return "Map tiles already downloaded"
        val parts = mutableListOf("Map tiles")
        if (input.segmentCount > 0) {
            val finished = input.completedSegments.coerceIn(0, input.segmentCount)
            parts.add("segment $finished/${input.segmentCount}")
        }
        if (input.requiredResources > 0) {
            parts.add("${input.completedResources}/${input.requiredResources} tiles")
        }
        parts.add(formatBytes(input.completedBytes))
        percent(input)?.let { parts.add("$it%") }
        return parts.joinToString(" · ")
    }

    private fun previewSummary(input: PrepareProgressInput): String {
        val status = input.previewStatus?.takeIf { it.isNotBlank() }
        if (status != null) return status
        val percent = percent(input)
        return if (percent == null) "Turn previews" else "Turn previews · $percent%"
    }

    private fun toPercent(fraction: Float): Int =
        (fraction.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)
}
