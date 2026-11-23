/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorosPkceTest {

    @Test
    fun rfc7636ChallengeVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            CorosPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun verifierIsUrlSafeAndLongEnough() {
        val verifier = CorosPkce.verifier()
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
    }

    @Test
    fun statesAreRandomUrlSafe() {
        val first = CorosPkce.state()
        val second = CorosPkce.state()
        assertNotEquals(first, second)
        assertTrue(first.isNotEmpty())
        assertTrue(first.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
    }
}
