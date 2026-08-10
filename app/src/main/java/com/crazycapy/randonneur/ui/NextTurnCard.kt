/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.nav.Geo
import com.crazycapy.randonneur.nav.maneuverFor
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.voice.Phrases
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Small corner popup for the next turn: an arrow, the distance/move, an optional
 * "then …", and a zoomed-in preview of the upcoming route drawn heading-up.
 * Only rendered while a ride is active and the feature is enabled.
 */
@Composable
fun NextTurnCard(modifier: Modifier = Modifier) {
    if (!RideStore.active || !RideStore.nextTurnPopupEnabled) return
    val degrees = RideStore.nextTurnDegrees ?: return

    Card(modifier = modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TurnArrow(degrees, Modifier.size(38.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = when (val m = RideStore.nextTurnM) {
                            null -> ""
                            else -> Phrases.formatDistance(m.coerceAtLeast(0.0))
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = Phrases.maneuverWord(maneuverFor(degrees)),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val afterDeg = RideStore.nextTurnAfterDegrees
                    val afterM = RideStore.nextTurnAfterM
                    if (afterDeg != null && afterM != null) {
                        Text(
                            text = "then ${Phrases.maneuverWord(maneuverFor(afterDeg))} in ${Phrases.formatDistance(afterM)}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            UpcomingRoutePreview(Modifier.fillMaxWidth().height(44.dp))
        }
    }
}

/** Direction arrow, rotated clockwise by the signed turn angle (positive = right). */
@Composable
private fun TurnArrow(degrees: Double, modifier: Modifier) {
    val arrowColor = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(modifier = modifier, shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Canvas(Modifier.padding(7.dp).rotate(degrees.toFloat())) {
            val w = size.width
            val h = size.height
            val arc = Path().apply {
                moveTo(w / 2f, h * 0.05f)
                lineTo(w * 0.78f, h * 0.45f)
                lineTo(w * 0.60f, h * 0.42f)
                lineTo(w * 0.60f, h * 0.95f)
                lineTo(w * 0.40f, h * 0.95f)
                lineTo(w * 0.40f, h * 0.42f)
                lineTo(w * 0.22f, h * 0.45f)
                close()
            }
            drawPath(arc, color = arrowColor)
        }
    }
}

/** Heading-up route preview: upcoming polyline, with the rider at the bottom. */
@Composable
fun UpcomingRoutePreview(modifier: Modifier = Modifier) {
    val pts = RideStore.upcomingRoute
    val figColor = MaterialTheme.colorScheme.primary
    val riderColor = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        if (pts.size < 2) {
            drawLine(
                color = outline,
                start = Offset(0f, size.height * 0.5f),
                end = Offset(size.width, size.height * 0.5f),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
            return@Canvas
        }
        val lat0 = pts.first().first
        val lon0 = pts.first().second
        val heading = Geo.bearingDegrees(lat0, lon0, pts.last().first, pts.last().second)
        val cosLat = cos(Math.toRadians(lat0))
        val forward = ArrayList<Float>(pts.size)
        val left = ArrayList<Float>(pts.size)
        val rad = Math.toRadians(heading)
        val sinH = sin(rad)
        val cosH = cos(rad)
        for (p in pts) {
            val east = (p.second - lon0) * cosLat * 111_320.0
            val north = (p.first - lat0) * 111_320.0
            forward.add(((east * sinH + north * cosH)).toFloat())
            left.add(((east * cosH - north * sinH)).toFloat())
        }
        var maxF = 0f
        var maxL = 0f
        for (i in pts.indices) {
            maxF = max(maxF, kotlin.math.abs(forward[i]))
            maxL = max(maxL, kotlin.math.abs(left[i]))
        }
        val scale = if (maxF > 0f || maxL > 0f) {
            minOf((size.width * 0.5f) / max(maxL, 1f), (size.height * 0.42f) / max(maxF, 1f))
        } else 1f
        val cx = size.width / 2f
        val path = Path()
        for (i in pts.indices) {
            val x = cx + left[i] * scale
            val y = size.height - forward[i] * scale
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = figColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        drawCircle(
            color = riderColor,
            radius = 4f,
            center = Offset(cx, size.height - 2f),
        )
    }
}

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

/** Reusable corner-anchored overlay container with a subtle translucent backdrop. */
@Composable
fun CornerOverlayBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .background(Color.Transparent)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}