/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.helpers

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.crazycapy.randonneur.DEFAULT_LAT
import com.crazycapy.randonneur.DEFAULT_LON
import com.crazycapy.randonneur.RIDING_ZOOM
import com.crazycapy.randonneur.STYLE_DARK
import com.crazycapy.randonneur.STYLE_LIGHT
import com.crazycapy.randonneur.TAG
import com.crazycapy.randonneur.roadBrightenOverrides
import com.crazycapy.randonneur.gpx.Track
import com.crazycapy.randonneur.gpx.Waypoint
import com.crazycapy.randonneur.nav.Geo
import com.crazycapy.randonneur.radar.RadarVehicle
import com.crazycapy.randonneur.radar.RadarVehicleSize
import com.crazycapy.randonneur.state.RideStore
import com.google.gson.JsonObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

private const val RECENTER_THRESHOLD = 0.30
private const val ROUTE_FIT_PADDING = 80
private const val MAX_POI_CHIPS = 256

private const val UNMEASURED_TARGET_COLOR = "#9E9E9E"

/**
 * Grey when the target's class or lane position is a default rather than a
 * reading, so it does not read as a car measured into the rider's lane. A
 * range-only radar sends its lateral offset as zero, and zero is exactly the
 * middle of the lane, so the dot's position is a claim this app cannot make.
 * Colour only says so; it does not fix it.
 */
internal fun targetColor(target: RadarVehicle): String =
    if (!target.sizeKnown || !target.lateralKnown) {
        UNMEASURED_TARGET_COLOR
    } else when (target.size) {
        RadarVehicleSize.CAR -> "#FF9800"
        RadarVehicleSize.TRUCK -> "#E53935"
        RadarVehicleSize.BIKE -> "#42A5F5"
    }

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  MapActions — stateless helper functions operating on a MapLibreMap.    │
 * │                                                                         │
 * │  Every function here takes a [MapLibreMap] (plus optional args) and     │
 * │  mutates the map style, camera or layer state.  None of them hold any   │
 * │  Android Context or lifecycle — they are pure map operations.           │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

/** Lighten the road network of OpenFreeMap's dark style for better OLED contrast. */
internal fun brightenDarkRoads(style: Style) {
    for ((id, color) in roadBrightenOverrides) {
        (style.getLayer(id) as? LineLayer)?.setProperties(PropertyFactory.lineColor(color))
    }
}

/** Replace the route polyline and waypoint markers on the map. */
internal fun refreshRoute(map: MapLibreMap, track: Track?) {
    val t = track ?: return
    runCatching {
        val style = map.getStyle() ?: return
        style.removeLayer("route-layer")
        style.removeSource("route-source")
        val line = LineString.fromLngLats(t.points.map { Point.fromLngLat(it.lon, it.lat) })
        style.addSource(GeoJsonSource("route-source", line))
        style.addLayer(
            LineLayer("route-layer", "route-source").withProperties(
                PropertyFactory.lineColor(0xFF7CC29A.toInt()),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.95f),
            )
        )

        if (t.waypoints.isNotEmpty()) {
            style.removeLayer("poi-label-layer")
            style.removeLayer("poi-layer")
            style.removeSource("poi-source")
            var i = 0
            while (i < MAX_POI_CHIPS && style.getImage("poi-chip-$i") != null) {
                style.removeImage("poi-chip-$i")
                i++
            }
            val poiSource = GeoJsonSource("poi-source")
            style.addSource(poiSource)
            poiSource.setGeoJson(buildPoiGeoJson(t.waypoints))
            t.waypoints.forEachIndexed { i, wpt -> if (style.getImage("poi-chip-$i") == null) style.addImage("poi-chip-$i", drawPoiChip(wpt.name)) }
            style.addLayer(
                CircleLayer("poi-layer", "poi-source").withProperties(
                    PropertyFactory.circleColor(0xFFFFB300.toInt()),
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleOpacity(0.95f),
                )
            )
            style.addLayer(
                SymbolLayer("poi-label-layer", "poi-source").withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconSize(0.55f),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
            )
        }
    }
}

/** Serializes waypoints to a GeoJSON FeatureCollection string for the POI source. */
private fun buildPoiGeoJson(waypoints: List<Waypoint>): String {
    val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
    for ((i, wpt) in waypoints.withIndex()) {
        if (i > 0) sb.append(',')
        sb.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
        sb.append(wpt.lon).append(',').append(wpt.lat)
        sb.append("]},\"properties\":{\"name\":\"")
        sb.append(escapeJson(wpt.name))
        sb.append("\",\"icon\":\"poi-chip-").append(i).append("\"")
        if (wpt.description != null) {
            sb.append(",\"desc\":\"")
            sb.append(escapeJson(wpt.description)).append('"')
        }
        sb.append("}}")
    }
    sb.append("]}")
    return sb.toString()
}

private fun escapeJson(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

/** Renders a checkpoint name into a small amber-on-dark chip image for the map label. */
private fun drawPoiChip(name: String): Bitmap {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
    }
    val padX = 12f
    val textW = textPaint.measureText(name)
    val w = (textW + padX * 2 + 8).toInt()
    val h = 52
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD0000000.toInt() }
    c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 12f, 12f, pill)
    c.drawText(name, padX, 37f, textPaint)
    return bmp
}

/**
 * Nearest waypoint within a tap's reach of `lat`/`lon`, or null. Tolerance is
 * small so accidental taps near the route line don't open a checkpoint popup.
 */
internal fun nearestWaypoint(track: Track?, lat: Double, lon: Double, maxMeters: Double = 60.0): Waypoint? {
    val t = track ?: return null
    var best: Waypoint? = null
    var bestD = maxMeters
    for (w in t.waypoints) {
        val d = Geo.distanceMeters(lat, lon, w.lat, w.lon)
        if (d <= bestD) {
            bestD = d
            best = w
        }
    }
    return best
}

/** Tap radius in meters matching a ~25 px screen tolerance at `zoom`, clamped 60..2000 m. */
internal fun tapToleranceMeters(zoom: Double, lat: Double): Double {
    val metersPerPixel = 156543.03392 * kotlin.math.cos(lat * Math.PI / 180.0) / 2.0.pow(zoom)
    return (25 * metersPerPixel).coerceIn(60.0, 2000.0)
}

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Rider marker + follow camera                                           │
 * │                                                                         │
 * │  moveAndCenter:                                                         │
 * │    1. Updates the rider arrow position and rotation on the map          │
 * │    2. When `follow` (default), recentres the camera as the rider        │
 * │       drifts >30% from viewport centre (project screen-space check).   │
 * │       When the user has taken the camera (follow = false) it only       │
 * │       moves the rider marker.                                           │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

internal fun moveAndCenter(
    map: MapLibreMap,
    mapView: MapView,
    lat: Double,
    lon: Double,
    bearing: Double?,
    follow: Boolean = true,
) {
    updateMeMarker(map, lat, lon, bearing)
    if (!follow) return

    val center = map.cameraPosition?.target
    if (center == null || mapView.width <= 0 || mapView.height <= 0) {
        map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon)))
        return
    }
    val rider = map.getProjection().toScreenLocation(LatLng(lat, lon))
    val middle = map.getProjection().toScreenLocation(center)
    val dist = hypot((rider.x - middle.x).toDouble(), (rider.y - middle.y).toDouble())
    val threshold = RECENTER_THRESHOLD * min(mapView.width, mapView.height)
    if (dist > threshold) {
        map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon)))
    }
}

/**
 * Arrow marker that rotates to the current course.
 * Drawn as a [SymbolLayer] with a small triangle+shaft bitmap,
 * rotated via the `"bearing"` feature property.
 */
private fun updateMeMarker(map: MapLibreMap, lat: Double, lon: Double, bearing: Double?) {
    runCatching {
        val style = map.getStyle() ?: return
        val props = JsonObject().apply { addProperty("bearing", bearing ?: 0.0) }
        val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat), props)
        val existing = style.getSource("me-source") as? GeoJsonSource
        if (existing == null) {
            style.addImage("me-image", headingArrowBitmap())
            style.addSource(GeoJsonSource("me-source", feature))
            style.addLayer(
                SymbolLayer("me-layer", "me-source").withProperties(
                    PropertyFactory.iconImage("me-image"),
                    PropertyFactory.iconSize(0.9f),
                    PropertyFactory.iconRotate(Expression.get("bearing")),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconAllowOverlap(true),
                )
            )
        } else {
            existing.setGeoJson(feature)
        }
    }
}

/** Red rider-direction arrow pointing north; rotated by the SymbolLayer via bearing. */
private fun headingArrowBitmap(): Bitmap {
    val size = 96
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val arrowRed = Color.rgb(0xE5, 0x39, 0x35)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = arrowRed }
    val shaft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = arrowRed
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    val cx = size / 2f
    val arrow = Path().apply {
        moveTo(cx, 8f)
        lineTo(cx - 14f, 40f)
        lineTo(cx - 6f, 40f)
        lineTo(cx - 6f, 88f)
        lineTo(cx + 6f, 88f)
        lineTo(cx + 6f, 40f)
        lineTo(cx + 14f, 40f)
        close()
    }
    canvas.drawPath(arrow, outline)
    canvas.drawPath(arrow, fill)
    canvas.drawLine(cx, 88f, cx, 40f, shaft)
    return bmp
}

/** Fit the camera to the route bounds with padding. */
internal fun fitRoute(map: MapLibreMap, track: Track?) {
    val t = track ?: return
    if (t.points.size < 2) return
    if (t.lengthMeters < 10.0) {
        val p = t.points.first()
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(p.lat, p.lon))
            .zoom(RIDING_ZOOM)
            .build()
        return
    }
    val builder = LatLngBounds.Builder()
    for (p in t.points) builder.include(LatLng(p.lat, p.lon))
    map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), ROUTE_FIT_PADDING))
}

/** Centre the map on the rider position at a riding zoom level. */
internal fun centerOnRider(map: MapLibreMap) {
    val lat = RideStore.lat ?: return
    val lon = RideStore.lon ?: return
    map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), RIDING_ZOOM))
}

/** Centre on the best known location (used only when starting GPS). */
@SuppressLint("MissingPermission")
internal fun MapLibreMap.centerOnLastKnown(context: android.content.Context) {
    runCatching {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val best = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        )
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (best != null) {
            animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(best.latitude, best.longitude), RIDING_ZOOM))
        }
    }
}

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Idle-position dot                                                      │
 * │                                                                         │
 * │  A small blue circle with a white ring that follows the user's          │
 * │  last-known position while no route is loaded and the screen is on.     │
 * │  Gives a "where am I?" reference without needing to start navigation.   │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

internal fun updateIdleDot(map: MapLibreMap, lat: Double?, lon: Double?, show: Boolean) {
    runCatching {
        val style = map.getStyle() ?: return
        style.removeLayer("me-dot-core")
        style.removeLayer("me-dot-ring")
        style.removeSource("me-dot-source")
        if (!show || lat == null || lon == null) return
        val point = Point.fromLngLat(lon, lat)
        style.addSource(GeoJsonSource("me-dot-source", point))
        style.addLayer(
            CircleLayer("me-dot-ring", "me-dot-source").withProperties(
                PropertyFactory.circleColor(0xFFFFFFFF.toInt()),
                PropertyFactory.circleRadius(12f),
                PropertyFactory.circleOpacity(0.9f),
            )
        )
        style.addLayer(
            CircleLayer("me-dot-core", "me-dot-source").withProperties(
                PropertyFactory.circleColor(0xFF2196F3.toInt()),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleOpacity(1f),
                PropertyFactory.circleStrokeColor(0xFF1565C0.toInt()),
                PropertyFactory.circleStrokeWidth(2f),
            )
        )
    }
}

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Simulated rear-radar traffic layer                                     │
 * │                                                                         │
 * │  updateRadarTargets redraws the colour-coded dots behind the rider      │
 * │  during a ghost ride or a live rear-radar stream.  Both feed the same   │
 * │  [RadarVehicle] model.                                                  │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

internal fun updateRadarTargets(map: MapLibreMap, targets: List<RadarVehicle>, show: Boolean) {
    runCatching {
        val style = map.getStyle() ?: return
        if (!show || targets.isEmpty()) {
            style.removeLayer("radar-layer")
            style.removeSource("radar-source")
            return
        }
        val features = targets.map { t ->
            val props = JsonObject().apply { addProperty("color", targetColor(t)) }
            Feature.fromGeometry(Point.fromLngLat(t.lon, t.lat), props)
        }
        val existing = style.getSource("radar-source") as? GeoJsonSource
        if (existing == null) {
            style.addSource(GeoJsonSource("radar-source", FeatureCollection.fromFeatures(features)))
            style.addLayer(
                CircleLayer("radar-layer", "radar-source").withProperties(
                    PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleRadius(Expression.toNumber(Expression.get("radius"))),
                    PropertyFactory.circleOpacity(Expression.toNumber(Expression.get("opacity"))),
                    PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
                    PropertyFactory.circleStrokeWidth(1.5f),
                )
            )
        } else {
            existing.setGeoJson(FeatureCollection.fromFeatures(features))
        }
    }
}

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Style loading                                                          │
 * │                                                                         │
 * │  loadStyle sets an OpenFreeMap URL and applies the dark road brightening│
 * │  if needed.  Called once per activity resume (or dark/light toggle).    │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

internal fun loadMapStyle(map: MapLibreMap, dark: Boolean, onReady: (Style) -> Unit) {
    map.setStyle(if (dark) STYLE_DARK else STYLE_LIGHT) { style ->
        if (dark) brightenDarkRoads(style)
        onReady(style)
    }
}
