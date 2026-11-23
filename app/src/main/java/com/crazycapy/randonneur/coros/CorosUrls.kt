/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

object CorosUrls {
    const val REDIRECT_URI = "http://127.0.0.1:43123/callback"
    const val SCOPE = "openid offline_access mcp.tools"
    const val PROTOCOL_VERSION = "2025-06-18"

    data class Callback(
        val code: String?,
        val state: String?,
        val error: String?,
    )

    fun register(issuer: String): String = "$issuer/connect/register"

    fun loginSessions(issuer: String): String = "$issuer/api/v1/cli/login-sessions"

    fun claim(issuer: String, sessionId: String): String =
        "$issuer/api/v1/cli/login-sessions/${encodeQuery(sessionId)}/claim"

    fun token(issuer: String): String = "$issuer/oauth2/token"

    fun mcp(issuer: String): String = "$issuer/mcp"

    fun resource(issuer: String): String = "$issuer/mcp"

    fun authorize(
        issuer: String,
        clientId: String,
        codeChallenge: String,
        state: String,
        loginTicket: String,
    ): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPE,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "resource" to resource(issuer),
            "state" to state,
            "login_ticket" to loginTicket,
        )
        return "$issuer/oauth2/authorize?" + params.joinToString("&") {
            "${encodeQuery(it.first)}=${encodeQuery(it.second)}"
        }
    }

    fun parseCallback(location: String): Callback? {
        val uri = runCatching { URI(location) }.getOrNull() ?: return null
        val raw = uri.rawQuery ?: uri.rawFragment ?: return null
        if (raw.isBlank()) return null
        val params = raw.split("&").mapNotNull { part ->
            val index = part.indexOf('=')
            if (index < 0) {
                if (part.isBlank()) null else decodeQuery(part) to ""
            } else {
                decodeQuery(part.substring(0, index)) to decodeQuery(part.substring(index + 1))
            }
        }.toMap()
        return Callback(
            code = params["code"]?.takeIf { it.isNotBlank() },
            state = params["state"]?.takeIf { it.isNotBlank() },
            error = params["error"]?.takeIf { it.isNotBlank() },
        )
    }

    fun isLoopbackCallback(location: String): Boolean =
        location.startsWith("$REDIRECT_URI?") || location.startsWith("$REDIRECT_URI#")

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private fun decodeQuery(value: String): String =
        URLDecoder.decode(value.replace("+", " "), Charsets.UTF_8)
}
