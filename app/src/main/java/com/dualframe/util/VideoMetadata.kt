package com.dualframe.util

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * Reads actual video metadata from a recorded file.
 * Used by ExportManager to compute accurate crop rectangles
 * instead of assuming a fixed source aspect ratio.
 */
data class VideoMetadata(
    val rawWidth: Int,       // Width as stored in the file (before rotation)
    val rawHeight: Int,      // Height as stored in the file (before rotation)
    val rotation: Int,       // Rotation degrees (0, 90, 180, 270)
    val displayWidth: Int,   // Width as displayed after applying rotation
    val displayHeight: Int,  // Height as displayed after applying rotation
) {
    /** Display aspect ratio (width / height) after rotation. */
    val displayAspectRatio: Float
        get() = displayWidth.toFloat() / displayHeight.toFloat()

    companion object {
        private const val TAG = "VideoMetadata"

        /**
         * Read metadata from a video file using MediaMetadataRetriever.
         * Returns null if the file can't be read or dimensions are missing.
         */
        fun fromFile(file: File): VideoMetadata? {
            if (!file.exists() || file.length() == 0L) {
                Log.e(TAG, "File doesn't exist or is empty: ${file.absolutePath}")
                return null
            }

            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)

                val rawWidth = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull()

                val rawHeight = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull()

                val rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull() ?: 0

                if (rawWidth == null || rawHeight == null || rawWidth <= 0 || rawHeight <= 0) {
                    Log.e(TAG, "Invalid dimensions: ${rawWidth}x${rawHeight}")
                    return null
                }

                // After rotation, width and height may swap.
                // 90° or 270° rotation swaps width/height.
                val (displayW, displayH) = if (rotation == 90 || rotation == 270) {
                    rawHeight to rawWidth
                } else {
                    rawWidth to rawHeight
                }

                val metadata = VideoMetadata(
                    rawWidth = rawWidth,
                    rawHeight = rawHeight,
                    rotation = rotation,
                    displayWidth = displayW,
                    displayHeight = displayH,
                )

                Log.i(TAG, "Video metadata: raw=${rawWidth}x${rawHeight}, " +
                    "rotation=$rotation, display=${displayW}x${displayH}, " +
                    "aspect=${metadata.displayAspectRatio}")

                metadata
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read video metadata", e)
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) { }
            }
        }
    }
}
