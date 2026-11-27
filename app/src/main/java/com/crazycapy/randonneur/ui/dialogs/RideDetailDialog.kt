/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.dialogs

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.state.RideHistory
import com.crazycapy.randonneur.voice.Phrases
import kotlin.math.roundToInt

@Composable
internal fun RideDetailDialog(
    context: Context,
    ride: RideHistory.SavedRide,
    onClose: () -> Unit,
) {
    val series = remember(ride.id) { RideHistory.loadSeries(context, ride.id) }
    var showHr by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(ride.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${Phrases.formatDistance(ride.distanceM)} · moving ${fmt(ride.durationSec)} · total ${fmt(ride.totalSec)}\n" +
                        "paused ${fmt((ride.totalSec - ride.durationSec).coerceAtLeast(0))} · ${"%.1f".format(ride.avgKmh)} km/h avg · " +
                        "${"%.1f".format(ride.maxKmh)} max\n" +
                        "↑${ride.ascentM.roundToInt()} m · ↓${ride.descentM.roundToInt()} m" +
                        (series.firstOrNull { it.hr != null }?.let { " · HR up to ${series.mapNotNull { p -> p.hr }.maxOrNull() ?: 0} bpm" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (series.size >= 2) {
                    val hrs = series.mapNotNull { it.hr }
                    if (hrs.isNotEmpty()) {
                        TextButton(onClick = { showHr = !showHr }) {
                            Text(if (showHr) "Hide HR" else "Show HR")
                        }
                    }
                    Canvas(Modifier.fillMaxWidth().height(130.dp).padding(top = 12.dp)) {
                        val t0 = series.first().tMs
                        val span = (series.last().tMs - t0).coerceAtLeast(1)
                        val eles = series.mapNotNull { it.eleM }
                        val eMin = (eles.minOrNull() ?: 0.0)
                        val eMax = (eles.maxOrNull() ?: 1.0).let { if (it - eMin < 1.0) eMin + 1.0 else it }
                        val vMax = series.maxOf { it.speedKmh }.coerceAtLeast(1.0)
                        fun x(t: Long) = (t - t0).toFloat() / span * size.width
                        fun yEle(e: Double) = size.height * (1f - ((e - eMin) / (eMax - eMin)).toFloat())
                        if (eles.size >= 2) {
                            val fill = Path()
                            var started = false
                            series.forEach { p ->
                                val e = p.eleM ?: return@forEach
                                val px = x(p.tMs); val py = yEle(e)
                                if (!started) { fill.moveTo(px, py); started = true } else fill.lineTo(px, py)
                            }
                            if (started) {
                                fill.lineTo(size.width, size.height); fill.lineTo(0f, size.height); fill.close()
                                drawPath(fill, Color(0x3333A0FF))
                            }
                            val line = Path()
                            started = false
                            series.forEach { p ->
                                val e = p.eleM ?: return@forEach
                                val px = x(p.tMs); val py = yEle(e)
                                if (!started) { line.moveTo(px, py); started = true } else line.lineTo(px, py)
                            }
                            if (started) drawPath(line, Color(0xFF1E88E5), style = Stroke(width = 2f))
                        }
                        val speed = Path()
                        series.forEachIndexed { i, p ->
                            val px = x(p.tMs); val py = size.height * (1f - (p.speedKmh / vMax).toFloat())
                            if (i == 0) speed.moveTo(px, py) else speed.lineTo(px, py)
                        }
                        drawPath(speed, Color(0xFFFF7F0E), style = Stroke(width = 1.5f))
                        if (showHr && hrs.isNotEmpty()) {
                            val hMin = hrs.min().toDouble()
                            val hMax = hrs.max().toDouble().let { if (it - hMin < 1.0) hMin + 1.0 else it }
                            val hrLine = Path()
                            var first = true
                            series.forEach { p ->
                                val h = p.hr ?: return@forEach
                                val px = x(p.tMs)
                                val py = size.height * (1f - ((h - hMin) / (hMax - hMin)).toFloat())
                                if (first) { hrLine.moveTo(px, py); first = false } else hrLine.lineTo(px, py)
                            }
                            drawPath(hrLine, Color(0xFFE53935), style = Stroke(width = 1.5f))
                        }
                        drawLine(Color(0x22000000), Offset(0f, size.height), Offset(size.width, size.height), 1f)
                    }
                    Text(
                        "elevation ${(series.mapNotNull { it.eleM }.minOrNull() ?: 0.0).toInt()}–${(series.mapNotNull { it.eleM }.maxOrNull() ?: 0.0).toInt()} m · speed max ${"%.0f".format(series.maxOf { it.speedKmh })} km/h" +
                            (if (series.any { it.hr != null }) " · HR red" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("No track samples in this file.", style = MaterialTheme.typography.bodySmall)
                }
                CorosRideBlock(context = context, ride = ride)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

private fun fmt(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
