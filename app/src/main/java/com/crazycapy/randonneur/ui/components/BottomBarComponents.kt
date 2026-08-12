/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import com.crazycapy.randonneur.voice.Phrases

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  ResumeBanner — "Continue last ride" prompt                            │
 * │                                                                         │
 * │  Appears in the bottom bar when a ride was stopped mid-route and the   │
 * │  same route is still loaded.  Offers to resume from where you left off, │
 * │  or dismiss it (clears the saved resume point).                        │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

/** Whether the "continue last ride" banner should show for the loaded route. */
internal fun showResumeOffer(track: Track?): Boolean =
    track != null && RideStore.resumeAlongM != null && RideStore.resumeRouteName != null && RideStore.resumeRouteName == track.name

@Composable
internal fun ResumeBanner(onResume: () -> Unit, onDismiss: () -> Unit) {
    val track = RideStore.track
    var remaining = 0.0
    if (track != null && RideStore.resumeAlongM != null) {
        remaining = if (RideStore.resumeReversed) RideStore.resumeAlongM!! else track.lengthMeters - RideStore.resumeAlongM!!
    }
    val elapsed = RideStore.resumeElapsedSec
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Continue last ride", style = MaterialTheme.typography.titleSmall)
                val sub = buildString {
                    append(Phrases.formatDistance(remaining).replaceFirstChar { it.uppercase() })
                    append(" left")
                    if (elapsed != null && elapsed > 0) {
                        append(" · ")
                        val h = elapsed / 3600
                        val min = (elapsed % 3600) / 60
                        if (h > 0) append("${h}h ")
                        append("${min}min ridden")
                    }
                }
                Text(sub, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onResume) { Text("Resume") }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  GhostControls — in-ride speed × scaling buttons                       │
 * │                                                                         │
 * │  Visible inside the bottom bar only during a ghost ride.  Lets the     │
 * │  user speed up / slow down the simulation and tune the target cruise    │
 * │  speed.  All values are persisted immediately via RouteStore.           │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

@Composable
internal fun GhostControls() {
    val context = LocalContext.current
    val scale = RideStore.ghostTimeScale
    val speed = RideStore.ghostSpeedKmh
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { RideStore.ghostTimeScale = (scale / 1.5).coerceIn(1.0, 600.0); RouteStore.saveSettings(context) },
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) { Text("Slower") }
        Text("x${scale.toInt()}", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { RideStore.ghostTimeScale = (scale * 1.5).coerceIn(1.0, 600.0); RouteStore.saveSettings(context) },
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) { Text("Faster") }
        Text("|", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { RideStore.ghostSpeedKmh = (speed - 2.0).coerceIn(2.0, 60.0); RouteStore.saveSettings(context) },
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) { Text("Slow") }
        Text("${speed.toInt()} km/h", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            onClick = { RideStore.ghostSpeedKmh = (speed + 2.0).coerceIn(2.0, 60.0); RouteStore.saveSettings(context) },
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) { Text("Fast") }
    }
}
