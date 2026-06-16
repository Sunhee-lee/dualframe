package com.dualframe.data

import android.graphics.Bitmap
import android.net.Uri

data class UiState(
    val appStatus: AppStatus = AppStatus.IDLE,
    val recordingDurationSeconds: Int = 0,
    val countdownRemaining: Int = 0,
    val errorMessage: String? = null,
    val exportProgress: Float = 0f,
    val cameraReady: Boolean = false,
    val flashOn: Boolean = false,
    val masterIsPortrait: Boolean = true,
    val wasFrontCamera: Boolean = false,
    // Thumbnails of latest export results
    val thumbnailBitmap: Bitmap? = null,           // portrait thumbnail
    val landscapeThumbnailBitmap: Bitmap? = null,  // landscape thumbnail
    // Export result info (shown in result panel)
    val nativeExportInfo: String? = null,
    val croppedExportInfo: String? = null,
    // Temp file paths for exported files (NOT yet saved to gallery)
    val nativeTempPath: String? = null,
    val croppedTempPath: String? = null,
    // Saved URIs (set only after explicit user save action)
    val savedNativeUri: Uri? = null,
    val savedCroppedUri: Uri? = null,
    // Save status message (e.g., "Saved to gallery", "Saving...")
    val saveMessage: String? = null,
    // Whether the Remove Watermark dialog is showing
    val showRemoveWatermarkDialog: Boolean = false,
    val adFailDialog: AdFailType? = null,
    val showFhdFallbackToast: Boolean = false,
    // Settings
    val settings: AppSettings = AppSettings(),
    val supportedQualities: List<VideoQuality> = VideoQuality.entries,
)

enum class AdFailType {
    OFFLINE,
    REPEATED_FAILURE,
}

enum class AppStatus {
    IDLE,
    COUNTDOWN,
    RECORDING,
    EXPORTING_NATIVE,
    EXPORTING_CROPPED,
    EXPORT_COMPLETE, // result ready in temp, not yet saved to gallery
    SAVING,          // actively saving to gallery
    ERROR,
}
