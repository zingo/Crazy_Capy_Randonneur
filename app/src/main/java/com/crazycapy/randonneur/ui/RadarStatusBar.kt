/*
 * Copyright (c) 2026 Crazy Capy Randonneur contributors
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RadarStatusBar — compact live rear-radar status + overlay/tail-light toggles
 *
 * Rendered under the TrainingHud and always available whenever the overlay app
 * is installed and permitted (not only during navigation): a battery chip plus
 * an overlay show/hide toggle and a tail-light off/on toggle. The status text
 * is tappable to jump to the overlay app. The tail-light toggle is greyed until
 * a radar is connected. Nothing is drawn when the overlay app is absent or the
 * integration is disabled.
 */
package com.crazycapy.randonneur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.jjrh.bikeradar.ipc.IRadarService
import com.crazycapy.randonneur.radar.RadarClient
import com.crazycapy.randonneur.state.RideStore

/** Live rear-radar status chip + overlay/tail-light toggles. Always visible when the overlay app is present. */
@Composable
fun RadarStatusBar(modifier: Modifier = Modifier) {
    if (!RideStore.radarAvailable || !RideStore.radarIntegrationEnabled) return
    val context = LocalContext.current
    val fg = if (RideStore.darkMap) Color.White else Color(0xFF1A1A1A)
    val sub = if (RideStore.darkMap) Color(0xFFBABABA) else Color(0xFF666666)
    val bg = if (RideStore.darkMap) Color(0xCC121212) else Color(0xE8FFFFFF)

    Row(
        modifier
            .background(bg, RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val connected = RideStore.radarConnected
        val battery = RideStore.radarBatteryPercent
        val label = when {
            connected && battery != null -> "Radar $battery%"
            connected -> "Radar connected"
            else -> "Radar idle"
        }
        Text(
            text = label,
            color = if (connected) fg else sub,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { RadarClient.launchOverlayApp(context) },
        )
        val overlayOn = RideStore.radarOverlayVisible
        ToggleButton(
            active = overlayOn,
            enabled = true,
            onToggle = { RadarClient.setOverlayVisible(!overlayOn) },
            icon = if (overlayOn) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            contentDescription = "Show radar overlay",
        )
        val lightOn = RideStore.radarLightOn
        ToggleButton(
            active = lightOn,
            enabled = connected,
            onToggle = {
                RideStore.radarLightOn = !lightOn
                RadarClient.setRadarLightMode(if (lightOn) IRadarService.LIGHT_OFF else IRadarService.LIGHT_SOLID)
            },
            icon = Icons.Filled.Lightbulb,
            contentDescription = "Tail light",
        )
    }
}

@Composable
private fun ToggleButton(
    active: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
) {
    val onColor = Color(0xFF7CC29A)
    Row(
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(if (active) onColor else Color(0xFF666666), CircleShape)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) Color(0xFF12301F) else Color(0xFF1A1A1A),
            modifier = Modifier.size(16.dp),
        )
    }
}
