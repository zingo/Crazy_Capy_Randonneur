/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.cache

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crazycapy.randonneur.state.RideStore
import java.util.concurrent.atomic.AtomicBoolean

object OfflinePrune {

    private const val LOG_TAG = "OfflinePrune"

    var notice: String? by mutableStateOf(null)
        private set

    var revision: Int by mutableStateOf(0)
        private set

    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    fun pruneAtStartup(context: Context) {
        if (!RideStore.offlineAutoDeleteExpired) return
        val appContext = context.applicationContext
        Thread({
            if (!RideStore.offlineAutoDeleteExpired) return@Thread
            if (!running.compareAndSet(false, true)) return@Thread
            main.post { listAndPrune(appContext) }
        }, "offline-pack-prune").start()
    }

    private fun listAndPrune(context: Context) {
        RouteOfflinePack.listPacks(context) { packs ->
            val nowMs = System.currentTimeMillis()
            val expiryDays = RideStore.offlineExpiryDays
            Thread({
                val groups = groupOfflinePacks(packs, nowMs, expiryDays)
                val expired = groups.filter { it.expired }
                val ids = OfflineLimits.expiredGroupIds(
                    groups.map { OfflineExpiryGroup(it.ids, it.createdAt) },
                    autoDelete = true,
                    expiryDays = expiryDays,
                    nowMs = nowMs,
                )
                if (ids.isEmpty()) {
                    running.set(false)
                    return@Thread
                }
                Log.i(LOG_TAG, "auto-pruning ${expired.size} expired packs (${ids.size} regions)")
                main.post {
                    RouteOfflinePack.deletePacks(context, ids) {
                        notice = "Removed ${expired.size} expired pack${if (expired.size == 1) "" else "s"}"
                        revision++
                        running.set(false)
                    }
                }
            }, "offline-pack-prune-decide").start()
        }
    }
}
