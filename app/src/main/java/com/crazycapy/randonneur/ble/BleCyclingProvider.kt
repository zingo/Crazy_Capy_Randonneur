/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.crazycapy.randonneur.state.RideStore
import com.crazycapy.randonneur.state.SensorSource
import java.util.ArrayDeque
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

data class CyclingDevice(
    val name: String?,
    val address: String,
)

data class CyclingSensorState(
    val phase: CyclingPhase = CyclingPhase.IDLE,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val speedKmh: Double? = null,
    val cadenceRpm: Int? = null,
    val powerWatts: Int? = null,
    val error: String? = null,
    val devices: List<CyclingDevice> = emptyList(),
)

object BleCyclingProvider {
    private val cscServiceUuid = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
    private val cscMeasurementUuid = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
    private val cpsServiceUuid = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
    private val cpsMeasurementUuid = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val scanTimeoutMs = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var manager: BluetoothManager? = null
    private var scannerCallback: ScanCallback? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var desiredAddress: String? = null
    private var desiredName: String? = null
    private var autoReconnect = false
    private var reconnectAttempt = 0
    private var debugCadence: Int? = null
    private var debugPower: Int? = null

    private var cscCalculator = CscCalculator()
    private var cpsCalculator = CpsCalculator()
    private val subscriptions = ArrayDeque<BluetoothGattCharacteristic>()

    var state by mutableStateOf(CyclingSensorState())
        private set

    private val scanTimeout = Runnable { stopScan() }

    private val reconnectRunnable = Runnable {
        val address = desiredAddress ?: return@Runnable
        if (!autoReconnect) return@Runnable
        connect(address, desiredName, resumeReconnect = true)
    }

    fun init(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    fun hasPermissions(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun setDebugCadence(rpm: Int?) {
        debugCadence = rpm
    }

    fun setDebugPower(watts: Int?) {
        debugPower = watts
    }

    fun currentSpeedKmh(): Double? =
        state.speedKmh.takeIf { state.phase == CyclingPhase.CONNECTED }

    fun currentSpeedSource(): SensorSource? =
        SensorSource.BLE.takeIf { currentSpeedKmh() != null }

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

    @SuppressLint("MissingPermission")
    fun startScan() {
        val ctx = context ?: return
        if (!hasPermissions(ctx)) {
            update { it.copy(phase = CyclingPhase.PERMISSION_NEEDED, error = null) }
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            update { it.copy(phase = CyclingPhase.ERROR, error = "Bluetooth is off") }
            return
        }
        stopScan()
        closeGatt()
        autoReconnect = false
        desiredAddress = null
        reconnectAttempt = 0
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            update { it.copy(phase = CyclingPhase.ERROR, error = "BLE scanner unavailable") }
            return
        }
        update {
            it.copy(
                phase = CyclingPhase.SCANNING,
                error = null,
                devices = emptyList(),
                speedKmh = null,
                cadenceRpm = null,
                powerWatts = null,
            )
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(cscServiceUuid)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(cpsServiceUuid)).build(),
        )
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val address = device.address ?: return
                val name = result.scanRecord?.deviceName
                    ?: runCatching { device.name }.getOrNull()
                update { current ->
                    if (current.devices.any { it.address == address }) current
                    else current.copy(devices = current.devices + CyclingDevice(name, address))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                update { it.copy(phase = CyclingPhase.ERROR, error = "Scan failed ($errorCode)") }
                stopScan()
            }
        }
        scannerCallback = callback
        runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { failure ->
                scannerCallback = null
                update {
                    it.copy(phase = CyclingPhase.ERROR, error = failure.message ?: "Scan failed")
                }
                return
            }
        mainHandler.postDelayed(scanTimeout, scanTimeoutMs)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
        val callback = scannerCallback ?: return
        scannerCallback = null
        val adapter = manager?.adapter ?: return
        runCatching { adapter.bluetoothLeScanner?.stopScan(callback) }
        update {
            if (it.phase == CyclingPhase.SCANNING) it.copy(phase = CyclingPhase.IDLE) else it
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String? = null, resumeReconnect: Boolean = false) {
        val ctx = context ?: return
        if (!hasPermissions(ctx)) {
            update { it.copy(phase = CyclingPhase.PERMISSION_NEEDED, error = null) }
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            update { it.copy(phase = CyclingPhase.ERROR, error = "Bluetooth is off") }
            return
        }
        stopScan()
        desiredAddress = address
        if (!name.isNullOrBlank()) desiredName = name
        autoReconnect = true
        if (!resumeReconnect) reconnectAttempt = 0
        if (bluetoothGatt?.device?.address == address &&
            state.phase == CyclingPhase.CONNECTED
        ) {
            return
        }
        closeGatt()
        mainHandler.removeCallbacks(reconnectRunnable)
        cscCalculator = CscCalculator()
        cpsCalculator = CpsCalculator()
        subscriptions.clear()
        update { current ->
            current.copy(
                phase = CyclingPhase.CONNECTING,
                deviceAddress = address,
                deviceName = name ?: current.deviceName.takeIf { current.deviceAddress == address },
                error = null,
                speedKmh = null,
                cadenceRpm = null,
                powerWatts = null,
            )
        }
        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }
            .getOrElse {
                update { current -> current.copy(phase = CyclingPhase.ERROR, error = "Device not found") }
                return
            }
        bluetoothGatt = runCatching {
            device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrElse {
            update { current -> current.copy(phase = CyclingPhase.ERROR, error = "Connect failed") }
            null
        }
    }

    fun disconnect() {
        autoReconnect = false
        reconnectAttempt = 0
        desiredAddress = null
        mainHandler.removeCallbacks(reconnectRunnable)
        closeGatt()
        subscriptions.clear()
        update {
            it.copy(
                phase = CyclingPhase.IDLE,
                speedKmh = null,
                cadenceRpm = null,
                powerWatts = null,
                error = null,
                devices = emptyList(),
            )
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        autoReconnect = enabled
        if (!enabled) mainHandler.removeCallbacks(reconnectRunnable)
    }

    fun reconnectSaved(address: String?, name: String?) {
        if (address.isNullOrBlank()) return
        if (state.phase == CyclingPhase.CONNECTED && state.deviceAddress == address) {
            setAutoReconnect(true)
            return
        }
        connect(address, name)
    }

    fun shutdown() {
        autoReconnect = false
        mainHandler.removeCallbacks(reconnectRunnable)
        stopScan()
        closeGatt()
        subscriptions.clear()
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        mainHandler.removeCallbacks(reconnectRunnable)
        val gatt = bluetoothGatt
        bluetoothGatt = null
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnect || desiredAddress == null) return
        val delay = HrReconnectPolicy.delayForAttempt(reconnectAttempt)
        if (delay == null) {
            autoReconnect = false
            return
        }
        reconnectAttempt++
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun update(transform: (CyclingSensorState) -> CyclingSensorState) {
        mainHandler.post { state = transform(state) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) {
                runCatching { gatt.close() }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt()
                update {
                    it.copy(
                        phase = CyclingPhase.IDLE,
                        speedKmh = null,
                        cadenceRpm = null,
                        powerWatts = null,
                        error = "Connection failed ($status)",
                    )
                }
                scheduleReconnect()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (state.phase != CyclingPhase.CONNECTED) {
                    update { it.copy(phase = CyclingPhase.CONNECTING, error = null) }
                }
                runCatching { gatt.discoverServices() }.onFailure {
                    closeGatt()
                    update { current -> current.copy(phase = CyclingPhase.ERROR, error = "Service discovery failed") }
                    scheduleReconnect()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt()
                update {
                    it.copy(
                        phase = CyclingPhase.IDLE,
                        speedKmh = null,
                        cadenceRpm = null,
                        powerWatts = null,
                        error = if (it.phase == CyclingPhase.CONNECTED) "Sensor disconnected" else null,
                    )
                }
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            val csc = gatt.getService(cscServiceUuid)?.getCharacteristic(cscMeasurementUuid)
            val cps = gatt.getService(cpsServiceUuid)?.getCharacteristic(cpsMeasurementUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || (csc == null && cps == null)) {
                closeGatt()
                update { it.copy(phase = CyclingPhase.ERROR, error = "Cycling sensor service not found") }
                scheduleReconnect()
                return
            }
            subscriptions.clear()
            listOfNotNull(csc, cps).forEach { characteristic ->
                if (characteristic.getDescriptor(cccdUuid) == null) {
                    closeGatt()
                    update { it.copy(phase = CyclingPhase.ERROR, error = "Notification setup failed") }
                    scheduleReconnect()
                    return
                }
                subscriptions.add(characteristic)
            }
            subscribeNext(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || descriptor.uuid != cccdUuid) {
                closeGatt()
                update { it.copy(phase = CyclingPhase.ERROR, error = "Notification setup failed") }
                scheduleReconnect()
                return
            }
            subscribeNext(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== bluetoothGatt) return
            when (characteristic.uuid) {
                cscMeasurementUuid -> handleCsc(value)
                cpsMeasurementUuid -> handleCps(value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNext(gatt: BluetoothGatt) {
        val characteristic = subscriptions.pollFirst()
        if (characteristic == null) {
            reconnectAttempt = 0
            val name = desiredName ?: runCatching { gatt.device.name }.getOrNull()
            desiredName = name
            update {
                it.copy(
                    phase = CyclingPhase.CONNECTED,
                    deviceName = name ?: it.deviceName,
                    deviceAddress = gatt.device.address,
                    error = null,
                )
            }
            return
        }
        runCatching { gatt.setCharacteristicNotification(characteristic, true) }
        val cccd = characteristic.getDescriptor(cccdUuid)
        if (cccd == null) {
            closeGatt()
            update { it.copy(phase = CyclingPhase.ERROR, error = "Notification setup failed") }
            scheduleReconnect()
            return
        }
        runCatching {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }.onFailure {
            closeGatt()
            update { current -> current.copy(phase = CyclingPhase.ERROR, error = "Notification setup failed") }
            scheduleReconnect()
        }
    }

    private fun handleCsc(value: ByteArray) {
        val sample = CscMeasurementParser.parse(value) ?: return
        val reading = cscCalculator.update(sample, RideStore.wheelCircumferenceMm)
        update { current ->
            current.copy(
                speedKmh = reading.speedKmh ?: current.speedKmh,
                cadenceRpm = reading.cadenceRpm?.roundToInt() ?: current.cadenceRpm,
            )
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
}
