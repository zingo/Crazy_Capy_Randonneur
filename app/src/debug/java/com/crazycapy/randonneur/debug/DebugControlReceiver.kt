/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * DebugControlReceiver — debug-build-only adb hook for automated testing.
 *
 * Loads a GPX from a file path and starts/stops a ride without touching the UI.
 * START_GHOST drives the built-in simulator; START_GPS leaves the ride to real
 * location fixes, so an adb mock-location provider (tools/mock-route.sh) can
 * drive it along the route. Declared only in app/src/debug/AndroidManifest.xml,
 * so it is absent from release builds.
 *
 * Ghost ride (simulated movement):
 *   adb shell am broadcast \
 *     -a com.crazycapy.randonneur.debug.START_GHOST \
 *     -n com.crazycapy.randonneur/.debug.DebugControlReceiver \
 *     --es route /data/local/tmp/ghost_ride.gpx \
 *     --ei timeScale 1 --ei speedKmh 28 --ez reverse false --ez darkMap true
 *
 * Real-GPS ride (drive with an adb mock location provider):
 *   adb shell am broadcast \
 *     -a com.crazycapy.randonneur.debug.START_GPS \
 *     -n com.crazycapy.randonneur/.debug.DebugControlReceiver \
 *     --es route /data/local/tmp/route.gpx --ez darkMap true
 *
 * Stop:
 *   adb shell am broadcast \
 *     -a com.crazycapy.randonneur.debug.STOP \
 *     -n com.crazycapy.randonneur/.debug.DebugControlReceiver
 */
package com.crazycapy.randonneur.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackLoader
import com.crazycapy.randonneur.service.NavigationService
import com.crazycapy.randonneur.state.RideStore
import java.io.File

class DebugControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                ACTION_START_GHOST -> startGhost(context, intent)
                ACTION_START_GPS -> startGps(context, intent)
                ACTION_STOP -> NavigationService.stop(context)
                else -> {}
            }
        } catch (e: Exception) {
            // Android 12+ blocks starting a service from the background; the
            // script brings the app to the foreground first, but never crash.
            Log.e(TAG, "Action ${intent.action} failed", e)
        }
    }

    /** Loads the route named by [EXTRA_ROUTE], restricted to the adb staging dir. */
    private fun loadRoute(intent: Intent): Track? {
        val path = intent.getStringExtra(EXTRA_ROUTE)
        if (path.isNullOrBlank()) {
            Log.e(TAG, "No route path supplied")
            return null
        }
        // Only read routes from the adb staging directory (the script pushes
        // there); refuse anything else so an exported broadcast can't be used
        // to read arbitrary files.
        val file = try {
            File(path).canonicalFile
        } catch (e: Exception) {
            Log.e(TAG, "Bad route path: $path", e)
            return null
        }
        val allowed = File("/data/local/tmp").canonicalPath + File.separator
        if (!file.path.startsWith(allowed)) {
            Log.e(TAG, "Refusing route outside $allowed: ${file.path}")
            return null
        }
        if (!file.exists()) {
            Log.e(TAG, "Route file not found: $path")
            return null
        }
        return try {
            TrackLoader.loadString(file.readText(), file.nameWithoutExtension)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load route", e)
            null
        }
    }

    /** Seeds the resume/deviation fields shared by both ride modes. */
    private fun seedRide(intent: Intent, track: Track) {
        RideStore.track = track
        RideStore.status = "Route loaded: ${track.name}"
        RideStore.reverse = intent.getBooleanExtra(EXTRA_REVERSE, false)
        RideStore.darkMap = intent.getBooleanExtra(EXTRA_DARK_MAP, true)
        val startM = intent.numberExtra(EXTRA_START_M, 0.0)
        RideStore.resumeAlongM = startM.takeIf { it > 0.0 }
        RideStore.resumeElapsedSec = null
        RideStore.resumeRouteName = null
    }

    private fun startGhost(context: Context, intent: Intent) {
        val track = loadRoute(intent) ?: return
        seedRide(intent, track)
        RideStore.ghostTimeScale = intent.numberExtra(EXTRA_TIME_SCALE, 1.0)
        RideStore.ghostSpeedKmh = intent.numberExtra(EXTRA_SPEED_KMH, 28.0)
        NavigationService.startGhost(context)
        Log.i(TAG, "Started ghost ride on ${track.name} " +
            "(x${RideStore.ghostTimeScale}, ${RideStore.ghostSpeedKmh} km/h, " +
            "reverse=${RideStore.reverse})")
    }

    private fun startGps(context: Context, intent: Intent) {
        val track = loadRoute(intent) ?: return
        seedRide(intent, track)
        NavigationService.startGps(context)
        Log.i(TAG, "Started GPS ride on ${track.name} " +
            "(resume=${RideStore.resumeAlongM ?: 0.0}, reverse=${RideStore.reverse})")
    }

    /** Reads a numeric extra regardless of whether it was sent as int/long/float/double/string. */
    private fun Intent.numberExtra(key: String, default: Double): Double =
        when (val v = extras?.get(key)) {
            is Double -> v
            is Float -> v.toDouble()
            is Int -> v.toDouble()
            is Long -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: default
            else -> default
        }

    companion object {
        private const val TAG = "DebugControlReceiver"
        const val ACTION_START_GHOST = "com.crazycapy.randonneur.debug.START_GHOST"
        const val ACTION_START_GPS = "com.crazycapy.randonneur.debug.START_GPS"
        const val ACTION_STOP = "com.crazycapy.randonneur.debug.STOP"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_TIME_SCALE = "timeScale"
        const val EXTRA_SPEED_KMH = "speedKmh"
        const val EXTRA_REVERSE = "reverse"
        const val EXTRA_DARK_MAP = "darkMap"
        const val EXTRA_START_M = "startM"
    }
}
