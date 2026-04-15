package com.dualframe.ui

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
 * Main screen — top-level scaffold.
 *
 * Layout (portrait):
 * ┌─────────────────────────────┐
 * │ DualFrame  ● REC 01:23  [⚙]│  ← header with rec indicator
 * │      ┌─────────────┐       │
 * │      │ [9:16]      │       │  ← pill label, rule-of-thirds grid
 * │      │             │       │
 * │      └─────────────┘       │
 * │ ┌─────────────────────────┐│
 * │ │ [16:9]                  ││  ← pill label, rule-of-thirds grid
 * │ └─────────────────────────┘│
 * │        ● Record ●          │
 * │   [export progress]        │
 * │   [Open] [Share] [Folder]  │
 * └─────────────────────────────┘
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

    val secondBitmap by viewModel.cameraManager.secondPreviewBitmap.collectAsState()
    val dualAvailable by viewModel.cameraManager.dualPreviewAvailable.collectAsState()

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

    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            onSettingsChange = { viewModel.updateSettings(it) },
            onDismiss = { showSettings = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Header with recording indicator ──
        Header(
            state = state,
            onSettingsTap = { showSettings = true },
        )

        // ── Preview panels ──
        PreviewPanels(
            previewView = previewView,
            secondBitmap = secondBitmap,
            dualPreviewAvailable = dualAvailable,
            showGuides = state.settings.showGuides,
            modifier = Modifier.weight(1f),
        )

        // ── Controls area ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ExportStatusStub(state)

            RecordControls(
                appStatus = state.appStatus,
                cameraReady = state.cameraReady,
                countdownRemaining = state.countdownRemaining,
                onRecordTap = { viewModel.toggleRecording(hasAudioPermission) },
            )

            ResultActionsStub(state, viewModel, context)
            ErrorArea(state, onDismiss = { viewModel.clearError() })

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────

/**
 * Header bar: app title on left, recording indicator in center (when recording),
 * status chip + settings on right.
 */
@Composable
private fun Header(state: UiState, onSettingsTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "DualFrame",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )

        // Recording indicator — single REC dot + timer in the header
        if (state.appStatus == AppStatus.RECORDING) {
            RecIndicator(state.recordingDurationSeconds)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.appStatus != AppStatus.RECORDING) {
                StatusChip(state.appStatus)
                Spacer(modifier = Modifier.width(8.dp))
            }
            IconButton(onClick = onSettingsTap) {
                Text("⚙", fontSize = 20.sp)
            }
        }
    }
}

/**
 * Professional-style REC indicator: red dot + timer.
 * Placed once in the header — not duplicated on panels.
 */
@Composable
private fun RecIndicator(seconds: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0x44FF1744), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF1744)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = formatDuration(seconds),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StatusChip(status: AppStatus) {
    val (text, color) = when (status) {
        AppStatus.IDLE -> "Ready" to Color(0xFF888888)
        AppStatus.COUNTDOWN -> "Countdown" to Color(0xFFFFA726)
        AppStatus.RECORDING -> "REC" to Color(0xFFFF1744) // shown via RecIndicator instead
        AppStatus.EXPORTING_16x9 -> "Export 16:9" to Color(0xFFFFA726)
        AppStatus.EXPORTING_9x16 -> "Export 9:16" to Color(0xFFFFA726)
        AppStatus.EXPORT_COMPLETE -> "Done" to Color(0xFF66BB6A)
        AppStatus.ERROR -> "Error" to Color(0xFFCF6679)
    }
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

// ── Preview Panels ────────────────────────────────────────────────────

/**
 * Two separate live preview regions from one rear camera source.
 *
 * Each panel has:
 * - White-on-dark pill label at top-left ("9:16" / "16:9")
 * - Optional rule-of-thirds composition grid (controlled by showGuides)
 * - No colored borders, no recording-state color changes
 */
@Composable
private fun PreviewPanels(
    previewView: PreviewView,
    secondBitmap: Bitmap?,
    dualPreviewAvailable: Boolean,
    showGuides: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── 9:16 Portrait Preview (top) ──
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
        ) {
            if (dualPreviewAvailable && secondBitmap != null) {
                Image(
                    bitmap = secondBitmap.asImageBitmap(),
                    contentDescription = "9:16 live preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("9:16", color = Color(0xFF555555), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (showGuides) RuleOfThirdsGrid()
            AspectLabel("9:16")
        }

        // ── 16:9 Landscape Preview (bottom) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
            if (showGuides) RuleOfThirdsGrid()
            AspectLabel("16:9")
        }
    }
}

/**
 * White pill label pinned to top-left of the preview panel.
 * Semi-transparent dark background for readability on any video content.
 */
@Composable
private fun AspectLabel(text: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * Rule-of-thirds composition guide grid.
 * Two vertical + two horizontal lines dividing the frame into 9 cells.
 * Semi-transparent white, subtle enough not to distract.
 */
@Composable
private fun RuleOfThirdsGrid() {
    val lineColor = Color.White.copy(alpha = 0.3f)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val strokeWidth = 1f

        // Two vertical lines at 1/3 and 2/3
        drawLine(lineColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth)
        drawLine(lineColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth)

        // Two horizontal lines at 1/3 and 2/3
        drawLine(lineColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth)
        drawLine(lineColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth)

        // Center crosshair — very subtle
        val crossLen = 12f
        val cx = w / 2f
        val cy = h / 2f
        val crossColor = Color.White.copy(alpha = 0.4f)
        drawLine(crossColor, Offset(cx - crossLen, cy), Offset(cx + crossLen, cy), strokeWidth)
        drawLine(crossColor, Offset(cx, cy - crossLen), Offset(cx, cy + crossLen), strokeWidth)
    }
}

// ── Stubs for features built in next steps ────────────────────────────

@Composable
private fun ExportStatusStub(state: UiState) {
    val isExporting = state.appStatus == AppStatus.EXPORTING_16x9 ||
        state.appStatus == AppStatus.EXPORTING_9x16
    if (!isExporting) return

    val label = if (state.appStatus == AppStatus.EXPORTING_16x9) "Exporting 16:9..." else "Exporting 9:16..."
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color(0xFFFFA726), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { state.exportProgress },
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = Color(0xFFFFA726),
            trackColor = Color(0xFF333333),
        )
    }
}

@Composable
private fun ResultActionsStub(
    state: UiState,
    viewModel: MainViewModel,
    context: android.content.Context,
) {
    if (state.appStatus != AppStatus.EXPORT_COMPLETE) return

    Spacer(modifier = Modifier.height(8.dp))

    state.thumbnailBitmap?.let { bmp ->
        Box(
            modifier = Modifier
                .width(80.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black),
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Latest export",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionTextButton("Open") {
            viewModel.buildOpenIntent()?.let { context.startActivity(it) }
        }
        ActionTextButton("Share") {
            viewModel.buildShareIntent()?.let {
                context.startActivity(Intent.createChooser(it, "Share video"))
            }
        }
        ActionTextButton("Folder") {
            try { context.startActivity(viewModel.buildShowFolderIntent()) } catch (_: Exception) { }
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    state.landscape16x9Name?.let {
        Text("16:9: $it", color = Color(0xFF888888), fontSize = 10.sp)
    }
    state.portrait9x16Name?.let {
        Text("9:16: $it", color = Color(0xFF888888), fontSize = 10.sp)
    }

    Spacer(modifier = Modifier.height(6.dp))

    androidx.compose.material3.TextButton(onClick = { viewModel.resetToIdle() }) {
        Text("New Recording", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ActionTextButton(label: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
    ) {
        Text(label, fontSize = 12.sp)
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
        androidx.compose.material3.TextButton(onClick = onDismiss) {
            Text("Dismiss", color = Color(0xFFCF6679), fontSize = 11.sp)
        }
    }
}

private typealias Intent = android.content.Intent
