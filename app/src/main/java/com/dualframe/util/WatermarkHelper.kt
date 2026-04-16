package com.dualframe.util

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
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
    /**
     * Copy the source file with a text watermark overlay.
     * For portrait videos (height > width after rotation), the watermark is placed
     * near the top ~20% area. For landscape, it stays at bottom-right.
     */
    suspend fun applyWatermark(
        context: Context,
        sourceFile: File,
        outputFile: File,
    ): File? = withContext(Dispatchers.Main) {
        try {
            val mediaItem = MediaItem.fromUri(sourceFile.toURI().toString())

            // Determine if the source is portrait to adjust watermark position.
            // Portrait videos get the watermark near top-center (~20% from top),
            // landscape videos keep it at bottom-right.
            val metadata = VideoMetadata.fromFile(sourceFile)
            val isPortrait = metadata != null && metadata.displayHeight > metadata.displayWidth

            val overlayAnchorX: Float
            val overlayAnchorY: Float
            if (isPortrait) {
                // Top-center area, ~20% from top. In Media3 overlay coordinates:
                // X=0 is center, Y=1 is top, Y=-1 is bottom.
                // 20% from top → Y = 1 - 0.4 = 0.6
                overlayAnchorX = 0f
                overlayAnchorY = 0.6f
            } else {
                // Bottom-right for landscape
                overlayAnchorX = 0.8f
                overlayAnchorY = -0.8f
            }

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
                    .setOverlayFrameAnchor(overlayAnchorX, overlayAnchorY)
                    .setBackgroundFrameAnchor(overlayAnchorX, overlayAnchorY)
                    .build(),
            )

            val overlays: ImmutableList<TextureOverlay> = ImmutableList.of<TextureOverlay>(textOverlay)
            val overlayEffect = OverlayEffect(overlays)

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
