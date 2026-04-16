package com.dualframe.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as Camera2Manager
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dualframe.util.FileStorage
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Camera pipeline: Preview + VideoCapture only (2 use cases, always).
 *
 * The dual live preview is handled entirely by [DualPreviewRenderer] which takes
 * the single camera Preview SurfaceTexture and renders it twice via GPU into
 * two TextureView surfaces with different crop transforms (9:16 and 16:9).
 *
 * No ImageAnalysis is used. This ensures:
 * - FHD/UHD recording quality is never collapsed by a third use case
 * - Both previews stay live during recording (GPU rendering, not frozen)
 * - Zero CPU bitmap processing for the second preview
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var preview: Preview? = null

    private val _useFrontCamera = MutableStateFlow(false)
    val useFrontCamera: StateFlow<Boolean> = _useFrontCamera.asStateFlow()

    /** The GPU renderer that drives both preview TextureViews. */
    val renderer = DualPreviewRenderer()

    private var boundLifecycleOwner: LifecycleOwner? = null
    private var boundTargetFps: Int = 0
    private var boundQuality: Quality = Quality.FHD

    // ── Camera binding (always 2 use cases) ───────────────────────────

    /**
     * Initialize the renderer and bind camera.
     * The renderer creates a SurfaceTexture; we provide it to CameraX as the Preview surface.
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        targetFps: Int = 0,
        quality: Quality = Quality.FHD,
        onError: (String) -> Unit,
    ): Boolean {
        boundLifecycleOwner = lifecycleOwner
        boundTargetFps = targetFps
        boundQuality = quality

        return bindInternal(lifecycleOwner, targetFps, quality, onError)
    }

    private suspend fun bindInternal(
        lifecycleOwner: LifecycleOwner,
        targetFps: Int,
        quality: Quality,
        onError: (String) -> Unit,
    ): Boolean {
        try {
            val provider = getCameraProvider()
            cameraProvider = provider
            provider.unbindAll()

            logDiagnostics("bindInternal", quality, targetFps)

            // Build Preview with custom SurfaceProvider that feeds the GL renderer
            preview = Preview.Builder().build().also { previewUseCase ->
                previewUseCase.surfaceProvider = Preview.SurfaceProvider { request ->
                    onSurfaceRequested(request)
                }
            }

            // Build Recorder
            val qualitySelector = QualitySelector.from(
                quality,
                FallbackStrategy.higherQualityOrLowerThan(Quality.FHD)
            )
            val targetBitrate = when (quality) {
                Quality.UHD -> 40_000_000
                Quality.FHD -> 16_000_000
                else -> 8_000_000
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .setTargetVideoEncodingBitRate(targetBitrate)
                .build()

            val builder = VideoCapture.Builder(recorder)
            if (targetFps > 0) {
                builder.setTargetFrameRate(Range(targetFps, targetFps))
            }
            videoCapture = builder.build()

            // Mirror for front camera
            renderer.mirrorHorizontally = _useFrontCamera.value

            val cameraSelector = currentCameraSelector()

            // Always only 2 use cases — Preview + VideoCapture.
            // No ImageAnalysis. This preserves FHD/UHD recording quality.
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, videoCapture)
            Log.i(TAG, "Camera bound: Preview + VideoCapture (2 use cases, GPU dual preview)")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            onError("Camera setup failed: ${e.message}")
            return false
        }
    }

    /**
     * Handle CameraX SurfaceRequest: provide the renderer's SurfaceTexture as the preview surface.
     * This is where CameraX connects to our GL pipeline.
     */
    private fun onSurfaceRequested(request: SurfaceRequest) {
        val resolution = request.resolution
        Log.i(TAG, "SurfaceRequest: ${resolution.width}x${resolution.height}")

        renderer.setPreviewSize(resolution.width, resolution.height)

        // The renderer's SurfaceTexture may already be initialized (from a previous bind)
        // or we need to wait for it. Since init() is async, we handle both cases.
        val st = renderer.getCameraSurfaceTexture()
        if (st != null) {
            provideSurface(request, st)
        } else {
            // Renderer not ready yet — this shouldn't happen if init() was called first,
            // but handle gracefully by initializing now.
            renderer.init { newSt ->
                ContextCompat.getMainExecutor(context).execute {
                    provideSurface(request, newSt)
                }
            }
        }
    }

    private fun provideSurface(request: SurfaceRequest, st: SurfaceTexture) {
        val surface = Surface(st)
        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { result ->
            surface.release()
            Log.i(TAG, "Surface released: ${result.resultCode}")
        }
    }

    // ── Camera switching ──────────────────────────────────────────────

    suspend fun switchCamera(onError: (String) -> Unit): Boolean {
        val owner = boundLifecycleOwner ?: return false
        _useFrontCamera.value = !_useFrontCamera.value
        Log.i(TAG, "Switching to ${if (_useFrontCamera.value) "front" else "back"} camera")
        return bindInternal(owner, boundTargetFps, boundQuality, onError)
    }

    suspend fun rebindWithSettings(
        newFps: Int,
        newQuality: Quality,
        onError: (String) -> Unit,
    ): Boolean {
        if (newFps == boundTargetFps && newQuality == boundQuality) return true
        if (isRecording) return true
        val owner = boundLifecycleOwner ?: return false
        boundTargetFps = newFps
        boundQuality = newQuality
        return bindInternal(owner, newFps, newQuality, onError)
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
        renderer.release()
        boundLifecycleOwner = null
        Log.i(TAG, "Camera released")
    }

    val isRecording: Boolean
        get() = activeRecording != null

    private fun currentCameraSelector(): CameraSelector =
        if (_useFrontCamera.value) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

    private fun logDiagnostics(ctx: String, quality: Quality, targetFps: Int) {
        val camera = if (_useFrontCamera.value) "FRONT" else "REAR"
        Log.i(TAG, "=== $ctx === Camera=$camera, Quality=$quality, Fps=${if (targetFps == 0) "AUTO" else targetFps}")
        Log.i(TAG, "  Supported qualities: ${getSupportedQualities()}")
        Log.i(TAG, "  Supported fps: ${getSupportedFpsValues()}")
    }

    // ── Capability queries ──────────────────────────────────────────────

    fun getSupportedQualities(): List<Quality> {
        val provider = cameraProvider ?: return listOf(Quality.FHD, Quality.HD)
        val selector = currentCameraSelector()
        return try {
            val supported = QualitySelector.getSupportedQualities(provider.getCameraInfo(selector))
            listOf(Quality.UHD, Quality.FHD, Quality.HD).filter { it in supported }
                .ifEmpty { listOf(Quality.FHD, Quality.HD) }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot query supported qualities", e)
            listOf(Quality.FHD, Quality.HD)
        }
    }

    fun getSupportedFpsValues(): Set<Int> {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as Camera2Manager
            val facing = if (_useFrontCamera.value) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == facing) {
                    val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?: continue
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
                    try { cont.resume(future.get()) }
                    catch (e: Exception) { cont.resumeWithException(e) }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
}

private typealias FallbackStrategy = androidx.camera.video.FallbackStrategy
