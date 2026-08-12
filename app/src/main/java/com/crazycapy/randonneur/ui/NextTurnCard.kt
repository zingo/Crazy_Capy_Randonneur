/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.cache.TurnProjection
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import com.crazycapy.randonneur.voice.Phrases
import com.crazycapy.randonneur.STYLE_DARK
import com.crazycapy.randonneur.STYLE_LIGHT
import com.crazycapy.randonneur.roadBrightenOverrides
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory

const val CACHED_IMG_PX = 320

/**
 * Zoomed-in junction map: draws a cached turn image if one was prepared at load
 * time (`RouteCache`), falling back to a live `MapSnapshotter` render of the
 * route around the next turn. Arrow and route line are overlaid in exact
 * geo-projected positions via stored anchor points.
 */
@Composable
internal fun TurnPreview(modifier: Modifier = Modifier) {
    val pts = RideStore.upcomingRoute
    val darkMap = RideStore.darkMap
    val context = LocalContext.current

    // Cached path
    val routeId = remember { RideStore.track?.let { RouteStore.routeId(it.name) } }
    val turnIdx = RideStore.nextTurnIndex
    val cached = remember { mutableStateOf<CachedTurnData?>(null) }
    LaunchedEffect(routeId, darkMap, turnIdx, pts) {
        if (routeId != null && turnIdx != null) {
            val ct = RouteCache.loadTurn(context, routeId, darkMap, turnIdx)
            if (ct != null) cached.value = CachedTurnData(ct.bitmap, ct.anchors)
            else cached.value = null
        } else cached.value = null
    }

    // Live snapshotter fallback
    var snapshot by remember { mutableStateOf<MapSnapshot?>(null) }
    var snapshotterRef by remember { mutableStateOf<MapSnapshotter?>(null) }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val hPx = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(1)

        LaunchedEffect(pts, darkMap, wPx, hPx) {
            snapshotterRef?.cancel()
            snapshotterRef = null
            snapshot = null
            if (cached.value != null) return@LaunchedEffect
            if (pts.size < 2) return@LaunchedEffect
            val builder = LatLngBounds.Builder()
            pts.forEach { builder.include(LatLng(it.first, it.second)) }
            RideStore.lat?.let { lat -> RideStore.lon?.let { lon -> builder.include(LatLng(lat, lon)) } }
            val bounds = builder.build()
            runCatching {
                MapLibre.getInstance(context.applicationContext)
                val snap = MapSnapshotter(
                    context.applicationContext,
                    MapSnapshotter.Options(wPx, hPx)
                        .withStyle(if (darkMap) STYLE_DARK else STYLE_LIGHT)
                        .withRegion(bounds)
                        .withPadding(wPx / 7, hPx / 7, wPx / 7, hPx / 7)
                        .withLogo(false)
                        .withAttribution(false)
                )
                snapshotterRef = snap
                if (darkMap) {
                    snap.setObserver(object : MapSnapshotter.Observer {
                        override fun onDidFinishLoadingStyle() {
                            for ((id, color) in roadBrightenOverrides) {
                                (snap.getLayer(id) as? LineLayer)
                                    ?.setProperties(PropertyFactory.lineColor(color))
                            }
                        }
                        override fun onStyleImageMissing(name: String) {}
                    })
                }
                snap.start({ snapshot = it }, { snapshot = null })
            }
        }
        DisposableEffect(Unit) {
            onDispose { snapshotterRef?.cancel() }
        }

        val route = if (darkMap) Color(0xFF7CC29A) else Color(0xFF2E5D46)
        val arrow = Color(0xFFE53935)
        Surface(
            Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            color = if (darkMap) Color(0xFF121212) else Color(0xFFF0F3F5),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cd = cached.value
                if (cd != null && pts.size >= 2) {
                    val scaleX = size.width / CACHED_IMG_PX
                    val scaleY = size.height / CACHED_IMG_PX
                    drawImage(cd.bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    val path = Path()
                    for (i in pts.indices) {
                        val (px, py) = TurnProjection.project(cd.anchors, pts[i].first, pts[i].second)
                        val x = px * scaleX
                        val y = py * scaleY
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = route, style = Stroke(width = 8f, cap = StrokeCap.Round))
                    val rLat = RideStore.lat
                    val rLon = RideStore.lon
                    if (rLat != null && rLon != null) {
                        val (rx, ry) = TurnProjection.project(cd.anchors, rLat, rLon)
                        val rxs = rx * scaleX
                        val rys = ry * scaleY
                        if (rxs in 0f..size.width && rys in 0f..size.height) {
                            val a = 16f
                            val arrowPath = Path().apply {
                                moveTo(0f, -a)
                                lineTo(-a * 0.55f, a * 0.15f)
                                lineTo(-a * 0.22f, a * 0.15f)
                                lineTo(-a * 0.22f, a * 0.72f)
                                lineTo(a * 0.22f, a * 0.72f)
                                lineTo(a * 0.22f, a * 0.15f)
                                lineTo(a * 0.55f, a * 0.15f)
                                close()
                                translate(Offset(rxs, rys))
                            }
                            rotate((RideStore.bearing ?: 0.0).toFloat(), pivot = Offset(rxs, rys)) {
                                drawPath(arrowPath, color = arrow)
                                drawPath(arrowPath, color = Color.White, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
                            }
                        }
                    }
                } else if (pts.size >= 2) {
                    val s = snapshot ?: return@Canvas
                    drawImage(s.bitmap.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    val path = Path()
                    for (i in pts.indices) {
                        val p = s.pixelForLatLng(LatLng(pts[i].first, pts[i].second))
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path, color = route, style = Stroke(width = 8f, cap = StrokeCap.Round))
                    val rLat = RideStore.lat
                    val rLon = RideStore.lon
                    if (rLat != null && rLon != null) {
                        val r = s.pixelForLatLng(LatLng(rLat, rLon))
                        if (r.x in 0f..size.width && r.y in 0f..size.height) {
                            val a = 16f
                            val arrowPath = Path().apply {
                                moveTo(0f, -a)
                                lineTo(-a * 0.55f, a * 0.15f)
                                lineTo(-a * 0.22f, a * 0.15f)
                                lineTo(-a * 0.22f, a * 0.72f)
                                lineTo(a * 0.22f, a * 0.72f)
                                lineTo(a * 0.22f, a * 0.15f)
                                lineTo(a * 0.55f, a * 0.15f)
                                close()
                                translate(Offset(r.x, r.y))
                            }
                            rotate((RideStore.bearing ?: 0.0).toFloat(), pivot = Offset(r.x, r.y)) {
                                drawPath(arrowPath, color = arrow)
                                drawPath(arrowPath, color = Color.White, style = Stroke(width = 1.5f, cap = StrokeCap.Round))
                            }
                        }
                    }
                } else {
                    drawLine(
                        color = route,
                        start = Offset(0f, size.height * 0.7f),
                        end = Offset(size.width, size.height * 0.2f),
                        strokeWidth = 8f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private data class CachedTurnData(
    val bitmap: android.graphics.Bitmap,
    val anchors: List<com.crazycapy.randonneur.cache.Anchor>,
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
