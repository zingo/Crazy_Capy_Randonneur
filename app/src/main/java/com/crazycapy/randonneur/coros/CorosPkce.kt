/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object CorosPkce {
    private val random = SecureRandom()

    fun verifier(bytes: Int = 32): String = encode(randomBytes(bytes))

    fun challenge(verifier: String): String =
        encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun state(bytes: Int = 16): String = encode(randomBytes(bytes))

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

    fun encode(raw: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
}
