package com.acam.gl

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Records the GL pipeline into a video file.
 *
 * Video: the renderer blits the effects FBO into a MediaCodec input EGL
 * surface. Because the renderer only submits a frame when the custom-fps
 * clock is due (and stamps exact presentation timestamps), a chosen fps
 * below the sensor rate produces genuinely choppy old-film playback.
 *
 * Audio: AudioRecord -> AAC MediaCodec, muxed with MediaMuxer.
 *
 * Pattern adapted from Google's grafika (Apache-2.0), reimplemented in a
 * minimal form for this pipeline.
 */
class FrameRecorder(
    val cfg: RecConfig,
    private val egl: Triple<EGLDisplay, EGLContext, EGLConfig?>,
    private val context: Context
) {

    companion object {
        private const val TAG = "FrameRecorder"
        private const val SAMPLE_RATE = 44100
        private const val VIDEO_TIMEOUT_US = 10_000L
        private const val AUDIO_TIMEOUT_US = 10_000L
    }

    @Volatile var running = false
        internal set
    @Volatile var paused = false
    var frameCount = 0
        internal set

    // EGL objects (GL thread)
    private var eglDisplay: EGLDisplay = egl.first
    private var eglContext: EGLContext = egl.second
    private var eglSurface: EGLSurface? = null
    private var codecInputSurface: Surface? = null

    // Video codec
    private var videoCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var target: MediaSaver.VideoTarget? = null

    // Audio
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    private var audioAbsented = false

    private val bufferInfo = MediaCodec.BufferInfo()

    fun start() {
        val mime = if (cfg.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, cfg.encoderW, cfg.encoderH).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = codec.createInputSurface()
        codec.start()
        videoCodec = codec
        codecInputSurface = inputSurface

        // EGL window surface on the shared context for the codec input.
        eglSurface = createEglSurface(inputSurface)

        // Output target + muxer
        target = MediaSaver.createVideo(context)
        val fd: FileDescriptor = target!!.fd
        muxer = if (android.os.Build.VERSION.SDK_INT >= 26) {
            MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        if (cfg.audio) {
            startAudioOrGiveUp()
        }

        running = true
    }

    private fun createEglSurface(surface: Surface): EGLSurface {
        val dpy = eglDisplay
        val attribs = intArrayOf(EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
        // Preferred: the same config the context was created with.
        egl.third?.let { cfg ->
            val s = EGL14.eglCreateWindowSurface(dpy, cfg, surface, attribs, 0)
            if (s != null && s !== EGL14.EGL_NO_SURFACE) return s
        }
        // Fallback: choose a fresh RGBA8888 + recordable config.
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, configs, 0, 1, num, 0) || num[0] < 1) {
            throw IllegalStateException("No EGL config for encoder surface")
        }
        val s = EGL14.eglCreateWindowSurface(dpy, configs[0]!!, surface, attribs, 0)
        if (s == null || s === EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreateWindowSurface failed for encoder")
        }
        return s
    }

    /**
     * Blit the FBO texture into the codec surface and submit with an exact
     * timestamp. Must be called from the GL thread (needs the shared context).
     * Restores the previously current draw/read surfaces before returning.
     */
    fun blitAndSubmit(blit: BlitProgram, quad: FloatBuffer, fboTex: Int, ptsUs: Long) {
        val surface = eglSurface ?: throw IllegalStateException("not started")
        val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent(codec) failed")
        }
        try {
            android.opengl.GLES20.glViewport(0, 0, cfg.encoderW, cfg.encoderH)
            blit.use()
            blit.draw(quad, fboTex, identityMat)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, surface, ptsUs * 1000)
            if (!EGL14.eglSwapBuffers(eglDisplay, surface)) {
                throw IllegalStateException("eglSwapBuffers(codec) failed")
            }
            drainVideo(false)
        } finally {
            EGL14.eglMakeCurrent(eglDisplay, prevDraw, prevRead, eglContext)
        }
    }

    private val identityMat = com.acam.gl.Mat4.identity()

    private fun drainVideo(endOfStream: Boolean) {
        val codec = videoCodec ?: return
        while (true) {
            val idx = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 100_000L else VIDEO_TIMEOUT_US)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
                continue
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(codec.outputFormat, video = true)
            } else if (idx >= 0) {
                val encoded = codec.getOutputBuffer(idx)
                if (encoded != null && bufferInfo.size > 0 &&
                    bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                ) {
                    writeSample(videoTrack, codec, idx, encoded)
                } else {
                    codec.releaseOutputBuffer(idx, false)
                }
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    break
                }
            }
        }
    }

    @Synchronized
    private fun addTrack(format: MediaFormat, video: Boolean) {
        val m = muxer ?: return
        if (video && videoTrack < 0) {
            videoTrack = m.addTrack(format)
        } else if (!video && audioTrack < 0) {
            audioTrack = m.addTrack(format)
        }
        val needAudio = cfg.audio && !audioAbsented
        if (!muxerStarted && videoTrack >= 0 && (!needAudio || audioTrack >= 0)) {
            m.start()
            muxerStarted = true
        }
    }

    @Synchronized
    private fun writeSample(track: Int, codec: MediaCodec, idx: Int, buffer: ByteBuffer) {
        if (track < 0 || !muxerStarted) return
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        muxer?.writeSampleData(track, buffer, bufferInfo)
        codec.releaseOutputBuffer(idx, false)
    }

    // ---------------- Audio ----------------

    private fun startAudioOrGiveUp() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            @SuppressLint("MissingPermission") // checked by the view model before starting
            val record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 8192)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord init failed")

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            aCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            aCodec.start()

            audioRecord = record
            audioCodec = aCodec
            audioRunning = true
            audioThread = Thread({ audioLoop() }, "acam-audio").apply { start() }
        } catch (t: Throwable) {
            Log.w(TAG, "Audio unavailable, recording video only", t)
            audioAbsented = true
        }
    }

    private fun audioLoop() {
        val record = audioRecord ?: return
        val codec = audioCodec ?: return
        val chunk = 2048
        val shorts = ShortArray(chunk)
        val gain = cfg.micGain
        var totalSamples = 0L
        record.startRecording()
        try {
            while (audioRunning) {
                if (paused) {
                    // Pause: stop consuming; pts continues from sample count,
                    // so resume is gap-free.
                    record.stop()
                    while (paused && audioRunning) Thread.sleep(20)
                    if (!audioRunning) break
                    record.startRecording()
                    continue
                }
                val n = record.read(shorts, 0, chunk)
                if (n <= 0) continue
                if (gain != 1f) {
                    for (i in 0 until n) {
                        var v = shorts[i] * gain
                        if (v > 32767f) v = 32767f
                        if (v < -32768f) v = -32768f
                        shorts[i] = v.toInt().toShort()
                    }
                }
                val ptsUs = totalSamples * 1_000_000L / SAMPLE_RATE
                var offset = 0
                while (offset < n && audioRunning) {
                    val inIdx = codec.dequeueInputBuffer(AUDIO_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)!!
                        ib.clear()
                        val remaining = n - offset
                        val bytes = minOf(remaining, ib.remaining() / 2)
                        for (i in 0 until bytes) {
                            ib.putShort(shorts[offset + i])
                        }
                        codec.queueInputBuffer(inIdx, 0, bytes * 2, ptsUs + offset * 1_000_000L / SAMPLE_RATE, 0)
                        offset += bytes
                    }
                }
                totalSamples += n
                drainAudio(false)
            }
            // EOS
            val inIdx = codec.dequeueInputBuffer(100_000L)
            if (inIdx >= 0) {
                codec.queueInputBuffer(inIdx, 0, 0, totalSamples * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainAudio(true)
        } catch (t: Throwable) {
            Log.w(TAG, "Audio loop ended", t)
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private fun drainAudio(endOfStream: Boolean) {
        val codec = audioCodec ?: return
        while (true) {
            val idx = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 100_000L else AUDIO_TIMEOUT_US)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
                continue
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(codec.outputFormat, video = false)
            } else if (idx >= 0) {
                val encoded = codec.getOutputBuffer(idx)
                if (encoded != null && bufferInfo.size > 0 &&
                    bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                ) {
                    writeSample(audioTrack, codec, idx, encoded)
                } else {
                    codec.releaseOutputBuffer(idx, false)
                }
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    // ---------------- Stop ----------------

    fun stop() {
        // Audio first (its EOS is quick).
        audioRunning = false
        audioThread?.join(2000)
        audioCodec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        audioCodec = null

        // Video EOS.
        videoCodec?.let { c ->
            runCatching { c.signalEndOfInputStream() }
            drainVideo(true)
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        videoCodec = null

        if (muxerStarted) {
            runCatching { muxer?.stop() }
        }
        runCatching { muxer?.release() }
        muxer = null

        target?.close()
        target?.let { t ->
            if (t.path != null) {
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(t.path), arrayOf("video/mp4"), null
                )
            }
        }
        target = null
        running = false
    }

    /** Best-effort cleanup after a failure; never throws. */
    fun releaseQuietly() {
        runCatching {
            audioRunning = false
            audioThread?.join(1000)
            audioCodec?.let { c ->
                runCatching { c.stop() }; runCatching { c.release() }
            }
            videoCodec?.let { c ->
                runCatching { c.stop() }; runCatching { c.release() }
            }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            target?.close()
        }
        audioCodec = null
        videoCodec = null
        muxer = null
        target = null
        running = false
    }
}
