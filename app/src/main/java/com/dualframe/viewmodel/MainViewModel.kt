package com.dualframe.viewmodel

import android.app.Application
import android.util.Log
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.dualframe.camera.CameraManager
import com.dualframe.data.AppStatus
import com.dualframe.data.UiState
import com.dualframe.export.ExportManager
import com.dualframe.util.FileStorage
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Main ViewModel — single coordinator for the entire screen.
 *
 * Responsibilities:
 * 1. Camera lifecycle (bind/release)
 * 2. Recording start/stop
 * 3. Recording timer
 * 4. Post-recording export pipeline (16:9 then 9:16)
 * 5. Error surfacing
 * 6. UI state emission
 */
@UnstableApi
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val cameraManager = CameraManager(application)
    private val exportManager = ExportManager(application)

    private var masterFile: File? = null
    private var timerJob: Job? = null

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

    // ── Recording ─────────────────────────────────────────────────────

    fun toggleRecording(hasAudioPermission: Boolean) {
        if (cameraManager.isRecording) {
            stopRecording()
        } else {
            startRecording(hasAudioPermission)
        }
    }

    private fun startRecording(hasAudioPermission: Boolean) {
        clearError()
        _uiState.update {
            it.copy(
                hasAudioPermission = hasAudioPermission,
                masterFilePath = null,
                landscape16x9Path = null,
                portrait9x16Path = null,
            )
        }

        val file = cameraManager.startRecording(
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
        // State will update to EXPORTING when the finalize event fires
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
            is VideoRecordEvent.Status -> {
                // Recording is ongoing — timer handles the UI update
            }
            is VideoRecordEvent.Start -> {
                Log.i(TAG, "Recording started event received")
            }
            else -> { /* Pause/Resume — not used in MVP */ }
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
            // Step 1: Export 16:9
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
            val landscapeName = landscape.nameWithoutExtension
            val landscapeUri = FileStorage.saveToMediaStore(
                getApplication(), landscape, "$landscapeName.mp4"
            )
            _uiState.update { it.copy(landscape16x9Path = landscapeUri?.toString() ?: landscape.absolutePath) }

            // Step 2: Export 9:16
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
            val portraitName = portrait.nameWithoutExtension
            val portraitUri = FileStorage.saveToMediaStore(
                getApplication(), portrait, "$portraitName.mp4"
            )
            _uiState.update {
                it.copy(
                    appStatus = AppStatus.EXPORT_COMPLETE,
                    portrait9x16Path = portraitUri?.toString() ?: portrait.absolutePath,
                    exportProgress = 1f,
                )
            }

            Log.i(TAG, "Export pipeline complete")
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
                exportProgress = 0f,
                errorMessage = null,
            )
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopTimer()
        cameraManager.release()
    }
}
