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

data class CorosSportRecord(
    val labelId: String?,
    val sportType: Int?,
    val date: String?,
    val startTimestampSec: Long?,
    val endTimestampSec: Long?,
    val title: String?,
    val durationSec: Double? = null,
)

data class CorosHrZone(
    val zone: Int?,
    val label: String?,
    val minBpm: Int?,
    val maxBpm: Int?,
    val seconds: Double?,
    val percent: Double?,
)

data class CorosLap(
    val index: Int?,
    val durationSec: Double?,
    val distanceM: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgCadence: Int?,
    val maxCadence: Int?,
    val avgPowerW: Int?,
    val maxPowerW: Int?,
)

data class CorosActivityDetail(
    val labelId: String? = null,
    val sportType: Int? = null,
    val title: String? = null,
    val date: String? = null,
    val startTimestampSec: Long? = null,
    val durationSec: Double? = null,
    val distanceM: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val avgCadence: Int? = null,
    val maxCadence: Int? = null,
    val avgPowerW: Int? = null,
    val maxPowerW: Int? = null,
    val trainingLoad: Double? = null,
    val trainingEffect: Double? = null,
    val anaerobicTrainingEffect: Double? = null,
    val calories: Double? = null,
    val elevationGainM: Double? = null,
    val hrZones: List<CorosHrZone> = emptyList(),
)

data class CorosLatestActivity(
    val detail: CorosActivityDetail,
    val laps: List<CorosLap> = emptyList(),
)

object CorosActivityParser {
    private const val MAX_DEPTH = 12
    private val NUMBERED_BLOCK = Regex("^\\s*\\d+\\.\\s+")
    private val LAP_BLOCK = Regex("(?i)^\\s*(?:lap\\s*)?\\d+\\s*(?:[.):\\-].*)?$")
    private val LABEL_TEXT = Regex(
        "(?i)\\b(?:label\\s*id|label_id|activity\\s*id|activity_id)\\b\\s*[:=]\\s*\"?([A-Za-z0-9_-]+)",
    )
    private val SPORT_TEXT = Regex("(?i)\\bSport\\s*\\(\\s*(\\d+)\\s*\\)")
    private val SPORT_FIELD_TEXT = Regex("(?i)\\bsport_?type\\b\\s*[:=]\\s*(\\d+)")
    private val DATE_TEXT = Regex("\\b(20\\d{2}-?\\d{2}-?\\d{2})\\b")
    private val START_TEXT =
        Regex("(?i)\\bstart(?:_?timestamp|_?time|_?sec)?\\b\\s*[:=]\\s*(\\d{9,13})")
    private val END_TEXT =
        Regex("(?i)\\bend(?:_?timestamp|_?time|_?sec)?\\b\\s*[:=]\\s*(\\d{9,13})")
    private val AVG_HR_TEXT = Regex("(?i)(?:average|avg)\\.?\\s*heart\\s*rate\\s*[:=]?\\s*(\\d+)")
    private val MAX_HR_TEXT = Regex("(?i)max(?:imum)?\\.?\\s*heart\\s*rate\\s*[:=]?\\s*(\\d+)")
    private val AVG_CADENCE_TEXT = Regex("(?i)(?:average|avg)\\.?\\s*cadence\\s*[:=]?\\s*(\\d+)")
    private val MAX_CADENCE_TEXT = Regex("(?i)max(?:imum)?\\.?\\s*cadence\\s*[:=]?\\s*(\\d+)")
    private val AVG_POWER_TEXT = Regex("(?i)(?:average|avg)\\.?\\s*power\\s*[:=]?\\s*(\\d+)")
    private val MAX_POWER_TEXT = Regex("(?i)max(?:imum)?\\.?\\s*power\\s*[:=]?\\s*(\\d+)")
    private val DURATION_TEXT = Regex(
        "(?i)\\b(?:duration|elapsed(?:\\s*time)?|moving\\s*time|total\\s*time)\\b\\s*[:=]?\\s*" +
            "([0-9][0-9hHmMsS:.,\\s]*)",
    )
    private val DISTANCE_TEXT = Regex(
        "(?i)\\b(?:distance|total\\s*distance)\\b\\s*[:=]?\\s*([0-9][0-9.,]*\\s*(?:km|m|mi|mile|miles)?)",
    )
    private val TRAINING_LOAD_TEXT =
        Regex("(?i)\\btraining\\s*load\\b\\s*[:=]?\\s*(\\d+(?:\\.\\d+)?)")
    private val TRAINING_EFFECT_TEXT =
        Regex("(?i)\\b(?:aerobic\\s*)?training\\s*effect\\b\\s*[:=]?\\s*(\\d+(?:\\.\\d+)?)")
    private val ANAEROBIC_EFFECT_TEXT =
        Regex("(?i)\\banaerobic\\s*training\\s*effect\\b\\s*[:=]?\\s*(\\d+(?:\\.\\d+)?)")
    private val CALORIES_TEXT =
        Regex("(?i)\\b(?:calories|energy)\\b\\s*[:=]?\\s*(\\d+(?:\\.\\d+)?)")
    private val ELEVATION_TEXT = Regex(
        "(?i)\\b(?:elevation\\s*(?:gain)?|ascent|total\\s*ascent)\\b\\s*[:=]?\\s*" +
            "(\\d+(?:\\.\\d+)?)\\s*(?:m|meters?|metres?)?",
    )
    private val AVG_HR_KEYS = arrayOf(
        "avgHr", "avg_hr", "averageHr", "average_hr", "averageHeartRate", "average_heart_rate",
    )
    private val MAX_HR_KEYS = arrayOf("maxHr", "max_hr", "maxHeartRate", "max_heart_rate")
    private val AVG_CADENCE_KEYS =
        arrayOf("avgCadence", "avg_cadence", "averageCadence", "average_cadence")
    private val MAX_CADENCE_KEYS = arrayOf("maxCadence", "max_cadence", "maximumCadence")
    private val AVG_POWER_KEYS =
        arrayOf("avgPower", "avg_power", "averagePower", "average_power", "power")
    private val MAX_POWER_KEYS = arrayOf("maxPower", "max_power", "maximumPower")
    private val LABEL_KEYS = arrayOf("labelId", "label_id", "activityId", "activity_id", "id")
    private val SPORT_KEYS =
        arrayOf("sportType", "sport_type", "sportTypeCode", "sport_type_code", "sport")
    private val DATE_KEYS = arrayOf("date", "day", "startDate", "start_date", "startDay")
    private val START_KEYS = arrayOf(
        "startTimestamp", "start_timestamp", "startTime", "start_time", "startTimeSec",
        "start_time_sec", "startSec", "start",
    )
    private val END_KEYS = arrayOf(
        "endTimestamp", "end_timestamp", "endTime", "end_time", "endTimeSec",
        "end_time_sec", "endSec", "end",
    )
    private val NAME_KEYS =
        arrayOf("name", "title", "workoutName", "workout_name", "courseName", "course_name")
    private val DURATION_KEYS = arrayOf(
        "duration", "durationSec", "duration_s", "durationSeconds", "time", "time_s",
        "elapsedTime", "elapsed_time", "totalTime", "total_time", "totalLength",
    )
    private val DISTANCE_KEYS = arrayOf(
        "distance", "distanceM", "distance_m", "distanceMeters", "totalDistance",
        "total_distance",
    )
    private val TRAINING_LOAD_KEYS =
        arrayOf("trainingLoad", "training_load", "load", "trainingLoadScore")
    private val TRAINING_EFFECT_KEYS =
        arrayOf("trainingEffect", "training_effect", "aerobicTrainingEffect", "te")
    private val ANAEROBIC_EFFECT_KEYS = arrayOf(
        "anaerobicTrainingEffect", "anaerobic_training_effect", "anaerobicTe", "anaerobic_te",
    )
    private val CALORIE_KEYS =
        arrayOf("calories", "calorie", "totalCalories", "total_calories", "energy", "kcal")
    private val ELEVATION_KEYS = arrayOf(
        "elevationGain", "elevation_gain", "elevationGainM", "totalAscent", "total_ascent",
        "ascent", "elevation", "totalElevationGain", "total_elevation_gain",
    )
    private val ZONE_KEYS = arrayOf("zone", "zoneIndex", "zone_index", "index", "number")
    private val ZONE_NAME_KEYS = arrayOf("name", "label", "zoneName", "zone_name")
    private val ZONE_MIN_KEYS = arrayOf(
        "min", "minHr", "min_hr", "minBpm", "min_bpm", "lower", "lowerHr",
    )
    private val ZONE_MAX_KEYS = arrayOf(
        "max", "maxHr", "max_hr", "maxBpm", "max_bpm", "upper", "upperHr",
    )
    private val ZONE_SECONDS_KEYS =
        arrayOf("seconds", "time", "duration", "durationSec", "duration_sec", "timeSec")
    private val ZONE_PERCENT_KEYS =
        arrayOf("percent", "percentage", "pct", "ratio")

    fun parseSportRecords(lines: List<String>): List<CorosSportRecord> {
        val fromJson = lines.flatMap(::jsonSportRecords)
        if (fromJson.isNotEmpty()) return fromJson.distinctBy { it.labelId to it.startTimestampSec }
        return textSportRecords(lines.joinToString("\n"))
    }

    fun parseSportRecords(text: String): List<CorosSportRecord> {
        if (text.isBlank()) return emptyList()
        return parseSportRecords(listOf(text))
    }

    fun latest(records: List<CorosSportRecord>): CorosSportRecord? =
        records.maxByOrNull { record ->
            record.startTimestampSec ?: dateEpochSec(record.date) ?: 0L
        }

    fun parseActivityDetail(lines: List<String>): CorosActivityDetail {
        val text = lines.joinToString("\n")
        val objects = lines.flatMap(::jsonObjects)
        val metrics = jsonMetrics(objects) ?: textMetrics(text)
        return CorosActivityDetail(
            labelId = objects.firstNotNullOfOrNull { firstString(it, LABEL_KEYS) }
                ?: LABEL_TEXT.find(text)?.groupValues?.get(1),
            sportType = objects.firstNotNullOfOrNull { firstInt(it, SPORT_KEYS) }
                ?: (SPORT_TEXT.find(text) ?: SPORT_FIELD_TEXT.find(text))
                    ?.groupValues?.get(1)?.toIntOrNull(),
            title = objects.firstNotNullOfOrNull { firstString(it, NAME_KEYS) },
            date = objects.firstNotNullOfOrNull { firstString(it, DATE_KEYS) }
                ?: DATE_TEXT.find(text)?.groupValues?.get(1),
            startTimestampSec = objects.firstNotNullOfOrNull { firstTimestamp(it, START_KEYS) }
                ?: START_TEXT.find(text)?.groupValues?.get(1)?.toLongOrNull()
                    ?.let(::normalizeTimestamp),
            durationSec = metrics.durationSec,
            distanceM = metrics.distanceM,
            avgHr = metrics.avgHr,
            maxHr = metrics.maxHr,
            avgCadence = metrics.avgCadence,
            maxCadence = metrics.maxCadence,
            avgPowerW = metrics.avgPowerW,
            maxPowerW = metrics.maxPowerW,
            trainingLoad = metrics.trainingLoad,
            trainingEffect = metrics.trainingEffect,
            anaerobicTrainingEffect = metrics.anaerobicTrainingEffect,
            calories = metrics.calories,
            elevationGainM = metrics.elevationGainM,
            hrZones = jsonHrZones(lines),
        )
    }

    fun parseActivityDetail(text: String): CorosActivityDetail = parseActivityDetail(listOf(text))

    fun parseLaps(lines: List<String>): List<CorosLap> {
        val joined = lines.joinToString("\n")
        val fromJson = lines.flatMap(::jsonLaps).ifEmpty { jsonLaps(joined) }
        if (fromJson.isNotEmpty()) return fromJson
        return textLaps(joined)
    }

    fun parseLaps(text: String): List<CorosLap> = parseLaps(listOf(text))

    private data class Metrics(
        val durationSec: Double? = null,
        val distanceM: Double? = null,
        val avgHr: Int? = null,
        val maxHr: Int? = null,
        val avgCadence: Int? = null,
        val maxCadence: Int? = null,
        val avgPowerW: Int? = null,
        val maxPowerW: Int? = null,
        val trainingLoad: Double? = null,
        val trainingEffect: Double? = null,
        val anaerobicTrainingEffect: Double? = null,
        val calories: Double? = null,
        val elevationGainM: Double? = null,
    )

    private fun jsonSportRecords(text: String): List<CorosSportRecord> {
        val objects = jsonObjects(text)
        if (objects.isEmpty()) return emptyList()
        return objects.mapNotNull(::recordFromObject)
    }

    private fun recordFromObject(obj: JSONObject): CorosSportRecord? {
        val label = firstString(obj, LABEL_KEYS)
        val sport = firstInt(obj, SPORT_KEYS)
        val start = firstTimestamp(obj, START_KEYS)
        val date = firstString(obj, DATE_KEYS)
        if (label == null && sport == null) return null
        if (start == null && date == null) return null
        return CorosSportRecord(
            labelId = label,
            sportType = sport,
            date = date,
            startTimestampSec = start,
            endTimestampSec = firstTimestamp(obj, END_KEYS),
            title = firstString(obj, NAME_KEYS),
            durationSec = firstDouble(obj, DURATION_KEYS),
        )
    }

    private fun textSportRecords(text: String): List<CorosSportRecord> {
        val blocks = mutableListOf<MutableList<String>>()
        text.lines().forEach { line ->
            if (NUMBERED_BLOCK.containsMatchIn(line)) blocks.add(mutableListOf())
            if (blocks.isEmpty() && line.isNotBlank()) blocks.add(mutableListOf())
            if (blocks.isNotEmpty()) blocks.last().add(line)
        }
        return blocks.mapNotNull(::recordFromTextBlock)
    }

    private fun recordFromTextBlock(block: List<String>): CorosSportRecord? {
        val text = block.joinToString("\n")
        val label = LABEL_TEXT.find(text)?.groupValues?.get(1)
        val sport = (SPORT_TEXT.find(text) ?: SPORT_FIELD_TEXT.find(text))
            ?.groupValues?.get(1)?.toIntOrNull()
        if (label == null && sport == null) return null
        return CorosSportRecord(
            labelId = label,
            sportType = sport,
            date = DATE_TEXT.find(text)?.groupValues?.get(1),
            startTimestampSec = START_TEXT.find(text)?.groupValues?.get(1)
                ?.toLongOrNull()?.let(::normalizeTimestamp),
            endTimestampSec = END_TEXT.find(text)?.groupValues?.get(1)
                ?.toLongOrNull()?.let(::normalizeTimestamp),
            title = null,
            durationSec = parseDuration(DURATION_TEXT.find(text)?.groupValues?.get(1)),
        )
    }

    private fun jsonMetrics(objects: List<JSONObject>): Metrics? {
        val durations = objects.mapNotNull { firstDouble(it, DURATION_KEYS) }
        val distances = objects.mapNotNull { firstDouble(it, DISTANCE_KEYS) }
        val stats = Metrics(
            durationSec = durations.maxOrNull(),
            distanceM = distances.maxOrNull(),
            avgHr = objects.firstNotNullOfOrNull { firstInt(it, AVG_HR_KEYS) },
            maxHr = objects.firstNotNullOfOrNull { firstInt(it, MAX_HR_KEYS) },
            avgCadence = objects.firstNotNullOfOrNull { firstInt(it, AVG_CADENCE_KEYS) },
            maxCadence = objects.firstNotNullOfOrNull { firstInt(it, MAX_CADENCE_KEYS) },
            avgPowerW = objects.firstNotNullOfOrNull { firstInt(it, AVG_POWER_KEYS) },
            maxPowerW = objects.firstNotNullOfOrNull { firstInt(it, MAX_POWER_KEYS) },
            trainingLoad = objects.firstNotNullOfOrNull { firstDouble(it, TRAINING_LOAD_KEYS) },
            trainingEffect = objects.firstNotNullOfOrNull { firstDouble(it, TRAINING_EFFECT_KEYS) },
            anaerobicTrainingEffect =
                objects.firstNotNullOfOrNull { firstDouble(it, ANAEROBIC_EFFECT_KEYS) },
            calories = objects.firstNotNullOfOrNull { firstDouble(it, CALORIE_KEYS) },
            elevationGainM = objects.firstNotNullOfOrNull { firstDouble(it, ELEVATION_KEYS) },
        )
        return stats.takeIf {
            it.durationSec != null || it.distanceM != null || it.avgHr != null ||
                it.maxHr != null || it.avgCadence != null || it.maxCadence != null ||
                it.avgPowerW != null || it.maxPowerW != null || it.trainingLoad != null ||
                it.trainingEffect != null || it.anaerobicTrainingEffect != null ||
                it.calories != null || it.elevationGainM != null
        }
    }

    private fun textMetrics(text: String): Metrics = Metrics(
        durationSec = parseDuration(DURATION_TEXT.find(text)?.groupValues?.get(1)),
        distanceM = parseDistance(DISTANCE_TEXT.find(text)?.groupValues?.get(1)),
        avgHr = AVG_HR_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        maxHr = MAX_HR_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        avgCadence = AVG_CADENCE_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        maxCadence = MAX_CADENCE_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        avgPowerW = AVG_POWER_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        maxPowerW = MAX_POWER_TEXT.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        trainingLoad = TRAINING_LOAD_TEXT.find(text)?.groupValues?.get(1)?.toDoubleOrNull(),
        trainingEffect = TRAINING_EFFECT_TEXT.find(text)?.groupValues?.get(1)?.toDoubleOrNull(),
        anaerobicTrainingEffect =
            ANAEROBIC_EFFECT_TEXT.find(text)?.groupValues?.get(1)?.toDoubleOrNull(),
        calories = CALORIES_TEXT.find(text)?.groupValues?.get(1)?.toDoubleOrNull(),
        elevationGainM = ELEVATION_TEXT.find(text)?.groupValues?.get(1)?.toDoubleOrNull(),
    )

    private fun jsonHrZones(lines: List<String>): List<CorosHrZone> {
        val root = lines.firstNotNullOfOrNull(::jsonRoot) ?: return emptyList()
        val arrays = mutableListOf<JSONArray>()
        collectZoneArrays(root, arrays, 0)
        val zones = mutableListOf<CorosHrZone>()
        arrays.forEach { array ->
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val zone = CorosHrZone(
                    zone = firstInt(obj, ZONE_KEYS) ?: (index + 1),
                    label = firstString(obj, ZONE_NAME_KEYS),
                    minBpm = firstInt(obj, ZONE_MIN_KEYS),
                    maxBpm = firstInt(obj, ZONE_MAX_KEYS),
                    seconds = firstDouble(obj, ZONE_SECONDS_KEYS),
                    percent = firstDouble(obj, ZONE_PERCENT_KEYS),
                )
                if (zone.minBpm != null || zone.maxBpm != null || zone.seconds != null ||
                    zone.percent != null
                ) {
                    zones.add(zone)
                }
            }
        }
        return zones.distinctBy { it.zone to it.minBpm to it.maxBpm }
    }

    private fun collectZoneArrays(value: Any?, out: MutableList<JSONArray>, depth: Int) {
        if (depth > MAX_DEPTH) return
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val child = value.opt(key)
                if (child is JSONArray && key.contains("zone", ignoreCase = true)) out.add(child)
                collectZoneArrays(child, out, depth + 1)
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectZoneArrays(value.opt(index), out, depth + 1)
                }
            }
        }
    }

    private fun jsonLaps(text: String): List<CorosLap> {
        val root = jsonRoot(text) ?: return emptyList()
        val arrays = mutableListOf<JSONArray>()
        collectLapArrays(root, arrays, 0)
        val laps = mutableListOf<CorosLap>()
        arrays.forEach { array ->
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                lapFromObject(obj, laps.size + 1)?.let(laps::add)
            }
        }
        return laps.distinct()
    }

    private fun lapFromObject(obj: JSONObject, fallbackIndex: Int): CorosLap? {
        val lap = CorosLap(
            index = firstInt(obj, arrayOf("lapIndex", "lap_index", "index", "lap")) ?: fallbackIndex,
            durationSec = firstDouble(obj, DURATION_KEYS),
            distanceM = firstDouble(obj, DISTANCE_KEYS),
            avgHr = firstInt(obj, AVG_HR_KEYS),
            maxHr = firstInt(obj, MAX_HR_KEYS),
            avgCadence = firstInt(obj, AVG_CADENCE_KEYS),
            maxCadence = firstInt(obj, MAX_CADENCE_KEYS),
            avgPowerW = firstInt(obj, AVG_POWER_KEYS),
            maxPowerW = firstInt(obj, MAX_POWER_KEYS),
        )
        return lap.takeIf {
            it.durationSec != null || it.distanceM != null || it.avgHr != null ||
                it.maxHr != null || it.avgCadence != null || it.maxCadence != null ||
                it.avgPowerW != null || it.maxPowerW != null
        }
    }

    private fun textLaps(text: String): List<CorosLap> {
        val blocks = mutableListOf<MutableList<String>>()
        text.lines().forEach { line ->
            if (LAP_BLOCK.containsMatchIn(line)) blocks.add(mutableListOf())
            if (blocks.isNotEmpty()) blocks.last().add(line)
        }
        return blocks.mapIndexedNotNull { index, block ->
            val body = block.joinToString("\n")
            val metrics = textMetrics(body)
            val lap = CorosLap(
                index = index + 1,
                durationSec = metrics.durationSec,
                distanceM = metrics.distanceM,
                avgHr = metrics.avgHr,
                maxHr = metrics.maxHr,
                avgCadence = metrics.avgCadence,
                maxCadence = metrics.maxCadence,
                avgPowerW = metrics.avgPowerW,
                maxPowerW = metrics.maxPowerW,
            )
            lap.takeIf {
                it.durationSec != null || it.distanceM != null || it.avgHr != null ||
                    it.maxHr != null || it.avgCadence != null || it.maxCadence != null ||
                    it.avgPowerW != null || it.maxPowerW != null
            }
        }
    }

    private fun parseDuration(value: String?): Double? {
        val clean = value?.trim() ?: return null
        if (clean.isEmpty()) return null
        if (clean.contains(":")) {
            val parts = clean.split(":").mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.isEmpty()) return null
            var seconds = 0.0
            parts.forEach { seconds = seconds * 60.0 + it }
            return seconds
        }
        val number = Regex("([0-9]+(?:[.,][0-9]+)?)").find(clean)
            ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val lower = clean.lowercase()
        return when {
            lower.contains("hour") || Regex("\\dh").containsMatchIn(lower) -> number * 3600.0
            lower.contains("min") || Regex("\\dm").containsMatchIn(lower) -> number * 60.0
            else -> number
        }
    }

    private fun parseDistance(value: String?): Double? {
        val clean = value?.trim() ?: return null
        val number = Regex("([0-9]+(?:[.,][0-9]+)?)").find(clean)
            ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val lower = clean.lowercase()
        return when {
            lower.contains("km") -> number * 1000.0
            lower.contains("mile") || Regex("\\dmi\\b").containsMatchIn(lower) -> number * 1609.344
            else -> number
        }
    }

    private fun dateEpochSec(value: String?): Long? {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocalDate.parse(clean).atStartOfDay(ZoneId.systemDefault()).toEpochSecond() }
            .getOrNull()
            ?: runCatching {
                LocalDate.parse(
                    clean.replace("-", ""),
                    java.time.format.DateTimeFormatter.BASIC_ISO_DATE,
                ).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
            }.getOrNull()
    }

    private fun jsonRoot(text: String): Any? {
        val clean = text.trim()
        if (clean.startsWith("[")) return runCatching { JSONArray(clean) }.getOrNull()
        if (clean.startsWith("{")) return runCatching { JSONObject(clean) }.getOrNull()
        return null
    }

    private fun jsonObjects(text: String): List<JSONObject> {
        val root = jsonRoot(text) ?: return emptyList()
        val objects = mutableListOf<JSONObject>()
        collectObjects(root, objects, 0)
        return objects
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

    private fun collectLapArrays(value: Any?, out: MutableList<JSONArray>, depth: Int) {
        if (depth > MAX_DEPTH) return
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val child = value.opt(key)
                if (child is JSONArray && key.contains("lap", ignoreCase = true)) out.add(child)
                collectLapArrays(child, out, depth + 1)
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectLapArrays(value.opt(index), out, depth + 1)
                }
            }
        }
    }

    private fun firstString(obj: JSONObject, keys: Array<String>): String? {
        for (key in keys) {
            when (val value = obj.opt(key)) {
                null, JSONObject.NULL -> Unit
                is String -> value.takeIf { it.isNotBlank() }?.let { return it }
                is Number, is Boolean -> return value.toString()
            }
        }
        return null
    }

    private fun firstInt(obj: JSONObject, keys: Array<String>): Int? {
        for (key in keys) {
            when (val value = obj.opt(key)) {
                is Number -> return value.toInt()
                is String -> value.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun firstDouble(obj: JSONObject, keys: Array<String>): Double? {
        for (key in keys) {
            when (val value = obj.opt(key)) {
                is Number -> return value.toDouble()
                is String -> value.trim().toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun firstTimestamp(obj: JSONObject, keys: Array<String>): Long? {
        for (key in keys) {
            val parsed = parseTimestamp(obj.opt(key))
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
