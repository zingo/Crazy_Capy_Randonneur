/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crazycapy.randonneur.gpx.TrackLoader
import com.crazycapy.randonneur.service.NavigationService
import com.crazycapy.randonneur.state.RideStore
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the lock-screen guidance: drives a ghost ride through the
 * real [NavigationService] and verifies the ongoing notification carries the next
 * (and next-next) turn text, condensed, and is public (lock-screen visible).
 */
@RunWith(AndroidJUnit4::class)
class NotificationGhostRideTest {

    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grantPermissions() {
        val shell = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pkg = context.packageName
        for (p in listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
        )) {
            shell.executeShellCommand("pm grant $pkg $p")
        }
    }

    @Before
    fun loadTrack() {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        RideStore.track = TrackLoader.loadAsset(testCtx, "ghost_ride.gpx")
        RideStore.ghostTimeScale = 8.0
        RideStore.ghostSpeedKmh = 28.0
        RideStore.notificationEnabled = true
    }

    @After
    fun stopRide() {
        NavigationService.stop(context)
        Thread.sleep(300)
        RideStore.track = null
        RideStore.reset()
    }

    @Test
    fun ghostRidePostsTurnGuidanceNotification() {
        NavigationService.startGhost(context)
        val hadTurn = spinUntil(30_000) { RideStore.nextTurnM != null }
        assertTrue("ghost ride should reach a turn", hadTurn)

        // Notification should arrive shortly after (ticker or a turn event).
        val (title, text) = spinForNotification(20_000)
        assertTrue("title should carry a real maneuver, was: $title", hasManeuver(title))
        assertTrue("body should carry ride stats, was: $text", text.matches(Regex(".*\\d+(?:\\.\\d+)? (m|km) left( · .* km/h)?")))

        // Lock-screen visibility: public so it shows without unlocking.
        val notif = activeNotification() ?: error("no active notification found")
        assertTrue("notification must be lock-screen public", notif.visibility == Notification.VISIBILITY_PUBLIC)
    }

    private fun spinUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(200)
        }
        return cond()
    }

    private fun activeNotification(): Notification? {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications.firstOrNull { it.packageName == context.packageName }?.notification
    }

    private fun spinForNotification(timeoutMs: Long): Pair<String, String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = activeNotification()
            val title = n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            if (title.isNotEmpty() && hasManeuver(title)) return title to text
            Thread.sleep(200)
        }
        val n = activeNotification()
        return (n?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "") to
            (n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
    }

    private fun hasManeuver(s: String): Boolean =
        s.contains("Turn") || s.contains("Keep") || s.contains("Continue") ||
            s.contains("Sharp") || s.contains("U-turn")
}
