package com.dualframe.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

data class AppSettings(
    val audioEnabled: Boolean = true,
    val countdownSeconds: Int = 0,
    val videoQuality: VideoQuality = VideoQuality.FHD,
    val saveSelfieUnmirrored: Boolean = true,
    val frontCameraEffect: Boolean = true,
    val keepScreenAwake: Boolean = true,
    val showGuides: Boolean = true,
    // [PoC: feature/master-poc] When true, request a 4:3 portrait ViewPort
    // so 9:16 and 16:9 outputs are derived from a common, more square master.
    // Default OFF — ships current behavior unchanged. Toggle in Settings.
    val experimentalCommonMaster: Boolean = false,
)

enum class VideoQuality(val label: String) {
    UHD("UHD (4K)"),
    FHD("FHD (1080p)"),
    HD("HD (720p)"),
}

object SettingsStore {

    private const val PREFS_NAME = "dualframe_settings"
    private const val KEY_AUDIO = "audio_enabled"
    private const val KEY_COUNTDOWN = "countdown_seconds"
    private const val KEY_QUALITY = "video_quality"
    private const val KEY_MIRROR_FRONT = "mirror_front_camera"
    private const val KEY_FRONT_EFFECT = "front_camera_effect"
    private const val KEY_SCREEN_AWAKE = "keep_screen_awake"
    private const val KEY_GUIDES = "show_guides"
    private const val KEY_COMMON_MASTER = "experimental_common_master"

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
            saveSelfieUnmirrored = prefs.getBoolean(KEY_MIRROR_FRONT, true),
            frontCameraEffect = prefs.getBoolean(KEY_FRONT_EFFECT, true),
            keepScreenAwake = prefs.getBoolean(KEY_SCREEN_AWAKE, true),
            showGuides = prefs.getBoolean(KEY_GUIDES, true),
            experimentalCommonMaster = prefs.getBoolean(KEY_COMMON_MASTER, false),
        )
    }

    fun save(context: Context, settings: AppSettings) {
        prefs(context).edit {
            putBoolean(KEY_AUDIO, settings.audioEnabled)
            putInt(KEY_COUNTDOWN, settings.countdownSeconds)
            putString(KEY_QUALITY, settings.videoQuality.name)
            putBoolean(KEY_MIRROR_FRONT, settings.saveSelfieUnmirrored)
            putBoolean(KEY_FRONT_EFFECT, settings.frontCameraEffect)
            putBoolean(KEY_SCREEN_AWAKE, settings.keepScreenAwake)
            putBoolean(KEY_GUIDES, settings.showGuides)
            putBoolean(KEY_COMMON_MASTER, settings.experimentalCommonMaster)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
