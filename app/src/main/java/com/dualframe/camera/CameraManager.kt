package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as Camera2Manager
import android.util.Log
import android.util.Range
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
 * Quality-priority architecture:
 * - Idle mode: Preview + VideoCapture + ImageAnalysis (3 use cases, dual live preview)
 * - Recording mode: Preview + VideoCapture only (2 use cases, full resolution for recorder)
 *
 * WHY: CameraX negotiates stream resolution across all bound use cases. With 3 use cases,
 * the camera HAL typically downgrades VideoCapture to HD (720p) even when FHD/UHD is
 * requested. Dropping ImageAnalysis during recording lets the HAL allocate full resolution
 * to the recorder. The secondary preview freezes on the last frame during recording but
 * the recorded master gets the user's selected quality.
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
        private val ANALYSIS_RESOLUTION_NORMAL = Size(640, 480)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val _useFrontCamera = MutableStateFlow(false)
    val useFrontCamera: StateFlow<Boolean> = _useFrontCamera.asStateFlow()

    private val _secondPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val secondPreviewBitmap: StateFlow<Bitmap?> = _secondPreviewBitmap.asStateFlow()

    private val _dualPreviewAvailable = MutableStateFlow(false)
    val dualPreviewAvailable: StateFlow<Boolean> = _dualPreviewAvailable.asStateFlow()

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var boundLifecycleOwner: LifecycleOwner? = null
    private var boundPreviewView: PreviewView? = null
    private var boundTargetFps: Int = 0
    private var boundQuality: Quality = Quality.FHD

    // ── Camera binding ────────────────────────────────────────────────

    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        targetFps: Int = 0,
        quality: Quality = Quality.FHD,
        onError: (String) -> Unit,
    ): Boolean {
        boundLifecycleOwner = lifecycleOwner
        boundPreviewView = previewView
        boundTargetFps = targetFps
        boundQuality = quality
        return bindWithAnalysis(lifecycleOwner, previewView, targetFps, quality, onError)
    }

    /**
     * Bind all 3 use cases: Preview + VideoCapture + ImageAnalysis.
     * Used during idle/preview mode for dual live preview.
     * VideoCapture resolution may be reduced by the HAL to accommodate 3 streams.
     */
    private suspend fun bindWithAnalysis(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        targetFps: Int,
        quality: Quality,
        onError: (String) -> Unit,
    ): Boolean {
        try {
            val provider = getCameraProvider()
            cameraProvider = provider
            provider.unbindAll()

            logDiagnostics("bindWithAnalysis", quality, targetFps)

            preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val recorder = buildRecorder(quality)
            videoCapture = buildVideoCapture(recorder, targetFps)

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(ANALYSIS_RESOLUTION_NORMAL)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        processSecondPreviewFrame(imageProxy)
                    }
                }

            val cameraSelector = currentCameraSelector()

            try {
                provider.bindToLifecycle(
                    lifecycleOwner, cameraSelector,
                    preview, videoCapture, imageAnalysis,
                )
                _dualPreviewAvailable.value = true
                Log.i(TAG, "Bound 3 use cases: Preview + VideoCapture + ImageAnalysis")
            } catch (e: Exception) {
                Log.w(TAG, "Can't bind 3 use cases, dropping ImageAnalysis", e)
                provider.unbindAll()
                imageAnalysis = null
                _dualPreviewAvailable.value = false
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
                Log.i(TAG, "Bound 2 use cases: Preview + VideoCapture (no dual preview)")
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed completely", e)
            onError("Camera setup failed: ${e.message}")
            return false
        }
    }

    /**
     * Rebind with only Preview + VideoCapture (drop ImageAnalysis).
     * Used when recording starts to give the HAL full resolution for the recorder.
     *
     * This is the critical quality fix: with 3 use cases bound, CameraX typically
     * downgrades VideoCapture to 720p. With only 2, FHD/UHD is preserved.
     */
    private suspend fun bindForRecording(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        targetFps: Int,
        quality: Quality,
    ): Boolean {
        try {
            val provider = cameraProvider ?: getCameraProvider().also { cameraProvider = it }
            provider.unbindAll()

            logDiagnostics("bindForRecording (2 use cases)", quality, targetFps)

            preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val recorder = buildRecorder(quality)
            videoCapture = buildVideoCapture(recorder, targetFps)

            // No ImageAnalysis — this is intentional to maximize recording resolution.
            // The secondary preview will show the last captured frame (frozen).
            imageAnalysis = null
            _dualPreviewAvailable.value = false

            provider.bindToLifecycle(
                lifecycleOwner, currentCameraSelector(),
                preview, videoCapture,
            )
            Log.i(TAG, "Recording bind: Preview + VideoCapture only (quality priority)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Recording bind failed", e)
            return false
        }
    }

    private fun buildRecorder(quality: Quality): Recorder {
        val qualitySelector = QualitySelector.from(
            quality,
            FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
        )
        // Bitrate targets ~2x CameraX defaults for better output quality.
        // The encoder may cap to its actual max; these are target requests.
        val targetBitrate = when (quality) {
            Quality.UHD -> 40_000_000
            Quality.FHD -> 16_000_000
            else -> 8_000_000
        }
        return Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .setTargetVideoEncodingBitRate(targetBitrate)
            .build()
    }

    private fun buildVideoCapture(recorder: Recorder, targetFps: Int): VideoCapture<Recorder> {
        val builder = VideoCapture.Builder(recorder)
        if (targetFps > 0) {
            builder.setTargetFrameRate(Range(targetFps, targetFps))
        }
        return builder.build()
    }

    private fun currentCameraSelector(): CameraSelector =
        if (_useFrontCamera.value) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

    private fun logDiagnostics(context: String, quality: Quality, targetFps: Int) {
        val camera = if (_useFrontCamera.value) "FRONT" else "REAR"
        val supported = getSupportedQualities()
        val fpsValues = getSupportedFpsValues()
        Log.i(TAG, "=== $context ===")
        Log.i(TAG, "  Camera: $camera")
        Log.i(TAG, "  Requested quality: $quality")
        Log.i(TAG, "  Requested fps: ${if (targetFps == 0) "AUTO" else targetFps}")
        Log.i(TAG, "  Supported qualities: $supported")
        Log.i(TAG, "  Supported fps values: $fpsValues")
    }

    // ── Camera switching ──────────────────────────────────────────────

    suspend fun switchCamera(onError: (String) -> Unit): Boolean {
        val owner = boundLifecycleOwner ?: return false
        val view = boundPreviewView ?: return false

        _useFrontCamera.value = !_useFrontCamera.value
        _secondPreviewBitmap.value = null
        Log.i(TAG, "Switching to ${if (_useFrontCamera.value) "front" else "back"} camera")

        return bindWithAnalysis(owner, view, boundTargetFps, boundQuality, onError)
    }

    suspend fun rebindWithSettings(
        newFps: Int,
        newQuality: Quality,
        onError: (String) -> Unit,
    ): Boolean {
        if (newFps == boundTargetFps && newQuality == boundQuality) return true
        if (isRecording) return true

        val owner = boundLifecycleOwner ?: return false
        val view = boundPreviewView ?: return false

        boundTargetFps = newFps
        boundQuality = newQuality
        Log.i(TAG, "Rebinding: quality=$newQuality, fps=$newFps")
        return bindWithAnalysis(owner, view, newFps, newQuality, onError)
    }

    // ── Recording mode transitions ────────────────────────────────────

    /**
     * Enter recording mode: rebind with 2 use cases for full-resolution recording.
     * The secondary preview freezes on the last frame but the master recording
     * gets the full selected quality (FHD/UHD) instead of being collapsed to HD.
     */
    suspend fun enterRecordingMode(): Boolean {
        val owner = boundLifecycleOwner ?: return false
        val view = boundPreviewView ?: return false
        return bindForRecording(owner, view, boundTargetFps, boundQuality)
    }

    /**
     * Exit recording mode: rebind with 3 use cases to restore dual live preview.
     */
    suspend fun exitRecordingMode(): Boolean {
        val owner = boundLifecycleOwner ?: return false
        val view = boundPreviewView ?: return false
        return bindWithAnalysis(owner, view, boundTargetFps, boundQuality) { }
    }

    // ── Frame processing ──────────────────────────────────────────────

    private fun processSecondPreviewFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
                val cropped = centerCrop(rotated, 9f / 16f)
                val output = if (_useFrontCamera.value) mirrorBitmap(cropped) else cropped

                _secondPreviewBitmap.value = output

                if (cropped !== output) cropped.recycle()
                if (rotated !== bitmap && rotated !== cropped) rotated.recycle()
                if (bitmap !== rotated && bitmap !== cropped && bitmap !== output) bitmap.recycle()
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

    private fun mirrorBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { preScale(-1f, 1f) }
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

    // ── Capability queries ──────────────────────────────────────────────

    fun getSupportedQualities(): List<Quality> {
        val provider = cameraProvider ?: return listOf(Quality.FHD, Quality.HD)
        val selector = currentCameraSelector()
        val supported = try {
            QualitySelector.getSupportedQualities(provider.getCameraInfo(selector))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot query supported qualities", e)
            return listOf(Quality.FHD, Quality.HD)
        }
        val result = listOf(Quality.UHD, Quality.FHD, Quality.HD).filter { it in supported }
        Log.i(TAG, "Supported qualities: $result")
        return result.ifEmpty { listOf(Quality.FHD, Quality.HD) }
    }

    fun getSupportedFpsValues(): Set<Int> {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager
            val facing = if (_useFrontCamera.value) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == facing) {
                    val ranges = chars.get(
                        CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                    ) ?: continue
                    return ranges.map { it.upper }.toSet()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot query fps capabilities", e)
        }
        return setOf(30)
    }

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
