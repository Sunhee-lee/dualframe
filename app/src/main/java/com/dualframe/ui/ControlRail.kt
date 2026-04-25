package com.dualframe.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.dualframe.ui.theme.PretendardFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnlab.dualframe.R

object RailTheme {
    val tileSize: Dp = 56.dp
    val tileRadius: Dp = 12.dp
    val tileGap: Dp = 5.dp
    val tileBg = Color(0xFF151515).copy(alpha = 0.85f)
    val tileBorder = Color(0xFF252525)
    val iconColor = Color(0xFFCCCCCC)
    val iconSize: Dp = 22.dp
    val activeColor = Color(0xFF4CAF50)
    val inactiveColor = Color(0xFF666666)
    val font: FontFamily = PretendardFont
}

@Composable
fun SettingsPanel(
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

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RailTheme.tileGap),
    ) {
        IconTile(if (audioEnabled) Icons.Outlined.Mic else Icons.Outlined.MicOff,
            if (audioEnabled) stringResource(R.string.label_on) else stringResource(R.string.label_off),
            audioEnabled, rot, onAudioToggle)

        IconTile(Icons.Outlined.GridOn,
            if (guidesEnabled) stringResource(R.string.label_on) else stringResource(R.string.label_off),
            guidesEnabled, rot, onGuideToggle)

        IconTile(if (keepScreenOn) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            if (keepScreenOn) stringResource(R.string.label_on) else stringResource(R.string.label_off),
            keepScreenOn, rot, onKeepScreenToggle)

        ZoomTile(zoomRatio, rot, onZoomToggle)

        TextTile(resolution, rot, onResolutionCycle, fontSize = 14.sp)

        IconTile(Icons.Outlined.Timer,
            if (timerSeconds > 0) "${timerSeconds}s" else stringResource(R.string.label_off),
            timerSeconds > 0, rot, onTimerCycle)

        if (isFrontCamera) {
            TextTile(
                if (selfieEffect) stringResource(R.string.label_beauty_on) else stringResource(R.string.label_beauty_off),
                rot, onSelfieEffectToggle, isActive = selfieEffect,
            )
        }

        if (showFlash) {
            IconTile(if (flashOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                if (flashOn) stringResource(R.string.label_on) else stringResource(R.string.label_off),
                flashOn, rot, onFlashToggle)
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
        Text(displayText, color = RailTheme.activeColor, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, fontFamily = RailTheme.font,
            modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun TextTile(
    text: String,
    rotation: Float,
    onClick: () -> Unit,
    isActive: Boolean = false,
    fontSize: TextUnit = 12.sp,
) {
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
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            fontFamily = RailTheme.font,
            textAlign = TextAlign.Center,
            lineHeight = (fontSize.value + 2f).sp,
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
                    fontSize = 11.sp, lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold, fontFamily = RailTheme.font)
            }
        }
    }
}
