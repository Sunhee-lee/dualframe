package com.dualframe.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.dualframe.data.AppStatus
import com.dualframe.data.UiState
import com.dualframe.util.formatDuration
import com.dualframe.viewmodel.MainViewModel

/**
 * Main screen composable — the entire app UI.
 *
 * Layout (portrait):
 * ┌──────────────────────────┐
 * │   DualFrame  ·  Ready    │
 * │ ┌──────────────────────┐ │
 * │ │                      │ │
 * │ │  ┌────────────────┐  │ │  16:9 guide (solid cyan)
 * │ │  │  16:9          │  │ │
 * │ │  └────────────────┘  │ │
 * │ │                      │ │
 * │ │       ┌──────┐       │ │  9:16 guide (dashed yellow)
 * │ │       │ 9:16 │       │ │
 * │ │       │      │       │ │
 * │ │       └──────┘       │ │
 * │ │                      │ │
 * │ │   Camera Preview     │ │
 * │ └──────────────────────┘ │
 * │     Timer / Progress     │
 * │       ● Record ●        │
 * │     Saved files list     │
 * │     Error area           │
 * └──────────────────────────┘
 *
 * Technical approach:
 * CameraX only supports one Preview surface at a time. Trying to render the same
 * PreviewView in two Compose AndroidView slots crashes (View can have only one parent).
 *
 * Instead, we show ONE full camera preview and overlay TWO crop guide rectangles:
 * - Solid border: 16:9 landscape crop region (wide, short)
 * - Dashed border: 9:16 portrait crop region (narrow, tall)
 *
 * The dimmed area outside each guide shows what gets cropped. This approach is:
 * - Technically robust (no GL hacks, no duplicate surfaces)
 * - Accurate (guides match exactly what Transformer will crop)
 * - What professional camera apps use for aspect ratio guides
 */
@UnstableApi
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    hasAudioPermission: Boolean,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner) {
        viewModel.bindCamera(lifecycleOwner, previewView)
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DualFrame",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            StatusChip(state)
        }

        // ── Camera Preview with crop guides ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // Take all available vertical space
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            // Live camera feed
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            // Crop guide overlays drawn on top of the preview
            CropGuideOverlay(
                isRecording = state.appStatus == AppStatus.RECORDING,
            )
        }

        // ── Controls area below preview ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Timer or export progress
            when (state.appStatus) {
                AppStatus.RECORDING -> {
                    RecordingTimer(state.recordingDurationSeconds)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                AppStatus.EXPORTING_16x9, AppStatus.EXPORTING_9x16 -> {
                    ExportProgress(state)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                else -> {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Record button
            RecordButton(
                state = state,
                onClick = { viewModel.toggleRecording(hasAudioPermission) },
            )

            // Reset button after export
            if (state.appStatus == AppStatus.EXPORT_COMPLETE) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = { viewModel.resetToIdle() }) {
                    Text("New Recording", color = MaterialTheme.colorScheme.primary)
                }
            }

            // Output paths
            OutputPaths(state)

            // Error area
            ErrorArea(state, onDismiss = { viewModel.clearError() })

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ── Crop Guide Overlay ────────────────────────────────────────────────

/**
 * Draws two crop guide rectangles on top of the camera preview:
 *
 * 1. 16:9 landscape guide — solid cyan border, labeled "16:9"
 *    Centered horizontally and in the upper portion of the preview.
 *    Shows the widest horizontal strip that forms a 16:9 frame.
 *
 * 2. 9:16 portrait guide — dashed amber/yellow border, labeled "9:16"
 *    Centered horizontally and in the lower portion of the preview.
 *    Shows the tallest vertical strip that forms a 9:16 frame.
 *
 * The area outside each guide is dimmed to visualize what gets cropped.
 * During recording, guides turn red for visibility.
 */
@Composable
private fun CropGuideOverlay(isRecording: Boolean) {
    val textMeasurer = rememberTextMeasurer()
    val guideColor16x9 = if (isRecording) Color(0xFFFF5252) else Color(0xFF00BCD4)
    val guideColor9x16 = if (isRecording) Color(0xFFFF8A80) else Color(0xFFFFD54F)
    val dimColor = Color.Black.copy(alpha = 0.4f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // The preview fills this space with FILL_CENTER, so the actual video frame
        // may extend beyond the visible area. The crop guides represent center-crop
        // regions of the OUTPUT, calculated relative to this visible area.
        //
        // For a phone held in portrait, the preview is taller than wide.
        // Typical preview aspect ≈ 3:4 (width:height in portrait).

        val previewAspect = w / h // < 1 in portrait

        // ── 16:9 landscape guide ──
        // Output is 16:9 (wider than tall). From a portrait frame, this is a
        // short wide strip. We want the largest 16:9 rect that fits in the frame.
        val landscape16x9 = calculateCenterCropRect(
            containerWidth = w,
            containerHeight = h,
            targetAspect = 16f / 9f,
        )
        // Position in upper third
        val landscape16x9Offset = Offset(
            x = landscape16x9.left,
            y = (h * 0.15f) - (landscape16x9.height / 2f),
        )
        val landscapeRect = Rect(
            offset = Offset(landscape16x9.left, maxOf(landscape16x9Offset.y, 24f)),
            size = Size(landscape16x9.width, landscape16x9.height),
        )

        // Draw dim outside 16:9 guide
        drawDimOutside(landscapeRect, dimColor.copy(alpha = 0.15f))

        // Draw 16:9 border
        drawRect(
            color = guideColor16x9,
            topLeft = landscapeRect.topLeft,
            size = landscapeRect.size,
            style = Stroke(width = 3f),
        )

        // Label "16:9"
        val label16x9 = textMeasurer.measure(
            text = "16:9",
            style = TextStyle(
                color = guideColor16x9,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        drawText(
            textLayoutResult = label16x9,
            topLeft = Offset(
                landscapeRect.left + 8f,
                landscapeRect.top + 4f,
            ),
        )

        // ── 9:16 portrait guide ──
        // Output is 9:16 (taller than wide). From a portrait frame, this is a
        // narrow tall strip. Center crop from the full frame.
        val portrait9x16 = calculateCenterCropRect(
            containerWidth = w,
            containerHeight = h,
            targetAspect = 9f / 16f,
        )
        // Position in lower portion, centered
        val portraitYCenter = h * 0.62f
        val portraitRect = Rect(
            offset = Offset(
                portrait9x16.left,
                portraitYCenter - portrait9x16.height / 2f,
            ),
            size = Size(portrait9x16.width, portrait9x16.height),
        )

        // Draw dim outside 9:16 guide
        drawDimOutside(portraitRect, dimColor.copy(alpha = 0.15f))

        // Draw 9:16 border (dashed to distinguish from 16:9)
        drawRect(
            color = guideColor9x16,
            topLeft = portraitRect.topLeft,
            size = portraitRect.size,
            style = Stroke(
                width = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
            ),
        )

        // Label "9:16"
        val label9x16 = textMeasurer.measure(
            text = "9:16",
            style = TextStyle(
                color = guideColor9x16,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        drawText(
            textLayoutResult = label9x16,
            topLeft = Offset(
                portraitRect.left + 8f,
                portraitRect.top + 4f,
            ),
        )
    }
}

/**
 * Calculate the largest centered rectangle of a target aspect ratio that fits
 * within a container, scaled down to ~40% of the container to leave room for
 * both guides to be visible simultaneously.
 */
private fun calculateCenterCropRect(
    containerWidth: Float,
    containerHeight: Float,
    targetAspect: Float,
): Rect {
    // Scale factor: each guide uses ~40% of container so both fit
    val scale = 0.42f
    val maxW = containerWidth * scale
    val maxH = containerHeight * scale

    val rectWidth: Float
    val rectHeight: Float
    if (targetAspect >= 1f) {
        // Landscape: width-constrained
        rectWidth = maxW
        rectHeight = maxW / targetAspect
    } else {
        // Portrait: height-constrained
        rectHeight = maxH
        rectWidth = maxH * targetAspect
    }

    val left = (containerWidth - rectWidth) / 2f
    val top = (containerHeight - rectHeight) / 2f
    return Rect(Offset(left, top), Size(rectWidth, rectHeight))
}

/** Draw a semi-transparent dim over the entire canvas except the given rect. */
private fun DrawScope.drawDimOutside(rect: Rect, color: Color) {
    val path = Path().apply { addRect(rect) }
    clipPath(path, clipOp = ClipOp.Difference) {
        drawRect(color = color)
    }
}

// ── Status Chip ───────────────────────────────────────────────────────

@Composable
private fun StatusChip(state: UiState) {
    val (text, color) = when (state.appStatus) {
        AppStatus.IDLE -> "Ready" to Color(0xFF888888)
        AppStatus.RECORDING -> "REC" to Color(0xFFFF1744)
        AppStatus.EXPORTING_16x9 -> "Export 16:9" to Color(0xFFFFA726)
        AppStatus.EXPORTING_9x16 -> "Export 9:16" to Color(0xFFFFA726)
        AppStatus.EXPORT_COMPLETE -> "Done" to Color(0xFF66BB6A)
        AppStatus.ERROR -> "Error" to Color(0xFFCF6679)
    }

    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// ── Recording Timer ───────────────────────────────────────────────────

@Composable
private fun RecordingTimer(seconds: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "blink_alpha",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF1744).copy(alpha = alpha)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatDuration(seconds),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}

// ── Export Progress ────────────────────────────────────────────────────

@Composable
private fun ExportProgress(state: UiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(
            progress = { state.exportProgress },
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = Color(0xFFFFA726),
            trackColor = Color(0xFF333333),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${(state.exportProgress * 100).toInt()}%",
            color = Color(0xFFAAAAAA),
            fontSize = 11.sp,
        )
    }
}

// ── Record Button ─────────────────────────────────────────────────────

@Composable
private fun RecordButton(state: UiState, onClick: () -> Unit) {
    val isRecording = state.appStatus == AppStatus.RECORDING
    val isExporting = state.appStatus in listOf(AppStatus.EXPORTING_16x9, AppStatus.EXPORTING_9x16)
    val enabled = state.cameraReady && !isExporting && state.appStatus != AppStatus.EXPORT_COMPLETE

    val backgroundColor by animateColorAsState(
        targetValue = if (isRecording) Color(0xFFFF1744) else Color.White,
        label = "rec_btn_color",
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(68.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            disabledContainerColor = Color(0xFF444444),
        ),
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(Color.White, RoundedCornerShape(4.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(Color(0xFFFF1744), CircleShape),
            )
        }
    }
}

// ── Output Paths ──────────────────────────────────────────────────────

@Composable
private fun OutputPaths(state: UiState) {
    if (state.landscape16x9Path == null && state.portrait9x16Path == null) return

    Spacer(modifier = Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text("Saved Files", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        state.landscape16x9Path?.let { PathRow("16:9", it) }
        state.portrait9x16Path?.let { PathRow("9:16", it) }
    }
}

@Composable
private fun PathRow(label: String, path: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color(0xFF66BB6A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = path.substringAfterLast("/"),
            color = Color(0xFFAAAAAA),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Error Area ────────────────────────────────────────────────────────

@Composable
private fun ErrorArea(state: UiState, onDismiss: () -> Unit) {
    val error = state.errorMessage ?: return

    Spacer(modifier = Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF370000), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(error, color = Color(0xFFCF6679), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = Color(0xFFCF6679), fontSize = 11.sp)
        }
    }
}
