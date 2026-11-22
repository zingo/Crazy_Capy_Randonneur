/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.service

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.crazycapy.randonneur.cache.PrepareRecovery
import com.crazycapy.randonneur.cache.PrepareState
import com.crazycapy.randonneur.cache.RoutePrepare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PrepareRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        PrepareRecoveryWork.setWorkerRunning(true)
        try {
            val context = applicationContext
            val pending = PrepareRecovery.load(context) ?: return Result.success()
            if (PrepareService.active || RoutePrepare.state.running || RoutePrepare.state.paused) {
                return Result.retry()
            }
            val track = PrepareRecovery.resolveTrack(context, pending)
            if (track == null) {
                Log.i(LOG_TAG, "dropping stale prepare recovery for ${pending.routeName}")
                PrepareRecovery.clear(context)
                return Result.success()
            }
            PrepareNotifications.createChannel(context)
            var lastKey: String? = null
            setForeground(foregroundInfo(PrepareNotifications.build(context, RoutePrepare.state, true)))
            Log.i(LOG_TAG, "resuming prepare for ${pending.routeName}")
            withContext(Dispatchers.Main.immediate) {
                RoutePrepare.startRecovery(
                    context,
                    track,
                    pending.strategy,
                    pending.bothThemes,
                    pending.tuning,
                    pending.targetStyle,
                )
            }
            while (currentCoroutineContext().isActive) {
                val state = RoutePrepare.state
                if (state.routeId.isNotBlank() && state.routeId != pending.routeId) break
                if (state.done || state.cancelled) {
                    PrepareRecovery.clear(context)
                    break
                }
                if (!state.running && !state.paused) break
                if (PrepareService.active) return Result.retry()
                val notification = PrepareNotifications.build(context, state, true)
                val key = notificationKey(notification, state)
                if (key != lastKey) {
                    setForeground(foregroundInfo(notification))
                    lastKey = key
                }
                delay(1000)
            }
            return Result.success()
        } finally {
            PrepareRecoveryWork.setWorkerRunning(false)
        }
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        ForegroundInfo(
            PrepareNotifications.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    private fun notificationKey(notification: Notification, state: PrepareState): String =
        listOf(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
            notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            notification.extras.getInt(Notification.EXTRA_PROGRESS, -1),
            state.paused,
        ).joinToString("|")

    private companion object {
        const val LOG_TAG = "PrepareRecovery"
    }
}

object PrepareRecoveryWork {

    private const val UNIQUE_NAME = "offline-prepare-recovery"
    private const val LOG_TAG = "PrepareRecovery"

    @Volatile
    var workerRunning: Boolean = false
        private set

    internal fun setWorkerRunning(running: Boolean) {
        workerRunning = running
    }

    fun enqueue(context: Context) {
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    fun ensure(context: Context) {
        enqueue(context, ExistingWorkPolicy.KEEP)
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<PrepareRecoveryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, policy, request)
        Log.i(LOG_TAG, "enqueued recovery work ($policy)")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
    }
}
