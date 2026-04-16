package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Manages the CameraX pipeline: preview + secondary analysis feed + video recording.
 *
 * Supports front/back camera switching. Only one camera is active at a time.
 * Both cameras use the same dual-preview architecture:
 * - Preview use case → PreviewView (16:9 container)
 * - ImageAnalysis use case → bitmap frames cropped to 9:16
 * - VideoCapture use case → master recording
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
        private val ANALYSIS_RESOLUTION = Size(640, 480)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Currently selected camera lens facing
    private val _useFrontCamera = MutableStateFlow(false)
    val useFrontCamera: StateFlow<Boolean> = _useFrontCamera.asStateFlow()

    // Emits cropped 9:16 bitmaps for the secondary preview
    private val _secondPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val secondPreviewBitmap: StateFlow<Bitmap?> = _secondPreviewBitmap.asStateFlow()

    // Whether ImageAnalysis was successfully bound (true dual preview available)
    private val _dualPreviewAvailable = MutableStateFlow(false)
    val dualPreviewAvailable: StateFlow<Boolean> = _dualPreviewAvailable.asStateFlow()

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // Stored for rebinding on camera switch
    private var boundLifecycleOwner: LifecycleOwner? = null
    private var boundPreviewView: PreviewView? = null

    /**
     * Bind camera with preview, recording, and secondary analysis feed.
     * @return true if camera was bound successfully, false on total failure
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onError: (String) -> Unit,
    ): Boolean {
        // Store for rebinding on camera switch
        boundLifecycleOwner = lifecycleOwner
        boundPreviewView = previewView

        return bindCameraInternal(lifecycleOwner, previewView, onError)
    }

    private suspend fun bindCameraInternal(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onError: (String) -> Unit,
    ): Boolean {
        try {
            val provider = getCameraProvider()
            cameraProvider = provider
            provider.unbindAll()

            // 1. Build Preview use case
            preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            // 2. Build Recorder — prefer UHD for maximum crop headroom, with safe fallback.
            //    UHD (4K) gives the best export quality when center-cropping to 16:9 and 9:16.
            //    If UHD causes binding failures or isn't supported, CameraX falls back to FHD/HD.
            //    This is safer than hard-requiring UHD, which would crash on mid-range devices.
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD),
                FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
            )
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // 3. Build ImageAnalysis for secondary preview
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(ANALYSIS_RESOLUTION)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        processSecondPreviewFrame(imageProxy)
                    }
                }

            val cameraSelector = if (_useFrontCamera.value) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            // Try binding all 3 use cases. If that fails, drop ImageAnalysis.
            try {
                provider.bindToLifecycle(
                    lifecycleOwner, cameraSelector,
                    preview, videoCapture, imageAnalysis,
                )
                _dualPreviewAvailable.value = true
                Log.i(TAG, "Camera bound: Preview + VideoCapture + ImageAnalysis (dual preview)")
            } catch (e: Exception) {
                Log.w(TAG, "Can't bind 3 use cases, falling back to Preview + VideoCapture", e)
                provider.unbindAll()
                imageAnalysis = null
                _dualPreviewAvailable.value = false

                provider.bindToLifecycle(
                    lifecycleOwner, cameraSelector,
                    preview, videoCapture,
                )
                Log.i(TAG, "Camera bound: Preview + VideoCapture (fallback, no dual preview)")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed completely", e)
            onError("Camera setup failed: ${e.message}")
            return false
        }
    }

    /**
     * Switch between front and back camera. Rebinds all use cases.
     * Safe to call while idle — must NOT be called during recording.
     */
    suspend fun switchCamera(onError: (String) -> Unit): Boolean {
        val owner = boundLifecycleOwner ?: return false
        val view = boundPreviewView ?: return false

        _useFrontCamera.value = !_useFrontCamera.value
        _secondPreviewBitmap.value = null // Clear stale frame from previous camera
        Log.i(TAG, "Switching camera to ${if (_useFrontCamera.value) "front" else "back"}")

        return bindCameraInternal(owner, view, onError)
    }

    // ── Frame processing ──────────────────────────────────────────────

    private fun processSecondPreviewFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
                val cropped = centerCrop(rotated, 9f / 16f)

                // Emit the new frame. We do NOT recycle the previous bitmap because
                // Compose may still be drawing it on the main thread. At 640x480 ARGB_8888
                // (~1.2MB per frame), GC handles the turnover without issue.
                _secondPreviewBitmap.value = cropped

                // Recycle intermediates that are NOT the emitted bitmap
                if (rotated !== bitmap && rotated !== cropped) rotated.recycle()
                if (bitmap !== rotated && bitmap !== cropped) bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = imageProxy.width
        val height = imageProxy.height

        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + rowPadding / pixelStride

        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        return if (bitmapWidth != width) {
            val trimmed = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            trimmed
        } else {
            bitmap
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun centerCrop(bitmap: Bitmap, targetAspect: Float): Bitmap {
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        val srcAspect = srcW / srcH

        val cropW: Int
        val cropH: Int
        if (targetAspect > srcAspect) {
            cropW = bitmap.width
            cropH = (srcW / targetAspect).toInt()
        } else {
            cropH = bitmap.height
            cropW = (srcH * targetAspect).toInt()
        }

        val x = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
        val y = ((bitmap.height - cropH) / 2).coerceAtLeast(0)
        val safeW = cropW.coerceAtMost(bitmap.width - x)
        val safeH = cropH.coerceAtMost(bitmap.height - y)

        return Bitmap.createBitmap(bitmap, x, y, safeW, safeH)
    }

    // ── Recording ─────────────────────────────────────────────────────

    fun startRecording(
        audioEnabled: Boolean,
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
                if (audioEnabled && hasAudioPermission) {
                    @Suppress("MissingPermission")
                    pending.withAudioEnabled()
                } else {
                    if (!audioEnabled) Log.i(TAG, "Audio disabled by user setting")
                    else Log.w(TAG, "Audio enabled but permission not granted")
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

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        Log.i(TAG, "Recording stop requested")
    }

    fun release() {
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        analysisExecutor.shutdown()
        boundLifecycleOwner = null
        boundPreviewView = null
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

private typealias FallbackStrategy = androidx.camera.video.FallbackStrategy
