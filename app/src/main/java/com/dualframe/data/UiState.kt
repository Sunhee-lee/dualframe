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
    // Display names for the saved files
    val landscape16x9Name: String? = null,
    val portrait9x16Name: String? = null,
    val cameraReady: Boolean = false,
    // Thumbnail of latest export result
    val thumbnailBitmap: Bitmap? = null,
    // Settings
    val settings: AppSettings = AppSettings(),
)

enum class AppStatus {
    IDLE,
    COUNTDOWN,
    RECORDING,
    EXPORTING_16x9,
    EXPORTING_9x16,
    EXPORT_COMPLETE,
    ERROR,
}
