package com.dualframe.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.camera.video.Quality
import com.dualframe.camera.CameraManager
import com.dualframe.data.AppSettings
import com.dualframe.data.AppStatus
import com.dualframe.data.FrameRate
import com.dualframe.data.SettingsStore
import com.dualframe.data.UiState
import com.dualframe.data.VideoQuality
import com.dualframe.util.VideoMetadata
import com.dualframe.export.ExportManager
import com.dualframe.util.FileStorage
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

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val settings = _uiState.value.settings
        viewModelScope.launch {
            val success = cameraManager.bindCamera(
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                targetFps = settings.frameRate.fps,
                quality = settings.videoQuality.toCameraXQuality(),
                onError = { msg -> setError(msg) },
            )
            _uiState.update { it.copy(cameraReady = success) }
            refreshSupportedCapabilities()
        }
    }

    /** Switch front/back camera. Only allowed when not recording. */
    fun switchCamera() {
        if (cameraManager.isRecording) return
        _uiState.update { it.copy(cameraReady = false) }
        viewModelScope.launch {
            val success = cameraManager.switchCamera(
                onError = { msg -> setError(msg) },
            )
            _uiState.update { it.copy(cameraReady = success) }
            // Camera changed — refresh capabilities and auto-correct settings
            refreshSupportedCapabilities()
        }
    }

    /**
     * Query the current camera's supported quality/fps and update UiState.
     * If the current settings are no longer valid, auto-correct to safe defaults.
     */
    private fun refreshSupportedCapabilities() {
        val supportedCxQualities = cameraManager.getSupportedQualities()
        val supportedFpsValues = cameraManager.getSupportedFpsValues()

        // Map CameraX Quality → our VideoQuality enum
        val supportedQualities = VideoQuality.entries.filter { vq ->
            vq.toCameraXQuality() in supportedCxQualities
        }.ifEmpty { listOf(VideoQuality.FHD) }

        // Determine which frame rates the sensor supports.
        // Conservative: for UHD, we exclude 60fps even if sensor claims it,
        // because most encoders can't sustain UHD@60fps. This is noted in the UI
        // as the option being disabled rather than silently falling back.
        val currentQuality = _uiState.value.settings.videoQuality
        val supportedRates = FrameRate.entries.filter { fr ->
            when (fr) {
                FrameRate.AUTO -> true // always available
                else -> {
                    val sensorSupports = fr.fps in supportedFpsValues
                    // UHD + 60fps is unreliable on nearly all devices
                    val encoderLikely = !(currentQuality == VideoQuality.UHD && fr.fps >= 60)
                    sensorSupports && encoderLikely
                }
            }
        }.ifEmpty { listOf(FrameRate.AUTO) }

        // Auto-correct current settings if they became unsupported
        var settings = _uiState.value.settings
        if (settings.videoQuality !in supportedQualities) {
            settings = settings.copy(videoQuality = supportedQualities.first())
            Log.i(TAG, "Auto-corrected quality to ${settings.videoQuality}")
        }
        if (settings.frameRate !in supportedRates) {
            settings = settings.copy(frameRate = FrameRate.AUTO)
            Log.i(TAG, "Auto-corrected frame rate to AUTO")
        }

        _uiState.update {
            it.copy(
                settings = settings,
                supportedQualities = supportedQualities,
                supportedFrameRates = supportedRates,
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

        // If quality changed, refresh fps options (some fps may now be unsupported)
        if (newSettings.videoQuality != oldSettings.videoQuality) {
            refreshSupportedCapabilities()
        }

        // If quality or fps changed and not recording, rebind camera immediately
        val currentSettings = _uiState.value.settings // may have been auto-corrected
        if (currentSettings.frameRate != oldSettings.frameRate ||
            currentSettings.videoQuality != oldSettings.videoQuality
        ) {
            viewModelScope.launch {
                val success = cameraManager.rebindWithSettings(
                    newFps = currentSettings.frameRate.fps,
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
                landscape16x9Uri = null,
                portrait9x16Uri = null,
                thumbnailBitmap = null,
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
        clearError()
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                landscape16x9Uri = null,
                portrait9x16Uri = null,
                thumbnailBitmap = null,
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
     * Orientation-native export pipeline.
     *
     * Determines the master file's actual display orientation, then:
     * 1. Exports the "native" framing first (matches master → passthrough / minimal crop)
     * 2. Exports the "cropped" framing second (heavy center-crop to the other aspect)
     *
     * Portrait master (phone held upright) → 9:16 native, 16:9 cropped
     * Landscape master (rare, phone sideways) → 16:9 native, 9:16 cropped
     */
    private fun startExportPipeline() {
        val file = masterFile ?: run {
            setError("No master file to export")
            return
        }

        viewModelScope.launch {
            // Detect native orientation from the actual master file
            val isPortrait = withContext(Dispatchers.IO) {
                exportManager.isMasterPortrait(file)
            }

            // Determine export order: native first, then derived
            val nativeAspect: Float
            val nativeSuffix: String
            val nativeLabel: String
            val croppedAspect: Float
            val croppedSuffix: String
            val croppedLabel: String

            if (isPortrait) {
                nativeAspect = ExportManager.ASPECT_9x16
                nativeSuffix = "9x16"
                nativeLabel = "9:16"
                croppedAspect = ExportManager.ASPECT_16x9
                croppedSuffix = "16x9"
                croppedLabel = "16:9"
            } else {
                nativeAspect = ExportManager.ASPECT_16x9
                nativeSuffix = "16x9"
                nativeLabel = "16:9"
                croppedAspect = ExportManager.ASPECT_9x16
                croppedSuffix = "9x16"
                croppedLabel = "9:16"
            }

            // ── Step 1: Export native framing (passthrough / minimal crop) ──
            _uiState.update { it.copy(appStatus = AppStatus.EXPORTING_NATIVE, exportProgress = 0f) }

            val nativeFile = exportManager.exportToAspect(file, nativeAspect, nativeSuffix) { progress ->
                _uiState.update { it.copy(exportProgress = progress) }
            }
            if (nativeFile == null) {
                setError("$nativeLabel export failed")
                _uiState.update { it.copy(appStatus = AppStatus.ERROR) }
                return@launch
            }

            val nativeName = nativeFile.nameWithoutExtension + ".mp4"
            val nativeUri = FileStorage.saveToMediaStore(getApplication(), nativeFile, nativeName)

            // Read verified metadata from the native export (actual resolution + fps)
            val nativeMeta = withContext(Dispatchers.IO) { VideoMetadata.fromFile(nativeFile) }
            val nativeRes = nativeMeta?.let { "${it.displayWidth}x${it.displayHeight}" } ?: ""
            val nativeFps = withContext(Dispatchers.IO) { VideoMetadata.readActualFps(nativeFile) }

            // Store native URI under the correct aspect key
            if (isPortrait) {
                _uiState.update { it.copy(portrait9x16Uri = nativeUri) }
            } else {
                _uiState.update { it.copy(landscape16x9Uri = nativeUri) }
            }

            // ── Step 2: Export cropped/derived framing ──
            _uiState.update { it.copy(appStatus = AppStatus.EXPORTING_CROPPED, exportProgress = 0f) }

            val croppedFile = exportManager.exportToAspect(file, croppedAspect, croppedSuffix) { progress ->
                _uiState.update { it.copy(exportProgress = progress) }
            }
            if (croppedFile == null) {
                setError("$croppedLabel export failed")
                _uiState.update { it.copy(appStatus = AppStatus.ERROR) }
                return@launch
            }

            val croppedName = croppedFile.nameWithoutExtension + ".mp4"
            val croppedUri = FileStorage.saveToMediaStore(getApplication(), croppedFile, croppedName)
            val croppedMeta = withContext(Dispatchers.IO) { VideoMetadata.fromFile(croppedFile) }
            val croppedRes = croppedMeta?.let { "${it.displayWidth}x${it.displayHeight}" } ?: ""
            val croppedFps = withContext(Dispatchers.IO) { VideoMetadata.readActualFps(croppedFile) }

            if (isPortrait) {
                _uiState.update { it.copy(landscape16x9Uri = croppedUri) }
            } else {
                _uiState.update { it.copy(portrait9x16Uri = croppedUri) }
            }

            val thumbnail = generateThumbnail(nativeFile)

            // Developer-facing log with native/cropped distinction
            Log.i(TAG, "Export complete: " +
                "native=$nativeLabel $nativeRes $nativeFps, " +
                "derived=$croppedLabel $croppedRes $croppedFps")

            // User-facing labels: simple, no technical jargon
            _uiState.update {
                it.copy(
                    appStatus = AppStatus.EXPORT_COMPLETE,
                    exportProgress = 1f,
                    thumbnailBitmap = thumbnail,
                    nativeExportInfo = buildOutputLine(nativeLabel, nativeRes, nativeFps),
                    croppedExportInfo = buildOutputLine(croppedLabel, croppedRes, croppedFps),
                )
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

    // ── Post-export actions ───────────────────────────────────────────

    /** Build an ACTION_VIEW intent for the latest exported file. */
    fun buildOpenIntent(): Intent? {
        val uri = _uiState.value.portrait9x16Uri ?: _uiState.value.landscape16x9Uri ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Build an ACTION_SEND intent to share the latest export. */
    fun buildShareIntent(): Intent? {
        val uri = _uiState.value.portrait9x16Uri ?: _uiState.value.landscape16x9Uri ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

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
