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
import com.crazycapy.randonneur.cache.OfflineDetailStrategy
import com.crazycapy.randonneur.cache.OfflineLimits
import com.crazycapy.randonneur.cache.OfflinePackInfo
import com.crazycapy.randonneur.cache.OfflinePackStatus
import com.crazycapy.randonneur.cache.OfflinePrune
import com.crazycapy.randonneur.cache.RouteOfflinePack
import com.crazycapy.randonneur.cache.groupOfflinePacks
import com.crazycapy.randonneur.state.RideStore
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

    LaunchedEffect(refresh, OfflinePrune.revision) {
        packs = null
        RouteOfflinePack.listPacks(context) { list ->
            packs = list
            busy = false
        }
    }

    val groups = packs?.let { groupOfflinePacks(it, System.currentTimeMillis(), RideStore.offlineExpiryDays) }
    val totalBytes = groups?.sumOf { it.bytes } ?: 0L
    val capBytes = OfflineLimits.capBytes(RideStore.offlineStorageCapMb)
    val expiredGroups = groups?.filter { it.expired }.orEmpty()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Offline map packs") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OfflinePrune.notice?.let { notice ->
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (groups == null) {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                } else if (groups.isEmpty()) {
                    Text(
                        "No offline map packs yet. Prepare a route offline to download one.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        "${groups.size} group${if (groups.size == 1) "" else "s"} · ${formatBytes(totalBytes)} of " +
                            (capBytes?.let { "${formatBytes(it)} cap" } ?: "unlimited"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (expiredGroups.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${expiredGroups.size} expired",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    RouteOfflinePack.deletePacks(context, expiredGroups.flatMap { it.ids }) {
                                        refresh++
                                    }
                                },
                            ) { Text("Delete expired") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    groups.forEach { pack ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(pack.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    "${strategyText(pack.strategy)} · ${pack.theme} · ${pack.style}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                                Text(
                                    "${pack.segments} segment${if (pack.segments == 1) "" else "s"} · ${formatBytes(pack.bytes)} · " +
                                        "${formatDate(pack.createdAt)} · ${ageText(pack.ageDays)} · ${statusText(pack.status)}" +
                                        if (pack.expired) " · EXPIRED" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (pack.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    RouteOfflinePack.deletePacks(context, pack.ids) {
                                        refresh++
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete ${pack.name}",
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

private fun strategyText(strategy: OfflineDetailStrategy): String = when (strategy) {
    OfflineDetailStrategy.STANDARD -> "Standard"
    OfflineDetailStrategy.TURN_FOCUSED -> "Turn-focused"
}

private fun statusText(status: OfflinePackStatus): String = when (status) {
    OfflinePackStatus.COMPLETE -> "Complete"
    OfflinePackStatus.DOWNLOADING -> "Downloading"
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

private fun ageText(ageDays: Long?): String = when {
    ageDays == null -> "age unknown"
    ageDays == 0L -> "today"
    else -> "$ageDays day${if (ageDays == 1L) "" else "s"} old"
}
