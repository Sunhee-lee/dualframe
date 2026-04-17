package com.dualframe.util

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.RgbAdjustment
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
 * Applies watermark and/or beauty effects to a video file using Media3 Transformer.
 *
 * Beauty parameters (saved video, approximated via Media3 built-in effects):
 *   brightness=0.02, warmth=0.01 (red lift), saturation=1.01
 * Note: smooth/mix (blur) and gamma are preview-only — Media3 1.5.x has no
 * built-in blur/gamma effect and implementing a custom GlShaderProgram is out of scope.
 */
@UnstableApi
object WatermarkHelper {

    private const val TAG = "WatermarkHelper"

    /**
     * Re-encode sourceFile applying optional watermark and/or beauty.
     * Returns the output file, or null on failure.
     */
    suspend fun applyEffects(
        context: Context,
        sourceFile: File,
        outputFile: File,
        applyWatermark: Boolean,
        applyBeauty: Boolean,
    ): File? = withContext(Dispatchers.Main) {
        try {
            val mediaItem = MediaItem.fromUri(sourceFile.toURI().toString())

            val metadata = VideoMetadata.fromFile(sourceFile)
            val isPortrait = metadata != null && metadata.displayHeight > metadata.displayWidth

            val videoEffects = mutableListOf<androidx.media3.common.Effect>()

            // Beauty effects (saved-file approximation)
            if (applyBeauty) {
                videoEffects.add(Brightness(0.02f))
                videoEffects.add(
                    RgbAdjustment.Builder()
                        .setRedScale(1.01f)
                        .build()
                )
                videoEffects.add(
                    HslAdjustment.Builder()
                        .adjustSaturation(1f)
                        .build()
                )
            }

            // Watermark overlay
            if (applyWatermark) {
                val overlayAnchorX: Float
                val overlayAnchorY: Float
                if (isPortrait) {
                    overlayAnchorX = 0.85f
                    overlayAnchorY = 0.85f
                } else {
                    overlayAnchorX = 0.85f
                    overlayAnchorY = -0.85f
                }

                val textOverlay = TextOverlay.createStaticTextOverlay(
                    android.text.SpannableString("DualFrame").apply {
                        setSpan(
                            android.text.style.ForegroundColorSpan(0xB3FFFFFF.toInt()),
                            0, length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        setSpan(
                            android.text.style.RelativeSizeSpan(if (isPortrait) 1.1f else 0.96f),
                            0, length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    },
                    OverlaySettings.Builder()
                        .setOverlayFrameAnchor(overlayAnchorX, overlayAnchorY)
                        .setBackgroundFrameAnchor(overlayAnchorX, overlayAnchorY)
                        .build(),
                )

                val overlays: ImmutableList<TextureOverlay> =
                    ImmutableList.of<TextureOverlay>(textOverlay)
                videoEffects.add(OverlayEffect(overlays))
            }

            if (videoEffects.isEmpty()) {
                Log.w(TAG, "applyEffects called with no effects — skipping")
                return@withContext null
            }

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(listOf(), videoEffects.toList()))
                .build()

            runTransformer(context, editedMediaItem, outputFile)
            Log.i(TAG, "Effects applied (watermark=$applyWatermark, beauty=$applyBeauty): " +
                "${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "applyEffects failed", e)
            outputFile.delete()
            null
        }
    }

    /** Legacy alias — watermark only. */
    suspend fun applyWatermark(
        context: Context,
        sourceFile: File,
        outputFile: File,
    ): File? = applyEffects(context, sourceFile, outputFile, applyWatermark = true, applyBeauty = false)

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
