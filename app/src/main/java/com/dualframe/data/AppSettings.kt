package com.dualframe.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * App settings persisted via SharedPreferences.
 * Simple and sufficient for MVP — no need for DataStore overhead.
 */
data class AppSettings(
    val videoQuality: VideoQuality = VideoQuality.AUTO,
    val audioEnabled: Boolean = true,
    val countdownSeconds: Int = 0, // 0 = off, 3, 5
    val keepScreenAwake: Boolean = true,
    val showGuides: Boolean = true,
)

enum class VideoQuality(val label: String) {
    AUTO("Auto"),
    UHD("UHD (4K)"),
    FHD("FHD (1080p)"),
    HD("HD (720p)"),
}

/**
 * Reads/writes AppSettings to SharedPreferences.
 * All keys are namespaced under "dualframe_settings".
 */
object SettingsStore {

    private const val PREFS_NAME = "dualframe_settings"
    private const val KEY_QUALITY = "video_quality"
    private const val KEY_AUDIO = "audio_enabled"
    private const val KEY_COUNTDOWN = "countdown_seconds"
    private const val KEY_SCREEN_AWAKE = "keep_screen_awake"
    private const val KEY_GUIDES = "show_guides"

    fun load(context: Context): AppSettings {
        val prefs = prefs(context)
        return AppSettings(
            videoQuality = try {
                VideoQuality.valueOf(prefs.getString(KEY_QUALITY, "AUTO") ?: "AUTO")
            } catch (_: Exception) {
                VideoQuality.AUTO
            },
            audioEnabled = prefs.getBoolean(KEY_AUDIO, true),
            countdownSeconds = prefs.getInt(KEY_COUNTDOWN, 0),
            keepScreenAwake = prefs.getBoolean(KEY_SCREEN_AWAKE, true),
            showGuides = prefs.getBoolean(KEY_GUIDES, true),
        )
    }

    fun save(context: Context, settings: AppSettings) {
        prefs(context).edit {
            putString(KEY_QUALITY, settings.videoQuality.name)
            putBoolean(KEY_AUDIO, settings.audioEnabled)
            putInt(KEY_COUNTDOWN, settings.countdownSeconds)
            putBoolean(KEY_SCREEN_AWAKE, settings.keepScreenAwake)
            putBoolean(KEY_GUIDES, settings.showGuides)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
