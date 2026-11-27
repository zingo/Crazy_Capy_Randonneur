/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorosActivityParserTest {

    @Test
    fun parsesTextSportRecords() {
        val records = CorosActivityParser.parseSportRecords(
            """
            Sport Records — 2026-11-21 to 2026-11-27 (2 records)

            1. Sport(200) — 2026-11-24
               labelId: 476406894762688515
               StartTimestamp: 1790000000
               EndTimestamp: 1790000902

            2. Sport(100) — 2026-11-26
               labelId: 476406894762688600
               StartTimestamp: 1790172800
            """.trimIndent(),
        )

        assertEquals(2, records.size)
        assertEquals("476406894762688515", records[0].labelId)
        assertEquals(200, records[0].sportType)
        assertEquals(1790000000L, records[0].startTimestampSec)
        assertEquals("476406894762688600", records[1].labelId)
    }

    @Test
    fun parsesJsonSportRecords() {
        val records = CorosActivityParser.parseSportRecords(
            """
            {"records":[
              {"labelId":"abc123","sportType":200,"date":"20261124",
               "startTimestamp":1790000000,"title":"Morning Ride"},
              {"label_id":"def456","sport_type":100,"startTime":"2026-11-26T08:00:00Z"}
            ]}
            """.trimIndent(),
        )

        assertEquals(2, records.size)
        assertEquals("abc123", records[0].labelId)
        assertEquals(200, records[0].sportType)
        assertEquals("Morning Ride", records[0].title)
        assertEquals(1795680000L, records[1].startTimestampSec)
    }

    @Test
    fun latestPicksNewestStartTimeOrDate() {
        val records = listOf(
            CorosSportRecord("old", 100, "2026-11-20", 1790000000L, null, null),
            CorosSportRecord("new", 200, null, 1790172800L, null, null),
            CorosSportRecord("dated", 100, "2026-11-27", null, null, null),
        )

        assertEquals("dated", CorosActivityParser.latest(records)?.labelId)
        assertNull(CorosActivityParser.latest(emptyList()))
    }

    @Test
    fun parsesTextActivityDetail() {
        val detail = CorosActivityParser.parseActivityDetail(
            """
            Sport(200) Activity Details — 2026-11-24
            Duration: 1:23:45
            Distance: 42.3 km
            Average Heart Rate: 143 bpm
            Max Heart Rate: 176 bpm
            Average Cadence: 86 rpm
            Max Cadence: 101 rpm
            Average Power: 231 W
            Max Power: 512 W
            Training Load: 138
            Training Effect: 3.4
            """.trimIndent(),
        )

        assertEquals("2026-11-24", detail.date)
        assertEquals(200, detail.sportType)
        assertEquals(5025.0, detail.durationSec ?: 0.0, 0.001)
        assertEquals(42300.0, detail.distanceM ?: 0.0, 0.001)
        assertEquals(143, detail.avgHr)
        assertEquals(176, detail.maxHr)
        assertEquals(86, detail.avgCadence)
        assertEquals(101, detail.maxCadence)
        assertEquals(231, detail.avgPowerW)
        assertEquals(512, detail.maxPowerW)
        assertEquals(138.0, detail.trainingLoad ?: 0.0, 0.001)
        assertEquals(3.4, detail.trainingEffect ?: 0.0, 0.001)
    }

    @Test
    fun parsesJsonActivityDetail() {
        val detail = CorosActivityParser.parseActivityDetail(
            """
            {"activity":{"labelId":"abc123","sportType":200,"name":"Long Ride",
             "date":"20261124","startTimestamp":1790000000,
             "duration":5025,"distance":42300,
             "avgHr":143,"maxHr":176,"avgCadence":86,"maxCadence":101,
             "avgPower":231,"maxPower":512,"trainingLoad":138,"trainingEffect":3.4}}
            """.trimIndent(),
        )

        assertEquals("abc123", detail.labelId)
        assertEquals("Long Ride", detail.title)
        assertEquals(1790000000L, detail.startTimestampSec)
        assertEquals(5025.0, detail.durationSec ?: 0.0, 0.001)
        assertEquals(42300.0, detail.distanceM ?: 0.0, 0.001)
        assertEquals(143, detail.avgHr)
        assertEquals(176, detail.maxHr)
        assertEquals(86, detail.avgCadence)
        assertEquals(101, detail.maxCadence)
        assertEquals(231, detail.avgPowerW)
        assertEquals(512, detail.maxPowerW)
        assertEquals(138.0, detail.trainingLoad ?: 0.0, 0.001)
        assertEquals(3.4, detail.trainingEffect ?: 0.0, 0.001)
    }

    @Test
    fun parsesTextLaps() {
        val laps = CorosActivityParser.parseLaps(
            """
            Lap 1
            Duration: 10:00
            Distance: 5.0 km
            Average Heart Rate: 130 bpm
            Max Heart Rate: 145 bpm
            Average Cadence: 84 rpm

            Lap 2
            Duration: 12:30
            Distance: 6.2 km
            Average Heart Rate: 141 bpm
            Max Heart Rate: 158 bpm
            Average Power: 210 W
            """.trimIndent(),
        )

        assertEquals(2, laps.size)
        assertEquals(600.0, laps[0].durationSec ?: 0.0, 0.001)
        assertEquals(5000.0, laps[0].distanceM ?: 0.0, 0.001)
        assertEquals(130, laps[0].avgHr)
        assertEquals(750.0, laps[1].durationSec ?: 0.0, 0.001)
        assertEquals(210, laps[1].avgPowerW)
    }

    @Test
    fun parsesJsonLaps() {
        val laps = CorosActivityParser.parseLaps(
            """
            {"lapGroups":[{"laps":[
              {"lapIndex":1,"time":600,"distance":5000,"avgHr":130,"maxHr":145},
              {"lapIndex":2,"time":750,"distance":6200,"avgPower":210,"maxPower":400}
            ]}]}
            """.trimIndent(),
        )

        assertEquals(2, laps.size)
        assertEquals(1, laps[0].index)
        assertEquals(600.0, laps[0].durationSec ?: 0.0, 0.001)
        assertEquals(130, laps[0].avgHr)
        assertEquals(2, laps[1].index)
        assertEquals(210, laps[1].avgPowerW)
        assertEquals(400, laps[1].maxPowerW)
    }

    @Test
    fun parsesAdditionalDetailMetricsAndHrZones() {
        val detail = CorosActivityParser.parseActivityDetail(
            """
            {"activity":{"labelId":"abc123","sportType":200,
             "calories":910,"elevationGain":1240,
             "anaerobicTrainingEffect":1.2,
             "hrZones":[
               {"zone":1,"label":"Recovery","min":100,"max":120,"seconds":600,"percent":12},
               {"zone":2,"name":"Aerobic","minBpm":121,"maxBpm":140,"time":1200}
             ]}}
            """.trimIndent(),
        )

        assertEquals(910.0, detail.calories ?: 0.0, 0.001)
        assertEquals(1240.0, detail.elevationGainM ?: 0.0, 0.001)
        assertEquals(1.2, detail.anaerobicTrainingEffect ?: 0.0, 0.001)
        assertEquals(2, detail.hrZones.size)
        assertEquals("Aerobic", detail.hrZones[1].label)
        assertEquals(121, detail.hrZones[1].minBpm)
        assertEquals(1200.0, detail.hrZones[1].seconds ?: 0.0, 0.001)
    }

    @Test
    fun parsesSportRecordDurationForOverlap() {
        val records = CorosActivityParser.parseSportRecords(
            """{"records":[{"labelId":"abc","sportType":200,"startTime":1790000000,
               "duration":4200}]}""",
        )

        assertEquals(4200.0, records[0].durationSec ?: 0.0, 0.001)
    }

    @Test
    fun ignoresBlankAndNonActivityText() {
        assertEquals(0, CorosActivityParser.parseSportRecords("").size)
        assertEquals(0, CorosActivityParser.parseLaps("no laps here").size)
        assertEquals(null, CorosActivityParser.parseActivityDetail("nothing useful").avgHr)
    }
}
