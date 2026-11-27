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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.coros.CorosActivityDetail
import com.crazycapy.randonneur.coros.CorosController
import com.crazycapy.randonneur.coros.CorosLap
import com.crazycapy.randonneur.coros.CorosSection
import com.crazycapy.randonneur.coros.CorosStatus
import com.crazycapy.randonneur.voice.Phrases
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun CorosDialog(
    context: Context,
    onDismiss: () -> Unit,
) {
    val loading = CorosController.loading
    val error = CorosController.error
    val snapshot = CorosController.snapshot
    val lastSync = CorosController.lastSyncMs
    val latestActivity = CorosController.latestActivity
    val activityError = CorosController.lastActivityError
    val activityLoading = CorosController.activityLoading
    val connected = CorosController.status == CorosStatus.CONNECTED ||
        (CorosController.status == CorosStatus.ERROR && snapshot != null)

    LaunchedEffect(Unit) {
        if (!connected) return@LaunchedEffect
        if (snapshot == null && !loading) {
            CorosController.refresh(context)
        } else if (latestActivity == null && activityError == null && !activityLoading) {
            CorosController.refreshLatestActivity(context)
        }
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
                LatestActivity(
                    detail = latestActivity?.detail,
                    laps = latestActivity?.laps.orEmpty(),
                    error = activityError,
                    loading = activityLoading,
                    onRefresh = { CorosController.refreshLatestActivity(context) },
                )
                snapshot?.let { data ->
                    Section("Recent activities (14 days)", data.activities)
                    Section("Today's HRV", data.hrv)
                    Section("Recovery", data.recovery)
                    Section("Planned workouts", data.schedule)
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
private fun LatestActivity(
    detail: CorosActivityDetail?,
    laps: List<CorosLap>,
    error: String?,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Latest activity", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    when {
        loading -> Text("Loading latest activity…", style = MaterialTheme.typography.bodySmall)
        error != null -> Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        detail == null -> Text("No recent activity.", style = MaterialTheme.typography.bodySmall)
        else -> {
            Text(activityTitle(detail), style = MaterialTheme.typography.bodyMedium)
            activityWhen(detail)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            Text(activityMetrics(detail), style = MaterialTheme.typography.bodySmall)
            detailLine("Avg / max HR", detail.avgHr, detail.maxHr, "bpm")
            detailLine("Avg / max cadence", detail.avgCadence, detail.maxCadence, "rpm")
            detailLine("Avg / max power", detail.avgPowerW, detail.maxPowerW, "W")
            detail.trainingLoad?.let {
                Text("Training load ${formatNumber(it)}", style = MaterialTheme.typography.bodySmall)
            }
            detail.trainingEffect?.let {
                Text("Training effect ${formatNumber(it)}", style = MaterialTheme.typography.bodySmall)
            }
            if (laps.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(lapSummary(laps), style = MaterialTheme.typography.bodySmall)
                laps.take(5).forEach { lap ->
                    Text(lapLine(lap), style = MaterialTheme.typography.bodySmall)
                }
                if (laps.size > 5) {
                    Text("+${laps.size - 5} more laps", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun detailLine(label: String, average: Int?, max: Int?, unit: String) {
    if (average == null && max == null) return
    Text(
        "$label ${average ?: "–"} / ${max ?: "–"} $unit",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun activityTitle(detail: CorosActivityDetail): String =
    detail.title?.takeIf { it.isNotBlank() }
        ?: detail.sportType?.let { "Sport $it" }
        ?: "Completed ride"

private fun activityWhen(detail: CorosActivityDetail): String? =
    detail.startTimestampSec?.let {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it * 1000L))
    } ?: detail.date?.takeIf { it.isNotBlank() }

private fun activityMetrics(detail: CorosActivityDetail): String {
    val parts = mutableListOf<String>()
    detail.durationSec?.let { parts.add(formatDuration(it)) }
    detail.distanceM?.let { parts.add(Phrases.formatDistance(it)) }
    return if (parts.isEmpty()) "No duration/distance reported" else parts.joinToString(" · ")
}

private fun lapSummary(laps: List<CorosLap>): String {
    val parts = mutableListOf("${laps.size} lap" + if (laps.size == 1) "" else "s")
    val hrs = laps.mapNotNull { it.avgHr ?: it.maxHr }
    if (hrs.isNotEmpty()) parts.add("HR ${hrs.min()}–${hrs.max()} bpm")
    val cadences = laps.mapNotNull { it.avgCadence ?: it.maxCadence }
    if (cadences.isNotEmpty()) parts.add("cadence ${cadences.min()}–${cadences.max()} rpm")
    val powers = laps.mapNotNull { it.avgPowerW ?: it.maxPowerW }
    if (powers.isNotEmpty()) parts.add("power ${powers.min()}–${powers.max()} W")
    return "Laps · " + parts.joinToString(" · ")
}

private fun lapLine(lap: CorosLap): String {
    val parts = mutableListOf<String>()
    lap.index?.let { parts.add("Lap $it") }
    lap.durationSec?.let { parts.add(formatDuration(it)) }
    lap.distanceM?.let { parts.add(Phrases.formatDistance(it)) }
    (lap.avgHr ?: lap.maxHr)?.let { parts.add("$it bpm") }
    (lap.avgCadence ?: lap.maxCadence)?.let { parts.add("$it rpm") }
    (lap.avgPowerW ?: lap.maxPowerW)?.let { parts.add("$it W") }
    return parts.joinToString(" · ")
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

private fun formatNumber(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString()
    else rounded.toString()
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
