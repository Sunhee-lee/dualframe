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
 * Supports front/back camera switching. Only one camera is active at a time.
 * Both cameras use the same dual-preview architecture:
 * - Preview use case → PreviewView (16:9 container)
 * - ImageAnalysis use case → bitmap frames cropped to 9:16
 * - VideoCapture use case → master recording
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
        // Normal preview: 640x480 for smooth secondary preview
        private val ANALYSIS_RESOLUTION_NORMAL = Size(640, 480)
        // Recording mode: 320x240 to reduce CPU/memory pressure during recording
        private val ANALYSIS_RESOLUTION_RECORDING = Size(320, 240)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Recording-mode throttling state. When true, the analyzer skips frames
    // and uses lower resolution to reduce CPU pressure during recording.
    @Volatile
    private var isInRecordingMode = false
    @Volatile
    private var frameCounter = 0L

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

    // Stored for rebinding on camera/quality/fps switch
    private var boundLifecycleOwner: LifecycleOwner? = null
    private var boundPreviewView: PreviewView? = null
    private var boundTargetFps: Int = 0
    private var boundQuality: Quality = Quality.FHD

    /**
     * Bind camera with preview, recording, and secondary analysis feed.
     * @param targetFps Desired fps (0 = auto). Falls back if unsupported.
     * @param quality Desired recording quality. Falls back if unsupported.
     * @return true if camera was bound successfully, false on total failure
     */
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

        return bindCameraInternal(lifecycleOwner, previewView, targetFps, quality, onError)
    }

    private suspend fun bindCameraInternal(
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

            // 1. Build Preview use case
            preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            // 2. Build Recorder — user-selected quality with safe fallback.
            //    Both quality and fps are preferences: if the exact combo isn't supported
            //    (e.g., UHD+60fps on a front camera), CameraX negotiates the closest match.
            //    The fallback chain ensures the app never crashes due to unsupported combos.
            val qualitySelector = QualitySelector.from(
                quality,
                FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
            )

            // Target encoding bitrate — higher than CameraX defaults for better output quality.
            // These are practical targets; the encoder may cap to its actual max.
            // UHD default ~20Mbps → we request 40Mbps for sharper 4K.
            // FHD default ~8Mbps → we request 16Mbps for cleaner 1080p.
            // HD default ~4Mbps → we request 8Mbps for better 720p.
            val targetBitrate = when (quality) {
                Quality.UHD -> 40_000_000
                Quality.FHD -> 16_000_000
                else -> 8_000_000
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .setTargetVideoEncodingBitRate(targetBitrate)
                .build()
            Log.i(TAG, "Recorder configured: quality=$quality, target bitrate=${targetBitrate / 1_000_000}Mbps")

            // Apply target frame rate to VideoCapture if a specific fps was requested.
            // AUTO (fps=0) skips setTargetFrameRate, letting CameraX pick the best default.
            // For explicit fps, CameraX negotiates with the camera HAL and silently falls
            // back if the requested rate isn't supported — no crash, just a lower fps.
            val videoCaptureBuilder = VideoCapture.Builder(recorder)
            if (targetFps > 0) {
                videoCaptureBuilder.setTargetFrameRate(Range(targetFps, targetFps))
            }
            videoCapture = videoCaptureBuilder.build()

            // 3. Build ImageAnalysis for secondary preview
            // ImageAnalysis resolution is set to the normal size. During recording,
            // frame throttling in the analyzer reduces CPU cost without rebinding.
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

        return bindCameraInternal(owner, view, boundTargetFps, boundQuality, onError)
    }

    /**
     * Rebind with new fps and/or quality. Only rebinds if something actually changed
     * and the camera is not recording. Called by ViewModel on settings changes.
     */
    suspend fun rebindWithSettings(
        newFps: Int,
        newQuality: Quality,
        onError: (String) -> Unit,
    ): Boolean {
        if (newFps == boundTargetFps && newQuality == boundQuality) return true
        if (isRecording) return true // Don't interrupt recording; apply on next bind

        val owner = boundLifecycleOwner ?: return false
        val view = boundPreviewView ?: return false

        boundTargetFps = newFps
        boundQuality = newQuality
        Log.i(TAG, "Rebinding camera: quality=$newQuality, fps=$newFps")
        return bindCameraInternal(owner, view, newFps, newQuality, onError)
    }

    // ── Capability queries ──────────────────────────────────────────────

    /**
     * Query which CameraX Quality levels the current camera supports for video.
     * Uses CameraX QualitySelector.getSupportedQualities() which checks both the
     * camera hardware and the encoder capabilities.
     *
     * Returns the subset of [Quality.UHD, Quality.FHD, Quality.HD] that is supported.
     * Must be called after getCameraProvider() has been resolved.
     */
    fun getSupportedQualities(): List<Quality> {
        val provider = cameraProvider ?: return listOf(Quality.FHD, Quality.HD) // safe fallback
        val selector = if (_useFrontCamera.value) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        val supported = try {
            QualitySelector.getSupportedQualities(provider.getCameraInfo(selector))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot query supported qualities", e)
            return listOf(Quality.FHD, Quality.HD)
        }
        // Filter to the three we care about
        val result = listOf(Quality.UHD, Quality.FHD, Quality.HD).filter { it in supported }
        Log.i(TAG, "Supported qualities for ${if (_useFrontCamera.value) "front" else "back"}: $result")
        return result.ifEmpty { listOf(Quality.FHD, Quality.HD) }
    }

    /**
     * Query the maximum supported fps for the current camera.
     * Uses Camera2 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES to determine what
     * the hardware can actually deliver.
     *
     * CameraX doesn't expose per-quality fps limits directly, so we use the
     * Camera2 interop. This gives us the full set of ranges the sensor supports.
     * We check if 60fps, 30fps, 24fps appear as upper bounds in any range.
     *
     * Limitation: this returns sensor-level capabilities, not per-quality limits.
     * UHD+60fps may still not work even if 60fps is listed (the encoder may limit it).
     * We handle this conservatively in the ViewModel.
     */
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
                    val maxFps = ranges.map { it.upper }.toSet()
                    Log.i(TAG, "Camera $id fps ranges: ${ranges.toList()}, max values: $maxFps")
                    return maxFps
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot query fps capabilities", e)
        }
        return setOf(30) // safe fallback
    }

    // ── Recording mode ─────────────────────────────────────────────────

    /**
     * Enter quality-priority recording mode.
     * The secondary preview continues updating but at lower cost:
     * - Skips every other frame (halves CPU work)
     * - Downscales before processing (reduces bitmap allocation size)
     * This avoids rebinding use cases mid-recording which would interrupt capture.
     */
    fun enterRecordingMode() {
        isInRecordingMode = true
        frameCounter = 0
        Log.i(TAG, "Entered recording mode — secondary preview throttled")
    }

    /** Exit recording mode. Restores normal secondary preview refresh rate. */
    fun exitRecordingMode() {
        isInRecordingMode = false
        Log.i(TAG, "Exited recording mode — secondary preview restored")
    }

    // ── Frame processing ──────────────────────────────────────────────

    private fun processSecondPreviewFrame(imageProxy: ImageProxy) {
        try {
            frameCounter++

            // During recording, skip every other frame to reduce CPU pressure.
            // The secondary preview updates at ~15fps instead of ~30fps.
            if (isInRecordingMode && frameCounter % 2 != 0L) {
                return // frame skipped — imageProxy.close() in finally block
            }

            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                // During recording, downscale the bitmap before processing
                // to reduce allocation size and crop/mirror work.
                val source = if (isInRecordingMode) downscaleBitmap(bitmap) else bitmap

                val rotated = rotateBitmap(source, imageProxy.imageInfo.rotationDegrees)
                val cropped = centerCrop(rotated, 9f / 16f)

                // Mirror horizontally for front camera
                val output = if (_useFrontCamera.value) mirrorBitmap(cropped) else cropped

                // Emit the new frame. We do NOT recycle the previous bitmap because
                // Compose may still be drawing it on the main thread.
                _secondPreviewBitmap.value = output

                // Recycle intermediates that are NOT the emitted bitmap
                if (cropped !== output) cropped.recycle()
                if (rotated !== source && rotated !== cropped) rotated.recycle()
                if (source !== rotated && source !== cropped && source !== output) source.recycle()
                if (bitmap !== source) bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Downscale a bitmap to half resolution during recording mode.
     * Reduces memory allocation from ~1.2MB to ~300KB per processed frame.
     */
    private fun downscaleBitmap(bitmap: Bitmap): Bitmap {
        val halfW = (bitmap.width / 2).coerceAtLeast(1)
        val halfH = (bitmap.height / 2).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, halfW, halfH, false)
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

    /** Mirror a bitmap horizontally (for front camera consistency with PreviewView). */
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
