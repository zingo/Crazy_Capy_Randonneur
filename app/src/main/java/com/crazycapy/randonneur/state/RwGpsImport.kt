/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RwGpsImport — fetch ridewithgps data by URL/id and rebuild it as a Track
 *
 *   https://ridewithgps.com/routes/<id> | <id>       -> GET /routes/<id>.json    -> Track
 *   https://ridewithgps.com/users/<id> | <id>        -> GET /users/<id>/routes.json -> summaries
 *
 * The public route JSON carries the track points plus its POIs, so importing
 * from the URL gives back brevet checkpoints that the GPX download silently
 * drops. Blocking IO — call from Dispatchers.IO.
 */
package com.crazycapy.randonneur.state

import com.crazycapy.randonneur.gpx.RwGpsParser
import com.crazycapy.randonneur.gpx.Track
import java.net.HttpURLConnection
import java.net.URL

/** Imports ridewithgps routes by full URL or bare numeric id. Based on the public JSON endpoints. */
object RwGpsImport {

    private const val UA = "Crazy Capy Randonneur/0.1 (brevet import)"

    /** Fetch one route (full URL or numeric route id). Returns null on any failure. */
    fun fetchTrack(input: String): Track? {
        val id = extractId(input, "routes") ?: return null
        val conn = open("https://ridewithgps.com/routes/$id.json") ?: return null
        return try {
            if (conn.responseCode != 200) null
            else RwGpsParser.parse(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** List a user's public routes (profile URL or numeric user id). */
    fun listRoutes(input: String): List<RwGpsParser.RouteSummary> {
        val id = extractId(input, "users") ?: return emptyList()
        val conn = open("https://ridewithgps.com/users/$id/routes.json") ?: return emptyList()
        return try {
            if (conn.responseCode != 200) emptyList()
            else RwGpsParser.parseRouteList(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", UA)
        }
    }.getOrNull()

    private fun extractId(input: String, kind: String): String? {
        val t = input.trim()
        Regex("$kind/(\\d+)").find(t)?.let { return it.groupValues[1] }
        return t.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
    }
}
