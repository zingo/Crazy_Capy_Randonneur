/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CorosTokenStore {
    private const val PREFS = "coros_secure"
    private const val KEY = "token_v1"
    private const val ALIAS = "crazycapy.coros.token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    fun save(context: Context, token: CorosToken) {
        runCatching {
            val payload = JSONObject().apply {
                put("accessToken", token.accessToken)
                put("refreshToken", token.refreshToken ?: JSONObject.NULL)
                put("expiresAtMs", token.expiresAtMs)
                put("clientId", token.clientId)
                put("issuer", token.issuer)
                put("region", token.region.name)
            }.toString().toByteArray(Charsets.UTF_8)
            val encrypted = encrypt(payload)
            prefs(context).edit().putString(KEY, Base64.getEncoder().encodeToString(encrypted)).apply()
        }
    }

    fun load(context: Context): CorosToken? = runCatching {
        val raw = prefs(context).getString(KEY, null) ?: return null
        val decrypted = decrypt(Base64.getDecoder().decode(raw))
        val json = JSONObject(String(decrypted, Charsets.UTF_8))
        val access = json.optString("accessToken").takeIf { it.isNotBlank() } ?: return null
        val clientId = json.optString("clientId").takeIf { it.isNotBlank() } ?: return null
        val issuer = json.optString("issuer").takeIf { it.isNotBlank() } ?: return null
        CorosToken(
            accessToken = access,
            refreshToken = json.optString("refreshToken").takeIf { it.isNotBlank() },
            expiresAtMs = json.optLong("expiresAtMs"),
            clientId = clientId,
            issuer = issuer,
            region = CorosRegion.fromName(json.optString("region")),
        )
    }.getOrElse {
        clear(context)
        null
    }

    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY).apply() }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return ByteArray(iv.size + ciphertext.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(ciphertext, 0, it, iv.size, ciphertext.size)
        }
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val ivLength = 12
        require(payload.size > ivLength)
        val iv = payload.copyOfRange(0, ivLength)
        val ciphertext = payload.copyOfRange(ivLength, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
