/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.gpx

import java.security.MessageDigest

object TrackHash {

    private const val COORD_SCALE = 1_000_000.0
    private const val ELE_SCALE = 100.0

    fun of(track: Track): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (point in track.points) {
            feed(md, Math.round(point.lat * COORD_SCALE))
            feed(md, Math.round(point.lon * COORD_SCALE))
            val ele = point.ele
            if (ele == null) {
                md.update(0)
            } else {
                md.update(1)
                feed(md, Math.round(ele * ELE_SCALE))
            }
        }
        return md.digest().joinToString("") { byte ->
            ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1)
        }
    }

    private fun feed(md: MessageDigest, value: Long) {
        for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
            md.update((value ushr shift).toByte())
        }
    }
}
