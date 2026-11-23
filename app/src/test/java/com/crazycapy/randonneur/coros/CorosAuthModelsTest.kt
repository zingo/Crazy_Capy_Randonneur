/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorosAuthModelsTest {

    private val now = 1_700_000_000_000L

    @Test
    fun tokenExpiry() {
        val token = CorosToken("access", "refresh", now + 120_000L, "client", "issuer", CorosRegion.EU)
        assertFalse(token.isExpired(now))
        assertTrue(token.isExpired(now + 60_000L))
        assertTrue(token.isExpired(now + 120_000L))
    }

    @Test
    fun parsesTokenResponse() {
        val token = CorosAuthParser.parseToken(
            """{"access_token":"a","refresh_token":"r","expires_in":1800}""",
            now,
            "client",
            "https://mcpeu.coros.com",
            CorosRegion.EU,
        )
        assertNotNull(token)
        assertEquals("a", token?.accessToken)
        assertEquals("r", token?.refreshToken)
        assertEquals(now + 1_800_000L, token?.expiresAtMs)
    }

    @Test
    fun rejectsTokenWithoutAccess() {
        assertNull(
            CorosAuthParser.parseToken(
                """{"refresh_token":"r"}""",
                now,
                "client",
                "issuer",
                CorosRegion.EU,
            ),
        )
        assertNull(CorosAuthParser.parseToken("not json", now, "client", "issuer", CorosRegion.EU))
    }

    @Test
    fun refreshKeepsPreviousRefreshToken() {
        val token = CorosAuthParser.parseToken(
            """{"access_token":"a","expires_in":"900"}""",
            now,
            "client",
            "issuer",
            CorosRegion.US,
            previousRefreshToken = "old-refresh",
        )
        assertEquals("old-refresh", token?.refreshToken)
        assertEquals(now + 900_000L, token?.expiresAtMs)
    }

    @Test
    fun parsesClientIdVariants() {
        assertEquals("c1", CorosAuthParser.parseClientId("""{"client_id":"c1"}"""))
        assertEquals("c2", CorosAuthParser.parseClientId("""{"clientId":"c2"}"""))
        assertNull(CorosAuthParser.parseClientId("""{"other":"c3"}"""))
    }

    @Test
    fun parsesLoginSession() {
        val session = CorosAuthParser.parseLoginSession(
            """{"sessionId":"s","pollToken":"p","loginUrl":"https://login","expiresAt":1700000600,"intervalSeconds":3}""",
            now,
        )
        assertEquals("s", session?.sessionId)
        assertEquals("p", session?.pollToken)
        assertEquals("https://login", session?.loginUrl)
        assertEquals(1_700_000_600_000L, session?.expiresAtMs)
        assertEquals(3, session?.intervalSeconds)
    }

    @Test
    fun loginSessionIntervalIsClamped() {
        val session = CorosAuthParser.parseLoginSession(
            """{"session_id":"s","poll_token":"p","login_url":"https://login","interval_seconds":0}""",
            now,
        )
        assertEquals(1, session?.intervalSeconds)
    }

    @Test
    fun rejectsIncompleteLoginSession() {
        assertNull(CorosAuthParser.parseLoginSession("""{"sessionId":"s"}""", now))
    }

    @Test
    fun parsesClaimStates() {
        val authorized = CorosAuthParser.parseClaim(
            """{"status":"authorized","loginTicket":"ticket"}""",
        )
        assertEquals("authorized", authorized?.status)
        assertEquals("ticket", authorized?.loginTicket)

        val pending = CorosAuthParser.parseClaim("""{"data":{"status":"pending"}}""")
        assertEquals("pending", pending?.status)
        assertNull(pending?.loginTicket)
    }
}
