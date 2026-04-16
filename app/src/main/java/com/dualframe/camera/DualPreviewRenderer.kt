package com.dualframe.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GPU-based dual preview renderer.
 *
 * Takes a single camera SurfaceTexture and renders it into two output surfaces
 * (TextureViews) with different crop transforms — one for 9:16, one for 16:9.
 * All rendering happens on a dedicated GL thread. Zero CPU bitmap processing.
 *
 * Architecture:
 * 1. Creates an EGL context + OES texture + SurfaceTexture for CameraX to write into
 * 2. On each frame (onFrameAvailable), renders the camera texture twice:
 *    a) To surface1 with 9:16 center-crop vertex transform
 *    b) To surface2 with 16:9 center-crop vertex transform
 * 3. The crop is achieved by scaling the quad beyond [-1,1] clip space — the GPU clips
 *    the overflow, showing only the center portion matching the target aspect ratio.
 */
class DualPreviewRenderer {

    companion object {
        private const val TAG = "DualPreviewRenderer"

        private const val VERTEX_SHADER = """
            uniform mat4 uSTMatrix;
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = (uSTMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        // Fullscreen quad vertices (x, y) and texture coordinates (s, t)
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,  0f, 0f,  // bottom-left
             1f, -1f,  1f, 0f,  // bottom-right
            -1f,  1f,  0f, 1f,  // top-left
             1f,  1f,  1f, 1f,  // top-right
        )
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var cameraTextureId = 0
    private var program = 0

    private var surface9x16: Surface? = null
    private var surface16x9: Surface? = null
    private var eglSurface9x16: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglSurface16x9: EGLSurface = EGL14.EGL_NO_SURFACE

    // Camera preview dimensions (set when CameraX provides the SurfaceRequest)
    private var previewWidth = 0
    private var previewHeight = 0

    // Mirror for front camera
    var mirrorHorizontally = false

    private val stMatrix = FloatArray(16)
    private var vertexBuffer: FloatBuffer? = null

    private var uSTMatrixHandle = 0
    private var uMVPMatrixHandle = 0
    private var aPositionHandle = 0
    private var aTexCoordHandle = 0

    /**
     * Initialize the GL context and camera texture on a dedicated thread.
     * Returns the SurfaceTexture that CameraX should write into.
     */
    fun init(onReady: (SurfaceTexture) -> Unit) {
        glThread = HandlerThread("DualPreviewGL").apply { start() }
        glHandler = Handler(glThread!!.looper)

        glHandler?.post {
            initEGL()
            initShaders()
            cameraTextureId = createOESTexture()

            val st = SurfaceTexture(cameraTextureId)
            cameraSurfaceTexture = st

            st.setOnFrameAvailableListener({ renderFrame() }, glHandler)

            // Callback on the GL thread — caller can use this to provide to CameraX
            onReady(st)
            Log.i(TAG, "GL renderer initialized, camera texture ID=$cameraTextureId")
        }
    }

    /** Get the camera SurfaceTexture if already initialized, or null. */
    fun getCameraSurfaceTexture(): SurfaceTexture? = cameraSurfaceTexture

    /** Set the camera preview resolution (from CameraX SurfaceRequest). */
    fun setPreviewSize(width: Int, height: Int) {
        previewWidth = width
        previewHeight = height
        cameraSurfaceTexture?.setDefaultBufferSize(width, height)
        Log.i(TAG, "Preview size set: ${width}x${height}")
    }

    /** Set the output surface for the 9:16 preview panel. */
    fun setOutput9x16(surface: Surface) {
        glHandler?.post {
            if (eglSurface9x16 != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface9x16)
            }
            surface9x16 = surface
            eglSurface9x16 = createEGLWindowSurface(surface)
            Log.i(TAG, "9:16 output surface set")
        }
    }

    /** Set the output surface for the 16:9 preview panel. */
    fun setOutput16x9(surface: Surface) {
        glHandler?.post {
            if (eglSurface16x9 != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface16x9)
            }
            surface16x9 = surface
            eglSurface16x9 = createEGLWindowSurface(surface)
            Log.i(TAG, "16:9 output surface set")
        }
    }

    /** Render the camera texture to both output surfaces. */
    private fun renderFrame() {
        val st = cameraSurfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)

        // Determine camera preview aspect (after rotation, the preview is typically portrait)
        // CameraX preview buffers come in landscape from the sensor; the SurfaceTexture
        // transform matrix handles rotation. The visual aspect after transform is what matters.
        // We approximate: if width > height, landscape buffer → portrait display after 90° rotation.
        val visualAspect = if (previewWidth > previewHeight) {
            previewHeight.toFloat() / previewWidth.toFloat() // portrait after rotation
        } else {
            previewWidth.toFloat() / previewHeight.toFloat()
        }

        // Render to 9:16 surface
        if (eglSurface9x16 != EGL14.EGL_NO_SURFACE) {
            renderToSurface(eglSurface9x16, 9f / 16f, visualAspect)
        }

        // Render to 16:9 surface
        if (eglSurface16x9 != EGL14.EGL_NO_SURFACE) {
            renderToSurface(eglSurface16x9, 16f / 9f, visualAspect)
        }
    }

    /**
     * Render the camera texture to one output surface with a center-crop to targetAspect.
     *
     * Crop strategy: scale the quad beyond the [-1,1] viewport so only the center
     * portion matching the target aspect is visible. The GPU clips the overflow.
     *
     * For example, to get 16:9 from a 9:16 source:
     *   scaleX = 1.0 (full width), scaleY = sourceAspect / targetAspect = 0.5625 / 1.778 = 0.316
     *   → quad Y spans [-0.316, 0.316] in clip space → only center 31.6% of height visible
     *   Wait, that's wrong — we need to ENLARGE the quad so the crop happens by clipping.
     *   scaleY = targetAspect / sourceAspect = 1.778 / 0.5625 = 3.16
     *   → quad Y spans [-3.16, 3.16] → GPU clips to [-1,1] → center strip visible
     */
    private fun renderToSurface(eglSurface: EGLSurface, targetAspect: Float, sourceAspect: Float) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        val surfaceWidth = intArrayOf(0)
        val surfaceHeight = intArrayOf(0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, surfaceWidth, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, surfaceHeight, 0)
        GLES20.glViewport(0, 0, surfaceWidth[0], surfaceHeight[0])

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // Compute MVP matrix for center-crop scaling
        val mvp = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }

        // Mirror for front camera: flip X in the MVP matrix
        if (mirrorHorizontally) {
            android.opengl.Matrix.scaleM(mvp, 0, -1f, 1f, 1f)
        }

        if (sourceAspect > 0f && targetAspect > 0f) {
            if (targetAspect > sourceAspect) {
                // Target wider → enlarge Y so height gets clipped (show center horizontal strip)
                val scaleY = targetAspect / sourceAspect
                android.opengl.Matrix.scaleM(mvp, 0, 1f, scaleY, 1f)
            } else if (targetAspect < sourceAspect) {
                // Target taller → enlarge X so width gets clipped (show center vertical strip)
                val scaleX = sourceAspect / targetAspect
                android.opengl.Matrix.scaleM(mvp, 0, scaleX, 1f, 1f)
            }
        }

        // Set uniforms
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvp, 0)

        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)

        // Draw quad
        val buf = vertexBuffer ?: return
        buf.position(0)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 16, buf)

        buf.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoordHandle)
        GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 16, buf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTexCoordHandle)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    // ── EGL setup ─────────────────────────────────────────────────────

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]!!

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        // Create a small pbuffer as the initial current surface (required to compile shaders)
        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val pbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)

        Log.i(TAG, "EGL initialized")
    }

    private fun createEGLWindowSurface(surface: Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        return EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
    }

    // ── Shader setup ──────────────────────────────────────────────────

    private fun initShaders() {
        val vertShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertShader)
        GLES20.glAttachShader(program, fragShader)
        GLES20.glLinkProgram(program)

        uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

        // Vertex buffer
        val bb = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4).order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer().apply {
            put(QUAD_VERTICES)
            position(0)
        }

        Log.i(TAG, "Shaders compiled and linked")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compile error: $log")
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    // ── Cleanup ───────────────────────────────────────────────────────

    fun release() {
        glHandler?.post {
            cameraSurfaceTexture?.release()
            cameraSurfaceTexture = null
            if (eglSurface9x16 != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface9x16)
            if (eglSurface16x9 != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface16x9)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay)
            GLES20.glDeleteProgram(program)
            Log.i(TAG, "GL renderer released")
        }
        glThread?.quitSafely()
        glThread = null
        glHandler = null
    }
}
