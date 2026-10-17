package com.dualframe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.dualframe.ui.theme.PretendardFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.sunnlab.dualframe.R
import kotlinx.coroutines.delay

/** How long a long-press tooltip stays up before it withdraws on its own. */
private const val TOOLTIP_DURATION_MS = 2_200L

object RailTheme {
    val tileSize: Dp = 56.dp
    val tileRadius: Dp = 12.dp
    val tileGap: Dp = 5.dp
    val tileBg = Color(0xFF151515).copy(alpha = 0.65f)
    val tileBorder = Color(0xFF252525)
    val iconColor = Color(0xFFCCCCCC)
    val iconSize: Dp = 20.dp
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
    onMoreClick: () -> Unit = {},
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
            audioEnabled, rot, onAudioToggle,
            name = stringResource(R.string.tile_mic_name),
            description = stringResource(R.string.tile_mic_desc))

        IconTile(Icons.Outlined.GridOn,
            if (guidesEnabled) stringResource(R.string.label_on) else stringResource(R.string.label_off),
            guidesEnabled, rot, onGuideToggle,
            name = stringResource(R.string.tile_grid_name),
            description = stringResource(R.string.tile_grid_desc))

        // One icon for both states, like the guides tile: the label and its
        // colour carry the state. "Auto" reads as deferring to the phone, which
        // is what off actually means here.
        IconTile(Icons.Outlined.Smartphone,
            if (keepScreenOn) stringResource(R.string.rail_keep_screen_on)
            else stringResource(R.string.rail_keep_screen_auto),
            keepScreenOn, rot, onKeepScreenToggle,
            name = stringResource(R.string.tile_keep_screen_name),
            description = stringResource(R.string.tile_keep_screen_desc))

        ZoomTile(zoomRatio, rot, onZoomToggle)

        TextTile(resolution, rot, onResolutionCycle, fontSize = 14.sp,
            isActive = resolution.contains("4K"),
            name = stringResource(R.string.tile_resolution_name),
            description = stringResource(R.string.tile_resolution_desc))

        IconTile(Icons.Outlined.Timer,
            if (timerSeconds > 0) "${timerSeconds}s" else stringResource(R.string.label_off),
            timerSeconds > 0, rot, onTimerCycle,
            name = stringResource(R.string.tile_timer_name),
            description = stringResource(R.string.tile_timer_desc))

        if (isFrontCamera) {
            TextTile(
                if (selfieEffect) stringResource(R.string.label_beauty_on) else stringResource(R.string.label_beauty_off),
                rot, onSelfieEffectToggle, isActive = selfieEffect,
                name = stringResource(R.string.tile_beauty_name),
                description = stringResource(R.string.tile_beauty_desc),
            )
        }

        if (showFlash) {
            IconTile(if (flashOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                if (flashOn) stringResource(R.string.label_on) else stringResource(R.string.label_off),
                flashOn, rot, onFlashToggle,
                name = stringResource(R.string.tile_flash_name),
                description = stringResource(R.string.tile_flash_desc))
        }

        TextTile("${stringResource(R.string.settings_more)} >", rot, onMoreClick, fontSize = 14.sp,
            name = stringResource(R.string.tile_settings_name),
            description = stringResource(R.string.tile_settings_desc))
    }
}

/**
 * The frame every rail tile sits in.
 *
 * An icon and a one-word state cannot say what a control does — a reviewer
 * asked what the eye icon was for — so holding a tile names it and explains it.
 * combinedClickable is what keeps the two gestures apart: a long press consumes
 * the pointer, so lifting a finger afterwards does not also fire onClick.
 *
 * The tooltip is a plain Popup rather than Material3's TooltipBox: that one is
 * still experimental, positions above or below the anchor only, and gives no
 * say over how long it stays. Here it goes to the left of the tile, clear of
 * the rail and of the preview behind it.
 */
// combinedClickable is still ExperimentalFoundationApi in Foundation 1.7.x.
// The stable alternative, pointerInput + detectTapGestures, would mean giving
// up clickable's ripple and its button semantics, so the opt-in is the smaller
// change — scoped to this one function.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailTile(
    name: String,
    description: String,
    stateText: String?,
    rotation: Float,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    var tooltipShown by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    // Held sideways there is no room to the left of the rail for a tooltip, so
    // the tile simply behaves as it did before tooltips existed: a null
    // onLongClick leaves combinedClickable acting like a plain clickable, and a
    // long press that ends in a release still fires the tile's action.
    val upright = rotation == 0f

    LaunchedEffect(upright) { if (!upright) tooltipShown = false }

    if (tooltipShown) {
        LaunchedEffect(name) {
            delay(TOOLTIP_DURATION_MS)
            tooltipShown = false
        }
    }

    Box(
        modifier = Modifier.size(RailTheme.tileSize)
            .clip(RoundedCornerShape(RailTheme.tileRadius))
            .background(RailTheme.tileBg)
            .border(0.5.dp, RailTheme.tileBorder, RoundedCornerShape(RailTheme.tileRadius))
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (upright) {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        tooltipShown = true
                    }
                } else {
                    null
                },
            )
            // The visible label is a bare word like "ON"; a screen reader needs
            // to hear which control it belongs to, and what it does.
            .semantics {
                contentDescription = listOfNotNull(name, stateText, description)
                    .joinToString(", ")
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
        // Guarded here as well as in the effect above, so turning the phone
        // cannot leave a tooltip drawn for the frame before the effect runs.
        if (tooltipShown && upright) {
            RailTooltip(name, description, rotation) { tooltipShown = false }
        }
    }
}

/** Places the tooltip beside the tile, kept inside the window on every side. */
private class LeftOfTilePositionProvider(
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left - popupContentSize.width - gapPx)
            .coerceAtLeast(marginPx)
        val centredY = anchorBounds.top + anchorBounds.height / 2 - popupContentSize.height / 2
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        return IntOffset(x, centredY.coerceIn(marginPx, maxY))
    }
}

@Composable
private fun RailTooltip(
    name: String,
    description: String,
    rotation: Float,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }
    val marginPx = with(density) { 12.dp.roundToPx() }

    Popup(
        popupPositionProvider = LeftOfTilePositionProvider(gapPx, marginPx),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                // Capped so a long translation wraps instead of running off the
                // screen, and so it stays within the height it is rotated into.
                .widthIn(max = 200.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF202020).copy(alpha = 0.95f))
                .border(0.5.dp, RailTheme.tileBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .rotate(rotation),
        ) {
            Text(name,
                color = Color.White, fontSize = 13.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = RailTheme.font,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(description,
                color = Color(0xFFBBBBBB), fontSize = 12.sp, lineHeight = 15.sp,
                fontWeight = FontWeight.Normal, fontFamily = RailTheme.font,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ZoomTile(zoomRatio: Float, rotation: Float, onClick: () -> Unit) {
    val displayText = if (zoomRatio < 0.8f) "0.6x" else "1.0x"
    RailTile(
        name = stringResource(R.string.tile_zoom_name),
        description = stringResource(R.string.tile_zoom_desc),
        stateText = displayText,
        rotation = rotation,
        onClick = onClick,
    ) {
        Text(displayText, color = RailTheme.iconColor, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = RailTheme.font,
            modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun TextTile(
    text: String,
    rotation: Float,
    onClick: () -> Unit,
    name: String,
    description: String,
    isActive: Boolean = false,
    fontSize: TextUnit = 12.sp,
) {
    RailTile(
        name = name,
        description = description,
        stateText = text,
        rotation = rotation,
        onClick = onClick,
    ) {
        Text(text,
            color = if (isActive) RailTheme.activeColor else RailTheme.iconColor,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
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
    name: String,
    description: String,
) {
    RailTile(
        name = name,
        description = description,
        stateText = stateText,
        rotation = rotation,
        onClick = onClick,
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
                    fontWeight = FontWeight.Medium, fontFamily = RailTheme.font,
                    // The tile is a fixed 56.dp square; a translation long
                    // enough to wrap would push the column out of it.
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
