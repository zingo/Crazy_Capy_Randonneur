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
import com.crazycapy.randonneur.DEFAULT_LAT
import com.crazycapy.randonneur.DEFAULT_LON
import com.crazycapy.randonneur.RIDING_ZOOM
import com.crazycapy.randonneur.STYLE_DARK
import com.crazycapy.randonneur.STYLE_LIGHT
import com.crazycapy.randonneur.TAG
import com.crazycapy.randonneur.roadBrightenOverrides
import com.crazycapy.randonneur.gpx.Track
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

private const val RECENTER_THRESHOLD = 0.30
private const val ROUTE_FIT_PADDING = 80

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
            style.removeLayer("poi-layer")
            style.removeSource("poi-source")
            val features = t.waypoints.map { wpt ->
                Feature.fromGeometry(Point.fromLngLat(wpt.lon, wpt.lat))
            }
            style.addSource(GeoJsonSource("poi-source", FeatureCollection.fromFeatures(features)))
            style.addLayer(
                CircleLayer("poi-layer", "poi-source").withProperties(
                    PropertyFactory.circleColor(0xFFFFB300.toInt()),
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleOpacity(0.95f),
                )
            )
        }
    }
}

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Rider marker + follow camera                                           │
 * │                                                                         │
 * │  moveAndCenter:                                                         │
 * │    1. Updates the rider arrow position and rotation on the map          │
 * │    2. Recentres the camera when the rider drifts >30% from viewport     │
 * │       centre (project screen-space check).                              │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

internal fun moveAndCenter(
    map: MapLibreMap,
    mapView: MapView,
    lat: Double,
    lon: Double,
    bearing: Double?,
) {
    updateMeMarker(map, lat, lon, bearing)

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
