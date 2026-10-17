package com.dualframe.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Turns one still capture into the two frames the app saves.
 *
 * The same shape as the video pipeline: one master, cropped twice. The portrait
 * frame is the ViewPort region CameraX already framed to 9:16, and the landscape
 * one is cut from it with [CropMath] — the same function the preview renderer and
 * the video exporter use, so all three agree on what the user was looking at.
 */
object PhotoSplitter {

    private const val TAG = "PhotoSplitter"
    private const val DIAG_TAG = "DualFrameCameraDiag"

    private const val JPEG_QUALITY = 95
    private const val LANDSCAPE_ASPECT = 16f / 9f

    data class Result(
        val portrait: File,
        val landscape: File,
        /** For the diagnostics the first release needs: what we actually got. */
        val decodedSize: String,
        val portraitSize: String,
        val landscapeSize: String,
    )

    /**
     * @param cropRect the ViewPort rect CameraX reports on the captured image,
     *   in the JPEG's own coordinates and before rotation.
     * @param rotationDegrees the image's reported rotation, applied after cropping
     *   so the saved file is upright.
     * @param verticalOffset the user's framing offset, identical in meaning to the
     *   one the video export takes.
     */
    suspend fun splitAndSave(
        context: Context,
        jpegBytes: ByteArray,
        cropRect: Rect?,
        rotationDegrees: Int,
        verticalOffset: Float,
    ): Result? = withContext(Dispatchers.IO) {
        var decoded: Bitmap? = null
        var portraitBmp: Bitmap? = null
        var landscapeBmp: Bitmap? = null
        try {
            decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            if (decoded == null) {
                Log.e(TAG, "JPEG decode returned null (${jpegBytes.size} bytes)")
                return@withContext null
            }
            val decodedSize = "${decoded.width}x${decoded.height}"

            portraitBmp = applyViewPortAndRotation(decoded, cropRect, rotationDegrees)
            if (portraitBmp !== decoded) {
                decoded.recycle()
                decoded = null
            }
            val portraitSize = "${portraitBmp.width}x${portraitBmp.height}"

            landscapeBmp = cropToLandscape(portraitBmp, verticalOffset)
            val landscapeSize = "${landscapeBmp.width}x${landscapeBmp.height}"

            Log.i(DIAG_TAG, "=== PHOTO SPLIT ===")
            Log.i(DIAG_TAG, "  JPEG decoded      : $decodedSize")
            Log.i(DIAG_TAG, "  ViewPort cropRect : $cropRect (rotation=$rotationDegrees)")
            Log.i(DIAG_TAG, "  Portrait saved    : $portraitSize " +
                "(aspect=${"%.4f".format(portraitBmp.width.toFloat() / portraitBmp.height)})")
            Log.i(DIAG_TAG, "  Landscape saved   : $landscapeSize " +
                "(aspect=${"%.4f".format(landscapeBmp.width.toFloat() / landscapeBmp.height)}, " +
                "offset=$verticalOffset)")
            Log.i(DIAG_TAG, "===================")

            val portraitFile = FileStorage.createPhotoFile(context, "V")
            val landscapeFile = FileStorage.createPhotoFile(context, "H")
            writeJpeg(portraitBmp, portraitFile)
            writeJpeg(landscapeBmp, landscapeFile)

            Result(portraitFile, landscapeFile, decodedSize, portraitSize, landscapeSize)
        } catch (e: OutOfMemoryError) {
            // A full-resolution still is a large allocation and this app has no
            // largeHeap. Report it rather than taking the process down.
            Log.e(TAG, "Out of memory splitting the capture", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Photo split failed", e)
            null
        } finally {
            decoded?.recycle()
            if (portraitBmp !== landscapeBmp) portraitBmp?.recycle()
            landscapeBmp?.recycle()
        }
    }

    /**
     * Cuts the still down to what the ViewPort framed and turns it upright.
     *
     * CameraX reports the ViewPort region on the captured image but leaves the
     * JPEG itself whole, so the crop has to be applied here for the saved frame
     * to match what the preview showed.
     */
    private fun applyViewPortAndRotation(
        source: Bitmap,
        cropRect: Rect?,
        rotationDegrees: Int,
    ): Bitmap {
        val full = Rect(0, 0, source.width, source.height)
        val rect = cropRect?.let { Rect(it) }?.takeIf { it.intersect(full) && !it.isEmpty } ?: full
        val needsCrop = rect != full
        val needsRotation = rotationDegrees % 360 != 0
        if (!needsCrop && !needsRotation) return source

        val matrix = if (needsRotation) {
            Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        } else {
            null
        }
        return Bitmap.createBitmap(
            source, rect.left, rect.top, rect.width(), rect.height(), matrix, true,
        )
    }

    /** Derives the landscape frame, exactly as the exporter derives its own. */
    private fun cropToLandscape(portrait: Bitmap, verticalOffset: Float): Bitmap {
        val sourceAspect = portrait.width.toFloat() / portrait.height
        val crop = CropMath.centerCropWithVerticalOffset(
            sourceAspect, LANDSCAPE_ASPECT, verticalOffset,
        )
        val tex = CropMath.ndcToTexCoords(crop)

        // NDC y runs bottom-up; bitmap rows run top-down, so the top edge of the
        // bitmap rect comes from the far side of the texture range.
        val x = (tex.offsetX * portrait.width).toInt()
        val y = ((1f - (tex.offsetY + tex.rangeY)) * portrait.height).toInt()
        val w = (tex.rangeX * portrait.width).toInt()
        val h = (tex.rangeY * portrait.height).toInt()

        val safeX = x.coerceIn(0, (portrait.width - 1).coerceAtLeast(0))
        val safeY = y.coerceIn(0, (portrait.height - 1).coerceAtLeast(0))
        val safeW = w.coerceIn(1, portrait.width - safeX)
        val safeH = h.coerceIn(1, portrait.height - safeY)
        return Bitmap.createBitmap(portrait, safeX, safeY, safeW, safeH)
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }
}
