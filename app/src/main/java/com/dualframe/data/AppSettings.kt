package com.dualframe.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * App settings persisted via SharedPreferences.
 */
data class AppSettings(
    val audioEnabled: Boolean = true,
    val countdownSeconds: Int = 0, // 0 = off, 3, 5, 10
    val videoQuality: VideoQuality = VideoQuality.FHD,
    val frameRate: FrameRate = FrameRate.AUTO,
    val keepScreenAwake: Boolean = true,
    val showGuides: Boolean = true,
)

enum class VideoQuality(val label: String) {
    UHD("UHD (4K)"),
    FHD("FHD (1080p)"),
    HD("HD (720p)"),
}

enum class FrameRate(val fps: Int, val label: String) {
    AUTO(0, "Auto"),
    FPS_24(24, "24 fps"),
    FPS_30(30, "30 fps"),
    FPS_60(60, "60 fps"),
}

object SettingsStore {

    private const val PREFS_NAME = "dualframe_settings"
    private const val KEY_AUDIO = "audio_enabled"
    private const val KEY_COUNTDOWN = "countdown_seconds"
    private const val KEY_QUALITY = "video_quality"
    private const val KEY_FRAME_RATE = "frame_rate"
    private const val KEY_SCREEN_AWAKE = "keep_screen_awake"
    private const val KEY_GUIDES = "show_guides"

    fun load(context: Context): AppSettings {
        val prefs = prefs(context)
        return AppSettings(
            audioEnabled = prefs.getBoolean(KEY_AUDIO, true),
            countdownSeconds = prefs.getInt(KEY_COUNTDOWN, 0),
            videoQuality = try {
                VideoQuality.valueOf(prefs.getString(KEY_QUALITY, "FHD") ?: "FHD")
            } catch (_: Exception) {
                VideoQuality.FHD
            },
            frameRate = try {
                FrameRate.valueOf(prefs.getString(KEY_FRAME_RATE, "AUTO") ?: "AUTO")
            } catch (_: Exception) {
                FrameRate.AUTO
            },
            keepScreenAwake = prefs.getBoolean(KEY_SCREEN_AWAKE, true),
            showGuides = prefs.getBoolean(KEY_GUIDES, true),
        )
    }

    fun save(context: Context, settings: AppSettings) {
        prefs(context).edit {
            putBoolean(KEY_AUDIO, settings.audioEnabled)
            putInt(KEY_COUNTDOWN, settings.countdownSeconds)
            putString(KEY_QUALITY, settings.videoQuality.name)
            putString(KEY_FRAME_RATE, settings.frameRate.name)
            putBoolean(KEY_SCREEN_AWAKE, settings.keepScreenAwake)
            putBoolean(KEY_GUIDES, settings.showGuides)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
