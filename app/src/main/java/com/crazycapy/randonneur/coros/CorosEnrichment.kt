/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class CorosEnrichment(
    val activityId: String?,
    val activityName: String?,
    val activityDate: String?,
    val activityStartSec: Long,
    val activityEndSec: Long,
    val overlapSec: Long,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val avgCadence: Int? = null,
    val maxCadence: Int? = null,
    val avgPowerW: Int? = null,
    val maxPowerW: Int? = null,
    val trainingLoad: Double? = null,
    val aerobicTrainingEffect: Double? = null,
    val anaerobicTrainingEffect: Double? = null,
    val calories: Double? = null,
    val elevationGainM: Double? = null,
    val hrZones: List<CorosHrZone> = emptyList(),
    val laps: List<CorosLap> = emptyList(),
    val source: String = SOURCE,
    val syncedAtMs: Long,
) {
    companion object {
        const val SOURCE = "COROS"

        fun from(
            match: CorosMatch,
            detail: CorosActivityDetail,
            laps: List<CorosLap>,
            syncedAtMs: Long,
        ): CorosEnrichment = CorosEnrichment(
            activityId = detail.labelId ?: match.record.labelId,
            activityName = detail.title ?: match.record.title,
            activityDate = detail.date ?: match.record.date,
            activityStartSec = match.activityStartSec,
            activityEndSec = match.activityEndSec,
            overlapSec = match.overlapSec,
            avgHr = detail.avgHr,
            maxHr = detail.maxHr,
            avgCadence = detail.avgCadence,
            maxCadence = detail.maxCadence,
            avgPowerW = detail.avgPowerW,
            maxPowerW = detail.maxPowerW,
            trainingLoad = detail.trainingLoad,
            aerobicTrainingEffect = detail.trainingEffect,
            anaerobicTrainingEffect = detail.anaerobicTrainingEffect,
            calories = detail.calories,
            elevationGainM = detail.elevationGainM,
            hrZones = detail.hrZones,
            laps = laps,
            syncedAtMs = syncedAtMs,
        )
    }
}

object CorosEnrichmentCodec {
    fun encode(enrichment: CorosEnrichment): String = JSONObject().apply {
        put("activityId", enrichment.activityId)
        put("activityName", enrichment.activityName)
        put("activityDate", enrichment.activityDate)
        put("activityStartSec", enrichment.activityStartSec)
        put("activityEndSec", enrichment.activityEndSec)
        put("overlapSec", enrichment.overlapSec)
        put("avgHr", enrichment.avgHr)
        put("maxHr", enrichment.maxHr)
        put("avgCadence", enrichment.avgCadence)
        put("maxCadence", enrichment.maxCadence)
        put("avgPowerW", enrichment.avgPowerW)
        put("maxPowerW", enrichment.maxPowerW)
        put("trainingLoad", enrichment.trainingLoad)
        put("aerobicTrainingEffect", enrichment.aerobicTrainingEffect)
        put("anaerobicTrainingEffect", enrichment.anaerobicTrainingEffect)
        put("calories", enrichment.calories)
        put("elevationGainM", enrichment.elevationGainM)
        put("hrZones", JSONArray().apply {
            enrichment.hrZones.forEach { zone ->
                put(JSONObject().apply {
                    put("zone", zone.zone)
                    put("label", zone.label)
                    put("minBpm", zone.minBpm)
                    put("maxBpm", zone.maxBpm)
                    put("seconds", zone.seconds)
                    put("percent", zone.percent)
                })
            }
        })
        put("laps", JSONArray().apply {
            enrichment.laps.forEach { lap ->
                put(JSONObject().apply {
                    put("index", lap.index)
                    put("durationSec", lap.durationSec)
                    put("distanceM", lap.distanceM)
                    put("avgHr", lap.avgHr)
                    put("maxHr", lap.maxHr)
                    put("avgCadence", lap.avgCadence)
                    put("maxCadence", lap.maxCadence)
                    put("avgPowerW", lap.avgPowerW)
                    put("maxPowerW", lap.maxPowerW)
                })
            }
        })
        put("source", enrichment.source)
        put("syncedAtMs", enrichment.syncedAtMs)
    }.toString()

    fun decode(text: String?): CorosEnrichment? {
        val clean = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val root = JSONObject(clean)
            val start = root.optLong("activityStartSec", 0L)
            if (start <= 0L) return@runCatching null
            CorosEnrichment(
                activityId = root.optStringOrNull("activityId"),
                activityName = root.optStringOrNull("activityName"),
                activityDate = root.optStringOrNull("activityDate"),
                activityStartSec = start,
                activityEndSec = root.optLong("activityEndSec", start),
                overlapSec = root.optLong("overlapSec", 0L),
                avgHr = root.optIntOrNull("avgHr"),
                maxHr = root.optIntOrNull("maxHr"),
                avgCadence = root.optIntOrNull("avgCadence"),
                maxCadence = root.optIntOrNull("maxCadence"),
                avgPowerW = root.optIntOrNull("avgPowerW"),
                maxPowerW = root.optIntOrNull("maxPowerW"),
                trainingLoad = root.optDoubleOrNull("trainingLoad"),
                aerobicTrainingEffect = root.optDoubleOrNull("aerobicTrainingEffect"),
                anaerobicTrainingEffect = root.optDoubleOrNull("anaerobicTrainingEffect"),
                calories = root.optDoubleOrNull("calories"),
                elevationGainM = root.optDoubleOrNull("elevationGainM"),
                hrZones = root.optJSONArray("hrZones")?.let(::decodeZones).orEmpty(),
                laps = root.optJSONArray("laps")?.let(::decodeLaps).orEmpty(),
                source = root.optStringOrNull("source") ?: CorosEnrichment.SOURCE,
                syncedAtMs = root.optLong("syncedAtMs", 0L),
            )
        }.getOrNull()
    }

    private fun decodeZones(array: JSONArray): List<CorosHrZone> =
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { obj ->
                CorosHrZone(
                    zone = obj.optIntOrNull("zone"),
                    label = obj.optStringOrNull("label"),
                    minBpm = obj.optIntOrNull("minBpm"),
                    maxBpm = obj.optIntOrNull("maxBpm"),
                    seconds = obj.optDoubleOrNull("seconds"),
                    percent = obj.optDoubleOrNull("percent"),
                )
            }
        }

    private fun decodeLaps(array: JSONArray): List<CorosLap> =
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { obj ->
                CorosLap(
                    index = obj.optIntOrNull("index"),
                    durationSec = obj.optDoubleOrNull("durationSec"),
                    distanceM = obj.optDoubleOrNull("distanceM"),
                    avgHr = obj.optIntOrNull("avgHr"),
                    maxHr = obj.optIntOrNull("maxHr"),
                    avgCadence = obj.optIntOrNull("avgCadence"),
                    maxCadence = obj.optIntOrNull("maxCadence"),
                    avgPowerW = obj.optIntOrNull("avgPowerW"),
                    maxPowerW = obj.optIntOrNull("maxPowerW"),
                )
            }
        }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null
}

enum class MetricSource { APP, COROS }

data class MergedValue<T>(val value: T?, val source: MetricSource)

data class RideMetrics(
    val avgHr: MergedValue<Int>?,
    val maxHr: MergedValue<Int>?,
    val avgCadence: MergedValue<Int>?,
    val maxCadence: MergedValue<Int>?,
    val avgPowerW: MergedValue<Int>?,
    val maxPowerW: MergedValue<Int>?,
    val trainingLoad: MergedValue<Double>?,
    val aerobicTrainingEffect: MergedValue<Double>?,
    val anaerobicTrainingEffect: MergedValue<Double>?,
    val calories: MergedValue<Double>?,
    val elevationGainM: MergedValue<Double>?,
)

data class TcxSummary(
    val avgHr: Int?,
    val maxHr: Int?,
    val cadence: Int?,
    val avgPowerW: Int?,
    val maxPowerW: Int?,
    val calories: Double?,
)

object CorosMerge {
    fun merge(
        appHr: List<Int>,
        appElevationGainM: Double?,
        appCalories: Double?,
        enrichment: CorosEnrichment?,
    ): RideMetrics {
        val coros = enrichment
        val appHrValues = appHr.filter { it > 0 }
        val avgHr = if (appHrValues.isNotEmpty()) {
            MergedValue(appHrValues.average().roundToInt(), MetricSource.APP)
        } else {
            coros?.avgHr?.let { MergedValue(it, MetricSource.COROS) }
        }
        val maxHr = if (appHrValues.isNotEmpty()) {
            MergedValue(appHrValues.max(), MetricSource.APP)
        } else {
            coros?.maxHr?.let { MergedValue(it, MetricSource.COROS) }
        }
        return RideMetrics(
            avgHr = avgHr,
            maxHr = maxHr,
            avgCadence = coros?.avgCadence?.let { MergedValue(it, MetricSource.COROS) },
            maxCadence = coros?.maxCadence?.let { MergedValue(it, MetricSource.COROS) },
            avgPowerW = coros?.avgPowerW?.let { MergedValue(it, MetricSource.COROS) },
            maxPowerW = coros?.maxPowerW?.let { MergedValue(it, MetricSource.COROS) },
            trainingLoad = coros?.trainingLoad?.let { MergedValue(it, MetricSource.COROS) },
            aerobicTrainingEffect = coros?.aerobicTrainingEffect
                ?.let { MergedValue(it, MetricSource.COROS) },
            anaerobicTrainingEffect = coros?.anaerobicTrainingEffect
                ?.let { MergedValue(it, MetricSource.COROS) },
            calories = appCalories?.takeIf { it > 0.0 }?.let { MergedValue(it, MetricSource.APP) }
                ?: coros?.calories?.let { MergedValue(it, MetricSource.COROS) },
            elevationGainM = appElevationGainM?.let { MergedValue(it, MetricSource.APP) }
                ?: coros?.elevationGainM?.let { MergedValue(it, MetricSource.COROS) },
        )
    }

    fun tcx(metrics: RideMetrics): TcxSummary = TcxSummary(
        avgHr = metrics.avgHr?.value,
        maxHr = metrics.maxHr?.value,
        cadence = metrics.avgCadence?.value,
        avgPowerW = metrics.avgPowerW?.value,
        maxPowerW = metrics.maxPowerW?.value,
        calories = metrics.calories?.value,
    )
}
