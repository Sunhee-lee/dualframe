package com.dualframe.data

/**
 * Single source of truth for the main screen's UI state.
 * The ViewModel emits this; the Compose UI observes it.
 */
data class UiState(
    val appStatus: AppStatus = AppStatus.IDLE,
    val recordingDurationSeconds: Int = 0,
    val errorMessage: String? = null,
    val exportProgress: Float = 0f, // 0..1 for current export
    val masterFilePath: String? = null,
    val landscape16x9Path: String? = null,
    val portrait9x16Path: String? = null,
    val cameraReady: Boolean = false,
    val hasAudioPermission: Boolean = false,
)

enum class AppStatus {
    IDLE,
    RECORDING,
    EXPORTING_16x9,
    EXPORTING_9x16,
    EXPORT_COMPLETE,
    ERROR,
}
