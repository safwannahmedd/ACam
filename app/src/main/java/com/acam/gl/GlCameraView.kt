package com.acam.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.acam.data.EffectParams
import com.acam.data.FrameStyle
import com.acam.util.Framing
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** Immutable snapshot of everything the renderer needs for one frame. */
class PipeConfig(
    val params: EffectParams = EffectParams(),
    val outW: Int = 1920,
    val outH: Int = 1080,
    val mirror: Boolean = false,
    val displayRotation: Int = 0,
    val sensorOrientation: Int = 90,
    /** Live viewfinder zoom (user pinch). Applied DIGITALLY by the renderer
     *  on viewfinder sessions (see [GlPipe.viewfinderHwZoom] and the 1.2.5
     *  fix in onDrawFrame); the still capture keeps its own hardware zoom. */
    val zoom: Float = 1f,
    /** Date stamp corner: 0 top-left, 1 bottom-right, 2 bottom-left,
     *  3 top-right (from DateStampConfig.corner). */
    val stampCorner: Int = 1
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

    /** Full-resolution still, decoded from the ImageCapture JPEG, rendered
     *  through the same effects pipeline as the live preview. The renderer
     *  recycles the bitmap after uploading it to a GL texture. */
    class OfflineCapture(val bitmap: Bitmap, val rotationDeg: Int) : GlCommand()
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
 *
 * THE LIVE SOURCE (1.2.1): the CameraX Preview use case composes frames
 * straight into a SurfaceTexture owned by this pipeline, which the renderer
 * consumes as a GL_TEXTURE_EXTERNAL_OES.
 *
 * THE HANDSHAKE LATCH (1.2.2): the SurfaceTexture created on the GL thread
 * is stored here the moment it exists ([currentSurfaceTexture]) AND pushed
 * through [onSurfaceTexture]. The camera controller pulls the latch at bind
 * time in addition to listening for the push, so the controller can never
 * end up waiting forever for a notification that was fired before it
 * subscribed - the 1.2.1 "viewfinder never shows a frame" race: on devices
 * where CameraX init is slower than GL surface creation, the push fired
 * into a null listener and no Preview was ever bound. The view then never
 * drew anything and showed the window surface's uninitialized memory
 * (a flat, session-dependent gray/green field).
 */
class GlPipe(private val appContext: Context) {

    val config = AtomicReference(PipeConfig())

    @Volatile var streamW: Int = 0
        internal set
    @Volatile var streamH: Int = 0
        internal set

    /** Wall clock (elapsedRealtime) of the last stream (re)start - a new
     *  SurfaceTexture, a new SurfaceRequest or a ladder resize. The probe
     *  grants the camera this long to deliver its first frame before a
     *  "frozen" verdict may count. */
    @Volatile var streamResetAtMs: Long = SystemClock.elapsedRealtime()
        private set

    /** Latched live-source SurfaceTexture (GL thread writes, anyone reads). */
    @Volatile private var liveSt: android.graphics.SurfaceTexture? = null

    /** The pipeline's current live-source SurfaceTexture, or null while the
     *  GL context has not created one yet. Safe from any thread. */
    fun currentSurfaceTexture(): android.graphics.SurfaceTexture? = liveSt

    /** Mark that a new camera stream is being set up (grace timer reset). */
    fun noteStreamReset() {
        streamResetAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * True while the viewfinder runs on CPU-converted frames pushed by the
     * camera controller - the fallback of last resort, engaged only after
     * every GL-preview resolution was declared degenerate. Set by the
     * controller; read by the renderer on every frame.
     */
    @Volatile var cpuFallback: Boolean = false

    /**
     * THE 1.2.5 ZOOM FIX: true while the CURRENT viewfinder session zooms in
     * hardware (classic Preview+still binding, or the system PreviewView).
     * While false (the default single-stream viewfinder shapes), the camera
     * control is pinned to 1x and the renderer applies [PipeConfig.zoom]
     * digitally instead. Budget HALs implement zoom as a sensor-space
     * scaler.cropRegion and mangle every NON-still stream they apply it to
     * (a ~2000px rect landing on a 640x480 buffer = clamped corner sliver /
     * memset field / torn rows: the "extremely zoomed / flat / striped
     * viewfinder, photos perfect" failure). Keeping the crop region away
     * from viewfinder streams removes that entire failure class. Written by
     * the controller at bind time; read by the renderer and the tap-to-focus
     * mapping.
     */
    @Volatile var viewfinderHwZoom: Boolean = false

    /** Newest-wins slot for the CPU-converted viewfinder frame. The
     *  controller's analyzer thread publishes, the GL thread consumes. */
    private val cpuFrameSlot = AtomicReference<Bitmap?>(null)

    // ---- tap-to-focus support (the 1.2.7 fix) ----
    // The exact UV chain of the last drawn frame, latched for inversion.
    // Two pre-allocated buffers: the GL thread fills the idle one and flips
    // the index, readers snapshot the published one - no per-frame
    // allocation, no lock on the render path.
    private val uvLatch = arrayOf(FloatArray(16), FloatArray(16))
    @Volatile private var uvLatchIdx = 0
    @Volatile private var uvLatchValid = false

    /** GL thread: publish the frame's UV chain (target uv -> buffer uv). */
    internal fun latchUvMatrix(m: FloatArray) {
        val w = 1 - uvLatchIdx
        System.arraycopy(m, 0, uvLatch[w], 0, 16)
        uvLatchIdx = w
        uvLatchValid = true
    }

    /** Invert the last drawn frame's UV chain: target-frame uv (GL-natural,
     *  v = 0 at the frame BOTTOM) -> source-buffer uv ((0,0) = the buffer's
     *  top-left, the coordinate system SurfaceOrientedMeteringPointFactory
     *  expects). Whatever the device's SurfaceTexture transform, the sensor
     *  rotation, the digital zoom, the mirror or the CPU fallback did to the
     *  viewfinder, this returns the exact buffer pixel under the user's
     *  finger. Returns null before the first frame (or on a degenerate
     *  chain). Safe from any thread. */
    fun mapTargetToStream(u: Float, v: Float): Pair<Float, Float>? {
        if (!uvLatchValid) return null
        val m = uvLatch[uvLatchIdx]
        val a = m[0]; val b = m[4]
        val c = m[1]; val d = m[5]
        val det = a * d - b * c
        if (java.lang.Math.abs(det) < 1e-9f) return null
        val x = u - m[12]
        val y = v - m[13]
        return ((d * x - b * y) / det) to ((a * y - c * x) / det)
    }

    /** Controller (analyzer thread): publish one converted viewfinder
     *  frame. A not-yet-consumed older frame is simply dropped (GC). */
    fun pushCpuFrame(frame: Bitmap) {
        cpuFrameSlot.set(frame)
        view?.requestRender()
    }

    /** GL thread: take the pending frame, if any. */
    internal fun takeCpuFrame(): Bitmap? = cpuFrameSlot.getAndSet(null)

    /** Notified (GL thread) whenever the live stream size changes. */
    var onStreamSize: ((w: Int, h: Int) -> Unit)? = null

    /**
     * Notified (GL thread) when a (new) SurfaceTexture exists for the live
     * source - once per GL context. The camera controller stores it and
     * (re)binds the Preview use case to it.
     */
    var onSurfaceTexture: ((android.graphics.SurfaceTexture) -> Unit)? = null

    /**
     * Notified (GL thread) when the degenerate-stream probe concludes the
     * camera is feeding unusable frames (flat / stale / frozen). The
     * controller falls back down the preview resolution ladder.
     */
    var onStreamDegenerate: ((reason: String) -> Unit)? = null

    /** Notified (GL thread) when the probe sees healthy frames again. */
    var onStreamHealthy: (() -> Unit)? = null

    /** Preview buffer size to start with (controller's ladder rung). */
    @Volatile var previewSizeProvider: () -> Size = { Size(1280, 960) }

    var onPhoto: ((w: Int, h: Int, rgba: ByteArray) -> Unit)? = null
    var onRecordEvent: ((RecordEvent) -> Unit)? = null

    val commands = ConcurrentLinkedQueue<GlCommand>()

    @Volatile internal var view: GLSurfaceView? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val t0Ms = SystemClock.elapsedRealtime()

    fun nowSeconds(): Float = (SystemClock.elapsedRealtime() - t0Ms) / 1000f

    fun post(cmd: GlCommand) {
        commands.add(cmd)
        view?.requestRender()
    }

    /** Update the pipeline config and immediately request a render, so
     *  ratio / effect / mirror changes are visible even before the next
     *  camera frame arrives. */
    fun setConfig(cfg: PipeConfig) {
        config.set(cfg)
        view?.requestRender()
    }

    /**
     * The controller reports the stream size the camera session actually
     * settled on (from the Preview SurfaceRequest resolution). Sensor
     * oriented, like everything else in the framing math.
     */
    fun setStreamSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        var changed = false
        synchronized(this) {
            if (streamW != w || streamH != h) {
                streamW = w
                streamH = h
                changed = true
            }
        }
        if (changed) {
            noteStreamReset()
            onStreamSize?.invoke(w, h)
        }
    }

    /** New preview buffer size (ladder fallback) - safe from any thread. */
    fun resizePreview(w: Int, h: Int) {
        renderer.surfaceTexture?.setDefaultBufferSize(w, h)
        setStreamSize(w, h)
    }

    /** True while the live source exists but frames have stopped (or never
     *  started): the view needs watchdog render ticks so the starved-frame
     *  path (screen clear + frozen-stream probe) keeps executing. */
    fun needsWatchdogRender(): Boolean = renderer.starved()

    val renderer = Renderer()

    /** The GLSurfaceView.Renderer implementing the WYSIWYG pipeline. */
    inner class Renderer : GLSurfaceView.Renderer {

        private var blit: BlitProgram? = null
        private var quad: java.nio.FloatBuffer? = null
        private var overlayTex = 0
        private var captureTex = 0
        private var stampBmpW = 1
        private var stampBmpH = 1
        private var mainFbo: GlUtils.Fbo? = null
        private var offlineFbo: GlUtils.Fbo? = null

        // ---- live external-texture source (GL thread only) ----
        private var liveProgram: EffectProgram? = null
        @Volatile internal var surfaceTexture: android.graphics.SurfaceTexture? = null
            internal set
        private var oesTex = 0

        // ---- CPU-frame fallback source (GL thread only) ----
        private var cpuTex = 0
        @Volatile private var haveCpuFrame = false

        /** elapsedRealtime of the last CPU-frame upload; 0 until the first.
         *  Drives the watchdog render tick while in fallback mode. */
        @Volatile private var lastCpuAtMs = 0L

        @Volatile private var frameArrived = false
        private var framesReceived = 0L

        /** elapsedRealtime of the last updateTexImage; 0 until the first
         *  frame. Drives the watchdog render tick. */
        @Volatile private var lastFrameAtMs = 0L

        /** True when the live source exists but no frame has arrived for
         *  over a second: the view is starved and onDrawFrame must keep
         *  running (watchdog tick) so the probe can detect a frozen stream.
         *  RENDERMODE_WHEN_DIRTY alone would stop drawing - and a probe that
         *  only lives inside onDrawFrame can never notice its own absence. */
        fun starved(): Boolean {
            if (cpuFallback) {
                if (!haveCpuFrame) return true
                return SystemClock.elapsedRealtime() - lastCpuAtMs > 1000L
            }
            if (surfaceTexture == null) return false
            if (lastFrameAtMs == 0L) return true
            return SystemClock.elapsedRealtime() - lastFrameAtMs > 1000L
        }

        /** SurfaceTexture transform, refreshed after every updateTexImage. */
        private val stMat = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

        // ---- degenerate-stream probe (GL thread only) ----
        private var probeFbo: GlUtils.Fbo? = null
        private var lastProbeAtMs = 0L
        private var probeBadRun = 0
        private var probeLastHash = 0L
        private var probeHaveHash = false
        private var probeFramesAtLast = -1L
        private var probeWasBad = false
        private val probeBuf =
            ByteBuffer.allocateDirect(PROBE_W * PROBE_H * 4).order(ByteOrder.nativeOrder())

        /** Top-down flip for readbacks: renders the quad upside down so
         *  glReadPixels returns top-down rows with no extra copy. */
        private val flipPos = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        private val identity = Mat4.identity()
        private val flipYMat = Mat4.flipY()
        private var haveFrame = false
        private var surfaceW = 1
        private var surfaceH = 1

        // ---- pre-allocated per-frame matrices (no allocations in the hot
        //      path; see the project's "no per-frame allocation" rule) ----
        private val uvMat = FloatArray(16)
        private val uvMat2 = FloatArray(16)
        private val uvTmp = FloatArray(16)
        private val rotMat = FloatArray(16)
        private val cropMat = FloatArray(16)
        private val mirrorMat = FloatArray(16)
        private val screenPos = FloatArray(16)

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
            blit = BlitProgram()
            quad = GlUtils.quadBuffer()
            overlayTex = GlUtils.genTexture(GLES20.GL_TEXTURE_2D)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            // A fresh context invalidates every previous object name; drop
            // them all so the next frame re-creates everything it needs.
            liveProgram = null
            effects2D = null
            captureTex = 0
            cpuTex = 0
            haveCpuFrame = false
            mainFbo?.delete()
            mainFbo = null
            offlineFbo?.delete()
            offlineFbo = null
            probeFbo?.delete()
            probeFbo = null
            haveFrame = false
            probeBadRun = 0
            probeHaveHash = false
            probeWasBad = false
            lastProbeAtMs = 0L
            probeFramesAtLast = -1L
            createLiveSource()
        }

        /**
         * Create the external-OES texture + SurfaceTexture for the live
         * viewfinder and hand it to the camera controller. A previous
         * SurfaceTexture (from a lost GL context) is released first; the
         * controller rebinds the Preview use case onto the new one.
         */
        private fun createLiveSource() {
            val old = surfaceTexture
            surfaceTexture = null
            runCatching { old?.release() }
            if (oesTex != 0) GlUtils.deleteTexture(oesTex)

            val tex = GlUtils.genOesTexture()
            val st = android.graphics.SurfaceTexture(tex)
            val sz = previewSizeProvider()
            runCatching { st.setDefaultBufferSize(sz.width, sz.height) }
            // Frame callbacks on the main looper: the handler work is a
            // volatile flag set + requestRender, both cheap and thread-safe.
            st.setOnFrameAvailableListener({
                frameArrived = true
                view?.requestRender()
            }, mainHandler)
            oesTex = tex
            surfaceTexture = st
            liveSt = st                      // latch for late subscribers
            frameArrived = false
            framesReceived = 0
            lastFrameAtMs = 0L
            noteStreamReset()
            Log.i("GlPipe", "Live source: SurfaceTexture ${sz.width}x${sz.height} (OES)")
            onSurfaceTexture?.invoke(st)
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
            surfaceW = max(width, 1)
            surfaceH = max(height, 1)
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            drainCommands()

            val cfg = config.get()
            val blit = this.blit ?: return
            val quad = this.quad ?: return
            val cpuMode = cpuFallback
            val st = if (cpuMode) null else surfaceTexture

            if (cpuMode) {
                // ---- CPU-frame fallback: consume the newest converted frame ----
                consumeCpuFrame()
                if (!haveCpuFrame || streamW == 0 || streamH == 0) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, surfaceW, surfaceH)
                    clearScreen()
                    return
                }
            } else {
                // ---- consume the newest camera frame, if any ----
                if (frameArrived) {
                    frameArrived = false
                    if (st != null) {
                        try {
                            st.updateTexImage()
                            st.getTransformMatrix(stMat)
                            sanitizeStMat()
                            framesReceived++
                            haveFrame = true
                            lastFrameAtMs = SystemClock.elapsedRealtime()
                        } catch (t: Throwable) {
                            Log.e("GlPipe", "updateTexImage failed", t)
                        }
                    }
                }

                if (st == null || !haveFrame || streamW == 0 || streamH == 0) {
                    // Explicitly address the window surface: a previous frame
                    // may have left the probe FBO bound, and clearing that
                    // instead of the screen would leave stale pixels showing.
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                    GLES20.glViewport(0, 0, surfaceW, surfaceH)
                    clearScreen()
                    maybeProbe(quad, 0)
                    return
                }
            }
            var fx = if (cpuMode) effects2D else liveProgram
            if (fx == null) {
                try {
                    fx = if (cpuMode) {
                        EffectProgram(SrcKind.TEX2D).also { effects2D = it }
                    } else {
                        EffectProgram(SrcKind.OES).also { liveProgram = it }
                    }
                } catch (t: Throwable) {
                    Log.e("GlPipe", "effects program build failed", t)
                    clearScreen()
                    return
                }
            }

            // ---- framing: ST * R * C * M ----
            // Identical chain to the offline photo render except that the
            // SurfaceTexture transform (ST) replaces FLIP_Y: it carries the
            // producer's row order (a Y flip on every real device), while
            // the buffer itself is still sensor-oriented, so the rotation
            // direction stays the INVERSE of the image-space rotation
            // (SENSOR_ORIENTATION says "rotate the buffer clockwise by this
            // angle to be upright"; a clockwise image rotation is a
            // counter-clockwise UV rotation, so the matrix gets (360-rot)).
            //
            // THE 1.2.7 ROTATION FIX: a class of camera stacks hands the
            // SurfaceTexture a transform that ALREADY carries the sensor
            // rotation - a transpose-type matrix (2x2 part dominated by the
            // off-diagonal) instead of the plain Y flip. The buffer these
            // stacks deliver is pre-rotated, and the transform is the
            // producer's own instruction for reading it upright. The old
            // chain assumed base = flipY and applied R((360-rot)) on top,
            // double-rotating the viewfinder by 90 degrees on exactly those
            // devices while photos stayed correct (they render from the
            // JPEG's own rotationDegrees, which CameraX had already set to
            // the pre-rotated truth). Detection is per-frame, so a HAL that
            // changes its mind mid-stream is followed transparently. When
            // the transform owns the rotation, R becomes the identity and
            // the crop is computed on the ROTATED (upright) stream dims -
            // the stream as seen THROUGH the transform.
            val stRotates = !cpuMode && stMatTransposed()
            val rot = if (stRotates) 0
                else ((cfg.sensorOrientation - cfg.displayRotation) % 360 + 360) % 360
            val cropW = if (stRotates) streamH else streamW
            val cropH = if (stRotates) streamW else streamH
            val (csx, csy) = Framing.cropScale(cropW, cropH, rot, cfg.outW, cfg.outH)
            // THE 1.2.5 ZOOM FIX: the viewfinder zooms DIGITALLY - the
            // sampled window shrinks by the zoom factor around the center,
            // uniformly on both axes so the output aspect (and therefore the
            // crop already computed above) is preserved. Sessions that zoom
            // in hardware (classic Preview+still, system viewfinder) keep a
            // factor of 1 here so the zoom is never applied twice. This is
            // the same visual result the HAL's crop would give a healthy
            // stream, minus every way a budget HAL can mangle it.
            val zoom = if (viewfinderHwZoom) 1f else cfg.zoom.coerceAtLeast(1f)
            System.arraycopy(Mat4.rotation((360 - rot) % 360), 0, rotMat, 0, 16)
            System.arraycopy(Mat4.crop(csx / zoom, csy / zoom), 0, cropMat, 0, 16)
            // The base transform: the (sanitized) SurfaceTexture transform
            // on the OES path, the plain top-down flip on the CPU-frame path
            // (row 0 of a GLUtils-uploaded bitmap lands at v = 0, exactly
            // like the decoded-JPEG photo render - the chain the photo path
            // already proves correct on every device).
            val baseMat = if (cpuMode) flipYMat else stMat
            if (cfg.mirror) {
                System.arraycopy(Mat4.mirror(), 0, mirrorMat, 0, 16)
                Mat4.mulInto(uvTmp, cropMat, mirrorMat)          // C * M
                Mat4.mulInto(uvMat2, rotMat, uvTmp)              // R * C * M
                Mat4.mulInto(uvMat, baseMat, uvMat2)             // B * R * C * M
            } else {
                Mat4.mulInto(uvTmp, rotMat, cropMat)             // R * C
                Mat4.mulInto(uvMat, baseMat, uvTmp)              // B * R * C
            }
            // Latch the exact chain (target uv -> buffer uv) so view taps
            // can be inverted into metering pixels - whatever the device's
            // transform, the zoom, the mirror or the fallback mode did to
            // this frame (see [GlPipe.mapTargetToStream]).
            latchUvMatrix(uvMat)

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
            computeStampRect(cfg.params, cfg.stampCorner, stampBmpW, stampBmpH)

            fx.use()
            bindSource(cpuMode)
            fx.setParams(cfg.params, ew, eh, nowSeconds(), stampColorArr, stampRectArr)
            fx.draw(quad, identity, uvMat)

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
            screenPos[0] = sx; screenPos[5] = sy
            screenPos[10] = 1f; screenPos[15] = 1f
            screenPos[1] = 0f; screenPos[2] = 0f; screenPos[3] = 0f
            screenPos[4] = 0f; screenPos[6] = 0f; screenPos[7] = 0f
            screenPos[8] = 0f; screenPos[9] = 0f; screenPos[11] = 0f
            screenPos[12] = 0f; screenPos[13] = 0f; screenPos[14] = 0f
            blit.use()
            blit.draw(quad, fbo.tex, screenPos)

            // ---- photo capture ----
            // Done AFTER the screen blit: re-render the effects pass with a
            // vertically flipped quad so glReadPixels returns top-down rows
            // without any extra bitmap flip or memory copy. The FBO content
            // is rewritten next frame, so flipping it here is harmless.
            if (capturePending) {
                capturePending = false
                fbo.bind()
                GLES20.glViewport(0, 0, ew, eh)
                fx.use()
                bindSource(cpuMode)
                fx.setParams(cfg.params, ew, eh, nowSeconds(), stampColorArr, stampRectArr)
                fx.draw(quad, flipPos, uvMat)
                readPhoto(ew, eh)
            }

            // ---- degenerate-stream probe: the exact pixels just shown ----
            if (!cpuMode) maybeProbe(quad, fbo.tex)
        }

        /** Bind the active camera source + overlay to their fixed units:
         *  0 = camera source, 3 = overlay. The source is the OES external
         *  texture on the GL-preview path, the uploaded CPU-frame texture
         *  on the fallback path. */
        private fun bindSource(cpuMode: Boolean) {
            if (cpuMode) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cpuTex)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
            } else {
                bindLiveSource(oesTex, overlayTex)
            }
        }

        /**
         * Upload the newest CPU-converted frame (if any) to its 2D texture.
         * Called once per drawn frame while in fallback mode; returns true
         * when a usable frame is (already) bound.
         */
        private fun consumeCpuFrame(): Boolean {
            val bmp = takeCpuFrame() ?: return haveCpuFrame
            try {
                if (cpuTex == 0) cpuTex = GlUtils.genTexture(GLES20.GL_TEXTURE_2D)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cpuTex)
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                haveCpuFrame = true
                lastCpuAtMs = SystemClock.elapsedRealtime()
            } catch (t: Throwable) {
                Log.e("GlPipe", "CPU frame upload failed", t)
            } finally {
                // The upload is synchronous; the bitmap is done.
                runCatching { bmp.recycle() }
            }
            return haveCpuFrame
        }

        /**
         * Validate the SurfaceTexture transform every frame, cheaply: it
         * must be finite and map the unit square into a sane window. A
         * class of budget GPU drivers has been observed handing out zero /
         * NaN matrices, which collapse every UV to a single point - the
         * "viewfinder is one flat colour while photos are fine" failure.
         * A bad matrix is replaced with the standard camera transform
         * (a Y flip), which is what healthy devices return anyway.
         */
        private fun sanitizeStMat() {
            val m = stMat
            for (v in m) {
                if (!v.isFinite()) {
                    Log.e("GlPipe", "SurfaceTexture transform has non-finite entries - substituting flipY")
                    System.arraycopy(Mat4.flipY(), 0, m, 0, 16)
                    return
                }
            }
            // Column-major: uv' = [m0 m4; m1 m5] * uv + [m12; m13].
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (cu in CU) for (cv in CU) {
                val px = m[0] * cu + m[4] * cv + m[12]
                val py = m[1] * cu + m[5] * cv + m[13]
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
            }
            // Healthy camera transforms (flip, crop-rect) map [0,1]^2 into
            // roughly [0,1]; degenerate ones collapse to a point or fly off.
            val bad = maxX < -0.05f || minX > 1.05f ||
                maxY < -0.05f || minY > 1.05f ||
                (maxX - minX) < 0.5f || (maxY - minY) < 0.5f
            if (bad) {
                Log.e(
                    "GlPipe",
                    "Degenerate SurfaceTexture transform (x:[${minX}..${maxX}] y:[${minY}..${maxY}]) - substituting flipY"
                )
                System.arraycopy(Mat4.flipY(), 0, m, 0, 16)
            }
        }

        /** True when the current SurfaceTexture transform is transpose-type:
         *  its 2x2 part is dominated by the off-diagonal, meaning the
         *  producer already applied the sensor rotation to the buffer and
         *  encoded it in the transform. Healthy camera stacks return a
         *  Y-flip (diagonal). Must be called after getTransformMatrix +
         *  [sanitizeStMat] so the matrix under test is the real one. */
        private fun stMatTransposed(): Boolean {
            val diag = Math.abs(stMat[0]) + Math.abs(stMat[5])
            val off = Math.abs(stMat[1]) + Math.abs(stMat[4])
            return off > diag
        }

        /** Bind the live source + overlay to their fixed units:
         *  0 = camera OES texture, 3 = overlay. */
        private fun bindLiveSource(oesTex: Int, overlayTex: Int) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        }

        /** Run [runProbe] at most once per [PROBE_PERIOD_MS] of drawn
         *  frames. Time-based (not frame-count-based) so that a starved
         *  view - where draws only happen on the watchdog tick - still
         *  probes often enough to detect the stall within seconds.
         *  [srcTex] is the texture holding the frame the user just SAW
         *  (the effects FBO), or 0 when there is nothing new to judge. */
        private fun maybeProbe(quad: java.nio.FloatBuffer, srcTex: Int) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastProbeAtMs < PROBE_PERIOD_MS) return
            lastProbeAtMs = now
            runProbe(quad, srcTex)
        }

        /**
         * Cheap liveness check on the FINAL RENDERED VIEWFINDER PIXELS
         * (the exact texture blitted to the screen this frame): a 32x24
         * readback roughly once per [PROBE_PERIOD_MS].
         *
         * THE 1.2.4 FIX: the probe used to sample the RAW external
         * (OES) texture through its own render pass. On budget GPU/HAL
         * combos that render path disagreed with what the effects pass
         * put on screen - the user stared at a perfectly flat or torn
         * viewfinder while the probe happily read "healthy" pixels and
         * the whole fallback ladder never engaged. The probe now judges
         * the exact pixels the user sees, read back through the SAME
         * plain-RGBA-FBO channel the photo capture already proves
         * reliable on every device (readPhoto).
         *
         * Detects every failure mode seen in the field:
         *  - FLAT non-black fields (exactly or near-uniform - budget HALs
         *    have been observed writing a memset buffer, or the GPU's
         *    external-texture sampling collapsing to one value),
         *  - TORN / SMEARED buffers (near-constant rows that differ
         *    wildly between rows),
         *  - STALE frames (the exact same pixels re-rendered),
         *  - FROZEN streams (no new frames at all).
         * Black is legal (a dark room); only non-black flatness counts.
         *
         * A grace period after every stream (re)start keeps slow camera
         * opens (budget HALs can take well over a second) from being
         * misread as a freeze.
         */
        private fun runProbe(quad: java.nio.FloatBuffer, srcTex: Int) {
            // No live source (GL context never created one) -> nothing to
            // judge; the controller has nothing bound anyway.
            if (surfaceTexture == null) return
            val framesNow = framesReceived
            var bad = false
            var reason = ""

            if (framesNow == probeFramesAtLast) {
                // No new frame since the previous probe. Only counts as
                // "frozen" once the post-restart grace has elapsed; a
                // freshly (re)bound camera legitimately needs time.
                if (SystemClock.elapsedRealtime() - streamResetAtMs > FROZEN_GRACE_MS) {
                    bad = true
                    reason = "frozen"
                }
            } else {
                probeFramesAtLast = framesNow
                if (srcTex > 0) {
                    try {
                        val blitProg = blit ?: return
                        val fbo = probeFbo?.takeIf { it.w == PROBE_W && it.h == PROBE_H }
                            ?: GlUtils.createFbo(PROBE_W, PROBE_H).also { probeFbo = it }
                        fbo.bind()
                        GLES20.glViewport(0, 0, PROBE_W, PROBE_H)
                        blitProg.use()
                        blitProg.draw(quad, srcTex, identity)

                        probeBuf.position(0)
                        probeBuf.limit(PROBE_W * PROBE_H * 4)
                        GLES20.glReadPixels(
                            0, 0, PROBE_W, PROBE_H,
                            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, probeBuf
                        )
                        var minV = 255
                        var maxV = 0
                        var sum = 0L
                        var hash = 0x811c9dc5L
                        val rowSum = LongArray(PROBE_H)
                        val rowSq = LongArray(PROBE_H)
                        var idx = 0
                        val n = PROBE_W * PROBE_H
                        var i = 0
                        while (i < n) {
                            val v = probeBuf.get(idx).toInt() and 0xFF
                            if (v < minV) minV = v
                            if (v > maxV) maxV = v
                            sum += v
                            hash = (hash xor (v.toLong() * (i + 1))) * 0x01000193L and 0xFFFFFFFFL
                            val r = i / PROBE_W
                            rowSum[r] += v
                            rowSq[r] += v.toLong() * v
                            idx += 4
                            i++
                        }
                        val mean = sum / n
                        // Per-row structure: a torn / line-smeared buffer has
                        // near-constant rows that differ wildly BETWEEN rows -
                        // a shape no real scene produces (a flat wall makes all
                        // rows equally flat; a real image varies within rows).
                        var withinVar = 0.0
                        var minRow = Double.MAX_VALUE
                        var maxRow = -Double.MAX_VALUE
                        for (r in 0 until PROBE_H) {
                            val rm = rowSum[r].toDouble() / PROBE_W
                            val rv = (rowSq[r].toDouble() / PROBE_W - rm * rm).coerceAtLeast(0.0)
                            withinVar += rv
                            if (rm < minRow) minRow = rm
                            if (rm > maxRow) maxRow = rm
                        }
                        withinVar /= PROBE_H
                        val rowSpread = maxRow - minRow
                        // "flat": perfectly constant frame (a memset field from
                        // the HAL). Real scenes at 32x24 essentially always vary
                        // by at least two levels, even defocused flat walls.
                        if (maxV - minV <= 1 && mean > 24) {
                            bad = true
                            reason = "flat (mean=$mean)"
                        } else if (probeHaveHash && hash == probeLastHash) {
                            bad = true
                            reason = "stale"
                        } else if (withinVar <= 4.0 && rowSpread > 24.0) {
                            bad = true
                            reason = "smeared rows (var=%.1f, spread=%.0f)".format(withinVar, rowSpread)
                        }
                        probeLastHash = hash
                        probeHaveHash = true
                    } catch (t: Throwable) {
                        // The probe must never break the frame path.
                        Log.w("GlPipe", "probe failed", t)
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                        return
                    }
                }
            }

            if (bad) {
                probeBadRun++
                if (probeBadRun >= PROBE_TRIGGER) {
                    // No latch: after the controller rebinds at the next
                    // ladder rung, a still-degenerate stream must be able
                    // to report again and keep falling back.
                    probeBadRun = 0
                    Log.e("GlPipe", "Degenerate live stream: $reason")
                    onStreamDegenerate?.invoke(reason)
                }
            } else {
                probeBadRun = 0
                if (probeWasBad) {
                    onStreamHealthy?.invoke()
                }
            }
            probeWasBad = bad

            // Leave the pipeline on the window surface; a clearScreen in a
            // later starved frame must hit the screen, not this probe FBO.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
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
                    is GlCommand.OfflineCapture -> renderOffline(cmd.bitmap, cmd.rotationDeg)
                }
            }
        }

        /**
         * Renders a full-resolution still (decoded ImageCapture JPEG) through
         * the same effects pipeline, at the exact target size, into a
         * dedicated FBO and reads it back for saving. Runs on the GL thread
         * during command draining, before the live preview pass, using its
         * own texture and FBO so preview state is untouched.
         *
         * The texture matrix mirrors the live path (FLIP_Y * R * C * M)
         * which guarantees the photo matches the viewfinder field of view
         * and orientation.
         */
        private fun renderOffline(bitmap: Bitmap, rotationDeg: Int) {
            val quad = this.quad
            if (quad == null) {
                bitmap.recycle()
                return
            }
            val cfg = config.get()
            val outW = cfg.outW
            val outH = cfg.outH
            if (outW <= 0 || outH <= 0) {
                bitmap.recycle()
                return
            }
            try {
                val p2d = effects2D ?: EffectProgram(SrcKind.TEX2D)
                    .also { effects2D = it }
                if (captureTex == 0) captureTex = GlUtils.genTexture(GLES20.GL_TEXTURE_2D)
                val bw = bitmap.width
                val bh = bitmap.height
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captureTex)
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                bitmap.recycle()

                val rot = ((rotationDeg % 360) + 360) % 360
                val (csx, csy) = Framing.cropScale(bw, bh, rot, outW, outH)
                // Same chain AND same rotation direction as the live pass
                // (see the long comment in onDrawFrame): R gets the inverse
                // of the image-space rotation.
                var uvMat = Mat4.mul(Mat4.flipY(), Mat4.rotation((360 - rot) % 360))
                uvMat = Mat4.mul(uvMat, Mat4.crop(csx, csy))
                if (cfg.mirror) uvMat = Mat4.mul(uvMat, Mat4.mirror())

                val fbo = ensureOfflineFbo(outW, outH)
                fbo.bind()
                GLES20.glViewport(0, 0, outW, outH)
                computeStampRect(cfg.params, cfg.stampCorner, stampBmpW, stampBmpH)

                p2d.use()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captureTex)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
                p2d.setParams(cfg.params, outW, outH, nowSeconds(), stampColorArr, stampRectArr)
                p2d.draw(quad, flipPos, uvMat)
                readPhoto(outW, outH)
            } catch (t: Throwable) {
                Log.e("GlPipe", "Offline capture render failed", t)
                // Fall back to rendering from the live stream so a photo is
                // still produced (and the flash overlay always clears).
                post(GlCommand.CapturePhoto)
            }
        }

        private var effects2D: EffectProgram? = null

        private fun ensureOfflineFbo(w: Int, h: Int): GlUtils.Fbo {
            val cur = offlineFbo
            if (cur != null && cur.w == w && cur.h == h) return cur
            cur?.delete()
            val fbo = GlUtils.createFbo(w, h)
            offlineFbo = fbo
            return fbo
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
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
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

        private fun computeStampRect(p: EffectParams, corner: Int, bw: Int, bh: Int) {
            // Stamp tint is baked into the bitmap; keep the multiply white.
            stampColorArr[0] = 1f
            stampColorArr[1] = 1f
            stampColorArr[2] = 1f
            stampColorArr[3] = 1f
            val hUv = 0.075f
            val wUv = hUv * (bw.toFloat() / bh.toFloat())
            val side = 0.05f
            // Bottom placements sit just above the polaroid's thick bottom band.
            val bottomMargin = if (p.frame == FrameStyle.POLAROID) 0.20f else 0.075f
            // Top placements mirror the bottom margins (no thick top band on
            // any frame style, so a plain margin is enough).
            val topMargin = if (p.frame == FrameStyle.POLAROID) 0.10f else 0.075f
            val left = corner == 0 || corner == 2
            val top = corner == 0 || corner == 3
            stampRectArr[0] = if (left) side else 1f - wUv - side
            stampRectArr[1] = if (top) topMargin else 1f - hUv - bottomMargin
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

        /** Degenerate-stream probe cadence: at most one probe per this many
         *  milliseconds of drawn frames (healthy or watchdog-driven). */
        private const val PROBE_PERIOD_MS = 500L

        /** Consecutive bad probes before the stream is declared degenerate. */
        private const val PROBE_TRIGGER = 3

        /** Grace after a stream (re)start before "frozen" may be declared:
         *  budget HALs legitimately need over a second to open + configure. */
        private const val FROZEN_GRACE_MS = 2500L

        private const val PROBE_W = 32
        private const val PROBE_H = 24

        /** Unit-square corner coordinates for the transform sanity check. */
        private val CU = floatArrayOf(0f, 1f)
    }
}

/**
 * GLSurfaceView hosting the pipeline. ES 2.0 context: everything the
 * pipeline needs (the OES external texture, FBOs, blits) is core ES 2 +
 * the mandatory GL_OES_EGL_image_external extension (required for
 * SurfaceTexture, present on every Android GPU).
 */
class GlCameraView(context: Context, val pipe: GlPipe) : GLSurfaceView(context) {

    /** Keeps onDrawFrame alive while the camera delivers no frames, so the
     *  frozen-stream probe can actually run (RENDERMODE_WHEN_DIRTY would
     *  otherwise stop drawing - and with it all detection). The tick itself
     *  is a clock read + two volatile reads at 2 Hz: free when healthy. */
    private val watchdog = Handler(Looper.getMainLooper())
    private val watchdogTick = object : Runnable {
        override fun run() {
            if (pipe.needsWatchdogRender()) {
                requestRender()
            }
            watchdog.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(pipe.renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        pipe.view = this
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        watchdog.post(watchdogTick)
    }

    override fun onDetachedFromWindow() {
        watchdog.removeCallbacks(watchdogTick)
        if (pipe.view === this) pipe.view = null
        super.onDetachedFromWindow()
    }

    private companion object {
        private const val WATCHDOG_PERIOD_MS = 500L
    }
}
