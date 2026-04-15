package com.dualframe.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dualframe.util.FileStorage
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Manages the CameraX pipeline: preview binding and video recording.
 *
 * Design decisions:
 * - Uses a single rear camera with one Preview + one VideoCapture use case.
 * - The PreviewView is shared by the Compose UI (two clipped views of the same surface).
 * - Quality selection: tries UHD first, falls back to FHD, then HIGHEST available.
 *   This gives us the best master file to crop from.
 * - Audio recording is attempted if permission is granted; the recording still works without it.
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var preview: Preview? = null

    /**
     * Bind camera preview and video capture to the given lifecycle.
     * Must be called from the main thread.
     *
     * @param lifecycleOwner Activity or Fragment lifecycle
     * @param previewView The PreviewView to render the camera feed into
     * @param onReady Called when the camera is bound and preview is live
     * @param onError Called if camera setup fails
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onError: (String) -> Unit,
    ) {
        try {
            val provider = getCameraProvider()
            cameraProvider = provider

            // Unbind any existing use cases
            provider.unbindAll()

            // Build Preview use case
            preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            // Build Recorder with quality fallback strategy
            // Prefer UHD for maximum crop headroom, fall back gracefully
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD),
                FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            // Bind to rear camera only
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                videoCapture,
            )

            Log.i(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            onError("Camera setup failed: ${e.message}")
        }
    }

    /**
     * Start recording to a master file.
     *
     * @param hasAudioPermission Whether RECORD_AUDIO permission was granted
     * @param onEvent Callback for recording events (start, status, finalize)
     * @return The File where the master recording will be saved
     */
    fun startRecording(
        hasAudioPermission: Boolean,
        onEvent: (VideoRecordEvent) -> Unit,
    ): File? {
        val capture = videoCapture ?: run {
            Log.e(TAG, "VideoCapture not initialized")
            return null
        }

        val outputFile = FileStorage.createMasterFile(context)
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        val pendingRecording = capture.output
            .prepareRecording(context, outputOptions)
            .let { pending ->
                if (hasAudioPermission) {
                    @Suppress("MissingPermission")
                    pending.withAudioEnabled()
                } else {
                    Log.w(TAG, "Recording without audio — permission not granted")
                    pending
                }
            }

        activeRecording = pendingRecording.start(
            ContextCompat.getMainExecutor(context),
            onEvent,
        )

        Log.i(TAG, "Recording started: ${outputFile.absolutePath}")
        return outputFile
    }

    /** Stop the active recording. The finalize event will fire via the onEvent callback. */
    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        Log.i(TAG, "Recording stop requested")
    }

    /** Release camera resources. Call when the screen is destroyed. */
    fun release() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        Log.i(TAG, "Camera released")
    }

    val isRecording: Boolean
        get() = activeRecording != null

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
}

/**
 * Companion fallback strategy — CameraX QualitySelector helper.
 * Defined at file level to keep the import clean.
 */
private typealias FallbackStrategy = androidx.camera.video.FallbackStrategy
