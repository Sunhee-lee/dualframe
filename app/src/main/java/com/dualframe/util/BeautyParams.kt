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
     * Master switch. Set to false and the exporter chains Media3's own tone
     * effects instead of our shader, and the preview's smoothing collapses to a
     * pass-through — nothing else has to change. This is the safe way out if a
     * device turns out to pay too much for the smoothing; [SMOOTH_STRENGTH] is
     * the gentler lever, for when it is only slightly too strong or too slow.
     */
    const val SKIN_SMOOTH_ENABLED = true

    /**
     * Sampling ring radius as a fraction of the frame's SHORT edge, not a pixel
     * count and not its height.
     *
     * The short edge is the one quantity the two pipelines agree on. The preview
     * samples the camera buffer, which is landscape (1920x1080); the exporter
     * samples the upright master, which is portrait (1080x1920). Keying off
     * height would give the preview a 1.73px ring and the export a 3.07px one
     * for the same face — the export visibly softer than what was on screen.
     * Keyed off the short edge, both come to 1.73px, and a 2160-line master
     * scales up in step.
     */
    const val SMOOTH_RADIUS_RATIO = 0.0016f // ← 올리면 보정 범위 넓어짐

    /**
     * The sampling radius in texels for a frame of this size. Both pipelines
     * call this with their own frame's dimensions, in whichever orientation
     * they hold it, and get the same answer.
     */
    fun smoothRadiusPx(width: Int, height: Int): Float =
        (minOf(width, height) * SMOOTH_RADIUS_RATIO).coerceAtLeast(1f)

    /** How far towards the smoothed value skin is allowed to go. */
    const val SMOOTH_STRENGTH = 0.55f       // ← 올리면 더 부드러워짐

    /**
     * Brightness difference at which a neighbour stops counting. Lower keeps
     * more edges — lashes, lips, the line of a jaw — crisp; higher smooths
     * harder and starts to flatten them.
     */
    const val EDGE_THRESHOLD = 0.085f       // ← 내리면 윤곽이 더 선명
}
