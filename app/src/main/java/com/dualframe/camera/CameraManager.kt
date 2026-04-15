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
 * Architecture for dual live preview:
 * - CameraX Preview use case → PreviewView (shown in the 16:9 container, native live rendering)
 * - CameraX ImageAnalysis use case → captures frames, rotates, center-crops to 9:16 → emits Bitmap
 *   (shown in the 9:16 container as an Image composable)
 * - CameraX VideoCapture use case → records the master file
 *
 * This gives us two truly separate live video regions from one camera source without
 * custom OpenGL rendering. ImageAnalysis runs at a lower resolution (VGA) for performance,
 * which is fine since the 9:16 preview container is small.
 *
 * Fallback: If a device can't bind all 3 use cases, we drop ImageAnalysis and fall back
 * to single-preview + overlay mode. This is logged but the app remains functional.
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"

        // Resolution for the ImageAnalysis second preview.
        // Low enough for performance, high enough for a clean preview display.
        private val ANALYSIS_RESOLUTION = Size(640, 480)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Emits cropped 9:16 bitmaps for the secondary preview
    private val _secondPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val secondPreviewBitmap: StateFlow<Bitmap?> = _secondPreviewBitmap.asStateFlow()

    // Whether ImageAnalysis was successfully bound (true dual preview available)
    private val _dualPreviewAvailable = MutableStateFlow(false)
    val dualPreviewAvailable: StateFlow<Boolean> = _dualPreviewAvailable.asStateFlow()

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    /**
     * Bind camera with preview, recording, and secondary analysis feed.
     *
     * @param lifecycleOwner Activity lifecycle
     * @param previewView The PreviewView for the primary live preview
     * @param onError Called if camera setup fails entirely
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onError: (String) -> Unit,
    ) {
        try {
            val provider = getCameraProvider()
            cameraProvider = provider
            provider.unbindAll()

            // 1. Build Preview use case
            preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            // 2. Build Recorder — intentionally conservative quality selection.
            //    FHD first, HD fallback. UHD is excluded because binding 3 use cases
            //    (Preview + ImageAnalysis + VideoCapture) already puts pressure on the
            //    camera HAL and codec pipeline. UHD on top of that causes frame drops,
            //    encoder stalls, or outright binding failures on many mid-range devices.
            //    FHD provides excellent crop headroom for both 16:9 and 9:16 exports.
            val qualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.FHD, Quality.HD),
                FallbackStrategy.higherQualityOrLowerThan(Quality.HD)
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

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

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
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed completely", e)
            onError("Camera setup failed: ${e.message}")
        }
    }

    /**
     * Process each ImageAnalysis frame into a 9:16 cropped bitmap for the secondary preview.
     *
     * Steps:
     * 1. Convert ImageProxy to Bitmap (RGBA_8888 format, efficient on modern devices)
     * 2. Rotate to match display orientation
     * 3. Center-crop to 9:16 aspect ratio
     * 4. Emit via StateFlow
     */
    private fun processSecondPreviewFrame(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
                val cropped = centerCrop(rotated, 9f / 16f)
                _secondPreviewBitmap.value = cropped
                // Recycle intermediates if they're different objects
                if (rotated !== bitmap) bitmap.recycle()
                if (cropped !== rotated) rotated.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
        } finally {
            imageProxy.close()
        }
    }

    /** Convert ImageProxy (RGBA_8888) to Bitmap. */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = imageProxy.width
        val height = imageProxy.height

        // Handle row padding if present
        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + rowPadding / pixelStride

        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        // Trim padding if any
        return if (bitmapWidth != width) {
            val trimmed = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()
            trimmed
        } else {
            bitmap
        }
    }

    /** Rotate a bitmap by the given degrees. Returns the original if rotation is 0. */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Center-crop a bitmap to the target aspect ratio.
     * Returns a new bitmap of the cropped region, or the original if already matching.
     */
    private fun centerCrop(bitmap: Bitmap, targetAspect: Float): Bitmap {
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        val srcAspect = srcW / srcH

        val cropW: Int
        val cropH: Int
        if (targetAspect > srcAspect) {
            // Target wider → full width, crop height
            cropW = bitmap.width
            cropH = (srcW / targetAspect).toInt()
        } else {
            // Target taller → full height, crop width
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

    /**
     * Start recording to a master file.
     *
     * @param audioEnabled Whether audio recording is enabled (requires RECORD_AUDIO permission)
     * @param hasAudioPermission Whether the RECORD_AUDIO permission was granted
     * @param onEvent Callback for recording lifecycle events
     * @return The master File, or null on failure
     */
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
