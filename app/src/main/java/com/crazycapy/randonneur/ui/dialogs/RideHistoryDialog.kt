/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.dialogs

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.crazycapy.randonneur.voice.Phrases
import java.util.concurrent.TimeUnit

@Composable
internal fun RideHistoryDialog(
    context: Context,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var rides by remember { mutableStateOf(RideHistory.list(context)) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Ride history") },
        text = {
            Column {
                if (rides.isEmpty()) {
                    Text("No saved rides yet. Finish a GPS ride and it will show up here.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    rides.forEach { ride ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(ride.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${Phrases.formatDistance(ride.distanceM)} · ${formatDuration(ride.durationSec)} · ${"%.1f".format(ride.avgKmh)} km/h avg · ${"%.1f".format(ride.maxKmh)} max",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = {
                                val track = RideHistory.loadTrack(context, ride.id) ?: return@TextButton
                                val named = Track(ride.name, track.points, track.waypoints)
                                RideStore.track = named
                                RideStore.status = "Route loaded: ${named.name}"
                                RouteStore.saveTrack(context, named)
                                onDone()
                            }) { Text("Use") }
                            TextButton(onClick = {
                                RideHistory.delete(context, ride.id)
                                rides = RideHistory.list(context)
                            }) { Text("Delete") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Close") } },
    )
}

private fun formatDuration(seconds: Long): String {
    val h = TimeUnit.SECONDS.toHours(seconds)
    val m = TimeUnit.SECONDS.toMinutes(seconds) % 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
