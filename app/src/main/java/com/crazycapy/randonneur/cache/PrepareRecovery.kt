/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import android.content.Context
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackHash
import com.crazycapy.randonneur.state.RouteStore
import java.io.File

data class PrepareRecoveryState(
    val routeId: String,
    val trackHash: String,
    val routeName: String,
    val strategy: OfflineDetailStrategy,
    val bothThemes: Boolean,
    val tuning: OfflineTuning = OfflineTuning.DEFAULT,
    val targetStyle: String? = null,
) {
    fun encode(): String = listOf(
        "version=$VERSION",
        "routeId=$routeId",
        "trackHash=$trackHash",
        "routeName=${routeName.replace('\n', ' ')}",
        "strategy=${strategy.name}",
        "bothThemes=$bothThemes",
        "tuning=${tuning.signature}",
        "targetStyle=${targetStyle.orEmpty()}",
    ).joinToString("\n")

    fun matches(track: Track): Boolean =
        routeId == RouteIdentity.of(track) && trackHash == TrackHash.of(track)

    companion object {
        private const val VERSION = 1

        fun decode(raw: String?): PrepareRecoveryState? {
            if (raw.isNullOrBlank()) return null
            val values = raw.lineSequence().mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.substring(0, split) to line.substring(split + 1)
            }.toMap()
            if (values["version"]?.toIntOrNull() != VERSION) return null
            val routeId = values["routeId"]?.takeIf { it.isNotBlank() } ?: return null
            val trackHash = values["trackHash"]?.takeIf { it.isNotBlank() } ?: return null
            val routeName = values["routeName"] ?: return null
            val strategy = OfflineDetailStrategy.entries.firstOrNull {
                it.name == values["strategy"]
            } ?: return null
            val bothThemes = values["bothThemes"]?.toBooleanStrictOrNull() ?: return null
            val tuning = OfflineTuning.parseSignature(values["tuning"]) ?: OfflineTuning.DEFAULT
            val targetStyle = values["targetStyle"]?.takeIf { it.isNotBlank() }
            return PrepareRecoveryState(
                routeId,
                trackHash,
                routeName,
                strategy,
                bothThemes,
                tuning,
                targetStyle,
            )
        }
    }
}

object PrepareRecovery {

    private const val FILE_NAME = "prepare-recovery.txt"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun save(
        context: Context,
        track: Track,
        strategy: OfflineDetailStrategy,
        bothThemes: Boolean,
        tuning: OfflineTuning = OfflineTuning.DEFAULT,
        targetStyle: String? = null,
    ): PrepareRecoveryState? {
        val saved = runCatching { RouteStore.saveTrack(context, track) }.getOrNull() ?: return null
        val state = PrepareRecoveryState(
            routeId = saved.id,
            trackHash = TrackHash.of(track),
            routeName = track.name,
            strategy = strategy,
            bothThemes = bothThemes,
            tuning = tuning,
            targetStyle = targetStyle,
        )
        runCatching {
            val target = file(context)
            val temp = File(target.parentFile, "$FILE_NAME.tmp")
            temp.writeText(state.encode())
            if (!temp.renameTo(target)) {
                target.writeText(state.encode())
                temp.delete()
            }
        }
        return state
    }

    fun load(context: Context): PrepareRecoveryState? = runCatching {
        val f = file(context)
        if (!f.exists()) null else PrepareRecoveryState.decode(f.readText())
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    fun resolveTrack(context: Context, state: PrepareRecoveryState): Track? =
        RouteStore.loadTrack(context, state.routeId)?.takeIf { state.matches(it) }
}
