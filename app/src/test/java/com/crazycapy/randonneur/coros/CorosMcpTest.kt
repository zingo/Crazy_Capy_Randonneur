/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorosMcpTest {

    @Test
    fun extractsSseDataLines() {
        val body = """
            event: message
            data: {"jsonrpc":"2.0","id":2,"result":{"content":[]}}

            data: [DONE]
        """.trimIndent()
        val payloads = CorosMcpParser.payloads(body)
        assertEquals(1, payloads.size)
        assertTrue(payloads[0].startsWith("{"))
    }

    @Test
    fun parsesJsonBody() {
        val response = CorosMcpParser.parse(
            """{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"hello"}]}}""",
        )
        assertNull(response.error)
        assertNotNull(response.result)
        assertEquals(listOf("hello"), CorosMcpParser.texts(response.result!!))
    }

    @Test
    fun parsesSseBody() {
        val response = CorosMcpParser.parse(
            """
                event: message
                data: {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"ride"}]}}
            """.trimIndent(),
        )
        assertEquals(listOf("ride"), CorosMcpParser.texts(response.result!!))
    }

    @Test
    fun usesLastRpcResult() {
        val response = CorosMcpParser.parse(
            """
                data: {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}
                data: {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"last"}]}}
            """.trimIndent(),
        )
        assertEquals(listOf("last"), CorosMcpParser.texts(response.result!!))
    }

    @Test
    fun reportsRpcError() {
        val response = CorosMcpParser.parse(
            """{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"bad tool"}}""",
        )
        assertNull(response.result)
        assertEquals("bad tool", response.error)
    }

    @Test
    fun malformedResponseIsAnError() {
        val response = CorosMcpParser.parse("not json at all")
        assertNull(response.result)
        assertNotNull(response.error)
    }

    @Test
    fun keepsMultilineToolTables() {
        val result = JSONObject()
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", "Date       Ride\n2026-11-20 42 km"),
                ),
            )
        val texts = CorosMcpParser.texts(result)
        assertEquals(1, texts.size)
        assertTrue(texts[0].contains("Date"))
        assertTrue(texts[0].contains("42 km"))
    }

    @Test
    fun ignoresBlankAndNonTextContent() {
        val result = JSONObject()
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", " "))
                    .put(JSONObject().put("type", "image")),
            )
        assertTrue(CorosMcpParser.texts(result).isEmpty())
    }

    @Test
    fun fallsBackToStructuredContent() {
        val result = JSONObject().put(
            "structuredContent",
            JSONObject().put("hrv", 62).put("restingHr", 48),
        )
        val texts = CorosMcpParser.texts(result)
        assertEquals(1, texts.size)
        assertTrue(texts[0].contains("hrv"))
        assertTrue(texts[0].contains("48"))
    }

    @Test
    fun structuredContentInsideContentItem() {
        val result = JSONObject().put(
            "content",
            JSONArray().put(
                JSONObject().put(
                    "structuredContent",
                    JSONObject().put("recovery", "good"),
                ),
            ),
        )
        val texts = CorosMcpParser.texts(result)
        assertEquals(1, texts.size)
        assertTrue(texts[0].contains("good"))
    }

    @Test
    fun emptyResultHasNoText() {
        val result = JSONObject().put("content", JSONArray())
        assertTrue(CorosMcpParser.texts(result).isEmpty())
    }
}
