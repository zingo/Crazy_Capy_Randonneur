/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import org.json.JSONObject

data class OfflinePackMetadata(
    val routeId: String,
    val routeName: String,
    val trackHash: String,
    val style: String,
    val theme: String,
    val bytes: Long?,
    val createdAt: Long?,
    val strategy: OfflineDetailStrategy = OfflineDetailStrategy.STANDARD,
    val tuning: OfflineTuning = OfflineTuning.DEFAULT,
    val segmentIndex: Int = 0,
    val segmentCount: Int = 1,
    val schema: Int = SCHEMA,
) {
    fun toJson(): ByteArray = JSONObject().apply {
        put(KEY_SCHEMA, SCHEMA)
        put(KEY_ROUTE_ID, routeId)
        put(KEY_ROUTE_NAME, routeName)
        put(KEY_TRACK_HASH, trackHash)
        put(KEY_STYLE, style)
        put(KEY_THEME, theme)
        put(KEY_STRATEGY, strategy.name)
        put(KEY_TUNING, tuning.signature)
        put(KEY_SEGMENT_INDEX, segmentIndex)
        put(KEY_SEGMENT_COUNT, segmentCount)
        if (bytes != null) put(KEY_BYTES, bytes) else put(KEY_BYTES, JSONObject.NULL)
        if (createdAt != null) put(KEY_CREATED_AT, createdAt) else put(KEY_CREATED_AT, JSONObject.NULL)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val SCHEMA = 2
        private const val KEY_SCHEMA = "schema"
        private const val KEY_ROUTE_ID = "routeId"
        private const val KEY_ROUTE_NAME = "routeName"
        private const val KEY_TRACK_HASH = "trackHash"
        private const val KEY_STYLE = "style"
        private const val KEY_THEME = "theme"
        private const val KEY_STRATEGY = "strategy"
        private const val KEY_TUNING = "tuning"
        private const val KEY_SEGMENT_INDEX = "segmentIndex"
        private const val KEY_SEGMENT_COUNT = "segmentCount"
        private const val KEY_BYTES = "bytes"
        private const val KEY_CREATED_AT = "createdAt"

        fun parse(raw: ByteArray?): OfflinePackMetadata? {
            if (raw == null || raw.isEmpty()) return null
            val text = String(raw, Charsets.UTF_8).trim()
            if (!text.startsWith("{")) return null
            return runCatching {
                val json = JSONObject(text)
                val routeId = json.optString(KEY_ROUTE_ID).takeIf { it.isNotBlank() } ?: return null
                val routeName = json.optString(KEY_ROUTE_NAME)
                val trackHash = json.optString(KEY_TRACK_HASH).takeIf { it.isNotBlank() } ?: return null
                val style = json.optString(KEY_STYLE).takeIf { it.isNotBlank() } ?: return null
                val theme = json.optString(KEY_THEME).takeIf { it.isNotBlank() } ?: return null
                val strategy = OfflineDetailStrategy.values().firstOrNull {
                    it.name == json.optString(KEY_STRATEGY)
                } ?: OfflineDetailStrategy.STANDARD
                OfflinePackMetadata(
                    routeId = routeId,
                    routeName = routeName,
                    trackHash = trackHash,
                    style = style,
                    theme = theme,
                    bytes = if (json.isNull(KEY_BYTES)) null else json.optLong(KEY_BYTES),
                    createdAt = if (json.isNull(KEY_CREATED_AT)) null else json.optLong(KEY_CREATED_AT),
                    strategy = strategy,
                    tuning = OfflineTuning.parseSignature(json.optString(KEY_TUNING))
                        ?: OfflineTuning.DEFAULT,
                    segmentIndex = json.optInt(KEY_SEGMENT_INDEX, 0),
                    segmentCount = json.optInt(KEY_SEGMENT_COUNT, 1),
                    schema = json.optInt(KEY_SCHEMA, 1),
                )
            }.getOrNull()
        }
    }
}
