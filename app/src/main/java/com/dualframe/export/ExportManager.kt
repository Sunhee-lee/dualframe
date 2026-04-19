package com.dualframe.export

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.dualframe.util.CropMath
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
 * Quality-preserving strategy:
 * - Native output (matches master framing): file copy, no re-encode, no quality loss.
 * - Derived output (different aspect): Transformer center-crop with explicit resolution
 *   preservation via Presentation.createForHeight() to prevent default downscaling.
 *
 * Media3 Transformer, when effects are applied, may default to a lower output resolution
 * (often 720p). We counter this by explicitly requesting the output height to match
 * the cropped region's actual pixel dimensions.
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
     * "Export" the native framing by copying the master file directly.
     * No re-encode, no crop, no quality loss. This preserves the exact recorded resolution.
     *
     * If the master aspect doesn't exactly match the target (e.g., camera records at
     * a slightly non-standard ratio), the copy still preserves full quality — the tiny
     * aspect difference is negligible for playback.
     */
    suspend fun exportNativeCopy(
        masterFile: File,
        fileSuffix: String,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val outputFile = FileStorage.createExportFile(context, fileSuffix)
        try {
            onProgress(0.1f)
            masterFile.inputStream().use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            onProgress(1f)
            Log.i(TAG, "Native copy complete: ${outputFile.absolutePath} " +
                "(${outputFile.length() / 1024}KB, no re-encode)")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Native copy failed", e)
            outputFile.delete()
            null
        }
    }

    /**
     * Export the master file to a different aspect ratio via center-crop.
     * Uses Transformer with explicit resolution preservation.
     *
     * The key to avoiding quality loss: after computing the crop region's pixel
     * dimensions, we set Presentation.createForHeight() to match. This prevents
     * Transformer from applying its default downscale.
     *
     * Example for portrait 1080x1920 master → 16:9 crop:
     *   Crop keeps full width (1080px) and 31.6% of height (607px).
     *   We set Presentation height to 607 → output is 1080x607 at full quality.
     *   Without this, Transformer would default to ~720p → ~1280x720.
     */
    suspend fun exportCropped(
        masterFile: File,
        targetAspect: Float,
        fileSuffix: String,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.Main) {
        val outputFile = FileStorage.createExportFile(context, fileSuffix)
        try {
            val metadata = VideoMetadata.fromFile(masterFile)
            if (metadata == null) {
                Log.e(TAG, "Cannot read metadata for crop")
            }
            runCropTransform(
                inputFile = masterFile,
                outputFile = outputFile,
                targetAspectRatio = targetAspect,
                sourceMetadata = metadata,
                onProgress = onProgress,
            )
            Log.i(TAG, "$fileSuffix crop export complete: ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "$fileSuffix crop export failed", e)
            outputFile.delete()
            null
        }
    }

    /**
     * Center-crop + re-encode with explicit resolution preservation.
     *
     * Transformer applies rotation metadata first, then the Crop effect.
     * We compute the exact pixel dimensions of the cropped output and use
     * Presentation to lock that resolution, preventing default downscaling.
     */
    private suspend fun runCropTransform(
        inputFile: File,
        outputFile: File,
        targetAspectRatio: Float,
        sourceMetadata: VideoMetadata?,
        onProgress: (Float) -> Unit,
    ) = suspendCancellableCoroutine { cont ->

        val sourceAspect = sourceMetadata?.displayAspectRatio ?: ASPECT_16x9
        val displayW = sourceMetadata?.displayWidth ?: 1920
        val displayH = sourceMetadata?.displayHeight ?: 1080

        // Use shared CropMath — same math used by preview renderer for WYSIWYG
        val crop = CropMath.centerCrop(sourceAspect, targetAspectRatio)
        val outputW = (displayW * crop.keepFractionX).toInt().coerceAtLeast(2)
        val outputH = (displayH * crop.keepFractionY).toInt().coerceAtLeast(2)

        Log.i(TAG, "Export crop: source=${displayW}x${displayH} " +
            "(aspect=${"%.3f".format(sourceAspect)}) → target=${"%.3f".format(targetAspectRatio)} " +
            "→ output=${outputW}x${outputH} " +
            "bounds=[L=${"%.3f".format(crop.left)} R=${"%.3f".format(crop.right)} " +
            "B=${"%.3f".format(crop.bottom)} T=${"%.3f".format(crop.top)}]")

        val cropEffect = Crop(crop.left, crop.right, crop.bottom, crop.top)

        // Presentation locks the output to the exact cropped pixel dimensions.
        // Without this, Transformer defaults to ~720p when effects are present.
        // We use the output height and let Presentation compute width from aspect.
        val presentation = Presentation.createForHeight(outputH.coerceAtLeast(2))

        val mediaItem = MediaItem.fromUri(inputFile.toURI().toString())
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(listOf(), listOf(cropEffect, presentation)))
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
