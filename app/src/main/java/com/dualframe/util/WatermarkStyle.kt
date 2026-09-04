package com.dualframe.util

/**
 * Watermark appearance — shared between the burned-in export overlay
 * (WatermarkHelper) and the result-screen thumbnail preview (MainScreen).
 *
 * HOW TO TUNE:
 * Change these numbers, rebuild, and both the saved video and the preview update.
 *
 * The stroke exists only so the 40% white mark doesn't vanish on white or bright
 * backgrounds. It is meant to be barely perceptible — raising STROKE_ALPHA makes
 * the watermark louder, which defeats the purpose.
 */
object WatermarkStyle {

    const val TEXT = "DualFrame"

    // ── Fill ──
    // The mark itself: white at 40% alpha (0x66 = 102/255).
    const val FILL_COLOR = 0x66FFFFFF       // ← 워터마크 본체 (흰색 40%)

    // ── Stroke ──
    // Thin dark outline drawn UNDER the fill. Black at 20% alpha (0x33 = 51/255).
    const val STROKE_COLOR = 0x33000000     // ← 외곽선 색/투명도 (검정 20%)

    // Stroke width as a fraction of the rendered text size, so it tracks the
    // watermark scale automatically instead of being pinned to one resolution.
    // 0.025 → 1080p ≈ 1.1–1.25 px, UHD ≈ 2.5 px.
    const val STROKE_WIDTH_RATIO = 0.025f   // ← 올리면 외곽선 굵어짐
}
