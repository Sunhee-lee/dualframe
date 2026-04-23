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
    val tileSize: Dp = 72.dp
    val tileRadius: Dp = 14.dp
    val tileGap: Dp = 6.dp
    val tileBg = Color(0xFF1A1A1A)
    val tileBorder = Color(0xFF2A2A2A)
    val iconColor = Color(0xFFE0E0E0)
    val activeColor = Color(0xFF4CAF50)
    val inactiveColor = Color(0xFF888888)
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
    deviceRotation: Int,
    onAudioToggle: () -> Unit,
    onZoomReset: () -> Unit,
    onGuideToggle: () -> Unit,
    onTimerCycle: () -> Unit,
    onFlashToggle: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rot = when (deviceRotation) { 270 -> 90f; 90 -> -90f; else -> 0f }

    Column(
        modifier = modifier.padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RailTheme.tileGap),
    ) {
        // 1. Recording time
        RecTimeTile(isRecording, recordingSeconds, rot)

        // 2. Audio
        SquareControlTile(
            icon = if (audioEnabled) Icons.Outlined.Mic else Icons.Outlined.MicOff,
            label = "오디오",
            stateText = if (audioEnabled) "ON" else "OFF",
            isActive = audioEnabled,
            rotation = rot,
            onClick = onAudioToggle,
        )

        // 3. Zoom
        SquareControlTile(
            icon = Icons.Outlined.Search,
            label = "줌 설정",
            stateText = "${"%.1f".format(zoomRatio)}x",
            isActive = true,
            rotation = rot,
            onClick = onZoomReset,
        )

        // 4. Guide
        SquareControlTile(
            icon = Icons.Outlined.GridOn,
            label = "가이드",
            stateText = if (guidesEnabled) "ON" else "OFF",
            isActive = guidesEnabled,
            rotation = rot,
            onClick = onGuideToggle,
        )

        // 5. Timer
        SquareControlTile(
            icon = Icons.Outlined.Timer,
            label = "타이머",
            stateText = if (timerSeconds > 0) "${timerSeconds}s" else "OFF",
            isActive = timerSeconds > 0,
            rotation = rot,
            onClick = onTimerCycle,
        )

        // 6. Flash
        if (showFlash) {
            SquareControlTile(
                icon = if (flashOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                label = "플래시",
                stateText = if (flashOn) "ON" else "OFF",
                isActive = flashOn,
                rotation = rot,
                onClick = onFlashToggle,
            )
        }

        // 7. Settings
        SquareControlTile(
            icon = Icons.Outlined.Settings,
            label = "설정",
            stateText = null,
            isActive = false,
            rotation = rot,
            onClick = onSettings,
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
                Spacer(Modifier.height(3.dp))
                Text(
                    text = formatRecTime(seconds),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Text("00:00:00", color = RailTheme.inactiveColor, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(2.dp))
            Text("녹화 시간", color = RailTheme.inactiveColor, fontSize = 9.sp)
        }
    }
}

@Composable
private fun SquareControlTile(
    icon: ImageVector,
    label: String,
    stateText: String?,
    isActive: Boolean,
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
                imageVector = icon,
                contentDescription = label,
                tint = RailTheme.iconColor,
                modifier = Modifier.size(22.dp),
            )
            if (stateText != null) {
                Text(
                    text = stateText,
                    color = if (isActive) RailTheme.activeColor else RailTheme.inactiveColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(label, color = RailTheme.inactiveColor, fontSize = 9.sp)
        }
    }
}

private fun formatRecTime(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
