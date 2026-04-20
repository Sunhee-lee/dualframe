package com.dualframe.ui

import android.graphics.SurfaceTexture
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    hasAudioPermission: Boolean,
) {
    val state by viewModel.uiState.collectAsState()
    val isFrontCamera by viewModel.cameraManager.useFrontCamera.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderer = viewModel.cameraManager.renderer

    DisposableEffect(lifecycleOwner) {
        renderer.init { viewModel.bindCamera(lifecycleOwner) }
        onDispose { }
    }

    // Detect device physical rotation via sensor (Activity stays portrait-locked).
    // 0 = portrait, 270 = landscape CCW (top-left), 90 = landscape CW (top-right).
    var deviceRotation by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                deviceRotation = when {
                    degrees in 60..120 -> 90
                    degrees in 240..300 -> 270
                    else -> 0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    // Sync device rotation → CameraX targetRotation so saved video matches viewing direction
    LaunchedEffect(deviceRotation) {
        val surfaceRot = when (deviceRotation) {
            270 -> android.view.Surface.ROTATION_90
            90 -> android.view.Surface.ROTATION_270
            else -> android.view.Surface.ROTATION_0
        }
        viewModel.cameraManager.setTargetRotation(surfaceRot)
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
        Header(
            state = state,
            showFlash = !isFrontCamera && state.cameraReady,
            flashOn = state.flashOn,
            onFlashToggle = { viewModel.toggleFlash() },
        )

        PreviewPanels(renderer, viewModel.cameraManager, state.settings.showGuides,
            Modifier.weight(1f), deviceRotation = deviceRotation)

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ExportStatusStub(state)
            Row(Modifier.fillMaxWidth(), Arrangement.Center, Alignment.CenterVertically) {
                if (state.appStatus != AppStatus.RECORDING) {
                    IconButton(
                        onClick = { viewModel.switchCamera() },
                        enabled = state.cameraReady,
                    ) {
                        Icon(Icons.Outlined.Cameraswitch, "Switch camera",
                            tint = if (state.cameraReady) Color.White else Color(0xFF555555),
                            modifier = Modifier.size(22.dp))
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                RecordControls(state.appStatus, state.cameraReady, state.countdownRemaining) {
                    viewModel.toggleRecording(hasAudioPermission)
                }
                if (state.appStatus != AppStatus.RECORDING) {
                    IconButton({ showSettings = true }) {
                        Icon(Icons.Outlined.Settings, "Settings",
                            tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }
            ResultActions(state, viewModel, context)
            ErrorArea(state) { viewModel.clearError() }
        }
    }

    if (state.showRemoveWatermarkDialog) {
        RemoveWatermarkDialog(viewModel, context) { viewModel.dismissRemoveWatermarkDialog() }
    }
}

// ── Header ────────────────────────────────────────────────────────────

@Composable
private fun Header(
    state: UiState,
    showFlash: Boolean, flashOn: Boolean, onFlashToggle: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text("DualFrame", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
        if (state.appStatus == AppStatus.RECORDING) RecIndicator(state.recordingDurationSeconds)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.appStatus != AppStatus.RECORDING) { StatusChip(state.appStatus); Spacer(Modifier.width(4.dp)) }
            // Flash icon — rear camera only, hidden for front camera
            if (showFlash) {
                IconButton(onClick = onFlashToggle) {
                    Icon(
                        imageVector = if (flashOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                        contentDescription = if (flashOn) "Flash on" else "Flash off",
                        tint = if (flashOn) Color(0xFFFFD54F) else Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
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
        AppStatus.SAVING -> "Saving..." to Color(0xFFFFA726)
        AppStatus.ERROR -> "Error" to Color(0xFFCF6679)
    }
    Text(text = text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

// ── Preview Panels (GPU dual render via TextureViews) ─────────────────

@Composable
private fun PreviewPanels(
    renderer: com.dualframe.camera.DualPreviewRenderer,
    cameraManager: com.dualframe.camera.CameraManager,
    showGuides: Boolean,
    modifier: Modifier = Modifier,
    deviceRotation: Int = 0,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val isDeviceLandscape = deviceRotation == 90 || deviceRotation == 270
        val rot = when (deviceRotation) { 270 -> 90f; 90 -> -90f; else -> 0f }

        var pFocusKey by remember { mutableIntStateOf(0) }
        var pFocusPos by remember { mutableStateOf(Offset.Zero) }

        Box(
            Modifier.weight(2f).aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(8.dp)).background(Color.Black)
                .pointerInput(Unit) {
                    detectTapPanZoom(
                        onTap = { pos ->
                            pFocusPos = pos; pFocusKey++
                            cameraManager.focusAt(pos.x / size.width, pos.y / size.height)
                        },
                        onGesture = { _, zoom ->
                            if (zoom != 1f) cameraManager.setZoomRatio(cameraManager.currentZoomRatio * zoom)
                        },
                    )
                },
        ) {
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
            when (deviceRotation) {
                90 -> {  // landscape right
                    GhostWatermark(Alignment.TopEnd, 11, rot)
                    AspectLabel("16:9", Alignment.BottomStart, rot)
                }
                270 -> { // landscape left — mirror anchors
                    GhostWatermark(Alignment.BottomStart, 11, rot)
                    AspectLabel("16:9", Alignment.TopEnd, rot)
                }
                else -> {
                    GhostWatermark(Alignment.TopEnd, 13, 0f)
                    AspectLabel("9:16", Alignment.TopStart, 0f)
                }
            }
            FocusRingOverlay(pFocusKey, pFocusPos)
        }

        var lFocusKey by remember { mutableIntStateOf(0) }
        var lFocusPos by remember { mutableStateOf(Offset.Zero) }
        var landscapeOffset by remember { mutableFloatStateOf(0f) }

        Box(
            Modifier.weight(1f).fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapPanZoom(
                        onTap = { pos ->
                            lFocusPos = pos; lFocusKey++
                            val normX = pos.x / size.width
                            val normY = pos.y / size.height
                            val masterAspect = renderer.masterVisualAspect
                            val panelAspect = size.width.toFloat() / size.height
                            val keepY = (masterAspect / panelAspect).coerceAtMost(1f)
                            val maxShift = 1f - keepY
                            val shift = renderer.landscapeCropOffsetY * maxShift
                            val masterY = (1f - keepY - shift) / 2f + normY * keepY
                            cameraManager.focusAt(normX, masterY.coerceIn(0f, 1f))
                        },
                        onGesture = { pan, zoom ->
                            if (zoom != 1f) cameraManager.setZoomRatio(cameraManager.currentZoomRatio * zoom)
                            if (pan.y != 0f) {
                                val sensitivity = 2f / size.height
                                landscapeOffset = (landscapeOffset - pan.y * sensitivity).coerceIn(-1f, 1f)
                                renderer.landscapeCropOffsetY = landscapeOffset
                            }
                        },
                    )
                },
        ) {
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
            GuideBorder()
            if (showGuides) RuleOfThirdsGrid()
            when (deviceRotation) {
                90 -> {
                    GhostWatermark(Alignment.TopStart, 13, rot)
                    AspectLabel("9:16", Alignment.BottomStart, rot)
                }
                270 -> {
                    GhostWatermark(Alignment.BottomEnd, 13, rot)
                    AspectLabel("9:16", Alignment.TopEnd, rot)
                }
                else -> {
                    GhostWatermark(Alignment.BottomEnd, 11, 0f)
                    AspectLabel("16:9", Alignment.TopStart, 0f)
                }
            }
            CropPositionIndicator(landscapeOffset)
            FocusRingOverlay(lFocusKey, lFocusPos)
        }
    }
}

// ── Custom gesture detector: tap + pan + pinch in one scope ──────────

private suspend fun PointerInputScope.detectTapPanZoom(
    onTap: (Offset) -> Unit,
    onGesture: (pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startPos = down.position
        var pastSlop = false
        var maxPointers = 1

        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size > maxPointers) maxPointers = pressed.size

            if (pressed.size >= 2) {
                pastSlop = true
                val cur = (pressed[0].position - pressed[1].position).getDistance()
                val prev = (pressed[0].previousPosition - pressed[1].previousPosition).getDistance()
                if (prev > 0f && cur > 0f) onGesture(Offset.Zero, cur / prev)
                event.changes.forEach { it.consume() }
            } else if (pressed.size == 1 && maxPointers == 1) {
                val change = pressed[0]
                if (!pastSlop && (change.position - startPos).getDistance() > viewConfiguration.touchSlop) {
                    pastSlop = true
                }
                if (pastSlop) {
                    val pan = change.position - change.previousPosition
                    if (pan != Offset.Zero) {
                        onGesture(pan, 1f)
                        change.consume()
                    }
                }
            }
        } while (event.changes.any { it.pressed })

        if (!pastSlop && maxPointers == 1) onTap(startPos)
    }
}

// ── Focus ring animation ─────────────────────────────────────────────

@Composable
private fun FocusRingOverlay(tapKey: Int, position: Offset) {
    if (tapKey == 0) return
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(tapKey) {
        alpha.snapTo(0.7f)
        scale.snapTo(1.4f)
        launch { scale.animateTo(1f, tween(200)) }
        delay(600)
        alpha.animateTo(0f, tween(300))
    }
    Canvas(Modifier.fillMaxSize()) {
        if (alpha.value > 0f) {
            drawCircle(
                color = Color.White.copy(alpha = alpha.value),
                radius = 22.dp.toPx() * scale.value,
                center = position,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/**
 * Thin semi-transparent border that marks the 16:9 guide region.
 * Drawn relative to the containing Box (i.e., the visible preview rect),
 * not the screen, so the frame always matches the on-screen crop.
 */
@Composable
private fun GuideBorder() {
    Box(
        Modifier.fillMaxSize()
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun GhostWatermark(anchor: Alignment, fontSize: Int, rotation: Float) {
    val padH = if (rotation != 0f) 2.dp else 8.dp
    val padV = if (rotation != 0f) 24.dp else 8.dp
    Box(Modifier.fillMaxSize()) {
        Text(
            text = "DualFrame",
            color = Color.White.copy(alpha = 0.3f),
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(anchor)
                .padding(horizontal = padH, vertical = padV)
                .rotate(rotation),
        )
    }
}

@Composable
private fun AspectLabel(text: String, anchor: Alignment, rotation: Float) {
    val padH = if (rotation != 0f) 3.dp else 6.dp
    val padV = if (rotation != 0f) 8.dp else 6.dp
    Box(Modifier.fillMaxSize()) {
        Text(text = text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(anchor).padding(horizontal = padH, vertical = padV).rotate(rotation)
                .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun CropPositionIndicator(offset: Float) {
    if (offset == 0f) return
    Canvas(Modifier.fillMaxSize()) {
        val barW = 3.dp.toPx()
        val barX = size.width - barW - 4.dp.toPx()
        val pad = 4.dp.toPx()
        val trackH = size.height - pad * 2
        val thumbH = trackH * 0.3f
        val travel = trackH - thumbH
        val thumbTop = pad + travel * (1f - offset) / 2f
        drawRoundRect(
            color = Color.White.copy(alpha = 0.15f),
            topLeft = Offset(barX, pad),
            size = Size(barW, trackH),
            cornerRadius = CornerRadius(barW / 2),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.5f),
            topLeft = Offset(barX, thumbTop),
            size = Size(barW, thumbH),
            cornerRadius = CornerRadius(barW / 2),
        )
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
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { state.exportProgress },
            modifier = Modifier.fillMaxWidth(0.6f).height(5.dp).clip(RoundedCornerShape(3.dp)),
            color = Color(0xFFFFA726), trackColor = Color(0xFF333333))
    }
}

@Composable
private fun ResultActions(state: UiState, viewModel: MainViewModel, context: android.content.Context) {
    if (state.appStatus != AppStatus.EXPORT_COMPLETE && state.appStatus != AppStatus.SAVING) return
    Spacer(Modifier.height(8.dp))

    // Thumbnail LEFT (with status below it), buttons RIGHT
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Thumbnail + save status stacked
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            state.thumbnailBitmap?.let { bmp ->
                Box(
                    Modifier.width(72.dp).aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(6.dp)).background(Color.Black),
                ) {
                    Image(bmp.asImageBitmap(), "Result", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            // Save progress bar
            if (state.appStatus == AppStatus.SAVING) {
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.width(72.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF66BB6A), trackColor = Color(0xFF333333),
                )
            }
            // "Saved" text under thumbnail
            state.saveMessage?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = Color(0xFF66BB6A), fontSize = 11.sp)
            }
        }

        Spacer(Modifier.width(12.dp))

        // Action buttons stacked vertically on the right
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ResultButton("Save Both", state.appStatus != AppStatus.SAVING) {
                viewModel.saveBothWithWatermark()
            }
            if (!com.dualframe.monetize.ProEntitlement.isProOwned(context)) {
                ResultButton("Remove Watermark", state.appStatus != AppStatus.SAVING) {
                    viewModel.showRemoveWatermarkDialog()
                }
            }
            ResultButton("View Saved", true) {
                try { context.startActivity(viewModel.buildOpenGalleryIntent()) } catch (_: Exception) {}
            }
            ResultButton("Retake", true) {
                viewModel.resetToIdle()
            }
        }
    }
}

@Composable
private fun ResultButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(38.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(label, fontSize = 16.sp, maxLines = 1)
    }
}

/** Remove Watermark dialog with two options: Watch Ad or Go PRO. */
@Composable
private fun RemoveWatermarkDialog(
    viewModel: MainViewModel,
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Remove Watermark", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        onDismiss()
                        // Show rewarded ad — on reward, save clean
                        val activity = context as? android.app.Activity
                        if (activity != null) {
                            com.dualframe.monetize.AdRewardManager.showAd(
                                activity = activity,
                                onRewarded = { viewModel.saveBothClean() },
                                onFailed = { msg ->
                                    viewModel.clearError()
                                    // Show error briefly via saveMessage
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Watch Ad", fontSize = 16.sp)
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        onDismiss()
                        val activity = context as? android.app.Activity
                        if (activity != null) {
                            com.dualframe.monetize.BillingManager.getInstance(context)
                                .launchPurchase(activity) { success ->
                                    if (success) viewModel.saveBothClean()
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Go Pro", fontSize = 16.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onDismiss) {
                Text("Cancel", color = Color(0xFF999999))
            }
        },
    )
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
