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
     * device turns out to pay too much for the smoothing. [SMOOTH_RADIUS_RATIO]
     * and [SMOOTH_LUMA_STRENGTH] are the gentler levers, for when it is only
     * slightly too strong or too slow.
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
    const val SMOOTH_RADIUS_RATIO = 0.0070f // ← 올리면 보정 범위 넓어짐

    /**
     * The sampling radius in texels for a frame of this size. Both pipelines
     * call this with their own frame's dimensions, in whichever orientation
     * they hold it, and get the same answer.
     */
    fun smoothRadiusPx(width: Int, height: Int): Float =
        (minOf(width, height) * SMOOTH_RADIUS_RATIO).coerceAtLeast(1f)

    /** Inner ring radius, as a fraction of the outer one. */
    const val INNER_RING_RATIO = 0.45f

    /**
     * How much an outer-ring tap counts against an inner one. Below 1 so the
     * kernel falls off with distance instead of being a flat disc, which is
     * what keeps the result looking like soft skin rather than like a smear.
     */
    const val OUTER_RING_WEIGHT = 0.6f

    /**
     * How far towards the smoothed value skin LUMINANCE is allowed to go.
     *
     * Luminance is where skin texture lives, so this is the number that decides
     * between smooth and plastic. Past about 0.85 a face stops having a surface.
     */
    const val SMOOTH_LUMA_STRENGTH = 0.62f  // ← 올리면 더 매끈, 과하면 플라스틱

    /**
     * How far towards the smoothed value skin CHROMA is allowed to go.
     *
     * Much higher than the luminance figure on purpose. Blotchiness, redness
     * and healing marks are mostly colour variation, and colour carries no fine
     * detail in skin worth keeping, so evening it out nearly all the way reads
     * as a clear complexion while the texture above keeps it from looking fake.
     * This is where most of the visible improvement comes from, and it costs
     * nothing extra to compute.
     */
    const val SMOOTH_CHROMA_STRENGTH = 0.92f // ← 올리면 홍조·잡티 색이 더 고르게

    /**
     * Brightness difference at which a neighbour stops counting.
     *
     * The old 0.085 was the second reason the effect barely showed: a blemish
     * differs from the skin around it by roughly 0.03 to 0.08, which that
     * threshold read as an edge to protect, weighting it down to 0.37 and then
     * to 0.01. At 0.14 the same blemishes weigh 0.71 and 0.39 and do get evened
     * out, while a lip edge (~0.27) and a lash (~0.6) still weigh zero, so what
     * should stay sharp still does.
     */
    const val EDGE_THRESHOLD = 0.14f        // ← 내리면 윤곽이 더 선명, 대신 잡티도 남음

    /**
     * Extra brightness for skin alone, on top of the frame-wide [BRIGHTNESS].
     *
     * Gated by the skin mask so the background is untouched, and faded out
     * above mid-grey so shadow on a face lifts while a lit forehead does not
     * climb towards white. This is the "화사" part, and the first dial to turn
     * down if faces start looking pale.
     */
    const val SKIN_BRIGHTEN = 0.05f         // ← 올리면 화사, 과하면 하얗게 뜸
}
