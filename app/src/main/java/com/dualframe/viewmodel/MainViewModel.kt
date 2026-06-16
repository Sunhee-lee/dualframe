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
    private var isToggling = false

    fun toggleRecording(hasAudioPermission: Boolean) {
        if (isToggling) return
        isToggling = true
        try {
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
        } finally {
            isToggling = false
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
                nativeSuffix = "portrait"
                nativeLabel = "9:16"
                croppedAspect = ExportManager.ASPECT_16x9
                croppedSuffix = "landscape"
                croppedLabel = "16:9"
            } else {
                nativeSuffix = "landscape"
                nativeLabel = "16:9"
                croppedAspect = ExportManager.ASPECT_9x16
                croppedSuffix = "portrait"
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

            // Auto-save: if PRO + autoSave enabled, save immediately
            val currentSettings = _uiState.value.settings
            if (currentSettings.autoSave &&
                com.dualframe.monetize.ProEntitlement.isProOwned(getApplication())) {
                Log.i(TAG, "Auto-save triggered")
                saveBothWithWatermark()
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

        _uiState.update { it.copy(appStatus = AppStatus.SAVING, saveMessage = null) }

        viewModelScope.launch {
            val app: android.app.Application = getApplication()
            val nativeFile = File(nativePath)
            val croppedFile = File(croppedPath)

            val isFront = cameraManager.useFrontCamera.value
            val beauty = isFront && _uiState.value.settings.frontCameraEffect
            val mirror = isFront
            val wmNative = FileStorage.createExportFile(app, "wm_portrait")
            val wmCropped = FileStorage.createExportFile(app, "wm_landscape")

            val wmNativeResult = WatermarkHelper.applyEffects(
                app, nativeFile, wmNative,
                applyWatermark = true, applyBeauty = beauty, mirrorHorizontally = mirror,
            )
            val wmCroppedResult = WatermarkHelper.applyEffects(
                app, croppedFile, wmCropped,
                applyWatermark = true, applyBeauty = beauty, mirrorHorizontally = mirror,
            )

            val sourceNative = wmNativeResult ?: nativeFile
            val sourceCropped = wmCroppedResult ?: croppedFile

            _uiState.update { it.copy(saveMessage = null) }

            val uriN = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourceNative, nativeFile.name)
            }
            val uriC = withContext(Dispatchers.IO) {
                FileStorage.saveToMediaStore(app, sourceCropped, croppedFile.name)
            }

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
     * Save both exports to gallery WITHOUT watermark (rewarded/PRO path).
     */
    fun saveBothClean() {
        val state = _uiState.value
        if (!canSave() && state.appStatus != AppStatus.SAVING) return
        val nativePath = state.nativeTempPath ?: return
        val croppedPath = state.croppedTempPath ?: return

        _uiState.update { it.copy(appStatus = AppStatus.SAVING, saveMessage = null) }

        viewModelScope.launch {
            val app: android.app.Application = getApplication()
            val nativeFile = File(nativePath)
            val croppedFile = File(croppedPath)

            val isFront = cameraManager.useFrontCamera.value
            val beauty = isFront && _uiState.value.settings.frontCameraEffect
            val mirror = isFront
            val needsTransform = beauty || mirror

            val sourceNative: File = if (needsTransform) {
                val processed = FileStorage.createExportFile(app, "tmp_portrait")
                WatermarkHelper.applyEffects(
                    app, nativeFile, processed,
                    applyWatermark = false, applyBeauty = beauty, mirrorHorizontally = mirror,
                ) ?: nativeFile
            } else nativeFile

            val sourceCropped: File = if (needsTransform) {
                val processed = FileStorage.createExportFile(app, "tmp_landscape")
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
