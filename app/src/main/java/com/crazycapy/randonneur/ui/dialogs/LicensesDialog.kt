/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.crazycapy.randonneur.ui.dialogs

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * Full-screen scrollable viewer for the bundled third-party notices (and our license).
 */
@Composable
internal fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember(context) {
        runCatching {
            context.assets.open("notices/THIRD_PARTY_NOTICES.md")
                .bufferedReader()
                .use { it.readText() }
        }.getOrDefault("Third-party notices could not be loaded.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licenses") },
        text = {
            Text(
                notices,
                modifier = Modifier.verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
