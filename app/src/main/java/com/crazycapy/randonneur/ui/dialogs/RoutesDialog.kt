/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.dialogs

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.gpx.RwGpsParser
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import com.crazycapy.randonneur.state.RwGpsImport
import com.crazycapy.randonneur.voice.Phrases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  RoutesDialog — saved-routes library manager                           │
 * │                                                                         │
 * │  Each route is shown as a tappable row: name + distance + cache action  │
 * │  or cache size.  Tap the row to load the route.  Long-press opens a     │
 * │  context menu for managing the cache and deleting the route.            │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RoutesDialog(
    context: Context,
    onDismiss: () -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
) {
    val busyId = RouteCache.activeRouteId
    var showRwgps by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved routes") },
        text = {
            if (RouteStore.routes.isEmpty()) {
                Text("No routes saved yet. Import a GPX to keep it here.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column {
                    RouteStore.routes.forEach { route ->
                        val bytes = remember(busyId, route.id) { RouteCache.routeBytes(context, route.id) }
                        val isBusy = busyId == route.id
                        var showMenu by remember { mutableStateOf(false) }

                        val thumb = remember(busyId, route.id) { RouteCache.loadOverview(context, route.id) }

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onLoad(route.id); onDismiss() },
                                    onLongClick = { showMenu = true },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Route overview thumbnail
                            if (thumb != null) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.size(60.dp, 40.dp),
                                ) {
                                    Image(bitmap = thumb.asImageBitmap(), contentDescription = "Route overview", modifier = Modifier.size(60.dp, 40.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(route.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(Phrases.formatDistance(route.lengthM), style = MaterialTheme.typography.bodySmall)
                            }

                            Spacer(Modifier.width(8.dp))

                            if (isBusy) {
                                Text("caching…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            } else if (bytes > 0) {
                                Text(
                                    "${bytes / 1024} kB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("cached", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        val t = RouteStore.loadTrack(context, route.id)
                                        if (t != null) RouteCache.preCache(context, t)
                                    },
                                ) { Text("Cache") }
                            }

                            // Three-dot menu trigger
                            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Route options", tint = MaterialTheme.colorScheme.onSurface)
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (bytes > 0) "Re-cache" else "Cache") },
                                    onClick = {
                                        showMenu = false
                                        val t = RouteStore.loadTrack(context, route.id)
                                        if (t != null) RouteCache.preCache(context, t)
                                    },
                                )
                                if (bytes > 0) {
                                    DropdownMenuItem(
                                        text = { Text("Clear cache") },
                                        onClick = {
                                            showMenu = false
                                            RouteCache.deleteRoute(context, route.id)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Delete route", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMenu = false
                                        RouteCache.deleteRoute(context, route.id)
                                        onDelete(route.id)
                                    },
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onImport(); onDismiss() }) { Text("Import") }
                TextButton(onClick = { showRwgps = true }) { Text("RWGPS") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
    if (showRwgps) {
        RwGpsImportDialog(
            context = context,
            onDone = { showRwgps = false; onDismiss() },
            onCancel = { showRwgps = false },
        )
    }
}

@Composable
private fun RwGpsImportDialog(
    context: Context,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var routes by remember { mutableStateOf<List<RwGpsParser.RouteSummary>?>(null) }

    val doImport: (String) -> Unit = { target ->
        scope.launch {
            busy = true; error = null
            val track = withContext(Dispatchers.IO) { RwGpsImport.fetchTrack(target) }
            busy = false
            if (track != null) {
                RideStore.track = track
                RideStore.status = "Route loaded: ${track.name}"
                RouteStore.saveTrack(context, track)
                onDone()
            } else {
                error = "Couldn't fetch that route. Check the URL and that you're online."
            }
        }
    }
    val doList: (String) -> Unit = { target ->
        scope.launch {
            busy = true; error = null
            val list = withContext(Dispatchers.IO) { RwGpsImport.listRoutes(target) }
            busy = false
            if (list.isEmpty()) error = "No public routes found for that profile."
            else {
                routes = list
                url = target
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(if (routes == null) "Import from ridewithgps" else "Select a route") },
        text = {
            Column {
                if (routes != null) {
                    routes!!.forEach { route ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { doImport(route.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(route.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(Phrases.formatDistance(route.distanceM), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(if (busy) "…" else "→", color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                    }
                    if (busy) {
                        Spacer(Modifier.height(8.dp))
                        Text("Working…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    } else if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text(
                        "Paste a ridewithgps.com route URL (or its numeric id), or a user profile URL/id to pick from their public routes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; error = null },
                        placeholder = { Text("https://ridewithgps.com/routes/37482029") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (busy) {
                        Text("Working…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    } else if (error != null) {
                        Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (routes == null) {
                Row {
                    TextButton(enabled = !busy && url.isNotBlank(), onClick = { doImport(url) }) { Text("Import") }
                    TextButton(enabled = !busy && url.isNotBlank(), onClick = { doList(url) }) { Text("List routes") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onCancel() }) { Text("Close") }
        },
    )
}
