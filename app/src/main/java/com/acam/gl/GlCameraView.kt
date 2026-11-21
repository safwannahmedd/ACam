package com.acam.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
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
 * The live viewfinder frames arrive as YUV_420_888 ImageProxys from the
 * CameraX ImageAnalysis use case (the most universally supported camera
 * output format) and are uploaded to plain GL textures - the same texture
 * model as the full-resolution photo path, so the WYSIWYG guarantee holds
 * by construction: viewfinder and saved photo share one shader, one matrix
 * chain (FLIP_Y * R * C) and one framing math.
 */
class GlPipe(private val appContext: Context) {

    val config = AtomicReference(PipeConfig())

    @Volatile var streamW: Int = 0
        internal set
    @Volatile var streamH: Int = 0
        internal set

    /** Notified (GL thread) whenever the live stream size changes. */
    var onStreamSize: ((w: Int, h: Int) -> Unit)? = null

    var onPhoto: ((w: Int, h: Int, rgba: ByteArray) -> Unit)? = null
    var onRecordEvent: ((RecordEvent) -> Unit)? = null

    val commands = ConcurrentLinkedQueue<GlCommand>()

    @Volatile internal var view: GLSurfaceView? = null

    private val frameLock = Any()
    private var pendingFrame: ImageProxy? = null

    private val t0Ms = SystemClock.elapsedRealtime()

    fun nowSeconds(): Float = (SystemClock.elapsedRealtime() - t0Ms) / 1000f

    fun post(cmd: GlCommand) {
        commands.add(cmd)
        view?.requestRender()
    }

    /**
     * Called by the ImageAnalysis analyzer thread for every new frame.
     * O(1): swaps the single pending slot (closing any frame the GL thread
     * has not consumed yet) and wakes the GL thread. KEEP_ONLY_LATEST
     * backpressure: the camera delivers the next frame only after the GL
     * thread closes this one.
     */
    fun submitFrame(image: ImageProxy) {
        if (image.width <= 0 || image.height <= 0) {
            image.close()
            return
        }
        synchronized(frameLock) {
            pendingFrame?.close()
            pendingFrame = image
        }
        view?.requestRender()
    }

    /** Drop an unconsumed frame (view detached). */
    fun clearPending() {
        synchronized(frameLock) {
            pendingFrame?.close()
            pendingFrame = null
        }
    }

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

        // ---- live YUV source (GL thread only) ----
        private var liveProgram: EffectProgram? = null
        private var liveKind: SrcKind? = null
        private var texY = 0
        private var texU = 0          // planar U | semi: interleaved chroma
        private var texV = 0          // planar V only
        private var yuvAllocated = false
        private var yuvTexW = 0
        private var yuvTexH = 0
        private var cTexW = 0
        private var cTexH = 0
        private var semiPlanar = false
        private var firstIsU = true
        private val yuvScale = FloatArray(4)

        /** Top-down flip for readbacks: renders the quad upside down so
         *  glReadPixels returns top-down rows with no extra copy. */
        private val flipPos = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        private val identity = Mat4.identity()
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
            blit = BlitProgram()
            quad = GlUtils.quadBuffer()
            overlayTex = GlUtils.genTexture(GLES20.GL_TEXTURE_2D)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            // A fresh context invalidates every previous object name; drop
            // them all so the next frame re-creates everything it needs.
            liveProgram = null
            liveKind = null
            effects2D = null
            texY = 0
            texU = 0
            texV = 0
            captureTex = 0
            yuvAllocated = false
            mainFbo?.delete()
            mainFbo = null
            offlineFbo?.delete()
            offlineFbo = null
            haveFrame = false
        }

        override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
            surfaceW = width
            surfaceH = height
        }

        override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
            drainCommands()

            val cfg = config.get()
            val blit = this.blit ?: return
            val quad = this.quad ?: return

            // ---- consume one live frame (if any) ----
            val frame = synchronized(frameLock) {
                val f = pendingFrame
                pendingFrame = null
                f
            }
            if (frame != null) {
                try {
                    uploadFrame(frame)
                    haveFrame = true
                } catch (t: Throwable) {
                    Log.e("GlPipe", "YUV upload failed", t)
                } finally {
                    frame.close()
                }
            }

            if (!haveFrame || streamW == 0 || streamH == 0) {
                clearScreen()
                return
            }
            val effects = liveProgram

            if (effects == null) {
                clearScreen()
                return
            }

            // ---- framing: FLIP_Y * R * C * M ----
            // Identical chain to the offline photo render (the texture
            // rows are top-down in both cases), which is what makes the
            // viewfinder literally the saved photo.
            val rot = ((cfg.sensorOrientation - cfg.displayRotation) % 360 + 360) % 360
            val (csx, csy) = Framing.cropScale(streamW, streamH, rot, cfg.outW, cfg.outH)
            var uvMat = Mat4.mul(Mat4.flipY(), Mat4.rotation(rot))
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
            bindLiveSource()
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
                effects.use()
                bindLiveSource()
                effects.setParams(cfg.params, ew, eh, nowSeconds(), stampColorArr, stampRectArr)
                effects.draw(quad, flipPos, uvMat)
                readPhoto(ew, eh)
            }
        }

        /** Bind the live source textures + overlay to their fixed units:
         *  0 = Y, 1 = U / interleaved chroma, 2 = V, 3 = overlay. */
        private fun bindLiveSource() {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texY)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texU)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (semiPlanar) 0 else texV)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTex)
        }

        /**
         * Upload one YUV_420_888 frame into the plane textures.
         *
         * Row strides are honored by allocating each texture rowStride
         * bytes wide (the padding becomes extra, never-sampled columns)
         * and scaling the sampling x coordinate by imageW/texW. The last
         * row may be shorter than the stride, so it is uploaded as a
         * separate one-row sub-image - both uploads read only bytes the
         * plane buffer is guaranteed to hold.
         */
        private fun uploadFrame(image: ImageProxy) {
            val planes = image.planes
            if (planes.size < 3) {
                Log.e("GlPipe", "YUV frame with ${planes.size} planes; dropped")
                return
            }
            val w = image.width
            val h = image.height
            val yRow = planes[0].rowStride
            val semi = planes[1].pixelStride == 2
            val uRow = planes[1].rowStride
            val vRow = planes[2].rowStride
            // For semi-planar memory, the chroma plane whose buffer starts
            // first owns the first byte of each texel.
            val newFirstIsU = planes[1].buffer.position() <= planes[2].buffer.position()

            val cw = (w + 1) / 2
            val ch = (h + 1) / 2
            val newTexW = yRow
            val newTexH = h
            val newCTexW = if (semi) uRow / 2 else uRow
            val newCTexH = ch
            if (newTexW < w || newCTexW < cw || newTexH < 1 || newCTexH < 1) {
                Log.e("GlPipe", "Bad YUV layout: $w x $h, yRow=$yRow, cRow=$uRow, semi=$semi")
                return
            }
            if (semi && uRow % 2 != 0) {
                // An odd interleaved row stride cannot be mapped onto 2-byte
                // texels; never observed in practice.
                Log.e("GlPipe", "Odd chroma row stride $uRow; frame dropped")
                return
            }

            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

            if (!yuvAllocated || yuvTexW != newTexW || yuvTexH != newTexH ||
                cTexW != newCTexW || cTexH != newCTexH || semiPlanar != semi
            ) {
                if (texY == 0) texY = GlUtils.genTexture(GLES20.GL_TEXTURE_2D)
                if (texU == 0) texU = GlUtils.genTexture(GLES20.GL_TEXTURE_2D)
                if (texV == 0) texV = GlUtils.genTexture(GLES20.GL_TEXTURE_2D)
                allocPlane(texY, GLES20.GL_LUMINANCE, newTexW, newTexH)
                if (semi) {
                    allocPlane(texU, GLES20.GL_LUMINANCE_ALPHA, newCTexW, newCTexH)
                } else {
                    allocPlane(texU, GLES20.GL_LUMINANCE, newCTexW, newCTexH)
                    allocPlane(texV, GLES20.GL_LUMINANCE, vRow, newCTexH)
                }
                yuvAllocated = true
                yuvTexW = newTexW
                yuvTexH = newTexH
                cTexW = newCTexW
                cTexH = newCTexH
                semiPlanar = semi
                Log.i(
                    "GlPipe",
                    "YUV layout: $w x $h, ${if (semi) "semi" else "planar"}, " +
                        "yRow=$yRow, cRow=$uRow, firstIsU=$newFirstIsU"
                )
            }

            // Compile (or recompile) the live program if the layout kind
            // changed. Rare: at most once per device.
            if (liveKind != (if (semi) SrcKind.YUV_SEMI else SrcKind.YUV_PLANAR)) {
                liveProgram?.let { GLES20.glDeleteProgram(it.program) }
                val kind = if (semi) SrcKind.YUV_SEMI else SrcKind.YUV_PLANAR
                liveProgram = EffectProgram(kind)
                liveKind = kind
            }
            val prog = liveProgram ?: return

            // ---- luma plane ----
            uploadPlane(planes[0].buffer, texY, GLES20.GL_LUMINANCE, yRow, h, w, 1)

            // ---- chroma plane(s) ----
            if (semi) {
                // 2 bytes per texel; GL rows are uRow bytes == one buffer row.
                uploadPlane(planes[1].buffer, texU, GLES20.GL_LUMINANCE_ALPHA, uRow, ch, cw * 2, 2)
            } else {
                uploadPlane(planes[1].buffer, texU, GLES20.GL_LUMINANCE, uRow, ch, cw, 1)
                uploadPlane(planes[2].buffer, texV, GLES20.GL_LUMINANCE, vRow, ch, cw, 1)
            }

            firstIsU = newFirstIsU
            prog.setFirstIsU(firstIsU)
            yuvScale[0] = w.toFloat() / yuvTexW
            yuvScale[1] = 1f
            yuvScale[2] = cw.toFloat() / cTexW
            yuvScale[3] = 1f
            prog.setYuvScale(yuvScale)

            if (streamW != w || streamH != h) {
                streamW = w
                streamH = h
                onStreamSize?.invoke(w, h)
            }
        }

        /** (Re)specify a plane texture at the given size. */
        private fun allocPlane(tex: Int, internalFormat: Int, w: Int, h: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, internalFormat, w, h, 0,
                internalFormat, GLES20.GL_UNSIGNED_BYTE, null as ByteBuffer?
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        /**
         * Upload one plane whose buffer rows are [rowBytes] bytes apart
         * into a texture allocated rowBytes bytes wide. [lastRowBytes] is
         * the number of valid bytes in the final row (usually < rowBytes).
         * [bytesPerTexel] converts row bytes to texture texels (1 for
         * luminance planes, 2 for the interleaved LUMINANCE_ALPHA plane).
         * All reads stay within the buffer's remaining bytes.
         */
        private fun uploadPlane(
            buffer: ByteBuffer,
            tex: Int,
            format: Int,
            rowBytes: Int,
            rows: Int,
            lastRowBytes: Int,
            bytesPerTexel: Int
        ) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            val base = buffer.position()
            val available = buffer.remaining()
            val widthMain = rowBytes / bytesPerTexel
            if (rows > 1) {
                val need = rowBytes.toLong() * (rows - 1)
                if (need > available) {
                    buffer.position(base)
                    return
                }
                buffer.position(base)
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, 0,
                    widthMain, rows - 1, format, GLES20.GL_UNSIGNED_BYTE, buffer
                )
            }
            val offset = rowBytes.toLong() * (rows - 1)
            if (offset + lastRowBytes <= available) {
                buffer.position(base + offset.toInt())
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0, 0, rows - 1,
                    lastRowBytes / bytesPerTexel, 1, format, GLES20.GL_UNSIGNED_BYTE, buffer
                )
            }
            buffer.position(base)
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
                var uvMat = Mat4.mul(Mat4.flipY(), Mat4.rotation(rot))
                uvMat = Mat4.mul(uvMat, Mat4.crop(csx, csy))
                if (cfg.mirror) uvMat = Mat4.mul(uvMat, Mat4.mirror())

                val fbo = ensureOfflineFbo(outW, outH)
                fbo.bind()
                GLES20.glViewport(0, 0, outW, outH)
                computeStampRect(cfg.params, stampBmpW, stampBmpH)

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
 * GLSurfaceView hosting the pipeline. ES 2.0 context: everything the
 * pipeline needs (planar luminance textures, FBOs, blits) is core ES 2.
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
        pipe.clearPending()
        if (pipe.view === this) pipe.view = null
    }
}
