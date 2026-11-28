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
import com.crazycapy.randonneur.state.SensorSource
import java.util.UUID
import kotlin.math.roundToInt

enum class CyclingPhase {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    PERMISSION_NEEDED,
    ERROR,
}

data class CyclingSensorState(
    val phase: CyclingPhase = CyclingPhase.IDLE,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val cadenceRpm: Int? = null,
    val powerWatts: Int? = null,
    val error: String? = null,
    val devices: List<BleDevice> = emptyList(),
)

private val cscServiceUuid = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
private val cscMeasurementUuid = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
private val cpsServiceUuid = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
private val cpsMeasurementUuid = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")

object BleCyclingProvider : BleSensorProvider(
    scanServiceUuids = listOf(cscServiceUuid, cpsServiceUuid),
    serviceNotFoundError = "Cycling sensor service not found",
) {
    private var debugCadence: Int? = null
    private var debugPower: Int? = null

    private var cscCalculator = CscCalculator()
    private var cpsCalculator = CpsCalculator()

    var state by mutableStateOf(CyclingSensorState())
        private set

    fun setDebugCadence(rpm: Int?) {
        debugCadence = rpm
    }

    fun setDebugPower(watts: Int?) {
        debugPower = watts
    }

    fun currentCadence(): Int? =
        debugCadence ?: state.cadenceRpm.takeIf { state.phase == CyclingPhase.CONNECTED }

    fun currentCadenceSource(): SensorSource? = when {
        debugCadence != null -> SensorSource.DEBUG
        currentCadence() != null -> SensorSource.BLE
        else -> null
    }

    fun currentPower(): Int? =
        debugPower ?: state.powerWatts.takeIf { state.phase == CyclingPhase.CONNECTED }

    fun currentPowerSource(): SensorSource? = when {
        debugPower != null -> SensorSource.DEBUG
        currentPower() != null -> SensorSource.BLE
        else -> null
    }

    override fun onBeforeConnect() {
        cscCalculator = CscCalculator()
        cpsCalculator = CpsCalculator()
    }

    override fun notificationCharacteristics(gatt: BluetoothGatt): List<BluetoothGattCharacteristic> =
        listOfNotNull(
            gatt.getService(cscServiceUuid)?.getCharacteristic(cscMeasurementUuid),
            gatt.getService(cpsServiceUuid)?.getCharacteristic(cpsMeasurementUuid),
        )

    override fun onNotification(uuid: UUID, value: ByteArray) {
        when (uuid) {
            cscMeasurementUuid -> handleCsc(value)
            cpsMeasurementUuid -> handleCps(value)
        }
    }

    override fun handle(event: Event) {
        when (event) {
            Event.ScanStarted -> update {
                it.copy(
                    phase = CyclingPhase.SCANNING,
                    error = null,
                    devices = emptyList(),
                    cadenceRpm = null,
                    powerWatts = null,
                )
            }
            Event.ScanStopped -> update {
                if (it.phase == CyclingPhase.SCANNING) it.copy(phase = CyclingPhase.IDLE) else it
            }
            is Event.DeviceFound -> update { current ->
                if (current.devices.any { it.address == event.address }) current
                else current.copy(devices = current.devices + BleDevice(event.name, event.address))
            }
            is Event.Connecting -> update { current ->
                current.copy(
                    phase = CyclingPhase.CONNECTING,
                    deviceAddress = event.address,
                    deviceName = event.name
                        ?: current.deviceName.takeIf { current.deviceAddress == event.address },
                    error = null,
                    cadenceRpm = null,
                    powerWatts = null,
                )
            }
            Event.LinkUp -> update {
                if (it.phase != CyclingPhase.CONNECTED) {
                    it.copy(phase = CyclingPhase.CONNECTING, error = null)
                } else {
                    it
                }
            }
            is Event.Connected -> update {
                it.copy(
                    phase = CyclingPhase.CONNECTED,
                    deviceName = event.name ?: it.deviceName,
                    deviceAddress = event.gatt.device.address,
                    error = null,
                )
            }
            is Event.ConnectionFailed -> update {
                it.copy(
                    phase = CyclingPhase.IDLE,
                    cadenceRpm = null,
                    powerWatts = null,
                    error = "Connection failed (${event.status})",
                )
            }
            is Event.ConnectionLost -> update {
                it.copy(
                    phase = CyclingPhase.IDLE,
                    cadenceRpm = null,
                    powerWatts = null,
                    error = if (event.wasConnected) "Sensor disconnected" else null,
                )
            }
            Event.Disconnected -> update {
                it.copy(
                    phase = CyclingPhase.IDLE,
                    cadenceRpm = null,
                    powerWatts = null,
                    error = null,
                    devices = emptyList(),
                )
            }
            Event.PermissionNeeded -> update {
                it.copy(phase = CyclingPhase.PERMISSION_NEEDED, error = null)
            }
            is Event.Error -> update { it.copy(phase = CyclingPhase.ERROR, error = event.message) }
        }
    }

    private fun handleCsc(value: ByteArray) {
        val sample = CscMeasurementParser.parse(value) ?: return
        val reading = cscCalculator.update(sample)
        update { current ->
            current.copy(cadenceRpm = reading.cadenceRpm?.roundToInt() ?: current.cadenceRpm)
        }
    }

    private fun handleCps(value: ByteArray) {
        val sample = CpsMeasurementParser.parse(value) ?: return
        val reading = cpsCalculator.update(sample)
        update { current ->
            current.copy(
                powerWatts = reading.powerWatts,
                cadenceRpm = reading.cadenceRpm?.roundToInt() ?: current.cadenceRpm,
            )
        }
    }

    private fun update(transform: (CyclingSensorState) -> CyclingSensorState) {
        mainHandler.post { state = transform(state) }
    }
}
