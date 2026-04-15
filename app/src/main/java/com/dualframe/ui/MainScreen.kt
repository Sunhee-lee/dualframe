package com.dualframe.ui

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.dualframe.data.AppStatus
import com.dualframe.data.UiState
import com.dualframe.viewmodel.MainViewModel

/**
 * Main screen — top-level scaffold that composes all sub-sections.
 *
 * Layout (portrait):
 * ┌────────────────────────────┐
 * │  DualFrame    [status] [⚙] │  ← header
 * │       ┌────────────┐       │
 * │       │ 9:16 Live  │       │  ← ImageAnalysis bitmap (top)
 * │       │ Preview    │       │
 * │       └────────────┘       │
 * │ ┌────────────────────────┐ │
 * │ │  16:9 Live Preview     │ │  ← PreviewView native CameraX (bottom)
 * │ └────────────────────────┘ │
 * │   [timer / countdown]      │
 * │       ● Record ●          │
 * │   [export progress]        │
 * │   [Open] [Share] [Folder]  │
 * │   [thumbnail]  [files]     │
 * │   [error]                  │
 * └────────────────────────────┘
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

    // Secondary preview bitmap from ImageAnalysis
    val secondBitmap by viewModel.cameraManager.secondPreviewBitmap.collectAsState()
    val dualAvailable by viewModel.cameraManager.dualPreviewAvailable.collectAsState()

    // PreviewView for primary 16:9 preview
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Bind camera on first composition
    DisposableEffect(lifecycleOwner) {
        viewModel.bindCamera(lifecycleOwner, previewView)
        onDispose { }
    }

    // Settings sheet state
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
        // ── Header ──
        Header(
            state = state,
            onSettingsTap = { showSettings = true },
        )

        // ── Preview panels (9:16 on top, 16:9 on bottom) ──
        PreviewPanels(
            previewView = previewView,
            secondBitmap = secondBitmap,
            dualPreviewAvailable = dualAvailable,
            isRecording = state.appStatus == AppStatus.RECORDING,
            modifier = Modifier.weight(1f),
        )

        // ── Controls area ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Export progress (placeholder — will be replaced by ExportStatusPanel)
            ExportStatusStub(state)

            // Record button + timer + countdown
            RecordControls(
                appStatus = state.appStatus,
                cameraReady = state.cameraReady,
                countdownRemaining = state.countdownRemaining,
                recordingSeconds = state.recordingDurationSeconds,
                onRecordTap = { viewModel.toggleRecording(hasAudioPermission) },
            )

            // Post-export actions (placeholder — will be replaced by ResultActionsRow)
            ResultActionsStub(state, viewModel, context)

            // Error area
            ErrorArea(state, onDismiss = { viewModel.clearError() })

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────

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

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(state.appStatus)
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onSettingsTap) {
                Text("⚙", fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun StatusChip(status: AppStatus) {
    val (text, color) = when (status) {
        AppStatus.IDLE -> "Ready" to Color(0xFF888888)
        AppStatus.COUNTDOWN -> "Countdown" to Color(0xFFFFA726)
        AppStatus.RECORDING -> "REC" to Color(0xFFFF1744)
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

// ── Preview Panels (inline for now — will be extracted to PreviewPanels.kt) ──

@Composable
private fun PreviewPanels(
    previewView: PreviewView,
    secondBitmap: Bitmap?,
    dualPreviewAvailable: Boolean,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
) {

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── 9:16 Portrait Preview (top — ImageAnalysis bitmap or fallback) ──
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("9:16 Portrait", color = Color(0xFFFFD54F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (dualPreviewAvailable && secondBitmap != null) {
                    Image(
                        bitmap = secondBitmap.asImageBitmap(),
                        contentDescription = "9:16 live preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        "9:16",
                        color = Color(0xFF555555),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // ── 16:9 Landscape Preview (bottom — native CameraX PreviewView) ──
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("16:9 Landscape", color = Color(0xFF00BCD4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            }
        }
    }
}

// ── Stubs for features built in next steps ────────────────────────────

/** Export progress — will be replaced by ExportStatusPanel.kt */
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

/** Post-export actions — will be replaced by ResultActionsRow.kt */
@Composable
private fun ResultActionsStub(
    state: UiState,
    viewModel: MainViewModel,
    context: android.content.Context,
) {
    if (state.appStatus != AppStatus.EXPORT_COMPLETE) return

    Spacer(modifier = Modifier.height(8.dp))

    // Thumbnail
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

    // Action buttons
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

    // File names
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

// Intent needs to be imported for the share chooser
private typealias Intent = android.content.Intent
