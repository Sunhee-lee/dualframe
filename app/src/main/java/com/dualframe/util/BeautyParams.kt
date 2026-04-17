package com.dualframe.util

/**
 * Selfie effect parameters — shared between preview GL shader and saved-file Media3 effects.
 *
 * HOW TO TUNE:
 * Change these numbers, rebuild, and both preview and saved output will update.
 * Preview also has a subtle blur (BLUR_*) that saved files can't replicate
 * because Media3 1.5.x has no built-in blur effect.
 *
 * Media3 built-in effects used for saved files:
 *   Brightness(BRIGHTNESS)
 *   Contrast(CONTRAST)
 *   RgbAdjustment(red=RED_SCALE, green=GREEN_SCALE, blue=BLUE_SCALE)
 *   HslAdjustment(saturation=SATURATION_BOOST)
 */
object BeautyParams {

    // ── Brightness ──
    // Additive brightness shift. 0 = off, 0.05 = noticeably brighter, 0.1 = very bright.
    const val BRIGHTNESS = 0.08f            // ← 밝기 (0.065→0.08)

    const val CONTRAST = 0.08f              // ← 선명도 유지

    // 노란기 줄임: red 더 낮추고, green도 미세하게 낮춤
    const val RED_SCALE = 1.0f              // ← 1.01→1.0 노란기 제거
    const val GREEN_SCALE = 0.995f          // ← 1.005→0.995 노란기 줄임
    const val BLUE_SCALE = 1.005f           // ← 0.99→1.005 약간 쿨톤

    // ── Saturation ──
    // Percentage-point boost for HslAdjustment. 0 = off, 5 = vivid.
    const val SATURATION_BOOST = 3f         // ← 올리면 색이 진해짐

    // ── Blur (preview only — Media3 has no blur) ──
    // Texel offset for 4-sample box blur. 0 = off.
    const val BLUR_OFFSET = 0.0018f         // ← 올리면 블러 범위 커짐
    // Blend ratio of blurred vs original. 0 = no blur, 1 = full blur.
    const val BLUR_MIX = 0.20f              // ← 올리면 더 부드러워짐
}
