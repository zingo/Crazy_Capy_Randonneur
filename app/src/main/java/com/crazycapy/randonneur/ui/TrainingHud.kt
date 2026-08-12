/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * TrainingHud — 3x2 training stats grid + turn preview card
 *
 *   ┌────────┬────────┬────────┐
 *   │ Speed  │ Covered│ Time   │     Top-left overlay on the map
 *   │ 32.0   │ 12.4 km│ 1:23:45│
 *   ├────────┼────────┼────────┤     TurnPreview sits to the right
 *   │ Avg    │ Left   │ ETA    │
 *   │ 28.5   │ 5.2 km │ 15:30  │
 *   └────────┴────────┴────────┘
 *
 * Reads from RideStore (mutableStateOf) and recomposes on state changes.
 */
package com.crazycapy.randonneur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crazycapy.randonneur.state.RideStore
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import com.crazycapy.randonneur.voice.Phrases
import kotlin.math.roundToInt

/**
 * Top info box: a 2x2 grid of big, glanceable numbers (Speed | dist ridden over
 * Avg | dist total) with the next-turn junction preview beside it whenever one
 * is relevant. Kept on screen after a ride is stopped so the finished stats stay
 * readable; hidden only on a fresh launch.
 */
@Composable
fun TrainingHud(modifier: Modifier = Modifier) {
    // Keep showing the box after the ride ends so the finished stats remain
    // visible, and once a route is loaded (even before starting); suppress it
    // on a fresh launch where there is nothing to show.
    if (!RideStore.active && RideStore.track == null && RideStore.coveredM == 0.0 && RideStore.elapsedSec == 0L) return

    val speed = RideStore.speedKmh
    val avg = RideStore.avgSpeedKmh
    val covered = RideStore.coveredM
    val total = RideStore.totalM.takeIf { it > 0 } ?: RideStore.track?.lengthMeters ?: 0.0
    val leftM = RideStore.remainingM ?: total
    val previewM = RideStore.nextTurnM

    val coveredFmt = Phrases.formatShort(covered)
    val leftFmt = Phrases.formatShort(leftM)

    val elapsed = RideStore.elapsedSec
    val timeFmt = formatElapsed(elapsed)

    var currentTime by remember { mutableStateOf(clockNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = clockNow()
            delay(30_000)
        }
    }

    var etaMode by remember { mutableStateOf(EtaMode.TOTAL_TIME) }
    val etaDisplay = formatEta(etaMode, leftM, avg, elapsed)
    val etaUnit = when (etaMode) {
        EtaMode.TIME_LEFT -> "left"
        EtaMode.TOTAL_TIME -> "total"
        EtaMode.ETA -> "ETA"
    }

    val showPreview = RideStore.nextTurnPopupEnabled &&
        RideStore.nextTurnPopupVisible &&
        RideStore.upcomingRoute.isNotEmpty()

    Row(
        modifier
            .background(
                if (RideStore.darkMap) Color(0xCC121212) else Color(0xE8FFFFFF),
                RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 3x2 stats grid: Speed | Covered | Time / Avg | Left | ETA.
        Column(Modifier.width(IntrinsicSize.Min)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Metric(
                    value = if (speed > 0) formatKmh(speed) else "--",
                    unit = "km/h",
                    valueColor = if (RideStore.darkMap) Color(0xFF7CC29A) else Color(0xFF2E5D46),
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    value = coveredFmt.substringBefore(" "),
                    unit = coveredFmt.substringAfter(" "),
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    value = timeFmt,
                    unit = currentTime,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Metric(
                    value = if (avg > 0) formatKmh(avg) else "--",
                    unit = "km/h",
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    value = leftFmt.substringBefore(" "),
                    unit = leftFmt.substringAfter(" "),
                    modifier = Modifier.weight(1f),
                )
                Metric(
                    value = etaDisplay,
                    unit = etaUnit,
                    onClick = { etaMode = EtaMode.entries[(etaMode.ordinal + 1) % EtaMode.entries.size] },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showPreview) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TurnPreview(Modifier.size(100.dp))
                Text(
                    text = "${(previewM ?: 0.0).coerceAtLeast(0.0).roundToInt()} m",
                    color = if (RideStore.darkMap) Color(0xFFE9E9E9) else Color(0xFF1A1A1A),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Metric(
    modifier: Modifier = Modifier,
    value: String,
    unit: String,
    valueColor: Color = if (RideStore.darkMap) Color.White else Color(0xFF1A1A1A),
    onClick: (() -> Unit)? = null,
) {
    Column(
        (if (onClick != null) modifier.clickable(onClick = onClick) else modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = valueColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            unit,
            color = if (RideStore.darkMap) Color(0xFFBABABA) else Color(0xFF666666),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private fun formatKmh(kmh: Double): String = ((kmh * 10).roundToInt() / 10.0).toString()

private fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else -> "${m}:${s.toString().padStart(2, '0')}"
    }
}

private fun computeEta(leftM: Double, avgKmh: Double): String {
    if (avgKmh <= 0 || leftM <= 0) return "--:--"
    val remainingSeconds = (leftM / (avgKmh / 3.6)).roundToInt().coerceAtLeast(1)
    val eta = Instant.now().plus(Duration.ofSeconds(remainingSeconds.toLong()))
    val zdt = eta.atZone(ZoneId.systemDefault())
    return "${zdt.hour.toString().padStart(2, '0')}:${zdt.minute.toString().padStart(2, '0')}"
}

private enum class EtaMode { TIME_LEFT, TOTAL_TIME, ETA }

private fun formatEta(mode: EtaMode, leftM: Double, avgKmh: Double, elapsedSec: Long): String {
    if (avgKmh <= 0 || leftM <= 0) return "--:--"
    val remainingSec = (leftM / (avgKmh / 3.6)).roundToInt().coerceAtLeast(1).toLong()
    return when (mode) {
        EtaMode.TIME_LEFT -> formatDuration(remainingSec)
        EtaMode.TOTAL_TIME -> formatDuration(elapsedSec + remainingSec)
        EtaMode.ETA -> computeEta(leftM, avgKmh)
    }
}

private fun clockNow(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

private fun formatDuration(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return "${h}:${m.toString().padStart(2, '0')}"
}
