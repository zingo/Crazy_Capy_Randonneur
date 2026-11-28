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
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.UUID

data class BleDevice(
    val name: String?,
    val address: String,
)

abstract class BleSensorProvider(
    private val scanServiceUuids: List<UUID>,
    private val serviceNotFoundError: String,
) {
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val scanTimeoutMs = 15_000L

    protected val mainHandler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var manager: BluetoothManager? = null
    private var scannerCallback: ScanCallback? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var desiredAddress: String? = null
    private var desiredName: String? = null
    private var autoReconnect = false
    private var reconnectAttempt = 0
    private var connected = false
    private val subscriptions = ArrayDeque<BluetoothGattCharacteristic>()

    protected sealed class Event {
        object ScanStarted : Event()
        object ScanStopped : Event()
        data class DeviceFound(val name: String?, val address: String) : Event()
        data class Connecting(val address: String, val name: String?) : Event()
        object LinkUp : Event()
        data class Connected(val gatt: BluetoothGatt, val name: String?) : Event()
        data class ConnectionFailed(val status: Int) : Event()
        data class ConnectionLost(val wasConnected: Boolean) : Event()
        object Disconnected : Event()
        object PermissionNeeded : Event()
        data class Error(val message: String) : Event()
    }

    protected abstract fun handle(event: Event)

    protected abstract fun notificationCharacteristics(gatt: BluetoothGatt): List<BluetoothGattCharacteristic>

    protected abstract fun onNotification(uuid: UUID, value: ByteArray)

    protected open fun onBeforeConnect() {}

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

    @SuppressLint("MissingPermission")
    fun startScan() {
        val ctx = context ?: return
        if (!hasPermissions(ctx)) {
            handle(Event.PermissionNeeded)
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            handle(Event.Error("Bluetooth is off"))
            return
        }
        stopScan()
        closeGatt()
        autoReconnect = false
        desiredAddress = null
        reconnectAttempt = 0
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            handle(Event.Error("BLE scanner unavailable"))
            return
        }
        handle(Event.ScanStarted)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = scanServiceUuids.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val address = device.address ?: return
                val name = result.scanRecord?.deviceName
                    ?: runCatching { device.name }.getOrNull()
                handle(Event.DeviceFound(name, address))
            }

            override fun onScanFailed(errorCode: Int) {
                handle(Event.Error("Scan failed ($errorCode)"))
                stopScan()
            }
        }
        scannerCallback = callback
        runCatching { scanner.startScan(filters, settings, callback) }
            .onFailure { failure ->
                scannerCallback = null
                handle(Event.Error(failure.message ?: "Scan failed"))
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
        handle(Event.ScanStopped)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, name: String? = null, resumeReconnect: Boolean = false) {
        val ctx = context ?: return
        if (!hasPermissions(ctx)) {
            handle(Event.PermissionNeeded)
            return
        }
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            handle(Event.Error("Bluetooth is off"))
            return
        }
        stopScan()
        desiredAddress = address
        if (!name.isNullOrBlank()) desiredName = name
        autoReconnect = true
        if (!resumeReconnect) reconnectAttempt = 0
        if (bluetoothGatt?.device?.address == address && connected) return
        closeGatt()
        mainHandler.removeCallbacks(reconnectRunnable)
        subscriptions.clear()
        onBeforeConnect()
        handle(Event.Connecting(address, name))
        val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }
            .getOrElse {
                handle(Event.Error("Device not found"))
                return
            }
        bluetoothGatt = runCatching {
            device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrElse {
            handle(Event.Error("Connect failed"))
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
        handle(Event.Disconnected)
    }

    fun setAutoReconnect(enabled: Boolean) {
        autoReconnect = enabled
        if (!enabled) mainHandler.removeCallbacks(reconnectRunnable)
    }

    fun reconnectSaved(address: String?, name: String?) {
        if (address.isNullOrBlank()) return
        if (connected && bluetoothGatt?.device?.address == address) {
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
        connected = false
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

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) {
                runCatching { gatt.close() }
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt()
                handle(Event.ConnectionFailed(status))
                scheduleReconnect()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handle(Event.LinkUp)
                runCatching { gatt.discoverServices() }.onFailure {
                    closeGatt()
                    handle(Event.Error("Service discovery failed"))
                    scheduleReconnect()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasConnected = connected
                closeGatt()
                handle(Event.ConnectionLost(wasConnected))
                scheduleReconnect()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            val characteristics = notificationCharacteristics(gatt)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristics.isEmpty()) {
                closeGatt()
                handle(Event.Error(serviceNotFoundError))
                scheduleReconnect()
                return
            }
            subscriptions.clear()
            characteristics.forEach { characteristic ->
                if (characteristic.getDescriptor(cccdUuid) == null) {
                    closeGatt()
                    handle(Event.Error("Notification setup failed"))
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
                handle(Event.Error("Notification setup failed"))
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
            onNotification(characteristic.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeNext(gatt: BluetoothGatt) {
        val characteristic = subscriptions.pollFirst()
        if (characteristic == null) {
            reconnectAttempt = 0
            val name = desiredName ?: runCatching { gatt.device.name }.getOrNull()
            desiredName = name
            connected = true
            handle(Event.Connected(gatt, name))
            return
        }
        runCatching { gatt.setCharacteristicNotification(characteristic, true) }
        val cccd = characteristic.getDescriptor(cccdUuid)
        if (cccd == null) {
            closeGatt()
            handle(Event.Error("Notification setup failed"))
            scheduleReconnect()
            return
        }
        runCatching {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }.onFailure {
            closeGatt()
            handle(Event.Error("Notification setup failed"))
            scheduleReconnect()
        }
    }
}
