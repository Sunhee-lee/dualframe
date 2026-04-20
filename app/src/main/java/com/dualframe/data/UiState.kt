package com.dualframe.data

import android.graphics.Bitmap
import android.net.Uri

data class UiState(
    val appStatus: AppStatus = AppStatus.IDLE,
    val recordingDurationSeconds: Int = 0,
    val countdownRemaining: Int = 0,
    val errorMessage: String? = null,
    val exportProgress: Float = 0f,
    val masterFilePath: String? = null,
    val cameraReady: Boolean = false,
    val flashOn: Boolean = false,
    // Thumbnail of latest export result
    val thumbnailBitmap: Bitmap? = null,
    // Export result info (shown in result panel)
    val portraitExportInfo: String? = null,
    val landscapeExportInfo: String? = null,
    // Temp file paths for exported files (NOT yet saved to gallery)
    val portraitTempPath: String? = null,
    val landscapeTempPath: String? = null,
    // Saved URIs (set only after explicit user save action)
    val savedPortraitUri: Uri? = null,
    val savedLandscapeUri: Uri? = null,
    // Save status message (e.g., "Saved to gallery", "Saving...")
    val saveMessage: String? = null,
    // Whether the Remove Watermark dialog is showing
    val showRemoveWatermarkDialog: Boolean = false,
    // Settings
    val settings: AppSettings = AppSettings(),
    val supportedQualities: List<VideoQuality> = VideoQuality.entries,
)

enum class AppStatus {
    IDLE,
    COUNTDOWN,
    RECORDING,
    EXPORTING_PORTRAIT,
    EXPORTING_LANDSCAPE,
    EXPORT_COMPLETE, // result ready in temp, not yet saved to gallery
    SAVING,          // actively saving to gallery
    ERROR,
}
