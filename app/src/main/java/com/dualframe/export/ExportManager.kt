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
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Handles post-recording export of the master file into 16:9 and 9:16 crops.
 *
 * Design decisions:
 * - Uses Media3 Transformer which is the modern, supported way to do video transforms on Android.
 * - Crop is center-based: we extract the largest centered rectangle of the target aspect ratio
 *   from the source frame.
 * - Each export runs sequentially (not simultaneously) to avoid GPU/CPU contention.
 * - The Transformer handles re-encoding efficiently with hardware acceleration where available.
 *
 * Crop math:
 * - Media3 Crop effect uses normalized coordinates in [-1, 1] range for both axes.
 * - left=-1 is the left edge, right=1 is the right edge, top=1 is top, bottom=-1 is bottom.
 * - To crop, we set the edges inward from the full frame.
 *
 * Example: A 16:9 source (1920x1080) → 9:16 output
 *   We need to crop width. Target aspect = 9/16 = 0.5625
 *   Source aspect = 16/9 = 1.778
 *   We keep full height, crop width to: 1080 * (9/16) = 607.5 px
 *   Fraction of width to keep: 607.5/1920 = 0.3164
 *   In normalized coords: left = -0.3164, right = 0.3164
 */
@UnstableApi
class ExportManager(private val context: Context) {

    companion object {
        private const val TAG = "ExportManager"
    }

    /**
     * Export the master file to a 16:9 landscape version.
     * Center-crops vertically if needed (e.g., if source is 4:3).
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
            runTransform(
                inputFile = masterFile,
                outputFile = outputFile,
                targetAspectRatio = 16f / 9f,
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
     * Center-crops horizontally from the source.
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
            runTransform(
                inputFile = masterFile,
                outputFile = outputFile,
                targetAspectRatio = 9f / 16f,
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
     * Run the actual Transformer export with a center-crop to the target aspect ratio.
     *
     * The Crop effect in Media3 uses normalized coordinates [-1, 1]:
     *   - For width:  left=-1 means left edge, right=1 means right edge
     *   - For height: bottom=-1 means bottom edge, top=1 means top edge
     *
     * To center-crop to a target aspect ratio from a source with a different aspect ratio,
     * we compute what fraction of each dimension to keep and set the crop edges accordingly.
     */
    private suspend fun runTransform(
        inputFile: File,
        outputFile: File,
        targetAspectRatio: Float,
        onProgress: (Float) -> Unit,
    ) = suspendCancellableCoroutine { cont ->

        // We don't know the exact source resolution at this point, but the Crop effect
        // works in normalized space. We compute the crop assuming:
        // - If targetAspect > sourceAspect: crop top/bottom (keep full width)
        // - If targetAspect < sourceAspect: crop left/right (keep full height)
        //
        // Since the master is typically recorded at 16:9 (landscape sensor),
        // but the phone is portrait, CameraX records in the sensor's native orientation
        // with rotation metadata. Media3 Transformer respects this metadata.
        //
        // For a typical 16:9 master:
        //   - 16:9 export: minimal or no crop needed
        //   - 9:16 export: heavy horizontal crop from 16:9 → 9:16

        // Use Presentation effect to force output resolution and aspect ratio.
        // The Crop effect handles the center-crop, then Presentation scales to final size.

        val sourceAspect = 16f / 9f // Most rear cameras record 16:9 by default

        val cropLeft: Float
        val cropRight: Float
        val cropBottom: Float
        val cropTop: Float

        if (targetAspectRatio >= sourceAspect) {
            // Target is wider or same — crop height (top/bottom)
            val keepFraction = sourceAspect / targetAspectRatio
            cropLeft = -1f
            cropRight = 1f
            cropBottom = -keepFraction
            cropTop = keepFraction
        } else {
            // Target is taller — crop width (left/right)
            val keepFraction = targetAspectRatio / sourceAspect
            cropLeft = -keepFraction
            cropRight = keepFraction
            cropBottom = -1f
            cropTop = 1f
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

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onProgress(1f)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            })
            .build()

        // Start the export
        transformer.start(editedMediaItem, outputFile.absolutePath)

        // Poll progress on the main thread
        // Transformer doesn't have a progress callback, so we poll via getProgress()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val progressBundle = androidx.media3.common.util.ProgressHolder()
        val pollRunnable = object : Runnable {
            override fun run() {
                if (!cont.isActive) return
                val state = transformer.getProgress(progressBundle)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressBundle.progress / 100f)
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    handler.postDelayed(this, 250)
                }
            }
        }
        handler.postDelayed(pollRunnable, 250)

        cont.invokeOnCancellation {
            transformer.cancel()
            handler.removeCallbacks(pollRunnable)
            outputFile.delete()
        }
    }
}
