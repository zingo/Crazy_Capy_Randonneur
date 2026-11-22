/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.crazycapy.randonneur.MainActivity
import com.crazycapy.randonneur.R
import com.crazycapy.randonneur.cache.PreparePhase
import com.crazycapy.randonneur.cache.PrepareProgress
import com.crazycapy.randonneur.cache.PrepareProgressInput
import com.crazycapy.randonneur.cache.PrepareState
import com.crazycapy.randonneur.cache.RouteCache
import com.crazycapy.randonneur.cache.RouteOfflinePack

internal object PrepareActions {
    const val PAUSE = "com.crazycapy.randonneur.action.PREPARE_PAUSE"
    const val RESUME = "com.crazycapy.randonneur.action.PREPARE_RESUME"
    const val CANCEL = "com.crazycapy.randonneur.action.PREPARE_CANCEL"
}

internal object PrepareNotifications {

    const val CHANNEL_ID = "offline_prepare"
    const val NOTIFICATION_ID = 2

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Offline preparation",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Offline route pack progress"
            setShowBadge(false)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        state: PrepareState,
        recoveryActions: Boolean,
    ): Notification {
        val input = inputFor(state)
        val percent = PrepareProgress.percent(input)
        val text = PrepareProgress.summary(input)
        val paused = state.paused
        val pauseLabel = if (paused) "Resume" else "Pause"
        val pauseIcon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_notification)
            .setContentTitle(PrepareProgress.title(input))
            .setContentText(text)
            .setSubText(phaseLabel(state.phase))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(context))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(
                pauseIcon,
                pauseLabel,
                action(
                    context,
                    if (paused) PrepareActions.RESUME else PrepareActions.PAUSE,
                    11,
                    recoveryActions,
                ),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                action(context, PrepareActions.CANCEL, 12, recoveryActions),
            )
            .build()
    }

    fun inputFor(state: PrepareState): PrepareProgressInput {
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

    private fun action(
        context: Context,
        action: String,
        code: Int,
        recoveryActions: Boolean,
    ): PendingIntent {
        val intent = if (recoveryActions) {
            Intent(context, PrepareControlReceiver::class.java).setAction(action)
        } else {
            Intent(context, PrepareService::class.java).setAction(action)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (recoveryActions) {
            PendingIntent.getBroadcast(context, code, intent, flags)
        } else {
            PendingIntent.getService(context, code, intent, flags)
        }
    }

    private fun openIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
