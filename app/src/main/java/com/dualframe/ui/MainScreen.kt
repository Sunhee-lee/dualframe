package com.dualframe.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
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

@UnstableApi
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    hasAudioPermission: Boolean,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderer = viewModel.cameraManager.renderer

    DisposableEffect(lifecycleOwner) {
        renderer.init { viewModel.bindCamera(lifecycleOwner) }
        onDispose { }
    }

    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            supportedQualities = state.supportedQualities,
            onSettingsChange = { viewModel.updateSettings(it) },
            onDismiss = { showSettings = false },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(state, state.cameraReady && state.appStatus != AppStatus.RECORDING, { viewModel.switchCamera() })

        PreviewPanels(renderer, state.settings.showGuides, Modifier.weight(1f))

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ExportStatusStub(state)
            Row(Modifier.fillMaxWidth(), Arrangement.Center, Alignment.CenterVertically) {
                Spacer(Modifier.width(48.dp))
                RecordControls(state.appStatus, state.cameraReady, state.countdownRemaining) {
                    viewModel.toggleRecording(hasAudioPermission)
                }
                IconButton({ showSettings = true }, Modifier.padding(start = 8.dp)) {
                    Text("⚙", fontSize = 22.sp, color = Color(0xFFAAAAAA))
                }
            }
            ResultActions(state, viewModel, context)
            ErrorArea(state) { viewModel.clearError() }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────

@Composable
private fun Header(state: UiState, canSwitch: Boolean, onSwitch: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text("DualFrame", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
        if (state.appStatus == AppStatus.RECORDING) RecIndicator(state.recordingDurationSeconds)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.appStatus != AppStatus.RECORDING) { StatusChip(state.appStatus); Spacer(Modifier.width(4.dp)) }
            IconButton(onSwitch, enabled = canSwitch) {
                Text("🔄", fontSize = 18.sp, color = if (canSwitch) Color.White else Color(0xFF555555))
            }
        }
    }
}

@Composable
private fun RecIndicator(seconds: Int) {
    Row(Modifier.background(Color(0x44FF1744), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF1744)))
        Spacer(Modifier.width(6.dp))
        Text(text = formatDuration(seconds), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StatusChip(status: AppStatus) {
    val (text, color) = when (status) {
        AppStatus.IDLE -> "Ready" to Color(0xFF888888)
        AppStatus.COUNTDOWN -> "Countdown" to Color(0xFFFFA726)
        AppStatus.RECORDING -> "REC" to Color(0xFFFF1744)
        AppStatus.EXPORTING_NATIVE -> "Exporting..." to Color(0xFFFFA726)
        AppStatus.EXPORTING_CROPPED -> "Exporting..." to Color(0xFFFFA726)
        AppStatus.EXPORT_COMPLETE -> "Done" to Color(0xFF66BB6A)
        AppStatus.ERROR -> "Error" to Color(0xFFCF6679)
    }
    Text(text = text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

// ── Preview Panels (GPU dual render via TextureViews) ─────────────────

@Composable
private fun PreviewPanels(
    renderer: com.dualframe.camera.DualPreviewRenderer,
    showGuides: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(2f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { renderer.setOutput9x16(Surface(st)) }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { renderer.setOutput9x16(Surface(st)) }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (showGuides) RuleOfThirdsGrid()
            AspectLabel("9:16")
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { renderer.setOutput16x9(Surface(st)) }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { renderer.setOutput16x9(Surface(st)) }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (showGuides) RuleOfThirdsGrid()
            AspectLabel("16:9")
        }
    }
}

@Composable
private fun AspectLabel(text: String) {
    Box(Modifier.fillMaxSize()) {
        Text(text = text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                .background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun RuleOfThirdsGrid() {
    val c = Color.White.copy(alpha = 0.3f)
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawLine(c, Offset(w/3f,0f), Offset(w/3f,h), 1f)
        drawLine(c, Offset(2f*w/3f,0f), Offset(2f*w/3f,h), 1f)
        drawLine(c, Offset(0f,h/3f), Offset(w,h/3f), 1f)
        drawLine(c, Offset(0f,2f*h/3f), Offset(w,2f*h/3f), 1f)
        val cc = Color.White.copy(alpha = 0.4f)
        drawLine(cc, Offset(w/2f-12f,h/2f), Offset(w/2f+12f,h/2f), 1f)
        drawLine(cc, Offset(w/2f,h/2f-12f), Offset(w/2f,h/2f+12f), 1f)
    }
}

// ── Export / Result / Error ────────────────────────────────────────────

@Composable
private fun ExportStatusStub(state: UiState) {
    val exporting = state.appStatus == AppStatus.EXPORTING_NATIVE || state.appStatus == AppStatus.EXPORTING_CROPPED
    if (!exporting) return
    val label = if (state.appStatus == AppStatus.EXPORTING_NATIVE) "Exporting native..." else "Exporting cropped..."
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFFFFA726), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { state.exportProgress },
            modifier = Modifier.fillMaxWidth(0.6f).height(5.dp).clip(RoundedCornerShape(3.dp)),
            color = Color(0xFFFFA726), trackColor = Color(0xFF333333))
    }
}

@Composable
private fun ResultActions(state: UiState, viewModel: MainViewModel, context: android.content.Context) {
    if (state.appStatus != AppStatus.EXPORT_COMPLETE) return
    Spacer(Modifier.height(8.dp))
    state.thumbnailBitmap?.let { bmp ->
        Box(Modifier.width(80.dp).aspectRatio(9f/16f).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
            Image(bmp.asImageBitmap(), "Latest export", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(4.dp))
    }
    state.nativeExportInfo?.let { Text(it, color = Color(0xFFAAAAAA), fontSize = 10.sp) }
    state.croppedExportInfo?.let { Text(it, color = Color(0xFFAAAAAA), fontSize = 10.sp) }
    Spacer(Modifier.height(4.dp))
    androidx.compose.material3.TextButton({ viewModel.resetToIdle() }, Modifier.padding(bottom = 2.dp)) {
        Text("✕  Close", color = Color(0xFF999999), fontSize = 12.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionBtn("Open") { viewModel.buildOpenIntent()?.let { context.startActivity(it) } }
        ActionBtn("Share") { viewModel.buildShareIntent()?.let { context.startActivity(android.content.Intent.createChooser(it, "Share")) } }
    }
}

@Composable
private fun ActionBtn(label: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick, Modifier.height(34.dp)) { Text(label, fontSize = 12.sp) }
}

@Composable
private fun ErrorArea(state: UiState, onDismiss: () -> Unit) {
    val error = state.errorMessage ?: return
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth().background(Color(0xFF370000), RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text(error, color = Color(0xFFCF6679), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.TextButton(onDismiss) { Text("Dismiss", color = Color(0xFFCF6679), fontSize = 11.sp) }
    }
}
