package com.dualframe.util

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect

/**
 * The selfie effect as a Media3 effect, so the saved file gets the same
 * treatment the preview shows.
 *
 * It replaces the four built-in effects the exporter used to chain — brightness,
 * contrast, RGB and saturation — because those could not smooth skin, and
 * splitting the look across two implementations is what let the preview and the
 * file drift apart in the first place. The GLSL comes from [BeautyShader],
 * verbatim and identically parameterised.
 *
 * Media3 hands SDR frames to an effect as ordinary electrical RGB, untouched by
 * any transfer function — `Contrast` and friends multiply the sampled value
 * directly (see `fragment_shader_transformation_es2.glsl` in media3-effect) —
 * which is the same space the preview's external camera texture samples in. So
 * the shared GLSL means the same arithmetic on the same numbers, not merely the
 * same source text.
 */
@UnstableApi
class SkinSmoothEffect : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean) =
        SkinSmoothShaderProgram(useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int) = false
}

@UnstableApi
class SkinSmoothShaderProgram(
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private companion object {
        /**
         * The texture-coordinate convention Media3's own effects use — see
         * `vertex_shader_transformation_es2.glsl`, which computes exactly this
         * with an identity texture matrix. No Y flip: the intermediate textures
         * an effect reads are already the right way up.
         */
        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uRadius;
            varying vec2 vTexSamplingCoord;

            ${BeautyShader.COMMON_GLSL}

            void main() {
                vec2 o = uTexelSize * uRadius;
                vec3 c  = texture2D(uTexSampler, vTexSamplingCoord).rgb;
                vec3 n0 = texture2D(uTexSampler, vTexSamplingCoord + vec2(-o.x, -o.y)).rgb;
                vec3 n1 = texture2D(uTexSampler, vTexSamplingCoord + vec2( 0.0, -o.y)).rgb;
                vec3 n2 = texture2D(uTexSampler, vTexSamplingCoord + vec2( o.x, -o.y)).rgb;
                vec3 n3 = texture2D(uTexSampler, vTexSamplingCoord + vec2(-o.x,  0.0)).rgb;
                vec3 n4 = texture2D(uTexSampler, vTexSamplingCoord + vec2( o.x,  0.0)).rgb;
                vec3 n5 = texture2D(uTexSampler, vTexSamplingCoord + vec2(-o.x,  o.y)).rgb;
                vec3 n6 = texture2D(uTexSampler, vTexSamplingCoord + vec2( 0.0,  o.y)).rgb;
                vec3 n7 = texture2D(uTexSampler, vTexSamplingCoord + vec2( o.x,  o.y)).rgb;

                vec3 result = dfSmoothSkin(c, n0, n1, n2, n3, n4, n5, n6, n7);
                gl_FragColor = vec4(dfTone(result), 1.0);
            }
        """
    }

    private val glProgram: GlProgram = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    private var texelWidth = 0f
    private var texelHeight = 0f
    private var radiusPx = 1f

    init {
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelWidth = 1f / inputWidth
        texelHeight = 1f / inputHeight
        radiusPx = BeautyParams.smoothRadiusPx(inputWidth, inputHeight)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(texelWidth, texelHeight))
            glProgram.setFloatUniform("uRadius", radiusPx)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}
