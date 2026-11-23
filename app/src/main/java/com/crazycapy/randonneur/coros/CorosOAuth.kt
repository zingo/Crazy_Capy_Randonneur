/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

object CorosOAuth {

    suspend fun register(issuer: String): String {
        val body = JSONObject().apply {
            put("client_name", "Crazy Capy Randonneur")
            put("redirect_uris", JSONArray().put(CorosUrls.REDIRECT_URI))
            put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            put("response_types", JSONArray().put("code"))
            put("scope", CorosUrls.SCOPE)
            put("token_endpoint_auth_method", "none")
        }.toString()
        val response = CorosHttp.postJson(
            CorosUrls.register(issuer),
            body,
            mapOf("Accept" to "application/json"),
        )
        if (response.code !in 200..299) {
            throw CorosAuthException("COROS registration failed (${response.code})")
        }
        return CorosAuthParser.parseClientId(response.body)
            ?: throw CorosAuthException("COROS registration returned no client id")
    }

    suspend fun createLoginSession(issuer: String, clientId: String): CorosLoginSession {
        val body = JSONObject().put("clientId", clientId).toString()
        val response = CorosHttp.postJson(
            CorosUrls.loginSessions(issuer),
            body,
            mapOf("Accept" to "application/json"),
        )
        if (response.code !in 200..299) {
            throw CorosAuthException("Could not start COROS login (${response.code})")
        }
        return CorosAuthParser.parseLoginSession(response.body, System.currentTimeMillis())
            ?: throw CorosAuthException("COROS login session was incomplete")
    }

    suspend fun awaitLoginTicket(
        issuer: String,
        session: CorosLoginSession,
    ): String {
        val deadline = session.expiresAtMs ?: (System.currentTimeMillis() + 10 * 60_000L)
        while (true) {
            val response = CorosHttp.postJson(
                CorosUrls.claim(issuer, session.sessionId),
                "{}",
                mapOf(
                    "Accept" to "application/json",
                    "X-Poll-Token" to session.pollToken,
                ),
            )
            if (response.code in 200..299) {
                val claim = CorosAuthParser.parseClaim(response.body)
                    ?: throw CorosAuthException("COROS login returned no status")
                when (claim.status.lowercase()) {
                    "authorized", "approved", "success", "succeeded" -> {
                        return claim.loginTicket
                            ?: throw CorosAuthException("COROS login was authorized without a ticket")
                    }
                    "pending", "waiting", "created", "in_progress" -> Unit
                    "failed", "expired", "claimed", "denied", "cancelled", "canceled" -> {
                        throw CorosAuthException("COROS login did not complete")
                    }
                    else -> {
                        claim.loginTicket?.let { return it }
                        throw CorosAuthException("COROS login did not complete")
                    }
                }
            } else if (response.code != 429 && response.code !in 500..599) {
                throw CorosAuthException("COROS login did not complete (${response.code})")
            }
            if (System.currentTimeMillis() >= deadline) {
                throw CorosAuthException("COROS login session expired")
            }
            delay(session.intervalSeconds * 1000L)
        }
    }

    suspend fun authorize(
        issuer: String,
        clientId: String,
        loginTicket: String,
    ): CorosAuthorizeResult {
        val verifier = CorosPkce.verifier()
        val challenge = CorosPkce.challenge(verifier)
        val state = CorosPkce.state()
        val url = CorosUrls.authorize(
            issuer = issuer,
            clientId = clientId,
            codeChallenge = challenge,
            state = state,
            loginTicket = loginTicket,
        )
        val response = CorosHttp.get(
            url,
            headers = mapOf("Accept" to "text/html"),
            followRedirects = false,
        )
        if (response.code !in intArrayOf(302, 303, 307, 308)) {
            throw CorosAuthException("COROS authorization failed (${response.code})")
        }
        val location = response.headers["location"]
            ?: throw CorosAuthException("COROS authorization returned no redirect")
        if (!CorosUrls.isLoopbackCallback(location)) {
            throw CorosAuthException("COROS authorization left the app callback")
        }
        val callback = CorosUrls.parseCallback(location)
            ?: throw CorosAuthException("COROS authorization callback was unreadable")
        if (callback.error != null) throw CorosAuthException("COROS authorization was denied")
        if (callback.state != state) throw CorosAuthException("COROS authorization state mismatch")
        val code = callback.code ?: throw CorosAuthException("COROS authorization returned no code")
        return CorosAuthorizeResult(code, verifier)
    }

    suspend fun exchange(
        issuer: String,
        clientId: String,
        code: String,
        verifier: String,
        region: CorosRegion,
    ): CorosToken {
        val response = CorosHttp.postForm(
            CorosUrls.token(issuer),
            listOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "redirect_uri" to CorosUrls.REDIRECT_URI,
                "code_verifier" to verifier,
            ),
            mapOf("Accept" to "application/json"),
        )
        if (response.code !in 200..299) {
            throw CorosAuthException("COROS token exchange failed (${response.code})")
        }
        return CorosAuthParser.parseToken(
            response.body,
            System.currentTimeMillis(),
            clientId,
            issuer,
            region,
        ) ?: throw CorosAuthException("COROS token response was incomplete")
    }

    suspend fun refresh(token: CorosToken): CorosToken {
        val refreshToken = token.refreshToken
            ?: throw CorosAuthException("COROS session expired — reconnect")
        val response = CorosHttp.postForm(
            CorosUrls.token(token.issuer),
            listOf(
                "grant_type" to "refresh_token",
                "client_id" to token.clientId,
                "refresh_token" to refreshToken,
            ),
            mapOf("Accept" to "application/json"),
        )
        if (response.code !in 200..299) {
            throw CorosAuthException("COROS session expired — reconnect")
        }
        return CorosAuthParser.parseToken(
            response.body,
            System.currentTimeMillis(),
            token.clientId,
            token.issuer,
            token.region,
            previousRefreshToken = refreshToken,
        ) ?: throw CorosAuthException("COROS token response was incomplete")
    }
}
