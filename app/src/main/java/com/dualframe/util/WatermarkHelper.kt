package com.dualframe.util

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.sunnlab.dualframe.R
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
        mirrorHorizontally: Boolean = false,
    ): File? = withContext(Dispatchers.Main) {
        try {
            val mediaItem = MediaItem.fromUri(sourceFile.toURI().toString())

            val metadata = VideoMetadata.fromFile(sourceFile)
            val isPortrait = metadata != null && metadata.displayHeight > metadata.displayWidth

            val videoEffects = mutableListOf<androidx.media3.common.Effect>()

            // Horizontal mirror — flips saved selfie to match preview direction
            if (mirrorHorizontally) {
                videoEffects.add(
                    ScaleAndRotateTransformation.Builder()
                        .setScale(-1f, 1f)
                        .build()
                )
            }

            // Beauty effects — values from BeautyParams.kt
            // Same parameters as the preview GL shader (minus blur which Media3 can't do).
            if (applyBeauty) {
                videoEffects.add(Brightness(BeautyParams.BRIGHTNESS))
                videoEffects.add(Contrast(BeautyParams.CONTRAST))
                videoEffects.add(
                    RgbAdjustment.Builder()
                        .setRedScale(BeautyParams.RED_SCALE)
                        .setGreenScale(BeautyParams.GREEN_SCALE)
                        .setBlueScale(BeautyParams.BLUE_SCALE)
                        .build()
                )
                videoEffects.add(
                    HslAdjustment.Builder()
                        .adjustSaturation(BeautyParams.SATURATION_BOOST)
                        .build()
                )
            }

            // Watermark overlay
            if (applyWatermark) {
                val overlayAnchorX: Float
                val overlayAnchorY: Float
                if (isPortrait) {
                    overlayAnchorX = 0.85f
                    overlayAnchorY = 0.92f
                } else {
                    overlayAnchorX = 0.85f
                    overlayAnchorY = -0.85f
                }

                // Watermark size scales with FINAL output resolution (post-crop).
                // Base: UHD short edge (2160) = scale 1.0 = full size.
                // FHD (1080) = 0.5, HD (720) = 0.33. Min 0.25 to stay visible.
                val finalW = metadata?.displayWidth ?: 1080
                val finalH = metadata?.displayHeight ?: 1920
                val shortEdge = minOf(finalW, finalH).toFloat()
                val scale = (shortEdge / 2160f).coerceIn(0.25f, 1.0f)
                val baseSize = if (isPortrait) 1.0f else 0.9f
                val textSize = baseSize * scale

                val pretendard = ResourcesCompat.getFont(context, R.font.pretendard_semibold)
                    ?: Typeface.DEFAULT_BOLD
                val textOverlay = TextOverlay.createStaticTextOverlay(
                    SpannableString("DualFrame").apply {
                        setSpan(
                            ForegroundColorSpan(0x66FFFFFF.toInt()),
                            0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        setSpan(
                            RelativeSizeSpan(textSize),
                            0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        setSpan(
                            TypefaceSpan(pretendard),
                            0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
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
