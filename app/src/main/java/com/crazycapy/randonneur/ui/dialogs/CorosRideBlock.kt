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
import com.crazycapy.randonneur.coros.CorosEnrichment
import com.crazycapy.randonneur.coros.CorosHrZone
import com.crazycapy.randonneur.coros.CorosLap
import com.crazycapy.randonneur.coros.CorosMerge
import com.crazycapy.randonneur.coros.MergedValue
import com.crazycapy.randonneur.coros.MetricSource
import com.crazycapy.randonneur.state.RideHistory
import com.crazycapy.randonneur.voice.Phrases
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun CorosRideBlock(
    context: Context,
    ride: RideHistory.SavedRide,
) {
    var enrichment by remember(ride.id) {
        mutableStateOf(RideHistory.loadEnrichment(context, ride.id))
    }
    val sync = CorosController.rideSyncStates[ride.id]
    val connected = CorosController.isConnected()
    LaunchedEffect(sync?.completedAtMs) {
        enrichment = RideHistory.loadEnrichment(context, ride.id)
    }
    val series = remember(ride.id) { RideHistory.loadSeries(context, ride.id) }
    val appHr = remember(series) { series.mapNotNull { it.hr } }
    val appElevation = ride.ascentM.takeIf { series.any { p -> p.eleM != null } }
    val metrics = remember(enrichment, appHr, appElevation) {
        CorosMerge.merge(appHr, appElevation, null, enrichment)
    }

    Spacer(Modifier.height(10.dp))
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("COROS", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(
            onClick = { CorosController.syncRide(context, ride) },
            enabled = connected && sync?.syncing != true,
        ) {
            Text(if (enrichment == null) "Sync COROS" else "Refresh")
        }
    }
    when {
        sync?.syncing == true -> Text("Matching COROS activity…", style = MaterialTheme.typography.bodySmall)
        sync?.error != null -> Text(
            sync.error!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        else -> sync?.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (!connected) {
        Text(
            "Connect COROS in Settings to sync this ride.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (enrichment == null) {
        if (sync?.message == null && sync?.error == null && sync?.syncing != true) {
            Text("Not synced with COROS.", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val data = enrichment!!
    Text(matchedLine(data), style = MaterialTheme.typography.bodySmall)
    Text(
        "Overlap ${formatDuration(data.overlapSec)} · COROS ${formatClock(data.activityStartSec)}–" +
            "${formatClock(data.activityEndSec)}",
        style = MaterialTheme.typography.bodySmall,
    )
    val contributed = contributedMetrics(metrics)
    if (contributed.isEmpty()) {
        Text(
            "App data already covers the available COROS summary.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        contributed.forEach { line ->
            Text("$line · COROS", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (data.hrZones.isNotEmpty()) {
        Text("HR zones · COROS", style = MaterialTheme.typography.bodySmall)
        data.hrZones.take(6).forEach { zone ->
            Text(zoneLine(zone), style = MaterialTheme.typography.bodySmall)
        }
        if (data.hrZones.size > 6) {
            Text("+${data.hrZones.size - 6} more zones", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (data.laps.isNotEmpty()) {
        Text("Laps · COROS · ${lapSummary(data.laps)}", style = MaterialTheme.typography.bodySmall)
        data.laps.take(4).forEach { lap ->
            Text(lapLine(lap), style = MaterialTheme.typography.bodySmall)
        }
        if (data.laps.size > 4) {
            Text("+${data.laps.size - 4} more laps", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(
        "Source COROS · synced ${formatSync(data.syncedAtMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
    )
}

private fun matchedLine(data: CorosEnrichment): String {
    val name = data.activityName?.takeIf { it.isNotBlank() }
    val date = data.activityDate?.takeIf { it.isNotBlank() }
    val head = name ?: data.activityId ?: "COROS activity"
    return if (date != null) "Matched: $head · $date" else "Matched: $head"
}

private fun contributedMetrics(metrics: com.crazycapy.randonneur.coros.RideMetrics): List<String> {
    val out = mutableListOf<String>()
    fun int(label: String, value: MergedValue<Int>?) {
        if (value?.source == MetricSource.COROS && value.value != null) {
            out.add("$label ${value.value}")
        }
    }
    fun dbl(label: String, value: MergedValue<Double>?, unit: String = "") {
        if (value?.source == MetricSource.COROS && value.value != null) {
            out.add("$label ${formatNumber(value.value)}$unit")
        }
    }
    int("avg HR", metrics.avgHr)
    int("max HR", metrics.maxHr)
    int("avg cadence", metrics.avgCadence)
    int("max cadence", metrics.maxCadence)
    int("avg power", metrics.avgPowerW)
    int("max power", metrics.maxPowerW)
    dbl("training load", metrics.trainingLoad)
    dbl("aerobic TE", metrics.aerobicTrainingEffect)
    dbl("anaerobic TE", metrics.anaerobicTrainingEffect)
    dbl("calories", metrics.calories)
    dbl("elevation gain", metrics.elevationGainM, " m")
    return out
}

private fun zoneLine(zone: CorosHrZone): String {
    val parts = mutableListOf<String>()
    parts.add(zone.label ?: zone.zone?.let { "Zone $it" } ?: "Zone")
    zone.minBpm?.let { min ->
        zone.maxBpm?.let { max -> parts.add("$min–$max bpm") } ?: parts.add("$min+ bpm")
    }
    zone.seconds?.let { parts.add(formatDuration(it.roundToInt().toLong())) }
    zone.percent?.let { parts.add("${formatNumber(it)}%") }
    return parts.joinToString(" · ")
}

private fun lapSummary(laps: List<CorosLap>): String {
    val parts = mutableListOf("${laps.size} lap" + if (laps.size == 1) "" else "s")
    val hrs = laps.mapNotNull { it.avgHr ?: it.maxHr }
    if (hrs.isNotEmpty()) parts.add("HR ${hrs.min()}–${hrs.max()} bpm")
    val cadences = laps.mapNotNull { it.avgCadence ?: it.maxCadence }
    if (cadences.isNotEmpty()) parts.add("cadence ${cadences.min()}–${cadences.max()} rpm")
    val powers = laps.mapNotNull { it.avgPowerW ?: it.maxPowerW }
    if (powers.isNotEmpty()) parts.add("power ${powers.min()}–${powers.max()} W")
    return parts.joinToString(" · ")
}

private fun lapLine(lap: CorosLap): String {
    val parts = mutableListOf<String>()
    lap.index?.let { parts.add("Lap $it") }
    lap.durationSec?.let { parts.add(formatDuration(it.roundToInt().toLong())) }
    lap.distanceM?.let { parts.add(Phrases.formatDistance(it)) }
    lap.avgHr?.let { parts.add("$it bpm") }
    lap.avgCadence?.let { parts.add("$it rpm") }
    lap.avgPowerW?.let { parts.add("$it W") }
    return parts.joinToString(" · ")
}

private fun formatDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

private fun formatClock(epochSec: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSec * 1000L))

private fun formatSync(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatNumber(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString()
    else rounded.toString()
}
