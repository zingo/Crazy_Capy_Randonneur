/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Constants — shared literals used across packages.                     │
 * │  Every magic number or URL that appears in more than one file lives     │
 * │  here so there is a single place to tweak it.                          │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

/** OpenFreeMap tile style URLs (dark first — saves OLED battery). */
internal const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
internal const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/liberty"

/** Fallback camera position when no route or last-known location exists. */
internal const val DEFAULT_LAT = 59.329
internal const val DEFAULT_LON = 18.069

/** Tag for Android logcat (classic log, no Timber dependency wanted). */
internal const val TAG = "CrazyCapyRandonneur"

/** Requested GPS fix interval in milliseconds. */
internal const val GPS_INTERVAL_MS = 3000L

/** Riding zoom level for the main map. */
internal const val RIDING_ZOOM = 15.0

/** Km/h to m/s conversion factor. */
internal const val KMH_TO_MS = 3.6

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Turn preview image cache                                              │
 * │                                                                         │
 * │  CACHED_IMG_PX — fixed pixel size for the pre-rendered turn snapshots.  │
 * │  The HUD's TurnPreview is 100 dp, which on a ~2.625x display is 262 px │
 * │  — 320 px gives plenty of headroom for crisp downscaling.              │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
internal const val CACHED_IMG_PX = 320

/*
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Road-colour overrides  (dark style only)                              │
 * │                                                                         │
 * │  OpenFreeMap's "dark" style paints roads nearly black on a black        │
 * │  background, which is invisible on OLED in daylight.  These overrides   │
 * │  lift the road network to visible greys while keeping the dark         │
 * │  aesthetic.  Applied to both the main map and the turn-preview         │
 * │  snapshot after the style finishes loading.                            │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
internal val roadBrightenOverrides = mapOf(
    "highway_major_inner" to 0xFF838383.toInt(),
    "highway_major_casing" to 0xFF4A4A4A.toInt(),
    "highway_major_subtle" to 0xFF5A5A5A.toInt(),
    "highway_motorway_inner" to 0xFF8E8E8E.toInt(),
    "highway_motorway_casing" to 0xFF505050.toInt(),
    "highway_motorway_subtle" to 0xFF5A5A5A.toInt(),
    "highway_minor" to 0xFF6E6E6E.toInt(),
    "highway_path" to 0xFF3A3A3C.toInt(),
)
