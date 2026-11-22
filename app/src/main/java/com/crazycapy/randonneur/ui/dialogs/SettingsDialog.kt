/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.cache.OfflineDetailStrategy
import com.crazycapy.randonneur.cache.OfflineThemeMode
import com.crazycapy.randonneur.cache.OfflineTuning
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.radar.RadarClient
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import com.crazycapy.randonneur.voice.SpeechMarkerGenerator
import kotlin.math.roundToInt

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  SettingsDialog — ride options, offline prep, ghost ride, about         │
 * │                                                                         │
 * │  Opened from the gear icon (top-right).  Scrollable list of toggles,    │
 * │  sliders and action buttons in a single AlertDialog.                    │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
@Composable
internal fun SettingsDialog(
    onDismiss: () -> Unit,
    onShowLicenses: () -> Unit,
    onShowRoutes: () -> Unit,
    onShowOfflinePacks: () -> Unit,
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
                    onCheckedChange = { RideStore.nextTurnPopupEnabled = it; RouteStore.saveSettings(context) },
                    title = "Next-turn popup",
                    subtitle = "Corner popup with arrow and route preview",
                )
                SettingSwitch(
                    checked = RideStore.notificationEnabled,
                    onCheckedChange = { RideStore.notificationEnabled = it; RouteStore.saveSettings(context) },
                    title = "Live notification",
                    subtitle = "Refresh the notification every second (saves battery when off)",
                )
                SettingSwitch(
                    checked = RideStore.duckMusicEnabled,
                    onCheckedChange = { RideStore.duckMusicEnabled = it; RouteStore.saveSettings(context) },
                    title = "Pause audio while speaking",
                    subtitle = "Other apps pause during guidance announcements",
                )
                SettingSlider(
                    value = RideStore.beepVolume,
                    onValueChange = { RideStore.beepVolume = it; RouteStore.saveSettings(context) },
                    title = "Turn beeps",
                    subtitle = "Left vs right beeps that shorten as the turn nears",
                )
                SettingSlider(
                    value = RideStore.navVolume,
                    onValueChange = { RideStore.navVolume = it; RouteStore.saveSettings(context) },
                    title = "Navigation voice",
                    subtitle = "Spoken turn guidance",
                )
                Column(Modifier.fillMaxWidth()) {
                    Text("GPS update rate", style = MaterialTheme.typography.bodyMedium)
                    Text("Power-save 5 s · normal 3 s · precise 1 s", style = MaterialTheme.typography.bodySmall)
                    Row {
                        listOf(5 to "5 s", 3 to "3 s", 1 to "1 s").forEach { (sec, label) ->
                            TextButton(onClick = { RideStore.gpsIntervalSec = sec; RouteStore.saveSettings(context) }) {
                                Text(if (RideStore.gpsIntervalSec == sec) "$label ✓" else label)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved routes", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onShowRoutes(); onDismiss() }) { Text("Manage") }
                }
                SettingSwitch(
                    checked = RideStore.precacheEnabled,
                    onCheckedChange = { RideStore.precacheEnabled = it; RouteStore.saveSettings(context) },
                    title = "Prepare routes offline",
                    subtitle = "Ask to download map tiles and turn previews when a route loads",
                )
                Column(Modifier.fillMaxWidth()) {
                    Text("Offline map detail", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Standard keeps the whole corridor at zoom 8–14; turn-focused saves space with low zoom between turns and high zoom near them",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        OfflineDetailStrategy.values().forEach { option ->
                            TextButton(onClick = {
                                RideStore.offlineDetailStrategy = option
                                RouteStore.saveSettings(context)
                            }) {
                                Text(
                                    when (option) {
                                        OfflineDetailStrategy.STANDARD -> "Standard"
                                        OfflineDetailStrategy.TURN_FOCUSED -> "Turn-focused"
                                    } + if (RideStore.offlineDetailStrategy == option) " ✓" else "",
                                )
                            }
                        }
                    }
                }
                Text("Advanced offline tuning", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Applies to the next prepare; existing packs with different tuning are replaced",
                    style = MaterialTheme.typography.bodySmall,
                )
                SettingRangeSlider(
                    title = "Segment length",
                    subtitle = "Corridor chunk in km · 2–50",
                    value = RideStore.offlineSegmentKm.toFloat(),
                    valueText = "${formatTuningKm(RideStore.offlineSegmentKm)} km",
                    valueRange = OfflineTuning.MIN_SEGMENT_KM.toFloat()..OfflineTuning.MAX_SEGMENT_KM.toFloat(),
                    steps = (OfflineTuning.MAX_SEGMENT_KM - OfflineTuning.MIN_SEGMENT_KM).toInt() - 1,
                    onValueChange = {
                        RideStore.offlineSegmentKm = it.roundToInt().toDouble()
                        RouteStore.saveSettings(context)
                    },
                )
                SettingRangeSlider(
                    title = "Corridor padding",
                    subtitle = "Map margin either side in km · 0.5–10",
                    value = RideStore.offlineCorridorPadKm.toFloat(),
                    valueText = "${formatTuningKm(RideStore.offlineCorridorPadKm)} km",
                    valueRange = OfflineTuning.MIN_CORRIDOR_PAD_KM.toFloat()..OfflineTuning.MAX_CORRIDOR_PAD_KM.toFloat(),
                    steps = 18,
                    onValueChange = {
                        RideStore.offlineCorridorPadKm = (it * 2f).roundToInt() / 2.0
                        RouteStore.saveSettings(context)
                    },
                )
                SettingRangeSlider(
                    title = "High-detail turn box",
                    subtitle = "Along-route turn window in km · 0.25–5",
                    value = RideStore.offlineTurnBoxKm.toFloat(),
                    valueText = "${formatTuningKm(RideStore.offlineTurnBoxKm)} km",
                    valueRange = OfflineTuning.MIN_TURN_PAD_KM.toFloat()..OfflineTuning.MAX_TURN_PAD_KM.toFloat(),
                    steps = 18,
                    onValueChange = {
                        RideStore.offlineTurnBoxKm = (it * 4f).roundToInt() / 4.0
                        RouteStore.saveSettings(context)
                    },
                )
                SettingRangeSlider(
                    title = "High-detail max zoom",
                    subtitle = "Tile zoom ceiling · 13–16",
                    value = RideStore.offlineHighMaxZoom.toFloat(),
                    valueText = "zoom ${RideStore.offlineHighMaxZoom}",
                    valueRange = OfflineTuning.MIN_MAX_ZOOM.toFloat()..OfflineTuning.MAX_MAX_ZOOM.toFloat(),
                    steps = OfflineTuning.MAX_MAX_ZOOM - OfflineTuning.MIN_MAX_ZOOM - 1,
                    onValueChange = {
                        RideStore.offlineHighMaxZoom = it.roundToInt()
                        RouteStore.saveSettings(context)
                    },
                )
                Column(Modifier.fillMaxWidth()) {
                    Text("Offline map theme", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Default for “also prepare the other theme” in the prepare dialog",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        OfflineThemeMode.values().forEach { option ->
                            TextButton(onClick = {
                                RideStore.offlineThemeMode = option
                                RouteStore.saveSettings(context)
                            }) {
                                Text(
                                    when (option) {
                                        OfflineThemeMode.OFF -> "Off"
                                        OfflineThemeMode.ALWAYS -> "Always"
                                        OfflineThemeMode.ASK -> "Ask"
                                    } + if (RideStore.offlineThemeMode == option) " ✓" else "",
                                )
                            }
                        }
                    }
                }
                SettingNumberField(
                    title = "Offline storage cap",
                    subtitle = "Map-pack budget in MB · 0 = unlimited",
                    value = RideStore.offlineStorageCapMb,
                    suffix = "MB",
                    onValueChange = { RideStore.offlineStorageCapMb = it; RouteStore.saveSettings(context) },
                )
                SettingNumberField(
                    title = "Offline pack expiry",
                    subtitle = "Flag packs older than this many days · 0 = never",
                    value = RideStore.offlineExpiryDays,
                    suffix = "days",
                    onValueChange = { RideStore.offlineExpiryDays = it; RouteStore.saveSettings(context) },
                )
                SettingSwitch(
                    checked = RideStore.offlineAutoDeleteExpired,
                    onCheckedChange = {
                        RideStore.offlineAutoDeleteExpired = it
                        RouteStore.saveSettings(context)
                    },
                    title = "Auto-delete expired offline packs",
                    subtitle = "Remove expired packs on app start · off keeps them listed",
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Offline map packs", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "List, inspect and delete downloaded route maps",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onShowOfflinePacks(); onDismiss() }) { Text("Manage") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val totalBytes = remember { RouteCache.totalBytes(context) }
                    Text(
                        if (totalBytes > 0) "Route caches: ${totalBytes / 1024} kB" else "Route caches: empty",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (totalBytes > 0) {
                        TextButton(onClick = { RouteCache.deleteAll(context) }) { Text("Clear all") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Rear radar", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SettingSwitch(
                    checked = RideStore.radarIntegrationEnabled,
                    onCheckedChange = { RadarClient.setIntegrationEnabled(context, it); RouteStore.saveSettings(context) },
                    title = "Live rear-radar",
                    subtitle = "Read a real rear radar via the overlay app (off saves battery)",
                )
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
                    TextButton(enabled = ghostAvailable, onClick = { onStartGhost(); onDismiss() }) { Text("Start") }
                }
                SettingSwitch(
                    checked = RideStore.showGhostControls,
                    onCheckedChange = { RideStore.showGhostControls = it; RouteStore.saveSettings(context) },
                    title = "Show ghost controls",
                    subtitle = "Speed/scale/radar buttons during a ghost ride",
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Speech markers", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Mark every spoken clip on the map at its trigger point",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = {
                        val track = RideStore.track
                        if (track != null) {
                            Thread {
                                val markers = SpeechMarkerGenerator().generate(track, RideStore.ghostSpeedKmh)
                                RideStore.speechMarkers = markers
                                RideStore.speechMarkersVisible = true
                            }.start()
                        }
                    }, enabled = ghostAvailable) { Text("Generate") }
                }
                SettingSwitch(
                    checked = RideStore.speechMarkersVisible,
                    onCheckedChange = { RideStore.speechMarkersVisible = it },
                    title = "Show speech markers",
                    subtitle = "Toggle the marker overlay on the map",
                )
                Spacer(Modifier.height(12.dp))
                Text("About", style = MaterialTheme.typography.titleSmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                AboutRow("App name", "Crazy Capy Randonneur")
                AboutRow("Version", versionName)
                AboutRow("Version code", versionCode.toString())
                AboutRow("Build type", buildType)
                AboutRow(
                    "Rear radar",
                    "Optional: android-bike-radar-overlay (GPL-3.0-or-later, separate app)",
                )
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
private fun SettingNumberField(
    title: String,
    subtitle: String,
    value: Int,
    suffix: String,
    onValueChange: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(6)
                text = digits
                digits.toIntOrNull()?.let(onValueChange)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(88.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(suffix, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SettingSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingRangeSlider(
    title: String,
    subtitle: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

private fun formatTuningKm(km: Double): String =
    if (km % 1.0 == 0.0) km.toInt().toString() else "%.2f".format(km).trimEnd('0').trimEnd('.')

@Composable
internal fun SettingSlider(value: Int, onValueChange: (Int) -> Unit, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (value <= 0) "Off" else "$value%", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..100f,
            steps = 19,
        )
    }
}

@Composable
internal fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
