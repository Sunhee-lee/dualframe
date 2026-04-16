package com.dualframe.util

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.OverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Applies a simple text watermark to a video file using Media3 Transformer.
 * The watermark is a semi-transparent "DualFrame" text overlay.
 *
 * Used in the "Save with Ads" flow to mark free-tier exports.
 */
@UnstableApi
object WatermarkHelper {

    private const val TAG = "WatermarkHelper"

    /**
     * Copy the source file with a text watermark overlay.
     * Returns the watermarked output file, or null on failure.
     */
    suspend fun applyWatermark(
        context: Context,
        sourceFile: File,
        outputFile: File,
    ): File? = withContext(Dispatchers.Main) {
        try {
            val mediaItem = MediaItem.fromUri(sourceFile.toURI().toString())

            // Simple text watermark — positioned bottom-right, semi-transparent
            val textOverlay = TextOverlay.createStaticTextOverlay(
                android.text.SpannableString("DualFrame").apply {
                    setSpan(
                        android.text.style.ForegroundColorSpan(0x88FFFFFF.toInt()),
                        0, length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        android.text.style.RelativeSizeSpan(1.2f),
                        0, length,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                },
                OverlaySettings.Builder()
                    .setOverlayFrameAnchor(0.8f, -0.8f) // bottom-right area
                    .setBackgroundFrameAnchor(0.8f, -0.8f)
                    .build(),
            )

            val overlayEffect = OverlayEffect(ImmutableList.of(textOverlay))

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(listOf(), listOf(overlayEffect)))
                .build()

            runTransformer(context, editedMediaItem, outputFile)
            Log.i(TAG, "Watermark applied: ${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Watermark failed", e)
            outputFile.delete()
            null
        }
    }

    private suspend fun runTransformer(
        context: Context,
        editedMediaItem: EditedMediaItem,
        outputFile: File,
    ) = suspendCancellableCoroutine { cont ->
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
            .build()
        transformer.start(editedMediaItem, outputFile.absolutePath)
        cont.invokeOnCancellation { transformer.cancel(); outputFile.delete() }
    }
}
