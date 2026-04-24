package com.dualframe.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.scale
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

    // Keep screen on based on setting
    val keepScreenOn = state.settings.keepScreenAwake
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
    }

    // Back button confirmation dialog
    var showExitDialog by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    BackHandler { showExitDialog = true }
    if (showExitDialog) {
        val dialogRot = when (deviceRotation) { 270 -> 90f; 90 -> -90f; else -> 0f }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showExitDialog = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.rotate(dialogRot)
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
                    .padding(24.dp)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("종료", color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                Spacer(Modifier.height(12.dp))
                Text("앱을 종료하시겠습니까?", color = Color(0xFFCCCCCC),
                    fontSize = 15.sp, fontFamily = FontFamily.SansSerif)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    androidx.compose.material3.TextButton(onClick = { showExitDialog = false }) {
                        Text("취소", color = Color(0xFF999999), fontFamily = FontFamily.SansSerif)
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        showExitDialog = false
                        (context as? Activity)?.finish()
                    }) {
                        Text("종료", color = Color(0xFFFF5252), fontFamily = FontFamily.SansSerif)
                    }
                }
            }
        }
    }

    val zoomRatio by viewModel.cameraManager.zoomRatio.collectAsState()
    val isRecording = state.appStatus == AppStatus.RECORDING

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
        ) {
            // ── Top header: DualFrame + Ready/REC + Settings gear ──
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("DualFrame", color = Color.White, fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                Spacer(Modifier.width(10.dp))
                if (isRecording) {
                    Row(
                        Modifier.background(Color(0x44FF1744), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF1744)))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = formatDuration(state.recordingDurationSeconds),
                            color = Color.White, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        )
                    }
                } else {
                    StatusChip(state.appStatus)
                }
                Spacer(Modifier.weight(1f))
                // Gear icon — always occupies space, invisible during recording
                Box(
                    Modifier.size(44.dp)
                        .then(if (!isRecording) Modifier
                            .clickable { settingsExpanded = !settingsExpanded }
                        else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!isRecording) {
                        Icon(Icons.Outlined.Settings, null,
                            tint = if (settingsExpanded) RailTheme.activeColor else RailTheme.iconColor,
                            modifier = Modifier.size(22.dp))
                    }
                }
            }

            // ── Main content: previews centered ──
            BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val fitWidth = ((maxHeight.value - 8f) / (16f / 9f + 9f / 16f))
                .coerceAtLeast(0f).dp.coerceAtMost(maxWidth)

            PreviewPanels(
                renderer, viewModel.cameraManager, state.settings.showGuides,
                Modifier.width(fitWidth),
                deviceRotation = deviceRotation, appStatus = state.appStatus,
            )
        }

        ExportStatusStub(state)

        BottomActionBar(
            appStatus = state.appStatus,
            cameraReady = state.cameraReady,
            onGallery = {
                try { context.startActivity(buildGalleryIntent(context)) }
                catch (_: Exception) {}
            },
            onRecord = { viewModel.toggleRecording(hasAudioPermission) },
            onSwitchCamera = { viewModel.switchCamera() },
        )

        ErrorArea(state) { viewModel.clearError() }
        }

        // Settings overlay — slides down from top-right, no container background
        AnimatedVisibility(
            visible = settingsExpanded && !isRecording,
            modifier = Modifier.align(Alignment.TopEnd)
                .padding(top = 56.dp, end = 8.dp),
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        ) {
            SettingsPanel(
                audioEnabled = state.settings.audioEnabled,
                zoomRatio = zoomRatio,
                guidesEnabled = state.settings.showGuides,
                timerSeconds = state.settings.countdownSeconds,
                flashOn = state.flashOn,
                showFlash = !isFrontCamera && state.cameraReady,
                keepScreenOn = keepScreenOn,
                selfieEffect = state.settings.frontCameraEffect,
                isFrontCamera = isFrontCamera,
                resolution = state.settings.videoQuality.label.substringBefore(" (").replace(" ", "\n"),
                deviceRotation = deviceRotation,
                onAudioToggle = {
                    viewModel.updateSettings(state.settings.copy(audioEnabled = !state.settings.audioEnabled))
                },
                onZoomToggle = {
                    val target = if (zoomRatio < 0.8f) 1f else 0.6f
                    viewModel.cameraManager.setZoomRatio(target)
                },
                onGuideToggle = {
                    viewModel.updateSettings(state.settings.copy(showGuides = !state.settings.showGuides))
                },
                onTimerCycle = {
                    val next = when (state.settings.countdownSeconds) {
                        0 -> 3; 3 -> 5; 5 -> 10; else -> 0
                    }
                    viewModel.updateSettings(state.settings.copy(countdownSeconds = next))
                },
                onFlashToggle = { viewModel.toggleFlash() },
                onKeepScreenToggle = {
                    viewModel.updateSettings(state.settings.copy(keepScreenAwake = !keepScreenOn))
                },
                onSelfieEffectToggle = {
                    viewModel.updateSettings(state.settings.copy(frontCameraEffect = !state.settings.frontCameraEffect))
                },
                onResolutionCycle = {
                    val qualities = com.dualframe.data.VideoQuality.entries
                    val idx = qualities.indexOf(state.settings.videoQuality)
                    val next = qualities[(idx + 1) % qualities.size]
                    viewModel.updateSettings(state.settings.copy(videoQuality = next))
                },
            )
        }
    }

    // Fullscreen result overlay
    if (state.appStatus == AppStatus.EXPORT_COMPLETE || state.appStatus == AppStatus.SAVING) {
        ResultActions(state, viewModel, context, deviceRotation)
    }

    if (state.showRemoveWatermarkDialog) {
        RemoveWatermarkDialog(viewModel, context) { viewModel.dismissRemoveWatermarkDialog() }
    }
}

// ── Status Chip ──────────────────────────────────────────────────────

@Composable
private fun StatusChip(status: AppStatus) {
    val (text, color) = when (status) {
        AppStatus.IDLE -> "Ready" to Color(0xFF4CAF50)
        AppStatus.COUNTDOWN -> "Countdown" to Color(0xFFFFA726)
        AppStatus.RECORDING -> "REC" to Color(0xFFFF1744)
        AppStatus.EXPORTING_NATIVE -> "Exporting" to Color(0xFFFFA726)
        AppStatus.EXPORTING_CROPPED -> "Exporting" to Color(0xFFFFA726)
        AppStatus.EXPORT_COMPLETE -> "Done" to Color(0xFF66BB6A)
        AppStatus.SAVING -> "Saving" to Color(0xFFFFA726)
        AppStatus.ERROR -> "Error" to Color(0xFFCF6679)
    }
    Text(text = text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.SansSerif,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp))
}

// ── Preview Panels (GPU dual render via TextureViews) ─────────────────

@Composable
private fun PreviewPanels(
    renderer: com.dualframe.camera.DualPreviewRenderer,
    cameraManager: com.dualframe.camera.CameraManager,
    showGuides: Boolean,
    modifier: Modifier = Modifier,
    deviceRotation: Int = 0,
    appStatus: AppStatus = AppStatus.IDLE,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val isDeviceLandscape = deviceRotation == 90 || deviceRotation == 270
        val rot = when (deviceRotation) { 270 -> 90f; 90 -> -90f; else -> 0f }

        var pFocusKey by remember { mutableIntStateOf(0) }
        var pFocusPos by remember { mutableStateOf(Offset.Zero) }

        Box(
            Modifier.fillMaxWidth().aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(12.dp)).background(Color.Black)
                .pointerInput(Unit) {
                    detectTapPanZoom(
                        onTap = { pos ->
                            pFocusPos = pos; pFocusKey++
                            cameraManager.focusAt(pos.x / size.width, pos.y / size.height)
                        },
                        onGesture = { _, zoom ->
                            if (!cameraManager.isRecording && zoom != 1f)
                                cameraManager.setZoomRatio(cameraManager.currentZoomRatio * zoom)
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
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
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
                            if (cameraManager.isRecording) return@detectTapPanZoom
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
    val padH = if (rotation != 0f) 0.dp else 8.dp
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
    val padV = if (rotation != 0f) 9.dp else 6.dp
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
private fun ResultActions(state: UiState, viewModel: MainViewModel, context: android.content.Context, deviceRotation: Int = 0) {
    if (state.appStatus != AppStatus.EXPORT_COMPLETE && state.appStatus != AppStatus.SAVING) return

    val isPro = com.dualframe.monetize.ProEntitlement.isProOwned(context)
    val isLandscapeRecording = !state.masterIsPortrait
    val mirrorMod = if (state.wasFrontCamera) Modifier.scale(-1f, 1f) else Modifier

    val portraitBmp = if (state.masterIsPortrait) state.thumbnailBitmap else state.landscapeThumbnailBitmap
    val landscapeBmp = if (state.masterIsPortrait) state.landscapeThumbnailBitmap else state.thumbnailBitmap

    if (isLandscapeRecording) {
        val activity = context as? Activity
        DisposableEffect(Unit) {
            val window = activity?.window
            window?.let {
                val lp = it.attributes
                lp.rotationAnimation = 2
                it.attributes = lp
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            onDispose {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                window?.let {
                    val lp = it.attributes
                    lp.rotationAnimation = 0
                    it.attributes = lp
                }
            }
        }
    }

    val saveLabel = when {
        state.appStatus == AppStatus.SAVING -> "Saving..."
        state.saveMessage != null -> "Saved"
        else -> "Save Videos"
    }
    val saveEnabled = state.appStatus == AppStatus.EXPORT_COMPLETE && state.saveMessage == null

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center,
    ) {
        if (isLandscapeRecording) {
            // ── Landscape: right turn 180°, left turn 0° ──
            val landscapeRot = if (deviceRotation == 90) 180f else 0f
            Row(
                modifier = Modifier.fillMaxSize()
                    .rotate(landscapeRot)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Thumbnails: same height, order swapped based on rotation
                Row(
                    modifier = Modifier.weight(0.55f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (deviceRotation == 270) {
                        // Left turn: landscape first, portrait second
                        LandscapeThumb(landscapeBmp, mirrorMod, isPro)
                        PortraitThumb(portraitBmp, mirrorMod, isPro)
                    } else {
                        // Right turn (180° flipped): portrait first, landscape second
                        PortraitThumb(portraitBmp, mirrorMod, isPro)
                        LandscapeThumb(landscapeBmp, mirrorMod, isPro)
                    }
                }

                // Right: buttons centered vertically
                Column(
                    modifier = Modifier.weight(0.45f).padding(start = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    PrimaryResultButton(saveLabel, Modifier.fillMaxWidth(0.9f), saveEnabled, isSaved = state.saveMessage != null) {
                        viewModel.saveBothWithWatermark()
                    }
                    if (!isPro) {
                        RemoveWatermarkResultButton(Modifier.fillMaxWidth(0.9f), state.appStatus != AppStatus.SAVING) {
                            viewModel.showRemoveWatermarkDialog()
                        }
                    }
                    ResultButton("View in Gallery", Modifier.fillMaxWidth(0.9f), true) {
                        try { context.startActivity(buildGalleryIntent(context)) }
                        catch (_: Exception) {}
                    }
                    ResultButton("Retake", Modifier.fillMaxWidth(0.9f), true) {
                        viewModel.resetToIdle()
                    }
                }
            }
        } else {
            // ── Portrait: centered vertical layout ──
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                portraitBmp?.let { bmp ->
                    Box(
                        Modifier.fillMaxWidth(0.50f).aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(12.dp)).background(Color.Black),
                    ) {
                        Image(bmp.asImageBitmap(), "Portrait", Modifier.fillMaxSize().then(mirrorMod), contentScale = ContentScale.Crop)
                        if (!isPro) ThumbnailWatermark(Alignment.TopEnd)
                    }
                }

                Spacer(Modifier.height(6.dp))

                landscapeBmp?.let { bmp ->
                    Box(
                        Modifier.fillMaxWidth(0.50f).aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp)).background(Color.Black),
                    ) {
                        Image(bmp.asImageBitmap(), "Landscape", Modifier.fillMaxSize().then(mirrorMod), contentScale = ContentScale.Crop)
                        if (!isPro) ThumbnailWatermark(Alignment.BottomEnd)
                    }
                }

                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(0.75f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryResultButton(saveLabel, Modifier.fillMaxWidth(), saveEnabled, isSaved = state.saveMessage != null) {
                        viewModel.saveBothWithWatermark()
                    }
                    if (!isPro) {
                        RemoveWatermarkResultButton(Modifier.fillMaxWidth(), state.appStatus != AppStatus.SAVING) {
                            viewModel.showRemoveWatermarkDialog()
                        }
                    }
                    ResultButton("View in Gallery", Modifier.fillMaxWidth(), true) {
                        try { context.startActivity(buildGalleryIntent(context)) }
                        catch (_: Exception) {}
                    }
                    ResultButton("Retake", Modifier.fillMaxWidth(), true) {
                        viewModel.resetToIdle()
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailWatermark(anchor: Alignment) {
    Box(Modifier.fillMaxSize()) {
        Text(
            "DualFrame",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            modifier = Modifier.align(anchor)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PortraitThumb(bmp: android.graphics.Bitmap?, mirrorMod: Modifier, isPro: Boolean) {
    bmp?.let {
        Box(
            Modifier.fillMaxHeight(0.65f).aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(10.dp)).background(Color.Black)
        ) {
            Image(it.asImageBitmap(), "Portrait", Modifier.fillMaxSize().then(mirrorMod), contentScale = ContentScale.Crop)
            if (!isPro) ThumbnailWatermark(Alignment.TopEnd)
        }
    }
}

@Composable
private fun LandscapeThumb(bmp: android.graphics.Bitmap?, mirrorMod: Modifier, isPro: Boolean) {
    bmp?.let {
        Box(
            Modifier.fillMaxHeight(0.65f).aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp)).background(Color.Black)
        ) {
            Image(it.asImageBitmap(), "Landscape", Modifier.fillMaxSize().then(mirrorMod), contentScale = ContentScale.Crop)
            if (!isPro) ThumbnailWatermark(Alignment.BottomEnd)
        }
    }
}

@Composable
private fun PrimaryResultButton(label: String, modifier: Modifier, enabled: Boolean, isSaved: Boolean = false, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp)
            .border(1.dp, if (enabled || isSaved) Color.White.copy(alpha = 0.85f) else Color(0xFF444444), RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Color(0xFF2A2A2A),
            contentColor = Color.White,
            disabledContainerColor = if (isSaved) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
            disabledContentColor = if (isSaved) Color.White else Color(0xFF666666),
        ),
    ) {
        Text(label, fontSize = 20.sp, maxLines = 1,
            fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
        if (isSaved) {
            Spacer(Modifier.width(6.dp))
            Text("✓", color = Color(0xFF66BB6A), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RemoveWatermarkResultButton(modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text("Remove Watermark", color = Color.White, fontSize = 19.sp,
            fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(4.dp))
        Text("♕", color = Color(0xFFFFD700), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResultButton(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(label, color = Color.White, fontSize = 16.sp, maxLines = 1,
            fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RemoveWatermarkDialog(
    viewModel: MainViewModel,
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF121212),
        title = {
            Text("Remove Watermark", color = Color.White, fontSize = 22.sp,
                fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        onDismiss()
                        val activity = context as? android.app.Activity
                        if (activity != null) {
                            com.dualframe.monetize.AdRewardManager.showAd(
                                activity = activity,
                                onRewarded = { viewModel.saveBothClean() },
                                onFailed = { viewModel.clearError() },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Watch Ad to Remove", color = Color.White, fontSize = 18.sp,
                        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium)
                }
                androidx.compose.material3.Button(
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
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2A2A2A),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Go PRO", fontSize = 18.sp,
                        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(4.dp))
                    Text("♕", color = Color(0xFFFFD700), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "One-time purchase.\nRemove watermarks forever.",
                    color = Color(0xFFBBBBBB), fontSize = 14.sp,
                    fontFamily = FontFamily.SansSerif,
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color(0xFFBBBBBB), fontSize = 15.sp,
                            fontFamily = FontFamily.SansSerif)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
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
