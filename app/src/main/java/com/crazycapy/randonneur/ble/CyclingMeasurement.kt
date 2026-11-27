/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

data class CscSample(
    val wheelRevolutions: Long? = null,
    val wheelEventTime: Int? = null,
    val crankRevolutions: Int? = null,
    val crankEventTime: Int? = null,
)

data class CscReading(
    val speedKmh: Double? = null,
    val cadenceRpm: Double? = null,
)

object CscMeasurementParser {
    fun parse(data: ByteArray): CscSample? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        val wheelPresent = flags and 0x01 != 0
        val crankPresent = flags and 0x02 != 0
        if (!wheelPresent && !crankPresent) return null

        var offset = 1
        var wheelRevolutions: Long? = null
        var wheelEventTime: Int? = null
        var crankRevolutions: Int? = null
        var crankEventTime: Int? = null

        if (wheelPresent) {
            if (data.size < offset + 6) return null
            wheelRevolutions = readUInt32(data, offset)
            wheelEventTime = readUInt16(data, offset + 4)
            offset += 6
        }
        if (crankPresent) {
            if (data.size < offset + 4) return null
            crankRevolutions = readUInt16(data, offset)
            crankEventTime = readUInt16(data, offset + 2)
        }
        return CscSample(wheelRevolutions, wheelEventTime, crankRevolutions, crankEventTime)
    }

    internal fun readUInt16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    internal fun readUInt32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
}

data class CpsSample(
    val powerWatts: Int,
    val wheelRevolutions: Long? = null,
    val wheelEventTime: Int? = null,
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

        var wheelRevolutions: Long? = null
        var wheelEventTime: Int? = null
        if (flags and 0x10 != 0) {
            if (data.size < offset + 6) return CpsSample(power)
            wheelRevolutions = CscMeasurementParser.readUInt32(data, offset)
            wheelEventTime = CscMeasurementParser.readUInt16(data, offset + 4)
            offset += 6
        }

        var crankRevolutions: Int? = null
        var crankEventTime: Int? = null
        if (flags and 0x20 != 0) {
            if (data.size < offset + 4) return CpsSample(power)
            crankRevolutions = CscMeasurementParser.readUInt16(data, offset)
            crankEventTime = CscMeasurementParser.readUInt16(data, offset + 2)
        }

        return CpsSample(
            powerWatts = power,
            wheelRevolutions = wheelRevolutions,
            wheelEventTime = wheelEventTime,
            crankRevolutions = crankRevolutions,
            crankEventTime = crankEventTime,
        )
    }
}

class CscCalculator {
    private var previousWheel: Pair<Long, Int>? = null
    private var previousCrank: Pair<Int, Int>? = null

    fun update(sample: CscSample, circumferenceMm: Int): CscReading {
        val wheel = wheelSpeed(sample, circumferenceMm)
        val cadence = cadenceRpm(sample)
        return CscReading(wheel, cadence)
    }

    private fun wheelSpeed(sample: CscSample, circumferenceMm: Int): Double? {
        val revs = sample.wheelRevolutions ?: return null
        val time = sample.wheelEventTime ?: return null
        val previous = previousWheel
        if (previous == null) {
            previousWheel = revs to time
            return null
        }
        val timeDelta = ((time - previous.second) and 0xFFFF)
        if (timeDelta == 0) return null
        val revDelta = (revs - previous.first) and 0xFFFFFFFFL
        previousWheel = revs to time
        val seconds = timeDelta / 1024.0
        val metres = revDelta * (circumferenceMm / 1000.0)
        return metres / seconds * 3.6
    }

    private fun cadenceRpm(sample: CscSample): Double? {
        val revs = sample.crankRevolutions ?: return null
        val time = sample.crankEventTime ?: return null
        val previous = previousCrank
        if (previous == null) {
            previousCrank = revs to time
            return null
        }
        val timeDelta = ((time - previous.second) and 0xFFFF)
        if (timeDelta == 0) return null
        val revDelta = (revs - previous.first) and 0xFFFF
        previousCrank = revs to time
        return revDelta * 60.0 * 1024.0 / timeDelta
    }
}

class CpsCalculator {
    private var previousCrank: Pair<Int, Int>? = null

    fun update(sample: CpsSample): CpsReading {
        val revs = sample.crankRevolutions
        val time = sample.crankEventTime
        if (revs == null || time == null) return CpsReading(sample.powerWatts)
        val previous = previousCrank
        if (previous == null) {
            previousCrank = revs to time
            return CpsReading(sample.powerWatts)
        }
        val timeDelta = ((time - previous.second) and 0xFFFF)
        if (timeDelta == 0) return CpsReading(sample.powerWatts)
        val revDelta = (revs - previous.first) and 0xFFFF
        previousCrank = revs to time
        return CpsReading(sample.powerWatts, revDelta * 60.0 * 1024.0 / timeDelta)
    }
}
