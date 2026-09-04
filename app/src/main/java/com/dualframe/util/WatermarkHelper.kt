package com.dualframe.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
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
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.sunnlab.dualframe.R
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.ceil

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
    private const val PROGRESS_POLL_MS = 200L

    /**
     * Re-encode sourceFile applying optional watermark and/or beauty.
     * Returns the output file, or null on failure.
     *
     * [onProgress] receives 0f..1f while the transcode runs. Applying any effect
     * forces a full decode -> GL -> re-encode pass, which is slow on long or
     * high-resolution clips, so callers should surface this to the user.
     */
    suspend fun applyEffects(
        context: Context,
        sourceFile: File,
        outputFile: File,
        applyWatermark: Boolean,
        applyBeauty: Boolean,
        mirrorHorizontally: Boolean = false,
        onProgress: (Float) -> Unit = {},
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

                val watermark = buildWatermarkBitmap(context, textSize)

                // The stroke padding grows the overlay bitmap, and the overlay anchor
                // is normalised against that bitmap — so shrinking the anchor by the
                // same ratio puts every text pixel exactly where it sat before.
                // See buildWatermarkBitmap for why the padding is needed at all.
                val settings = OverlaySettings.Builder()
                    .setOverlayFrameAnchor(
                        overlayAnchorX * watermark.contentWidth / watermark.bitmap.width.toFloat(),
                        overlayAnchorY * watermark.contentHeight / watermark.bitmap.height.toFloat(),
                    )
                    .setBackgroundFrameAnchor(overlayAnchorX, overlayAnchorY)
                    .build()

                val overlays: ImmutableList<TextureOverlay> = ImmutableList.of<TextureOverlay>(
                    BitmapOverlay.createStaticBitmapOverlay(watermark.bitmap, settings),
                )
                videoEffects.add(OverlayEffect(overlays))
            }

            if (videoEffects.isEmpty()) {
                Log.w(TAG, "applyEffects called with no effects — skipping")
                return@withContext null
            }

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(listOf(), videoEffects.toList()))
                .build()

            runTransformer(context, editedMediaItem, outputFile, onProgress)
            Log.i(TAG, "Effects applied (watermark=$applyWatermark, beauty=$applyBeauty): " +
                "${outputFile.absolutePath}")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "applyEffects failed", e)
            outputFile.delete()
            null
        }
    }


    /** A rendered watermark plus the text box inside it, excluding stroke padding. */
    private class WatermarkBitmap(
        val bitmap: Bitmap,
        val contentWidth: Int,
        val contentHeight: Int,
    )

    /**
     * Renders the watermark to a bitmap: a thin dark stroke first, then the white
     * fill on top of it at the same position.
     *
     * We build the bitmap ourselves rather than using TextOverlay because
     * TextOverlay sizes its bitmap to the StaticLayout bounds exactly, with no
     * slack — half of the stroke falls outside the glyph outline and would be
     * clipped at the bitmap edge.
     *
     * Geometry is otherwise identical to what TextOverlay produced: the same
     * TextOverlay.TEXT_SIZE_PIXELS base scaled by [textScale], measured with the
     * same StaticLayout, so the mark keeps its previous size. Position is
     * preserved by the anchor correction at the call site.
     */
    private fun buildWatermarkBitmap(context: Context, textScale: Float): WatermarkBitmap {
        val pretendard = ResourcesCompat.getFont(context, R.font.pretendard_semibold)
            ?: Typeface.DEFAULT_BOLD
        val textSizePx = TextOverlay.TEXT_SIZE_PIXELS * textScale
        val strokeWidthPx = textSizePx * WatermarkStyle.STROKE_WIDTH_RATIO

        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = pretendard
            textSize = textSizePx
            style = Paint.Style.FILL
            color = WatermarkStyle.FILL_COLOR
        }
        val strokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = pretendard
            textSize = textSizePx
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            strokeJoin = Paint.Join.ROUND
            color = WatermarkStyle.STROKE_COLOR
        }

        // Stroke style doesn't affect glyph advances, so both layouts measure alike.
        val contentWidth = ceil(fillPaint.measureText(WatermarkStyle.TEXT)).toInt().coerceAtLeast(1)
        val fillLayout = singleLineLayout(fillPaint, contentWidth)
        val strokeLayout = singleLineLayout(strokePaint, contentWidth)
        val contentHeight = fillLayout.height.coerceAtLeast(1)

        // Half the stroke sits outside the outline; +1 covers antialiasing.
        val pad = ceil(strokeWidthPx / 2f).toInt() + 1

        val bitmap = Bitmap.createBitmap(
            contentWidth + 2 * pad, contentHeight + 2 * pad, Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.translate(pad.toFloat(), pad.toFloat())
        strokeLayout.draw(canvas)
        fillLayout.draw(canvas)

        Log.i(TAG, "Watermark bitmap: ${bitmap.width}x${bitmap.height} " +
            "(text ${contentWidth}x$contentHeight, textSize ${"%.1f".format(textSizePx)}px, " +
            "stroke ${"%.2f".format(strokeWidthPx)}px, pad ${pad}px)")

        return WatermarkBitmap(bitmap, contentWidth, contentHeight)
    }

    private fun singleLineLayout(paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder
            .obtain(WatermarkStyle.TEXT, 0, WatermarkStyle.TEXT.length, paint, width)
            .build()

    private suspend fun runTransformer(
        context: Context,
        editedMediaItem: EditedMediaItem,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ) = suspendCancellableCoroutine { cont ->
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
                handler.postDelayed(this, PROGRESS_POLL_MS)
            }
        }

        transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    handler.removeCallbacks(pollRunnable)
                    onProgress(1f)
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                    handler.removeCallbacks(pollRunnable)
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
            .build()
        transformer.start(editedMediaItem, outputFile.absolutePath)
        handler.postDelayed(pollRunnable, PROGRESS_POLL_MS)

        cont.invokeOnCancellation {
            transformer.cancel()
            handler.removeCallbacks(pollRunnable)
            outputFile.delete()
        }
    }
}
