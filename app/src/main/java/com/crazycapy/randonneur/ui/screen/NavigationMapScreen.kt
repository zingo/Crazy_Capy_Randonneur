/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.crazycapy.randonneur.BuildConfig
import com.crazycapy.randonneur.DEFAULT_LAT
import com.crazycapy.randonneur.DEFAULT_LON
import com.crazycapy.randonneur.TAG
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.nav.TurnFinder
import com.crazycapy.randonneur.service.NavigationService
import com.crazycapy.randonneur.state.RideMode
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import com.crazycapy.randonneur.ui.OffRouteAck
import com.crazycapy.randonneur.ui.TrainingHud
import com.crazycapy.randonneur.ui.components.GhostControls
import com.crazycapy.randonneur.ui.components.ResumeBanner
import com.crazycapy.randonneur.ui.components.showResumeOffer
import com.crazycapy.randonneur.ui.dialogs.LicensesDialog
import com.crazycapy.randonneur.ui.dialogs.RoutesDialog
import com.crazycapy.randonneur.ui.dialogs.SettingsDialog
import com.crazycapy.randonneur.ui.dialogs.StartRideDialog
import com.crazycapy.randonneur.ui.helpers.centerOnLastKnown
import com.crazycapy.randonneur.ui.helpers.centerOnRider
import com.crazycapy.randonneur.ui.helpers.fitRoute
import com.crazycapy.randonneur.ui.helpers.loadMapStyle
import com.crazycapy.randonneur.ui.helpers.moveAndCenter
import com.crazycapy.randonneur.ui.helpers.refreshRoute
import com.crazycapy.randonneur.ui.helpers.updateIdleDot
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlinx.coroutines.delay

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  NavigationMapScreen                                                    │
 * │                                                                         │
 * │  The single full-screen composable that the Activity hosts.  Owns the   │
 * │  MapView lifecycle, the Compose overlay stack (HUD / dialogs / bottom   │
 * │  bar), and the reactive subscriptions that sync RideStore state with   │
 * │  the map (style loading, route redraw, rider follow, idle dot).         │
 * │                                                                         │
 * │  SCREEN LAYOUT                                                          │
 * │                                                                         │
 * │  ┌──────────────────────────────────────────────────────────┐           │
 * │  │  ┌────┬───┐  ┌──────────┐   ⚙ (Settings FAB top-right)  │           │
 * │  │  │Spd │Cov│  │  Turn    │                               │           │
 * │  │  │ Avg│Lef│  │ Preview  │  ← TrainingHud (top‑left)     │           │
 * │  │  └────┴───┘  └──────────┘                               │           │
 * │  │                                                          │           │
 * │  │              MapLibre MapView (full area beneath)        │           │
 * │  │                                                          │           │
 * │  │  [−]  [+]  ☀/🌙  ← zoom + dark toggle FABs (right)     │           │
 * │  │                                                          │           │
 * │  ├──────────────────────────────────────────────────────────┤           │
 * │  │  status text  [Routes] [Navigate/Stop] [↕] [Slower..]  │ ← bottom  │
 * │  │  [Resume banner if applicable]                          │ ← bar     │
 * │  └──────────────────────────────────────────────────────────┘           │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
@Composable
internal fun NavigationMapScreen(
    onImportRequest: () -> Unit,
    onLoadSavedRoute: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { getMapAsync { map = it } }
    }
    var track by remember { mutableStateOf(RideStore.track) }
    var status by remember { mutableStateOf(RideStore.status) }
    var idleLat by remember { mutableStateOf<Double?>(null) }
    var idleLon by remember { mutableStateOf<Double?>(null) }
    var idleCenteredOnce by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showRoutes by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<RideMode?>(null) }
    var askPrecacheFor by remember { mutableStateOf<Track?>(null) }

    // Poll RideStore for text changes while the screen is visible.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            if (!RideStore.mapVisible) continue
            if (track !== RideStore.track) track = RideStore.track
            if (status != RideStore.status) status = RideStore.status
        }
    }

    // Track lifecycle events → mapView lifecycle + mapVisible flag.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> RideStore.mapVisible = event == Lifecycle.Event.ON_RESUME }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); RideStore.mapVisible = false }
    }
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(null)
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); mapView.onDestroy() }
    }

    // (Re)load the map style when dark/light toggle changes.
    LaunchedEffect(map, RideStore.darkMap) {
        val m = map ?: return@LaunchedEffect
        loadMapStyle(m, RideStore.darkMap) { style ->
            refreshRoute(m, track)
            if (RideStore.active) centerOnRider(m)
            else if (track != null) fitRoute(m, track)
            else if (idleLat != null && idleLon != null) {
                Log.d(TAG, "Centering on self position @ $idleLat,$idleLon")
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(idleLat!!, idleLon!!), 15.0))
            } else {
                m.cameraPosition = CameraPosition.Builder().target(LatLng(DEFAULT_LAT, DEFAULT_LON)).zoom(12.0).build()
            }
            if (!RideStore.active) updateIdleDot(m, idleLat, idleLon, show = !RideStore.active)
        }
    }

    // Redraw route polyline when track changes.
    LaunchedEffect(map, track) {
        val m = map ?: return@LaunchedEffect
        if (!RideStore.mapVisible) return@LaunchedEffect
        refreshRoute(m, track)
        if (track != null && !RideStore.active) fitRoute(m, track)
    }

    // Follow the rider while the screen is on.
    LaunchedEffect(RideStore.lat, RideStore.lon, RideStore.bearing, RideStore.mapVisible) {
        if (!RideStore.mapVisible) return@LaunchedEffect
        val lat = RideStore.lat ?: return@LaunchedEffect
        val lon = RideStore.lon ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        moveAndCenter(m, mapView, lat, lon, RideStore.bearing)
    }

    // Idle-position dot: passive location listener.
    DisposableEffect(context, RideStore.mapVisible) {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val hasLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun centerToSelf() {
            if (idleCenteredOnce || !RideStore.mapVisible || RideStore.active || RideStore.track != null) return
            val m = map ?: return; val la = idleLat ?: return; val lo = idleLon ?: return
            idleCenteredOnce = true
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(la, lo), 15.0))
        }
        if (hasLoc && idleLat == null && idleLon == null) {
            val last = listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER,
            ).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            if (last != null) { idleLat = last.latitude; idleLon = last.longitude; centerToSelf() }
            else if (RideStore.mapVisible) {
                runCatching { lm.requestSingleUpdate(android.location.LocationManager.GPS_PROVIDER, { idleLat = it.latitude; idleLon = it.longitude; centerToSelf() }, android.os.Looper.getMainLooper()) }
            }
        }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) { idleLat = location.latitude; idleLon = location.longitude }
        }
        if (RideStore.mapVisible) runCatching { lm.requestLocationUpdates(android.location.LocationManager.PASSIVE_PROVIDER, 5000L, 0f, listener, android.os.Looper.getMainLooper()) }
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }

    // Redraw idle dot.
    LaunchedEffect(map, RideStore.active, RideStore.darkMap, idleLat, idleLon, RideStore.mapVisible) {
        val m = map ?: return@LaunchedEffect
        if (RideStore.active || !RideStore.mapVisible) { updateIdleDot(m, null, null, show = false); return@LaunchedEffect }
        if (idleLat == null || idleLon == null) return@LaunchedEffect
        updateIdleDot(m, idleLat, idleLon, show = true)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            when (pendingStart) {
                RideMode.GHOST -> NavigationService.startGhost(context)
                else -> { NavigationService.startGps(context); map?.centerOnLastKnown(context) }
            }
        } else status = "Location needed to start navigation"
        pendingStart = null
    }

    val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    Scaffold(
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) }, modifier = Modifier.size(44.dp), containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom in")
                }
                FloatingActionButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) }, modifier = Modifier.size(44.dp), containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Icon(Icons.Filled.Remove, contentDescription = "Zoom out")
                }
                FloatingActionButton(onClick = { RideStore.darkMap = !RideStore.darkMap }, containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Icon(if (RideStore.darkMap) Icons.Filled.BrightnessLow else Icons.Filled.BrightnessHigh, contentDescription = if (RideStore.darkMap) "Switch to light map" else "Switch to dark map")
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                    Text(
                        when { RideStore.active -> status ?: "Riding…"; track != null -> status ?: "Route ready"; else -> status ?: "Import a GPX or share one to this app" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val cacheStatus = RouteCache.status
                    if (cacheStatus != null) Text(cacheStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (RideStore.active && RideStore.mode == RideMode.GHOST) GhostControls()
                    if (!RideStore.active && showResumeOffer(track)) {
                        ResumeBanner(
                            onResume = {
                                RideStore.reverse = RideStore.resumeReversed
                                val resumeMode = if (RideStore.resumeMode == RideMode.GHOST) RideMode.GHOST else RideMode.GPS
                                if (resumeMode == RideMode.GHOST) RideStore.ghostTimeScale = 1.0
                                if (hasLocation) { if (resumeMode == RideMode.GHOST) NavigationService.startGhost(context) else NavigationService.startGps(context) }
                                else { pendingStart = resumeMode; permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                            },
                            onDismiss = { RouteStore.clearLastRide(context); RideStore.resumeAlongM = null; RideStore.resumeElapsedSec = null; RideStore.resumeRouteName = null },
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = if (RideStore.active) 4.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        if (!RideStore.active) {
                            OutlinedButton(onClick = { showRoutes = true }, Modifier.weight(1f)) { Text("Routes") }
                            if (track != null) OutlinedButton(onClick = { pendingStart = RideMode.GPS }, Modifier.weight(1f)) { Text("Navigate") }
                        } else {
                            OutlinedButton(onClick = { NavigationService.toggleReverse(context) }, Modifier.weight(1f)) { Text(if (RideStore.reverse) "Original dir" else "Reverse dir") }
                            Button(onClick = { NavigationService.stop(context) }, Modifier.weight(1f)) { Text("Stop") }
                        }
                    }
                }
            }
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            TrainingHud(Modifier.align(Alignment.TopStart).padding(top = 8.dp, start = 8.dp))
            if (RideStore.active && RideStore.offRouteActive && !RideStore.offRouteAcknowledged) {
                OffRouteAck(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }

    // Dialogs triggered by state flags.
    if (showSettings) SettingsDialog(onDismiss = { showSettings = false }, onShowLicenses = { showLicenses = true }, onShowRoutes = { showRoutes = true }, onStartGhost = { pendingStart = RideMode.GHOST }, ghostAvailable = track != null, versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE, buildType = BuildConfig.BUILD_TYPE)
    if (showLicenses) LicensesDialog(onDismiss = { showLicenses = false })
    if (showRoutes) RoutesDialog(context = context, onDismiss = { showRoutes = false }, onLoad = onLoadSavedRoute, onDelete = { RouteStore.deleteRoute(context, it) }, onImport = onImportRequest)
    pendingStart?.let { mode ->
        StartRideDialog(mode = mode, onDismiss = { pendingStart = null }, onStart = { reverseOn ->
            RideStore.reverse = reverseOn; RideStore.resumeAlongM = null; RideStore.resumeElapsedSec = null; RideStore.resumeRouteName = null
            if (mode == RideMode.GHOST) RideStore.ghostTimeScale = 1.0
            val start: () -> Unit = { if (mode == RideMode.GHOST) NavigationService.startGhost(context) else { NavigationService.startGps(context); map?.centerOnLastKnown(context) } }
            if (hasLocation) { start(); pendingStart = null } else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        })
    }

    // Pre-cache ask dialog.
    LaunchedEffect(RideStore.track) {
        val t = RideStore.track
        if (t != null && RideStore.precacheEnabled && !RideStore.active && !RouteCache.isCached(context, RouteStore.routeId(t.name), t, RideStore.darkMap)) {
            val turns = TurnFinder.find(t).size
            if (turns > 0) askPrecacheFor = t
        }
    }
    askPrecacheFor?.let { track ->
        val turns = TurnFinder.find(track).size
        val estMb = (turns * 4 * 50 + 512) / 1024
        AlertDialog(
            onDismissRequest = { askPrecacheFor = null },
            title = { Text("Pre-cache route?") },
            text = {
                Column {
                    Text("Render turn previews and warm tiles for ${track.name} ahead of time — saves battery on the ride.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("~${estMb} MB (best on Wi-Fi while charging) · ${turns} turns", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { askPrecacheFor = null; RouteCache.preCache(context, track) }) { Text("Pre-cache") } },
            dismissButton = { TextButton(onClick = { askPrecacheFor = null }) { Text("Not now") } },
        )
    }
}
