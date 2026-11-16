/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.dialogs

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.state.RideHistory
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import com.crazycapy.randonneur.ui.helpers.copyToUri
import com.crazycapy.randonneur.ui.helpers.shareGpx
import com.crazycapy.randonneur.voice.Phrases
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Composable
internal fun RideHistoryDialog(
    context: Context,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var rides by remember { mutableStateOf(RideHistory.list(context)) }
    var renameTarget by remember { mutableStateOf<RideHistory.SavedRide?>(null) }
    var exportTarget by remember { mutableStateOf<File?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val f = exportTarget
        if (uri != null && f != null) copyToUri(context, f, uri)
        exportTarget = null
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Ride history") },
        text = {
            Column {
                if (rides.isEmpty()) {
                    Text("No saved rides yet. Finish a GPS ride and it will show up here.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    rides.forEach { ride ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(ride.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${Phrases.formatDistance(ride.distanceM)} · ${formatDuration(ride.durationSec)} · ${"%.1f".format(ride.avgKmh)} km/h avg · ${"%.1f".format(ride.maxKmh)} max · ↑${ride.ascentM.roundToInt()} ↓${ride.descentM.roundToInt()} m",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row {
                                    TextButton(onClick = {
                                        val track = RideHistory.loadTrack(context, ride.id) ?: return@TextButton
                                        val named = Track(ride.name, track.points, track.waypoints)
                                        RideStore.track = named
                                        RideStore.status = "Route loaded: ${named.name}"
                                        RouteStore.saveTrack(context, named)
                                        onDone()
                                    }) { Text("Ride") }
                                    RideHistory.fileFor(context, ride.id)?.let { file ->
                                        TextButton(onClick = { shareGpx(context, file, ride.name) }) { Text("Export") }
                                        TextButton(onClick = {
                                            exportTarget = file
                                            saveLauncher.launch(ride.name + ".gpx")
                                        }) { Text("Save") }
                                    }
                                    TextButton(onClick = { renameTarget = ride }) { Text("Rename") }
                                    TextButton(onClick = {
                                        RideHistory.delete(context, ride.id)
                                        rides = RideHistory.list(context)
                                    }) { Text("Delete") }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Close") } },
    )

    renameTarget?.let { ride ->
        var text by remember(ride.id) { mutableStateOf(ride.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename ride") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    RideHistory.renameRide(context, ride.id, text)
                    rides = RideHistory.list(context)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val h = TimeUnit.SECONDS.toHours(seconds)
    val m = TimeUnit.SECONDS.toMinutes(seconds) % 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
