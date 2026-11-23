/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.json.JSONObject
import java.time.Instant

data class CorosToken(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMs: Long,
    val clientId: String,
    val issuer: String,
    val region: CorosRegion,
) {
    fun isExpired(nowMs: Long, skewMs: Long = 60_000L): Boolean = nowMs + skewMs >= expiresAtMs
}

data class CorosLoginSession(
    val sessionId: String,
    val pollToken: String,
    val loginUrl: String,
    val expiresAtMs: Long?,
    val intervalSeconds: Int,
)

data class CorosClaim(
    val status: String,
    val loginTicket: String?,
)

data class CorosAuthorizeResult(
    val code: String,
    val verifier: String,
)

class CorosAuthException(message: String) : Exception(message)

object CorosAuthParser {

    fun parseClientId(body: String): String? = runCatching {
        val json = JSONObject(body)
        json.optString("client_id").takeIf { it.isNotBlank() }
            ?: json.optString("clientId").takeIf { it.isNotBlank() }
    }.getOrNull()

    fun parseLoginSession(body: String, nowMs: Long): CorosLoginSession? = runCatching {
        val json = JSONObject(body)
        val sessionId = json.optString("sessionId").takeIf { it.isNotBlank() }
            ?: json.optString("session_id").takeIf { it.isNotBlank() }
            ?: return null
        val pollToken = json.optString("pollToken").takeIf { it.isNotBlank() }
            ?: json.optString("poll_token").takeIf { it.isNotBlank() }
            ?: return null
        val loginUrl = json.optString("loginUrl").takeIf { it.isNotBlank() }
            ?: json.optString("login_url").takeIf { it.isNotBlank() }
            ?: return null
        val expires = json.opt("expiresAt") ?: json.opt("expires_at")
        val interval = when (val value = json.opt("intervalSeconds") ?: json.opt("interval_seconds")) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 2
            else -> 2
        }
        CorosLoginSession(
            sessionId = sessionId,
            pollToken = pollToken,
            loginUrl = loginUrl,
            expiresAtMs = parseEpochMs(expires),
            intervalSeconds = interval.coerceIn(1, 30),
        )
    }.getOrNull()

    fun parseClaim(body: String): CorosClaim? = runCatching {
        val root = JSONObject(body)
        val json = root.optJSONObject("data") ?: root
        val status = json.optString("status").takeIf { it.isNotBlank() }
            ?: json.optString("state").takeIf { it.isNotBlank() }
            ?: return null
        val ticket = json.optString("loginTicket").takeIf { it.isNotBlank() }
            ?: json.optString("login_ticket").takeIf { it.isNotBlank() }
            ?: json.optString("ticket").takeIf { it.isNotBlank() }
        CorosClaim(status, ticket)
    }.getOrNull()

    fun parseToken(
        body: String,
        nowMs: Long,
        clientId: String,
        issuer: String,
        region: CorosRegion,
        previousRefreshToken: String? = null,
    ): CorosToken? = runCatching {
        val json = JSONObject(body)
        val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: previousRefreshToken
        val expiresIn = when (val value = json.opt("expires_in")) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 3600L
            else -> 3600L
        }
        CorosToken(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = nowMs + expiresIn.coerceAtLeast(0L) * 1000L,
            clientId = clientId,
            issuer = issuer,
            region = region,
        )
    }.getOrNull()

    fun parseEpochMs(value: Any?): Long? = when (value) {
        is Number -> normalizeEpoch(value.toLong())
        is String -> value.toLongOrNull()?.let(::normalizeEpoch)
            ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        else -> null
    }

    private fun normalizeEpoch(value: Long): Long =
        if (value in 1L..2_000_000_000L) value * 1000L else value
}
