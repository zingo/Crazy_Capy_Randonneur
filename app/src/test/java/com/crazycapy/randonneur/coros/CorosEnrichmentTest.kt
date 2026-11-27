/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.coros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorosEnrichmentTest {

    private fun sample() = CorosEnrichment(
        activityId = "abc123",
        activityName = "Long ride",
        activityDate = "2026-11-27",
        activityStartSec = 1000,
        activityEndSec = 4000,
        overlapSec = 2800,
        avgHr = 143,
        maxHr = 176,
        avgCadence = 86,
        maxCadence = 101,
        avgPowerW = 231,
        maxPowerW = 512,
        trainingLoad = 138.0,
        aerobicTrainingEffect = 3.4,
        anaerobicTrainingEffect = 1.2,
        calories = 910.0,
        elevationGainM = 1240.0,
        hrZones = listOf(
            CorosHrZone(1, "Recovery", 100, 120, 600.0, 12.0),
            CorosHrZone(2, "Aerobic", 121, 140, 1200.0, 40.0),
        ),
        laps = listOf(
            CorosLap(1, 600.0, 5000.0, 130, 145, 84, 92, 210, 400),
        ),
        syncedAtMs = 5000,
    )

    @Test
    fun roundTripsAllFields() {
        val decoded = CorosEnrichmentCodec.decode(CorosEnrichmentCodec.encode(sample()))

        assertEquals(sample(), decoded)
    }

    @Test
    fun decodesBackwardCompatibleMinimalPayload() {
        val decoded = CorosEnrichmentCodec.decode(
            """{"activityStartSec":1000,"activityEndSec":2000,"syncedAtMs":123}""",
        )

        assertEquals(1000L, decoded?.activityStartSec)
        assertEquals(2000L, decoded?.activityEndSec)
        assertEquals(CorosEnrichment.SOURCE, decoded?.source)
        assertNull(decoded?.avgHr)
        assertEquals(emptyList<CorosHrZone>(), decoded?.hrZones)
        assertEquals(emptyList<CorosLap>(), decoded?.laps)
    }

    @Test
    fun ignoresUnknownFieldsAndNullStrings() {
        val decoded = CorosEnrichmentCodec.decode(
            """{"activityStartSec":1000,"activityEndSec":2000,"syncedAtMs":123,
               "activityName":null,"avgHr":150,"futureField":"ignored"}""",
        )

        assertNull(decoded?.activityName)
        assertEquals(150, decoded?.avgHr)
    }

    @Test
    fun rejectsInvalidPayloads() {
        assertNull(CorosEnrichmentCodec.decode(null))
        assertNull(CorosEnrichmentCodec.decode(""))
        assertNull(CorosEnrichmentCodec.decode("not json"))
        assertNull(CorosEnrichmentCodec.decode("""{"activityEndSec":2000}"""))
    }
}
