/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.dialogs

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.state.RouteStore
import com.crazycapy.randonneur.voice.Phrases

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  RoutesDialog — saved-routes library manager                           │
 * │                                                                         │
 * │  Lists every route saved on-device (GPX in filesDir/routes/), showing   │
 * │  its name, length, cache status (KB cached / "not cached"), and three   │
 * │  action buttons: Load / Cache (pre-render turns) / Del (delete cache)   │
 * │  / Delete (remove route).  The cache status auto-refreshes after a      │
 * │  pre-cache job finishes (keyed on RouteCache.activeRouteId).            │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
@Composable
internal fun RoutesDialog(
    context: Context,
    onDismiss: () -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
) {
    val cacheKey = RouteCache.activeRouteId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved routes") },
        text = {
            if (RouteStore.routes.isEmpty()) {
                Text("No routes saved yet. Import a GPX to keep it here.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column {
                    RouteStore.routes.forEach { route ->
                        val bytes = remember(cacheKey, route.id) { RouteCache.routeBytes(context, route.id) }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(route.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(Phrases.formatDistance(route.lengthM), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (bytes > 0) "${bytes / 1024} kB cached" else "not cached",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (bytes > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                )
                            }
                            TextButton(onClick = { onLoad(route.id); onDismiss() }) { Text("Load") }
                            TextButton(onClick = {
                                val t = RouteStore.loadTrack(context, route.id)
                                if (t != null) RouteCache.preCache(context, t)
                            }) { Text("Cache") }
                            if (bytes > 0) {
                                TextButton(onClick = { RouteCache.deleteRoute(context, route.id) }) { Text("Del") }
                            }
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
