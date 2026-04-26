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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.dualframe.data.AppStatus
import com.sunnlab.dualframe.R
import com.dualframe.data.UiState
import com.dualframe.ui.theme.PretendardFont
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
    BackHandler(enabled = true) {
        android.util.Log.d("BackHandler", "Back pressed — showExitDialog=true")
        showExitDialog = true
    }

    val zoomRatio by viewModel.cameraManager.zoomRatio.collectAsState()
    val isRecording = state.appStatus == AppStatus.RECORDING

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
        ) {
            // ── Top header: DualFrame + Ready/REC + Settings gear ──
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("DualFrame", color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, fontFamily = PretendardFont)
                Spacer(Modifier.width(7.dp))
                if (isRecording) {
                    Row(
                        Modifier.background(Color(0x44FF1744), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFF1744)))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = formatDuration(state.recordingDurationSeconds),
                            color = Color.White, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
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
                            modifier = Modifier.size(20.dp))
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
                .padding(top = 56.dp, end = 2.dp),
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

    // Exit confirmation — rendered last to appear on top of everything
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
                Text(stringResource(R.string.dialog_exit_title), color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, fontFamily = PretendardFont)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.dialog_exit_message), color = Color(0xFFCCCCCC),
                    fontSize = 15.sp, fontFamily = PretendardFont)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    androidx.compose.material3.TextButton(onClick = { showExitDialog = false }) {
                        Text(stringResource(R.string.btn_cancel), color = Color(0xFF999999), fontFamily = PretendardFont)
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        showExitDialog = false
                        (context as? Activity)?.finish()
                    }) {
                        Text(stringResource(R.string.btn_exit), color = Color(0xFFFF5252), fontFamily = PretendardFont)
                    }
                }
            }
        }
    }
}

// ── Status Chip ──────────────────────────────────────────────────────

@Composable
private fun StatusChip(status: AppStatus) {
    val (text, color) = when (status) {
        AppStatus.IDLE -> stringResource(R.string.status_ready) to Color(0xFF4CAF50)
        AppStatus.COUNTDOWN -> stringResource(R.string.status_countdown) to Color(0xFFFFA726)
        AppStatus.RECORDING -> stringResource(R.string.status_rec) to Color(0xFFFF1744)
        AppStatus.EXPORTING_NATIVE -> stringResource(R.string.status_exporting) to Color(0xFFFFA726)
        AppStatus.EXPORTING_CROPPED -> stringResource(R.string.status_exporting) to Color(0xFFFFA726)
        AppStatus.EXPORT_COMPLETE -> stringResource(R.string.status_done) to Color(0xFF66BB6A)
        AppStatus.SAVING -> stringResource(R.string.status_saving) to Color(0xFFFFA726)
        AppStatus.ERROR -> stringResource(R.string.status_error) to Color(0xFFCF6679)
    }
    Text(text = text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Normal,
        fontFamily = PretendardFont,
        modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp))
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
                90 -> {  // landscape right — 9:16 frame
                    GhostWatermark(Alignment.TopEnd, 13, rot, rotVPad = 30.dp)
                    AspectLabel("16:9", Alignment.BottomStart, rot)
                }
                270 -> {
                    GhostWatermark(Alignment.BottomStart, 13, rot, rotVPad = 30.dp)
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
                    GhostWatermark(Alignment.TopStart, 9, rot, rotVPad = 21.dp, isCropFrame = true)
                    AspectLabel("9:16", Alignment.BottomStart, rot)
                }
                270 -> {
                    GhostWatermark(Alignment.BottomEnd, 9, rot, rotVPad = 21.dp, isCropFrame = true)
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
private fun GhostWatermark(anchor: Alignment, fontSize: Int, rotation: Float, rotVPad: Dp = 20.dp, isCropFrame: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        val hOffset = when {
            rotation == 0f -> 0.dp
            !isCropFrame -> 0.dp
            rotation < 0f -> 30.dp
            else -> (-30).dp
        }
        Box(
            modifier = Modifier.align(anchor)
                .padding(horizontal = if (rotation == 0f) 6.dp else 0.dp, vertical = if (rotation == 0f) 6.dp else rotVPad)
                .offset(x = hOffset),
        ) {
            Text(
                text = "DualFrame",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = fontSize.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = PretendardFont,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

@Composable
private fun AspectLabel(text: String, anchor: Alignment, rotation: Float) {
    val padH = if (rotation == 0f) 4.dp else 0.dp
    val padV = if (rotation == 0f) 4.dp else 12.dp
    val offsetX = when {
        rotation == 0f -> 0.dp
        rotation < 0f -> 30.dp
        else -> (-30).dp
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.align(anchor)
                .padding(horizontal = padH, vertical = padV)
                .offset(x = offsetX),
        ) {
            Text(text = text, color = Color.White, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.rotate(rotation)
                    .background(Color(0x77000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp))
        }
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
        state.appStatus == AppStatus.SAVING -> stringResource(R.string.label_saving)
        state.saveMessage != null -> stringResource(R.string.label_saved)
        else -> stringResource(R.string.btn_save_videos)
    }
    val saveEnabled = state.appStatus == AppStatus.EXPORT_COMPLETE && state.saveMessage == null

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center,
    ) {
        if (isLandscapeRecording) {
            // ── Landscape: consistent layout for both rotation directions ──
            val landscapeRot = if (deviceRotation == 90) 180f else 0f

            // Back handler for landscape save screen
            BackHandler { viewModel.resetToIdle() }

            Row(
                modifier = Modifier.fillMaxSize()
                    .rotate(landscapeRot)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Thumbnails: shared height computed from constraints
                BoxWithConstraints(
                    modifier = Modifier.weight(0.55f),
                    contentAlignment = Alignment.Center,
                ) {
                    val spacing = 6.dp
                    val maxH = maxHeight * 0.7f
                    val widthNeeded16x9 = maxH * 16f / 9f
                    val widthNeeded9x16 = maxH * 9f / 16f
                    val totalW = widthNeeded16x9 + widthNeeded9x16 + spacing
                    val thumbH = if (totalW > maxWidth) {
                        (maxWidth - spacing) / (16f / 9f + 9f / 16f)
                    } else {
                        maxH
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (deviceRotation == 270) {
                            LandscapeThumbFixed(landscapeBmp, thumbH, mirrorMod, isPro)
                            PortraitThumbFixed(portraitBmp, thumbH, mirrorMod, isPro)
                        } else {
                            PortraitThumbFixed(portraitBmp, thumbH, mirrorMod, isPro)
                            LandscapeThumbFixed(landscapeBmp, thumbH, mirrorMod, isPro)
                        }
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
                    ResultButton(stringResource(R.string.btn_view_in_gallery), Modifier.fillMaxWidth(0.9f), true) {
                        try { context.startActivity(buildGalleryIntent(context)) }
                        catch (_: Exception) {}
                    }
                    ResultButton(stringResource(R.string.btn_retake), Modifier.fillMaxWidth(0.9f), true) {
                        viewModel.resetToIdle()
                    }
                }
            }
        } else {
            // ── Portrait: centered vertical layout ──
            BackHandler { viewModel.resetToIdle() }
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
                    ResultButton(stringResource(R.string.btn_view_in_gallery), Modifier.fillMaxWidth(), true) {
                        try { context.startActivity(buildGalleryIntent(context)) }
                        catch (_: Exception) {}
                    }
                    ResultButton(stringResource(R.string.btn_retake), Modifier.fillMaxWidth(), true) {
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
            color = Color.White.copy(alpha = 0.40f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = PretendardFont,
            modifier = Modifier.align(anchor)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PortraitThumbFixed(bmp: android.graphics.Bitmap?, thumbH: Dp, mirrorMod: Modifier, isPro: Boolean) {
    bmp?.let {
        Box(
            Modifier.height(thumbH).aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(10.dp)).background(Color.Black)
        ) {
            Image(it.asImageBitmap(), "Portrait", Modifier.fillMaxSize().then(mirrorMod), contentScale = ContentScale.Crop)
            if (!isPro) ThumbnailWatermark(Alignment.TopEnd)
        }
    }
}

@Composable
private fun LandscapeThumbFixed(bmp: android.graphics.Bitmap?, thumbH: Dp, mirrorMod: Modifier, isPro: Boolean) {
    bmp?.let {
        Box(
            Modifier.height(thumbH).aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp)).background(Color.Black)
        ) {
            Image(it.asImageBitmap(), "Landscape", Modifier.fillMaxSize().then(mirrorMod), contentScale = ContentScale.Crop)
            if (!isPro) ThumbnailWatermark(Alignment.BottomEnd)
        }
    }
}

private val ShinyGreenBrush = Brush.horizontalGradient(
    colors = listOf(Color(0xFF228B22), Color(0xFF32CD32), Color(0xFF228B22))
)
private val SavedGreenBrush = Brush.horizontalGradient(
    colors = listOf(Color(0xFF2E7D32), Color(0xFF66BB6A), Color(0xFF2E7D32))
)

@Composable
private fun PrimaryResultButton(label: String, modifier: Modifier, enabled: Boolean, isSaved: Boolean = false, onClick: () -> Unit) {
    val brush = when {
        isSaved -> SavedGreenBrush
        enabled -> ShinyGreenBrush
        else -> null
    }
    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (brush == null) Color(0xFF1A1A1A) else Color.Transparent,
        modifier = modifier.height(48.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (brush != null) Modifier.background(brush) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White, fontSize = 18.sp, maxLines = 1,
                    fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).sp)
                if (isSaved) {
                    Spacer(Modifier.width(6.dp))
                    Text("✓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun RemoveWatermarkResultButton(modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp)
            .border(1.dp, Color(0xFFFFD54A).copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFF0D0D0D),
            contentColor = Color.White,
        ),
    ) {
        Text(stringResource(R.string.btn_remove_watermark), color = Color(0xFFFFD54A), fontSize = 18.sp,
            fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).sp)
        Spacer(Modifier.width(6.dp))
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
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFF0D0D0D),
            contentColor = Color.White,
        ),
    ) {
        Text(label, color = Color.White, fontSize = 18.sp, maxLines = 1,
            fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).sp)
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
            Text(stringResource(R.string.dialog_remove_watermark_title), color = Color.White, fontSize = 22.sp,
                fontFamily = PretendardFont, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        onDismiss()
                        val activity = context as? android.app.Activity
                        if (activity != null) {
                            com.dualframe.monetize.AdRewardManager.showAd(
                                activity = activity,
                                onRewarded = {
                                    viewModel.saveBothClean()
                                    android.widget.Toast.makeText(context,
                                        context.getString(R.string.toast_watermark_removed),
                                        android.widget.Toast.LENGTH_SHORT).show()
                                },
                                onFailed = { viewModel.clearError() },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF0D0D0D),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(stringResource(R.string.btn_watch_ad), color = Color.White, fontSize = 18.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.Medium)
                }
                val shinyGoldBrush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFFFFD700), Color(0xFFFFF176), Color(0xFFFFD700))
                )
                androidx.compose.material3.Surface(
                    onClick = {
                        val activity = context as? android.app.Activity
                        if (activity != null) {
                            com.dualframe.monetize.BillingManager.getInstance(context)
                                .launchPurchase(activity) { success ->
                                    if (success) {
                                        onDismiss()
                                        viewModel.saveBothClean()
                                    }
                                }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(shinyGoldBrush),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.btn_go_pro), color = Color(0xFF111111), fontSize = 18.sp,
                                fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(4.dp))
                            Text("♕", color = Color(0xFF111111), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    "${stringResource(R.string.desc_pro_onetime)}\n${stringResource(R.string.desc_pro_no_watermark)}\n${stringResource(R.string.desc_pro_adfree)}",
                    color = Color(0xFFBBBBBB), fontSize = 14.sp,
                    fontFamily = PretendardFont,
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    androidx.compose.material3.TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.btn_cancel), color = Color(0xFFBBBBBB), fontSize = 15.sp,
                            fontFamily = PretendardFont)
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
        androidx.compose.material3.TextButton(onDismiss) { Text(stringResource(R.string.error_dismiss), color = Color(0xFFCF6679), fontSize = 11.sp) }
    }
}
