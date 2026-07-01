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
import androidx.compose.foundation.gestures.draggable
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
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    var showSettingsPage by remember {
        val prefs = context.getSharedPreferences("dualframe_settings", android.content.Context.MODE_PRIVATE)
        val reopen = prefs.getBoolean("reopen_settings", false)
        if (reopen) prefs.edit().remove("reopen_settings").apply()
        mutableStateOf(reopen)
    }

    if (showSettingsPage) {
        SettingsPage(
            settings = state.settings,
            onSettingsChange = { viewModel.updateSettings(it) },
            onBack = { showSettingsPage = false },
        )
        return
    }
    BackHandler(enabled = true) {
        android.util.Log.d("BackHandler", "Back pressed — showExitDialog=true")
        showExitDialog = true
    }

    val zoomRatio by viewModel.cameraManager.zoomRatio.collectAsState()
    val isRecording = state.appStatus == AppStatus.RECORDING
    val isPaused = state.appStatus == AppStatus.PAUSED
    val isRecordingOrPaused = isRecording || isPaused

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
                when {
                    isRecording -> RecStatusChip(state.recordingDurationSeconds, isPaused = false)
                    isPaused -> RecStatusChip(state.recordingDurationSeconds, isPaused = true)
                    else -> StatusChip(state.appStatus)
                }
                Spacer(Modifier.weight(1f))
                // Right slot: storage badge (if recording w/ ≤5min) OR gear icon (if not recording)
                if (isRecordingOrPaused) {
                    state.remainingRecordingSeconds?.let { remaining ->
                        StorageWarningBadge(remaining)
                    }
                } else {
                    Box(
                        Modifier.size(44.dp)
                            .clickable { settingsExpanded = !settingsExpanded },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Settings, null,
                            tint = if (settingsExpanded) RailTheme.activeColor else RailTheme.iconColor,
                            modifier = Modifier.size(24.dp))
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

            // Countdown overlay
            if (state.appStatus == AppStatus.COUNTDOWN && state.countdownRemaining > 0) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val scale = remember { Animatable(1.5f) }
                    val alpha = remember { Animatable(1f) }
                    LaunchedEffect(state.countdownRemaining) {
                        scale.snapTo(1.5f)
                        alpha.snapTo(1f)
                        launch { scale.animateTo(1f, tween(600)) }
                        launch { alpha.animateTo(0.6f, tween(800)) }
                    }
                    Text(
                        text = "${state.countdownRemaining}",
                        color = Color.White.copy(alpha = alpha.value),
                        fontSize = (72 * scale.value).sp,
                        fontFamily = PretendardFont,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Camera switch fade
            val switchAlpha = remember { Animatable(0f) }
            LaunchedEffect(isFrontCamera) {
                switchAlpha.snapTo(1f)
                switchAlpha.animateTo(0f, tween(350))
            }
            if (switchAlpha.value > 0.01f) {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = switchAlpha.value)),
                )
            }
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
            onPause = { viewModel.togglePause() },
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
                onMoreClick = {
                    settingsExpanded = false
                    showSettingsPage = true
                },
            )
        }
    }

    // FHD fallback toast
    if (state.showFhdFallbackToast) {
        LaunchedEffect(Unit) {
            android.widget.Toast.makeText(context,
                context.getString(R.string.toast_export_fallback_fhd),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Auto-save completion toasts
    if (state.showAutoSaveCompleteToast) {
        LaunchedEffect(Unit) {
            android.widget.Toast.makeText(context,
                context.getString(R.string.toast_auto_save_complete),
                android.widget.Toast.LENGTH_SHORT).show()
            viewModel.dismissAutoSaveToasts()
        }
    }
    if (state.showAutoSaveFailToast) {
        LaunchedEffect(Unit) {
            android.widget.Toast.makeText(context,
                context.getString(R.string.toast_auto_save_failed),
                android.widget.Toast.LENGTH_LONG).show()
            viewModel.dismissAutoSaveToasts()
        }
    }

    // Fullscreen result overlay
    if (state.appStatus == AppStatus.EXPORT_COMPLETE || state.appStatus == AppStatus.SAVING) {
        ResultActions(state, viewModel, context, deviceRotation)
    }

    if (state.showRemoveWatermarkDialog) {
        RemoveWatermarkDialog(viewModel, context) { viewModel.dismissRemoveWatermarkDialog() }
    }

    // Ad failure dialogs
    state.adFailDialog?.let { failType ->
        AdFailDialog(
            failType = failType,
            onRetry = {
                viewModel.dismissAdFailDialog()
                val activity = context as? Activity
                if (activity != null) {
                    com.dualframe.monetize.AdRewardManager.showAd(
                        activity = activity,
                        onRewarded = {
                            viewModel.onAdRewarded()
                            android.widget.Toast.makeText(context,
                                context.getString(R.string.toast_watermark_removed),
                                android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onFailed = { viewModel.onAdFailed() },
                    )
                }
            },
            onSaveWithWatermark = {
                viewModel.dismissAdFailDialog()
                viewModel.saveBothWithWatermark()
            },
            onViewPro = {
                viewModel.dismissAdFailDialog()
                viewModel.showRemoveWatermarkDialog()
            },
        )
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
        AppStatus.PAUSED -> stringResource(R.string.recording_paused_prefix) to Color(0xFF888888)
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

@Composable
private fun RecStatusChip(durationSeconds: Int, isPaused: Boolean) {
    val bg = if (isPaused) Color(0x44888888) else Color(0x44FF1744)
    val dot = if (isPaused) Color(0xFF888888) else Color(0xFFFF1744)
    Row(
        Modifier.background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(5.dp))
        if (isPaused) {
            Text(
                text = stringResource(R.string.recording_paused_prefix) + " " + formatDuration(durationSeconds),
                color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
            )
        } else {
            Text(
                text = formatDuration(durationSeconds),
                color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun StorageWarningBadge(remainingSeconds: Int) {
    val isRed = remainingSeconds <= 60
    val bg = if (isRed) Color(0x66E53935) else Color(0x66FFB300)
    val fg = if (isRed) Color(0xFFFF5252) else Color(0xFFFFC107)
    val label = if (remainingSeconds >= 60) {
        stringResource(R.string.recording_remaining_min, remainingSeconds / 60)
    } else {
        stringResource(R.string.recording_remaining_sec, remainingSeconds)
    }
    Row(
        Modifier.background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", color = fg, fontSize = 13.sp)
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = PretendardFont)
    }
}

@Composable
private fun EndedEarlyBanner() {
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF3D2A00), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.recording_ended_early_title),
            color = Color(0xFFFFB300), fontSize = 13.sp,
            fontWeight = FontWeight.Bold, fontFamily = PretendardFont)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.recording_ended_early_desc),
            color = Color(0xFFCCCCCC), fontSize = 11.sp,
            fontFamily = PretendardFont)
    }
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
                .clip(RoundedCornerShape(6.dp)).background(Color.Black)
                .pointerInput(Unit) {
                    detectTapPanZoom(
                        onTap = { pos ->
                            pFocusPos = pos; pFocusKey++
                            cameraManager.focusAt(pos.x / size.width, pos.y / size.height)
                        },
                        onGesture = { _, zoom ->
                            if (zoom != 1f)
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
            ExposureSliderOverlay(pFocusKey, pFocusPos, cameraManager)
        }

        var lFocusKey by remember { mutableIntStateOf(0) }
        var lFocusPos by remember { mutableStateOf(Offset.Zero) }
        var landscapeOffset by remember { mutableFloatStateOf(0f) }

        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
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
                            if (!cameraManager.isRecording && pan.y != 0f) {
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
            ExposureSliderOverlay(lFocusKey, lFocusPos, cameraManager)
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

@Composable
private fun ExposureSliderOverlay(
    tapKey: Int,
    focusPos: Offset,
    cameraManager: com.dualframe.camera.CameraManager,
) {
    if (tapKey == 0) return
    val range = remember(tapKey) { cameraManager.exposureCompensationRange() } ?: return
    val (minIdx, maxIdx, _) = range

    var index by remember(tapKey) { mutableIntStateOf(0) }
    var alpha by remember { mutableStateOf(0f) }
    var lastTouchAtMs by remember(tapKey) { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(tapKey) {
        cameraManager.setExposureCompensation(0)
        index = 0
        alpha = 1f
        // Auto-hide 3s after last touch
        while (true) {
            delay(500)
            val idle = System.currentTimeMillis() - lastTouchAtMs
            if (idle >= 3000) { alpha = 0f; break }
        }
    }

    if (alpha == 0f) return

    val density = LocalDensity.current
    val sliderHeightDp = 120.dp
    val sliderWidthDp = 28.dp
    val gapDp = 12.dp

    // Slider positioned to the right of focus ring, kept within bounds
    val sliderTopPx = with(density) { (focusPos.y - sliderHeightDp.toPx() / 2f).coerceAtLeast(0f) }
    val sliderLeftPx = with(density) { focusPos.x + 22.dp.toPx() + gapDp.toPx() }

    val sliderHeightPx = with(density) { sliderHeightDp.toPx() }
    val dragState = androidx.compose.foundation.gestures.rememberDraggableState { dragY ->
        lastTouchAtMs = System.currentTimeMillis()
        val delta = (-dragY / sliderHeightPx) * (maxIdx - minIdx)
        val newIdx = (index + delta.toInt()).coerceIn(minIdx, maxIdx)
        if (newIdx != index) {
            index = newIdx
            cameraManager.setExposureCompensation(newIdx)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(sliderLeftPx.toInt(), sliderTopPx.toInt())
                }
                .size(sliderWidthDp, sliderHeightDp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .draggable(
                    state = dragState,
                    orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                    onDragStarted = { lastTouchAtMs = System.currentTimeMillis() },
                    onDragStopped = { lastTouchAtMs = System.currentTimeMillis() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxSize().padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("+", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                val pctFromCenter = if (maxIdx != minIdx) (index - minIdx).toFloat() / (maxIdx - minIdx) else 0.5f
                Box(
                    Modifier.width(4.dp).height(60.dp)
                        .background(Color(0x66FFFFFF), RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val fillOffset = ((1f - pctFromCenter) * 56).toInt()
                    Box(
                        Modifier.offset(y = (fillOffset - 28).dp)
                            .size(10.dp)
                            .background(Color(0xFFFFC107), CircleShape),
                    )
                }
                Text("−", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GhostWatermark(anchor: Alignment, fontSize: Int, rotation: Float, rotVPad: Dp = 20.dp, isCropFrame: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        val hOffset = when {
            rotation == 0f -> 0.dp
            isCropFrame && rotation < 0f -> (-8).dp
            isCropFrame -> 8.dp
            rotation < 0f -> 10.dp
            else -> (-10).dp
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
        rotation < 0f -> (-6).dp
        else -> 6.dp
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
                    .background(Color(0x80000000), RoundedCornerShape(6.dp))
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

    val isSaved = state.saveMessage != null
    val isSavedClean = state.saveMessage == "Saved"
    val isAutoSave = state.settings.autoSave && isPro
    val saveLabel = when {
        state.appStatus == AppStatus.SAVING -> stringResource(R.string.label_saving)
        isSaved -> stringResource(R.string.label_saved)
        else -> stringResource(R.string.btn_save_videos)
    }
    val saveIcon = if (isSaved) Icons.Outlined.Check else Icons.Outlined.FileDownload
    val saveEnabled = state.appStatus != AppStatus.SAVING && !isSavedClean

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center,
    ) {
        if (isLandscapeRecording) {
            // ── Landscape: consistent layout for both rotation directions ──
            val landscapeRot = if (deviceRotation == 90) 180f else 0f

            // Back handler for landscape save screen
            BackHandler { viewModel.resetToIdle() }

            if (state.endedEarlyDueToStorage) {
                Box(
                    Modifier.fillMaxWidth().rotate(landscapeRot)
                        .padding(horizontal = 30.dp, vertical = 6.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    EndedEarlyBanner()
                }
            }
            Row(
                modifier = Modifier.fillMaxSize()
                    .rotate(landscapeRot)
                    .padding(start = 30.dp, end = 30.dp, top = 24.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Thumbnails
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
                    modifier = Modifier.weight(0.45f).padding(start = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    SaveResultButton(saveIcon, saveLabel,
                        enabled = if (isAutoSave) false else saveEnabled,
                        forceGreen = isSavedClean,
                    ) {
                        if (isPro) viewModel.saveBothWithWatermark()
                        else viewModel.showRemoveWatermarkDialog()
                    }
                    IconResultButton(Icons.Outlined.PhotoLibrary, stringResource(R.string.btn_view_in_gallery)) {
                        try { context.startActivity(buildGalleryIntent(context)) }
                        catch (_: Exception) {}
                    }
                    IconResultButton(Icons.Outlined.CameraAlt, stringResource(R.string.btn_retake)) {
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
                if (state.endedEarlyDueToStorage) {
                    EndedEarlyBanner()
                    Spacer(Modifier.height(8.dp))
                }
                portraitBmp?.let { bmp ->
                    Box(
                        Modifier.fillMaxWidth(0.50f).aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(6.dp)).background(Color.Black),
                    ) {
                        Image(bmp.asImageBitmap(), "Portrait", Modifier.fillMaxSize().then(mirrorMod), contentScale = ContentScale.Crop)
                        if (!isPro) ThumbnailWatermark(Alignment.TopEnd)
                    }
                }

                Spacer(Modifier.height(6.dp))

                landscapeBmp?.let { bmp ->
                    Box(
                        Modifier.fillMaxWidth(0.50f).aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(6.dp)).background(Color.Black),
                    ) {
                        Image(bmp.asImageBitmap(), "Landscape", Modifier.fillMaxSize().then(mirrorMod), contentScale = ContentScale.Crop)
                        if (!isPro) ThumbnailWatermark(Alignment.BottomEnd)
                    }
                }

                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(0.75f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SaveResultButton(saveIcon, saveLabel,
                        enabled = if (isAutoSave) false else saveEnabled,
                        forceGreen = isSavedClean,
                    ) {
                        if (isPro) viewModel.saveBothWithWatermark()
                        else viewModel.showRemoveWatermarkDialog()
                    }
                    IconResultButton(Icons.Outlined.PhotoLibrary, stringResource(R.string.btn_view_in_gallery)) {
                        try { context.startActivity(buildGalleryIntent(context)) }
                        catch (_: Exception) {}
                    }
                    IconResultButton(Icons.Outlined.CameraAlt, stringResource(R.string.btn_retake)) {
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
                .clip(RoundedCornerShape(6.dp)).background(Color.Black)
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
                .clip(RoundedCornerShape(6.dp)).background(Color.Black)
        ) {
            Image(it.asImageBitmap(), "Landscape", Modifier.fillMaxSize().then(mirrorMod), contentScale = ContentScale.Crop)
            if (!isPro) ThumbnailWatermark(Alignment.BottomEnd)
        }
    }
}

private val SaveGreenBrush = Brush.horizontalGradient(
    colors = listOf(Color(0xFF228B22), Color(0xFF32CD32), Color(0xFF228B22))
)

@Composable
private fun SaveResultButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    forceGreen: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled || forceGreen) SaveGreenBrush else Brush.horizontalGradient(listOf(Color(0xFF1A1A1A), Color(0xFF1A1A1A))),
                RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 18.sp,
                fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun IconResultButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().height(52.dp)
            .border(1.dp, Color(0xFF555555), RoundedCornerShape(10.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 18.sp,
                fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RemoveWatermarkDialog(
    viewModel: MainViewModel,
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    val price = com.dualframe.monetize.BillingManager.getInstance(context).formattedPrice
    var isProcessing by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF141414))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Close
            Box(Modifier.fillMaxWidth()) {
                Text("✕", color = Color(0xFF888888), fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .clickable { onDismiss() }.padding(4.dp))
            }

            // Title
            Text(stringResource(R.string.save_popup_title), color = Color.White, fontSize = 22.sp,
                fontFamily = PretendardFont, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(14.dp))

            // 1. PRO Upgrade — premium gold gradient
            val goldBrush = Brush.horizontalGradient(
                colors = listOf(Color(0xFFD4AF37), Color(0xFFF5D76E), Color(0xFFC89B2A))
            )
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(goldBrush)
                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(12.dp))
                    .clickable {
                        if (isProcessing) return@clickable
                        isProcessing = true
                        val activity = context as? android.app.Activity
                        if (activity != null) {
                            com.dualframe.monetize.BillingManager.getInstance(context)
                                .launchPurchase(activity) { success ->
                                    isProcessing = false
                                    if (success) {
                                        android.widget.Toast.makeText(context,
                                            context.getString(R.string.pro_purchase_success),
                                            android.widget.Toast.LENGTH_LONG).show()
                                        onDismiss()
                                        viewModel.saveBothClean()
                                    } else {
                                        android.widget.Toast.makeText(context,
                                            context.getString(R.string.pro_purchase_failed),
                                            android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                        } else { isProcessing = false }
                    }
                    .padding(start = 10.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.Black.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val iconGold = Brush.linearGradient(
                        colors = listOf(Color(0xFFF5D76E), Color.White, Color(0xFFF5D76E))
                    )
                    Icon(Icons.Outlined.WorkspacePremium, null,
                        modifier = Modifier.size(24.dp)
                            .graphicsLayer(alpha = 0.99f)
                            .drawWithCache {
                                onDrawWithContent {
                                    drawContent()
                                    drawRect(brush = iconGold, blendMode = BlendMode.SrcAtop)
                                }
                            })
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.save_popup_pro_title), color = Color(0xFF111111), fontSize = 18.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(R.string.save_popup_pro_subtitle), color = Color(0xFF6B5E3A), fontSize = 13.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.Normal,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (price != null) {
                    Text(price, color = Color(0xFFF0F0F0), fontSize = 15.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(4.dp))
                Text("›", color = Color.White, fontSize = 20.sp)
            }

            Spacer(Modifier.height(8.dp))

            // 2. Watch Ad
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(12.dp))
                    .clickable {
                        if (isProcessing) return@clickable
                        isProcessing = true
                        onDismiss()
                        val activity = context as? android.app.Activity
                        if (activity != null) {
                            com.dualframe.monetize.AdRewardManager.showAd(
                                activity = activity,
                                onRewarded = {
                                    viewModel.onAdRewarded()
                                    android.widget.Toast.makeText(context,
                                        context.getString(R.string.toast_watermark_removed),
                                        android.widget.Toast.LENGTH_SHORT).show()
                                },
                                onFailed = { viewModel.onAdFailed() },
                            )
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.save_popup_ad_title), color = Color.White, fontSize = 18.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(R.string.save_popup_ad_desc), color = Color(0xFFAAAAAA), fontSize = 13.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.Normal,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Text("›", color = Color(0xFF666666), fontSize = 20.sp)
            }

            Spacer(Modifier.height(8.dp))

            // 3. Basic Save
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1A1A1A))
                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(12.dp))
                    .clickable { onDismiss(); viewModel.saveBothWithWatermark() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.SaveAlt, null, tint = Color(0xFFAAAAAA),
                    modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                val watermarkDesc = stringResource(R.string.save_popup_watermark_desc)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.save_popup_watermark_title), color = Color.White, fontSize = 18.sp,
                        fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (watermarkDesc.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(watermarkDesc, color = Color(0xFFAAAAAA), fontSize = 13.sp,
                            fontFamily = PretendardFont, fontWeight = FontWeight.Normal)
                    }
                }
                Text("›", color = Color(0xFF666666), fontSize = 20.sp)
            }

        }
    }
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

@Composable
private fun AdFailDialog(
    failType: com.dualframe.data.AdFailType,
    onRetry: () -> Unit,
    onSaveWithWatermark: () -> Unit,
    onViewPro: () -> Unit,
) {
    val title = when (failType) {
        com.dualframe.data.AdFailType.OFFLINE -> stringResource(R.string.error_ad_offline_title)
        com.dualframe.data.AdFailType.REPEATED_FAILURE -> stringResource(R.string.error_ad_repeated_title)
    }
    val desc = when (failType) {
        com.dualframe.data.AdFailType.OFFLINE -> stringResource(R.string.error_ad_offline_desc)
        com.dualframe.data.AdFailType.REPEATED_FAILURE -> stringResource(R.string.error_ad_repeated_desc)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.85f)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, fontSize = 17.sp,
                fontWeight = FontWeight.Bold, fontFamily = PretendardFont,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(desc, color = Color(0xFFAAAAAA), fontSize = 14.sp,
                fontFamily = PretendardFont,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(20.dp))

            // Retry button
            Box(
                modifier = Modifier.fillMaxWidth().height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF4CAF50))
                    .clickable { onRetry() },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.btn_retry), color = Color.White, fontSize = 15.sp,
                    fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(8.dp))

            // Save with watermark button
            Box(
                modifier = Modifier.fillMaxWidth().height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(10.dp))
                    .clickable { onSaveWithWatermark() },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.btn_save_with_watermark), color = Color.White, fontSize = 15.sp,
                    fontFamily = PretendardFont, fontWeight = FontWeight.SemiBold)
            }

            // PRO link (only for repeated failure)
            if (failType == com.dualframe.data.AdFailType.REPEATED_FAILURE) {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.btn_view_pro), color = Color(0xFF888888), fontSize = 13.sp,
                    fontFamily = PretendardFont,
                    modifier = Modifier.clickable { onViewPro() }.padding(4.dp))
            }
        }
    }
}
