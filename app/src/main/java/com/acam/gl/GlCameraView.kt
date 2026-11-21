package com.acam.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.acam.data.EffectParams
import com.acam.data.FrameStyle
import com.acam.util.Framing
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** Immutable snapshot of everything the renderer needs for one frame. */
class PipeConfig(
    val params: EffectParams = EffectParams(),
    val outW: Int = 1920,
    val outH: Int = 1080,
    val mirror: Boolean = false,
    val displayRotation: Int = 0,
    val sensorOrientation: Int = 90
)

/** Recording configuration, resolved (encoder-clamped) by the view model. */
class RecConfig(
    val targetW: Int,
    val targetH: Int,
    val encoderW: Int,
    val encoderH: Int,
    val fps: Int,
    val bitrateBps: Int,
    val hevc: Boolean,
    val audio: Boolean,
    val micGain: Float
)

sealed class GlCommand {
    object CapturePhoto : GlCommand()
    class StartRecording(val cfg: RecConfig) : GlCommand()
    object StopRecording : GlCommand()
    object PauseRecording : GlCommand()
    object ResumeRecording : GlCommand()
    class UpdateStamp(val bitmap: Bitmap?) : GlCommand()
}

/** Events delivered from the GL thread back to the view model. */
sealed class RecordEvent {
    class Started(val fileDesc: Long) : RecordEvent()
    object Stopped : RecordEvent()
    object Paused : RecordEvent()
    object Resumed : RecordEvent()
    class Failed(val message: String) : RecordEvent()
}

/**
 * The bridge between the UI/camera layers and the GL thread. The view model
 * writes configuration and enqueues commands; the renderer reads them once
 * per frame. Nothing here allocates per frame.
 */
class GlPipe(private val appContext: Context) {

    val config = AtomicReference(PipeConfig())

    @Volatile var streamW: Int = 0
        internal set
    @Volatile var streamH: Int = 0
        internal set

    /** Created on the GL thread in onSurfaceCreated; camera binds to it. */
    @Volatile var surfaceTexture: SurfaceTexture? = null
        internal set

    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null
    var onPhoto: ((w: Int, h: Int, rgba: ByteArray) -> Unit)? = null
    var onRecordEvent: ((RecordEvent) -> Unit)? = null

    val commands = ConcurrentLinkedQueue<GlCommand>()

    @Volatile internal var view: GLSurfaceView? = null

    private val t0Ms = SystemClock.elapsedRealtime()

    fun nowSeconds(): Float = (SystemClock.elapsedRealtime() - t0Ms) / 1000f

    fun post(cmd: GlCommand) {
        commands.add(cmd)
        view?.requestRender()
    }

    val renderer = Renderer()

    /** The GLSurfaceView.Renderer implementing the WYSIWYG pipeline. */
    inner class Renderer : GLSurfaceView.Renderer {

        private var effects: EffectProgram? = null
        private var blit: BlitProgram? = null
        private var quad: java.nio.FloatBuffer? = null
        private var oesTex = 0
        private var overlayTex = 0
        private var stampBmpW = 1
        private var stampBmpH = 1
        private var mainFbo: GlUtils.Fbo? = null

        private val stMat = FloatArray(16)
        private val identity = Mat4.identity()
        private val frameAvailable = AtomicBoolean(false)
        private var haveFrame = false
        private var surfaceW = 1
        private var surfaceH = 1

        private var capturePending = false

        // Recording state (GL thread only)
        private var recorder: FrameRecorder? = null
        private var recIntervalNs = 0L
        private var recNextNs = 0L

        // Reusable capture buffer
        private var pixelBuf: ByteBuffer? = null
        private var pixelBufSize = 0

        private val stampColorArr = FloatArray(4)
        private val stampRectArr = FloatArray(4)

        override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
            effects = EffectProgram()
            blit = BlitProgram()
            quad = GlUtils.quadBuffer()
            oesTex = GlUtils.genTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            overlayTex = GlUtils.genTexture(GLES20.GL_TEXTURE_2D)

            val st = SurfaceTexture(oesTex)
            st.setDefaultBufferSize(1920, 1080) // provisional; CameraX sets the real size
            st.setOnFrameAvailableListener {
                frameAvailable.set(true)
                view?.requestRender()
            }
            surfaceTexture = st
            haveFrame = false
            onSurfaceTextureReady?.invoke(st)
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            drainCommands()

            val cfg = config.get()
            val effects = this.effects ?: return
            val blit = this.blit ?: return
            val quad = this.quad ?: return
            val st = surfaceTexture

            if (st == null || streamW == 0 || streamH == 0) {
                clearScreen()
                return
            }

            if (frameAvailable.getAndSet(false)) {
                st.updateTexImage()
                st.getTransformMatrix(stMat)
                haveFrame = true
            }
            if (!haveFrame) {
                clearScreen()
                return
            }

            // ---- framing: ST * R * C * M ----
            val rot = ((cfg.sensorOrientation - cfg.displayRotation) % 360 + 360) % 360
            val (csx, csy) = Framing.cropScale(streamW, streamH, rot, cfg.outW, cfg.outH)
            var uvMat = Mat4.mul(stMat, Mat4.rotation(rot))
            uvMat = Mat4.mul(uvMat, Mat4.crop(csx, csy))
            if (cfg.mirror) uvMat = Mat4.mul(uvMat, Mat4.mirror())

            // ---- choose effect target size ----
            val exact = capturePending || recorder != null ||
                max(cfg.outW, cfg.outH) <= PREVIEW_CAP
            val ew: Int
            val eh: Int
            if (exact) {
                ew = cfg.outW
                eh = cfg.outH
            } else {
                // Preview-only downscale: visually identical, much cheaper.
                val s = PREVIEW_CAP.toFloat() / max(cfg.outW, cfg.outH)
                ew = max((cfg.outW * s).toInt(), 1)
                eh = max((cfg.outH * s).toInt(), 1)
            }

            val fbo = ensureFbo(ew, eh)
            fbo.bind()
            GLES20.glViewport(0, 0, ew, eh)

            // ---- date stamp placement ----
            computeStampRect(cfg.params, stampBmpW, stampBmpH)

            effects.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
            effects.setParams(cfg.params, ew, eh, nowSeconds(), stampColorArr, stampRectArr)
            effects.draw(quad, identity, uvMat)

            // ---- recording submit (frame dropping => genuine low fps) ----
            val rec = recorder
            if (rec != null && rec.running && !rec.paused) {
                val now = System.nanoTime()
                if (now >= recNextNs) {
                    val ptsUs = rec.frameCount * 1_000_000L / max(rec.cfg.fps, 1)
                    try {
                        rec.blitAndSubmit(blit, quad, fbo.tex, ptsUs)
                        rec.frameCount++
                        recNextNs = max(recNextNs + recIntervalNs, now)
                    } catch (t: Throwable) {
                        recorder = null
                        rec.releaseQuietly()
                        onRecordEvent?.invoke(RecordEvent.Failed(t.message ?: "encode"))
                    }
                }
            }

            // ---- blit to screen, letterboxed, NEAREST ----
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            clearScreen()
            val fa = ew.toFloat() / eh
            val va = surfaceW.toFloat() / surfaceH
            val sx: Float
            val sy: Float
            if (fa > va) {
                sx = 1f
                sy = va / fa
            } else {
                sy = 1f
                sx = fa / va
            }
            val pos = floatArrayOf(
                sx, 0f, 0f, 0f,
                0f, sy, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f
            )
            blit.use()
            blit.draw(quad, fbo.tex, pos)

            // ---- photo capture ----
            // Done AFTER the screen blit: re-render the effects pass with a
            // vertically flipped quad so glReadPixels returns top-down rows
            // without any extra bitmap flip or memory copy. The FBO content
            // is rewritten next frame, so flipping it here is harmless.
            if (capturePending) {
                capturePending = false
                fbo.bind()
                GLES20.glViewport(0, 0, ew, eh)
                val flip = floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, -1f, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f
                )
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
                effects.setParams(cfg.params, ew, eh, nowSeconds(), stampColorArr, stampRectArr)
                effects.draw(quad, flip, uvMat)
                readPhoto(ew, eh)
            }
        }

        private fun clearScreen() {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        private fun ensureFbo(w: Int, h: Int): GlUtils.Fbo {
            val cur = mainFbo
            if (cur != null && cur.w == w && cur.h == h) return cur
            cur?.delete()
            val fbo = GlUtils.createFbo(w, h)
            mainFbo = fbo
            return fbo
        }

        private fun drainCommands() {
            while (true) {
                val cmd = commands.poll() ?: break
                when (cmd) {
                    is GlCommand.CapturePhoto -> capturePending = true
                    is GlCommand.StartRecording -> startRecording(cmd.cfg)
                    is GlCommand.StopRecording -> stopRecording()
                    is GlCommand.PauseRecording -> recorder?.paused = true
                    is GlCommand.ResumeRecording -> {
                        recorder?.paused = false
                        recNextNs = System.nanoTime()
                    }
                    is GlCommand.UpdateStamp -> uploadStamp(cmd.bitmap)
                }
            }
        }

        private fun startRecording(cfg: RecConfig) {
            if (recorder != null) return
            try {
                val rec = FrameRecorder(cfg, currentEgl(), appContext)
                rec.start()
                recorder = rec
                recIntervalNs = 1_000_000_000L / max(cfg.fps, 1)
                recNextNs = System.nanoTime()
                onRecordEvent?.invoke(RecordEvent.Started(0L))
            } catch (t: Throwable) {
                onRecordEvent?.invoke(RecordEvent.Failed(t.message ?: "start"))
            }
        }

        private fun stopRecording() {
            val rec = recorder ?: return
            recorder = null
            try {
                rec.stop()
                onRecordEvent?.invoke(RecordEvent.Stopped)
            } catch (t: Throwable) {
                onRecordEvent?.invoke(RecordEvent.Failed(t.message ?: "stop"))
            } finally {
                rec.releaseQuietly()
            }
        }

        private fun uploadStamp(bitmap: Bitmap?) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
            if (bitmap == null) {
                stampBmpW = 1
                stampBmpH = 1
                // 1x1 transparent placeholder
                val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                px.put(byteArrayOf(0, 0, 0, 0)).position(0)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px
                )
            } else {
                stampBmpW = bitmap.width
                stampBmpH = bitmap.height
                android.opengl.GLUtils.texImage2D(
                    GLES20.GL_TEXTURE_2D, 0, bitmap, 0
                )
            }
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        private fun computeStampRect(p: EffectParams, bw: Int, bh: Int) {
            // Orange stamp tint is baked into the bitmap; keep alpha full.
            stampColorArr[0] = 1f
            stampColorArr[1] = 1f
            stampColorArr[2] = 1f
            stampColorArr[3] = 1f
            val hUv = 0.075f
            val wUv = hUv * (bw.toFloat() / bh.toFloat())
            val bottomMargin = if (p.frame == FrameStyle.POLAROID) 0.20f else 0.075f
            stampRectArr[0] = 1f - wUv - 0.05f
            stampRectArr[1] = 1f - hUv - bottomMargin
            stampRectArr[2] = wUv
            stampRectArr[3] = hUv
        }

        private fun readPhoto(w: Int, h: Int) {
            val size = w * h * 4
            var buf = pixelBuf
            if (buf == null || pixelBufSize < size) {
                buf = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
                pixelBuf = buf
                pixelBufSize = size
            }
            buf.position(0)
            buf.limit(size)
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            // Copy out so the buffer can be reused while encoding runs elsewhere.
            val bytes = ByteArray(size)
            buf.position(0)
            buf.get(bytes)
            onPhoto?.invoke(w, h, bytes)
        }

        /** Used by FrameRecorder to create its EGL surface on the shared context. */
        internal fun currentEgl(): Triple<android.opengl.EGLDisplay, android.opengl.EGLContext, android.opengl.EGLConfig?> {
            val dpy = EGL14.eglGetCurrentDisplay()
            val ctx = EGL14.eglGetCurrentContext()
            val surf = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val cfgId = IntArray(1)
            EGL14.eglQuerySurface(dpy, surf, EGL10_CONFIG_ID, cfgId, 0)
            val num = IntArray(1)
            EGL14.eglGetConfigs(dpy, null, 0, 0, num, 0)
            val configs = arrayOfNulls<android.opengl.EGLConfig>(num[0])
            EGL14.eglGetConfigs(dpy, configs, 0, configs.size, num, 0)
            for (c in configs) {
                val id = IntArray(1)
                EGL14.eglGetConfigAttrib(dpy, c, EGL10_CONFIG_ID, id, 0)
                if (id[0] == cfgId[0]) return Triple(dpy, ctx, c)
            }
            return Triple(dpy, ctx, null as android.opengl.EGLConfig?)
        }
    }

    companion object {
        /** Preview is rendered at the exact target size up to this long side;
         *  above it, a downscaled preview is visually identical (and much
         *  cheaper). Photos and videos always render at the exact target. */
        const val PREVIEW_CAP = 1440
        private const val EGL10_CONFIG_ID = 0x3028
    }
}

/**
 * GLSurfaceView hosting the pipeline. ES 2.0 context so that
 * samplerExternalOES works on every device.
 */
class GlCameraView(context: Context, val pipe: GlPipe) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(pipe.renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        pipe.view = this
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (pipe.view === this) pipe.view = null
    }
}
