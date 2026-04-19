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
            // Aspects match — no crop
            CropResult(-1f, 1f, -1f, 1f, 1f, 1f).also {
                Log.d(TAG, "No crop: source=${"%.3f".format(sourceAspect)} ≈ target=${"%.3f".format(targetAspect)}")
            }
        } else if (targetAspect > sourceAspect) {
            // Target is wider → crop top/bottom, keep full width
            val keepY = sourceAspect / targetAspect
            CropResult(
                left = -1f, right = 1f,
                bottom = -keepY, top = keepY,
                keepFractionX = 1f, keepFractionY = keepY,
            ).also {
                Log.d(TAG, "Crop height: keepY=${"%.3f".format(keepY)} " +
                    "(source=${"%.3f".format(sourceAspect)} → target=${"%.3f".format(targetAspect)})")
            }
        } else {
            // Target is taller → crop left/right, keep full height
            val keepX = targetAspect / sourceAspect
            CropResult(
                left = -keepX, right = keepX,
                bottom = -1f, top = 1f,
                keepFractionX = keepX, keepFractionY = 1f,
            ).also {
                Log.d(TAG, "Crop width: keepX=${"%.3f".format(keepX)} " +
                    "(source=${"%.3f".format(sourceAspect)} → target=${"%.3f".format(targetAspect)})")
            }
        }
    }

    /**
     * Convert NDC crop bounds to texture coordinate offsets.
     * NDC [-1,1] maps to texCoord [0,1]:
     *   texOffset = (1 - keepFraction) / 2
     *   texRange = keepFraction
     */
    fun ndcToTexCoords(crop: CropResult): TexCropCoords {
        val texOffsetX = (1f - crop.keepFractionX) / 2f
        val texOffsetY = (1f - crop.keepFractionY) / 2f
        return TexCropCoords(
            offsetX = texOffsetX,
            offsetY = texOffsetY,
            rangeX = crop.keepFractionX,
            rangeY = crop.keepFractionY,
        )
    }

    data class TexCropCoords(
        val offsetX: Float, // left edge in [0,1] tex space
        val offsetY: Float, // bottom edge in [0,1] tex space
        val rangeX: Float,  // width in [0,1] tex space
        val rangeY: Float,  // height in [0,1] tex space
    )
}
