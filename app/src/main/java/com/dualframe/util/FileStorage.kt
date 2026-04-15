package com.dualframe.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles file creation for video recordings and exports.
 *
 * Strategy:
 * - Master recordings go to app-private storage first (fast, no permissions needed).
 * - Exported files get copied to MediaStore (Movies/) so they're visible in gallery.
 * - We use timestamps for deterministic, collision-free naming.
 */
object FileStorage {

    private const val DIRECTORY = "DualFrame"
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** Create a temp file in app-private cache for the master recording. */
    fun createMasterFile(context: Context): File {
        val dir = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val stamp = dateFormat.format(Date())
        return File(dir, "DF_${stamp}_master.mp4")
    }

    /** Build output file path for an export (stays in cache until saved to MediaStore). */
    fun createExportFile(context: Context, suffix: String): File {
        val dir = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val stamp = dateFormat.format(Date())
        return File(dir, "DF_${stamp}_$suffix.mp4")
    }

    /**
     * Copy a finished video file into MediaStore Movies/ so it appears in the gallery.
     * Returns the content URI on success, null on failure.
     */
    fun saveToMediaStore(context: Context, sourceFile: File, displayName: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$DIRECTORY")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Clean up old temp files from app cache. */
    fun cleanupCache(context: Context) {
        val dir = File(context.cacheDir, DIRECTORY)
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
