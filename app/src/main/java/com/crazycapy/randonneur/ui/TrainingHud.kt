/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
 * Training-focused HUD shown while a ride is active: speed, heart rate,
 * distance covered/remaining, elapsed time and next POI. Only composed when the
 * screen is on and the app is in front.
 */
@Composable
fun TrainingHud() {
    val active by androidx.compose.runtime.rememberUpdatedState(RideStore.active)
    if (!active) return

    val speed = RideStore.speedKmh
    val avg = RideStore.avgSpeedKmh
    val hr = RideStore.hr
    val covered = RideStore.coveredM
    val remaining = RideStore.remainingM
    val elapsed = RideStore.elapsedSec
    val poiName = RideStore.nextPoiName
    val poiM = RideStore.nextPoiM

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 36.dp)
            .background(
                if (RideStore.darkMap) Color(0xCC121212) else Color(0xE8FFFFFF),
                RoundedCornerShape(18.dp),
            )
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BigMetric(
                value = if (speed > 0) formatKmh(speed) else "--",
                unit = "km/h",
                label = "Speed",
                valueColor = if (RideStore.darkMap) Color(0xFF7CC29A) else Color(0xFF2E5D46),
                modifier = Modifier.weight(1f),
            )
            BigMetric(
                value = if (avg > 0) formatKmh(avg) else "--",
                unit = "km/h",
                label = "Avg",
                modifier = Modifier.weight(1f),
            )
            BigMetric(
                value = hr?.toString() ?: "--",
                unit = "bpm",
                label = "Heart",
                icon = if (hr != null) Icons.Filled.Favorite else null,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LineMetric("Ridden", if (covered > 0) Phrases.formatDistance(covered) else "0 meters")
            LineMetric("To go", remaining?.let { Phrases.formatDistance(it) } ?: "--")
            LineMetric("Time", formatTime(elapsed))
        }

        if (poiName != null && poiM != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(
                        if (RideStore.darkMap) Color(0x1A7CC29A) else Color(0x2A2E5D46),
                        RoundedCornerShape(10.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    "Next: $poiName · ${Phrases.formatDistance(poiM)}",
                    color = if (RideStore.darkMap) Color(0xFFBFE8CE) else Color(0xFF2E5D46),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun BigMetric(
    modifier: Modifier = Modifier,
    value: String,
    unit: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    valueColor: Color = if (RideStore.darkMap) Color.White else Color(0xFF1A1A1A),
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = Color(0xFFE53935),
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                value,
                color = valueColor,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                unit,
                modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                color = if (RideStore.darkMap) Color(0xFFBABABA) else Color(0xFF666666),
                fontSize = 11.sp,
            )
        }
        Text(
            label,
            color = if (RideStore.darkMap) Color(0xFFBABABA) else Color(0xFF666666),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun LineMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = if (RideStore.darkMap) Color(0xFFF1F1F1) else Color(0xFF1A1A1A),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            label,
            color = if (RideStore.darkMap) Color(0xFF9A9A9A) else Color(0xFF888888),
            fontSize = 11.sp,
        )
    }
}

private fun formatKmh(kmh: Double): String = ((kmh * 10).roundToInt() / 10.0).toString()

private fun formatTime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        else -> "%02d:%02d".format(m, s)
    }
}