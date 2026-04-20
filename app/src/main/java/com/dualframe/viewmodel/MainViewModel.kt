package com.dualframe.viewmodel

import android.app.Application
import android.content.Intent
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
    }

    // ── Camera ────────────────────────────────────────────────────────

    /**
     * Bind camera with GPU dual preview. No PreviewView needed — the renderer
     * provides its own SurfaceTexture to CameraX. Always 2 use cases.
     */
    fun bindCamera(lifecycleOwner: LifecycleOwner) {
        val settings = _uiState.value.settings
        viewModelScope.launch {
            val success = cameraManager.bindCamera(
                lifecycleOwner = lifecycleOwner,
                quality = settings.videoQuality.toCameraXQuality(),
                onError = { msg -> setError(msg) },
            )
            _uiState.update { it.copy(cameraReady = success) }
            refreshSupportedCapabilities()
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
            refreshSupportedCapabilities()
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
        if (settings.videoQuality !in supportedQualities) {
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

        // If quality changed, refresh supported options and rebind camera
        if (newSettings.videoQuality != oldSettings.videoQuality) {
            refreshSupportedCapabilities()
            val currentSettings = _uiState.value.settings
            viewModelScope.launch {
                val success = cameraManager.rebindWithQuality(
                    newQuality = currentSettings.videoQuality.toCameraXQuality(),
                    onError = { msg -> setError(msg) },
                )
                _uiState.update { it.copy(cameraReady = success) }
            }
        }
    }

    // ── Recording ─────────────────────────────────────────────────────

    /**
     * Called when the user taps the record button.
     * If recording, stops. If idle, starts (with optional countdown).
     */
    fun toggleRecording(hasAudioPermission: Boolean) {
        val status = _uiState.value.appStatus
        when {
            status == AppStatus.RECORDING -> stopRecording()
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
    }

    private fun startCountdown(seconds: Int, hasAudioPermission: Boolean) {
        clearError()
        _uiState.update {
            it.copy(
                appStatus = AppStatus.COUNTDOWN,
                countdownRemaining = seconds,
                thumbnailBitmap = null,
                portraitExportInfo = null,
                landscapeExportInfo = null,
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
        clearError()
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                thumbnailBitmap = null,
                portraitExportInfo = null,
                landscapeExportInfo = null,
            )
        }

        // No rebind needed — always 2 use cases (Preview + VideoCapture).
        // Both previews stay live via GPU renderer during recording.
        val file = cameraManager.startRecording(
            audioEnabled = settings.audioEnabled,
            hasAudioPermission = hasAudioPermission,
            onEvent = ::onRecordingEvent,
        )

        if (file == null) {
            setError("Failed to start recording")
            return
        }

        masterFile = file
        _uiState.update { it.copy(appStatus = AppStatus.RECORDING, recordingDurationSeconds = 0) }
        startTimer()
    }

    private fun stopRecording() {
        cameraManager.stopRecording()
        stopTimer()
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

                    _uiState.update { it.copy(masterFilePath = masterFile?.absolutePath) }
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
                _uiState.update { it.copy(recordingDurationSeconds = it.recordingDurationSeconds + 1) }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    // ── Export pipeline ───────────────────────────────────────────────

    /**
     * Full-sensor export pipeline: both 9:16 portrait and 16:9 landscape are
     * derived as crops from the full-sensor master. ExportManager handles the
     * identity-crop optimization internally (falls back to file copy when the
     * source aspect already matches the target, e.g. on 16:9 sensor + portrait
     * device the 9:16 crop is lossless).
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

            // ── Step 1: Portrait (9:16) crop from full-sensor master ──
            _uiState.update { it.copy(appStatus = AppStatus.EXPORTING_PORTRAIT, exportProgress = 0f) }

            val portraitFile = exportManager.exportCropped(
                masterFile = file,
                targetAspect = ExportManager.ASPECT_9x16,
                fileSuffix = "portrait",
                verticalOffset = 0f,
            ) { progress ->
                _uiState.update { it.copy(exportProgress = progress) }
            }
            if (portraitFile == null) {
                setError("9:16 export failed")
                _uiState.update { it.copy(appStatus = AppStatus.ERROR) }
                return@launch
            }

            val portraitMeta = withContext(Dispatchers.IO) { VideoMetadata.fromFile(portraitFile) }
            val portraitRes = portraitMeta?.let { "${it.displayWidth}x${it.displayHeight}" } ?: ""
            val portraitFps = withContext(Dispatchers.IO) { VideoMetadata.readActualFps(portraitFile) }
            Log.i("DualFrameCameraDiag", "Portrait export (9:16): file=${portraitFile.name}")
            Log.i("DualFrameCameraDiag", "  display: ${portraitMeta?.displayWidth}x${portraitMeta?.displayHeight}")
            Log.i("DualFrameCameraDiag", "  fps: $portraitFps, size: ${portraitFile.length()/1024}KB")

            // ── Step 2: Landscape (16:9) crop from full-sensor master ──
            _uiState.update { it.copy(appStatus = AppStatus.EXPORTING_LANDSCAPE, exportProgress = 0f) }

            val cropOffset = cameraManager.renderer.landscapeCropOffsetY
            Log.i("DualFrameCameraDiag", "Landscape crop vertical offset: ${"%.3f".format(cropOffset)}")
            val landscapeFile = exportManager.exportCropped(
                masterFile = file,
                targetAspect = ExportManager.ASPECT_16x9,
                fileSuffix = "landscape",
                verticalOffset = cropOffset,
            ) { progress ->
                _uiState.update { it.copy(exportProgress = progress) }
            }
            if (landscapeFile == null) {
                setError("16:9 export failed")
                _uiState.update { it.copy(appStatus = AppStatus.ERROR) }
                return@launch
            }

            val landscapeMeta = withContext(Dispatchers.IO) { VideoMetadata.fromFile(landscapeFile) }
            val landscapeRes = landscapeMeta?.let { "${it.displayWidth}x${it.displayHeight}" } ?: ""
            val landscapeFps = withContext(Dispatchers.IO) { VideoMetadata.readActualFps(landscapeFile) }
            Log.i("DualFrameCameraDiag", "Landscape export (16:9): file=${landscapeFile.name}")
            Log.i("DualFrameCameraDiag", "  display: ${landscapeMeta?.displayWidth}x${landscapeMeta?.displayHeight}")
            Log.i("DualFrameCameraDiag", "  fps: $landscapeFps, size: ${landscapeFile.length()/1024}KB")
            Log.i("DualFrameCameraDiag", "==============================")

            val thumbnail = generateThumbnail(portraitFile)

            _uiState.update {
                it.copy(
                    appStatus = AppStatus.EXPORT_COMPLETE,
                    exportProgress = 1f,
                    thumbnailBitmap = thumbnail,
                    portraitExportInfo = buildOutputLine("9:16", portraitRes, portraitFps),
                    landscapeExportInfo = buildOutputLine("16:9", landscapeRes, landscapeFps),
                    portraitTempPath = portraitFile.absolutePath,
                    landscapeTempPath = landscapeFile.absolutePath,
                    savedPortraitUri = null,
                    savedLandscapeUri = null,
                    saveMessage = null,
                )
            }
        }
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
    fun saveBothWithWatermark() {
        val state = _uiState.value
        if (state.appStatus != AppStatus.EXPORT_COMPLETE) return

        // PRO users get clean save automatically
        if (com.dualframe.monetize.ProEntitlement.isProOwned(getApplication())) {
            saveBothClean()
            return
        }

        val portraitPath = state.portraitTempPath ?: return
        val landscapePath = state.landscapeTempPath ?: return

        _uiState.update { it.copy(appStatus = AppStatus.SAVING, saveMessage = null) }

        viewModelScope.launch {
            val app: android.app.Application = getApplication()
            val portraitFile = File(portraitPath)
            val landscapeFile = File(landscapePath)

            val isFront = cameraManager.useFrontCamera.value
            val beauty = isFront && _uiState.value.settings.frontCameraEffect
            // Mirror saved selfie when setting is OFF (user wants mirrored save)
            val mirror = isFront && !_uiState.value.settings.saveSelfieUnmirrored
            val wmPortrait = FileStorage.createExportFile(app, "wm_portrait")
            val wmLandscape = FileStorage.createExportFile(app, "wm_landscape")

            val wmPortraitResult = WatermarkHelper.applyEffects(
                app, portraitFile, wmPortrait,
                applyWatermark = true, applyBeauty = beauty, mirrorHorizontally = mirror,
            )
            val wmLandscapeResult = WatermarkHelper.applyEffects(
                app, landscapeFile, wmLandscape,
                applyWatermark = true, applyBeauty = beauty, mirrorHorizontally = mirror,
            )

            val sourcePortrait = wmPortraitResult ?: portraitFile
            val sourceLandscape = wmLandscapeResult ?: landscapeFile

            _uiState.update { it.copy(saveMessage = null) }

            val uriP = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourcePortrait, portraitFile.name)
            }
            val uriL = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourceLandscape, landscapeFile.name)
            }

            _uiState.update {
                it.copy(
                    appStatus = AppStatus.EXPORT_COMPLETE,
                    savedPortraitUri = uriP,
                    savedLandscapeUri = uriL,
                    saveMessage = if (uriP != null && uriL != null) "Saved" else "Save failed",
                )
            }
        }
    }

    /**
     * Save both exports to gallery WITHOUT watermark (rewarded/PRO path).
     */
    fun saveBothClean() {
        val state = _uiState.value
        if (state.appStatus != AppStatus.EXPORT_COMPLETE && state.appStatus != AppStatus.SAVING) return
        val portraitPath = state.portraitTempPath ?: return
        val landscapePath = state.landscapeTempPath ?: return

        _uiState.update { it.copy(appStatus = AppStatus.SAVING, saveMessage = null) }

        viewModelScope.launch {
            val app: android.app.Application = getApplication()
            val portraitFile = File(portraitPath)
            val landscapeFile = File(landscapePath)

            val isFront = cameraManager.useFrontCamera.value
            val beauty = isFront && _uiState.value.settings.frontCameraEffect
            val mirror = isFront && !_uiState.value.settings.saveSelfieUnmirrored
            val needsTransform = beauty || mirror

            val sourcePortrait: File = if (needsTransform) {
                val processed = FileStorage.createExportFile(app, "tmp_portrait")
                WatermarkHelper.applyEffects(
                    app, portraitFile, processed,
                    applyWatermark = false, applyBeauty = beauty, mirrorHorizontally = mirror,
                ) ?: portraitFile
            } else portraitFile

            val sourceLandscape: File = if (needsTransform) {
                val processed = FileStorage.createExportFile(app, "tmp_landscape")
                WatermarkHelper.applyEffects(
                    app, landscapeFile, processed,
                    applyWatermark = false, applyBeauty = beauty, mirrorHorizontally = mirror,
                ) ?: landscapeFile
            } else landscapeFile

            val uriP = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourcePortrait, portraitFile.name)
            }
            val uriL = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourceLandscape, landscapeFile.name)
            }

            _uiState.update {
                it.copy(
                    appStatus = AppStatus.EXPORT_COMPLETE,
                    savedPortraitUri = uriP,
                    savedLandscapeUri = uriL,
                    saveMessage = if (uriP != null && uriL != null) "Saved" else "Save failed",
                )
            }
        }
    }

    fun showRemoveWatermarkDialog() {
        _uiState.update { it.copy(showRemoveWatermarkDialog = true) }
    }

    fun dismissRemoveWatermarkDialog() {
        _uiState.update { it.copy(showRemoveWatermarkDialog = false) }
    }

    /**
     * Build intent to open the saved video in the system's default viewer.
     * Uses the saved content:// URI with FLAG_GRANT_READ_URI_PERMISSION.
     * Falls back to generic gallery if no saved URI is available.
     */
    fun buildOpenGalleryIntent(): Intent {
        val savedUri = _uiState.value.savedPortraitUri ?: _uiState.value.savedLandscapeUri
        return if (savedUri != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(savedUri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*")
            }
        }
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
