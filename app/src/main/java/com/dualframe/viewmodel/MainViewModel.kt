package com.dualframe.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.camera.video.Quality
import com.dualframe.camera.CameraManager
import com.dualframe.data.AppSettings
import com.dualframe.data.AppStatus
import com.dualframe.data.SettingsStore
import com.dualframe.data.UiState
import com.dualframe.data.VideoQuality
import com.dualframe.util.VideoMetadata
import com.dualframe.export.ExportManager
import com.dualframe.util.FileStorage
import com.dualframe.util.WatermarkHelper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main ViewModel — single coordinator for the entire screen.
 *
 * Responsibilities:
 * 1. Camera lifecycle (bind/release)
 * 2. Recording with optional countdown
 * 3. Recording timer
 * 4. Post-recording export pipeline (16:9 then 9:16)
 * 5. Settings management
 * 6. Post-export thumbnail generation
 * 7. Error surfacing
 * 8. UI state emission
 */
@UnstableApi
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"

        // Save-phase progress budget. The two effect passes run sequentially and
        // dominate the wait; the tail covers the two MediaStore writes.
        private const val SAVE_PASS_1_START = 0f
        private const val SAVE_PASS_1_END = 0.45f
        private const val SAVE_PASS_2_START = 0.45f
        private const val SAVE_PASS_2_END = 0.90f
        private const val SAVE_MEDIASTORE_MID = 0.95f
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val cameraManager = CameraManager(application)
    private val exportManager = ExportManager(application)

    private var masterFile: File? = null
    private var timerJob: Job? = null
    private var countdownJob: Job? = null

    init {
        // Load persisted settings
        val settings = SettingsStore.load(application)
        _uiState.update { it.copy(settings = settings) }
        cameraManager.setInitialCamera(settings.defaultFrontCamera)
    }

    // ── Camera ────────────────────────────────────────────────────────

    /**
     * Bind camera with GPU dual preview. No PreviewView needed — the renderer
     * provides its own SurfaceTexture to CameraX. Always 2 use cases.
     */
    fun bindCamera(lifecycleOwner: LifecycleOwner) {
        viewModelScope.launch {
            // Supported qualities aren't knowable until a CameraX provider exists,
            // and binding used to be what created it — so the first bind ran with
            // the stored default (FHD on a fresh install) and the first-launch
            // promotion to UHD arrived after the Recorder was already built.
            // Prime the provider first, resolve the quality, then bind once.
            //
            // Skipping the refresh when the provider is unavailable matters: it
            // would otherwise mark settings initialized off getSupportedQualities'
            // [FHD, HD] fallback, pinning a 4K device to FHD for good.
            if (cameraManager.prepareCameraProvider()) {
                refreshSupportedCapabilities()
            }
            val settings = _uiState.value.settings
            val success = cameraManager.bindCamera(
                lifecycleOwner = lifecycleOwner,
                quality = settings.videoQuality.toCameraXQuality(),
                onError = { msg -> setError(msg) },
            )
            _uiState.update { it.copy(cameraReady = success) }
        }
    }

    fun switchCamera() {
        if (cameraManager.isRecording) return
        _uiState.update { it.copy(cameraReady = false, flashOn = false) }
        viewModelScope.launch {
            val success = cameraManager.switchCamera(
                onError = { msg -> setError(msg) },
            )
            _uiState.update { it.copy(cameraReady = success) }
            // The new camera may not support the current quality, in which case
            // refreshSupportedCapabilities clamps the setting — push that back to
            // CameraX so the bound quality and the displayed one stay in step.
            refreshSupportedCapabilities()
            if (success) syncCameraQuality()
        }
    }

    fun toggleFlash() {
        val newState = !_uiState.value.flashOn
        cameraManager.setTorch(newState)
        _uiState.update { it.copy(flashOn = newState) }
    }

    /**
     * Query the current camera's supported quality/fps and update UiState.
     * If the current settings are no longer valid, auto-correct to safe defaults.
     */
    private fun refreshSupportedCapabilities() {
        val supportedCxQualities = cameraManager.getSupportedQualities()
        val supportedQualities = VideoQuality.entries.filter { vq ->
            vq.toCameraXQuality() in supportedCxQualities
        }.ifEmpty { listOf(VideoQuality.FHD) }

        var settings = _uiState.value.settings
        val app = getApplication<android.app.Application>()
        if (SettingsStore.isFirstLaunch(app)) {
            settings = settings.copy(videoQuality = supportedQualities.first())
            Log.i(TAG, "First launch: set quality to best available ${settings.videoQuality}")
            SettingsStore.markInitialized(app)
        } else if (settings.videoQuality !in supportedQualities) {
            settings = settings.copy(videoQuality = supportedQualities.first())
            Log.i(TAG, "Auto-corrected quality to ${settings.videoQuality}")
        }

        _uiState.update {
            it.copy(
                settings = settings,
                supportedQualities = supportedQualities,
            )
        }
        SettingsStore.save(getApplication(), settings)
    }

    /** Re-apply the effective quality to CameraX. No-ops when already bound at it. */
    private suspend fun syncCameraQuality() {
        val quality = _uiState.value.settings.videoQuality.toCameraXQuality()
        val ok = cameraManager.rebindWithQuality(
            newQuality = quality,
            onError = { msg -> setError(msg) },
        )
        _uiState.update { it.copy(cameraReady = ok) }
    }

    // ── Settings ──────────────────────────────────────────────────────

    fun updateSettings(newSettings: AppSettings) {
        val oldSettings = _uiState.value.settings
        _uiState.update { it.copy(settings = newSettings) }
        SettingsStore.save(getApplication(), newSettings)
        Log.i(TAG, "Settings updated: $newSettings")

        // Front camera beauty effect — only when front camera is active
        if (newSettings.frontCameraEffect != oldSettings.frontCameraEffect) {
            cameraManager.renderer.beautyEnabled =
                cameraManager.useFrontCamera.value && newSettings.frontCameraEffect
        }

        // If default camera changed, switch to match
        if (newSettings.defaultFrontCamera != oldSettings.defaultFrontCamera) {
            val currentlyFront = cameraManager.useFrontCamera.value
            if (currentlyFront != newSettings.defaultFrontCamera) {
                switchCamera()
            }
        }

        // If quality changed, refresh supported options and rebind camera
        if (newSettings.videoQuality != oldSettings.videoQuality) {
            refreshSupportedCapabilities()
            viewModelScope.launch { syncCameraQuality() }
        }
    }

    // ── Recording ─────────────────────────────────────────────────────

    /**
     * Called when the user taps the record button.
     * If recording, stops. If idle, starts (with optional countdown).
     */
    private var isToggling = false

    fun toggleRecording(hasAudioPermission: Boolean) {
        if (isToggling) return
        isToggling = true
        try {
            val status = _uiState.value.appStatus
            when {
                status == AppStatus.RECORDING || status == AppStatus.PAUSED -> stopRecording()
                status == AppStatus.COUNTDOWN -> cancelCountdown()
                status == AppStatus.IDLE || status == AppStatus.EXPORT_COMPLETE || status == AppStatus.ERROR -> {
                    val countdown = _uiState.value.settings.countdownSeconds
                    if (countdown > 0) {
                        startCountdown(countdown, hasAudioPermission)
                    } else {
                        startRecording(hasAudioPermission)
                    }
                }
            }
        } finally {
            isToggling = false
        }
    }

    fun togglePause() {
        val status = _uiState.value.appStatus
        when (status) {
            AppStatus.RECORDING -> {
                cameraManager.pauseRecording()
                _uiState.update { it.copy(appStatus = AppStatus.PAUSED) }
            }
            AppStatus.PAUSED -> {
                cameraManager.resumeRecording()
                _uiState.update { it.copy(appStatus = AppStatus.RECORDING) }
            }
            else -> { /* no-op */ }
        }
    }

    fun onAppBackgrounded() {
        val status = _uiState.value.appStatus
        if (status == AppStatus.PAUSED) {
            Log.i(TAG, "App backgrounded while paused — auto stopping")
            stopRecording()
        }
    }

    private fun startCountdown(seconds: Int, hasAudioPermission: Boolean) {
        clearError()
        _uiState.update {
            it.copy(
                appStatus = AppStatus.COUNTDOWN,
                countdownRemaining = seconds,
                thumbnailBitmap = null,
                landscapeThumbnailBitmap = null,
                nativeExportInfo = null,
                croppedExportInfo = null,
            )
        }

        countdownJob = viewModelScope.launch {
            for (i in seconds downTo 1) {
                _uiState.update { it.copy(countdownRemaining = i) }
                delay(1000)
            }
            _uiState.update { it.copy(countdownRemaining = 0) }
            startRecording(hasAudioPermission)
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _uiState.update { it.copy(appStatus = AppStatus.IDLE, countdownRemaining = 0) }
    }

    private fun startRecording(hasAudioPermission: Boolean) {
        if (_uiState.value.appStatus == AppStatus.RECORDING) return
        clearError()
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                appStatus = AppStatus.RECORDING,
                recordingDurationSeconds = 0,
                thumbnailBitmap = null,
                landscapeThumbnailBitmap = null,
                nativeExportInfo = null,
                croppedExportInfo = null,
                remainingRecordingSeconds = null,
                endedEarlyDueToStorage = false,
            )
        }

        val file = cameraManager.startRecording(
            audioEnabled = settings.audioEnabled,
            hasAudioPermission = hasAudioPermission,
            onEvent = ::onRecordingEvent,
        )

        if (file == null) {
            _uiState.update { it.copy(appStatus = AppStatus.IDLE) }
            setError("Failed to start recording")
            return
        }

        masterFile = file
        startTimer()
    }

    private fun stopRecording() {
        cameraManager.stopRecording()
        stopTimer()
        _uiState.update { it.copy(remainingRecordingSeconds = null) }
    }

    private fun onRecordingEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Finalize -> {
                if (event.hasError()) {
                    Log.e(TAG, "Recording finalize error: ${event.error}")
                    setError("Recording failed (error ${event.error})")
                    _uiState.update { it.copy(appStatus = AppStatus.ERROR) }
                } else {
                    Log.i(TAG, "Recording finalized: ${masterFile?.absolutePath}")

                    // === MASTER FILE DIAGNOSTICS ===
                    // Log the actual recorded resolution BEFORE any export.
                    // This is the ground truth for whether CameraX honored the quality selection.
                    masterFile?.let { f ->
                        viewModelScope.launch {
                            val meta = withContext(Dispatchers.IO) { VideoMetadata.fromFile(f) }
                            val fps = withContext(Dispatchers.IO) { VideoMetadata.readActualFps(f) }
                            val settings = _uiState.value.settings
                            Log.i(TAG, "=== MASTER FILE DIAGNOSTICS ===")
                            Log.i(TAG, "  Requested quality: ${settings.videoQuality}")
                            Log.i(TAG, "  Fps policy: AUTO (internal)")
                            Log.i(TAG, "  Master raw: ${meta?.rawWidth}x${meta?.rawHeight}")
                            Log.i(TAG, "  Master display: ${meta?.displayWidth}x${meta?.displayHeight}")
                            Log.i(TAG, "  Master rotation: ${meta?.rotation}")
                            Log.i(TAG, "  Master actual fps: $fps")
                            Log.i(TAG, "  Master file size: ${f.length() / 1024}KB")
                            Log.i(TAG, "================================")
                        }
                    }

                    startExportPipeline()
                }
            }
            is VideoRecordEvent.Start -> Log.i(TAG, "Recording started")
            is VideoRecordEvent.Status -> { /* Timer handles UI */ }
            else -> { /* Pause/Resume not used */ }
        }
    }

    // ── Timer ─────────────────────────────────────────────────────────

    private fun startTimer() {
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val status = _uiState.value.appStatus
                if (status == AppStatus.PAUSED) continue

                // Update elapsed time only during RECORDING
                if (status == AppStatus.RECORDING) {
                    _uiState.update { it.copy(recordingDurationSeconds = it.recordingDurationSeconds + 1) }
                }

                // Compute remaining recording seconds for warning badge only.
                // We do NOT auto-stop — user retains full control. CameraX will
                // finalize the file if storage runs out during recording.
                val remaining = computeRemainingSeconds()
                // 4K UHD uses ~5MB/s, so 5min threshold triggers too often.
                // Lower threshold to 3min for UHD, keep 5min for FHD/HD.
                val badgeThreshold = if (_uiState.value.settings.videoQuality == com.dualframe.data.VideoQuality.UHD) 3 * 60 else 5 * 60
                _uiState.update {
                    it.copy(remainingRecordingSeconds = if (remaining != null && remaining <= badgeThreshold) remaining else null)
                }
            }
        }
    }

    private fun computeRemainingSeconds(): Int? {
        val app: android.app.Application = getApplication()
        val cacheDir = app.cacheDir
        val available = try {
            android.os.StatFs(cacheDir.absolutePath).availableBytes
        } catch (_: Exception) { return null }

        val master = masterFile ?: return null
        val elapsedSec = _uiState.value.recordingDurationSeconds
        if (!master.exists() || elapsedSec <= 0) {
            // Fallback: estimate from nominal bitrate
            val bitrate = when (_uiState.value.settings.videoQuality) {
                com.dualframe.data.VideoQuality.UHD -> 40_000_000L
                com.dualframe.data.VideoQuality.FHD -> 16_000_000L
                else -> 8_000_000L
            }
            val bytesPerSec = bitrate / 8
            return (available / bytesPerSec).toInt()
        }

        // Live rate: actual bytes / elapsed seconds
        val bytesPerSec = (master.length() / elapsedSec.toLong()).coerceAtLeast(1L)
        // Need buffer for finalizing (moov atom, cropped export ≈ 2x master size)
        val reserveBytes = 50_000_000L
        val usable = (available - reserveBytes).coerceAtLeast(0L)
        return (usable / bytesPerSec).toInt()
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    // ── Export pipeline ───────────────────────────────────────────────

    /**
     * Quality-preserving export pipeline.
     *
     * Step 1 — Native output: direct file copy of the master. No re-encode,
     *   no Transformer, no quality loss. Resolution matches exactly what CameraX recorded.
     * Step 2 — Derived output: Transformer center-crop to the other aspect ratio,
     *   with Presentation.createForHeight() to preserve pixel dimensions and prevent
     *   Transformer's default downscale to ~720p.
     *
     * Portrait master (default) → portrait native copy + landscape crop
     * Landscape master (fallback) → landscape native copy + portrait crop
     */
    private fun startExportPipeline() {
        val file = masterFile ?: run {
            setError("No master file to export")
            return
        }

        viewModelScope.launch {
            // === EXPORT PIPELINE DIAGNOSTICS ===
            val masterMeta = withContext(Dispatchers.IO) { VideoMetadata.fromFile(file) }
            Log.i("DualFrameCameraDiag", "=== EXPORT PIPELINE DIAGNOSTICS ===")
            Log.i("DualFrameCameraDiag", "Master file: ${file.name} (${file.length()/1024}KB)")
            Log.i("DualFrameCameraDiag", "Master raw: ${masterMeta?.rawWidth}x${masterMeta?.rawHeight}")
            Log.i("DualFrameCameraDiag", "Master display: ${masterMeta?.displayWidth}x${masterMeta?.displayHeight}")
            Log.i("DualFrameCameraDiag", "Master rotation: ${masterMeta?.rotation}")
            Log.i("DualFrameCameraDiag", "Master display aspect: ${masterMeta?.displayAspectRatio}")

            // Detect native orientation from the actual master file
            val isPortrait = withContext(Dispatchers.IO) {
                exportManager.isMasterPortrait(file)
            }
            Log.i("DualFrameCameraDiag", "isMasterPortrait: $isPortrait")

            val nativeSuffix: String
            val nativeLabel: String
            val croppedAspect: Float
            val croppedSuffix: String
            val croppedLabel: String

            if (isPortrait) {
                nativeSuffix = "V"
                nativeLabel = "9:16"
                croppedAspect = ExportManager.ASPECT_16x9
                croppedSuffix = "H"
                croppedLabel = "16:9"
            } else {
                nativeSuffix = "H"
                nativeLabel = "16:9"
                croppedAspect = ExportManager.ASPECT_9x16
                croppedSuffix = "V"
                croppedLabel = "9:16"
            }

            // ── Step 1: Native output — direct file copy, zero quality loss ──
            // The master file already has the native framing. Copying preserves
            // the exact recorded resolution, bitrate, and codec without re-encoding.
            _uiState.update { it.copy(appStatus = AppStatus.EXPORTING_NATIVE, exportProgress = 0f) }

            val nativeFile = exportManager.exportNativeCopy(file, nativeSuffix) { progress ->
                _uiState.update { it.copy(exportProgress = progress) }
            }
            if (nativeFile == null) {
                setError("$nativeLabel export failed")
                _uiState.update { it.copy(appStatus = AppStatus.ERROR) }
                return@launch
            }

            val nativeMeta = withContext(Dispatchers.IO) { VideoMetadata.fromFile(nativeFile) }
            val nativeRes = nativeMeta?.let { "${it.displayWidth}x${it.displayHeight}" } ?: ""
            val nativeFps = withContext(Dispatchers.IO) { VideoMetadata.readActualFps(nativeFile) }
            Log.i("DualFrameCameraDiag", "Native export ($nativeLabel): file=${nativeFile.name}")
            Log.i("DualFrameCameraDiag", "  raw: ${nativeMeta?.rawWidth}x${nativeMeta?.rawHeight}")
            Log.i("DualFrameCameraDiag", "  display: ${nativeMeta?.displayWidth}x${nativeMeta?.displayHeight}")
            Log.i("DualFrameCameraDiag", "  rotation: ${nativeMeta?.rotation}")
            Log.i("DualFrameCameraDiag", "  fps: $nativeFps")
            Log.i("DualFrameCameraDiag", "  size: ${nativeFile.length()/1024}KB")

            // ── Step 2: Derived output — center-crop with resolution preservation ──
            // Uses Presentation.createForHeight() to prevent Transformer's default downscale.
            _uiState.update { it.copy(appStatus = AppStatus.EXPORTING_CROPPED, exportProgress = 0f) }

            val cropOffset = cameraManager.renderer.landscapeCropOffsetY
            val targetRot = cameraManager.currentTargetRotation
            val isFront = cameraManager.useFrontCamera.value
            val masterRotation = masterMeta?.rotation ?: 0
            // For landscape recordings, the preview vertical offset maps to export
            // horizontal offset. The direction depends on rotation:
            //   ROTATION_90 (left turn): invert
            //   ROTATION_270 (right turn): same
            // Front camera adds mirror flip in preview, so the offset direction
            // is inverted again for both rotation directions.
            var exportOffset = cropOffset
            if (!isPortrait) {
                val invertForRotation = targetRot == android.view.Surface.ROTATION_90
                val invertForMirror = isFront
                if (invertForRotation != invertForMirror) exportOffset = -cropOffset
            }
            Log.i("DualFrameCameraDiag", "=== CROP OFFSET DEBUG ===")
            Log.i("DualFrameCameraDiag", "  cropOffset (raw): ${"%.4f".format(cropOffset)}")
            Log.i("DualFrameCameraDiag", "  targetRot: $targetRot isFront: $isFront isPortrait: $isPortrait")
            Log.i("DualFrameCameraDiag", "  exportOffset: ${"%.4f".format(exportOffset)}")
            Log.i("DualFrameCameraDiag", "========================")
            val croppedFile = exportManager.exportCropped(
                file, croppedAspect, croppedSuffix,
                verticalOffset = exportOffset,
            ) { progress ->
                _uiState.update { it.copy(exportProgress = progress) }
            }
            if (croppedFile == null) {
                setError("$croppedLabel export failed")
                _uiState.update { it.copy(appStatus = AppStatus.ERROR) }
                return@launch
            }

            if (exportManager.didFallbackToFhd) {
                _uiState.update { it.copy(showFhdFallbackToast = true) }
            }

            val croppedMeta = withContext(Dispatchers.IO) { VideoMetadata.fromFile(croppedFile) }
            val croppedRes = croppedMeta?.let { "${it.displayWidth}x${it.displayHeight}" } ?: ""
            val croppedFps = withContext(Dispatchers.IO) { VideoMetadata.readActualFps(croppedFile) }
            Log.i("DualFrameCameraDiag", "Cropped export ($croppedLabel): file=${croppedFile.name}")
            Log.i("DualFrameCameraDiag", "  raw: ${croppedMeta?.rawWidth}x${croppedMeta?.rawHeight}")
            Log.i("DualFrameCameraDiag", "  display: ${croppedMeta?.displayWidth}x${croppedMeta?.displayHeight}")
            Log.i("DualFrameCameraDiag", "  rotation: ${croppedMeta?.rotation}")
            Log.i("DualFrameCameraDiag", "  fps: $croppedFps")
            Log.i("DualFrameCameraDiag", "==============================")

            val thumbnail = generateThumbnail(nativeFile)
            val landscapeThumbnail = generateThumbnail(croppedFile)

            // Files ready in temp — NOT saved to gallery yet.
            // User must press Save Both or Remove Watermark to trigger gallery save.
            _uiState.update {
                it.copy(
                    appStatus = AppStatus.EXPORT_COMPLETE,
                    exportProgress = 1f,
                    masterIsPortrait = isPortrait,
                    wasFrontCamera = cameraManager.useFrontCamera.value,
                    thumbnailBitmap = thumbnail,
                    landscapeThumbnailBitmap = landscapeThumbnail,
                    nativeExportInfo = buildOutputLine(nativeLabel, nativeRes, nativeFps),
                    croppedExportInfo = buildOutputLine(croppedLabel, croppedRes, croppedFps),
                    nativeTempPath = nativeFile.absolutePath,
                    croppedTempPath = croppedFile.absolutePath,
                    savedNativeUri = null,
                    savedCroppedUri = null,
                    saveMessage = null,
                )
            }

            // Auto-save: if PRO + autoSave enabled, save in background and reset to IDLE
            // Skip auto-save if recording ended early due to storage — user needs to see the situation
            val currentSettings = _uiState.value.settings
            val endedEarly = _uiState.value.endedEarlyDueToStorage
            if (currentSettings.autoSave &&
                com.dualframe.monetize.ProEntitlement.isProOwned(getApplication()) &&
                !endedEarly) {
                Log.i(TAG, "Auto-save triggered — running in background")
                autoSaveInBackground(nativeFile.absolutePath, croppedFile.absolutePath)
            } else if (endedEarly) {
                Log.w(TAG, "Auto-save skipped: recording ended early due to storage")
            }
        }
    }

    /**
     * Auto-save path: keep app in IDLE so the user can continue recording immediately.
     * Save runs in background, toast fires on completion.
     */
    private fun autoSaveInBackground(nativePath: String, croppedPath: String) {
        // Reset UI to IDLE right away so the camera screen is usable
        _uiState.update {
            it.copy(
                appStatus = AppStatus.IDLE,
                thumbnailBitmap = null,
                landscapeThumbnailBitmap = null,
                nativeTempPath = null,
                croppedTempPath = null,
                nativeExportInfo = null,
                croppedExportInfo = null,
                savedNativeUri = null,
                savedCroppedUri = null,
                saveMessage = null,
            )
        }

        viewModelScope.launch {
            val app: android.app.Application = getApplication()
            val nativeFile = File(nativePath)
            val croppedFile = File(croppedPath)

            val isFront = cameraManager.useFrontCamera.value
            val beauty = isFront && _uiState.value.settings.frontCameraEffect
            val mirror = isFront
            val needsTransform = beauty || mirror

            val sourceNative: File = if (needsTransform) {
                val processed = FileStorage.createExportFile(app, "tmp_V")
                WatermarkHelper.applyEffects(
                    app, nativeFile, processed,
                    applyWatermark = false, applyBeauty = beauty, mirrorHorizontally = mirror,
                ) ?: nativeFile
            } else nativeFile

            val sourceCropped: File = if (needsTransform) {
                val processed = FileStorage.createExportFile(app, "tmp_H")
                WatermarkHelper.applyEffects(
                    app, croppedFile, processed,
                    applyWatermark = false, applyBeauty = beauty, mirrorHorizontally = mirror,
                ) ?: croppedFile
            } else croppedFile

            val uriN = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourceNative, nativeFile.name)
            }
            val uriC = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourceCropped, croppedFile.name)
            }

            withContext(Dispatchers.IO) {
                FileStorage.deleteTempFile(sourceNative)
                FileStorage.deleteTempFile(sourceCropped)
                FileStorage.deleteTempFile(nativeFile)
                FileStorage.deleteTempFile(croppedFile)
            }

            val success = uriN != null && uriC != null
            Log.i(TAG, "Auto-save completed: success=$success")
            _uiState.update {
                if (success) it.copy(showAutoSaveCompleteToast = true)
                else it.copy(showAutoSaveFailToast = true)
            }
        }
    }

    fun dismissAutoSaveToasts() {
        _uiState.update {
            it.copy(showAutoSaveCompleteToast = false, showAutoSaveFailToast = false)
        }
    }

    /** Map a single effect pass's 0..1 progress into its slice of the save bar. */
    private fun publishSaveProgress(start: Float, end: Float, passProgress: Float) {
        val mapped = start + (end - start) * passProgress.coerceIn(0f, 1f)
        _uiState.update { it.copy(saveProgress = mapped) }
    }

    // ── Explicit save actions ────────────────────────────────────────

    /**
     * Save both exports to gallery WITH watermark (free-tier path).
     * Watermark is applied via Media3 Transformer text overlay.
     */
    /**
     * Save both exports. If PRO is owned, save without watermark.
     * Otherwise save with watermark.
     */
    private fun canSave(): Boolean {
        val status = _uiState.value.appStatus
        return status == AppStatus.EXPORT_COMPLETE || status == AppStatus.ERROR
    }

    fun saveBothWithWatermark() {
        val state = _uiState.value
        if (!canSave()) return

        val app: android.app.Application = getApplication()

        // Storage check
        val nativeFile = state.nativeTempPath?.let { File(it) }
        val croppedFile = state.croppedTempPath?.let { File(it) }
        val requiredBytes = (nativeFile?.length() ?: 0L) + (croppedFile?.length() ?: 0L) + 50_000_000L
        if (!FileStorage.hasEnoughStorage(app, requiredBytes)) {
            setError(app.getString(com.sunnlab.dualframe.R.string.error_storage_full))
            return
        }

        // PRO users get clean save automatically
        if (com.dualframe.monetize.ProEntitlement.isProOwned(app)) {
            saveBothClean()
            return
        }

        val nativePath = state.nativeTempPath ?: return
        val croppedPath = state.croppedTempPath ?: return

        _uiState.update { it.copy(appStatus = AppStatus.SAVING, saveMessage = null, saveProgress = 0f) }

        viewModelScope.launch {
            val app: android.app.Application = getApplication()
            val nativeFile = File(nativePath)
            val croppedFile = File(croppedPath)

            val isFront = cameraManager.useFrontCamera.value
            val beauty = isFront && _uiState.value.settings.frontCameraEffect
            val mirror = isFront
            val wmNative = FileStorage.createExportFile(app, "wm_V")
            val wmCropped = FileStorage.createExportFile(app, "wm_H")

            // Two sequential full transcodes mapped onto one 0..1 bar, leaving the
            // tail for the MediaStore writes. See SAVE_PASS_* in the companion.
            val wmNativeResult = WatermarkHelper.applyEffects(
                app, nativeFile, wmNative,
                applyWatermark = true, applyBeauty = beauty, mirrorHorizontally = mirror,
                onProgress = { p -> publishSaveProgress(SAVE_PASS_1_START, SAVE_PASS_1_END, p) },
            )
            val wmCroppedResult = WatermarkHelper.applyEffects(
                app, croppedFile, wmCropped,
                applyWatermark = true, applyBeauty = beauty, mirrorHorizontally = mirror,
                onProgress = { p -> publishSaveProgress(SAVE_PASS_2_START, SAVE_PASS_2_END, p) },
            )

            val sourceNative = wmNativeResult ?: nativeFile
            val sourceCropped = wmCroppedResult ?: croppedFile

            _uiState.update { it.copy(saveMessage = null, saveProgress = SAVE_PASS_2_END) }

            val uriN = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourceNative, nativeFile.name)
            }
            _uiState.update { it.copy(saveProgress = SAVE_MEDIASTORE_MID) }
            val uriC = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourceCropped, croppedFile.name)
            }
            _uiState.update { it.copy(saveProgress = 1f) }

            // Clean watermarked temp files only — keep originals for potential re-save
            if (uriN != null && uriC != null) {
                withContext(Dispatchers.IO) {
                    FileStorage.deleteTempFile(sourceNative)
                    FileStorage.deleteTempFile(sourceCropped)
                }
            }

            _uiState.update {
                it.copy(
                    appStatus = AppStatus.EXPORT_COMPLETE,
                    savedNativeUri = uriN,
                    savedCroppedUri = uriC,
                    saveMessage = if (uriN != null && uriC != null) "SavedWithWatermark"
                        else getApplication<android.app.Application>().getString(com.sunnlab.dualframe.R.string.error_save_failed),
                )
            }
        }
    }

    /**
     * Save both exports to gallery WITHOUT watermark (rewarded/PRO path).
     */
    fun saveBothClean() {
        val state = _uiState.value
        if (!canSave() && state.appStatus != AppStatus.SAVING) return
        val nativePath = state.nativeTempPath ?: return
        val croppedPath = state.croppedTempPath ?: return

        _uiState.update { it.copy(appStatus = AppStatus.SAVING, saveMessage = null, saveProgress = 0f) }

        viewModelScope.launch {
            val app: android.app.Application = getApplication()
            val nativeFile = File(nativePath)
            val croppedFile = File(croppedPath)

            val isFront = cameraManager.useFrontCamera.value
            val beauty = isFront && _uiState.value.settings.frontCameraEffect
            val mirror = isFront
            val needsTransform = beauty || mirror

            // Rear-camera clean saves are a plain copy — no transcode, so the bar
            // simply jumps to the MediaStore tail below.
            val sourceNative: File = if (needsTransform) {
                val processed = FileStorage.createExportFile(app, "tmp_V")
                WatermarkHelper.applyEffects(
                    app, nativeFile, processed,
                    applyWatermark = false, applyBeauty = beauty, mirrorHorizontally = mirror,
                    onProgress = { p -> publishSaveProgress(SAVE_PASS_1_START, SAVE_PASS_1_END, p) },
                ) ?: nativeFile
            } else nativeFile

            val sourceCropped: File = if (needsTransform) {
                val processed = FileStorage.createExportFile(app, "tmp_H")
                WatermarkHelper.applyEffects(
                    app, croppedFile, processed,
                    applyWatermark = false, applyBeauty = beauty, mirrorHorizontally = mirror,
                    onProgress = { p -> publishSaveProgress(SAVE_PASS_2_START, SAVE_PASS_2_END, p) },
                ) ?: croppedFile
            } else croppedFile

            _uiState.update { it.copy(saveProgress = SAVE_PASS_2_END) }

            val uriN = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourceNative, nativeFile.name)
            }
            _uiState.update { it.copy(saveProgress = SAVE_MEDIASTORE_MID) }
            val uriC = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourceCropped, croppedFile.name)
            }
            _uiState.update { it.copy(saveProgress = 1f) }

            if (uriN != null && uriC != null) {
                withContext(Dispatchers.IO) {
                    FileStorage.deleteTempFile(sourceNative)
                    FileStorage.deleteTempFile(sourceCropped)
                    FileStorage.deleteTempFile(nativeFile)
                    FileStorage.deleteTempFile(croppedFile)
                }
            }

            _uiState.update {
                it.copy(
                    appStatus = AppStatus.EXPORT_COMPLETE,
                    savedNativeUri = uriN,
                    savedCroppedUri = uriC,
                    saveMessage = if (uriN != null && uriC != null) "Saved"
                        else getApplication<android.app.Application>().getString(com.sunnlab.dualframe.R.string.error_save_failed),
                )
            }
        }
    }

    fun onAdRewarded() {
        val app: android.app.Application = getApplication()
        Log.i(TAG, "Ad watched — saving without watermark")
        com.dualframe.monetize.AdGraceManager.resetGrace(app)
        saveBothClean()
    }

    fun onAdFailed() {
        val app: android.app.Application = getApplication()
        val online = com.dualframe.monetize.AdGraceManager.isOnline(app)
        Log.i(TAG, "Ad failed — online=$online")

        if (!online) {
            Log.i(TAG, "Offline — showing offline dialog")
            _uiState.update { it.copy(adFailDialog = com.dualframe.data.AdFailType.OFFLINE) }
            return
        }

        if (com.dualframe.monetize.AdGraceManager.canUseGrace(app)) {
            Log.i(TAG, "Grace save — saving without watermark (1st failure in 24h)")
            com.dualframe.monetize.AdGraceManager.useGrace(app)
            saveBothClean()
            android.widget.Toast.makeText(app,
                app.getString(com.sunnlab.dualframe.R.string.error_ad_grace_title) + "\n" +
                app.getString(com.sunnlab.dualframe.R.string.error_ad_grace_desc),
                android.widget.Toast.LENGTH_LONG).show()
            return
        }

        Log.i(TAG, "Repeated ad failure — showing options dialog")
        _uiState.update { it.copy(adFailDialog = com.dualframe.data.AdFailType.REPEATED_FAILURE) }
    }

    fun dismissAdFailDialog() {
        _uiState.update { it.copy(adFailDialog = null) }
    }

    fun showRemoveWatermarkDialog() {
        _uiState.update { it.copy(showRemoveWatermarkDialog = true) }
    }

    fun dismissRemoveWatermarkDialog() {
        _uiState.update { it.copy(showRemoveWatermarkDialog = false) }
    }

    // ── Thumbnail ─────────────────────────────────────────────────────

    private suspend fun generateThumbnail(videoFile: File): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            // Get a frame from 1 second in (or start if shorter)
            retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate thumbnail", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    // ── Post-export actions (removed auto-save intents) ─────────────

    // ── Error handling ────────────────────────────────────────────────

    private fun setError(message: String) {
        Log.e(TAG, message)
        _uiState.update { it.copy(errorMessage = message, appStatus = AppStatus.ERROR) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
        if (_uiState.value.appStatus == AppStatus.ERROR) {
            _uiState.update { it.copy(appStatus = AppStatus.IDLE) }
        }
    }

    fun resetToIdle() {
        _uiState.update {
            it.copy(
                appStatus = AppStatus.IDLE,
                recordingDurationSeconds = 0,
                countdownRemaining = 0,
                exportProgress = 0f,
                errorMessage = null,
                endedEarlyDueToStorage = false,
                remainingRecordingSeconds = null,
            )
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopTimer()
        countdownJob?.cancel()
        cameraManager.release()
    }
}

/** Map user-facing VideoQuality enum to CameraX Quality constant. */
private fun VideoQuality.toCameraXQuality(): Quality = when (this) {
    VideoQuality.UHD -> Quality.UHD
    VideoQuality.FHD -> Quality.FHD
    VideoQuality.HD -> Quality.HD
}

/** Build a clean user-facing output line like "9:16 · 1080x1920 · 30.0 fps". */
private fun buildOutputLine(label: String, resolution: String, fps: String): String {
    val parts = mutableListOf(label)
    if (resolution.isNotEmpty()) parts.add(resolution)
    if (fps != "unknown") parts.add(fps)
    return parts.joinToString(" · ")
}
