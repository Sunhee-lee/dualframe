package com.dualframe.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileStorage {

    private const val TAG = "FileStorage"
    private const val TEMP_DIR = "dualframe_export_temp"
    private const val MASTER_DIR = "masters"
    private const val GALLERY_DIR = "DualFrame"
    private const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // Master lives in filesDir (survives Android's cache auto-cleanup under low storage).
    // Only removed when a) both V/H MediaStore saves verified, b) user explicit delete,
    // or c) user explicit [다시 촬영]/BackHandler at EXPORT_COMPLETE (discardMaster).
    fun createMasterFile(context: Context): File {
        val dir = File(context.filesDir, MASTER_DIR).apply { mkdirs() }
        val stamp = dateFormat.format(Date())
        return File(dir, "DF_${stamp}_master.mp4")
    }

    // V/H temp files stay in cacheDir; deleted immediately after each save step.
    fun createExportFile(context: Context, suffix: String): File {
        val dir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
        val stamp = dateFormat.format(Date())
        return File(dir, "DF_${stamp}_$suffix.mp4")
    }

    // MediaStore displayName: preserves the historical `DF_${nowStamp}_${suffix}.mp4`
    // convention so gallery filenames stay stable across the Approach C change.
    // masterFile is passed for a possible future switch to master-based timestamps.
    @Suppress("UNUSED_PARAMETER")
    fun makeDisplayName(masterFile: File, suffix: String): String {
        val stamp = dateFormat.format(Date())
        return "DF_${stamp}_$suffix.mp4"
    }

    fun hasEnoughStorage(context: Context, requiredBytes: Long): Boolean {
        val stat = StatFs(context.cacheDir.absolutePath)
        val available = stat.availableBytes
        return available > requiredBytes
    }

    // null = StatFs query failed (undetermined). Caller must treat this as
    // "unable to preflight" — NOT as 0 bytes — and fall through to the actual
    // save, which will still fail safely (markFailed + master preserved).
    fun availableInternalStorageBytes(context: Context): Long? =
        try { StatFs(context.filesDir.absolutePath).availableBytes } catch (_: Exception) { null }

    fun saveToMediaStore(context: Context, sourceFile: File, displayName: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$GALLERY_DIR")
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
            Log.e(TAG, "MediaStore save failed, cleaning pending Uri", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun deleteTempFile(file: File?) {
        if (file == null) return
        runCatching {
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "Deleted temp: ${file.name}")
            }
        }
    }

    // Cleans stale export temp files from cacheDir only.
    // Master files in filesDir/masters/ are NEVER auto-cleaned here — they are
    // managed by MasterRecoveryStore (markCompletedAndDeleteMaster / deleteFailedMaster / discardMaster).
    fun cleanupStaleTemp(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, TEMP_DIR)
            if (!dir.exists()) return
            val now = System.currentTimeMillis()
            var count = 0
            dir.listFiles()?.forEach { file ->
                if (now - file.lastModified() > STALE_THRESHOLD_MS) {
                    file.delete()
                    count++
                }
            }
            if (count > 0) Log.i(TAG, "Cleaned $count stale temp files (>24h)")
        }
    }

}
