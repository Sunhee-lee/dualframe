package com.dualframe.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
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
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
        private const val DIAG_TAG = "DualFrameCameraDiag"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var preview: Preview? = null
    private var camera: androidx.camera.core.Camera? = null
    @Volatile private var isCameraBound = false
    @Volatile private var isStartingRecording = false
    @Volatile private var isStoppingRecording = false

    private val _useFrontCamera = MutableStateFlow(false)
    val useFrontCamera: StateFlow<Boolean> = _useFrontCamera.asStateFlow()

    fun setInitialCamera(front: Boolean) {
        _useFrontCamera.value = front
    }

    /** The GPU renderer that drives both preview TextureViews. */
    val renderer = DualPreviewRenderer()

    private var boundLifecycleOwner: LifecycleOwner? = null
    private var boundQuality: Quality = Quality.FHD
    // ── Camera binding (always 2 use cases) ───────────────────────────

    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        quality: Quality = Quality.FHD,
        onError: (String) -> Unit,
    ): Boolean {
        boundLifecycleOwner = lifecycleOwner
        boundQuality = quality
        return bindInternal(lifecycleOwner, quality, onError)
    }

    private suspend fun bindInternal(
        lifecycleOwner: LifecycleOwner,
        quality: Quality,
        onError: (String) -> Unit,
        allowStabilization: Boolean = true,
    ): Boolean {
        try {
            val provider = getCameraProvider()
            cameraProvider = provider
            isCameraBound = false
            provider.unbindAll()

            logDiagnostics("bindInternal", quality)

            // Check stabilization support before binding
            val cameraSelector = currentCameraSelector()
            val provider2 = provider
            val stabSupport = try {
                val cameraInfo = provider2.getCameraInfo(cameraSelector)
                val camera2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cameraInfo)
                val modes = camera2Info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
                )
                val hasPreview = modes?.contains(android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION) == true
                val hasVideo = modes?.contains(android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
                Log.i(TAG, "Stabilization support: preview=$hasPreview video=$hasVideo")
                Pair(hasPreview, hasVideo)
            } catch (e: Exception) {
                Log.w(TAG, "Stabilization check failed: ${e.message}")
                Pair(false, false)
            }

            // ==================================================================
            // Policy: 화질을 최우선. Stabilization은 선택한 해상도를 downgrade
            // 하지 않는 경우에만 적용.
            //
            // - Video Stabilization: 표준 API, 대부분 해상도(UHD 포함)에서 유지됨.
            //   지원 시 활성화.
            // - Preview Stabilization: 강한 안정화이지만 UHD에서 자동 downgrade
            //   유발. UHD가 아닌 경우에만 활성화.
            // - Fallback: 사용자가 선택한 해상도 자체를 기기가 지원하지 않을 때만
            //   차선 화질로 이동. Stabilization으로 인한 downgrade 없음.
            // ==================================================================
            val allowPreviewStab = allowStabilization && (quality != Quality.UHD) && stabSupport.first
            val allowVideoStab = allowStabilization && stabSupport.second
            val previewStabReason = when {
                allowPreviewStab -> "ON"
                !allowStabilization -> "OFF(disabled after downgrade detection)"
                quality == Quality.UHD -> "OFF(UHD downgrade prevention)"
                else -> "OFF(unsupported)"
            }
            val videoStabReason = when {
                allowVideoStab -> "ON"
                !allowStabilization -> "OFF(disabled after downgrade detection)"
                else -> "OFF(unsupported)"
            }
            Log.i(TAG, "Quality policy: requested=$quality previewStab=$previewStabReason videoStab=$videoStabReason")

            // Build Preview — no explicit aspect ratio target. The ViewPort (9:16)
            // drives the crop rect for VideoCapture. Preview gets the raw sensor buffer;
            // the renderer applies the matching crop for WYSIWYG via masterVisualAspect.
            preview = Preview.Builder()
                .apply {
                    if (allowPreviewStab) {
                        setPreviewStabilizationEnabled(true)
                    }
                }
                .build().also { previewUseCase ->
                previewUseCase.surfaceProvider = Preview.SurfaceProvider { request ->
                    onSurfaceRequested(request)
                }
            }

            // Build Recorder — fallback only when the selected quality itself is
            // unsupported by the device. Never downgrade due to stabilization.
            val qualityOrder = listOf(quality, Quality.UHD, Quality.FHD, Quality.HD, Quality.SD).distinct()
            val qualitySelector = QualitySelector.fromOrderedList(qualityOrder)
            val targetBitrate = when (quality) {
                Quality.UHD -> 40_000_000
                Quality.FHD -> 16_000_000
                else -> 8_000_000
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .setTargetVideoEncodingBitRate(targetBitrate)
                .build()

            // No setTargetFrameRate — CameraX picks the optimal fps automatically.
            videoCapture = VideoCapture.Builder(recorder)
                .apply {
                    if (allowVideoStab) {
                        setVideoStabilizationEnabled(true)
                    }
                }
                .build()

            // Preview mirroring: DO NOT mirror here. The SurfaceTexture transform
            // matrix (stMatrix) already includes horizontal flip for front cameras.
            // Adding another mirror would double-flip → reversed preview.
            // Saved-file mirroring is handled separately in the export/save pipeline.
            renderer.mirrorHorizontally = false

            // Bind via UseCaseGroup + ViewPort. ViewPort is 9:16 PORTRAIT because:
            // 1. Portrait (9:16) is the primary output — native copy, zero quality loss
            // 2. Landscape (16:9) is derived from the portrait master via center-crop
            // 3. CameraX ViewPort crops the VideoCapture buffer to 9:16
            // 4. The renderer applies a matching crop to Preview for WYSIWYG
            val viewPort = androidx.camera.core.ViewPort.Builder(
                android.util.Rational(9, 16),
                android.view.Surface.ROTATION_0,
            )
                .setScaleType(androidx.camera.core.ViewPort.FILL_CENTER)
                .build()

            val useCaseGroup = androidx.camera.core.UseCaseGroup.Builder()
                .addUseCase(preview!!)
                .addUseCase(videoCapture!!)
                .setViewPort(viewPort)
                .build()

            camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
            isCameraBound = true
            _zoomRatio.value = 1f
            Log.i(TAG, "Camera bound: UseCaseGroup(Preview+VideoCapture) with ViewPort 9:16")

            // === DIAGNOSTIC LOGGING ===
            val cam = camera!!
            val camInfo = cam.cameraInfo
            val zoomState = camInfo.zoomState.value
            Log.i(DIAG_TAG, "=== DUALFRAME CAMERA DIAGNOSTICS ===")
            Log.i(DIAG_TAG, "Zoom ratio: ${zoomState?.zoomRatio}")

            try {
                val camera2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(camInfo)
                val stabModes = camera2Info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
                )
                Log.i(DIAG_TAG, "Available video stabilization modes: ${stabModes?.toList()}")
                val activeRect = camera2Info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
                )
                Log.i(DIAG_TAG, "Sensor active array: $activeRect")
            } catch (e: Exception) {
                Log.w(DIAG_TAG, "Camera2 interop not available: ${e.message}")
            }

            val previewRes = preview?.attachedSurfaceResolution
            val masterRes = videoCapture?.attachedSurfaceResolution
            Log.i(DIAG_TAG, "Preview resolution: $previewRes")
            Log.i(DIAG_TAG, "VideoCapture resolution: $masterRes")

            // Compare requested vs actual — quality-first policy:
            // If the selected quality was downgraded AND any stabilization was ON,
            // rebind without stabilization to preserve the user's chosen resolution.
            // Applies to UHD, FHD, HD — the user's choice is always priority #1.
            val expectedLongEdge = when (quality) {
                Quality.UHD -> 2160
                Quality.FHD -> 1080
                Quality.HD -> 720
                else -> null
            }
            if (masterRes != null && expectedLongEdge != null) {
                val actualLongEdge = maxOf(masterRes.width, masterRes.height)
                if (actualLongEdge < expectedLongEdge) {
                    Log.w(DIAG_TAG, "⚠ Resolution downgraded: requested=$quality (${expectedLongEdge}p) " +
                        "actual=${actualLongEdge}p")

                    // Auto-recovery: any stabilization was on and might be the cause.
                    // Rebind with all stabilization disabled to force the requested resolution.
                    if (allowStabilization && (allowPreviewStab || allowVideoStab)) {
                        Log.w(DIAG_TAG, "→ Auto-recovery: disabling stabilization and rebinding for $quality")
                        return bindInternal(lifecycleOwner, quality, onError, allowStabilization = false)
                    }
                    // No stabilization to disable — device limitation, accept as-is.
                    Log.w(DIAG_TAG, "→ Device limitation, accepting actual resolution")
                } else {
                    Log.i(DIAG_TAG, "✓ Resolution matches request: $quality (${actualLongEdge}p)")
                }
            }

            // Tell renderer the master aspect so preview visually matches saved output
            // (WYSIWYG). Without this, if Preview buffer aspect ≠ master aspect, the
            // preview would show a wider FOV than what gets recorded.
            if (masterRes != null) {
                val shorter = minOf(masterRes.width, masterRes.height).toFloat()
                val longer = maxOf(masterRes.width, masterRes.height).toFloat()
                val aspect = shorter / longer
                renderer.masterVisualAspect = aspect
                Log.i(DIAG_TAG, "Master visual aspect set on renderer: ${"%.4f".format(aspect)}")
            }
            Log.i(DIAG_TAG, "==============================")

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
        Log.i(DIAG_TAG, "Preview SurfaceRequest: ${resolution.width}x${resolution.height}")
        Log.i(DIAG_TAG, "Preview SurfaceRequest aspect: ${"%.4f".format(resolution.width.toFloat()/resolution.height)}")

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
        return bindInternal(owner, boundQuality, onError)
    }

    suspend fun rebindWithQuality(
        newQuality: Quality,
        onError: (String) -> Unit,
    ): Boolean {
        if (newQuality == boundQuality) return true
        if (isRecording) return true
        val owner = boundLifecycleOwner ?: return false
        boundQuality = newQuality
        return bindInternal(owner, newQuality, onError)
    }

    // ── Zoom ──────────────────────────────────────────────────────────

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()
    val currentZoomRatio: Float get() = _zoomRatio.value

    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return
        val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        _zoomRatio.value = clamped
        cam.cameraControl.setZoomRatio(clamped)
    }

    var currentTargetRotation: Int = android.view.Surface.ROTATION_0
        private set

    fun setTargetRotation(surfaceRotation: Int) {
        currentTargetRotation = surfaceRotation
        preview?.targetRotation = surfaceRotation
        videoCapture?.targetRotation = surfaceRotation
    }

    // ── Focus / Metering ─────────────────────────────────────────────

    fun focusAt(normX: Float, normY: Float) {
        val cam = camera ?: return
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(
            normX.coerceIn(0f, 1f), normY.coerceIn(0f, 1f),
        )
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        ).setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
    }


    // ── Torch (flash) ─────────────────────────────────────────────────

    fun setTorch(enabled: Boolean) {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            cam.cameraControl.enableTorch(enabled)
            Log.i(TAG, "Torch ${if (enabled) "ON" else "OFF"}")
        }
    }

    // ── Recording ─────────────────────────────────────────────────────

    fun startRecording(
        audioEnabled: Boolean,
        hasAudioPermission: Boolean,
        onEvent: (VideoRecordEvent) -> Unit,
    ): File? {
        if (activeRecording != null) {
            Log.w(TAG, "startRecording blocked: already recording")
            return null
        }
        if (isStartingRecording) {
            Log.w(TAG, "startRecording blocked: start in progress")
            return null
        }
        if (isStoppingRecording) {
            Log.w(TAG, "startRecording blocked: stop in progress")
            return null
        }
        if (!isCameraBound) {
            Log.e(TAG, "startRecording blocked: camera not bound")
            return null
        }
        val capture = videoCapture ?: run {
            Log.e(TAG, "startRecording blocked: VideoCapture not initialized")
            return null
        }

        isStartingRecording = true
        return try {
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
            outputFile
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startRecording failed: IllegalStateException", e)
            activeRecording = null
            null
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            activeRecording = null
            null
        } finally {
            isStartingRecording = false
        }
    }

    fun pauseRecording() {
        try {
            activeRecording?.pause()
            Log.i(TAG, "Recording paused")
        } catch (e: Exception) {
            Log.e(TAG, "pauseRecording error", e)
        }
    }

    fun resumeRecording() {
        try {
            activeRecording?.resume()
            Log.i(TAG, "Recording resumed")
        } catch (e: Exception) {
            Log.e(TAG, "resumeRecording error", e)
        }
    }

    fun stopRecording() {
        if (activeRecording == null) {
            Log.w(TAG, "stopRecording: no active recording")
            return
        }
        if (isStoppingRecording) {
            Log.w(TAG, "stopRecording: already stopping")
            return
        }
        isStoppingRecording = true
        try {
            activeRecording?.stop()
            activeRecording = null
            Log.i(TAG, "Recording stop requested")
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording error", e)
            activeRecording = null
        } finally {
            isStoppingRecording = false
        }
    }

    fun release() {
        isCameraBound = false
        try {
            activeRecording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recording during release", e)
        }
        activeRecording = null
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding camera during release", e)
        }
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

    private fun logDiagnostics(ctx: String, quality: Quality) {
        val camera = if (_useFrontCamera.value) "FRONT" else "REAR"
        Log.i(TAG, "=== $ctx === Camera=$camera, Quality=$quality, Fps=AUTO (internal)")
        Log.i(TAG, "  Supported qualities: ${getSupportedQualities()}")
    }

    // ── Capability queries ──────────────────────────────────────────────

    fun getSupportedQualities(): List<Quality> {
        val provider = cameraProvider ?: return listOf(Quality.FHD, Quality.HD)
        val selector = currentCameraSelector()
        return try {
            val cameraInfo = provider.getCameraInfo(selector)
            val capabilities = Recorder.getVideoCapabilities(cameraInfo)
            val supported = capabilities.getSupportedQualities(capabilities.supportedDynamicRanges.first())
            listOf(Quality.UHD, Quality.FHD, Quality.HD).filter { it in supported }
                .ifEmpty { listOf(Quality.FHD, Quality.HD) }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot query supported qualities", e)
            listOf(Quality.FHD, Quality.HD)
        }
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
