package com.dualframe.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val tileSize: Dp = 50.dp
    val tileRadius: Dp = 11.dp
    val tileGap: Dp = 5.dp
    val tileBg = Color(0xFF151515)
    val tileBorder = Color(0xFF252525)
    val iconColor = Color(0xFFCCCCCC)
    val iconSize: Dp = 18.dp
    val activeColor = Color(0xFF4CAF50)
    val inactiveColor = Color(0xFF666666)
    val font: FontFamily = FontFamily.SansSerif
}

@Composable
fun ControlRail(
    isRecording: Boolean,
    audioEnabled: Boolean,
    zoomRatio: Float,
    guidesEnabled: Boolean,
    timerSeconds: Int,
    flashOn: Boolean,
    showFlash: Boolean,
    keepScreenOn: Boolean,
    selfieEffect: Boolean,
    isFrontCamera: Boolean,
    resolution: String,
    deviceRotation: Int,
    onAudioToggle: () -> Unit,
    onZoomToggle: () -> Unit,
    onGuideToggle: () -> Unit,
    onTimerCycle: () -> Unit,
    onFlashToggle: () -> Unit,
    onKeepScreenToggle: () -> Unit,
    onSelfieEffectToggle: () -> Unit,
    onResolutionCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rot = when (deviceRotation) { 270 -> 90f; 90 -> -90f; else -> 0f }
    var expanded by remember { mutableStateOf(false) }

    // Hide entire rail during recording
    if (isRecording) return

    Column(
        modifier = modifier.padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(
            icon = Icons.Outlined.Settings,
            stateText = null, isActive = expanded,
            rotation = rot, onClick = { expanded = !expanded },
        )

        AnimatedVisibility(
            visible = expanded,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
        ) {
            Column(
                modifier = Modifier
                    .padding(top = RailTheme.tileGap)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RailTheme.tileGap),
            ) {
                IconTile(if (audioEnabled) Icons.Outlined.Mic else Icons.Outlined.MicOff,
                    if (audioEnabled) "ON" else "OFF", audioEnabled, rot, onAudioToggle)

                IconTile(Icons.Outlined.GridOn,
                    if (guidesEnabled) "ON" else "OFF", guidesEnabled, rot, onGuideToggle)

                if (showFlash) {
                    IconTile(if (flashOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                        if (flashOn) "ON" else "OFF", flashOn, rot, onFlashToggle)
                }

                IconTile(if (keepScreenOn) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    if (keepScreenOn) "ON" else "OFF", keepScreenOn, rot, onKeepScreenToggle)

                ZoomTile(zoomRatio, rot, onZoomToggle)

                TextTile(resolution, rot, onResolutionCycle)

                IconTile(Icons.Outlined.Timer,
                    if (timerSeconds > 0) "${timerSeconds}s" else "OFF",
                    timerSeconds > 0, rot, onTimerCycle)

                if (isFrontCamera) {
                    IconTile(Icons.Outlined.Visibility,
                        if (selfieEffect) "ON" else "OFF", selfieEffect, rot, onSelfieEffectToggle)
                }
            }
        }
    }
}

@Composable
private fun ZoomTile(zoomRatio: Float, rotation: Float, onClick: () -> Unit) {
    val displayText = if (zoomRatio < 0.8f) "0.6x" else "1.0x"
    Box(
        modifier = Modifier.size(RailTheme.tileSize)
            .clip(RoundedCornerShape(RailTheme.tileRadius))
            .background(RailTheme.tileBg)
            .border(0.5.dp, RailTheme.tileBorder, RoundedCornerShape(RailTheme.tileRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(displayText, color = RailTheme.activeColor, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, fontFamily = RailTheme.font,
            modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun TextTile(text: String, rotation: Float, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(RailTheme.tileSize)
            .clip(RoundedCornerShape(RailTheme.tileRadius))
            .background(RailTheme.tileBg)
            .border(0.5.dp, RailTheme.tileBorder, RoundedCornerShape(RailTheme.tileRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = RailTheme.iconColor, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, fontFamily = RailTheme.font,
            modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun IconTile(
    icon: ImageVector,
    stateText: String?,
    isActive: Boolean,
    rotation: Float,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(RailTheme.tileSize)
            .clip(RoundedCornerShape(RailTheme.tileRadius))
            .background(RailTheme.tileBg)
            .border(0.5.dp, RailTheme.tileBorder, RoundedCornerShape(RailTheme.tileRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.rotate(rotation)) {
            Icon(icon, null,
                tint = if (isActive) RailTheme.activeColor else RailTheme.iconColor,
                modifier = Modifier.size(RailTheme.iconSize))
            if (stateText != null) {
                Text(stateText,
                    color = if (isActive) RailTheme.activeColor else RailTheme.inactiveColor,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = RailTheme.font)
            }
        }
    }
}
