package com.acam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.hardware.camera2.params.RggbChannelVector
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.acam.data.CameraPrefs
import com.acam.data.FocusMode
import com.acam.gl.GlPipe
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs

/** One selectable physical lens. */
data class LensEntry(
    val cameraId: String,
    val label: String,
    val isFront: Boolean
)

/**
 * Owns the CameraX binding. The viewfinder is the CameraX Preview use
 * case rendering straight into the pipeline's SurfaceTexture (consumed as
 * a GL external texture - the one continuous camera path every HAL is
 * factory-tuned for); full-resolution stills go through ImageCapture (the
 * other mandated, universally reliable output).
 *
 * THE 1.2.2 HANDSHAKE FIX: the 1.2.1 rewrite introduced a startup race -
 * the pipe callbacks were installed only inside the ProcessCameraProvider
 * future, so on devices where CameraX initializes slower than the GL
 * surface, the GL thread's "SurfaceTexture ready" push fired into a null
 * listener. The controller then never bound a Preview at all: the
 * viewfinder stayed on the GLSurfaceView's uninitialized window memory
 * (a flat, session-dependent gray/green field) while photos kept working
 * and no error was ever raised. The callbacks now install in the
 * controller's init (before the GL view can exist) and maybeBind() also
 * pulls the SurfaceTexture from the pipe's latch, so no ordering of the
 * two async inits can strand the session.
 *
 * THE 1.2.1 VIEWFINDER FIX: the viewfinder used to consume YUV_420_888
 * ImageAnalysis frames. On a class of budget HALs (UNISOC-class SoCs)
 * the analysis stream itself delivers degenerate data - flat fields,
 * half-written torn buffers - which showed up as the "extremely zoomed /
 * striped" viewfinder while photos taken a second later were perfect.
 * Preview buffers, composited by the HAL exactly like the stock camera
 * app's viewfinder, do not have that failure mode; they also carry no
 * CPU-side format/stride handling at all.
 *
 * SESSION LAYOUT (the compatibility fix for budget HALs):
 * A large class of ultra-budget camera HALs (common on UNISOC-class SoCs)
 * produce garbage in EVERY stream of a session that carries two output
 * streams at once (e.g. viewfinder + still capture simultaneously): flat
 * color fields, half-written buffers, striped frames. Single-stream
 * sessions are the one configuration every HAL must support (it is what
 * the legacy Camera1 API uses). So in compatibility mode the controller
 * binds exactly ONE use case at any time:
 *   - LIVE:  the Preview stream only (viewfinder + video),
 *   - STILL: the ImageCapture stream only; the viewfinder briefly freezes
 *     while the photo is taken, then LIVE is rebound.
 * On known-good devices the classic simultaneous binding remains available
 * via the "compatible capture" setting (off = classic).
 *
 * Binding patterns adapted from android/camera-samples (Apache-2.0).
 *
 * NOTE: face detection is never enabled; STATISTICS_FACE_DETECT_MODE is
 * explicitly forced OFF in every capture request (hard product rule).
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val pipe: GlPipe
) {

    companion object {
        private const val TAG = "CameraController"

        /** Settle time after rebinding the still-only session before the
         *  shot (and between retries): camera open + session + AE. */
        private const val STILL_SETTLE_MS = 600L

        /** Time without a single analysis frame before the CPU fallback
         *  declares the stream suspect (the HAL delivered nothing at all). */
        private const val CPU_NO_FRAMES_MS = 5000L
    }

    /** What the camera session is currently bound for. CPU is the
     *  compatibility viewfinder: CPU-converted ImageAnalysis frames pushed
     *  to the GL pipeline as plain 2D textures (see onFallbackFrame). SYS
     *  is the system viewfinder of last resort: CameraX Preview rendered
     *  by a PreviewView (the compositor path the stock camera app uses) -
     *  no live effects, but a picture on every HAL ever shipped. */
    enum class SessionMode { LIVE, STILL, CPU, SYS }

    /** Preview (viewfinder) resolutions to try, most desirable first. The
     *  ladder auto-falls back when the GL-side probe reports degenerate
     *  frames at a size the HAL cannot actually drive. Order matters: the
     *  4:3 default first (FOV-matched with the still capture, and the size
     *  every earlier version ran), then the sizes budget HALs are actually
     *  validated at (the stock app's viewfinder size and the camera1
     *  universal), and only then the aggressive 1600x1200 - the size budget
     *  HALs are least likely to have ever been tested at. The last rung the
     *  probe declared healthy is persisted and becomes the starting rung. */
    private val previewLadder = listOf(
        Size(1280, 960),    // 4:3 - the long-standing default, FOV-matched
        Size(1280, 720),    // 16:9 - the stock camera app's viewfinder size,
                            // the one budget HALs are factory-tested at
        Size(640, 480),     // VGA - the camera1 universal
        Size(1600, 1200)    // 4:3 hi-res - tried last: highest quality, but
                            // the least-validated stream size on budget HALs
    )
    private var previewIdx = 0

    private val bgExecutor: Executor = Executors.newSingleThreadExecutor()
    private val analyzerExecutor: Executor = Executors.newSingleThreadExecutor()
    private val diagExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    var camera: Camera? = null
        private set
    var entries: List<LensEntry> = emptyList()
        private set
    var activeIndex: Int = 0
        private set
    var caps: Caps? = null
        private set

    /** True when a still-capture use case is currently bound. */
    @Volatile var imageCaptureReady: Boolean = false
        private set

    /** True while a compatibility-mode still capture is between the unbind
     *  and the rebind; lens switches and re-binds must wait for it. */
    @Volatile var captureInFlight: Boolean = false
        private set

    /** True while the diagnostics runner owns the camera session. */
    @Volatile var diagnosticsRunning: Boolean = false
        private set

    private var imageCapture: ImageCapture? = null

    /** Latest user preferences, supplied by the view model. */
    var prefsProvider: () -> CameraPrefs = { CameraPrefs() }

    /** Single-stream ("compatible") capture mode; default ON. Supplied by
     *  the view model so it persists with the rest of the settings. */
    var compatProvider: () -> Boolean = { true }

    /** Video stabilization preference, supplied by the view model so it
     *  survives process death together with the rest of the settings. */
    var stabilizationProvider: () -> Boolean = { false }

    /** Current display rotation (0/90/180/270), supplied by the UI. */
    var displayRotationProvider: () -> Int = { 0 }

    /** Last preview size the stream probe declared healthy, persisted by
     *  the view model (null = none yet). The ladder starts there. */
    var savedPreviewProvider: () -> Size? = { null }

    /** Persisted "compatibility viewfinder" preference: engage the CPU
     *  frame path from the start instead of re-running the whole (slow)
     *  degenerate-ladder every launch. Cleared only by Reset all settings. */
    var cpuPrefProvider: () -> Boolean = { false }

    /** Persisted "system viewfinder" preference: skip straight to the
     *  PreviewView-based viewfinder (the guaranteed-working floor). */
    var systemVfPrefProvider: () -> Boolean = { false }

    /** The viewfinder switched to (or back from) the system (PreviewView)
     *  viewfinder of last resort. Drives the on-screen chip. */
    var onVfSystem: ((Boolean) -> Unit)? = null

    /** The PreviewView hosting the system viewfinder, attached by the UI
     *  when it composes it (SYS mode only). */
    @Volatile private var systemPreviewView: PreviewView? = null

    /** True while the viewfinder runs on the system PreviewView - the
     *  fallback of last resort when every GL shape failed. */
    @Volatile private var systemVf = false

    /** The probe declared the current ladder rung healthy - the view model
     *  persists it as the next launch's starting rung. */
    var onPreviewRungHealthy: ((w: Int, h: Int) -> Unit)? = null

    /** The viewfinder switched to (or back from) the CPU-frame
     *  compatibility mode. Drives the small on-screen chip. */
    var onVfCompat: ((Boolean) -> Unit)? = null

    /** The pipeline's SurfaceTexture for the live viewfinder, created on
     *  the GL thread and reported through [GlPipe.onSurfaceTexture]. */
    @Volatile private var glSurfaceTexture: SurfaceTexture? = null

    /** The producer Surface currently handed to CameraX (one at a time:
     *  a SurfaceTexture accepts a single connected producer). */
    private var previewSurface: Surface? = null
    private val surfaceLock = Any()

    var onEntriesChanged: ((List<LensEntry>, Int) -> Unit)? = null
    var onCapsChanged: ((Caps?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Live analysis stream was declared degenerate at every ladder rung
     *  (true) / recovered (false). Drives the camera-screen banner. */
    var onStreamSuspect: ((Boolean) -> Unit)? = null

    /** The viewfinder automatically switched to a fallback resolution. */
    var onAnalysisFallback: ((Size) -> Unit)? = null

    @Volatile private var streamSuspect = false

    /** True once the viewfinder runs on CPU-converted frames (the GL-preview
     *  fallback of last resort). Sticky for the session. */
    @Volatile private var cpuFallback = false

    // CPU-fallback verification state (analyzer thread only).
    private var fallbackSeen = 0
    private var fallbackBadRun = 0

    /** Momentary torch used to emulate a flash while capturing a photo. */
    @Volatile private var captureFlash = false

    private var cameraProvider: androidx.camera.lifecycle.ProcessCameraProvider? = null

    init {
        // THE 1.2.2 HANDSHAKE FIX: every pipe callback is installed HERE, at
        // construction - before MainActivity's setContent can have created
        // the GL view, and therefore before the GL thread can possibly fire
        // onSurfaceTexture. In 1.2.1 these listeners were installed inside
        // the ProcessCameraProvider future callback; on devices where that
        // future resolves slower than GL surface creation (budget SoCs),
        // createLiveSource fired into a still-null listener, the controller
        // never learned the SurfaceTexture existed, no Preview was ever
        // bound, and the viewfinder showed the GLSurfaceView's uninitialized
        // window memory - the "flat gray/green viewfinder, photos fine"
        // report. maybeBind() additionally PULLS the latched SurfaceTexture
        // from the pipe, so even a missed push cannot strand the controller.
        pipe.onStreamSize = { w, h ->
            mainHandler.post { refineCapsWithStream(w, h) }
        }
        pipe.onSurfaceTexture = { st ->
            mainHandler.post { onGlSurfaceTexture(st) }
        }
        pipe.onStreamDegenerate = { reason ->
            mainHandler.post { onProbeDegenerate(reason) }
        }
        pipe.onStreamHealthy = {
            mainHandler.post { onProbeHealthy() }
        }
        pipe.previewSizeProvider = { previewLadder[previewIdx] }
    }

    fun start() {
        // Start the ladder at the last rung the probe declared healthy, and
        // engage the persisted compatibility viewfinder without re-running
        // the (slow) degenerate ladder from scratch.
        val saved = savedPreviewProvider()
        if (saved != null) {
            val idx = previewLadder.indexOfFirst {
                it.width == saved.width && it.height == saved.height
            }
            if (idx >= 0) previewIdx = idx
        }
        if (systemVfPrefProvider()) {
            // The whole GL ladder (including the CPU rung) was declared
            // degenerate in a previous session: start straight at the
            // system viewfinder instead of making the user watch the
            // (slow) descent again.
            systemVf = true
            onVfSystem?.invoke(true)
        } else if (cpuPrefProvider()) {
            cpuFallback = true
            pipe.cpuFallback = true
            onVfCompat?.invoke(true)
        }
        val future = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val infos = provider.availableCameraInfos
                val backFocals = infos.mapNotNull { info ->
                    runCatching {
                        val ch = Camera2CameraInfo.extractCameraCharacteristics(info)
                        if (ch.get(CameraCharacteristics.LENS_FACING) == CameraSelector.LENS_FACING_BACK) {
                            ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                        } else null
                    }.getOrNull()
                }
                val mainBackFocal = backFocals.minOrNull()

                entries = infos.mapNotNull { info ->
                    runCatching {
                        val ch = Camera2CameraInfo.extractCameraCharacteristics(info)
                        val facing = ch.get(CameraCharacteristics.LENS_FACING)
                            ?: CameraSelector.LENS_FACING_BACK
                        val focal = ch
                            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                        val label = if (facing == CameraSelector.LENS_FACING_FRONT) {
                            "Front"
                        } else if (mainBackFocal != null && mainBackFocal > 0f) {
                            when {
                                abs(focal - mainBackFocal) / mainBackFocal < 0.15f -> "Back"
                                focal < mainBackFocal -> "Ultra-wide"
                                else -> "Telephoto"
                            }
                        } else "Back"
                        LensEntry(Camera2CameraInfo.from(info).cameraId, label, facing == CameraSelector.LENS_FACING_FRONT)
                    }.getOrNull()
                }
                val firstBack = entries.indexOfFirst { !it.isFront }
                activeIndex = if (firstBack >= 0) firstBack else 0
                onEntriesChanged?.invoke(entries, activeIndex)
                maybeBind()
            } catch (t: Throwable) {
                Log.e(TAG, "Camera init failed", t)
                onError?.invoke(t.message ?: "camera init")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    /** Bind LIVE once both async prerequisites exist: the camera provider
     *  and the GL pipeline's SurfaceTexture. Whichever arrives last binds.
     *  The SurfaceTexture is taken from the push callback OR pulled from
     *  the pipe's latch, whichever is available first. */
    private fun maybeBind() {
        if (cameraProvider == null) return
        if (glSurfaceTexture == null) {
            val latched = pipe.currentSurfaceTexture()
            if (latched != null) {
                Log.i(TAG, "Live SurfaceTexture recovered from latch")
                glSurfaceTexture = latched
            }
        }
        if (glSurfaceTexture == null) {
            Log.w(TAG, "maybeBind: no GL SurfaceTexture yet; waiting for push")
            return
        }
        if (camera == null && !diagnosticsRunning && !captureInFlight) bind()
    }

    /** A (new) SurfaceTexture exists - usually once, again only after a GL
     *  context rebuild. Store it; if a session is already running it still
     *  feeds the RELEASED previous texture, so rebind onto the new one. */
    private fun onGlSurfaceTexture(st: SurfaceTexture) {
        if (glSurfaceTexture === st) {
            // Already known (push after the latch pull, or a re-notification):
            // nothing new, but the provider may only now be ready to bind.
            maybeBind()
            return
        }
        val had = glSurfaceTexture != null
        glSurfaceTexture = st
        if (had && camera != null && !captureInFlight && !diagnosticsRunning) {
            bind()
        } else {
            maybeBind()
        }
    }

    fun selectLens(index: Int) {
        if (index < 0 || index >= entries.size || index == activeIndex) return
        activeIndex = index
        // A lens switch during an in-flight single-stream capture must not
        // unbind the still session; the capture completion rebinds LIVE for
        // the newly selected lens.
        if (captureInFlight || diagnosticsRunning) return
        bind()
    }

    fun switchNext() {
        if (entries.isEmpty()) return
        selectLens((activeIndex + 1) % entries.size)
    }

    /** Build the camera selector that pins the active lens. */
    private fun activeSelector(entry: LensEntry): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { list ->
                list.filter {
                    runCatching {
                        Camera2CameraInfo.from(it).getCameraId() == entry.cameraId
                    }.getOrDefault(false)
                }
            }
            .build()

    /**
     * Build the Preview use case and connect it to the pipeline's
     * SurfaceTexture. The resolution selector pins the ladder rung's ratio
     * (4:3 rungs match the 4:3 still capture so the viewfinder field of
     * view matches the saved photo; the compatibility rungs are 16:9 / VGA,
     * which the effects pipeline center-crops to the chosen output ratio).
     *
     * The SurfaceRequest resolution is authoritative: the default buffer
     * size, the framing math and the tap-to-focus mapping all use it.
     */
    private fun buildPreview(previewSize: Size): Preview {
        // The aspect strategy must AGREE with the rung's ratio, or the two
        // strategies fight and CameraX resolves to an arbitrary size.
        val aspect = if (previewSize.width * 9 == previewSize.height * 16)
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val previewSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspect)
            .setResolutionStrategy(
                ResolutionStrategy(
                    previewSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()
        val preview = Preview.Builder()
            .setResolutionSelector(previewSelector)
            .build()
        preview.setSurfaceProvider(analyzerExecutor) { request ->
            attachPreviewSurface(request)
        }
        return preview
    }

    /** Hand the pipeline's SurfaceTexture to a Preview SurfaceRequest.
     *  Runs on [analyzerExecutor]; safe to force-release a lingering
     *  previous producer first because a new request implies the old one
     *  was cancelled by the rebind. */
    private fun attachPreviewSurface(request: SurfaceRequest) {
        val st = glSurfaceTexture
        if (st == null) {
            // Cannot happen (bind is gated on the SurfaceTexture); leaving
            // the request unanswered only stalls it until the next rebind.
            Log.e(TAG, "SurfaceRequest without a SurfaceTexture")
            return
        }
        val res = request.resolution
        runCatching { st.setDefaultBufferSize(res.width, res.height) }
        pipe.setStreamSize(res.width, res.height)
        // A new SurfaceRequest means a fresh stream is being set up: restart
        // the probe's frozen-stream grace window regardless of size change.
        pipe.noteStreamReset()
        val surface = Surface(st)
        synchronized(surfaceLock) {
            previewSurface?.release()
            previewSurface = surface
        }
        request.provideSurface(surface, analyzerExecutor) {
            synchronized(surfaceLock) {
                if (previewSurface === surface) previewSurface = null
            }
            surface.release()
        }
    }

    private fun buildStill(): ImageCapture {
        val stillSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(4000, 3000),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(stillSelector)
            .build()
    }

    /**
     * (Re)bind the camera session.
     *
     * LIVE  - viewfinder: the Preview stream alone in compatibility mode,
     *         or Preview + still capture together classically (the
     *         standard CameraX pairing on healthy HALs).
     * STILL - photo taking: the still capture stream alone.
     * CPU   - compatibility viewfinder: a single ImageAnalysis stream whose
     *         frames are CPU-verified, CPU-converted and pushed to the GL
     *         pipeline as plain 2D textures. Engaged automatically when
     *         every GL-preview rung was declared degenerate; the one shape
     *         that keeps a working viewfinder even on HALs whose preview
     *         streams are broken, because it is the same texture class the
     *         (always working) photo render uses.
     *
     * A LIVE request is transparently routed to CPU once the fallback is
     * engaged - every caller (post-capture rebind, lens switch, rotation)
     * keeps working unchanged.
     */
    fun bind(mode: SessionMode = SessionMode.LIVE, previewSize: Size = previewLadder[previewIdx]) {
        val provider = cameraProvider ?: return
        if (diagnosticsRunning) return
        val effMode = when {
            mode == SessionMode.LIVE && systemVf -> SessionMode.SYS
            mode == SessionMode.LIVE && cpuFallback -> SessionMode.CPU
            else -> mode
        }
        // LIVE needs the pipeline's SurfaceTexture; with the init-time
        // listeners and the latch this cannot normally happen anymore, so
        // log it loudly if it ever does.
        if (effMode == SessionMode.LIVE && glSurfaceTexture == null) {
            Log.e(TAG, "bind(LIVE) without a GL SurfaceTexture - skipped")
            return
        }
        if (effMode == SessionMode.SYS && systemPreviewView == null) {
            // The UI has not composed the PreviewView yet; it will call
            // attachSystemPreviewView, which rebinds. Nothing to do now.
            Log.i(TAG, "bind(SYS) without a PreviewView yet; waiting for the UI")
            return
        }
        val entry = entries.getOrNull(activeIndex) ?: return
        try {
            val selector = activeSelector(entry)
            val compat = compatProvider()

            provider.unbindAll()
            imageCapture = null
            imageCaptureReady = false

            when (effMode) {
                SessionMode.LIVE -> {
                    val preview = buildPreview(previewSize)
                    preview.targetRotation = when (displayRotationProvider()) {
                        90 -> Surface.ROTATION_90
                        180 -> Surface.ROTATION_180
                        270 -> Surface.ROTATION_270
                        else -> Surface.ROTATION_0
                    }
                    if (compat) {
                        // Exactly one stream: the Preview surface, exactly
                        // like the stock camera app's viewfinder. No second
                        // stream that a budget HAL could trip over.
                        camera = provider.bindToLifecycle(lifecycleOwner, selector, preview)
                    } else {
                        val stillCapture = buildStill()
                        try {
                            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, stillCapture)
                            imageCapture = stillCapture
                            imageCaptureReady = true
                        } catch (t: Throwable) {
                            Log.e(TAG, "ImageCapture bind failed; viewfinder-only fallback", t)
                            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview)
                        }
                    }
                }
                SessionMode.STILL -> {
                    val stillCapture = buildStill()
                    camera = provider.bindToLifecycle(lifecycleOwner, selector, stillCapture)
                    imageCapture = stillCapture
                    imageCaptureReady = true
                }
                SessionMode.SYS -> {
                    val pv = systemPreviewView
                    if (pv != null) {
                        val preview = buildPreview(previewSize)
                        preview.targetRotation = when (displayRotationProvider()) {
                            90 -> Surface.ROTATION_90
                            180 -> Surface.ROTATION_180
                            270 -> Surface.ROTATION_270
                            else -> Surface.ROTATION_0
                        }
                        // The stock-camera rendering path: the HAL composes
                        // straight into the PreviewView's surface. No app GL,
                        // no external-texture sampling - the one viewfinder
                        // shape that works on every HAL ever shipped.
                        preview.setSurfaceProvider(pv.surfaceProvider)
                        camera = provider.bindToLifecycle(lifecycleOwner, selector, preview)
                    }
                }
                SessionMode.CPU -> {
                    fallbackSeen = 0
                    fallbackBadRun = 0
                    val analysis = buildFallbackAnalysis()
                    analysis.setAnalyzer(analyzerExecutor, ::onFallbackFrame)
                    camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis)
                    // If the HAL cannot deliver even analysis frames, fall
                    // through to the system viewfinder instead of sitting on
                    // a black viewfinder forever.
                    mainHandler.postDelayed({
                        if (cpuFallback && !systemVf && fallbackSeen == 0) {
                            engageSystemVf("no analysis frames at all")
                        }
                    }, CPU_NO_FRAMES_MS)
                }
            }

            val camera = this.camera ?: return
            val characteristics = Camera2CameraInfo.extractCameraCharacteristics(camera.cameraInfo)

            // Provisional caps from characteristics; refined when the GL
            // pipeline reports the real stream size.
            caps = Caps.from(characteristics, entry.cameraId, entry.label, caps?.streamW ?: 0, caps?.streamH ?: 0, null)
            onCapsChanged?.invoke(caps)

            applyPrefs(prefsProvider())
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed", t)
            onError?.invoke(t.message ?: "bind")
        }
    }

    /**
     * Take a photo, orchestrating the session for maximum HAL compatibility.
     *
     * In compatibility mode (default) this is a full single-stream cycle:
     * unbind LIVE -> bind STILL (only the ImageCapture stream, the one
     * session shape every HAL supports) -> wait for the camera to be open
     * and settle -> shoot -> restore LIVE. The viewfinder freezes for the
     * duration (a fraction of a second), like a real camera's shutter.
     *
     * Outside compatibility mode the still use case is already bound
     * alongside the viewfinder and the picture is taken directly.
     */
    fun captureStill(
        displayRotation: Int,
        onResult: (jpeg: ByteArray, rotationDeg: Int, cropRect: Rect?) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!compatProvider()) {
            if (imageCapture == null) {
                onError("ImageCapture unavailable")
            } else {
                takePicture(displayRotation, onResult, onError)
            }
            return
        }
        mainHandler.post {
            if (captureInFlight || diagnosticsRunning) {
                onError("camera busy")
                return@post
            }
            captureInFlight = true
            try {
                bind(SessionMode.STILL)
                if (imageCapture == null) throw IllegalStateException("still bind failed")

                // Give the freshly rebound camera time to open the device
                // and build the capture session before shooting; shooting
                // into a half-configured session fails on many HALs. One
                // retry covers the occasional slow open.
                mainHandler.postDelayed({
                    try {
                        shootWithRetry(displayRotation, 0, onResult, onError)
                    } catch (t: Throwable) {
                        finishCompatCapture()
                        onError(t.message ?: "capture")
                    }
                }, STILL_SETTLE_MS)
            } catch (t: Throwable) {
                captureInFlight = false
                bind(SessionMode.LIVE)
                onError(t.message ?: "capture")
            }
        }
    }

    /** Take the picture, retrying once (the first attempt can race the
     *  capture session still being configured after the rebind). */
    private fun shootWithRetry(
        displayRotation: Int,
        attempt: Int,
        onResult: (jpeg: ByteArray, rotationDeg: Int, cropRect: Rect?) -> Unit,
        onError: (String) -> Unit
    ) {
        takePicture(
            displayRotation,
            onResult = { jpeg, rot, crop ->
                finishCompatCapture()
                onResult(jpeg, rot, crop)
            },
            onError = { msg ->
                if (attempt == 0) {
                    mainHandler.postDelayed({
                        if (captureInFlight) {
                            shootWithRetry(displayRotation, 1, onResult, onError)
                        }
                    }, STILL_SETTLE_MS)
                } else {
                    finishCompatCapture()
                    onError(msg)
                }
            }
        )
    }

    /** Restore the LIVE viewfinder session after a single-stream capture. */
    private fun finishCompatCapture() {
        mainHandler.post {
            captureInFlight = false
            if (!diagnosticsRunning) bind(SessionMode.LIVE)
        }
    }

    /**
     * The GL-side probe (raw preview pixels, before any effects) concluded
     * the live stream is degenerate: flat non-black fields, stale frames
     * (the exact same buffer re-queued), or a frozen stream (no frames at
     * all). Fall back down the preview resolution ladder; if every rung
     * fails, tell the UI the stream is suspect.
     *
     * Verdicts during a compat capture or the diagnostics suite are ignored:
     * the viewfinder freezes BY DESIGN while the still-only session runs.
     */
    private fun onProbeDegenerate(reason: String) {
        if (captureInFlight || diagnosticsRunning) return
        if (systemVf) return
        if (previewIdx < previewLadder.lastIndex) {
            previewIdx++
            val size = previewLadder[previewIdx]
            Log.i(TAG, "Falling back to preview size $size ($reason)")
            pipe.resizePreview(size.width, size.height)
            bind(SessionMode.LIVE, size)
            onAnalysisFallback?.invoke(size)
        } else if (!cpuFallback) {
            // Every GL-preview rung is degenerate. Switch the viewfinder to
            // the CPU-frame path: if the HAL can produce ANY sane frames at
            // the analysis size, the user gets a working (if softer)
            // viewfinder built from the texture class the photo render
            // already proves correct. The engagement is persisted so the
            // next launch skips the slow ladder entirely.
            Log.e(TAG, "GL preview degenerate at every size ($reason) - switching to the CPU viewfinder")
            cpuFallback = true
            pipe.cpuFallback = true
            if (streamSuspect) {
                streamSuspect = false
                onStreamSuspect?.invoke(false)
            }
            onVfCompat?.invoke(true)
            bind(SessionMode.CPU)
        }
        // If the CPU rung is already engaged and still degenerate, the
        // CPU path's own verification (onFallbackFrame) escalates to the
        // system viewfinder - there is nothing left to try here.
    }

    /** The probe sees healthy frames again (after a fallback / suspect). */
    private fun onProbeHealthy() {
        // Remember the working rung so the next launch starts on it instead
        // of re-falling through the ladder in front of the user.
        if (!cpuFallback && !systemVf) {
            val rung = previewLadder[previewIdx]
            onPreviewRungHealthy?.invoke(rung.width, rung.height)
        }
        if (streamSuspect) {
            streamSuspect = false
            onStreamSuspect?.invoke(false)
        }
    }

    /**
     * Engage the system viewfinder (PreviewView): the last rung of the
     * ladder, reached only when every GL shape - the OES preview at every
     * resolution AND the CPU-converted analysis frames - was declared
     * degenerate. Photos and their effects are untouched (they render from
     * the ImageCapture JPEG through the proven TEX2D path); the LIVE
     * viewfinder loses the effect preview, which is why this is the last
     * resort and not the default. Runs on the main thread.
     */
    private fun engageSystemVf(reason: String) {
        if (systemVf) return
        // May be reached from the analyzer thread (onFallbackFrame); the
        // session rebind below is main-thread-only.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { engageSystemVf(reason) }
            return
        }
        if (systemVf) return
        Log.e(TAG, "Viewfinder degenerate on every GL path ($reason) - switching to the system viewfinder")
        systemVf = true
        // The GL pipeline no longer consumes a camera stream; make sure the
        // CPU-frame path is off so a later manual "system viewfinder: off"
        // retries the full GL ladder from the top.
        cpuFallback = false
        pipe.cpuFallback = false
        if (streamSuspect) {
            streamSuspect = false
            onStreamSuspect?.invoke(false)
        }
        onVfCompat?.invoke(false)
        onVfSystem?.invoke(true)
        bind(SessionMode.LIVE)
    }

    /**
     * The UI composed the PreviewView for the system viewfinder (or is
     * replacing a detached one). Bind the camera onto it if SYS mode is
     * active. Called from the AndroidView factory on the main thread.
     */
    fun attachSystemPreviewView(pv: PreviewView) {
        systemPreviewView = pv
        if (systemVf && cameraProvider != null && !diagnosticsRunning && !captureInFlight) {
            bind(SessionMode.LIVE)
        }
    }

    /** The UI removed the PreviewView (system viewfinder turned off). */
    fun detachSystemPreviewView(pv: PreviewView) {
        if (systemPreviewView === pv) systemPreviewView = null
    }

    /** Manual switch (Settings): on = system viewfinder, off = retry the
     * full GL ladder from the top rung. */
    fun setSystemViewfinder(on: Boolean) {
        if (on == systemVf) return
        systemVf = on
        if (on) {
            cpuFallback = false
            pipe.cpuFallback = false
            onVfCompat?.invoke(false)
            onVfSystem?.invoke(true)
        } else {
            onVfSystem?.invoke(false)
            // Retry everything: reset the ladder to the remembered rung.
            val saved = savedPreviewProvider()
            if (saved != null) {
                val idx = previewLadder.indexOfFirst {
                    it.width == saved.width && it.height == saved.height
                }
                if (idx >= 0) previewIdx = idx
            }
        }
        if (cameraProvider != null) bind(SessionMode.LIVE)
    }

    /** Tap-to-focus in system-viewfinder mode: view coordinates straight
     *  into the PreviewView's own metering point factory (it knows the
     *  exact stream transform it renders with). */
    fun focusAtView(viewX: Float, viewY: Float) {
        val camera = camera ?: return
        val pv = systemPreviewView ?: return
        if (!pv.isAttachedToWindow) return
        val point = pv.meteringPointFactory.createPoint(viewX, viewY)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    /** The ImageAnalysis use case behind the CPU compatibility viewfinder:
     * one small 4:3 stream at the camera1-universal size. */
    private fun buildFallbackAnalysis(): ImageAnalysis {
        val res = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
            .build()
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(res)
            .build()
    }

    /**
     * One CPU-frame fallback frame. Runs on [analyzerExecutor].
     *
     * Every frame is verified on the CPU first - this is the ONLY place in
     * the app where what the HAL actually delivered is measured directly,
     * with no GPU in between: flat fields are counted, and only frames with
     * real luma spread are converted and displayed. If even this stream
     * cannot produce sane frames, the stream-suspect banner carries the
     * measured numbers instead of a guess.
     */
    private fun onFallbackFrame(image: ImageProxy) {
        try {
            val w = image.width
            val h = image.height
            if (w <= 0 || h <= 0 || image.planes.size < 3) return
            fallbackSeen++

            val yPlane = image.planes[0]
            val yBuf = yPlane.buffer
            val yOff = yBuf.position()
            val yRow = yPlane.rowStride
            val stepX = (w / 32).coerceAtLeast(1)
            val stepY = (h / 24).coerceAtLeast(1)
            var mn = 255
            var mx = 0
            var sum = 0L
            var n = 0L
            // Sampled grid statistics: also per-row means/spreads, so torn
            // (horizontally smeared) buffers can be told apart from real
            // scenes the same way the GL probe does it.
            val rows = 24
            val cols = 32
            val rowSum = LongArray(rows)
            val rowSq = LongArray(rows)
            val limit = yBuf.limit()
            for (yy in 0 until h step stepY) {
                val base = yOff + yy * yRow
                val rIdx = (yy * rows / h).coerceIn(0, rows - 1)
                for (xx in 0 until w step stepX) {
                    val idx = base + xx
                    if (idx < 0 || idx >= limit) continue
                    val v = yBuf.get(idx).toInt() and 0xFF
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                    sum += v
                    rowSum[rIdx] += v
                    rowSq[rIdx] += v.toLong() * v
                    n++
                }
            }
            if (n == 0L) return
            val mean = sum / n
            var withinVar = 0.0
            var minRow = Double.MAX_VALUE
            var maxRow = -Double.MAX_VALUE
            for (r in 0 until rows) {
                val c = rowSum[r].toDouble() / cols
                val rv = (rowSq[r].toDouble() / cols - c * c).coerceAtLeast(0.0)
                withinVar += rv
                if (rowSum[r] > 0) {
                    if (c < minRow) minRow = c
                    if (c > maxRow) maxRow = c
                }
            }
            withinVar /= rows
            val rowSpread = if (maxRow >= minRow) maxRow - minRow else 0.0
            val torn = withinVar <= 4.0 && rowSpread > 24.0

            if ((mx - mn <= 2 && mean > 24) || torn) {
                // Flat or torn: the same degenerate data the GL probe
                // rejected. Do not display it; count it, and once it is
                // clear this is chronic, escalate to the system viewfinder.
                fallbackBadRun++
                if (fallbackBadRun >= 8 && fallbackSeen >= 12 && !systemVf) {
                    engageSystemVf(
                        if (torn) "CPU frames torn (var=%.1f, spread=%.0f)"
                            .format(withinVar, rowSpread) else "CPU frames flat (mean=$mean)"
                    )
                }
                return
            }

            fallbackBadRun = 0
            if (streamSuspect) {
                streamSuspect = false
                onStreamSuspect?.invoke(false)
            }

            val bmp = YuvToBitmap.convert(image)
            pipe.setStreamSize(w, h)
            pipe.pushCpuFrame(bmp)
        } catch (t: Throwable) {
            Log.e(TAG, "fallback frame failed", t)
        } finally {
            image.close()
        }
    }

    /** Rebuild caps when the live stream reports its actual frame size
     *  (called from the GL thread via the pipe, hence the main-thread hop).
     *  The stream size drives the viewfinder framing math and the
     *  tap-to-focus coordinate mapping. */
    private fun refineCapsWithStream(w: Int, h: Int) {
        val camera = camera ?: return
        val entry = entries.getOrNull(activeIndex) ?: return
        val current = caps
        if (current != null && current.streamW == w && current.streamH == h) return
        val characteristics = runCatching {
            Camera2CameraInfo.extractCameraCharacteristics(camera.cameraInfo)
        }.getOrNull() ?: return
        val newCaps = Caps.from(characteristics, entry.cameraId, entry.label, w, h, null)
        if (newCaps != caps) {
            caps = newCaps
            onCapsChanged?.invoke(newCaps)
        }
    }

    /**
     * Full-resolution still capture through the bound ImageCapture use
     * case. Delivers the JPEG bytes, the rotation that makes them upright
     * for the current display orientation, and an optional crop rect
     * (older devices express zoom as a metadata crop instead of cropping
     * pixels).
     */
    fun takePicture(
        displayRotation: Int,
        onResult: (jpeg: ByteArray, rotationDeg: Int, cropRect: Rect?) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = imageCapture
        if (capture == null) {
            onError("ImageCapture unavailable")
            return
        }
        capture.targetRotation = when (displayRotation) {
            90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
        capture.takePicture(bgExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val plane = image.planes.firstOrNull()
                    if (plane == null || plane.buffer.remaining() <= 0) {
                        image.close()
                        onError("empty capture")
                        return
                    }
                    val buf = plane.buffer
                    val jpeg = ByteArray(buf.remaining())
                    buf.get(jpeg)
                    val rot = image.imageInfo.rotationDegrees
                    val crop = image.cropRect
                    val useCrop = if (
                        crop != null && crop.width() > 0 && crop.height() > 0 &&
                        (crop.width() < image.width || crop.height() < image.height)
                    ) crop else null
                    image.close()
                    onResult(jpeg, rot, useCrop)
                } catch (t: Throwable) {
                    runCatching { image.close() }
                    onError(t.message ?: "capture")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception.message ?: "capture")
            }
        })
    }

    /**
     * Run the on-device camera diagnostics suite (see Diagnostics.kt) and
     * restore the LIVE session afterwards. Everything is saved to
     * Pictures/ACam-Diagnostics for sharing.
     */
    fun runDiagnostics(
        onProgress: (String) -> Unit,
        onDone: (summary: String) -> Unit
    ) {
        val provider = cameraProvider ?: run {
            onDone("Camera not initialized")
            return
        }
        val entry = entries.getOrNull(activeIndex) ?: run {
            onDone("No camera")
            return
        }
        if (diagnosticsRunning) return
        diagnosticsRunning = true
        provider.unbindAll()
        imageCapture = null
        imageCaptureReady = false

        val runner = Diagnostics(
            context, lifecycleOwner, provider, activeSelector(entry),
            entry, diagExecutor, mainHandler
        )
        runner.run(
            onProgress = onProgress,
            onDone = { summary ->
                mainHandler.post {
                    diagnosticsRunning = false
                    streamSuspect = false
                    bind(SessionMode.LIVE)
                    onDone(summary)
                }
            }
        )
    }

    /** Apply manual controls through Camera2 interop. */
    @SuppressLint("UnsafeOptInUsageError")
    fun applyPrefs(prefs: CameraPrefs) {
        val camera = camera ?: return
        val caps = caps ?: return
        val builder = CaptureRequestOptions.Builder()

        // Hard product rule: no face detection, ever.
        builder.setCaptureRequestOption(
            CaptureRequest.STATISTICS_FACE_DETECT_MODE,
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
        )

        // ---- Exposure: AE compensation or full manual ISO/shutter ----
        if (prefs.manualIso && caps.isoMin != null && caps.isoMax != null &&
            caps.exposureMinNs != null && caps.exposureMaxNs != null
        ) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF
            )
            builder.setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                prefs.iso.coerceIn(caps.isoMin!!, caps.isoMax!!)
            )
            builder.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                prefs.shutterNs.coerceIn(caps.exposureMinNs!!, caps.exposureMaxNs!!)
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON
            )
            camera.cameraControl.setExposureCompensationIndex(
                prefs.evIndex.coerceIn(caps.evMin, caps.evMax)
            )
        }

        // ---- White balance: auto or Kelvin -> gains ----
        if (prefs.wbKelvin > 0 && caps.manualWb) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF
            )
            builder.setCaptureRequestOption<RggbChannelVector>(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                kelvinToGains(prefs.wbKelvin)
            )
        } else {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO
            )
        }

        // ---- Focus ----
        when (prefs.focusMode) {
            FocusMode.MANUAL -> {
                if (caps.minFocusDistance > 0f) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF
                    )
                    builder.setCaptureRequestOption(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        prefs.focusDistance.coerceIn(0f, 1f) * caps.minFocusDistance
                    )
                } else {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                }
            }
            FocusMode.AUTO -> builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO
            )
            FocusMode.CONTINUOUS -> builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }

        // ---- Video stabilization ----
        val stab = stabilizationProvider()
        if (stab && caps.videoStab) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        }

        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(builder.build())

        camera.cameraControl.setZoomRatio(prefs.zoom.coerceIn(1f, caps.maxZoom))
        camera.cameraControl.enableTorch(captureFlash || prefs.flashMode == com.acam.data.FlashMode.TORCH)
    }

    @Volatile var stabilizationWanted: Boolean = false

    /** Tap-to-focus at stream pixels (sensor-oriented). */
    fun focusAt(streamX: Float, streamY: Float) {
        val camera = camera ?: return
        val sw = pipe.streamW
        val sh = pipe.streamH
        if (sw <= 0 || sh <= 0) return
        val factory = SurfaceOrientedMeteringPointFactory(sw.toFloat(), sh.toFloat())
        val point = factory.createPoint(streamX, streamY)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).build()
        camera.cameraControl.startFocusAndMetering(action)
    }

    /** Momentary capture flash. Never turns the LED off while the torch
     *  mode is active, and always restores the torch state afterwards. */
    fun startCaptureFlash(on: Boolean) {
        captureFlash = on
        val torchActive = prefsProvider().flashMode == com.acam.data.FlashMode.TORCH
        camera?.cameraControl?.enableTorch(on || torchActive)
    }

    /**
     * Kelvin -> COLOR_CORRECTION_GAINS using the standard
     * Tanner Helland approximation. Gains are reciprocals of the target RGB.
     */
    private fun kelvinToGains(kelvin: Int): RggbChannelVector {
        val t = kelvin.coerceIn(1500, 12000) / 100f
        val r: Float = if (t <= 66f) 255f else 329.698727446f * Math.pow((t - 60).toDouble(), -0.1332047592).toFloat()
        val g: Float = if (t <= 66f) (99.4708025861 * kotlin.math.ln(t.toDouble()) - 161.1195681661).toFloat()
        else 288.1221695283f * Math.pow((t - 60).toDouble(), -0.0755148492).toFloat()
        val b: Float = if (t >= 66f) 255f else if (t <= 19f) 0f
        else (138.5177312231 * kotlin.math.ln((t - 10).toDouble()) - 305.0447927307).toFloat()

        fun c(v: Float) = (v.coerceIn(0f, 255f) / 255f).coerceAtLeast(0.01f)
        val rr = c(r); val gg = c(g); val bb = c(b)
        return RggbChannelVector(1f / rr, 1f, 1f, 1f / bb)
    }
}
