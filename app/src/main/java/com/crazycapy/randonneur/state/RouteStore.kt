/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RouteStore — on-device route library + settings + last-ride persistence
 *
 *   saveTrack(track)  -> GPX file in app-private dir
 *   listSaved()       -> GPX filenames with metadata
 *   loadTrack(uri)    -> Track object
 *   Settings: beepVolume / navVolume / lastRideState -> JSON preferences
 *
 * Last-ride resume: saves the active track URI + progress so the next
 * cold start can offer "Continue last ride".
 */
package com.crazycapy.randonneur.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crazycapy.randonneur.cache.OfflineDetailStrategy
import com.crazycapy.randonneur.cache.OfflineThemeMode
import com.crazycapy.randonneur.cache.RouteIdentity
import com.crazycapy.randonneur.gpx.GpxParser
import com.crazycapy.randonneur.gpx.GpxWriter
import com.crazycapy.randonneur.gpx.Track
import java.io.File

/** A route saved in the app's private storage, easy to re-load later. */
data class SavedRoute(
    val id: String,
    val name: String,
    val savedAtMs: Long,
    val lengthM: Double,
)

/** The interrupted ride we can offer to resume next launch. */
data class LastRide(
    val routeName: String,
    val reverse: Boolean,
    val alongM: Double,
    val elapsedSec: Long,
    val mode: RideMode,
)

/**
 * Local persistence for the saved-routes library, the resume point, and the
 * configurable behavior toggles. Plain files (one GPX per route plus small text
 * indexes) so no Room/DataStore dependency is needed. All IO is best-effort.
 */
object RouteStore {

    // 10-meter minimum; shorter = at the start, not worth resuming
    private const val LAST_RIDE_MIN_RESUME_M = 10.0

    var routes: List<SavedRoute> by mutableStateOf(emptyList())

    private var dir: File? = null

    private fun dataDir(context: Context): File =
        dir ?: File(context.filesDir, "routes").apply { mkdirs() }.also { dir = it }

    private fun indexFile(context: Context): File = File(dataDir(context), "index.txt")
    private fun settingsFile(context: Context): File = File(dataDir(context), "settings.txt")
    private fun lastRideFile(context: Context): File = File(dataDir(context), "lastride.txt")

    fun init(context: Context) {
        if (dir != null) return
        reload(context)
    }

    /** (Re)read the index into [routes]. */
    fun reload(context: Context) {
        routes = runCatching {
            val f = indexFile(context)
            if (!f.exists()) emptyList()
            else f.readLines().mapNotNull(::parseIndexLine).sortedByDescending { it.savedAtMs }
        }.getOrDefault(emptyList())
    }

    private fun parseIndexLine(line: String): SavedRoute? {
        val parts = line.split("\t")
        if (parts.size < 4) return null
        val savedAt = parts[2].toLongOrNull() ?: return null
        val length = parts[3].toDoubleOrNull() ?: 0.0
        return SavedRoute(parts[0], parts[1], savedAt, length)
    }

    private fun writeIndex(context: Context) {
        runCatching {
            indexFile(context).writeText(routes.joinToString("\n") {
                listOf(it.id, it.name, it.savedAtMs.toString(), it.lengthM.toString()).joinToString("\t")
            })
        }
    }

    /** Store a route (by name, deduped) and return its [SavedRoute]. */
    fun saveTrack(context: Context, track: Track): SavedRoute {
        val id = track.storageId?.takeIf { it.isNotBlank() } ?: routeId(track.name)
        if (track.lengthMeters < LAST_RIDE_MIN_RESUME_M) {
            return routes.firstOrNull { it.id == id }
                ?: SavedRoute(id, track.name, System.currentTimeMillis(), track.lengthMeters)
        }
        val existing = routes.firstOrNull { it.id == id }
        val sr = existing ?: SavedRoute(
            id = id,
            name = track.name,
            savedAtMs = System.currentTimeMillis(),
            lengthM = track.lengthMeters,
        )
        val gpx = java.io.StringWriter()
        runCatching { GpxWriter.write(track, gpx) }
        runCatching { File(dataDir(context), "${sr.id}.gpx").writeText(gpx.toString()) }
        if (existing == null) {
            routes = routes + sr
            writeIndex(context)
        }
        return sr
    }

    fun fileFor(context: Context, id: String): File? =
        File(dataDir(context), "$id.gpx").takeIf { it.exists() }

    fun renameRoute(context: Context, id: String, newName: String) {
        val name = newName.trim().ifBlank { return }
        val existing = routes.firstOrNull { it.id == id } ?: return
        routes = routes.map { if (it.id == id) it.copy(name = name) else it }
        writeIndex(context)
        val track = loadTrack(context, id) ?: return
        val renamed = Track(name, track.points, track.waypoints, track.storageId ?: id)
        val gpx = java.io.StringWriter()
        runCatching { GpxWriter.write(renamed, gpx) }
        runCatching { File(dataDir(context), "$id.gpx").writeText(gpx.toString()) }
    }

    fun deleteRoute(context: Context, id: String) {
        runCatching { File(dataDir(context), "$id.gpx").delete() }
        routes = routes.filterNot { it.id == id }
        writeIndex(context)
    }

    /** Re-load a saved route from disk. */
    fun loadTrack(context: Context, id: String): Track? =
        runCatching {
            val f = File(dataDir(context), "$id.gpx")
            if (!f.exists()) return null
            val name = routes.firstOrNull { it.id == id }?.name ?: id
            f.inputStream().use { GpxParser().parse(name, it) }.withStorageId(id)
        }.getOrNull()

    /** Stable storage id derived from the route name (matches the save key). */
    internal fun routeId(name: String): String = RouteIdentity.legacyNameId(name)

    // ---- Configurable toggles ----

    fun loadSettings(context: Context) {
        val map = runCatching {
            val f = settingsFile(context)
            if (!f.exists()) emptyMap()
            else f.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
            }.toMap()
        }.getOrDefault(emptyMap())

        map["nextTurnPopup"]?.toBooleanStrictOrNull()?.let { RideStore.nextTurnPopupEnabled = it }
        map["notification"]?.toBooleanStrictOrNull()?.let { RideStore.notificationEnabled = it }
        map["duckMusic"]?.toBooleanStrictOrNull()?.let { RideStore.duckMusicEnabled = it }
        map["beepVolume"]?.toIntOrNull()?.let { RideStore.beepVolume = it }
        map["navVolume"]?.toIntOrNull()?.let { RideStore.navVolume = it }
        map["beeps"]?.toBooleanStrictOrNull()?.let { RideStore.beepVolume = if (it) RideStore.beepVolume else 0 }
        map["darkMap"]?.toBooleanStrictOrNull()?.let { RideStore.darkMap = it }
        map["precacheEnabled"]?.toBooleanStrictOrNull()?.let { RideStore.precacheEnabled = it }
        map["offlineDetail"]?.let { value ->
            OfflineDetailStrategy.values().firstOrNull { it.name == value }?.let {
                RideStore.offlineDetailStrategy = it
            }
        }
        map["offlineThemes"]?.let { value ->
            OfflineThemeMode.values().firstOrNull { it.name == value }?.let {
                RideStore.offlineThemeMode = it
            }
        }
        map["offlinePrepareBothThemes"]?.toBooleanStrictOrNull()?.let { RideStore.offlinePrepareBothThemes = it }
        map["offlineStorageCapMb"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { RideStore.offlineStorageCapMb = it }
        map["offlineExpiryDays"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { RideStore.offlineExpiryDays = it }
        map["offlineAutoDeleteExpired"]?.toBooleanStrictOrNull()?.let {
            RideStore.offlineAutoDeleteExpired = it
        }
        map["ghostTimeScale"]?.toDoubleOrNull()?.let { RideStore.ghostTimeScale = it }
        map["ghostSpeedKmh"]?.toDoubleOrNull()?.let { RideStore.ghostSpeedKmh = it }
        map["radarSim"]?.toBooleanStrictOrNull()?.let { RideStore.radarSimEnabled = it }
        map["radarIntegration"]?.toBooleanStrictOrNull()?.let { RideStore.radarIntegrationEnabled = it }
        map["showGhostControls"]?.toBooleanStrictOrNull()?.let { RideStore.showGhostControls = it }
        map["rwgpsUrl"]?.takeIf { it.isNotBlank() }?.let { RideStore.rwgpsUrl = it }
        map["gpsIntervalSec"]?.toIntOrNull()?.takeIf { it in listOf(1, 3, 5) }?.let { RideStore.gpsIntervalSec = it }
    }

    fun saveSettings(context: Context) {
        val lines = listOf(
            "nextTurnPopup=${RideStore.nextTurnPopupEnabled}",
            "notification=${RideStore.notificationEnabled}",
            "duckMusic=${RideStore.duckMusicEnabled}",
            "beepVolume=${RideStore.beepVolume}",
            "navVolume=${RideStore.navVolume}",
            "darkMap=${RideStore.darkMap}",
            "precacheEnabled=${RideStore.precacheEnabled}",
            "offlineDetail=${RideStore.offlineDetailStrategy.name}",
            "offlineThemes=${RideStore.offlineThemeMode.name}",
            "offlinePrepareBothThemes=${RideStore.offlinePrepareBothThemes}",
            "offlineStorageCapMb=${RideStore.offlineStorageCapMb}",
            "offlineExpiryDays=${RideStore.offlineExpiryDays}",
            "offlineAutoDeleteExpired=${RideStore.offlineAutoDeleteExpired}",
            "ghostTimeScale=${RideStore.ghostTimeScale}",
            "ghostSpeedKmh=${RideStore.ghostSpeedKmh}",
            "radarSim=${RideStore.radarSimEnabled}",
            "radarIntegration=${RideStore.radarIntegrationEnabled}",
            "showGhostControls=${RideStore.showGhostControls}",
            "rwgpsUrl=${RideStore.rwgpsUrl}",
            "gpsIntervalSec=${RideStore.gpsIntervalSec}",
        )
        runCatching { settingsFile(context).writeText(lines.joinToString("\n")) }
    }

    // ---- Resume point ----

    fun loadLastRide(context: Context): LastRide? = runCatching {
        val f = lastRideFile(context)
        if (!f.exists()) return null
        val parts = f.readText().trim().split("\t")
        if (parts.size < 4) return null
        val reverse = parts[1].toBooleanStrictOrNull() ?: false
        val along = parts[2].toDoubleOrNull() ?: return null
        val elapsed = parts[3].toLongOrNull() ?: 0L
        val mode = if (parts.size >= 5) {
            RideMode.entries.firstOrNull { it.name == parts[4] } ?: RideMode.GPS
        } else {
            RideMode.GPS
        }
        LastRide(parts[0], reverse, along, elapsed, mode)
    }.getOrNull()

    fun saveLastRide(
        context: Context,
        routeName: String,
        reverse: Boolean,
        alongM: Double,
        elapsedSec: Long,
        mode: RideMode,
    ) {
        runCatching {
            lastRideFile(context).writeText(
                listOf(routeName, reverse.toString(), alongM.toString(), elapsedSec.toString(), mode.name)
                    .joinToString("\t")
            )
        }
    }

    fun clearLastRide(context: Context) {
        runCatching { lastRideFile(context).delete() }
    }
}