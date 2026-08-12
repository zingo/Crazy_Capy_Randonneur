/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * TrackLoader — Loads a route from any content URI by delegating to GpxParser.
 *
 *   content:// URI or file:// URI -> InputStream -> GpxParser -> Track
 *
 * Handles Android content resolver lookups and error wrapping for the share-intent flow.
 */
package com.crazycapy.randonneur.gpx

import android.content.Context
import android.net.Uri
import java.io.IOException

/** Loads a GPX route from different sources (share target, SAF picker, assets). */
object TrackLoader {

    fun loadUri(context: Context, uri: Uri, displayName: String? = null): Track {
        val resolver = context.contentResolver
        val stream = resolver.openInputStream(uri)
            ?: throw IOException("Cannot open $uri")
        return stream.use { GpxParser().parse(displayName, it) }
    }

    fun loadString(content: String, name: String? = null): Track =
        content.byteInputStream().use { GpxParser().parse(name, it) }

    fun loadAsset(context: Context, assetPath: String): Track =
        context.assets.open(assetPath).use { GpxParser().parse(assetPath, it) }
}