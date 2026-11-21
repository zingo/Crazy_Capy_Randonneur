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
import com.crazycapy.randonneur.cache.RouteOfflinePack
import com.crazycapy.randonneur.gpx.Track

@Composable
internal fun OfflinePackDialog(
    track: Track,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state = RouteOfflinePack.state
    LaunchedEffect(track) { RouteOfflinePack.start(context, track) }

    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text("Offline map") },
        text = {
            Column {
                Text(state.routeName, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                when {
                    state.running -> {
                        if (state.percent != null) {
                            LinearProgressIndicator(
                                progress = { state.percent },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            statusLine(state.percent, state.completedBytes, state.completedResources, state.requiredResources),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.done -> Text(
                        "Saved ${formatBytes(state.completedBytes)} for offline use.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.cancelled -> Text(
                        "Download cancelled.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (state.running) {
                TextButton(onClick = { RouteOfflinePack.cancel() }) { Text("Cancel") }
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
