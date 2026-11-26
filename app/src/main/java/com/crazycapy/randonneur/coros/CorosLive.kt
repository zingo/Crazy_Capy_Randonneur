/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class CorosLiveStatus {
    IDLE,
    ACTIVE,
    ERROR,
}

enum class LiveSource {
    NONE,
    BLE,
    COROS,
    DEBUG,
}

data class CorosActivityRecord(
    val labelId: String?,
    val sportType: Int?,
    val date: String?,
    val startTimestampSec: Long?,
    val endTimestampSec: Long?,
    val inProgress: Boolean?,
    val title: String?,
)

data class CorosActivityDetail(
    val inProgress: Boolean,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgCadence: Int?,
    val avgPowerW: Int?,
)

data class CorosWindowStats(
    val inProgress: Boolean?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgCadence: Int?,
    val maxCadence: Int?,
    val avgPowerW: Int?,
    val maxPowerW: Int?,
    val samples: Int,
)

data class CorosLiveDiag(
    val state: String = "off",
    val polls: Int = 0,
    val failures: Int = 0,
    val lastPollMs: Long? = null,
    val lastDataMs: Long? = null,
    val records: Int = 0,
    val candidates: Int = 0,
    val activity: String? = null,
    val window: String? = null,
    val workout: String? = null,
    val error: String? = null,
)

data class CorosTimeWindow(
    val startSec: Long,
    val endSec: Long,
)

object CorosPollPolicy {
    const val BASE_INTERVAL_MS = 30_000L
    const val MAX_INTERVAL_MS = 300_000L
    const val MAX_ACTIVITY_CANDIDATES = 5
    const val MAX_DETAIL_CHECKS = 2
    const val WINDOW_SECONDS = 300L

    fun shouldPoll(rideActive: Boolean, paused: Boolean): Boolean = rideActive && !paused

    fun nextDelayMs(failures: Int): Long {
        if (failures <= 0) return BASE_INTERVAL_MS
        val shift = failures.coerceAtMost(4)
        return (BASE_INTERVAL_MS shl shift).coerceAtMost(MAX_INTERVAL_MS)
    }

    fun liveWindow(
        nowSec: Long,
        activityStartSec: Long?,
        activityEndSec: Long?,
    ): CorosTimeWindow? {
        val end = when {
            activityEndSec != null && activityEndSec in 1 until nowSec -> activityEndSec
            else -> nowSec
        }
        val earliest = activityStartSec?.takeIf { it > 0 } ?: (end - WINDOW_SECONDS)
        val start = maxOf(end - WINDOW_SECONDS, earliest)
        if (end <= start) return null
        return CorosTimeWindow(start, end)
    }
}

object CorosLiveParser {
    private const val MAX_DEPTH = 12
    private val NUMBERED_BLOCK = Regex("^\\s*\\d+\\.\\s+")
    private val LABEL_TEXT = Regex(
        "(?i)\\b(?:label\\s*id|label_id|activity\\s*id|activity_id)\\b\\s*[:=]\\s*\"?([A-Za-z0-9_-]+)",
    )
    private val SPORT_TEXT = Regex("(?i)\\bSport\\s*\\(\\s*(\\d+)\\s*\\)")
    private val SPORT_FIELD_TEXT = Regex("(?i)\\bsport_?type\\b\\s*[:=]\\s*(\\d+)")
    private val DATE_TEXT = Regex("\\b(20\\d{2}-?\\d{2}-?\\d{2})\\b")
    private val START_TEXT = Regex(
        "(?i)\\bstart(?:_?timestamp|_?time|_?sec)?\\b\\s*[:=]\\s*(\\d{9,13})",
    )
    private val END_TEXT = Regex(
        "(?i)\\bend(?:_?timestamp|_?time|_?sec)?\\b\\s*[:=]\\s*(\\d{9,13})",
    )
    private val AVG_HR_TEXT = Regex("(?i)(?:average|avg)\\.?\\s*heart\\s*rate\\s*[:=]?\\s*(\\d+)")
    private val MAX_HR_TEXT = Regex("(?i)max(?:imum)?\\.?\\s*heart\\s*rate\\s*[:=]?\\s*(\\d+)")
    private val AVG_CADENCE_TEXT = Regex("(?i)(?:average|avg)\\.?\\s*cadence\\s*[:=]?\\s*(\\d+)")
    private val MAX_CADENCE_TEXT = Regex("(?i)max(?:imum)?\\.?\\s*cadence\\s*[:=]?\\s*(\\d+)")
    private val AVG_POWER_TEXT = Regex("(?i)(?:average|avg)\\.?\\s*power\\s*[:=]?\\s*(\\d+)")
    private val MAX_POWER_TEXT = Regex("(?i)max(?:imum)?\\.?\\s*power\\s*[:=]?\\s*(\\d+)")
    private val AVG_HR_KEYS = arrayOf(
        "avgHr", "avg_hr", "averageHr", "average_hr", "averageHeartRate", "average_heart_rate",
    )
    private val MAX_HR_KEYS = arrayOf("maxHr", "max_hr", "maxHeartRate", "max_heart_rate")
    private val AVG_CADENCE_KEYS = arrayOf(
        "avgCadence", "avg_cadence", "averageCadence", "average_cadence",
    )
    private val MAX_CADENCE_KEYS = arrayOf("maxCadence", "max_cadence", "maximumCadence")
    private val AVG_POWER_KEYS = arrayOf(
        "avgPower", "avg_power", "averagePower", "average_power", "power",
    )
    private val MAX_POWER_KEYS = arrayOf("maxPower", "max_power", "maximumPower")
    private val LABEL_KEYS = arrayOf(
        "labelId", "label_id", "activityId", "activity_id", "id",
    )
    private val SPORT_KEYS = arrayOf(
        "sportType", "sport_type", "sportTypeCode", "sport_type_code", "sport",
    )
    private val DATE_KEYS = arrayOf("date", "day", "startDate", "start_date", "startDay")
    private val START_KEYS = arrayOf(
        "startTimestamp", "start_timestamp", "startTime", "start_time", "startTimeSec",
        "start_time_sec", "startSec", "start",
    )
    private val END_KEYS = arrayOf(
        "endTimestamp", "end_timestamp", "endTime", "end_time", "endTimeSec",
        "end_time_sec", "endSec", "end",
    )
    private val STATUS_KEYS = arrayOf("activityStatus", "activity_status", "status", "state")
    private val IN_PROGRESS_KEYS = arrayOf("inProgress", "in_progress")
    private val NAME_KEYS = arrayOf(
        "name", "title", "workoutName", "workout_name", "courseName", "course_name",
    )
    private val WORKOUT_LIST_KEYS = arrayOf(
        "workouts", "courses", "items", "activities", "schedule",
    )
    private val DURATION_KEYS = arrayOf(
        "time", "time_s", "totalLength", "duration", "duration_s", "elapsedTime",
    )

    fun parseSportRecords(lines: List<String>): List<CorosActivityRecord> =
        parseSportRecords(lines.joinToString("\n"))

    fun parseSportRecords(text: String): List<CorosActivityRecord> {
        if (text.isBlank()) return emptyList()
        val fromJson = jsonSportRecords(text)
        if (fromJson.isNotEmpty()) return fromJson
        return textSportRecords(text)
    }

    fun parseActivityDetail(lines: List<String>): CorosActivityDetail {
        val text = lines.joinToString("\n")
        val objects = jsonObjects(text)
        val jsonStatus = objects.firstNotNullOfOrNull { obj ->
            firstBool(obj, IN_PROGRESS_KEYS) ?: statusInProgress(firstString(obj, STATUS_KEYS))
        }
        val textStatus = statusInProgress(text)
        val stats = jsonStats(objects) ?: textStats(text)
        return CorosActivityDetail(
            inProgress = jsonStatus ?: textStatus ?: false,
            avgHr = stats?.avgHr,
            maxHr = stats?.maxHr,
            avgCadence = stats?.avgCadence,
            avgPowerW = stats?.avgPowerW,
        )
    }

    fun parseWindow(lines: List<String>): CorosWindowStats {
        val text = lines.joinToString("\n")
        val objects = jsonObjects(text)
        val jsonStatus = objects.firstNotNullOfOrNull { obj ->
            firstBool(obj, IN_PROGRESS_KEYS) ?: statusInProgress(firstString(obj, STATUS_KEYS))
        }
        val textStatus = statusInProgress(text)
        val stats = jsonStats(objects) ?: textStats(text)
        return CorosWindowStats(
            inProgress = jsonStatus ?: textStatus,
            avgHr = stats?.avgHr,
            maxHr = stats?.maxHr,
            avgCadence = stats?.avgCadence,
            maxCadence = stats?.maxCadence,
            avgPowerW = stats?.avgPowerW,
            maxPowerW = stats?.maxPowerW,
            samples = stats?.samples ?: 0,
        )
    }

    fun parseTodayWorkout(lines: List<String>, today: LocalDate): String? {
        val text = lines.joinToString("\n").trim()
        if (text.isEmpty()) return null
        return jsonTodayWorkout(text, today) ?: textTodayWorkout(text, today)
    }

    fun isDate(value: String?, today: LocalDate): Boolean {
        val clean = value?.trim()?.replace("-", "") ?: return false
        return clean == today.format(DateTimeFormatter.BASIC_ISO_DATE)
    }

    fun statusInProgress(status: String?): Boolean? {
        val text = status?.lowercase()?.trim() ?: return null
        if (text.isEmpty()) return null
        if (text.contains("inactive")) return false
        return when {
            text.contains("in progress") || text.contains("in_progress") ||
                text.contains("ongoing") || Regex("\\bactive\\b").containsMatchIn(text) -> true
            text.contains("complete") || text.contains("finish") || text.contains("ended") ||
                text.contains("done") -> false
            else -> null
        }
    }

    private fun jsonSportRecords(text: String): List<CorosActivityRecord> {
        val root = jsonRoot(text) ?: return emptyList()
        val objects = mutableListOf<JSONObject>()
        collectObjects(root, objects, 0)
        return objects.mapNotNull(::recordFromObject)
            .distinctBy { it.labelId to it.startTimestampSec }
    }

    private fun recordFromObject(obj: JSONObject): CorosActivityRecord? {
        val label = firstString(obj, LABEL_KEYS)
        val sport = firstInt(obj, SPORT_KEYS)
        val start = firstTimestamp(obj, START_KEYS)
        val end = firstTimestamp(obj, END_KEYS)
        val date = firstString(obj, DATE_KEYS)
        val statusText = firstString(obj, STATUS_KEYS)
        val status = firstBool(obj, IN_PROGRESS_KEYS) ?: statusInProgress(statusText)
        if (label == null && sport == null) return null
        if (sport == null && start == null && date == null && status == null) return null
        return CorosActivityRecord(
            labelId = label,
            sportType = sport,
            date = date,
            startTimestampSec = start,
            endTimestampSec = end,
            inProgress = status,
            title = firstString(obj, NAME_KEYS),
        )
    }

    private fun textSportRecords(text: String): List<CorosActivityRecord> {
        val blocks = mutableListOf<MutableList<String>>()
        text.lines().forEach { line ->
            if (NUMBERED_BLOCK.containsMatchIn(line)) blocks.add(mutableListOf())
            if (blocks.isEmpty() && line.isNotBlank()) blocks.add(mutableListOf())
            if (blocks.isNotEmpty()) blocks.last().add(line)
        }
        return blocks.mapNotNull(::recordFromTextBlock)
    }

    private fun recordFromTextBlock(block: List<String>): CorosActivityRecord? {
        val text = block.joinToString("\n")
        val label = LABEL_TEXT.find(text)?.groupValues?.get(1)
        val sport = (SPORT_TEXT.find(text) ?: SPORT_FIELD_TEXT.find(text))
            ?.groupValues?.get(1)?.toIntOrNull()
        if (label == null && sport == null) return null
        return CorosActivityRecord(
            labelId = label,
            sportType = sport,
            date = DATE_TEXT.find(text)?.groupValues?.get(1),
            startTimestampSec = START_TEXT.find(text)?.groupValues?.get(1)
                ?.toLongOrNull()?.let(::normalizeTimestamp),
            endTimestampSec = END_TEXT.find(text)?.groupValues?.get(1)
                ?.toLongOrNull()?.let(::normalizeTimestamp),
            inProgress = statusInProgress(text),
            title = null,
        )
    }

    private fun jsonObjects(text: String): List<JSONObject> {
        val root = jsonRoot(text) ?: return emptyList()
        val objects = mutableListOf<JSONObject>()
        collectObjects(root, objects, 0)
        return objects
    }

    private fun jsonStats(objects: List<JSONObject>): Stats? {
        var samples = 0
        val hr = Acc()
        val maxHr = Acc()
        val cadence = Acc()
        val maxCadence = Acc()
        val power = Acc()
        val maxPower = Acc()
        objects.forEach { obj ->
            val avgHr = firstInt(obj, AVG_HR_KEYS)
            val lapMaxHr = firstInt(obj, MAX_HR_KEYS)
            val avgCadence = firstInt(obj, AVG_CADENCE_KEYS)
            val lapMaxCadence = firstInt(obj, MAX_CADENCE_KEYS)
            val avgPower = firstInt(obj, AVG_POWER_KEYS)
            val lapMaxPower = firstInt(obj, MAX_POWER_KEYS)
            if (avgHr == null && lapMaxHr == null && avgCadence == null &&
                lapMaxCadence == null && avgPower == null && lapMaxPower == null
            ) {
                return@forEach
            }
            val weight = firstDouble(obj, DURATION_KEYS)?.takeIf { it > 0.0 } ?: 1.0
            hr.add(avgHr, null, weight)
            maxHr.add(null, lapMaxHr, weight)
            cadence.add(avgCadence, null, weight)
            maxCadence.add(null, lapMaxCadence, weight)
            power.add(avgPower, null, weight)
            maxPower.add(null, lapMaxPower, weight)
            samples++
        }
        if (samples == 0) return null
        return Stats(
            avgHr = hr.average(),
            maxHr = maxHr.maximum(),
            avgCadence = cadence.average(),
            maxCadence = maxCadence.maximum(),
            avgPowerW = power.average(),
            maxPowerW = maxPower.maximum(),
            samples = samples,
        )
    }

    private fun textStats(text: String): Stats? {
        val avgHr = averageMatches(AVG_HR_TEXT, text)
        val maxHr = maxMatches(MAX_HR_TEXT, text)
        val avgCadence = averageMatches(AVG_CADENCE_TEXT, text)
        val maxCadence = maxMatches(MAX_CADENCE_TEXT, text)
        val avgPower = averageMatches(AVG_POWER_TEXT, text)
        val maxPower = maxMatches(MAX_POWER_TEXT, text)
        if (avgHr == null && maxHr == null && avgCadence == null &&
            maxCadence == null && avgPower == null && maxPower == null
        ) {
            return null
        }
        return Stats(avgHr, maxHr, avgCadence, maxCadence, avgPower, maxPower, 1)
    }

    private fun averageMatches(regex: Regex, text: String): Int? {
        val values = regex.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.toList()
        if (values.isEmpty()) return null
        return values.average().roundToInt()
    }

    private fun maxMatches(regex: Regex, text: String): Int? =
        regex.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.maxOrNull()

    private fun jsonTodayWorkout(text: String, today: LocalDate): String? {
        val objects = jsonObjects(text)
        for (obj in objects) {
            val date = firstString(obj, DATE_KEYS) ?: continue
            if (!isDate(date, today)) continue
            workoutName(obj)?.let { return compact(it) }
        }
        return null
    }

    private fun workoutName(obj: JSONObject): String? {
        firstString(obj, NAME_KEYS)?.let { return it }
        for (key in WORKOUT_LIST_KEYS) {
            val array = obj.optJSONArray(key) ?: continue
            for (index in 0 until array.length()) {
                val child = array.optJSONObject(index) ?: continue
                firstString(child, NAME_KEYS)?.let { return it }
            }
        }
        return null
    }

    private fun textTodayWorkout(text: String, today: LocalDate): String? {
        val basic = today.format(DateTimeFormatter.BASIC_ISO_DATE)
        val iso = today.toString()
        var afterToday = false
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val hasToday = trimmed.contains(iso) || trimmed.contains(basic)
            if (hasToday) {
                afterToday = true
                val rest = trimmed
                    .replace(iso, "")
                    .replace(basic, "")
                    .trim(' ', '-', '—', ':', '\t')
                if (looksLikeWorkout(rest)) return compact(rest)
                return@forEach
            }
            if (afterToday) {
                if (DATE_TEXT.containsMatchIn(trimmed)) return null
                if (looksLikeWorkout(trimmed)) {
                    return compact(trimmed.trimStart('-', '*', '•', '·').trim())
                }
            }
        }
        return null
    }

    private fun looksLikeWorkout(line: String): Boolean {
        val clean = line.trim().trimStart('-', '*', '•', '·').trim()
        if (clean.isEmpty()) return false
        val lower = clean.lowercase()
        if (lower.contains("training schedule") || lower.contains("planned workout") ||
            lower.contains("sport records") || lower.contains("no workout") ||
            lower.contains("no planned")
        ) {
            return false
        }
        return !lower.contains("this week")
    }

    private fun compact(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        return if (clean.length > 80) clean.take(77) + "…" else clean
    }

    private data class Stats(
        val avgHr: Int?,
        val maxHr: Int?,
        val avgCadence: Int?,
        val maxCadence: Int?,
        val avgPowerW: Int?,
        val maxPowerW: Int?,
        val samples: Int,
    )

    private class Acc {
        private var weighted = 0.0
        private var weight = 0.0
        private var maximum: Int? = null

        fun add(value: Int?, maxValue: Int?, sampleWeight: Double) {
            if (value != null) {
                weighted += value * sampleWeight
                weight += sampleWeight
            }
            if (maxValue != null && (maximum == null || maxValue > maximum!!)) maximum = maxValue
        }

        fun average(): Int? = if (weight > 0.0) (weighted / weight).roundToInt() else null

        fun maximum(): Int? = maximum
    }

    private fun jsonRoot(text: String): Any? {
        val clean = text.trim()
        if (clean.startsWith("[")) return runCatching { JSONArray(clean) }.getOrNull()
        if (clean.startsWith("{")) return runCatching { JSONObject(clean) }.getOrNull()
        return null
    }

    private fun collectObjects(value: Any?, out: MutableList<JSONObject>, depth: Int) {
        if (depth > MAX_DEPTH) return
        when (value) {
            is JSONObject -> {
                out.add(value)
                value.keys().forEach { key -> collectObjects(value.opt(key), out, depth + 1) }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectObjects(value.opt(index), out, depth + 1)
                }
            }
        }
    }

    private fun firstString(obj: JSONObject, keys: Array<String>): String? {
        for (key in keys) {
            val value = obj.opt(key)
            when (value) {
                null, JSONObject.NULL -> Unit
                is String -> value.takeIf { it.isNotBlank() }?.let { return it }
                is Number, is Boolean -> return value.toString()
            }
        }
        return null
    }

    private fun firstInt(obj: JSONObject, keys: Array<String>): Int? {
        for (key in keys) {
            val value = obj.opt(key)
            when (value) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun firstDouble(obj: JSONObject, keys: Array<String>): Double? {
        for (key in keys) {
            val value = obj.opt(key)
            when (value) {
                is Number -> return value.toDouble()
                is String -> value.trim().toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun firstBool(obj: JSONObject, keys: Array<String>): Boolean? {
        for (key in keys) {
            when (val value = obj.opt(key)) {
                is Boolean -> return value
                is String -> when (value.trim().lowercase()) {
                    "true", "yes", "1" -> return true
                    "false", "no", "0" -> return false
                }
            }
        }
        return null
    }

    private fun firstTimestamp(obj: JSONObject, keys: Array<String>): Long? {
        for (key in keys) {
            val value = obj.opt(key)
            val parsed = parseTimestamp(value)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseTimestamp(value: Any?): Long? = when (value) {
        is Number -> normalizeTimestamp(value.toLong())
        is String -> value.trim().toLongOrNull()?.let(::normalizeTimestamp)
            ?: runCatching { Instant.parse(value).epochSecond }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(value.replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault()).toEpochSecond()
            }.getOrNull()
        else -> null
    }

    private fun normalizeTimestamp(value: Long): Long = when {
        value in 1L..2_000_000_000L -> value
        value in 1_000_000_000_000L..9_999_999_999_999L -> value / 1000L
        else -> value
    }
}
