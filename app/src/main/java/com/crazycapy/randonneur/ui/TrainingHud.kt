/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * TrainingHud — 2x2 training stats grid + turn preview card
 *
 *   ┌────────┬────────┐
 *   │ Speed  │ Covered│     Top-left overlay on the map
 *   │ 32.0   │ 12.4 km│
 *   ├────────┼────────┤     TurnPreview sits to the right
 *   │ Avg    │ Left   │
 *   │ 28.5   │ 5.2 km │
 *   └────────┴────────┘
 *
 * Reads from RideStore (mutableStateOf) and recomposes on state changes.
 */
package com.crazycapy.randonneur.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crazycapy.randonneur.state.RideStore
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

    val coveredFmt = Phrases.formatDistance(covered)
    val leftFmt = Phrases.formatDistance(leftM)
    val showPreview = RideStore.nextTurnPopupEnabled &&
        RideStore.nextTurnPopupVisible &&
        RideStore.upcomingRoute.isNotEmpty()

    Row(
        modifier
            .background(
                if (RideStore.darkMap) Color(0xCC121212) else Color(0xE8FFFFFF),
                RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 2x2 stats grid: Speed | ridden over Avg | left.
        Column(Modifier.width(IntrinsicSize.Min)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = valueColor,
            fontSize = 30.sp,
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
