/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

data class CscSample(
    val crankRevolutions: Int? = null,
    val crankEventTime: Int? = null,
)

data class CscReading(
    val cadenceRpm: Double? = null,
)

object CscMeasurementParser {
    fun parse(data: ByteArray): CscSample? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val wheelPresent = flags and 0x01 != 0
        val crankPresent = flags and 0x02 != 0
        if (!crankPresent) return null

        var offset = 1
        if (wheelPresent) {
            if (data.size < offset + 6) return null
            offset += 6
        }
        if (data.size < offset + 4) return null
        return CscSample(readUInt16(data, offset), readUInt16(data, offset + 2))
    }

    internal fun readUInt16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}

data class CpsSample(
    val powerWatts: Int,
    val crankRevolutions: Int? = null,
    val crankEventTime: Int? = null,
)

data class CpsReading(
    val powerWatts: Int,
    val cadenceRpm: Double? = null,
)

object CpsMeasurementParser {
    fun parse(data: ByteArray): CpsSample? {
        if (data.size < 3) return null
        val flags = CscMeasurementParser.readUInt16(data, 0)
        val power = CscMeasurementParser.readUInt16(data, 2)
        var offset = 4

        if (flags and 0x01 != 0) offset += 1
        if (flags and 0x04 != 0) offset += 2
        if (flags and 0x10 != 0) {
            if (data.size < offset + 6) return CpsSample(power)
            offset += 6
        }
        if (flags and 0x20 != 0) {
            if (data.size < offset + 4) return CpsSample(power)
            return CpsSample(
                powerWatts = power,
                crankRevolutions = CscMeasurementParser.readUInt16(data, offset),
                crankEventTime = CscMeasurementParser.readUInt16(data, offset + 2),
            )
        }
        return CpsSample(power)
    }
}

private class CadenceTracker {
    private var previous: Pair<Int, Int>? = null

    fun update(revolutions: Int?, eventTime: Int?): Double? {
        if (revolutions == null || eventTime == null) return null
        val previous = previous
        if (previous == null) {
            this.previous = revolutions to eventTime
            return null
        }
        val timeDelta = ((eventTime - previous.second) and 0xFFFF)
        if (timeDelta == 0) return null
        val revDelta = (revolutions - previous.first) and 0xFFFF
        this.previous = revolutions to eventTime
        return revDelta * 60.0 * 1024.0 / timeDelta
    }
}

class CscCalculator {
    private val cadence = CadenceTracker()

    fun update(sample: CscSample): CscReading =
        CscReading(cadence.update(sample.crankRevolutions, sample.crankEventTime))
}

class CpsCalculator {
    private val cadence = CadenceTracker()

    fun update(sample: CpsSample): CpsReading =
        CpsReading(sample.powerWatts, cadence.update(sample.crankRevolutions, sample.crankEventTime))
}
