/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import android.content.Context
import android.util.Log
import com.crazycapy.randonneur.BuildConfig
import com.crazycapy.randonneur.state.RideStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object CorosLiveMonitor {
    private const val TAG = "CorosLive"
    private const val INITIAL_DELAY_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var generation = 0
    private var scheduleLoadedFor: LocalDate? = null
    var diag: CorosLiveDiag by mutableStateOf(CorosLiveDiag())

    fun start(context: Context) {
        val app = context.applicationContext
        if (!RideStore.active || !CorosController.hasLiveSession()) {
            job?.cancel()
            job = null
            clearLiveStats()
            diag = CorosLiveDiag(state = "idle", error = "not active or no COROS session")
            log("idle (not connected)")
            return
        }
        if (job?.isActive == true) return
        val runId = ++generation
        diag = CorosLiveDiag(state = "start", lastPollMs = System.currentTimeMillis())
        log("start")
        job = scope.launch { runLoop(app, runId) }
    }

    fun pause() {
        generation++
        job?.cancel()
        job = null
        clearLiveStats()
        diag = diag.copy(state = "paused")
        log("paused")
    }

    fun resume(context: Context) {
        start(context)
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        scheduleLoadedFor = null
        clearLiveStats()
        RideStore.corosTodayWorkout = null
        diag = diag.copy(state = "stop")
        log("stop")
    }

    private suspend fun runLoop(app: Context, runId: Int) {
        var failures = 0
        var activity: CorosActivityRecord? = null
        delay(INITIAL_DELAY_MS)
        while (currentCoroutineContext().isActive && runId == generation) {
            if (!CorosPollPolicy.shouldPoll(
                    RideStore.active,
                    RideStore.paused || RideStore.autoPaused,
                )
            ) {
                return
            }
            diag = diag.copy(state = "polling", polls = diag.polls + 1, lastPollMs = System.currentTimeMillis())
            val token = CorosController.acquireLiveToken(app)
            if (token == null) {
                if (!CorosController.hasLiveSession()) {
                    clearLiveStats()
                    return
                }
                failures++
                RideStore.corosLiveStatus = CorosLiveStatus.ERROR
                diag = diag.copy(state = "error", failures = failures, error = "poll returned no result")
                delay(CorosPollPolicy.nextDelayMs(failures))
                continue
            }
            val result = try {
                val session = LiveSession(app, token)
                loadSchedule(session)
                poll(session, activity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("poll failed: ${failure.javaClass.simpleName}")
                diag = diag.copy(state = "error", error = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
                null
            }
            if (result == null) {
                failures++
                RideStore.corosLiveStatus = CorosLiveStatus.ERROR
                diag = diag.copy(state = "error", failures = failures, error = "poll returned no result")
                delay(CorosPollPolicy.nextDelayMs(failures))
            } else {
                failures = 0
                activity = result.activity
                apply(result)
                delay(CorosPollPolicy.BASE_INTERVAL_MS)
            }
        }
    }

    private suspend fun loadSchedule(session: LiveSession) {
        val today = LocalDate.now()
        if (scheduleLoadedFor == today) return
        try {
            val lines = session.call("queryTrainingSchedule", scheduleArguments(today))
            RideStore.corosTodayWorkout = CorosLiveParser.parseTodayWorkout(lines, today)
            diag = diag.copy(workout = RideStore.corosTodayWorkout ?: "none")
            scheduleLoadedFor = today
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    private suspend fun poll(session: LiveSession, known: CorosActivityRecord?): PollResult {
        var activity = known
        if (activity == null) {
            activity = detectActivity(session)
            if (activity == null) return PollResult(null, null)
        }
        val label = activity.labelId ?: return PollResult(null, null)
        val sport = activity.sportType ?: return PollResult(null, null)
        val window = CorosPollPolicy.liveWindow(
            nowSec = System.currentTimeMillis() / 1000L,
            activityStartSec = activity.startTimestampSec,
            activityEndSec = activity.endTimestampSec,
        ) ?: return PollResult(activity, null)
        val arguments = JSONObject().apply {
            put("labelId", label)
            put("sportType", sport)
            put("startTimestamp", window.startSec)
            put("endTimestamp", window.endSec)
        }
        val stats = CorosLiveParser.parseWindow(
            session.call("queryCustomActivityLapData", arguments),
        )
        diag = diag.copy(
            window = "hr=${stats.avgHr ?: stats.maxHr ?: "-"} cad=${stats.avgCadence ?: stats.maxCadence ?: "-"} pow=${stats.avgPowerW ?: stats.maxPowerW ?: "-"} samples=${stats.samples}",
        )
        if (stats.inProgress == false) return PollResult(null, null)
        return PollResult(activity, stats)
    }

    private suspend fun detectActivity(session: LiveSession): CorosActivityRecord? {
        val today = LocalDate.now()
        val records = CorosLiveParser.parseSportRecords(
            session.call("querySportRecords", recordArguments(today)),
        )
        val candidates = records
            .filter { it.date == null || CorosLiveParser.isDate(it.date, today) }
            .sortedByDescending { it.startTimestampSec ?: 0L }
            .take(CorosPollPolicy.MAX_ACTIVITY_CANDIDATES)
        diag = diag.copy(records = records.size, candidates = candidates.size)
        var detailChecks = 0
        for (record in candidates) {
            if (record.inProgress == true && record.labelId != null && record.sportType != null) {
                diag = diag.copy(activity = "${record.title ?: "activity"} ${record.labelId}/${record.sportType} inProgress=true")
                return record
            }
            if (record.inProgress == false) continue
            val label = record.labelId ?: continue
            val sport = record.sportType ?: continue
            if (detailChecks++ >= CorosPollPolicy.MAX_DETAIL_CHECKS) break
            val detail = CorosLiveParser.parseActivityDetail(
                session.call(
                    "getActivityDetail",
                    JSONObject().apply {
                        put("labelId", label)
                        put("sportType", sport)
                    },
                ),
            )
            if (detail.inProgress) {
                diag = diag.copy(activity = "${record.title ?: "activity"} $label/$sport inProgress=true")
                return record.copy(inProgress = true, labelId = label, sportType = sport)
            }
        }
        diag = diag.copy(activity = "none in progress today")
        return null
    }

    private fun apply(result: PollResult) {
        val activity = result.activity
        if (activity == null) {
            clearLiveStats()
            return
        }
        RideStore.corosLiveStatus = CorosLiveStatus.ACTIVE
        RideStore.corosLiveSource = LiveSource.COROS
        diag = diag.copy(state = "active", lastDataMs = System.currentTimeMillis(), error = null)
        result.stats?.let { stats ->
            (stats.avgHr ?: stats.maxHr)?.let { RideStore.corosHr = it }
            (stats.avgCadence ?: stats.maxCadence)?.let {
                RideStore.corosCadence = it
                RideStore.cadenceSource = LiveSource.COROS
            }
            (stats.avgPowerW ?: stats.maxPowerW)?.let { RideStore.corosPowerW = it }
        }
        RideStore.corosLiveUpdatedMs = System.currentTimeMillis()
    }

    private fun clearLiveStats() {
        RideStore.corosLiveStatus = CorosLiveStatus.IDLE
        RideStore.corosLiveSource = LiveSource.NONE
        RideStore.corosHr = null
        RideStore.corosCadence = null
        RideStore.corosPowerW = null
        RideStore.corosLiveUpdatedMs = null
        if (RideStore.cadenceSource == LiveSource.COROS) RideStore.cadenceSource = LiveSource.NONE
    }

    private fun recordArguments(today: LocalDate): JSONObject = JSONObject().apply {
        put("startDate", today.format(DateTimeFormatter.BASIC_ISO_DATE))
        put("endDate", today.format(DateTimeFormatter.BASIC_ISO_DATE))
        put("limit", CorosPollPolicy.MAX_ACTIVITY_CANDIDATES)
    }

    private fun scheduleArguments(today: LocalDate): JSONObject = JSONObject().apply {
        put("startDate", today.format(DateTimeFormatter.BASIC_ISO_DATE))
        put("endDate", today.format(DateTimeFormatter.BASIC_ISO_DATE))
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private data class PollResult(
        val activity: CorosActivityRecord?,
        val stats: CorosWindowStats?,
    )

    private class LiveSession(app: Context, initial: CorosToken) {
        private val context = app.applicationContext
        private var token = initial
        private var client = CorosMcpClient(initial.issuer)

        suspend fun call(tool: String, arguments: JSONObject): List<String> {
            try {
                return client.callTool(token.accessToken, tool, arguments)
            } catch (failure: CorosMcpException) {
                if (failure.code != 401) throw failure
                val fresh = CorosController.acquireLiveToken(
                    context,
                    rejectedAccessToken = token.accessToken,
                ) ?: throw failure
                token = fresh
                client = CorosMcpClient(fresh.issuer)
                return client.callTool(fresh.accessToken, tool, arguments)
            }
        }
    }
}
