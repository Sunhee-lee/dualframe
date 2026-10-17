package com.dualframe.util

/**
 * The selfie effect, written once.
 *
 * The preview and the saved file used to drift: the preview ran a box blur the
 * exporter had no way to reproduce, so what the user framed was not what they
 * got. Everything that decides a pixel now lives here as GLSL, and both sides
 * include it verbatim — only the sampling loop differs, because the preview
 * reads an external camera texture and the exporter reads an ordinary one.
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
     * Edge-preserving smoothing over a 3x3 ring, then applied only where skin
     * was found.
     *
     * Each neighbour is weighted by how close its brightness is to the centre's,
     * so a sample that falls on an eyelash, a lip edge or the line of a jaw
     * contributes almost nothing and that detail survives. A plain average —
     * which is what the old preview did — has no such defence, which is why it
     * softened hair and eyes along with everything else.
     *
     * [uTexelSize] is one source pixel, and the radius is a fraction of frame
     * height rather than a fixed count of pixels: a 1080p preview and a 2160p
     * master then blur across the same share of a face, which is what makes the
     * two look alike.
     */
    val SMOOTH_GLSL = if (!BeautyParams.SKIN_SMOOTH_ENABLED) """
        vec3 dfSmoothSkin(vec3 centre, vec3 n0, vec3 n1, vec3 n2, vec3 n3,
                          vec3 n4, vec3 n5, vec3 n6, vec3 n7) {
            return centre;
        }
    """ else """
        vec3 dfSmoothSkin(vec3 centre, vec3 n0, vec3 n1, vec3 n2, vec3 n3,
                          vec3 n4, vec3 n5, vec3 n6, vec3 n7) {
            float cy = dfLuma(centre);
            vec3 sum = centre;
            float wsum = 1.0;

            float w;
            w = 1.0 - smoothstep(0.0, ${glsl(BeautyParams.EDGE_THRESHOLD)}, abs(dfLuma(n0) - cy)); sum += n0 * w; wsum += w;
            w = 1.0 - smoothstep(0.0, ${glsl(BeautyParams.EDGE_THRESHOLD)}, abs(dfLuma(n1) - cy)); sum += n1 * w; wsum += w;
            w = 1.0 - smoothstep(0.0, ${glsl(BeautyParams.EDGE_THRESHOLD)}, abs(dfLuma(n2) - cy)); sum += n2 * w; wsum += w;
            w = 1.0 - smoothstep(0.0, ${glsl(BeautyParams.EDGE_THRESHOLD)}, abs(dfLuma(n3) - cy)); sum += n3 * w; wsum += w;
            w = 1.0 - smoothstep(0.0, ${glsl(BeautyParams.EDGE_THRESHOLD)}, abs(dfLuma(n4) - cy)); sum += n4 * w; wsum += w;
            w = 1.0 - smoothstep(0.0, ${glsl(BeautyParams.EDGE_THRESHOLD)}, abs(dfLuma(n5) - cy)); sum += n5 * w; wsum += w;
            w = 1.0 - smoothstep(0.0, ${glsl(BeautyParams.EDGE_THRESHOLD)}, abs(dfLuma(n6) - cy)); sum += n6 * w; wsum += w;
            w = 1.0 - smoothstep(0.0, ${glsl(BeautyParams.EDGE_THRESHOLD)}, abs(dfLuma(n7) - cy)); sum += n7 * w; wsum += w;

            vec3 smoothed = sum / wsum;
            float mask = dfSkinMask(centre);
            return mix(centre, smoothed, mask * ${glsl(BeautyParams.SMOOTH_STRENGTH)});
        }
    """

    /**
     * Everything a fragment shader needs, in the order it needs it.
     *
     * With [BeautyParams.SKIN_SMOOTH_ENABLED] off, `dfSmoothSkin` is emitted as
     * a pass-through, so both pipelines keep compiling and keep their tone
     * correction while the smoothing — and, once the driver inlines it, the
     * eight neighbour fetches that feed it — drops out.
     */
    val COMMON_GLSL = SKIN_MASK_GLSL + TONE_GLSL + SMOOTH_GLSL

    /** GLSL rejects "1" where it expects a float, so every literal gets a point. */
    private fun glsl(v: Float): String {
        val s = v.toString()
        return if (s.contains('.') || s.contains('e') || s.contains('E')) s else "$s.0"
    }
}
