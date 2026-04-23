package com.dualframe.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dualframe.data.AppStatus

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
    appStatus: AppStatus,
    cameraReady: Boolean,
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
    layoutSwapped: Boolean,
    onAudioToggle: () -> Unit,
    onZoomToggle: () -> Unit,
    onGuideToggle: () -> Unit,
    onTimerCycle: () -> Unit,
    onFlashToggle: () -> Unit,
    onKeepScreenToggle: () -> Unit,
    onSelfieEffectToggle: () -> Unit,
    onResolutionCycle: () -> Unit,
    onSwapToggle: () -> Unit,
    onSwitchCamera: () -> Unit,
    onRecord: () -> Unit,
    onGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rot = when (deviceRotation) { 270 -> 90f; 90 -> -90f; else -> 0f }
    var expanded by remember { mutableStateOf(false) }
    val isRecording = appStatus == AppStatus.RECORDING
    val isCountdown = appStatus == AppStatus.COUNTDOWN
    val isExporting = appStatus == AppStatus.EXPORTING_NATIVE || appStatus == AppStatus.EXPORTING_CROPPED
    val actionEnabled = cameraReady && !isExporting

    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Settings gear — hidden during recording
        if (!isRecording) {
            IconTile(
                icon = Icons.Outlined.Settings,
                stateText = null, isActive = expanded,
                rotation = rot, onClick = { expanded = !expanded },
            )
        }

        // Expandable settings — hidden during recording
        AnimatedVisibility(
            visible = expanded && !isRecording,
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

                IconTile(if (keepScreenOn) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    if (keepScreenOn) "ON" else "OFF", keepScreenOn, rot, onKeepScreenToggle)

                ZoomTile(zoomRatio, rot, onZoomToggle)

                TextTile(resolution, rot, onResolutionCycle)

                IconTile(Icons.Outlined.Timer,
                    if (timerSeconds > 0) "${timerSeconds}s" else "OFF",
                    timerSeconds > 0, rot, onTimerCycle)

                if (isFrontCamera) {
                    TextTile(
                        if (selfieEffect) "Beauty\non" else "Beauty\noff",
                        rot, onSelfieEffectToggle, isActive = selfieEffect,
                    )
                }

                IconTile(Icons.Outlined.SwapHoriz,
                    null, layoutSwapped, rot, onSwapToggle)
            }
        }

        // Push action buttons to bottom — fixed position
        Spacer(Modifier.weight(1f))

        // Action buttons — always visible, position stable
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RailTheme.tileGap),
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF1A1A1A))
                    .clickable(enabled = actionEnabled && !isRecording) { onSwitchCamera() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Cameraswitch, "Switch",
                    tint = if (actionEnabled && !isRecording) Color.White else Color(0xFF555555),
                    modifier = Modifier.size(20.dp))
            }

            RecordDot(isRecording, isCountdown, isExporting, actionEnabled, onRecord)

            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF1A1A1A))
                    .clickable { onGallery() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.PhotoLibrary, "Gallery",
                    tint = Color.White, modifier = Modifier.size(20.dp))
            }

            // Flash at very bottom
            if (showFlash && !isRecording) {
                IconTile(if (flashOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    if (flashOn) "ON" else "OFF", flashOn, rot, onFlashToggle)
            }
        }
    }
}

@Composable
private fun RecordDot(
    isRecording: Boolean,
    isCountdown: Boolean,
    isExporting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val outerColor by animateColorAsState(
        targetValue = when {
            isRecording -> Color(0xFFFF1744)
            isCountdown -> Color(0xFFFFA726)
            isExporting -> Color(0xFF555555)
            else -> Color.White
        },
        label = "rec",
    )

    Box(
        modifier = Modifier.size(60.dp).clip(CircleShape).background(outerColor)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        when {
            isRecording -> Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
            isCountdown -> Text("X", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            isExporting -> Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0xFF888888)))
            else -> Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFFFF1744)))
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
private fun TextTile(text: String, rotation: Float, onClick: () -> Unit, isActive: Boolean = false) {
    Box(
        modifier = Modifier.size(RailTheme.tileSize)
            .clip(RoundedCornerShape(RailTheme.tileRadius))
            .background(RailTheme.tileBg)
            .border(0.5.dp, RailTheme.tileBorder, RoundedCornerShape(RailTheme.tileRadius))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text,
            color = if (isActive) RailTheme.activeColor else RailTheme.iconColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = RailTheme.font,
            textAlign = TextAlign.Center,
            lineHeight = 12.sp,
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
            verticalArrangement = Arrangement.spacedBy(0.dp),
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
