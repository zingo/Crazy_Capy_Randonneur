/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.dialogs

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import com.crazycapy.randonneur.ble.BleCyclingProvider
import com.crazycapy.randonneur.ble.BleHrProvider
import com.crazycapy.randonneur.ble.CyclingPhase
import com.crazycapy.randonneur.ble.CyclingSensorState
import com.crazycapy.randonneur.ble.HrPhase
import com.crazycapy.randonneur.ble.HrSensorState
import com.crazycapy.randonneur.cache.OfflineDetailStrategy
import com.crazycapy.randonneur.cache.OfflineThemeMode
import com.crazycapy.randonneur.cache.OfflineTuning
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.coros.CorosController
import com.crazycapy.randonneur.coros.CorosRegion
import com.crazycapy.randonneur.coros.CorosStatus
import com.crazycapy.randonneur.radar.RadarClient
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import com.crazycapy.randonneur.voice.SpeechMarkerGenerator
import kotlin.math.roundToInt

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  SettingsDialog — ride options, sensors, offline prep, about            │
 * │                                                                         │
 * │  Opened from the gear icon (top-right).  Scrollable sections of         │
 * │  toggles, sliders and action buttons in a single AlertDialog.           │
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
    val hrState = BleHrProvider.state
    val cyclingState = BleCyclingProvider.state
    val hrActions = rememberSensorActions(
        hasPermissions = { BleHrProvider.hasPermissions(context) },
        startScan = { BleHrProvider.startScan() },
        connect = { address, name -> BleHrProvider.connect(address, name) },
        saveDevice = { address, name ->
            RideStore.hrDeviceAddress = address
            RideStore.hrDeviceName = name
            RouteStore.saveSettings(context)
        },
    )
    val cyclingActions = rememberSensorActions(
        hasPermissions = { BleCyclingProvider.hasPermissions(context) },
        startScan = { BleCyclingProvider.startScan() },
        connect = { address, name -> BleCyclingProvider.connect(address, name) },
        saveDevice = { address, name ->
            RideStore.cyclingDeviceAddress = address
            RideStore.cyclingDeviceName = name
            RouteStore.saveSettings(context)
        },
    )

    var showCorosData by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingsSection(
                    title = "Ride",
                    subtitle = "Guidance, audio and GPS update rate",
                ) {
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
                    SettingChoices(
                        title = "GPS update rate",
                        subtitle = "Power-save 5 s · normal 3 s · precise 1 s",
                    ) {
                        listOf(5 to "5 s", 3 to "3 s", 1 to "1 s").forEach { (sec, label) ->
                            TextButton(onClick = { RideStore.gpsIntervalSec = sec; RouteStore.saveSettings(context) }) {
                                Text(if (RideStore.gpsIntervalSec == sec) "$label ✓" else label)
                            }
                        }
                    }
                    SettingRow(
                        title = "Saved routes",
                        subtitle = "Open the route library to manage saved files",
                    ) {
                        TextButton(onClick = { onShowRoutes(); onDismiss() }) { Text("Manage") }
                    }
                }

                SettingsSection(
                    title = "Sensors",
                    subtitle = "Bluetooth heart rate, cadence and power",
                ) {
                    SensorSection(
                        title = "Heart rate",
                        status = hrStatusText(context, hrState),
                        description = "Standard BLE heart-rate straps (service 0x180D). A real sensor needs BLE-capable hardware.",
                        connected = hrState.phase == HrPhase.CONNECTED,
                        scanning = hrState.phase == HrPhase.SCANNING,
                        devices = hrState.devices,
                        savedAddress = RideStore.hrDeviceAddress,
                        savedName = RideStore.hrDeviceName,
                        emptyText = "No heart-rate straps found yet",
                        chooseText = "Choose a strap",
                        fallbackName = "Heart-rate strap",
                        actions = hrActions,
                        onCancelScan = { BleHrProvider.stopScan() },
                        onDisconnect = { BleHrProvider.disconnect() },
                    )
                    SensorSection(
                        title = "Cadence / power",
                        status = cyclingStatusText(context, cyclingState),
                        description = "Standard BLE cadence (CSC 0x1816) and power (CPS 0x1818) sensors. GPS speed is always the HUD speed. A real sensor needs BLE-capable hardware.",
                        connected = cyclingState.phase == CyclingPhase.CONNECTED,
                        scanning = cyclingState.phase == CyclingPhase.SCANNING,
                        devices = cyclingState.devices,
                        savedAddress = RideStore.cyclingDeviceAddress,
                        savedName = RideStore.cyclingDeviceName,
                        emptyText = "No cycling sensors found yet",
                        chooseText = "Choose a sensor",
                        fallbackName = "Cycling sensor",
                        actions = cyclingActions,
                        onCancelScan = { BleCyclingProvider.stopScan() },
                        onDisconnect = { BleCyclingProvider.disconnect() },
                    )
                }

                SettingsSection(
                    title = "Connections",
                    subtitle = "Rear radar and optional COROS integration",
                ) {
                    SettingSwitch(
                        checked = RideStore.radarIntegrationEnabled,
                        onCheckedChange = { RadarClient.setIntegrationEnabled(context, it); RouteStore.saveSettings(context) },
                        title = "Live rear-radar",
                        subtitle = "Read a real rear radar via the overlay app (off saves battery)",
                    )
                    SettingRow(
                        title = "COROS status",
                        subtitle = corosStatusText(),
                    ) {
                        if (CorosController.status == CorosStatus.CONNECTED) {
                            TextButton(onClick = { showCorosData = true }) { Text("View data") }
                        }
                    }
                    CorosController.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    SettingChoices(
                        title = "COROS region",
                        subtitle = "Sign-in and data server; changing it requires reconnecting",
                    ) {
                        CorosRegion.entries.forEach { option ->
                            TextButton(onClick = { CorosController.setRegion(context, option) }) {
                                Text(option.shortLabel + if (RideStore.corosRegion == option) " ✓" else "")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        when (CorosController.status) {
                            CorosStatus.WAITING -> {
                                TextButton(
                                    onClick = { CorosController.openLogin(context) },
                                    enabled = CorosController.loginUrl != null,
                                ) { Text("Open login link again") }
                                TextButton(onClick = { CorosController.cancelLogin() }) { Text("Cancel") }
                            }
                            CorosStatus.CONNECTED -> {
                                TextButton(onClick = { CorosController.disconnect(context) }) { Text("Disconnect") }
                                TextButton(onClick = { showCorosData = true }) { Text("COROS data") }
                            }
                            else -> {
                                TextButton(onClick = { CorosController.connect(context) }) { Text("Connect") }
                            }
                        }
                    }
                }

                SettingsSection(
                    title = "Offline maps & storage",
                    subtitle = "Route preparation, downloaded packs and retention",
                ) {
                    SettingSwitch(
                        checked = RideStore.precacheEnabled,
                        onCheckedChange = { RideStore.precacheEnabled = it; RouteStore.saveSettings(context) },
                        title = "Prepare routes offline",
                        subtitle = "Ask to download map tiles and turn previews when a route loads",
                    )
                    SettingChoices(
                        title = "Offline map detail",
                        subtitle = "Standard keeps the whole corridor at zoom 8–14; turn-focused saves space with low zoom between turns and high zoom near them",
                    ) {
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
                    SettingSubheader(
                        title = "Advanced offline tuning",
                        subtitle = "Applies to the next prepare; existing packs with different tuning are replaced",
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
                    SettingChoices(
                        title = "Offline map theme",
                        subtitle = "Default for “also prepare the other theme” in the prepare dialog",
                    ) {
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
                    SettingRow(
                        title = "Offline map packs",
                        subtitle = "List, inspect and delete downloaded route maps",
                    ) {
                        TextButton(onClick = { onShowOfflinePacks(); onDismiss() }) { Text("Manage") }
                    }
                    val totalBytes = remember { RouteCache.totalBytes(context) }
                    SettingRow(
                        title = "Route caches",
                        subtitle = if (totalBytes > 0) "${totalBytes / 1024} kB used" else "Empty",
                    ) {
                        if (totalBytes > 0) {
                            TextButton(onClick = { RouteCache.deleteAll(context) }) { Text("Clear all") }
                        }
                    }
                }

                SettingsSection(
                    title = "Demo & diagnostics",
                    subtitle = "Ghost ride and speech-marker tools",
                ) {
                    SettingRow(
                        title = "Ghost ride",
                        subtitle = if (ghostAvailable) {
                            "Simulated ride on the loaded route (real time)"
                        } else {
                            "Load a route first"
                        },
                    ) {
                        TextButton(enabled = ghostAvailable, onClick = { onStartGhost(); onDismiss() }) {
                            Text("Start")
                        }
                    }
                    SettingSwitch(
                        checked = RideStore.showGhostControls,
                        onCheckedChange = { RideStore.showGhostControls = it; RouteStore.saveSettings(context) },
                        title = "Show ghost controls",
                        subtitle = "Speed/scale/radar buttons during a ghost ride",
                    )
                    SettingRow(
                        title = "Speech markers",
                        subtitle = "Mark every spoken clip on the map at its trigger point",
                    ) {
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
                }

                SettingsSection(
                    title = "About",
                    subtitle = "Version, attribution and licenses",
                ) {
                    AboutRow("App name", "Crazy Capy Randonneur")
                    AboutRow("Version", versionName)
                    AboutRow("Version code", versionCode.toString())
                    AboutRow("Build type", buildType)
                    AboutRow(
                        "Rear radar",
                        "Optional: android-bike-radar-overlay (GPL-3.0-or-later, separate app)",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Map data © OpenStreetMap contributors. Tiles by OpenFreeMap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    if (showCorosData) {
        CorosDialog(context = context, onDismiss = { showCorosData = false })
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 6.dp))
        content()
    }
}

@Composable
private fun SettingSubheader(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingLabel(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.bodyMedium)
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            SettingLabel(title, subtitle)
        }
        trailing()
    }
}

@Composable
private fun SettingChoices(
    title: String,
    subtitle: String,
    choices: @Composable RowScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        SettingLabel(title, subtitle)
        Row(content = choices)
    }
}

private fun cyclingStatusText(context: Context, state: CyclingSensorState): String {
    if (!BleCyclingProvider.hasPermissions(context)) return "Permission needed"
    return when (state.phase) {
        CyclingPhase.IDLE -> {
            val saved = RideStore.cyclingDeviceName ?: RideStore.cyclingDeviceAddress
            if (saved.isNullOrBlank()) "Not connected" else "Saved sensor: $saved"
        }
        CyclingPhase.SCANNING -> "Scanning…"
        CyclingPhase.CONNECTING -> "Connecting…"
        CyclingPhase.CONNECTED -> buildString {
            append("Connected: ").append(state.deviceName ?: state.deviceAddress ?: "sensor")
            val values = listOfNotNull(
                state.cadenceRpm?.let { "cadence" },
                state.powerWatts?.let { "power" },
            )
            if (values.isNotEmpty()) append(" · ").append(values.joinToString(", "))
        }
        CyclingPhase.PERMISSION_NEEDED -> "Bluetooth permission needed"
        CyclingPhase.ERROR -> state.error ?: "Error"
    }
}

private fun corosStatusText(): String = when (CorosController.status) {
    CorosStatus.NOT_CONNECTED -> "Not connected"
    CorosStatus.WAITING -> "Waiting for login"
    CorosStatus.CONNECTED -> "Connected"
    CorosStatus.ERROR -> "Error"
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
    SettingRow(title = title, subtitle = subtitle) {
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
    SettingRow(title = title, subtitle = subtitle) {
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
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SettingLabel(title, subtitle)
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

private fun hrStatusText(context: Context, state: HrSensorState): String {
    if (!BleHrProvider.hasPermissions(context)) return "Permission needed"
    return when (state.phase) {
        HrPhase.IDLE -> state.error?.let { "Error: $it" } ?: "Not connected"
        HrPhase.SCANNING -> "Scanning…"
        HrPhase.CONNECTING -> "Connecting…"
        HrPhase.CONNECTED -> {
            val name = state.deviceName ?: state.deviceAddress ?: "sensor"
            val bpm = state.bpm?.let { " · $it bpm" } ?: ""
            "Connected $name$bpm"
        }
        HrPhase.PERMISSION_NEEDED -> "Permission needed"
        HrPhase.ERROR -> "Error: ${state.error ?: "unknown"}"
    }
}

@Composable
internal fun SettingSlider(value: Int, onValueChange: (Int) -> Unit, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SettingLabel(title, subtitle)
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
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
