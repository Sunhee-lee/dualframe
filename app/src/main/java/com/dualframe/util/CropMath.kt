package com.dualframe.util

import android.util.Log

/**
 * Shared center-crop math used by BOTH preview renderer and export pipeline.
 * Guarantees WYSIWYG — preview and saved output show the exact same region.
 *
 * All bounds are in normalized [-1, 1] space (Media3 Crop convention):
 *   left=-1 is left edge, right=1 is right edge
 *   bottom=-1 is bottom edge, top=1 is top edge
 *
 * Preview renderer converts these bounds to texture coordinates.
 * ExportManager passes them directly to Media3 Crop effect.
 */
object CropMath {

    private const val TAG = "CropMath"

    data class CropResult(
        val left: Float,       // NDC left bound [-1, 1]
        val right: Float,      // NDC right bound [-1, 1]
        val bottom: Float,     // NDC bottom bound [-1, 1]
        val top: Float,        // NDC top bound [-1, 1]
        val keepFractionX: Float, // fraction of width kept (0..1)
        val keepFractionY: Float, // fraction of height kept (0..1)
    )

    /**
     * Compute center-crop bounds to transform sourceAspect → targetAspect.
     *
     * @param sourceAspect width/height of the source (post-rotation display aspect)
     * @param targetAspect width/height of the desired output
     * @return CropResult with NDC bounds and keep fractions
     */
    fun centerCrop(sourceAspect: Float, targetAspect: Float): CropResult {
        val tolerance = 0.01f

        return if (kotlin.math.abs(targetAspect - sourceAspect) < tolerance) {
            CropResult(-1f, 1f, -1f, 1f, 1f, 1f)
        } else if (targetAspect > sourceAspect) {
            val keepY = sourceAspect / targetAspect
            CropResult(
                left = -1f, right = 1f,
                bottom = -keepY, top = keepY,
                keepFractionX = 1f, keepFractionY = keepY,
            )
        } else {
            val keepX = targetAspect / sourceAspect
            CropResult(
                left = -keepX, right = keepX,
                bottom = -1f, top = 1f,
                keepFractionX = keepX, keepFractionY = 1f,
            )
        }
    }

    /**
     * Center-crop with a vertical offset for user-adjustable landscape framing.
     *
     * @param verticalOffset normalized [-1, 1] where -1 = crop window at bottom,
     *   0 = center (default), +1 = crop window at top of the source frame.
     *   Only affects crops that remove top/bottom (target wider than source).
     */
    fun centerCropWithVerticalOffset(
        sourceAspect: Float,
        targetAspect: Float,
        verticalOffset: Float = 0f,
    ): CropResult {
        val base = centerCrop(sourceAspect, targetAspect)
        if (verticalOffset == 0f || base.keepFractionY >= 1f) return base

        val maxShift = 1f - base.keepFractionY
        val shift = verticalOffset.coerceIn(-1f, 1f) * maxShift
        return base.copy(
            bottom = base.bottom + shift,
            top = base.top + shift,
        )
    }

    /**
     * Convert NDC crop bounds to texture coordinate offsets.
     * NDC [-1,1] maps to texCoord [0,1]: tex = (ndc + 1) / 2.
     * Handles both centered and offset crops.
     */
    fun ndcToTexCoords(crop: CropResult): TexCropCoords {
        val texLeft = (crop.left + 1f) / 2f
        val texBottom = (crop.bottom + 1f) / 2f
        val texRight = (crop.right + 1f) / 2f
        val texTop = (crop.top + 1f) / 2f
        return TexCropCoords(
            offsetX = texLeft,
            offsetY = texBottom,
            rangeX = texRight - texLeft,
            rangeY = texTop - texBottom,
        )
    }

    data class TexCropCoords(
        val offsetX: Float, // left edge in [0,1] tex space
        val offsetY: Float, // bottom edge in [0,1] tex space
        val rangeX: Float,  // width in [0,1] tex space
        val rangeY: Float,  // height in [0,1] tex space
    )
}
