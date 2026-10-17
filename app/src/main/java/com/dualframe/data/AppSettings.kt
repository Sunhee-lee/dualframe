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
    val autoSave: Boolean = false,
    val defaultFrontCamera: Boolean = false,
    val appLanguage: String = "system",
    /** Video until the user says otherwise; the choice is remembered. */
    val captureMode: CaptureMode = CaptureMode.VIDEO,
)

/** Which pipeline the shutter drives. Each binds its own CameraX use cases. */
enum class CaptureMode { VIDEO, PHOTO }

enum class VideoQuality(val label: String) {
    UHD("4K UHD"),
    FHD("FHD (1080p)"),
    HD("HD (720p)"),
}

object SettingsStore {

    private const val PREFS_NAME = "dualframe_settings"
    private const val KEY_AUDIO = "audio_enabled"
    private const val KEY_COUNTDOWN = "countdown_seconds"
    private const val KEY_QUALITY = "video_quality"
    private const val KEY_INITIALIZED = "settings_initialized"
    private const val KEY_MIRROR_FRONT = "mirror_front_camera"
    private const val KEY_FRONT_EFFECT = "front_camera_effect"
    private const val KEY_SCREEN_AWAKE = "keep_screen_awake"
    private const val KEY_GUIDES = "show_guides"
    private const val KEY_AUTO_SAVE = "auto_save"
    private const val KEY_DEFAULT_FRONT = "default_front_camera"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_CAPTURE_MODE = "capture_mode"

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
            autoSave = prefs.getBoolean(KEY_AUTO_SAVE, false),
            defaultFrontCamera = prefs.getBoolean(KEY_DEFAULT_FRONT, false),
            appLanguage = prefs.getString(KEY_LANGUAGE, "system") ?: "system",
            captureMode = try {
                CaptureMode.valueOf(prefs.getString(KEY_CAPTURE_MODE, "VIDEO") ?: "VIDEO")
            } catch (_: Exception) {
                CaptureMode.VIDEO
            },
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
            putBoolean(KEY_AUTO_SAVE, settings.autoSave)
            putBoolean(KEY_DEFAULT_FRONT, settings.defaultFrontCamera)
            putString(KEY_LANGUAGE, settings.appLanguage)
            putString(KEY_CAPTURE_MODE, settings.captureMode.name)
        }
    }

    fun isFirstLaunch(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_INITIALIZED, false)

    fun markInitialized(context: Context) {
        prefs(context).edit { putBoolean(KEY_INITIALIZED, true) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
