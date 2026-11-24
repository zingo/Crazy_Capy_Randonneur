/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class CorosHttpResult(
    val code: Int,
    val body: String,
    val headers: Map<String, String>,
)

object CorosHttp {
    private const val USER_AGENT = "Crazy Capy Randonneur"

    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (failure: UnknownHostException) {
            resolveOverHttps(hostname) ?: throw failure
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dns(dns)
        .build()

    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): CorosHttpResult = withContext(Dispatchers.IO) {
        send("POST", url, body.toByteArray(Charsets.UTF_8), "application/json", headers, true)
    }

    suspend fun postForm(
        url: String,
        fields: List<Pair<String, String>>,
        headers: Map<String, String> = emptyMap(),
    ): CorosHttpResult = withContext(Dispatchers.IO) {
        val body = fields.joinToString("&") {
            "${URLEncoder.encode(it.first, Charsets.UTF_8)}=${URLEncoder.encode(it.second, Charsets.UTF_8)}"
        }
        send(
            "POST",
            url,
            body.toByteArray(Charsets.UTF_8),
            "application/x-www-form-urlencoded",
            headers,
            true,
        )
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): CorosHttpResult = withContext(Dispatchers.IO) {
        send("GET", url, null, null, headers, followRedirects)
    }

    private fun send(
        method: String,
        url: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): CorosHttpResult {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        contentType?.let { builder.header("Content-Type", it) }
        headers.forEach { (name, value) -> builder.header(name, value) }
        val requestBody = body?.toRequestBody(contentType?.toMediaType())
        builder.method(method, requestBody)

        var lastFailure: IOException? = null
        repeat(3) { attempt ->
            try {
                val http = if (followRedirects) client else noRedirectClient
                http.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val responseHeaders = response.headers.toMultimap()
                        .mapValues { it.value.joinToString(", ") }
                        .mapKeys { it.key.lowercase() }
                    return CorosHttpResult(response.code, text, responseHeaders)
                }
            } catch (failure: IOException) {
                lastFailure = failure
                if (attempt < 2) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastFailure ?: IOException("COROS request failed")
    }

    private fun resolveOverHttps(hostname: String): List<InetAddress>? {
        val endpoints = listOf(
            { type: String -> "https://1.1.1.1/dns-query?name=$hostname&type=$type" },
            { type: String -> "https://8.8.8.8/resolve?name=$hostname&type=$type" },
        )
        val addresses = linkedSetOf<InetAddress>()
        for (endpoint in endpoints) {
            for (type in listOf("A", "AAAA")) {
                val request = Request.Builder()
                    .url(endpoint(type))
                    .header("Accept", "application/dns-json")
                    .build()
                val json = runCatching {
                    dohClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        JSONObject(response.body?.string().orEmpty())
                    }
                }.getOrNull() ?: continue
                val answers = json.optJSONArray("Answer") ?: continue
                for (index in 0 until answers.length()) {
                    val data = answers.optJSONObject(index)?.optString("data").orEmpty()
                    if (data.isBlank()) continue
                    runCatching { InetAddress.getByName(data) }.getOrNull()?.let(addresses::add)
                }
            }
            if (addresses.isNotEmpty()) return addresses.toList()
        }
        return addresses.takeIf { it.isNotEmpty() }?.toList()
    }
}
