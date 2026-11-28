/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

enum class HrPhase {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    PERMISSION_NEEDED,
    ERROR,
}

data class HrSensorState(
    val phase: HrPhase = HrPhase.IDLE,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val bpm: Int? = null,
    val error: String? = null,
    val devices: List<BleDevice> = emptyList(),
)

private val hrServiceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

object BleHrProvider : BleSensorProvider(
    scanServiceUuids = listOf(hrServiceUuid),
    serviceNotFoundError = "Heart-rate service not found",
), HrProvider {
    private val measurementUuid = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private var debugHr: Int? = null

    var state by mutableStateOf(HrSensorState())
        private set

    fun setDebugHr(bpm: Int?) {
        debugHr = bpm
        update { it.copy(bpm = bpm ?: it.bpm) }
    }

    override fun currentHr(): Int? = debugHr ?: state.bpm.takeIf { state.phase == HrPhase.CONNECTED }

    override fun notificationCharacteristics(gatt: BluetoothGatt): List<BluetoothGattCharacteristic> =
        listOfNotNull(gatt.getService(hrServiceUuid)?.getCharacteristic(measurementUuid))

    override fun onNotification(uuid: UUID, value: ByteArray) {
        if (uuid != measurementUuid) return
        val sample = HrMeasurementParser.parse(value) ?: return
        update { it.copy(bpm = sample.bpm) }
    }

    override fun handle(event: Event) {
        when (event) {
            Event.ScanStarted -> update {
                it.copy(phase = HrPhase.SCANNING, error = null, devices = emptyList())
            }
            Event.ScanStopped -> update {
                if (it.phase == HrPhase.SCANNING) it.copy(phase = HrPhase.IDLE) else it
            }
            is Event.DeviceFound -> update { current ->
                if (current.devices.any { it.address == event.address }) current
                else current.copy(devices = current.devices + BleDevice(event.name, event.address))
            }
            is Event.Connecting -> update { current ->
                current.copy(
                    phase = HrPhase.CONNECTING,
                    deviceAddress = event.address,
                    deviceName = event.name
                        ?: current.deviceName.takeIf { current.deviceAddress == event.address },
                    error = null,
                    bpm = null,
                )
            }
            Event.LinkUp -> update {
                if (it.phase != HrPhase.CONNECTED) it.copy(phase = HrPhase.CONNECTING, error = null) else it
            }
            is Event.Connected -> update {
                it.copy(
                    phase = HrPhase.CONNECTED,
                    deviceName = event.name ?: it.deviceName,
                    deviceAddress = event.gatt.device.address,
                    error = null,
                )
            }
            is Event.ConnectionFailed -> update {
                it.copy(phase = HrPhase.IDLE, bpm = null, error = "Connection failed (${event.status})")
            }
            is Event.ConnectionLost -> update {
                it.copy(
                    phase = HrPhase.IDLE,
                    bpm = null,
                    error = if (event.wasConnected) "Sensor disconnected" else null,
                )
            }
            Event.Disconnected -> update {
                it.copy(phase = HrPhase.IDLE, bpm = null, error = null, devices = emptyList())
            }
            Event.PermissionNeeded -> update { it.copy(phase = HrPhase.PERMISSION_NEEDED, error = null) }
            is Event.Error -> update { it.copy(phase = HrPhase.ERROR, error = event.message) }
        }
    }

    private fun update(transform: (HrSensorState) -> HrSensorState) {
        mainHandler.post { state = transform(state) }
    }
}
