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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.crazycapy.randonneur.cache.OfflineCapDecision
import com.crazycapy.randonneur.cache.OfflineDetailStrategy
import com.crazycapy.randonneur.cache.OfflineEstimateCalculator
import com.crazycapy.randonneur.cache.OfflineLimits
import com.crazycapy.randonneur.cache.PreparePhase
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.cache.RouteOfflinePack
import com.crazycapy.randonneur.cache.RoutePrepare
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore

@Composable
internal fun PrepareRouteDialog(
    track: Track,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val routeId = remember(track) { RouteStore.routeId(track.name) }
    var started by remember(track) { mutableStateOf(false) }
    var strategy by remember(track) { mutableStateOf(RideStore.offlineDetailStrategy) }
    var alsoOther by remember(track) { mutableStateOf(RideStore.defaultPrepareBothThemes()) }
    var capMessage by remember(track) { mutableStateOf<String?>(null) }
    var checkingCap by remember(track) { mutableStateOf(false) }
    val estimate = remember(track, strategy, alsoOther) {
        OfflineEstimateCalculator.estimate(track, strategy, if (alsoOther) 2 else 1)
    }
    val state = RoutePrepare.state
    val showProgress = started || (state.running && state.routeId == routeId)

    val map = RouteOfflinePack.state
    val previewStatus = RouteCache.status
    val previewProgress = RouteCache.progress

    fun beginPrepare() {
        RideStore.offlineDetailStrategy = strategy
        RideStore.offlinePrepareBothThemes = alsoOther
        RouteStore.saveSettings(context)
        started = true
        RoutePrepare.start(context, track, strategy, alsoOther)
    }

    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text("Prepare route offline") },
        text = {
            Column {
                Text(state.routeName.ifBlank { track.name }, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                if (!showProgress) {
                    Text("Offline map detail", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    StrategyOption(
                        selected = strategy == OfflineDetailStrategy.STANDARD,
                        title = "Standard",
                        subtitle = "Whole corridor at zoom 8–14",
                        onClick = { strategy = OfflineDetailStrategy.STANDARD },
                    )
                    StrategyOption(
                        selected = strategy == OfflineDetailStrategy.TURN_FOCUSED,
                        title = "Turn-focused",
                        subtitle = "Whole corridor at zoom 8–12 · high zoom 13–14 near turns",
                        onClick = { strategy = OfflineDetailStrategy.TURN_FOCUSED },
                    )
                    Spacer(Modifier.height(12.dp))
                    SettingSwitch(
                        checked = alsoOther,
                        onCheckedChange = {
                            alsoOther = it
                            RideStore.offlinePrepareBothThemes = it
                        },
                        title = "Also prepare the other map theme",
                        subtitle = "Dark and light are downloaded as separate regions",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Estimated pack size: ${formatBytes(estimate.bytes)} · ${estimate.segments} segments · " +
                            "${estimate.themes} theme${if (estimate.themes == 1) "" else "s"} (estimate)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    capMessage?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text("1/2 · Map tiles", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    if (state.mapSkipped) {
                        Text("Already downloaded ✓", style = MaterialTheme.typography.bodySmall)
                    } else {
                        when {
                            map.running -> {
                                if (map.percent != null) {
                                    LinearProgressIndicator(
                                        progress = { map.percent },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                Spacer(Modifier.height(8.dp))
                                if (map.segmentCount > 0) {
                                    Text(
                                        "Segment ${map.segmentIndex}/${map.segmentCount} · ${strategyLabel(map.strategy)}" +
                                            if (map.themeCount > 1) " · ${map.theme}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    statusLine(map.percent, map.completedBytes, map.completedResources, map.requiredResources),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            map.done -> Text(
                                "Saved ${formatBytes(map.completedBytes)} · ${map.segmentCount} segment${if (map.segmentCount == 1) "" else "s"}" +
                                    if (map.themeCount > 1) " · ${map.themeCount} themes." else ".",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            map.cancelled -> Text("Download cancelled.", style = MaterialTheme.typography.bodySmall)
                            else -> Text("Waiting…", style = MaterialTheme.typography.bodySmall)
                        }
                        map.error?.let { error ->
                            Spacer(Modifier.height(4.dp))
                            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("2/2 · Turn previews", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    when {
                        state.previewsSkipped -> Text("Already cached ✓", style = MaterialTheme.typography.bodySmall)
                        state.phase == PreparePhase.MAP -> Text("Waiting for map tiles…", style = MaterialTheme.typography.bodySmall)
                        else -> {
                            if (state.running && previewProgress != null) {
                                LinearProgressIndicator(
                                    progress = { previewProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            val status = state.previewsStatus ?: previewStatus
                            if (status != null) {
                                Text(status, style = MaterialTheme.typography.bodySmall)
                            } else if (state.running) {
                                Text("Preparing turn previews…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (state.cancelled) {
                        Spacer(Modifier.height(8.dp))
                        Text("Cancelled.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.done && !state.cancelled) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Route ready offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.running && showProgress -> TextButton(onClick = { RoutePrepare.cancel() }) { Text("Cancel") }
                !showProgress -> TextButton(
                    enabled = !checkingCap,
                    onClick = {
                        capMessage = null
                        val cap = RideStore.offlineStorageCapMb
                        if (cap <= 0) {
                            beginPrepare()
                        } else {
                            checkingCap = true
                            RouteOfflinePack.totalBytes(context) { used ->
                                checkingCap = false
                                if (OfflineLimits.decideCap(used, estimate.bytes, cap) == OfflineCapDecision.EXCEEDS) {
                                    capMessage = "Not enough offline storage: packs already use " +
                                        "${formatBytes(used)} of $cap MB and this prepare estimates " +
                                        "${formatBytes(estimate.bytes)}. Delete packs or raise the cap."
                                } else {
                                    beginPrepare()
                                }
                            }
                        }
                    },
                ) { Text(if (checkingCap) "Checking…" else "Start") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun StrategyOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun strategyLabel(strategy: OfflineDetailStrategy): String = when (strategy) {
    OfflineDetailStrategy.STANDARD -> "Standard"
    OfflineDetailStrategy.TURN_FOCUSED -> "Turn-focused"
}

private fun statusLine(percent: Float?, bytes: Long, done: Long, total: Long): String {
    val parts = mutableListOf(formatBytes(bytes))
    if (percent != null) parts.add("${(percent * 100).toInt()}%")
    if (total > 0) parts.add("$done/$total tiles")
    return "Downloading · " + parts.joinToString(" · ")
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.toInt()} kB"
    return "%.1f MB".format(kb / 1024.0)
}
