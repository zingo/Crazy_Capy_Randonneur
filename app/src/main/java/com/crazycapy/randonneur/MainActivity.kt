/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import com.crazycapy.randonneur.gpx.TrackLoader
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import com.crazycapy.randonneur.ui.screen.NavigationMapScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContext = applicationContext
        RouteStore.init(appContext)
        RouteStore.loadSettings(appContext)
        loadResumeState(appContext)
        handleShareIntent(intent)

        setContent {
            MaterialTheme {
                NavigationMapScreen(
                    onImportRequest = { picker.launch(arrayOf("*/*")) },
                    onLoadSavedRoute = { id -> applySavedRoute(id) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadRoute(uri)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val stream = intent.extras?.get(Intent.EXTRA_STREAM) as? Uri
            if (stream != null) loadRoute(stream)
        }
    }

    private fun loadRoute(uri: Uri) {
        try {
            val track = TrackLoader.loadUri(this, uri)
            RideStore.track = track
            RideStore.status = "Route loaded: ${track.name}"
            RouteStore.saveTrack(this, track)
        } catch (e: Exception) {
            RideStore.track = null
            RideStore.status = "Couldn't parse route: ${e.message}"
            Toast.makeText(this, RideStore.status, Toast.LENGTH_LONG).show()
        }
    }

    private fun applySavedRoute(savedRouteId: String) {
        val track = RouteStore.loadTrack(this, savedRouteId) ?: return
        RideStore.track = track
        RideStore.status = "Route loaded: ${track.name}"
    }

    private fun loadResumeState(context: Context) {
        val last = RouteStore.loadLastRide(context) ?: return
        RideStore.resumeRouteName = last.routeName
        RideStore.resumeReversed = last.reverse
        RideStore.resumeMode = last.mode
        RideStore.resumeAlongM = last.alongM
        RideStore.resumeElapsedSec = last.elapsedSec
    }
}
