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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.cache.OfflinePackInfo
import com.crazycapy.randonneur.cache.OfflinePackStatus
import com.crazycapy.randonneur.cache.RouteOfflinePack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun OfflinePacksDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var packs by remember { mutableStateOf<List<OfflinePackInfo>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        packs = null
        RouteOfflinePack.listPacks(context) { list ->
            packs = list
            busy = false
        }
    }

    val totalBytes = packs?.sumOf { it.bytes ?: 0L } ?: 0L
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Offline map packs") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val list = packs
                if (list == null) {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                } else if (list.isEmpty()) {
                    Text(
                        "No offline map packs yet. Prepare a route offline to download one.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "${list.size} pack${if (list.size == 1) "" else "s"} · ${formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    list.forEach { pack ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(pack.routeName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    "${pack.theme} · ${pack.style}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                                Text(
                                    "${formatBytes(pack.bytes)} · ${formatDate(pack.createdAt)} · ${statusText(pack)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    RouteOfflinePack.deletePacks(context, listOf(pack.id)) {
                                        refresh++
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete ${pack.routeName}",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                    if (busy) {
                        Spacer(Modifier.height(8.dp))
                        Text("Working…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && !packs.isNullOrEmpty(),
                onClick = {
                    busy = true
                    RouteOfflinePack.deletePacks(context, null) {
                        refresh++
                    }
                },
            ) { Text("Delete all") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun statusText(pack: OfflinePackInfo): String = when (pack.status) {
    OfflinePackStatus.COMPLETE -> "Complete"
    OfflinePackStatus.DOWNLOADING -> if (pack.requiredResources > 0) {
        val percent = (pack.completedResources * 100 / pack.requiredResources).toInt()
        "Downloading $percent%"
    } else {
        "Downloading"
    }
    OfflinePackStatus.INCOMPLETE -> "Incomplete"
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "—"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${kb.toInt()} kB"
    return "%.1f MB".format(kb / 1024.0)
}

private fun formatDate(createdAt: Long?): String {
    if (createdAt == null) return "—"
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(createdAt))
}
