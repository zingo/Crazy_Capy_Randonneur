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
import java.util.UUID

enum class HrPhase {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    PERMISSION_NEEDED,
    ERROR,
}

data class HrDevice(
    val name: String?,
    val address: String,
)

data class HrSensorState(
    val phase: HrPhase = HrPhase.IDLE,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val bpm: Int? = null,
    val error: String? = null,
    val devices: List<HrDevice> = emptyList(),
)

object BleHrProvider : HrProvider {
    private val serviceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val measurementUuid = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
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
    private var debugHr: Int? = null

    var state by mutableStateOf(HrSensorState())
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

    fun setDebugHr(bpm: Int?) {
        debugHr = bpm
        update { it.copy(bpm = bpm ?: it.bpm) }
    }

    override fun currentHr(): Int? = debugHr ?: state.bpm.takeIf { state.phase == HrPhase.CONNECTED }

    fun currentHrIsDebug(): Boolean = debugHr != null

    @SuppressLint("MissingPermission")
    fun startScan() {
        val ctx = context ?: return
        if (!hasPermissions(ctx)) {
            update { it.copy(phase = HrPhase.PERMISSION_NEEDED, error = null) }
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            update { it.copy(phase = HrPhase.ERROR, error = "Bluetooth is off") }
            return
        }
        stopScan()
        closeGatt()
        autoReconnect = false
        desiredAddress = null
        reconnectAttempt = 0
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            update { it.copy(phase = HrPhase.ERROR, error = "BLE scanner unavailable") }
            return
        }
        update { it.copy(phase = HrPhase.SCANNING, error = null, devices = emptyList()) }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build(),
        )
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val address = device.address ?: return
                val name = result.scanRecord?.deviceName
                    ?: runCatching { device.name }.getOrNull()
                update { current ->
                    if (current.devices.any { it.address == address }) current
                    else current.copy(devices = current.devices + HrDevice(name, address))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                update { it.copy(phase = HrPhase.ERROR, error = "Scan failed ($errorCode)") }
                stopScan()
            }
        }
        scannerCallback = callback
        runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { failure ->
                scannerCallback = null
                update {
                    it.copy(phase = HrPhase.ERROR, error = failure.message ?: "Scan failed")
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
            if (it.phase == HrPhase.SCANNING) it.copy(phase = HrPhase.IDLE) else it
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String? = null, resumeReconnect: Boolean = false) {
        val ctx = context ?: return
        if (!hasPermissions(ctx)) {
            update { it.copy(phase = HrPhase.PERMISSION_NEEDED, error = null) }
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            update { it.copy(phase = HrPhase.ERROR, error = "Bluetooth is off") }
            return
        }
        stopScan()
        desiredAddress = address
        if (!name.isNullOrBlank()) desiredName = name
        autoReconnect = true
        if (!resumeReconnect) reconnectAttempt = 0
        if (bluetoothGatt?.device?.address == address &&
            state.phase == HrPhase.CONNECTED
        ) {
            return
        }
        closeGatt()
        mainHandler.removeCallbacks(reconnectRunnable)
        update { current ->
            current.copy(
                phase = HrPhase.CONNECTING,
                deviceAddress = address,
                deviceName = name ?: current.deviceName.takeIf { current.deviceAddress == address },
                error = null,
                bpm = null,
            )
        }
        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }
            .getOrElse {
                update { current -> current.copy(phase = HrPhase.ERROR, error = "Device not found") }
                return
            }
        bluetoothGatt = runCatching {
            device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrElse {
            update { current -> current.copy(phase = HrPhase.ERROR, error = "Connect failed") }
            null
        }
    }

    fun disconnect() {
        autoReconnect = false
        reconnectAttempt = 0
        desiredAddress = null
        mainHandler.removeCallbacks(reconnectRunnable)
        closeGatt()
        update {
            it.copy(
                phase = HrPhase.IDLE,
                bpm = null,
                error = null,
                devices = emptyList(),
            )
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        autoReconnect = enabled
        if (!enabled) {
            mainHandler.removeCallbacks(reconnectRunnable)
        }
    }

    fun reconnectSaved(address: String?, name: String?) {
        if (address.isNullOrBlank()) return
        if (state.phase == HrPhase.CONNECTED && state.deviceAddress == address) {
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

    private fun update(transform: (HrSensorState) -> HrSensorState) {
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
                    it.copy(phase = HrPhase.IDLE, bpm = null, error = "Connection failed ($status)")
                }
                scheduleReconnect()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (state.phase != HrPhase.CONNECTED) {
                    update { it.copy(phase = HrPhase.CONNECTING, error = null) }
                }
                runCatching { gatt.discoverServices() }.onFailure {
                    closeGatt()
                    update { current -> current.copy(phase = HrPhase.ERROR, error = "Service discovery failed") }
                    scheduleReconnect()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt()
                update {
                    it.copy(
                        phase = HrPhase.IDLE,
                        bpm = null,
                        error = if (it.phase == HrPhase.CONNECTED) "Sensor disconnected" else null,
                    )
                }
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            val service = gatt.getService(serviceUuid)
            val characteristic = service?.getCharacteristic(measurementUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                closeGatt()
                update { it.copy(phase = HrPhase.ERROR, error = "Heart-rate service not found") }
                scheduleReconnect()
                return
            }
            runCatching { gatt.setCharacteristicNotification(characteristic, true) }
            val cccd = characteristic.getDescriptor(cccdUuid)
            if (cccd == null) {
                closeGatt()
                update { it.copy(phase = HrPhase.ERROR, error = "Notification setup failed") }
                scheduleReconnect()
                return
            }
            runCatching {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }.onFailure {
                closeGatt()
                update { current -> current.copy(phase = HrPhase.ERROR, error = "Notification setup failed") }
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt()
                update { it.copy(phase = HrPhase.ERROR, error = "Notification setup failed") }
                scheduleReconnect()
                return
            }
            if (descriptor.uuid != cccdUuid) return
            reconnectAttempt = 0
            val name = desiredName ?: runCatching { gatt.device.name }.getOrNull()
            desiredName = name
            update {
                it.copy(
                    phase = HrPhase.CONNECTED,
                    deviceName = name ?: it.deviceName,
                    deviceAddress = gatt.device.address,
                    error = null,
                )
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== bluetoothGatt || characteristic.uuid != measurementUuid) return
            val sample = HrMeasurementParser.parse(value) ?: return
            update { it.copy(bpm = sample.bpm) }
        }
    }
}
