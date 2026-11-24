/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CorosHttpResult(
    val code: Int,
    val body: String,
    val headers: Map<String, String>,
)

object CorosHttp {
    private const val USER_AGENT = "Crazy Capy Randonneur"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

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
        var lastFailure: java.io.IOException? = null
        repeat(3) { attempt ->
            val attemptConnection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = followRedirects
                useCaches = false
                requestMethod = method
                setRequestProperty("User-Agent", USER_AGENT)
                contentType?.let { setRequestProperty("Content-Type", it) }
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) doOutput = true
            }
            try {
                if (body != null) attemptConnection.outputStream.use { it.write(body) }
                val code = attemptConnection.responseCode
                val stream = if (code in 200..299) attemptConnection.inputStream else attemptConnection.errorStream
                val text = runCatching {
                    stream?.bufferedReader()?.use { it.readText() } ?: ""
                }.getOrDefault("")
                val responseHeaders = buildMap {
                    attemptConnection.headerFields.forEach { (name, values) ->
                        if (name != null && values.isNotEmpty()) {
                            put(name.lowercase(), values.joinToString(", "))
                        }
                    }
                }
                return CorosHttpResult(code, text, responseHeaders)
            } catch (failure: java.io.IOException) {
                lastFailure = failure
                if (attempt < 2) Thread.sleep(500L * (attempt + 1))
            } finally {
                attemptConnection.disconnect()
            }
        }
        throw lastFailure ?: java.io.IOException("COROS request failed")
    }
}
