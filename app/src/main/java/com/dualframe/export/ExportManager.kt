package com.dualframe.export

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
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

@UnstableApi
class ExportManager(private val context: Context) {

    companion object {
        private const val TAG = "ExportManager"
        const val ASPECT_16x9 = 16f / 9f
        const val ASPECT_9x16 = 9f / 16f
        private const val FHD_MAX_DIMENSION = 1920
    }

    var didFallbackToFhd: Boolean = false
        private set

    fun isMasterPortrait(masterFile: File): Boolean {
        val metadata = VideoMetadata.fromFile(masterFile)
        val aspect = metadata?.displayAspectRatio ?: ASPECT_16x9
        val isPortrait = aspect < 1f
        Log.i(TAG, "Master orientation: ${if (isPortrait) "portrait" else "landscape"} " +
            "(display aspect=${"%.3f".format(aspect)})")
        return isPortrait
    }

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
                    val buffer = ByteArray(8192)
                    val totalBytes = masterFile.length()
                    var copiedBytes = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                        onProgress(0.1f + 0.9f * (copiedBytes.toFloat() / totalBytes))
                    }
                }
            }
            Log.i(TAG, "$fileSuffix native copy complete: ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Native copy failed", e)
            outputFile.delete()
            null
        }
    }

    suspend fun exportCropped(
        masterFile: File,
        targetAspect: Float,
        fileSuffix: String,
        verticalOffset: Float = 0f,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.Main) {
        didFallbackToFhd = false
        val outputFile = FileStorage.createExportFile(context, fileSuffix)
        val metadata = VideoMetadata.fromFile(masterFile)
        if (metadata == null) {
            Log.e(TAG, "Cannot read metadata for crop")
        }

        logDeviceInfo(metadata)

        try {
            runCropTransform(
                inputFile = masterFile,
                outputFile = outputFile,
                targetAspectRatio = targetAspect,
                verticalOffset = verticalOffset,
                sourceMetadata = metadata,
                maxDimension = null,
                onProgress = onProgress,
            )
            Log.i(TAG, "$fileSuffix crop export complete: ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "$fileSuffix crop export FAILED at native resolution", e)
            logExportError(e, metadata)
            outputFile.delete()

            // Retry with FHD cap
            val fhdFile = FileStorage.createExportFile(context, fileSuffix)
            try {
                Log.i(TAG, "Retrying $fileSuffix export with FHD cap ($FHD_MAX_DIMENSION)")
                didFallbackToFhd = true
                runCropTransform(
                    inputFile = masterFile,
                    outputFile = fhdFile,
                    targetAspectRatio = targetAspect,
                    verticalOffset = verticalOffset,
                    sourceMetadata = metadata,
                    maxDimension = FHD_MAX_DIMENSION,
                    onProgress = onProgress,
                )
                Log.i(TAG, "$fileSuffix FHD fallback export complete: ${fhdFile.absolutePath}")
                fhdFile
            } catch (retryError: Exception) {
                Log.e(TAG, "$fileSuffix FHD fallback also failed", retryError)
                logExportError(retryError, metadata)
                fhdFile.delete()
                didFallbackToFhd = false
                null
            }
        }
    }

    private suspend fun runCropTransform(
        inputFile: File,
        outputFile: File,
        targetAspectRatio: Float,
        verticalOffset: Float = 0f,
        sourceMetadata: VideoMetadata?,
        maxDimension: Int? = null,
        onProgress: (Float) -> Unit,
    ) = suspendCancellableCoroutine { cont ->

        val sourceAspect = sourceMetadata?.displayAspectRatio ?: ASPECT_16x9
        val displayW = sourceMetadata?.displayWidth ?: 1920
        val displayH = sourceMetadata?.displayHeight ?: 1080

        val crop = CropMath.centerCropWithVerticalOffset(
            sourceAspect, targetAspectRatio, verticalOffset,
        )
        var outputW = (displayW * crop.keepFractionX).toInt().coerceAtLeast(2)
        var outputH = (displayH * crop.keepFractionY).toInt().coerceAtLeast(2)

        // Apply FHD cap if specified
        if (maxDimension != null) {
            val maxDim = maxOf(outputW, outputH)
            if (maxDim > maxDimension) {
                val scale = maxDimension.toFloat() / maxDim
                outputW = (outputW * scale).toInt().coerceAtLeast(2)
                outputH = (outputH * scale).toInt().coerceAtLeast(2)
                Log.i(TAG, "Resolution capped to ${outputW}x${outputH} (max=$maxDimension)")
            }
        }

        Log.i(TAG, "Export crop: source=${displayW}x${displayH} " +
            "(aspect=${"%.3f".format(sourceAspect)}) → target=${"%.3f".format(targetAspectRatio)} " +
            "→ output=${outputW}x${outputH} " +
            "bounds=[L=${"%.3f".format(crop.left)} R=${"%.3f".format(crop.right)} " +
            "B=${"%.3f".format(crop.bottom)} T=${"%.3f".format(crop.top)}]")

        val cropEffect = Crop(crop.left, crop.right, crop.bottom, crop.top)
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
                    Log.e(TAG, "Transformer error: code=${exportException.errorCode}", exportException)
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

    private fun logDeviceInfo(metadata: VideoMetadata?) {
        Log.i(TAG, "=== EXPORT DEVICE INFO ===")
        Log.i(TAG, "  Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.i(TAG, "  Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        Log.i(TAG, "  Input: ${metadata?.rawWidth}x${metadata?.rawHeight} rot=${metadata?.rotation}")
        Log.i(TAG, "  Display: ${metadata?.displayWidth}x${metadata?.displayHeight}")
        Log.i(TAG, "==========================")
    }

    private fun logExportError(e: Exception, metadata: VideoMetadata?) {
        val errorCode = if (e is ExportException) e.errorCode else -1
        Log.e(TAG, "=== EXPORT ERROR ===")
        Log.e(TAG, "  Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.e(TAG, "  Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        Log.e(TAG, "  Input: ${metadata?.displayWidth}x${metadata?.displayHeight}")
        Log.e(TAG, "  ErrorCode: $errorCode")
        Log.e(TAG, "  Message: ${e.message}")
        Log.e(TAG, "  Cause: ${e.cause?.message}")
        Log.e(TAG, "====================")
    }
}
