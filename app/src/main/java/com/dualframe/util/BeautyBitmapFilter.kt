package com.dualframe.util

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the selfie effect over a still frame, for the thumbnails on the result
 * screen.
 *
 * Those thumbnails are pulled straight out of the untouched master, because the
 * effect is applied by the exporter and the exporter has not run yet when the
 * result screen appears. So the one picture the user is shown before deciding
 * whether to save was the one picture the effect had never reached.
 *
 * It runs the shared [BeautyShader] GLSL on the GPU rather than reimplementing
 * the maths on the CPU. A second implementation is exactly how the preview and
 * the saved file drifted apart in the first place; there is no version of that
 * worth having for a thumbnail. `DF_SAMPLE` and the vertex convention here are
 * the exporter's, character for character.
 *
 * Everything that can fail — no EGL, no context, a shader the driver rejects —
 * returns the frame untouched. A thumbnail without the effect is a cosmetic
 * disappointment; no thumbnail is a broken screen.
 */
object BeautyBitmapFilter {

    private const val TAG = "BeautyBitmapFilter"

    /**
     * The thumbnails are shown at about half the screen's width, so a master's
     * full 2160x3840 is wasted work and a 33MB readback on a device that may
     * not have it to spare. Scaling first costs nothing in fidelity: the
     * radius is a fraction of the frame, so a smaller frame gets a
     * proportionally smaller kernel and the same look.
     */
    private const val MAX_EDGE = 1280

    private const val VERTEX_SHADER = """
        attribute vec4 aFramePosition;
        varying vec2 vTexSamplingCoord;
        void main() {
            gl_Position = aFramePosition;
            vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
        }
    """

    private val FRAGMENT_SHADER = """
        #define DF_SAMPLE(uv) texture2D(uTexSampler, uv)
        precision mediump float;
        uniform sampler2D uTexSampler;
        uniform vec2 uTexelSize;
        uniform float uRadius;
        varying vec2 vTexSamplingCoord;

        ${BeautyShader.COMMON_GLSL}

        void main() {
            gl_FragColor = vec4(dfBeauty(vTexSamplingCoord, uTexelSize, uRadius), 1.0);
        }
    """

    private val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

    /**
     * Returns [source] with the selfie effect applied, or [source] itself if
     * anything goes wrong. The caller owns both; when the result is a different
     * instance the source is no longer needed.
     */
    fun apply(source: Bitmap, level: Int = BeautyParams.DEFAULT_LEVEL): Bitmap {
        if (source.width <= 0 || source.height <= 0) return source

        val scaled = downscale(source)
        val w = scaled.width
        val h = scaled.height

        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return bail(source, scaled, "no EGL display")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                display = EGL14.EGL_NO_DISPLAY
                return bail(source, scaled, "eglInitialize failed")
            }

            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            val configAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, configCount, 0) ||
                configCount[0] == 0
            ) {
                return bail(source, scaled, "no suitable EGL config")
            }

            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (context == EGL14.EGL_NO_CONTEXT) return bail(source, scaled, "no EGL context")

            // A 1x1 surface only to have something current; the frame is drawn
            // into a framebuffer object, which is not bound by whatever pbuffer
            // dimensions a driver happens to allow.
            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            if (surface == EGL14.EGL_NO_SURFACE) return bail(source, scaled, "no pbuffer surface")
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return bail(source, scaled, "eglMakeCurrent failed")
            }

            val maxTexture = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
            if (maxTexture[0] in 1 until maxOf(w, h)) {
                return bail(source, scaled, "frame larger than GL_MAX_TEXTURE_SIZE")
            }

            val out = render(scaled, w, h, level) ?: return bail(source, scaled, "render failed")
            if (scaled !== source) scaled.recycle()
            return out
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail left unfiltered", e)
            if (scaled !== source) scaled.recycle()
            return source
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private fun bail(source: Bitmap, scaled: Bitmap, why: String): Bitmap {
        Log.w(TAG, "Thumbnail left unfiltered: $why")
        if (scaled !== source) scaled.recycle()
        return source
    }

    private fun downscale(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_EDGE) return source
        val ratio = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            /* filter= */ true,
        )
    }

    private fun render(source: Bitmap, w: Int, h: Int, level: Int): Bitmap? {
        var program = 0
        val textures = IntArray(2)
        val framebuffers = IntArray(1)
        try {
            program = buildProgram() ?: return null

            GLES20.glGenTextures(2, textures, 0)
            // 0: the frame, sampled. GL_LINEAR to match both pipelines, so taps
            // landing between texels average a 2x2 neighbourhood here too.
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, source, 0)

            // 1: the result, drawn into.
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )

            GLES20.glGenFramebuffers(1, framebuffers, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, textures[1], 0,
            )
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) !=
                GLES20.GL_FRAMEBUFFER_COMPLETE
            ) {
                Log.w(TAG, "Framebuffer incomplete")
                return null
            }

            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(program)

            val positionHandle = GLES20.glGetAttribLocation(program, "aFramePosition")
            val quad = ByteBuffer.allocateDirect(QUAD.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(QUAD); position(0) }
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quad)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexSampler"), 0)
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(program, "uTexelSize"), 1f / w, 1f / h,
            )
            val strength = BeautyParams.forLevel(level)
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(program, "uRadius"),
                BeautyParams.smoothRadiusPx(w, h, strength.radiusRatio),
            )
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLumaStrength"), strength.luma)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uChromaStrength"), strength.chroma)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSkinBrighten"), strength.skinBrighten)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uToneAmount"), strength.tone)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionHandle)

            // Two flips that cancel: the quad puts the frame's first row at the
            // bottom of the framebuffer, and glReadPixels starts reading from
            // the bottom. Row 0 of the readback is row 0 of the frame.
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            pixels.position(0)
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                .apply { copyPixelsFromBuffer(pixels) }
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (framebuffers[0] != 0) GLES20.glDeleteFramebuffers(1, framebuffers, 0)
            if (textures[0] != 0 || textures[1] != 0) GLES20.glDeleteTextures(2, textures, 0)
            if (program != 0) GLES20.glDeleteProgram(program)
        }
    }

    private fun buildProgram(): Int? {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return null
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (fragment == null) {
            GLES20.glDeleteShader(vertex)
            return null
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.w(TAG, "Link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return null
        }
        return program
    }

    private fun compile(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.w(TAG, "Compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }
}
