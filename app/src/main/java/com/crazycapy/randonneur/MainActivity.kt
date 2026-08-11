/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.TrackLoader
import com.crazycapy.randonneur.service.NavigationService
import com.crazycapy.randonneur.state.RideMode
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import com.crazycapy.randonneur.ui.OffRouteAck
import com.crazycapy.randonneur.ui.TrainingHud
import com.crazycapy.randonneur.voice.Phrases
import kotlin.math.roundToInt
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContext = applicationContext
        RouteStore.init(appContext)
        RouteStore.loadSettings(appContext)
        restoreLastRide(appContext)

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
            val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (stream != null) loadRoute(stream)
        }
    }

    private fun loadRoute(uri: Uri) {
        try {
            val track = TrackLoader.loadUri(this, uri)
            RideStore.track = track
            RideStore.status = "Route loaded: ${track.name}"
            // Copy into the app's saved-routes library so it's easy to reload later.
            RouteStore.saveTrack(this, track)
        } catch (e: Exception) {
            RideStore.track = null
            RideStore.status = "Couldn't parse route: ${e.message}"
            Toast.makeText(this, RideStore.status, Toast.LENGTH_LONG).show()
        }
    }

    /** Seed the resume prompt from the previously interrupted ride, if any. */
    private fun restoreLastRide(context: android.content.Context) {
        val last = RouteStore.loadLastRide(context) ?: return
        RideStore.resumeRouteName = last.routeName
        RideStore.resumeReversed = last.reverse
        RideStore.resumeMode = last.mode
        RideStore.resumeAlongM = last.alongM
        RideStore.resumeElapsedSec = last.elapsedSec
    }

    /** Load a saved library route and switch the current route to it. */
    private fun applySavedRoute(id: String) {
        val track = RouteStore.loadTrack(this, id) ?: return
        RideStore.track = track
        RideStore.status = "Route loaded: ${track.name}"
    }
}

private const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
private const val DEFAULT_LAT = 59.329
private const val DEFAULT_LON = 18.069
private const val TAG = "CrazyCapyRandonneur"

@Composable
fun NavigationMapScreen(
    onImportRequest: () -> Unit,
    onLoadSavedRoute: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            getMapAsync { map = it }
        }
    }

    var track by remember { mutableStateOf(RideStore.track) }
    var status by remember { mutableStateOf(RideStore.status) }

    // Idle-position marker: last known / passively-updated position when not riding.
    var idleLat by remember { mutableStateOf<Double?>(null) }
    var idleLon by remember { mutableStateOf<Double?>(null) }

    // True once the map has centred on the user's position at startup (idle, no route).
    var idleCenteredOnce by remember { mutableStateOf(false) }

    // Settings overlay: {@link BuildConfig} exposes version/name for the About section.
    var showSettings by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }

    // Saved-routes picker + pre-start direction dialog.
    var showRoutes by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<RideMode?>(null) }

    // Poll the shared store for text changes. Only sync while the screen is on
    // and the app is in front, so a background ride doesn't cause recompositions.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(250)
            if (!RideStore.mapVisible) continue
            if (track !== RideStore.track) track = RideStore.track
            if (status != RideStore.status) status = RideStore.status
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            RideStore.mapVisible = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            RideStore.mapVisible = false
        }
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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // (Re)load the style when the map is ready or the dark/light toggle changes.
    LaunchedEffect(map, RideStore.darkMap) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(if (RideStore.darkMap) STYLE_DARK else STYLE_LIGHT) {
            // OpenFreeMap's dark style paints roads nearly black on black;
            // lift the road network so it's actually visible in dark mode.
            if (RideStore.darkMap) brightenDarkRoads(it)
            refreshRoute(m, track)
            // Style reload resets the camera: re-fit the route, else keep rider/default.
            if (RideStore.active) {
                m.centerOnRider()
            } else if (track != null) {
                m.fitRoute(track)
            } else if (idleLat != null && idleLon != null) {
                // No route loaded: show the current position instead of a hard-coded default.
                Log.d(TAG, "Centering on self position @ $idleLat,$idleLon")
                m.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(idleLat!!, idleLon!!), 15.0)
                )
            } else {
                m.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(DEFAULT_LAT, DEFAULT_LON))
                    .zoom(12.0)
                    .build()
            }
            // The new style wiped all sources: re-add the idle location dot.
            if (!RideStore.active) updateIdleDot(m, idleLat, idleLon, show = !RideStore.active)
        }
    }

    // Redraw the route polyline whenever a (new) track appears while visible.
    // On a fresh load, fit the camera to the whole route so the full line is visible.
    LaunchedEffect(map, track) {
        val m = map ?: return@LaunchedEffect
        if (!RideStore.mapVisible) return@LaunchedEffect
        refreshRoute(m, track)
        if (track != null && !RideStore.active) m.fitRoute(track)
    }

    // Follow the rider, but ONLY while the screen is on and the app is in front.
    LaunchedEffect(RideStore.lat, RideStore.lon, RideStore.bearing, RideStore.mapVisible) {
        if (!RideStore.mapVisible) return@LaunchedEffect
        val lat = RideStore.lat ?: return@LaunchedEffect
        val lon = RideStore.lon ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        moveAndCenter(m, mapView, lat, lon, RideStore.bearing)
    }

    // Idle-position dot. A PASSIVE listener receives fixes that other apps trigger,
    // so this costs ~nothing while the screen is on and you are not navigating.
    DisposableEffect(context, RideStore.mapVisible) {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Center on the current position once, when idle with no route loaded.
        fun centerToSelf() {
            if (idleCenteredOnce) return
            if (!RideStore.mapVisible || RideStore.active || RideStore.track != null) return
            val m = map ?: return
            val la = idleLat ?: return
            val lo = idleLon ?: return
            idleCenteredOnce = true
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(la, lo), 15.0))
        }

        if (hasLoc && idleLat == null && idleLon == null) {
            // Seed with the last known fix right away.
            val last = listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER,
            )
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null) {
                idleLat = last.latitude
                idleLon = last.longitude
                centerToSelf()
            } else if (RideStore.mapVisible) {
                // No cached fix: grab one quick GPS sample for the "where am I?" centre.
                try {
                    lm.requestSingleUpdate(
                        android.location.LocationManager.GPS_PROVIDER,
                        { location ->
                            idleLat = location.latitude
                            idleLon = location.longitude
                            centerToSelf()
                        },
                        android.os.Looper.getMainLooper(),
                    )
                } catch (ignored: Exception) {
                }
            }
        }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                idleLat = location.latitude
                idleLon = location.longitude
            }
        }
        if (RideStore.mapVisible) {
            runCatching {
                lm.requestLocationUpdates(
                    android.location.LocationManager.PASSIVE_PROVIDER,
                    5000L,
                    0f,
                    listener,
                    android.os.Looper.getMainLooper(),
                )
            }
        }
        onDispose {
            runCatching { lm.removeUpdates(listener) }
        }
    }

    // (Re)draw the idle dot whenever the position, ride state or style changes.
    LaunchedEffect(map, RideStore.active, RideStore.darkMap, idleLat, idleLon, RideStore.mapVisible) {
        val m = map ?: return@LaunchedEffect
        if (RideStore.active || !RideStore.mapVisible) {
            updateIdleDot(m, null, null, show = false)
            return@LaunchedEffect
        }
        if (idleLat == null || idleLon == null) return@LaunchedEffect
        updateIdleDot(m, idleLat, idleLon, show = true)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (pendingStart) {
                RideMode.GHOST -> NavigationService.startGhost(context)
                else -> {
                    NavigationService.startGps(context)
                    map?.centerOnLastKnown(context)
                }
            }
        } else {
            status = "Location needed to start navigation"
        }
        pendingStart = null
    }

    val hasLocation = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    Scaffold(
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                FloatingActionButton(
                    onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) },
                    modifier = Modifier.size(44.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Zoom in",
                    )
                }
                FloatingActionButton(
                    onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) },
                    modifier = Modifier.size(44.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Icon(
                        Icons.Filled.Remove,
                        contentDescription = "Zoom out",
                    )
                }
                FloatingActionButton(
                    onClick = { RideStore.darkMap = !RideStore.darkMap },
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Icon(
                        if (RideStore.darkMap) Icons.Filled.BrightnessLow else Icons.Filled.BrightnessHigh,
                        contentDescription = if (RideStore.darkMap) "Switch to light map" else "Switch to dark map",
                    )
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                    Text(
                        when {
                            RideStore.active ->
                                status ?: "Riding…"
                            track != null -> status ?: "Route ready"
                            else -> status ?: "Import a GPX or share one to this app"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (RideStore.active && RideStore.mode == RideMode.GHOST) {
                        GhostControls()
                    }
                    if (!RideStore.active && showResumeOffer(track)) {
                        ResumeBanner(
                            onResume = {
                                RideStore.reverse = RideStore.resumeReversed
                                val resumeMode = if (RideStore.resumeMode == RideMode.GHOST) RideMode.GHOST else RideMode.GPS
                                if (resumeMode == RideMode.GHOST) RideStore.ghostTimeScale = 1.0
                                if (hasLocation) {
                                    if (resumeMode == RideMode.GHOST) {
                                        NavigationService.startGhost(context)
                                    } else {
                                        NavigationService.startGps(context)
                                    }
                                } else {
                                    pendingStart = resumeMode
                                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            },
                            onDismiss = {
                                RouteStore.clearLastRide(context)
                                RideStore.resumeAlongM = null
                                RideStore.resumeElapsedSec = null
                                RideStore.resumeRouteName = null
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = if (RideStore.active) 4.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        if (!RideStore.active) {
                            OutlinedButton(onClick = { showRoutes = true }, Modifier.weight(1f)) { Text("Routes") }
                            if (track != null) {
                                OutlinedButton(
                                    onClick = { pendingStart = RideMode.GPS },
                                    Modifier.weight(1f),
                                ) { Text("Navigate") }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { NavigationService.toggleReverse(context) },
                                Modifier.weight(1f),
                            ) {
                                Text(if (RideStore.reverse) "Original dir" else "Reverse dir")
                            }
                            Button(
                                onClick = { NavigationService.stop(context) },
                                Modifier.weight(1f),
                            ) { Text("Stop") }
                        }
                    }
                }
            }
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            TrainingHud(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )
            if (RideStore.active && RideStore.offRouteActive && !RideStore.offRouteAcknowledged) {
                OffRouteAck(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                )
            }
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onShowLicenses = { showLicenses = true },
            onShowRoutes = { showRoutes = true },
            onStartGhost = { pendingStart = RideMode.GHOST },
            ghostAvailable = track != null,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            buildType = BuildConfig.BUILD_TYPE,
        )
    }
    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
    if (showRoutes) {
        RoutesDialog(
            onDismiss = { showRoutes = false },
            onLoad = onLoadSavedRoute,
            onDelete = { RouteStore.deleteRoute(context, it) },
            onImport = onImportRequest,
        )
    }
    pendingStart?.let { mode ->
        StartRideDialog(
            mode = mode,
            onDismiss = { pendingStart = null },
            onStart = { reverseOn ->
                RideStore.reverse = reverseOn
                // Plain start: do not resume an interrupted ride.
                RideStore.resumeAlongM = null
                RideStore.resumeElapsedSec = null
                RideStore.resumeRouteName = null
                // Ghost is a demo/test tool: always start it at real-time speed.
                if (mode == RideMode.GHOST) RideStore.ghostTimeScale = 1.0
                val start: () -> Unit = {
                    if (mode == RideMode.GHOST) NavigationService.startGhost(context)
                    else {
                        NavigationService.startGps(context)
                        map?.centerOnLastKnown(context)
                    }
                }
                if (hasLocation) {
                    start()
                    pendingStart = null
                } else {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
        )
    }
}

/** Whether the "continue last ride" banner should show for the loaded route. */
private fun showResumeOffer(track: Track?): Boolean =
    track != null &&
        RideStore.resumeAlongM != null &&
        RideStore.resumeRouteName != null &&
        RideStore.resumeRouteName == track.name

/** Resume banner: offers to continue the previously interrupted ride. */
@Composable
private fun ResumeBanner(onResume: () -> Unit, onDismiss: () -> Unit) {
    val track = RideStore.track
    var remaining = 0.0
    if (track != null && RideStore.resumeAlongM != null) {
        remaining = if (RideStore.resumeReversed) {
            RideStore.resumeAlongM!!
        } else {
            track.lengthMeters - RideStore.resumeAlongM!!
        }
    }
    val elapsed = RideStore.resumeElapsedSec
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Continue last ride", style = MaterialTheme.typography.titleSmall)
                val sub = buildString {
                    append(Phrases.formatDistance(remaining).replaceFirstChar { it.uppercase() })
                    append(" left")
                    if (elapsed != null && elapsed > 0) {
                        append(" · ")
                        val h = elapsed / 3600
                        val min = (elapsed % 3600) / 60
                        if (h > 0) append("${h}h ")
                        append("${min}min ridden")
                    }
                }
                Text(sub, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onResume) { Text("Resume") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** Ghost-ride live speed and time-scale controls (persisted). */
@Composable
private fun GhostControls() {
    val context = LocalContext.current
    val scale = RideStore.ghostTimeScale
    val speed = RideStore.ghostSpeedKmh
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = {
                RideStore.ghostTimeScale = (scale / 1.5).coerceIn(1.0, 600.0)
                RouteStore.saveSettings(context)
            },
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) { Text("Slower") }
        Text("x${scale.toInt()}", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = {
                RideStore.ghostTimeScale = (scale * 1.5).coerceIn(1.0, 600.0)
                RouteStore.saveSettings(context)
            },
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) { Text("Faster") }

        Text("|", style = MaterialTheme.typography.titleSmall)

        OutlinedButton(
            onClick = {
                RideStore.ghostSpeedKmh = (speed - 2.0).coerceIn(2.0, 60.0)
                RouteStore.saveSettings(context)
            },
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) { Text("Slow") }
        Text("${speed.toInt()} km/h", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = {
                RideStore.ghostSpeedKmh = (speed + 2.0).coerceIn(2.0, 60.0)
                RouteStore.saveSettings(context)
            },
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) { Text("Fast") }
    }
}

/** Pre-start dialog: choose the riding direction before launching a ride. */
@Composable
private fun StartRideDialog(
    mode: RideMode,
    onDismiss: () -> Unit,
    onStart: (reverse: Boolean) -> Unit,
) {
    var reverse by remember { mutableStateOf(RideStore.reverse) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == RideMode.GHOST) "Start ghost ride" else "Start navigation") },
        text = {
            Column {
                Text(
                    if (reverse) "Riding the route in reverse" else "Riding the route as recorded",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Switch(
                    checked = reverse,
                    onCheckedChange = { reverse = it },
                    modifier = Modifier.align(Alignment.Start),
                )
                Text(
                    "Reverse direction",
                    modifier = Modifier.align(Alignment.Start),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(reverse) }) { Text(if (mode == RideMode.GHOST) "Start ghost" else "Navigate") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Saved-routes library: reload or delete previously imported routes. */
@Composable
private fun RoutesDialog(
    onDismiss: () -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved routes") },
        text = {
            if (RouteStore.routes.isEmpty()) {
                Text("No routes saved yet. Import a GPX to keep it here.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column {
                    RouteStore.routes.forEach { route ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(route.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    Phrases.formatDistance(route.lengthM),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { onLoad(route.id); onDismiss() }) { Text("Load") }
                            TextButton(onClick = { onDelete(route.id) }) { Text("Delete") }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(); onDismiss() }) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** Settings dialog: app version (About) plus a link to the in-app license viewer. */
@Composable
private fun SettingsDialog(
    onDismiss: () -> Unit,
    onShowLicenses: () -> Unit,
    onShowRoutes: () -> Unit,
    onStartGhost: () -> Unit,
    ghostAvailable: Boolean,
    versionName: String,
    versionCode: Int,
    buildType: String,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Ride options", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingSwitch(
                    checked = RideStore.nextTurnPopupEnabled,
                    onCheckedChange = {
                        RideStore.nextTurnPopupEnabled = it
                        RouteStore.saveSettings(context)
                    },
                    title = "Next-turn popup",
                    subtitle = "Corner popup with arrow and route preview",
                )
                SettingSwitch(
                    checked = RideStore.notificationEnabled,
                    onCheckedChange = {
                        RideStore.notificationEnabled = it
                        RouteStore.saveSettings(context)
                    },
                    title = "Live notification",
                    subtitle = "Refresh the notification every second (saves battery when off)",
                )
                SettingSwitch(
                    checked = RideStore.duckMusicEnabled,
                    onCheckedChange = {
                        RideStore.duckMusicEnabled = it
                        RouteStore.saveSettings(context)
                    },
                    title = "Pause audio while speaking",
                    subtitle = "Other apps pause during guidance announcements",
                )
                SettingSlider(
                    value = RideStore.beepVolume,
                    onValueChange = {
                        RideStore.beepVolume = it
                        RouteStore.saveSettings(context)
                    },
                    title = "Turn beeps",
                    subtitle = "Left vs right beeps that shorten as the turn nears",
                )
                SettingSlider(
                    value = RideStore.navVolume,
                    onValueChange = {
                        RideStore.navVolume = it
                        RouteStore.saveSettings(context)
                    },
                    title = "Navigation voice",
                    subtitle = "Spoken turn guidance",
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved routes", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onShowRoutes(); onDismiss() }) { Text("Manage") }
                }
                Spacer(Modifier.height(12.dp))
                Text("Demo", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ghost ride", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (ghostAvailable) "Simulated ride on the loaded route (real time)"
                            else "Load a route first",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        enabled = ghostAvailable,
                        onClick = { onStartGhost(); onDismiss() },
                    ) { Text("Start") }
                }
                Spacer(Modifier.height(12.dp))
                Text("About", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                AboutRow("App name", "Crazy Capy Randonneur")
                AboutRow("Version", versionName)
                AboutRow("Version code", versionCode.toString())
                AboutRow("Build type", buildType)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Map data © OpenStreetMap contributors. Tiles by OpenFreeMap.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onShowLicenses) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.weight(1f))
                Text("Open-source licenses")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun SettingSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Volume slider where 0 is "off" and 100 is full device volume. */
@Composable
private fun SettingSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    title: String,
    subtitle: String,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (value <= 0) "Off" else "$value%",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..100f,
            steps = 19,
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Full-screen scrollable viewer for the bundled third-party notices (and our license). */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember(context) {
        runCatching {
            context.assets.open("notices/THIRD_PARTY_NOTICES.md")
                .bufferedReader()
                .use { it.readText() }
        }.getOrDefault("Third-party notices could not be loaded.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licenses") },
        text = {
            Text(
                notices,
                modifier = Modifier.verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Lighten the road network of OpenFreeMap's dark style for better contrast on OLED. */
private fun brightenDarkRoads(style: Style) {
    val overrides = mapOf(
        // Major roads (primary/secondary/tertiary/trunk) and their casing outline.
        "highway_major_inner" to 0xFF838383.toInt(),
        "highway_major_casing" to 0xFF4A4A4A.toInt(),
        "highway_major_subtle" to 0xFF5A5A5A.toInt(),
        // Motorways and their casing.
        "highway_motorway_inner" to 0xFF8E8E8E.toInt(),
        "highway_motorway_casing" to 0xFF505050.toInt(),
        "highway_motorway_subtle" to 0xFF5A5A5A.toInt(),
        // Minor roads, service roads, tracks.
        "highway_minor" to 0xFF6E6E6E.toInt(),
        // Foot/bike paths (slightly lifted, still distinct from roads).
        "highway_path" to 0xFF3A3A3C.toInt(),
    )
    for ((id, color) in overrides) {
        (style.getLayer(id) as? LineLayer)?.setProperties(PropertyFactory.lineColor(color))
    }
}

/** Replace the route polyline and waypoint markers on the map. */
private fun refreshRoute(map: MapLibreMap, track: Track?) {
    val t = track ?: return
    runCatching {
        val style = map.getStyle() ?: return
        style.removeLayer("route-layer")
        style.removeSource("route-source")
        val line = LineString.fromLngLats(t.points.map { Point.fromLngLat(it.lon, it.lat) })
        style.addSource(GeoJsonSource("route-source", line))
        style.addLayer(
            LineLayer("route-layer", "route-source").withProperties(
                PropertyFactory.lineColor(0xFF7CC29A.toInt()),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.95f),
            )
        )

        // Named waypoints (POIs) from the track, drawn as amber dots.
        if (t.waypoints.isNotEmpty()) {
            style.removeLayer("poi-layer")
            style.removeSource("poi-source")
            val features = t.waypoints.map { wpt ->
                org.maplibre.geojson.Feature.fromGeometry(
                    Point.fromLngLat(wpt.lon, wpt.lat)
                )
            }
            style.addSource(
                GeoJsonSource(
                    "poi-source",
                    org.maplibre.geojson.FeatureCollection.fromFeatures(features),
                )
            )
            style.addLayer(
                CircleLayer("poi-layer", "poi-source").withProperties(
                    PropertyFactory.circleColor(0xFFFFB300.toInt()),
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleOpacity(0.95f),
                )
            )
        }
    }
}

/** Update the rider marker (a heading arrow) and follow the rider only when far off-center. */
private fun moveAndCenter(map: MapLibreMap, mapView: MapView, lat: Double, lon: Double, bearing: Double?) {
    updateMeMarker(map, lat, lon, bearing)

    val center = map.cameraPosition?.target
    if (center == null || mapView.width <= 0 || mapView.height <= 0) {
        map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon)))
        return
    }

    // Recenter only when the rider has moved >30% of the shorter screen edge
    // away from the middle; otherwise just let the arrow update.
    val rider = map.getProjection().toScreenLocation(LatLng(lat, lon))
    val middle = map.getProjection().toScreenLocation(center)
    val dx = rider.x - middle.x
    val dy = rider.y - middle.y
    val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble())
    val threshold = 0.30 * kotlin.math.min(mapView.width, mapView.height)
    if (dist > threshold) {
        map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon)))
    }
}

/** Idle-position marker: a small blue dot (with a white ring) at the last known location. */
private fun updateIdleDot(map: MapLibreMap, lat: Double?, lon: Double?, show: Boolean) {
    runCatching {
        val style = map.getStyle() ?: return
        style.removeLayer("me-dot-core")
        style.removeLayer("me-dot-ring")
        style.removeSource("me-dot-source")
if (!show || lat == null || lon == null) return
        val point = Point.fromLngLat(lon, lat)
        style.addSource(GeoJsonSource("me-dot-source", point))
        style.addLayer(
            CircleLayer("me-dot-ring", "me-dot-source").withProperties(
                PropertyFactory.circleColor(0xFFFFFFFF.toInt()),
                PropertyFactory.circleRadius(12f),
                PropertyFactory.circleOpacity(0.9f),
            )
        )
        style.addLayer(
            CircleLayer("me-dot-core", "me-dot-source").withProperties(
                PropertyFactory.circleColor(0xFF2196F3.toInt()),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleOpacity(1f),
                PropertyFactory.circleStrokeColor(0xFF1565C0.toInt()),
                PropertyFactory.circleStrokeWidth(2f),
            )
        )
    }
}

/** Arrow marker that rotates to the current course. N = pointing up; rotation from north. */
private fun updateMeMarker(map: MapLibreMap, lat: Double, lon: Double, bearing: Double?) {
    runCatching {
        val style = map.getStyle() ?: return
        val props = com.google.gson.JsonObject().apply {
            addProperty("bearing", bearing ?: 0.0)
        }
        val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat), props)
        val existing = style.getSource("me-source") as? GeoJsonSource
        if (existing == null) {
            style.addImage("me-image", headingArrowBitmap())
            style.addSource(GeoJsonSource("me-source", feature))
            style.addLayer(
                SymbolLayer("me-layer", "me-source").withProperties(
                    PropertyFactory.iconImage("me-image"),
                    PropertyFactory.iconSize(0.55f),
                    PropertyFactory.iconRotate(Expression.get("bearing")),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconAllowOverlap(true),
                )
            )
        } else {
            existing.setGeoJson(feature)
        }
    }
}

/** A small triangle+shaft arrow pointing north; rotated by the SymbolLayer via bearing. */
private fun headingArrowBitmap(): Bitmap {
    val size = 96
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE53935.toInt()
        style = Paint.Style.FILL
    }
    val path = Path().apply {
        moveTo(size * 0.50f, size * 0.08f) // tip
        lineTo(size * 0.28f, size * 0.52f) // left base
        lineTo(size * 0.44f, size * 0.52f)
        lineTo(size * 0.44f, size * 0.92f) // tail
        lineTo(size * 0.56f, size * 0.92f)
        lineTo(size * 0.56f, size * 0.52f)
        lineTo(size * 0.72f, size * 0.52f) // right base
        close()
    }
    canvas.drawPath(path, fill)
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = size * 0.02f
    }
    canvas.drawPath(path, outline)
    return bmp
}

private fun MapLibreMap.centerOnRider() {
    val lat = RideStore.lat
    val lon = RideStore.lon
    if (lat != null && lon != null) updateMeMarker(this, lat, lon, RideStore.bearing)
}

/** Fit the camera to show the full route (with padding), clamped to a sane minimum zoom. */
private fun MapLibreMap.fitRoute(track: Track?) {
    val t = track ?: return
    runCatching {
        val builder = LatLngBounds.Builder()
        t.points.forEach { builder.include(LatLng(it.lat, it.lon)) }
        val bounds = builder.build()
        val target = LatLng(
            (bounds.getLatNorth() + bounds.getLatSouth()) / 2.0,
            (bounds.getLonEast() + bounds.getLonWest()) / 2.0,
        )
        animateCamera(
            CameraUpdateFactory.newLatLngZoom(
                target,
                cameraZoomForBounds(bounds).coerceIn(4.0, 16.0),
            )
        )
    }
}

/** Rough zoom level that fits [bounds] on screen; adequate for "show the whole route". */
private fun cameraZoomForBounds(bounds: LatLngBounds): Double {
    val maxSpan = maxOf(bounds.latitudeSpan, bounds.longitudeSpan)
    if (maxSpan <= 0.0) return 12.0
    // Approximate Web-Mercator relationship: each zoom step halves the visible span.
    return 12.0 - kotlin.math.log2(maxSpan / 0.1)
}

private fun MapLibreMap.centerOnLastKnown(context: android.content.Context) {
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
    val provider: String? = if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER))
        android.location.LocationManager.GPS_PROVIDER
    else android.location.LocationManager.NETWORK_PROVIDER
    val loc = provider?.let {
        try {
            lm.getLastKnownLocation(it)
        } catch (ignored: SecurityException) {
            null
        }
    }
    loc?.let {
        animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.0))
    }
}