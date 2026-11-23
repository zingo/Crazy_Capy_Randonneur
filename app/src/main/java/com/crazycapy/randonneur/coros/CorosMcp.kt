/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import com.crazycapy.randonneur.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

data class CorosRpcResponse(
    val result: JSONObject?,
    val error: String?,
)

object CorosMcpParser {

    fun payloads(body: String): List<String> {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{")) return listOf(body.trim())
        return body.lineSequence()
            .mapNotNull { line ->
                val clean = line.trimStart()
                if (clean.startsWith("data:")) clean.removePrefix("data:").trimStart() else null
            }
            .filter { it.isNotBlank() && it.trim() != "[DONE]" }
            .toList()
    }

    fun parse(body: String): CorosRpcResponse {
        val candidates = payloads(body)
        if (candidates.isEmpty()) return CorosRpcResponse(null, "Unexpected COROS response")
        for (candidate in candidates.asReversed()) {
            val json = runCatching { JSONObject(candidate) }.getOrNull() ?: continue
            json.optJSONObject("error")?.let { error ->
                val message = error.optString("message").takeIf { it.isNotBlank() }
                    ?: error.optString("code").takeIf { it.isNotBlank() }
                    ?: "COROS request failed"
                return CorosRpcResponse(null, message)
            }
            json.optJSONObject("result")?.let { result ->
                return CorosRpcResponse(result, null)
            }
        }
        return CorosRpcResponse(null, "Unexpected COROS response")
    }

    fun texts(result: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val content = result.optJSONArray("content")
        if (content != null) {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index)
                if (item != null) {
                    val text = item.optString("text")
                    if (text.isNotBlank()) {
                        out.add(text)
                        continue
                    }
                    item.opt("structuredContent")?.let { structured ->
                        render(structured)?.let(out::add)
                    }
                } else {
                    content.optString(index).takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
        }
        if (out.isEmpty()) {
            result.opt("structuredContent")?.let { structured ->
                render(structured)?.let(out::add)
            }
        }
        return out
    }

    private fun render(value: Any): String? = when (value) {
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        is String -> value.takeIf { it.isNotBlank() }
        else -> null
    }
}

class CorosMcpException(message: String) : Exception(message)

class CorosMcpClient(private val issuer: String) {
    private var initialized = false

    suspend fun initialize(accessToken: String) {
        val params = JSONObject().apply {
            put("protocolVersion", CorosUrls.PROTOCOL_VERSION)
            put("capabilities", JSONObject())
            put(
                "clientInfo",
                JSONObject()
                    .put("name", "Crazy Capy Randonneur")
                    .put("version", BuildConfig.VERSION_NAME),
            )
        }
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            put("params", params)
        }
        request(accessToken, payload)
        initialized = true
    }

    suspend fun callTool(
        accessToken: String,
        name: String,
        arguments: JSONObject,
    ): List<String> {
        if (!initialized) initialize(accessToken)
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            put(
                "params",
                JSONObject()
                    .put("name", name)
                    .put("arguments", arguments),
            )
        }
        val response = request(accessToken, payload)
        val parsed = CorosMcpParser.parse(response.body)
        parsed.error?.let { throw CorosMcpException(it) }
        val result = parsed.result ?: throw CorosMcpException("Unexpected COROS response")
        return CorosMcpParser.texts(result)
    }

    private suspend fun request(accessToken: String, payload: JSONObject): CorosHttpResult {
        val response = CorosHttp.postJson(
            CorosUrls.mcp(issuer),
            payload.toString(),
            mapOf(
                "Accept" to "application/json, text/event-stream",
                "Authorization" to "Bearer $accessToken",
            ),
        )
        if (response.code !in 200..299) {
            throw CorosMcpException("COROS request failed (${response.code})")
        }
        return response
    }
}
