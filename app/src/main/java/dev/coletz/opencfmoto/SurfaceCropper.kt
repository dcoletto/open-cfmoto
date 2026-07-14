package dev.coletz.opencfmoto

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * GL crop stage between the Android Auto decoder and the bike encoder:
 *
 *   AA VideoDecoder ──800x480 buffers──▶ SurfaceTexture (OES) ──draw crop region──▶
 *   encoder input Surface (800x384)
 *
 * The phone streams a fixed 800x480 with the UI in a centered 800x384 viewport (margins
 * negotiated in ServiceDiscoveryResponse) and black bars top/bottom. MediaCodec's
 * SCALE_TO_FIT_WITH_CROPPING is not honored when the decoder renders straight into an
 * encoder input surface (the buffer gets stretched instead), so this stage samples only
 * the viewport region and draws it onto the encoder surface — 1:1 pixels, no distortion.
 *
 * All GL work runs on a dedicated handler thread; frames are pushed by the SurfaceTexture
 * frame-available callback, so the stage adds one buffer hop but no polling.
 */
class SurfaceCropper(
    private val outputSurface: Surface,
    srcWidth: Int,
    srcHeight: Int,
    cropX: Int,
    cropY: Int,
    cropWidth: Int,
    cropHeight: Int,
    private val log: (String) -> Unit,
) : SurfaceTexture.OnFrameAvailableListener {

    private val thread = HandlerThread("aa-crop").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var oesTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    @Volatile private var released = false

    private val srcW = srcWidth
    private val srcH = srcHeight
    private val stMatrix = FloatArray(16)

    // Full-viewport quad (triangle strip). Texcoords select the crop region in normalized
    // buffer space; the crop is vertically centered so the SurfaceTexture Y-flip in the
    // transform matrix keeps it correct.
    private val posBuffer = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texBuffer: FloatBuffer
    private var outWidth = cropWidth
    private var outHeight = cropHeight

    init {
        val u0 = cropX.toFloat() / srcW
        val u1 = (cropX + cropWidth).toFloat() / srcW
        val v0 = cropY.toFloat() / srcH
        val v1 = (cropY + cropHeight).toFloat() / srcH
        texBuffer = floatBufferOf(u0, v0, u1, v0, u0, v1, u1, v1)
    }

    /**
     * Initializes EGL on the GL thread and returns the Surface the AA decoder should render
     * into, or null if GL setup failed (caller should fall back to the raw encoder surface).
     */
    fun start(): Surface? {
        val latch = CountDownLatch(1)
        handler.post {
            try {
                initGl()
                log("[CROP] GL crop stage ready (${srcW}x$srcH → ${outWidth}x$outHeight)")
            } catch (e: Exception) {
                log("[CROP] GL init failed: $e")
                releaseOnGlThread()
            }
            latch.countDown()
        }
        latch.await()
        return inputSurface
    }

    fun release() {
        if (released) return
        released = true
        handler.post { releaseOnGlThread() }
        thread.quitSafely()
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (released) return
        try {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            drawFrame(st.timestamp)
        } catch (e: Exception) {
            if (!released) log("[CROP] draw failed: $e")
        }
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            "eglChooseConfig failed"
        }
        val config = configs[0]!!

        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, outputSurface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        check(eglSurface !== EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

        val size = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, size, 0)
        if (size[0] > 0) outWidth = size[0]
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, size, 0)
        if (size[0] > 0) outHeight = size[0]

        program = buildProgram()

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTexId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(oesTexId).apply {
            setDefaultBufferSize(srcW, srcH)
            setOnFrameAvailableListener(this@SurfaceCropper, handler)
        }
        inputSurface = Surface(surfaceTexture)
    }

    private fun drawFrame(timestampNs: Long) {
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glUseProgram(program)

        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTex = GLES20.glGetAttribLocation(program, "aTex")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuffer)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uStMatrix"), 1, false, stMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun buildProgram(): Int {
        val vs = compileShader(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uStMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uStMatrix * aTex).xy;
            }
            """,
        )
        val fs = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
            """,
        )
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(prog)}" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source.trimIndent())
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun releaseOnGlThread() {
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface !== EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext !== EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(values).apply { position(0) }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
