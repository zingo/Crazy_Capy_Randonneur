/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

enum class CorosRegion(val label: String, val issuer: String) {
    AUTO("Auto (EU)", "https://mcp.coros.com"),
    EU("Europe", "https://mcpeu.coros.com"),
    US("United States", "https://mcpus.coros.com"),
    CN("China", "https://mcpcn.coros.com");

    val mcpUrl: String
        get() = "$issuer/mcp"

    val shortLabel: String
        get() = when (this) {
            AUTO -> "Auto"
            EU -> "EU"
            US -> "US"
            CN -> "CN"
        }

    companion object {
        fun fromName(name: String?): CorosRegion = entries.firstOrNull { it.name == name } ?: AUTO
    }
}
