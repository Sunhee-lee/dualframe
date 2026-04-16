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
import androidx.media3.transformer.ProgressHolder
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
 * Exports the master recording into two aspect ratios: 16:9 and 9:16.
 *
 * Orientation-native logic:
 * - The master file's actual display aspect ratio determines which output is "native"
 *   (passthrough / minimal crop) and which is "cropped" (heavy center-crop).
 * - Portrait master (aspect < 1): 9:16 is native, 16:9 is derived.
 * - Landscape master (aspect >= 1): 16:9 is native, 9:16 is derived.
 *
 * The crop math reads real video metadata (width, height, rotation) and computes
 * center-crop coordinates in Media3's normalized [-1, 1] space.
 */
@UnstableApi
class ExportManager(private val context: Context) {

    companion object {
        private const val TAG = "ExportManager"
        const val ASPECT_16x9 = 16f / 9f  // 1.778
        const val ASPECT_9x16 = 9f / 16f  // 0.5625
    }

    /**
     * Determine whether the master file is natively portrait or landscape.
     * Returns true if portrait (display aspect < 1), false if landscape.
     */
    fun isMasterPortrait(masterFile: File): Boolean {
        val metadata = VideoMetadata.fromFile(masterFile)
        val aspect = metadata?.displayAspectRatio ?: ASPECT_16x9
        val isPortrait = aspect < 1f
        Log.i(TAG, "Master orientation: ${if (isPortrait) "portrait" else "landscape"} " +
            "(display aspect=${"%.3f".format(aspect)})")
        return isPortrait
    }

    /**
     * Export the master file to a specific target aspect ratio.
     *
     * @param masterFile The recorded master video
     * @param targetAspect Desired output W/H ratio (e.g., 16/9 or 9/16)
     * @param fileSuffix Suffix for the output filename (e.g., "16x9" or "9x16")
     * @param onProgress Progress callback 0f..1f
     * @return The exported file, or null on failure
     */
    suspend fun exportToAspect(
        masterFile: File,
        targetAspect: Float,
        fileSuffix: String,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.Main) {
        val outputFile = FileStorage.createExportFile(context, fileSuffix)
        try {
            val metadata = VideoMetadata.fromFile(masterFile)
            if (metadata == null) {
                Log.e(TAG, "Cannot read metadata, using fallback crop")
            }
            runTransform(
                inputFile = masterFile,
                outputFile = outputFile,
                targetAspectRatio = targetAspect,
                sourceMetadata = metadata,
                onProgress = onProgress,
            )
            Log.i(TAG, "$fileSuffix export complete: ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "$fileSuffix export failed", e)
            outputFile.delete()
            null
        }
    }

    /**
     * Core transform: center-crop the source to the target aspect ratio.
     *
     * Transformer applies the file's rotation metadata BEFORE the Crop effect,
     * so the Crop operates on the display-oriented frame.
     *
     * For a source with display dimensions W×H (display aspect = W/H):
     *   - target wider than source → crop top/bottom (keep full width)
     *   - target taller than source → crop left/right (keep full height)
     *   - aspects match → passthrough (no crop)
     */
    private suspend fun runTransform(
        inputFile: File,
        outputFile: File,
        targetAspectRatio: Float,
        sourceMetadata: VideoMetadata?,
        onProgress: (Float) -> Unit,
    ) = suspendCancellableCoroutine { cont ->

        val sourceAspect = sourceMetadata?.displayAspectRatio
            ?: ASPECT_16x9 // fallback only if metadata unreadable

        Log.i(TAG, "Crop: source aspect=${"%.3f".format(sourceAspect)}, " +
            "target aspect=${"%.3f".format(targetAspectRatio)}")

        val cropLeft: Float
        val cropRight: Float
        val cropBottom: Float
        val cropTop: Float

        val aspectTolerance = 0.01f
        if (kotlin.math.abs(targetAspectRatio - sourceAspect) < aspectTolerance) {
            // Source already matches target — passthrough
            cropLeft = -1f; cropRight = 1f; cropBottom = -1f; cropTop = 1f
            Log.i(TAG, "No crop needed — native framing matches target")
        } else if (targetAspectRatio > sourceAspect) {
            // Target is wider → crop height
            val keepFraction = sourceAspect / targetAspectRatio
            cropLeft = -1f; cropRight = 1f
            cropBottom = -keepFraction; cropTop = keepFraction
            Log.i(TAG, "Cropping height: keep ${"%.1f".format(keepFraction * 100)}%")
        } else {
            // Target is taller → crop width
            val keepFraction = targetAspectRatio / sourceAspect
            cropLeft = -keepFraction; cropRight = keepFraction
            cropBottom = -1f; cropTop = 1f
            Log.i(TAG, "Cropping width: keep ${"%.1f".format(keepFraction * 100)}%")
        }

        require(cropLeft >= -1f && cropRight <= 1f && cropBottom >= -1f && cropTop <= 1f) {
            "Crop out of bounds: L=$cropLeft R=$cropRight B=$cropBottom T=$cropTop"
        }

        val cropEffect = Crop(cropLeft, cropRight, cropBottom, cropTop)
        val mediaItem = MediaItem.fromUri(inputFile.toURI().toString())
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(listOf(), listOf(cropEffect)))
            .build()

        lateinit var transformer: Transformer
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val progressHolder = ProgressHolder()
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

        transformer = Transformer.Builder(context)
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
