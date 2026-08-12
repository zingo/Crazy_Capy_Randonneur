/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import com.crazycapy.randonneur.roadBrightenOverrides
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory

/**
 * Attach an observer to [snap] that brightens OpenFreeMap's "dark" road
 * colours when the style finishes loading.
 */
internal fun MapSnapshotter.brightenDarkRoads() {
    setObserver(object : MapSnapshotter.Observer {
        override fun onDidFinishLoadingStyle() {
            for ((id, color) in roadBrightenOverrides) {
                (getLayer(id) as? LineLayer)
                    ?.setProperties(PropertyFactory.lineColor(color))
            }
        }
        override fun onStyleImageMissing(name: String) {}
    })
}
