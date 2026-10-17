package com.dualframe.util

/**
 * Selfie effect parameters — shared between preview GL shader and saved-file Media3 effects.
 *
 * HOW TO TUNE:
 * Change these numbers, rebuild, and both preview and saved output will update
 * — genuinely, now. Both read them through BeautyShader, which writes the GLSL
 * once; neither carries a literal of its own to fall out of step with.
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

    // ── Skin smoothing (preview and saved file alike) ──
    // Shared by the preview shader and the exporter through BeautyShader, so a
    // change here moves both. The old BLUR_* pair is gone: it was a flat box
    // blur the exporter could not reproduce, so it softened hair and eyes in a
    // preview whose softness never reached the file.

    /**
     * Sampling ring radius as a fraction of frame height, not a pixel count —
     * a 1080p preview and a 2160p master then smooth across the same share of
     * a face, which is what makes the two look alike.
     */
    const val SMOOTH_RADIUS_RATIO = 0.0016f // ← 올리면 보정 범위 넓어짐

    /** How far towards the smoothed value skin is allowed to go. */
    const val SMOOTH_STRENGTH = 0.55f       // ← 올리면 더 부드러워짐

    /**
     * Brightness difference at which a neighbour stops counting. Lower keeps
     * more edges — lashes, lips, the line of a jaw — crisp; higher smooths
     * harder and starts to flatten them.
     */
    const val EDGE_THRESHOLD = 0.085f       // ← 내리면 윤곽이 더 선명
}
