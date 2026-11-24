/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CorosLiveParserTest {

    @Test
    fun parsesTextSportRecordInProgress() {
        val records = CorosLiveParser.parseSportRecords(
            """
            Sport Records — 2026-11-24 to 2026-11-24 (1 records)

            1. Sport(200) — 2026-11-24
               labelId: 476406894762688515
               Status: in progress
               StartTimestamp: 1790000000
               EndTimestamp: 1790000902
            """.trimIndent(),
        )

        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("476406894762688515", record.labelId)
        assertEquals(200, record.sportType)
        assertEquals(1790000000L, record.startTimestampSec)
        assertEquals(1790000902L, record.endTimestampSec)
        assertEquals(true, record.inProgress)
    }

    @Test
    fun parsesJsonSportRecord() {
        val records = CorosLiveParser.parseSportRecords(
            """
            {"records":[{"labelId":"abc123","sportType":200,"date":"20261124",
            "startTimestamp":1790000000,"endTimestamp":1790000902,
            "activityStatus":"Status: in progress"}]}
            """.trimIndent(),
        )

        assertEquals(1, records.size)
        assertEquals("abc123", records.single().labelId)
        assertEquals(200, records.single().sportType)
        assertEquals(true, records.single().inProgress)
    }

    @Test
    fun parsesCompletedSportRecord() {
        val records = CorosLiveParser.parseSportRecords(
            """
            {"activities":[{"label_id":"done1","sport_type":100,"date":"2026-11-24",
            "status":"completed","startTime":"2026-11-24T08:00:00Z"}]}
            """.trimIndent(),
        )

        assertEquals(1, records.size)
        assertEquals(false, records.single().inProgress)
        assertEquals(1795507200L, records.single().startTimestampSec)
    }

    @Test
    fun parsesTextActivityDetail() {
        val detail = CorosLiveParser.parseActivityDetail(
            listOf(
                """
                Sport(200) Activity Details

                Status: in progress. Metrics reflect data uploaded so far and will change when the activity ends.
                Average Heart Rate: 143 bpm
                Max Heart Rate: 152 bpm
                Average Cadence: 86 rpm
                Average Power: 231 W
                """.trimIndent(),
            ),
        )

        assertTrue(detail.inProgress)
        assertEquals(143, detail.avgHr)
        assertEquals(152, detail.maxHr)
        assertEquals(86, detail.avgCadence)
        assertEquals(231, detail.avgPowerW)
    }

    @Test
    fun parsesJsonActivityDetail() {
        val detail = CorosLiveParser.parseActivityDetail(
            listOf(
                """{"activityStatus":"Status: in progress","avgHr":143,"maxHr":152,"avgCadence":86,"avgPower":231}""",
            ),
        )

        assertTrue(detail.inProgress)
        assertEquals(143, detail.avgHr)
        assertEquals(152, detail.maxHr)
        assertEquals(86, detail.avgCadence)
        assertEquals(231, detail.avgPowerW)
    }

    @Test
    fun completedDetailIsNotInProgress() {
        val detail = CorosLiveParser.parseActivityDetail(
            listOf("Sport(200) Activity Details\nAverage Heart Rate: 120 bpm"),
        )

        assertFalse(detail.inProgress)
        assertEquals(120, detail.avgHr)
    }

    @Test
    fun parsesCustomWindowJson() {
        val stats = CorosLiveParser.parseWindow(
            listOf(
                """
                {
                  "activityStatus": "Status: in progress. Metrics reflect data uploaded so far.",
                  "lapGroups": [{
                    "laps": [{
                      "lapIndex": 1,
                      "time": 300,
                      "avgHr": 143,
                      "maxHr": 152,
                      "avgCadence": 86,
                      "maxCadence": 94,
                      "avgPower": 231,
                      "maxPower": 410
                    }]
                  }]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(true, stats.inProgress)
        assertEquals(143, stats.avgHr)
        assertEquals(152, stats.maxHr)
        assertEquals(86, stats.avgCadence)
        assertEquals(94, stats.maxCadence)
        assertEquals(231, stats.avgPowerW)
        assertEquals(410, stats.maxPowerW)
        assertEquals(1, stats.samples)
    }

    @Test
    fun averagesMultipleWindowLapsByDuration() {
        val stats = CorosLiveParser.parseWindow(
            listOf(
                """
                {"laps":[
                  {"time":300,"avgHr":100,"maxHr":120,"avgCadence":80},
                  {"time":100,"avgHr":140,"maxHr":150,"avgCadence":90}
                ]}
                """.trimIndent(),
            ),
        )

        assertEquals(110, stats.avgHr)
        assertEquals(150, stats.maxHr)
        assertEquals(83, stats.avgCadence)
        assertEquals(2, stats.samples)
    }

    @Test
    fun parsesTextWindow() {
        val stats = CorosLiveParser.parseWindow(
            listOf(
                """
                Last 5 minutes
                Average Heart Rate: 144 bpm
                Max Heart Rate: 161 bpm
                Average Cadence: 88 rpm
                Max Cadence: 101 rpm
                Average Power: 240 W
                Max Power: 512 W
                """.trimIndent(),
            ),
        )

        assertNull(stats.inProgress)
        assertEquals(144, stats.avgHr)
        assertEquals(161, stats.maxHr)
        assertEquals(88, stats.avgCadence)
        assertEquals(101, stats.maxCadence)
        assertEquals(240, stats.avgPowerW)
        assertEquals(512, stats.maxPowerW)
    }

    @Test
    fun parsesTextTodayWorkout() {
        val workout = CorosLiveParser.parseTodayWorkout(
            listOf(
                """
                Training Schedule — 2026-11-24

                2026-11-24
                - Threshold Ride 90 min
                """.trimIndent(),
            ),
            LocalDate.of(2026, 11, 24),
        )

        assertEquals("Threshold Ride 90 min", workout)
    }

    @Test
    fun parsesJsonTodayWorkout() {
        val workout = CorosLiveParser.parseTodayWorkout(
            listOf(
                """{"days":[{"date":"20261124","workouts":[{"name":"Recovery Spin 45 min"}]}]}""",
            ),
            LocalDate.of(2026, 11, 24),
        )

        assertEquals("Recovery Spin 45 min", workout)
    }

    @Test
    fun ignoresOtherDaysWorkout() {
        val workout = CorosLiveParser.parseTodayWorkout(
            listOf("""{"days":[{"date":"20261123","workouts":[{"name":"Old Ride"}]}]}"""),
            LocalDate.of(2026, 11, 24),
        )

        assertNull(workout)
    }
}
