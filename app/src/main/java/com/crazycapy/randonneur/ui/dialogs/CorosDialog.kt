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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.coros.CorosController
import com.crazycapy.randonneur.coros.CorosLiveStatus
import com.crazycapy.randonneur.coros.CorosSection
import com.crazycapy.randonneur.coros.CorosStatus
import com.crazycapy.randonneur.state.RideStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CorosDialog(
    context: Context,
    onDismiss: () -> Unit,
) {
    val loading = CorosController.loading
    val error = CorosController.error
    val snapshot = CorosController.snapshot
    val lastSync = CorosController.lastSyncMs
    val connected = CorosController.status == CorosStatus.CONNECTED ||
        (CorosController.status == CorosStatus.ERROR && snapshot != null)
    var appHint by remember { mutableStateOf<String?>(null) }
    val liveStatus = RideStore.corosLiveStatus
    val todayWorkout = CorosController.todayWorkout ?: RideStore.corosTodayWorkout

    LaunchedEffect(Unit) {
        if (connected && snapshot == null && !loading) CorosController.refresh(context)
    }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("COROS", Modifier.weight(1f))
                TextButton(
                    onClick = { CorosController.refresh(context) },
                    enabled = connected && !loading,
                ) { Text("Refresh") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(statusText(), style = MaterialTheme.typography.bodyMedium)
                if (liveStatus != CorosLiveStatus.IDLE) {
                    Text(
                        if (liveStatus == CorosLiveStatus.ACTIVE) "Live: reading COROS activity" else "Live: unavailable",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                todayWorkout?.let {
                    Text("Today: $it", style = MaterialTheme.typography.bodySmall)
                }
                lastSync?.let {
                    Text(
                        "Last sync ${formatSyncTime(it)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (loading) {
                    Spacer(Modifier.height(8.dp))
                    Text("Loading…", style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!connected) {
                    Spacer(Modifier.height(8.dp))
                    Text("Connect to COROS in Settings first.", style = MaterialTheme.typography.bodySmall)
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            appHint = if (CorosController.openCorosApp(context)) {
                                null
                            } else {
                                "COROS app is not installed"
                            }
                        },
                    ) { Text("Open COROS app") }
                }
                Text(
                    "Start a planned workout on your watch; Crazy Capy cannot start it remotely.",
                    style = MaterialTheme.typography.bodySmall,
                )
                appHint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                snapshot?.let { data ->
                    Section("Recent activities (14 days)", data.activities)
                    Section("Today's HRV", data.hrv)
                    Section("Recovery", data.recovery)
                    Section("Today's planned workouts", data.schedule)
                }
                if (snapshot == null && !loading && error == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("No COROS data yet.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Section(title: String, section: CorosSection) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    when {
        section.error != null -> Text(
            section.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        section.lines.isEmpty() -> Text("No data", style = MaterialTheme.typography.bodySmall)
        else -> section.lines.forEach { block ->
            block.lines().forEach { line ->
                if (line.isNotBlank()) Text(line.trimEnd(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun statusText(): String = when (CorosController.status) {
    CorosStatus.NOT_CONNECTED -> "Not connected"
    CorosStatus.WAITING -> "Waiting for login"
    CorosStatus.CONNECTED -> "Connected"
    CorosStatus.ERROR -> "Error"
}

private fun formatSyncTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
