/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.RouteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class CorosStatus {
    NOT_CONNECTED,
    WAITING,
    CONNECTED,
    ERROR,
}

data class CorosSection(
    val lines: List<String>,
    val error: String? = null,
)

data class CorosSnapshot(
    val activities: CorosSection,
    val hrv: CorosSection,
    val recovery: CorosSection,
    val schedule: CorosSection,
    val fetchedAtMs: Long,
)

object CorosController {
    var status: CorosStatus by mutableStateOf(CorosStatus.NOT_CONNECTED)
        private set
    var loginUrl: String? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var snapshot: CorosSnapshot? by mutableStateOf(null)
        private set
    var lastSyncMs: Long? by mutableStateOf(null)
        private set
    var todayWorkout: String? by mutableStateOf(null)
        private set

    @Volatile
    private var token: CorosToken? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(context: Context) {
        val app = context.applicationContext
        val loaded = CorosTokenStore.load(app)
        if (loaded != null && loaded.issuer == RideStore.corosRegion.issuer) {
            token = loaded
            status = CorosStatus.CONNECTED
        } else {
            if (loaded != null) CorosTokenStore.clear(app)
            token = null
            status = CorosStatus.NOT_CONNECTED
        }
    }

    fun setRegion(context: Context, region: CorosRegion) {
        val app = context.applicationContext
        if (RideStore.corosRegion == region) return
        RideStore.corosRegion = region
        RouteStore.saveSettings(app)
        if (token != null) {
            clearState(app)
        } else if (status == CorosStatus.WAITING) {
            cancelLogin()
        } else if (status == CorosStatus.ERROR) {
            error = null
            status = CorosStatus.NOT_CONNECTED
        }
    }

    fun connect(context: Context) {
        val app = context.applicationContext
        if (status == CorosStatus.WAITING || status == CorosStatus.CONNECTED) {
            if (status == CorosStatus.CONNECTED) refresh(app)
            return
        }
        if (token != null) {
            CorosTokenStore.clear(app)
            token = null
        }
        val region = RideStore.corosRegion
        job?.cancel()
        error = null
        loginUrl = null
        snapshot = null
        lastSyncMs = null
        status = CorosStatus.WAITING
        job = scope.launch {
            try {
                val clientId = CorosOAuth.register(region.issuer)
                val session = CorosOAuth.createLoginSession(region.issuer, clientId)
                loginUrl = session.loginUrl
                openBrowser(app, session.loginUrl)
                val ticket = CorosOAuth.awaitLoginTicket(region.issuer, session)
                if (status != CorosStatus.WAITING) return@launch
                val authorized = CorosOAuth.authorize(region.issuer, clientId, ticket)
                val fresh = CorosOAuth.exchange(
                    region.issuer,
                    clientId,
                    authorized.code,
                    authorized.verifier,
                    region,
                )
                CorosTokenStore.save(app, fresh)
                token = fresh
                loginUrl = null
                error = null
                status = CorosStatus.CONNECTED
                fetchData(app, fresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (status == CorosStatus.WAITING) {
                    loginUrl = null
                    status = CorosStatus.ERROR
                    error = failure.message ?: "COROS sign-in failed"
                }
            }
        }
    }

    fun cancelLogin() {
        if (status != CorosStatus.WAITING) return
        job?.cancel()
        job = null
        loginUrl = null
        error = null
        status = CorosStatus.NOT_CONNECTED
    }

    fun disconnect(context: Context) {
        clearState(context.applicationContext)
    }

    fun openLogin(context: Context) {
        val url = loginUrl ?: return
        openBrowser(context.applicationContext, url)
    }

    fun hasLiveSession(): Boolean = token != null

    fun openCorosApp(context: Context): Boolean {
        val app = context.applicationContext
        val intent = app.packageManager.getLaunchIntentForPackage(COROS_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(intent) }.isSuccess
    }

    suspend fun acquireLiveToken(
        context: Context,
        rejectedAccessToken: String? = null,
    ): CorosToken? {
        val current = token ?: return null
        if (rejectedAccessToken != null && current.accessToken != rejectedAccessToken) {
            return current
        }
        if (rejectedAccessToken == null && !current.isExpired(System.currentTimeMillis())) {
            return current
        }
        return try {
            val fresh = CorosOAuth.refresh(current)
            if (token?.accessToken == current.accessToken) {
                CorosTokenStore.save(context.applicationContext, fresh)
                token = fresh
            }
            fresh
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        if (loading || status == CorosStatus.WAITING) return
        val current = token ?: run {
            status = CorosStatus.NOT_CONNECTED
            return
        }
        scope.launch {
            loading = true
            error = null
            try {
                val fresh = ensureFresh(current)
                if (fresh !== current) {
                    CorosTokenStore.save(app, fresh)
                    token = fresh
                }
                fetchData(app, fresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = CorosStatus.ERROR
                error = failure.message ?: "Could not load COROS data"
            } finally {
                loading = false
            }
        }
    }

    private fun clearState(context: Context) {
        job?.cancel()
        job = null
        CorosTokenStore.clear(context)
        token = null
        loginUrl = null
        error = null
        snapshot = null
        lastSyncMs = null
        todayWorkout = null
        loading = false
        status = CorosStatus.NOT_CONNECTED
        CorosLiveMonitor.stop()
        RideStore.corosTodayWorkout = null
    }

    private suspend fun ensureFresh(current: CorosToken): CorosToken =
        if (current.isExpired(System.currentTimeMillis())) CorosOAuth.refresh(current) else current

    private suspend fun fetchData(context: Context, current: CorosToken) {
        loading = true
        error = null
        try {
            val client = CorosMcpClient(current.issuer)
            client.initialize(current.accessToken)
            val activities = section(current, client, "querySportRecords", activityArguments())
            val hrv = section(current, client, "querySleepHrv", JSONObject())
            val recovery = section(current, client, "queryRecoveryStatus", JSONObject())
            val schedule = section(current, client, "queryTrainingSchedule", scheduleArguments())
            val fetchedAt = System.currentTimeMillis()
            snapshot = CorosSnapshot(activities, hrv, recovery, schedule, fetchedAt)
            lastSyncMs = fetchedAt
            todayWorkout = CorosLiveParser.parseTodayWorkout(schedule.lines, LocalDate.now())
            RideStore.corosTodayWorkout = todayWorkout
            if (listOf(activities, hrv, recovery, schedule).all { it.error != null }) {
                status = CorosStatus.ERROR
                error = "Could not load COROS data"
            } else {
                status = CorosStatus.CONNECTED
                error = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            status = CorosStatus.ERROR
            error = failure.message ?: "Could not load COROS data"
        } finally {
            loading = false
        }
    }

    private suspend fun section(
        current: CorosToken,
        client: CorosMcpClient,
        tool: String,
        arguments: JSONObject,
    ): CorosSection = try {
        CorosSection(client.callTool(current.accessToken, tool, arguments))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CorosSection(emptyList(), "Unavailable")
    }

    private fun activityArguments(): JSONObject {
        val end = LocalDate.now()
        val start = end.minusDays(13)
        return JSONObject().apply {
            put("startDate", start.format(DateTimeFormatter.BASIC_ISO_DATE))
            put("endDate", end.format(DateTimeFormatter.BASIC_ISO_DATE))
            put("limit", 20)
        }
    }

    private fun scheduleArguments(): JSONObject {
        val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        return JSONObject().apply {
            put("startDate", today)
            put("endDate", today)
        }
    }

    private fun openBrowser(context: Context, url: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    private const val COROS_PACKAGE = "com.yf.smart.coros.dist"
}
