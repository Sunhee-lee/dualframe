package com.dualframe.viewmodel

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.dualframe.camera.CameraManager
import com.dualframe.data.AppSettings
import com.dualframe.data.AppStatus
import com.dualframe.data.SettingsStore
import com.dualframe.data.UiState
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
        viewModelScope.launch {
            cameraManager.bindCamera(
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                onError = { msg -> setError(msg) },
            )
            _uiState.update { it.copy(cameraReady = true) }
        }
    }

    // ── Settings ──────────────────────────────────────────────────────

    fun updateSettings(newSettings: AppSettings) {
        _uiState.update { it.copy(settings = newSettings) }
        SettingsStore.save(getApplication(), newSettings)
        Log.i(TAG, "Settings updated: $newSettings")
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
                landscape16x9Name = null,
                portrait9x16Name = null,
                thumbnailBitmap = null,
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
                landscape16x9Name = null,
                portrait9x16Name = null,
                thumbnailBitmap = null,
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

    private fun startExportPipeline() {
        val file = masterFile ?: run {
            setError("No master file to export")
            return
        }

        viewModelScope.launch {
            // Step 1: Export 16:9 landscape
            _uiState.update { it.copy(appStatus = AppStatus.EXPORTING_16x9, exportProgress = 0f) }

            val landscape = exportManager.exportLandscape(file) { progress ->
                _uiState.update { it.copy(exportProgress = progress) }
            }

            if (landscape == null) {
                setError("16:9 export failed")
                _uiState.update { it.copy(appStatus = AppStatus.ERROR) }
                return@launch
            }

            // Save 16:9 to gallery
            val landscapeName = landscape.nameWithoutExtension + ".mp4"
            val landscapeUri = FileStorage.saveToMediaStore(
                getApplication(), landscape, landscapeName
            )
            _uiState.update {
                it.copy(
                    landscape16x9Uri = landscapeUri,
                    landscape16x9Name = landscapeName,
                )
            }

            // Step 2: Export 9:16 portrait
            _uiState.update { it.copy(appStatus = AppStatus.EXPORTING_9x16, exportProgress = 0f) }

            val portrait = exportManager.exportPortrait(file) { progress ->
                _uiState.update { it.copy(exportProgress = progress) }
            }

            if (portrait == null) {
                setError("9:16 export failed")
                _uiState.update { it.copy(appStatus = AppStatus.ERROR) }
                return@launch
            }

            // Save 9:16 to gallery
            val portraitName = portrait.nameWithoutExtension + ".mp4"
            val portraitUri = FileStorage.saveToMediaStore(
                getApplication(), portrait, portraitName
            )

            // Generate thumbnail from the portrait export (9:16 is more recognizable as a thumbnail)
            val thumbnail = generateThumbnail(portrait)

            _uiState.update {
                it.copy(
                    appStatus = AppStatus.EXPORT_COMPLETE,
                    portrait9x16Uri = portraitUri,
                    portrait9x16Name = portraitName,
                    exportProgress = 1f,
                    thumbnailBitmap = thumbnail,
                )
            }

            Log.i(TAG, "Export pipeline complete")
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

    /**
     * Build an intent to show the folder containing exported files.
     *
     * Android doesn't reliably support opening a specific folder in Files/Gallery across
     * all OEMs. The most practical approach is to open the Videos collection in the
     * default media app, which surfaces our files since they're saved to MediaStore.
     */
    fun buildShowFolderIntent(): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            // Opens the default video gallery/collection
            data = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            type = "video/*"
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
