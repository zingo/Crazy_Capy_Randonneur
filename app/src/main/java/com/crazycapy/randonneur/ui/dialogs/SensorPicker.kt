/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.dialogs

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crazycapy.randonneur.ble.BleDevice

internal data class SensorActions(
    val onScan: () -> Unit,
    val onConnect: (String, String?) -> Unit,
)

@Composable
internal fun rememberSensorActions(
    hasPermissions: () -> Boolean,
    startScan: () -> Unit,
    connect: (String, String?) -> Unit,
    saveDevice: (String, String?) -> Unit,
): SensorActions {
    var pending by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val target = pending
        pending = null
        if (hasPermissions()) {
            if (target != null) connect(target.first, target.second) else startScan()
        }
    }
    fun requestPermission() {
        permission.launch(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
        )
    }
    return SensorActions(
        onScan = {
            if (hasPermissions()) {
                startScan()
            } else {
                pending = null
                requestPermission()
            }
        },
        onConnect = { address, name ->
            saveDevice(address, name)
            if (hasPermissions()) {
                connect(address, name)
            } else {
                pending = address to name
                requestPermission()
            }
        },
    )
}

@Composable
internal fun SensorSection(
    title: String,
    status: String,
    description: String,
    connected: Boolean,
    scanning: Boolean,
    devices: List<BleDevice>,
    savedAddress: String?,
    savedName: String?,
    emptyText: String,
    chooseText: String,
    fallbackName: String,
    actions: SensorActions,
    onCancelScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (connected) {
                TextButton(onClick = onDisconnect) { Text("Disconnect") }
            } else {
                if (scanning) {
                    TextButton(onClick = onCancelScan) { Text("Cancel scan") }
                } else {
                    TextButton(onClick = actions.onScan) { Text("Scan") }
                }
                if (!savedAddress.isNullOrBlank() && !scanning) {
                    TextButton(onClick = { actions.onConnect(savedAddress, savedName) }) {
                        Text("Connect ${savedName ?: "saved sensor"}")
                    }
                }
            }
        }
        if (scanning) {
            if (devices.isEmpty()) {
                Text(emptyText, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(chooseText, style = MaterialTheme.typography.bodySmall)
                devices.forEach { device ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name ?: fallbackName, style = MaterialTheme.typography.bodyMedium)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { actions.onConnect(device.address, device.name) }) {
                            Text("Connect")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
