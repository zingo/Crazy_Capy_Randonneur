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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.radar.RadarClient
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import kotlin.math.roundToInt

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  SettingsDialog — ride options, pre-cache, ghost ride, about            │
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved routes", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onShowRoutes(); onDismiss() }) { Text("Manage") }
                }
                SettingSwitch(
                    checked = RideStore.precacheEnabled,
                    onCheckedChange = { RideStore.precacheEnabled = it; RouteStore.saveSettings(context) },
                    title = "Pre-cache routes",
                    subtitle = "Ask to pre-render turn previews and warm tiles when a route loads",
                )
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
