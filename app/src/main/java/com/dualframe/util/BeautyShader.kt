package com.dualframe.util

/**
 * The selfie effect, written once.
 *
 * The preview and the saved file used to drift: the preview ran a box blur the
 * exporter had no way to reproduce, so what the user framed was not what they
 * got. Everything that decides a pixel now lives here as GLSL, and both sides
 * include it verbatim — including the sampling, which used to be duplicated.
 * The one difference between the two pipelines is which sampler the taps read,
 * and that is handed in as a `DF_SAMPLE(uv)` macro, so no arithmetic can
 * diverge even by accident.
 *
 * Numbers come from [BeautyParams] and are interpolated in, so there is one
 * place to tune and no literals to fall out of step.
 */
object BeautyShader {

    /**
     * Skin detection in YCbCr, which separates hue from brightness well enough
     * to find skin without finding a face. The bands are deliberately soft —
     * smoothstep rather than a threshold — so the effect fades in at the edge
     * of a cheek instead of drawing a line across it.
     *
     * Luminance gates both ends: near-black carries no detail worth smoothing,
     * and a blown highlight is already flat.
     *
     * These bands are left exactly as they were. Measured against real skin
     * values they already return 1.0 on every genuine cheek sample — bright,
     * standard, shadowed and cool-lit alike — so a weak result was never the
     * mask's doing. Widening the Cr band would admit the whites of the eyes,
     * which the lower bound at 0.485 is what keeps out.
     */
    const val SKIN_MASK_GLSL = """
        float dfLuma(vec3 c) {
            return dot(c, vec3(0.299, 0.587, 0.114));
        }

        float dfSkinMask(vec3 c) {
            float y  = dfLuma(c);
            float cb = (c.b - y) * 0.564 + 0.5;
            float cr = (c.r - y) * 0.713 + 0.5;
            float mb = smoothstep(0.330, 0.390, cb) * (1.0 - smoothstep(0.500, 0.560, cb));
            float mr = smoothstep(0.485, 0.535, cr) * (1.0 - smoothstep(0.630, 0.690, cr));
            float ml = smoothstep(0.120, 0.220, y)  * (1.0 - smoothstep(0.920, 0.995, y));
            return mb * mr * ml;
        }
    """

    /**
     * Brightness, contrast, white balance and saturation — unchanged from what
     * the app has always applied, just moved somewhere both pipelines can read.
     * This is frame-wide; anything meant for skin alone happens in [BEAUTY_GLSL]
     * where the mask is in hand.
     */
    val TONE_GLSL = """
        vec3 dfTone(vec3 c) {
            c = c + ${glsl(BeautyParams.BRIGHTNESS)};
            c = (c - 0.5) * (1.0 + ${glsl(BeautyParams.CONTRAST)}) + 0.5;
            c.r = c.r * ${glsl(BeautyParams.RED_SCALE)};
            c.g = c.g * ${glsl(BeautyParams.GREEN_SCALE)};
            c.b = c.b * ${glsl(BeautyParams.BLUE_SCALE)};
            float g = dfLuma(c);
            c = mix(vec3(g), c, ${glsl(1f + BeautyParams.SATURATION_BOOST / 100f)});
            return clamp(c, 0.0, 1.0);
        }
    """

    /**
     * Two concentric rings of taps, on the unit circle.
     *
     * A single 3x3 ring — what this used to be — is a poor low-pass: widen it
     * and it stops being a blur and starts being eight arbitrary point samples,
     * so detail between them survives untouched and edges ring. Two rings, the
     * outer one rotated half a step to fill the angular gaps, cover the disc
     * well enough to widen the radius to something that can actually reach a
     * blemish.
     *
     * Both pipelines sample bilinearly — the preview sets GL_LINEAR on the
     * camera texture, and Media3 always sets it on an effect's input (see
     * `GlProgram.Uniform.bind`) — so every tap that lands between texels
     * averages a 2x2 neighbourhood for free, on both sides identically. The
     * offsets are chosen off-texel for exactly that reason.
     */
    private const val TAP_DIRS_INNER =
        "( 1.000,  0.000)( 0.707,  0.707)( 0.000,  1.000)(-0.707,  0.707)" +
        "(-1.000,  0.000)(-0.707, -0.707)( 0.000, -1.000)( 0.707, -0.707)"

    private const val TAP_DIRS_OUTER =
        "( 0.924,  0.383)( 0.383,  0.924)(-0.383,  0.924)(-0.924,  0.383)" +
        "(-0.924, -0.383)(-0.383, -0.924)( 0.383, -0.924)( 0.924, -0.383)"

    /**
     * The whole selfie look, from taps to final colour.
     *
     * `DF_SAMPLE(uv)` is supplied by whichever pipeline includes this, because
     * the preview reads an external camera texture and the exporter an ordinary
     * one. Everything else — the geometry of the taps, the edge weights, the
     * mask, how far luma and chroma are each allowed to move, the skin lift and
     * the tone pass — is this text, the same text, on both sides.
     *
     * Why luma and chroma move at different speeds: skin *texture* lives in
     * luminance, and flattening it is what makes a face look like plastic, so
     * luma is only part-smoothed. Blotchiness, redness and healing spots live
     * mostly in *chroma*, which carries no detail anyone wants to keep, so it is
     * smoothed hard. Evening out colour while leaving some texture is what
     * reads as clear skin rather than as a filter.
     */
    val BEAUTY_GLSL: String = buildString {
        append("""
        vec3 dfBeauty(vec2 uv, vec2 texel, float radius) {
            vec2 rOut = texel * radius;
            vec2 rIn  = rOut * ${glsl(BeautyParams.INNER_RING_RATIO)};

            vec3 centre = DF_SAMPLE(uv).rgb;
            float cy = dfLuma(centre);

            vec3  sum  = centre;
            float wsum = 1.0;
            vec3  t;
            float w;
        """)
        for (d in Regex("""\(([^)]*)\)""").findAll(TAP_DIRS_INNER).map { it.groupValues[1] }) {
            append(tap(d, "rIn", 1f))
        }
        for (d in Regex("""\(([^)]*)\)""").findAll(TAP_DIRS_OUTER).map { it.groupValues[1] }) {
            append(tap(d, "rOut", BeautyParams.OUTER_RING_WEIGHT))
        }
        append("""
            vec3 smoothed = sum / wsum;
            float mask = dfSkinMask(centre);

            float sy = dfLuma(smoothed);
            float y  = mix(cy, sy, mask * ${glsl(BeautyParams.SMOOTH_LUMA_STRENGTH)});
            vec3  ch = mix(centre - vec3(cy), smoothed - vec3(sy),
                           mask * ${glsl(BeautyParams.SMOOTH_CHROMA_STRENGTH)});
            vec3 result = vec3(y) + ch;

            // Skin only, strongest in shadow and gone before the highlights, so
            // a face brightens without the frame going pale or a forehead
            // blowing out. The mask keeps it off the background entirely.
            result += mask * ${glsl(BeautyParams.SKIN_BRIGHTEN)}
                      * (1.0 - smoothstep(0.55, 0.95, cy));

            return dfTone(clamp(result, 0.0, 1.0));
        }
        """)
    }

    /**
     * One tap: read it, weigh it by how close its brightness is to the centre's,
     * accumulate.
     *
     * A sample that falls on an eyelash, a lip edge or the line of a jaw differs
     * in luminance far more than [BeautyParams.EDGE_THRESHOLD] allows, so it
     * contributes nothing and that detail survives untouched. The threshold is
     * what decides whether a blemish counts as detail to protect or as
     * something to even out, which is why it is the second of the two numbers
     * that governs how strong this looks at all.
     */
    private fun tap(dir: String, step: String, weight: Float): String {
        val w = if (weight == 1f) "" else " * ${glsl(weight)}"
        return """
            t = DF_SAMPLE(uv + vec2($dir) * $step).rgb;
            w = (1.0 - smoothstep(0.0, ${glsl(BeautyParams.EDGE_THRESHOLD)},
                                  abs(dfLuma(t) - cy)))$w;
            sum += t * w; wsum += w;
        """
    }

    /**
     * Everything a fragment shader needs, in the order it needs it. The
     * including shader must `#define DF_SAMPLE(uv)` before this.
     *
     * With [BeautyParams.SKIN_SMOOTH_ENABLED] off, `dfBeauty` reads one texel
     * and applies tone correction only — no rings, no mask, no extra fetches —
     * so both pipelines keep compiling and keep their colour while the
     * smoothing drops out entirely.
     */
    val COMMON_GLSL: String = SKIN_MASK_GLSL + TONE_GLSL +
        if (BeautyParams.SKIN_SMOOTH_ENABLED) BEAUTY_GLSL else """
        vec3 dfBeauty(vec2 uv, vec2 texel, float radius) {
            return dfTone(DF_SAMPLE(uv).rgb);
        }
        """

    /** GLSL rejects "1" where it expects a float, so every literal gets a point. */
    private fun glsl(v: Float): String {
        val s = v.toString()
        return if (s.contains('.') || s.contains('e') || s.contains('E')) s else "$s.0"
    }
}
