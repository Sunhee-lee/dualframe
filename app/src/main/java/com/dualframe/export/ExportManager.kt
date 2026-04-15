package com.dualframe.export

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.dualframe.util.FileStorage
import com.dualframe.util.VideoMetadata
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Handles post-recording export of the master file into 16:9 and 9:16 crops.
 *
 * Key improvement over v1: reads actual video metadata (width, height, rotation)
 * from the master file instead of assuming a fixed 16:9 source. This ensures correct
 * center-crop calculations for any camera output resolution/orientation.
 *
 * Media3 Transformer Crop effect uses normalized coordinates [-1, 1]:
 *   - left=-1 is left edge, right=1 is right edge
 *   - bottom=-1 is bottom edge, top=1 is top edge
 *   - Values closer to 0 = more cropping from that edge
 *
 * IMPORTANT: Transformer reads the rotation metadata from the file and applies it
 * BEFORE the Crop effect. So the Crop operates on the already-rotated (display)
 * frame. Our crop coordinates should be calculated based on the display dimensions,
 * not the raw buffer dimensions.
 */
@UnstableApi
class ExportManager(private val context: Context) {

    companion object {
        private const val TAG = "ExportManager"
        private const val TARGET_LANDSCAPE = 16f / 9f  // 1.778
        private const val TARGET_PORTRAIT = 9f / 16f    // 0.5625
    }

    /**
     * Export the master file to a 16:9 landscape version.
     *
     * @param masterFile The recorded master video file
     * @param onProgress Progress callback 0f..1f
     * @return The exported file, or null on failure
     */
    suspend fun exportLandscape(
        masterFile: File,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.Main) {
        val outputFile = FileStorage.createExportFile(context, "16x9")
        try {
            val metadata = VideoMetadata.fromFile(masterFile)
            if (metadata == null) {
                Log.e(TAG, "Cannot read metadata, using fallback crop")
            }
            runTransform(
                inputFile = masterFile,
                outputFile = outputFile,
                targetAspectRatio = TARGET_LANDSCAPE,
                sourceMetadata = metadata,
                onProgress = onProgress,
            )
            Log.i(TAG, "16:9 export complete: ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "16:9 export failed", e)
            outputFile.delete()
            null
        }
    }

    /**
     * Export the master file to a 9:16 portrait version.
     *
     * @param masterFile The recorded master video file
     * @param onProgress Progress callback 0f..1f
     * @return The exported file, or null on failure
     */
    suspend fun exportPortrait(
        masterFile: File,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.Main) {
        val outputFile = FileStorage.createExportFile(context, "9x16")
        try {
            val metadata = VideoMetadata.fromFile(masterFile)
            if (metadata == null) {
                Log.e(TAG, "Cannot read metadata, using fallback crop")
            }
            runTransform(
                inputFile = masterFile,
                outputFile = outputFile,
                targetAspectRatio = TARGET_PORTRAIT,
                sourceMetadata = metadata,
                onProgress = onProgress,
            )
            Log.i(TAG, "9:16 export complete: ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "9:16 export failed", e)
            outputFile.delete()
            null
        }
    }

    /**
     * Core transform: center-crop the source to the target aspect ratio.
     *
     * Crop coordinate math (explained):
     *
     * The Crop effect works AFTER Transformer applies the rotation metadata.
     * So the frame the Crop sees is in display orientation (post-rotation).
     *
     * For a source with display dimensions W×H (display aspect = W/H):
     *
     * Case 1: target aspect > source aspect (target is wider relative to source)
     *   → We need to crop top and bottom (reduce height)
     *   → Keep full width: left=-1, right=1
     *   → Height fraction to keep = sourceAspect / targetAspect
     *   → In normalized coords: bottom = -fraction, top = fraction
     *
     * Case 2: target aspect < source aspect (target is taller relative to source)
     *   → We need to crop left and right (reduce width)
     *   → Keep full height: bottom=-1, top=1
     *   → Width fraction to keep = targetAspect / sourceAspect
     *   → In normalized coords: left = -fraction, right = fraction
     *
     * Case 3: target ≈ source (within tolerance)
     *   → No crop needed, pass through
     *
     * @param inputFile Source video
     * @param outputFile Destination file
     * @param targetAspectRatio Desired output W/H ratio
     * @param sourceMetadata Actual video metadata (null = fallback to 16:9 assumption)
     * @param onProgress Progress callback
     */
    private suspend fun runTransform(
        inputFile: File,
        outputFile: File,
        targetAspectRatio: Float,
        sourceMetadata: VideoMetadata?,
        onProgress: (Float) -> Unit,
    ) = suspendCancellableCoroutine { cont ->

        // Use actual display aspect from metadata, or fallback to 16:9
        val sourceAspect = sourceMetadata?.displayAspectRatio ?: (16f / 9f)

        Log.i(TAG, "Crop: source aspect=$sourceAspect, target aspect=$targetAspectRatio")

        val cropLeft: Float
        val cropRight: Float
        val cropBottom: Float
        val cropTop: Float

        val aspectTolerance = 0.01f
        if (kotlin.math.abs(targetAspectRatio - sourceAspect) < aspectTolerance) {
            // Source already matches target — no crop
            cropLeft = -1f
            cropRight = 1f
            cropBottom = -1f
            cropTop = 1f
            Log.i(TAG, "No crop needed, aspects match within tolerance")
        } else if (targetAspectRatio > sourceAspect) {
            // Target is wider → crop height (top/bottom)
            val keepFraction = sourceAspect / targetAspectRatio
            cropLeft = -1f
            cropRight = 1f
            cropBottom = -keepFraction
            cropTop = keepFraction
            Log.i(TAG, "Cropping height: keep ${"%.1f".format(keepFraction * 100)}%")
        } else {
            // Target is taller → crop width (left/right)
            val keepFraction = targetAspectRatio / sourceAspect
            cropLeft = -keepFraction
            cropRight = keepFraction
            cropBottom = -1f
            cropTop = 1f
            Log.i(TAG, "Cropping width: keep ${"%.1f".format(keepFraction * 100)}%")
        }

        // Validate crop bounds are within [-1, 1]
        require(cropLeft >= -1f && cropRight <= 1f && cropBottom >= -1f && cropTop <= 1f) {
            "Crop out of bounds: left=$cropLeft right=$cropRight bottom=$cropBottom top=$cropTop"
        }

        val cropEffect = Crop(cropLeft, cropRight, cropBottom, cropTop)

        val mediaItem = MediaItem.fromUri(inputFile.toURI().toString())
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(
                Effects(
                    /* audioProcessors = */ listOf(),
                    /* videoEffects = */ listOf(cropEffect),
                )
            )
            .build()

        // Handler + runnable for progress polling (declared before listener so
        // the listener can stop polling on completion/error)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val progressHolder = androidx.media3.common.util.ProgressHolder()
        val pollRunnable = object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress / 100f)
                }
                handler.postDelayed(this, 200)
            }
        }

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(pollRunnable)
                    onProgress(1f)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    handler.removeCallbacks(pollRunnable)
                    Log.e(TAG, "Transformer error", exportException)
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            .build()

        transformer.start(editedMediaItem, outputFile.absolutePath)
        handler.postDelayed(pollRunnable, 200)

        cont.invokeOnCancellation {
            transformer.cancel()
            handler.removeCallbacks(pollRunnable)
            outputFile.delete()
        }
    }
}
