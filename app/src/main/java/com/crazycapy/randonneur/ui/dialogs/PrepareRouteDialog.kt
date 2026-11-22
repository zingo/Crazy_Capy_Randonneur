/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.cache.PreparePhase
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.cache.RouteOfflinePack
import com.crazycapy.randonneur.cache.RoutePrepare
import com.crazycapy.randonneur.gpx.Track

@Composable
internal fun PrepareRouteDialog(
    track: Track,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state = RoutePrepare.state
    LaunchedEffect(track) { RoutePrepare.start(context, track) }

    val map = RouteOfflinePack.state
    val previewStatus = RouteCache.status
    val previewProgress = RouteCache.progress

    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text("Prepare route offline") },
        text = {
            Column {
                Text(state.routeName.ifBlank { track.name }, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
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
                            Text(
                                statusLine(map.percent, map.completedBytes, map.completedResources, map.requiredResources),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        map.done -> Text(
                            "Saved ${formatBytes(map.completedBytes)} for offline use.",
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
        },
        confirmButton = {
            if (state.running) {
                TextButton(onClick = { RoutePrepare.cancel() }) { Text("Cancel") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
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
