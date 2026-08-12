/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.crazycapy.randonneur.state.RideMode
import com.crazycapy.randonneur.state.RideStore

/**
 * Pre-start dialog: choose the riding direction before launching a ride.
 */
@Composable
internal fun StartRideDialog(
    mode: RideMode,
    onDismiss: () -> Unit,
    onStart: (reverse: Boolean) -> Unit,
) {
    var reverse by remember { mutableStateOf(RideStore.reverse) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == RideMode.GHOST) "Start ghost ride" else "Start navigation") },
        text = {
            Column {
                Text(
                    if (reverse) "Riding the route in reverse" else "Riding the route as recorded",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Switch(
                    checked = reverse,
                    onCheckedChange = { reverse = it },
                    modifier = Modifier.align(Alignment.Start),
                )
                Text(
                    "Reverse direction",
                    modifier = Modifier.align(Alignment.Start),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(reverse) }) { Text(if (mode == RideMode.GHOST) "Start ghost" else "Navigate") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
