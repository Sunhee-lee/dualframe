package com.dualframe.data

import android.graphics.Bitmap
import android.net.Uri

/**
 * Single source of truth for the main screen's UI state.
 * The ViewModel emits this; the Compose UI observes it.
 */
data class UiState(
    val appStatus: AppStatus = AppStatus.IDLE,
    val recordingDurationSeconds: Int = 0,
    val countdownRemaining: Int = 0, // 0 = not counting down; 3,2,1 = active countdown
    val errorMessage: String? = null,
    val exportProgress: Float = 0f, // 0..1 for current export step
    val masterFilePath: String? = null,
    // Content URIs for exported files (for open/share intents)
    val landscape16x9Uri: Uri? = null,
    val portrait9x16Uri: Uri? = null,
    val cameraReady: Boolean = false,
    // Thumbnail of latest export result
    val thumbnailBitmap: Bitmap? = null,
    // Export result info lines — truthful about which was native vs cropped
    val nativeExportInfo: String? = null,  // e.g., "9:16 (native) — 30.0 fps"
    val croppedExportInfo: String? = null, // e.g., "16:9 (cropped)"
    // Settings
    val settings: AppSettings = AppSettings(),
    // Capability-driven: which quality/fps options are available on the current camera
    val supportedQualities: List<VideoQuality> = VideoQuality.entries,
    val supportedFrameRates: List<FrameRate> = FrameRate.entries,
)

enum class AppStatus {
    IDLE,
    COUNTDOWN,
    RECORDING,
    EXPORTING_NATIVE,  // first export: passthrough or minimal crop of native framing
    EXPORTING_CROPPED, // second export: center-crop to the other aspect ratio
    EXPORT_COMPLETE,
    ERROR,
}
