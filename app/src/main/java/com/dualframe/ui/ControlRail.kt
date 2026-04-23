package com.dualframe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object RailTheme {
    val tileSize: Dp = 52.dp
    val tileRadius: Dp = 12.dp
    val tileGap: Dp = 5.dp
    val tileBg = Color(0xFF1A1A1A)
    val tileBorder = Color(0xFF2A2A2A)
    val iconColor = Color(0xFFE0E0E0)
    val iconSize: Dp = 24.dp
    val activeColor = Color(0xFF4CAF50)
    val inactiveColor = Color(0xFF777777)
    val recDotColor = Color(0xFFFF1744)
}

@Composable
fun ControlRail(
    isRecording: Boolean,
    recordingSeconds: Int,
    audioEnabled: Boolean,
    zoomRatio: Float,
    guidesEnabled: Boolean,
    timerSeconds: Int,
    flashOn: Boolean,
    showFlash: Boolean,
    keepScreenOn: Boolean,
    deviceRotation: Int,
    onAudioToggle: () -> Unit,
    onZoomReset: () -> Unit,
    onGuideToggle: () -> Unit,
    onTimerCycle: () -> Unit,
    onFlashToggle: () -> Unit,
    onKeepScreenToggle: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rot = when (deviceRotation) { 270 -> 90f; 90 -> -90f; else -> 0f }

    Column(
        modifier = modifier.padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RailTheme.tileGap),
    ) {
        RecTimeTile(isRecording, recordingSeconds, rot)

        IconTile(
            icon = if (audioEnabled) Icons.Outlined.Mic else Icons.Outlined.MicOff,
            label = "Audio", isActive = audioEnabled,
            stateText = if (audioEnabled) "ON" else "OFF",
            rotation = rot, onClick = onAudioToggle,
        )

        IconTile(
            icon = Icons.Outlined.Search,
            label = "Zoom", isActive = true,
            stateText = "${"%.1f".format(zoomRatio)}x",
            rotation = rot, onClick = onZoomReset,
        )

        IconTile(
            icon = Icons.Outlined.GridOn,
            label = "Guide", isActive = guidesEnabled,
            stateText = if (guidesEnabled) "ON" else "OFF",
            rotation = rot, onClick = onGuideToggle,
        )

        IconTile(
            icon = Icons.Outlined.Timer,
            label = "Timer", isActive = timerSeconds > 0,
            stateText = if (timerSeconds > 0) "${timerSeconds}s" else "OFF",
            rotation = rot, onClick = onTimerCycle,
        )

        if (showFlash) {
            IconTile(
                icon = if (flashOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                label = "Flash", isActive = flashOn,
                stateText = null, rotation = rot, onClick = onFlashToggle,
            )
        }

        IconTile(
            icon = if (keepScreenOn) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            label = "Screen", isActive = keepScreenOn,
            stateText = null, rotation = rot, onClick = onKeepScreenToggle,
        )

        IconTile(
            icon = Icons.Outlined.Settings,
            label = "Settings", isActive = false,
            stateText = null, rotation = rot, onClick = onSettings,
        )
    }
}

@Composable
private fun RecTimeTile(isRecording: Boolean, seconds: Int, rotation: Float) {
    Box(
        modifier = Modifier
            .size(RailTheme.tileSize)
            .clip(RoundedCornerShape(RailTheme.tileRadius))
            .background(RailTheme.tileBg)
            .border(0.5.dp, RailTheme.tileBorder, RoundedCornerShape(RailTheme.tileRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.rotate(rotation),
        ) {
            if (isRecording) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(RailTheme.recDotColor))
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatRecTime(seconds),
                    color = Color.White, fontSize = 10.sp,
                    fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif,
                )
            } else {
                Text("--:--", color = RailTheme.inactiveColor, fontSize = 10.sp,
                    fontFamily = FontFamily.SansSerif)
            }
        }
    }
}

@Composable
private fun IconTile(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    stateText: String?,
    rotation: Float,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(RailTheme.tileSize)
            .clip(RoundedCornerShape(RailTheme.tileRadius))
            .background(RailTheme.tileBg)
            .border(0.5.dp, RailTheme.tileBorder, RoundedCornerShape(RailTheme.tileRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.rotate(rotation),
        ) {
            Icon(
                imageVector = icon, contentDescription = label,
                tint = if (isActive) RailTheme.activeColor else RailTheme.iconColor,
                modifier = Modifier.size(RailTheme.iconSize),
            )
            if (stateText != null) {
                Text(
                    text = stateText,
                    color = if (isActive) RailTheme.activeColor else RailTheme.inactiveColor,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                )
            }
        }
    }
}

private fun formatRecTime(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
