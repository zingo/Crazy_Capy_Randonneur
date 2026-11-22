/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.crazycapy.randonneur.cache.OfflineDetailStrategy
import com.crazycapy.randonneur.cache.OfflineTuning
import com.crazycapy.randonneur.cache.PrepareState
import com.crazycapy.randonneur.cache.RoutePrepare
import com.crazycapy.randonneur.gpx.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PrepareService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var lastNotification: String? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        active = true
        PrepareNotifications.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PrepareActions.PAUSE -> {
                RoutePrepare.pause()
                ensureForeground()
                observe()
            }
            PrepareActions.RESUME -> {
                RoutePrepare.resume(this)
                if (RoutePrepare.state.running) {
                    ensureForeground()
                    observe()
                } else {
                    finish()
                }
            }
            PrepareActions.CANCEL -> {
                RoutePrepare.cancel(this)
                finish()
            }
            else -> {
                val request = pending
                pending = null
                if (request != null) {
                    RoutePrepare.start(
                        this,
                        request.track,
                        request.strategy,
                        request.bothThemes,
                        request.tuning,
                        request.targetStyle,
                    )
                }
                ensureForeground()
                val state = RoutePrepare.state
                if (state.running || state.paused) {
                    observe()
                } else {
                    finish()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = false
        observeJob?.cancel()
        observeJob = null
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            while (isActive) {
                val state = RoutePrepare.state
                if (state.done || state.cancelled || (!state.running && !state.paused)) {
                    finish()
                    break
                }
                notifyCurrent(state)
                delay(1000)
            }
        }
    }

    private fun ensureForeground() {
        val notification = PrepareNotifications.build(
            this,
            RoutePrepare.state,
            recoveryActions = false,
        )
        ServiceCompat.startForeground(
            this,
            PrepareNotifications.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        foregroundStarted = true
    }

    private fun notifyCurrent(state: PrepareState) {
        val notification = PrepareNotifications.build(this, state, recoveryActions = false)
        val key = listOf(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
            notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            notification.extras.getInt(Notification.EXTRA_PROGRESS, -1),
            state.paused,
        ).joinToString("|")
        if (foregroundStarted && key == lastNotification) return
        lastNotification = key
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!foregroundStarted) {
                ServiceCompat.startForeground(
                    this,
                    PrepareNotifications.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                foregroundStarted = true
            } else {
                nm.notify(PrepareNotifications.NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun finish() {
        observeJob?.cancel()
        observeJob = null
        active = false
        PrepareRecoveryWork.cancel(this)
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private data class Request(
        val track: Track,
        val strategy: OfflineDetailStrategy,
        val bothThemes: Boolean,
        val tuning: OfflineTuning,
        val targetStyle: String?,
    )

    companion object {
        private const val ACTION_START = "com.crazycapy.randonneur.action.PREPARE_START"

        @Volatile
        var active: Boolean = false
            private set

        @Volatile
        private var pending: Request? = null

        fun start(
            context: Context,
            track: Track,
            strategy: OfflineDetailStrategy,
            bothThemes: Boolean,
            tuning: OfflineTuning = OfflineTuning.DEFAULT,
            targetStyle: String? = null,
        ) {
            active = true
            pending = Request(track, strategy, bothThemes, tuning, targetStyle)
            ContextCompat.startForegroundService(
                context,
                Intent(context, PrepareService::class.java).setAction(ACTION_START),
            )
        }
    }
}
