/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.crazycapy.randonneur.MainActivity
import com.crazycapy.randonneur.R
import com.crazycapy.randonneur.cache.OfflineDetailStrategy
import com.crazycapy.randonneur.cache.PreparePhase
import com.crazycapy.randonneur.cache.PrepareProgress
import com.crazycapy.randonneur.cache.PrepareProgressInput
import com.crazycapy.randonneur.cache.PrepareState
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.cache.RouteOfflinePack
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
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                RoutePrepare.pause()
                ensureForeground()
                observe()
            }
            ACTION_RESUME -> {
                RoutePrepare.resume(this)
                if (RoutePrepare.state.running) {
                    ensureForeground()
                    observe()
                } else {
                    finish()
                }
            }
            ACTION_CANCEL -> {
                RoutePrepare.cancel()
                finish()
            }
            else -> {
                val request = pending
                pending = null
                if (request != null) {
                    RoutePrepare.start(this, request.track, request.strategy, request.bothThemes)
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
        val notification = buildNotification(RoutePrepare.state)
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        foregroundStarted = true
    }

    private fun notifyCurrent(state: PrepareState) {
        val notification = buildNotification(state)
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
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                foregroundStarted = true
            } else {
                nm.notify(NOTIF_ID, notification)
            }
        } catch (_: SecurityException) {
        }
    }

    private fun buildNotification(state: PrepareState): Notification {
        val input = inputFor(state)
        val percent = PrepareProgress.percent(input)
        val text = PrepareProgress.summary(input)
        val paused = state.paused
        val pauseLabel = if (paused) "Resume" else "Pause"
        val pauseIcon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_notification)
            .setContentTitle(PrepareProgress.title(input))
            .setContentText(text)
            .setSubText(phaseLabel(state.phase))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(
                pauseIcon,
                pauseLabel,
                actionPending(if (paused) ACTION_RESUME else ACTION_PAUSE, 11),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                actionPending(ACTION_CANCEL, 12),
            )
            .build()
    }

    private fun inputFor(state: PrepareState): PrepareProgressInput {
        val map = RouteOfflinePack.state
        return PrepareProgressInput(
            routeName = state.routeName,
            phase = state.phase,
            paused = state.paused,
            mapSkipped = state.mapSkipped,
            previewsSkipped = state.previewsSkipped,
            mapPercent = map.percent,
            completedSegments = map.completedSegments,
            segmentCount = map.segmentCount,
            completedBytes = map.completedBytes,
            completedResources = map.completedResources,
            requiredResources = map.requiredResources,
            previewProgress = RouteCache.progress,
            previewStatus = state.previewsStatus ?: RouteCache.status,
        )
    }

    private fun phaseLabel(phase: PreparePhase): String = when (phase) {
        PreparePhase.MAP -> "Map tiles"
        PreparePhase.PREVIEWS -> "Turn previews"
        PreparePhase.DONE -> "Complete"
    }

    private fun actionPending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this,
            code,
            Intent(this, PrepareService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun finish() {
        observeJob?.cancel()
        observeJob = null
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Offline preparation",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Offline route pack progress"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private data class Request(
        val track: Track,
        val strategy: OfflineDetailStrategy,
        val bothThemes: Boolean,
    )

    companion object {
        private const val ACTION_START = "com.crazycapy.randonneur.action.PREPARE_START"
        private const val ACTION_PAUSE = "com.crazycapy.randonneur.action.PREPARE_PAUSE"
        private const val ACTION_RESUME = "com.crazycapy.randonneur.action.PREPARE_RESUME"
        private const val ACTION_CANCEL = "com.crazycapy.randonneur.action.PREPARE_CANCEL"
        private const val CHANNEL_ID = "offline_prepare"
        private const val NOTIF_ID = 2

        @Volatile
        private var pending: Request? = null

        fun start(
            context: Context,
            track: Track,
            strategy: OfflineDetailStrategy,
            bothThemes: Boolean,
        ) {
            pending = Request(track, strategy, bothThemes)
            ContextCompat.startForegroundService(
                context,
                Intent(context, PrepareService::class.java).setAction(ACTION_START),
            )
        }
    }
}
