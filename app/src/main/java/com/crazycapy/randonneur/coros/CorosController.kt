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
import com.crazycapy.randonneur.state.RideHistory
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

data class CorosRideSync(
    val syncing: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val completedAtMs: Long? = null,
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
    var latestActivity: CorosLatestActivity? by mutableStateOf(null)
        private set
    var lastActivityError: String? by mutableStateOf(null)
        private set
    var activityLoading: Boolean by mutableStateOf(false)
        private set
    var rideSyncStates: Map<String, CorosRideSync> by mutableStateOf(emptyMap())
        private set

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
        latestActivity = null
        lastActivityError = null
        activityLoading = false
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

    fun isConnected(): Boolean = token != null

    fun refreshLatestActivity(context: Context) {
        val app = context.applicationContext
        val current = token ?: return
        if (activityLoading) return
        scope.launch {
            try {
                val fresh = ensureFresh(current)
                if (fresh !== current) {
                    CorosTokenStore.save(app, fresh)
                    token = fresh
                }
                val client = CorosMcpClient(fresh.issuer)
                client.initialize(fresh.accessToken)
                fetchLatestActivity(client, fresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastActivityError = failure.message ?: "Could not load latest activity"
                activityLoading = false
            }
        }
    }

    fun syncRide(context: Context, ride: RideHistory.SavedRide) {
        val app = context.applicationContext
        val current = token ?: run {
            updateRideSync(
                ride.id,
                CorosRideSync(error = "COROS not connected", completedAtMs = System.currentTimeMillis()),
            )
            return
        }
        if (rideSyncStates[ride.id]?.syncing == true) return
        updateRideSync(ride.id, CorosRideSync(syncing = true))
        scope.launch {
            try {
                val fresh = ensureFresh(current)
                if (fresh !== current) {
                    CorosTokenStore.save(app, fresh)
                    token = fresh
                }
                val client = CorosMcpClient(fresh.issuer)
                client.initialize(fresh.accessToken)
                val records = CorosActivityParser.parseSportRecords(
                    client.callTool(fresh.accessToken, "querySportRecords", activityArguments()),
                )
                val rideStart = ride.startedAtMs / 1000L
                val length = (if (ride.totalSec > 0) ride.totalSec else ride.durationSec)
                    .coerceAtLeast(0L)
                val match = CorosRideMatcher.select(rideStart, rideStart + length, records)
                if (match == null) {
                    val existing = RideHistory.loadEnrichment(app, ride.id)
                    updateRideSync(
                        ride.id,
                        CorosRideSync(
                            message = if (existing == null) {
                                "No overlapping COROS activity in the last 14 days."
                            } else {
                                "No new overlap; keeping the existing COROS match."
                            },
                            completedAtMs = System.currentTimeMillis(),
                        ),
                    )
                    return@launch
                }
                val label = match.record.labelId
                val sport = match.record.sportType
                if (label == null || sport == null) {
                    updateRideSync(
                        ride.id,
                        CorosRideSync(
                            error = "Matched COROS activity has no id",
                            completedAtMs = System.currentTimeMillis(),
                        ),
                    )
                    return@launch
                }
                val idArguments = JSONObject()
                    .put("labelId", label)
                    .put("sportType", sport)
                val parsed = CorosActivityParser.parseActivityDetail(
                    client.callTool(fresh.accessToken, "getActivityDetail", idArguments),
                )
                val detail = parsed.copy(
                    labelId = label,
                    sportType = sport,
                    title = parsed.title ?: match.record.title,
                    date = parsed.date ?: match.record.date,
                    startTimestampSec = parsed.startTimestampSec ?: match.activityStartSec,
                )
                val laps = try {
                    CorosActivityParser.parseLaps(
                        client.callTool(fresh.accessToken, "queryActivityLapData", idArguments),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                val now = System.currentTimeMillis()
                val enrichment = CorosEnrichment.from(match, detail, laps, now)
                RideHistory.saveEnrichment(app, ride.id, enrichment)
                updateRideSync(
                    ride.id,
                    CorosRideSync(
                        message = "Matched ${detail.title ?: "COROS activity"} · " +
                            "overlap ${match.overlapSec / 60} min",
                        completedAtMs = now,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updateRideSync(
                    ride.id,
                    CorosRideSync(
                        error = failure.message ?: "Could not sync this ride",
                        completedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun updateRideSync(id: String, state: CorosRideSync) {
        rideSyncStates = rideSyncStates + (id to state)
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
        latestActivity = null
        lastActivityError = null
        activityLoading = false
        rideSyncStates = emptyMap()
        loading = false
        status = CorosStatus.NOT_CONNECTED
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
            val schedule = section(current, client, "queryTrainingSchedule", JSONObject())
            fetchLatestActivity(client, current)
            val fetchedAt = System.currentTimeMillis()
            snapshot = CorosSnapshot(activities, hrv, recovery, schedule, fetchedAt)
            lastSyncMs = fetchedAt
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

    private suspend fun fetchLatestActivity(client: CorosMcpClient, current: CorosToken) {
        activityLoading = true
        lastActivityError = null
        try {
            val records = CorosActivityParser.parseSportRecords(
                client.callTool(current.accessToken, "querySportRecords", latestActivityArguments()),
            )
            val newest = CorosActivityParser.latest(records)
            if (newest == null) {
                latestActivity = null
                return
            }
            val label = newest.labelId
            val sport = newest.sportType
            if (label == null || sport == null) {
                latestActivity = null
                lastActivityError = "Latest activity has no id"
                return
            }
            val idArguments = JSONObject()
                .put("labelId", label)
                .put("sportType", sport)
            val parsed = CorosActivityParser.parseActivityDetail(
                client.callTool(current.accessToken, "getActivityDetail", idArguments),
            )
            val detail = parsed.copy(
                labelId = label,
                sportType = sport,
                title = parsed.title ?: newest.title,
                date = parsed.date ?: newest.date,
                startTimestampSec = parsed.startTimestampSec ?: newest.startTimestampSec,
            )
            val laps = try {
                CorosActivityParser.parseLaps(
                    client.callTool(current.accessToken, "queryActivityLapData", idArguments),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            latestActivity = CorosLatestActivity(detail, laps)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            latestActivity = null
            lastActivityError = failure.message ?: "Could not load latest activity"
        } finally {
            activityLoading = false
        }
    }

    private fun latestActivityArguments(): JSONObject {
        val end = LocalDate.now()
        val start = end.minusDays(6)
        return JSONObject().apply {
            put("startDate", start.format(DateTimeFormatter.BASIC_ISO_DATE))
            put("endDate", end.format(DateTimeFormatter.BASIC_ISO_DATE))
            put("limit", 10)
        }
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

    private fun openBrowser(context: Context, url: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
