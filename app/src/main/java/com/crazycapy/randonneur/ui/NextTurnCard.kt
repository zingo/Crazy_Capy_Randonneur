/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.nav.Geo
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.voice.Phrases
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * Zoomed-in junction map: the route windowed around the next turn in a fixed
 * north-up orientation (matching the main map), with the rider marker when close.
 * The preview is generated once per turn and kept static while approaching.
 */
@Composable
internal fun TurnPreview(modifier: Modifier = Modifier) {
    val pts = RideStore.upcomingRoute
    val colors = TurnPreviewColors()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (RideStore.darkMap) Color(0xFF1B2A23) else Color(0xFFE7EFE9),
    ) {
        Canvas(Modifier.padding(3.dp)) {
            if (pts.size < 3) {
                drawLine(
                    color = colors.outline,
                    start = Offset(0f, size.height * 0.7f),
                    end = Offset(size.width, size.height * 0.2f),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                )
                return@Canvas
            }
            // The turn vertex: sharpest corner in the window.
            var corner = 1
            var best = -1.0
            for (i in 1 until pts.size - 1) {
                val turn = abs(
                    Geo.bearingDegrees(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second) -
                        Geo.bearingDegrees(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second)
                )
                val angle = if (turn > 180.0) 360.0 - turn else turn
                if (angle > best) {
                    best = angle
                    corner = i
                }
            }
            val lat0 = pts[corner].first
            val lon0 = pts[corner].second
            val cosLat = cos(Math.toRadians(lat0))
            val forward = ArrayList<Float>(pts.size)
            val left = ArrayList<Float>(pts.size)
            // North-up projection, matching the main map (x = east, y = -north).
            for (p in pts) {
                val east = (p.second - lon0) * cosLat * 111_320.0
                val north = (p.first - lat0) * 111_320.0
                forward.add((-north).toFloat())
                left.add(east.toFloat())
            }
            var maxF = 0f
            var maxL = 0f
            for (i in pts.indices) {
                maxF = max(maxF, abs(forward[i]))
                maxL = max(maxL, abs(left[i]))
            }
            val scale = if (maxF > 0f || maxL > 0f) {
                minOf((size.width * 0.46f) / max(maxL, 1f), (size.height * 0.38f) / max(maxF, 1f))
            } else 1f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val path = Path()
            for (i in pts.indices) {
                val x = cx + left[i] * scale
                val y = cy - forward[i] * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = colors.route, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            drawCircle(
                color = colors.vertex,
                radius = 3.5f,
                center = Offset(cx, cy),
            )

            // Rider marker at its projected position when inside the window.
            val rLat = RideStore.lat
            val rLon = RideStore.lon
            if (rLat != null && rLon != null) {
                val re = (rLon - lon0) * cosLat * 111_320.0
                val rn = (rLat - lat0) * 111_320.0
                val rf = (-rn).toFloat()
                val rl = re.toFloat()
                if (abs(rf) <= size.height / 2f && abs(rl) <= size.width / 2f) {
                    val riderX = cx + rl * scale
                    val riderY = cy - rf * scale
                    val tri = Path().apply {
                        moveTo(riderX, riderY - 6f)
                        lineTo(riderX - 4.5f, riderY + 4f)
                        lineTo(riderX + 4.5f, riderY + 4f)
                        close()
                    }
                    drawPath(tri, color = colors.rider)
                }
            }
        }
    }
}

/** Colors for the junction map, read once in composable context (not the draw scope). */
@Composable
private fun TurnPreviewColors(): TurnPreviewPalette = TurnPreviewPalette(
    route = MaterialTheme.colorScheme.primary,
    vertex = MaterialTheme.colorScheme.tertiary,
    rider = MaterialTheme.colorScheme.onPrimary,
    outline = MaterialTheme.colorScheme.outline,
)

private data class TurnPreviewPalette(
    val route: Color,
    val vertex: Color,
    val rider: Color,
    val outline: Color,
)

/** Acknowledgment chip while off the route: dismisses the repeat reminders. */
@Composable
fun OffRouteAck(modifier: Modifier = Modifier) {
    if (!RideStore.active || !RideStore.offRouteActive || RideStore.offRouteAcknowledged) return
    Surface(
        modifier = modifier.clickable { RideStore.offRouteAcknowledged = true },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Off route · ${Phrases.formatDistance(RideStore.offRouteM)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, contentDescription = "Acknowledge, stop reminders", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
        }
    }
}
