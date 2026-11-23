/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorosUrlsTest {

    @Test
    fun issuerEndpoints() {
        val issuer = CorosRegion.EU.issuer
        assertEquals("https://mcpeu.coros.com/connect/register", CorosUrls.register(issuer))
        assertEquals("https://mcpeu.coros.com/api/v1/cli/login-sessions", CorosUrls.loginSessions(issuer))
        assertEquals(
            "https://mcpeu.coros.com/api/v1/cli/login-sessions/session-1/claim",
            CorosUrls.claim(issuer, "session-1"),
        )
        assertEquals("https://mcpeu.coros.com/oauth2/token", CorosUrls.token(issuer))
        assertEquals("https://mcpeu.coros.com/mcp", CorosUrls.mcp(issuer))
        assertEquals("https://mcpeu.coros.com/mcp", CorosRegion.EU.mcpUrl)
    }

    @Test
    fun regionIssuers() {
        assertEquals("https://mcp.coros.com", CorosRegion.AUTO.issuer)
        assertEquals("https://mcpus.coros.com", CorosRegion.US.issuer)
        assertEquals("https://mcpeu.coros.com", CorosRegion.EU.issuer)
        assertEquals("https://mcpcn.coros.com", CorosRegion.CN.issuer)
    }

    @Test
    fun authorizeUrlCarriesPkceAndLoginTicket() {
        val url = CorosUrls.authorize(
            issuer = "https://mcpeu.coros.com",
            clientId = "client-1",
            codeChallenge = "challenge-1",
            state = "state-1",
            loginTicket = "ticket-1",
        )
        assertTrue(url.startsWith("https://mcpeu.coros.com/oauth2/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client-1"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A43123%2Fcallback"))
        assertTrue(url.contains("scope=openid%20offline_access%20mcp.tools"))
        assertTrue(url.contains("code_challenge=challenge-1"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("resource=https%3A%2F%2Fmcpeu.coros.com%2Fmcp"))
        assertTrue(url.contains("state=state-1"))
        assertTrue(url.contains("login_ticket=ticket-1"))
    }

    @Test
    fun parsesRedirectCallback() {
        val callback = CorosUrls.parseCallback(
            "http://127.0.0.1:43123/callback?code=abc%2F123&state=state-1",
        )
        assertEquals("abc/123", callback?.code)
        assertEquals("state-1", callback?.state)
        assertNull(callback?.error)
    }

    @Test
    fun parsesDeniedCallback() {
        val callback = CorosUrls.parseCallback(
            "http://127.0.0.1:43123/callback?error=access_denied&state=state-1",
        )
        assertEquals("access_denied", callback?.error)
        assertNull(callback?.code)
    }

    @Test
    fun rejectsLocationWithoutQuery() {
        assertNull(CorosUrls.parseCallback("http://127.0.0.1:43123/callback"))
        assertNull(CorosUrls.parseCallback("not a url"))
    }

    @Test
    fun recognisesLoopbackCallback() {
        assertTrue(CorosUrls.isLoopbackCallback("http://127.0.0.1:43123/callback?code=x"))
        assertFalse(CorosUrls.isLoopbackCallback("https://example.com/callback?code=x"))
    }

    @Test
    fun regionFromNameFallsBack() {
        assertEquals(CorosRegion.US, CorosRegion.fromName("US"))
        assertEquals(CorosRegion.AUTO, CorosRegion.fromName("unknown"))
        assertEquals(CorosRegion.AUTO, CorosRegion.fromName(null))
    }
}
