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
    const val BRIGHTNESS = 0.035f           // ← 올리면 더 밝아짐

    // ── Contrast ──
    // Contrast boost. 0 = off, 0.1 = moderate, -0.1 = flatter.
    // Keeps the image from looking washed out after brightness lift.
    const val CONTRAST = 0.06f              // ← 올리면 대비 강해짐

    // ── RGB (warmth) ──
    // Channel multipliers. red>1 + blue<1 = warm tone.
    const val RED_SCALE = 1.025f            // ← 올리면 따뜻해짐
    const val GREEN_SCALE = 1.005f          // ← 올리면 초록끼
    const val BLUE_SCALE = 0.975f           // ← 내리면 따뜻해짐

    // ── Saturation ──
    // Percentage-point boost for HslAdjustment. 0 = off, 5 = vivid.
    const val SATURATION_BOOST = 3f         // ← 올리면 색이 진해짐

    // ── Blur (preview only — Media3 has no blur) ──
    // Texel offset for 4-sample box blur. 0 = off.
    const val BLUR_OFFSET = 0.0018f         // ← 올리면 블러 범위 커짐
    // Blend ratio of blurred vs original. 0 = no blur, 1 = full blur.
    const val BLUR_MIX = 0.20f              // ← 올리면 더 부드러워짐
}
