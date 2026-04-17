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
    const val BRIGHTNESS = 0.065f           // ← 올리면 더 밝아짐 (0.035→0.065 더 티나게)

    // ── Contrast (선명도) ──
    const val CONTRAST = 0.08f              // ← 올리면 대비 강해짐 (0.06→0.08 미세 상향)

    // ── RGB (warmth) ──
    // 온도 살짝 낮춤: red 줄이고 blue 올림
    const val RED_SCALE = 1.01f             // ← 1.025→1.01 따뜻함 줄임
    const val GREEN_SCALE = 1.005f          // ← 유지
    const val BLUE_SCALE = 0.99f            // ← 0.975→0.99 차가움 줄임 (온도 낮춤)

    // ── Saturation ──
    // Percentage-point boost for HslAdjustment. 0 = off, 5 = vivid.
    const val SATURATION_BOOST = 3f         // ← 올리면 색이 진해짐

    // ── Blur (preview only — Media3 has no blur) ──
    // Texel offset for 4-sample box blur. 0 = off.
    const val BLUR_OFFSET = 0.0018f         // ← 올리면 블러 범위 커짐
    // Blend ratio of blurred vs original. 0 = no blur, 1 = full blur.
    const val BLUR_MIX = 0.20f              // ← 올리면 더 부드러워짐
}
