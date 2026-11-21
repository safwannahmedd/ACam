package com.acam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
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
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.lifecycle.LifecycleOwner
import com.acam.data.CameraPrefs
import com.acam.data.FocusMode
import com.acam.gl.GlPipe
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/** One selectable physical lens. */
data class LensEntry(
    val cameraId: String,
    val label: String,
    val isFront: Boolean
)

/**
 * Owns the CameraX binding: one ImageAnalysis use case feeding YUV frames
 * to the GL pipeline, plus Camera2 interop for manual ISO / shutter /
 * focus / white balance / stabilization.
 *
 * The viewfinder consumes YUV_420_888 ImageAnalysis frames instead of a
 * PRIVATE Preview stream. Reason: on a class of budget camera HALs the
 * PRIVATE (opaque) stream delivered to a SurfaceTexture produces broken
 * frames - flat color fields, half-written buffers - while the two
 * mandated, universally reliable camera outputs are exactly YUV_420_888
 * (every scanner/CV app) and JPEG (used here for full-res stills).
 * Photos are captured through ImageCapture and rendered through the same
 * effects shader, so WYSIWYG is preserved by construction.
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
    }

    private val bgExecutor: Executor = Executors.newSingleThreadExecutor()
    private val analyzerExecutor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    var camera: Camera? = null
        private set
    var entries: List<LensEntry> = emptyList()
        private set
    var activeIndex: Int = 0
        private set
    var caps: Caps? = null
        private set

    /** True when the full-resolution ImageCapture use case is bound; the
     *  photo path uses it instead of rendering from the live stream. */
    @Volatile var imageCaptureReady: Boolean = false
        private set

    private var imageCapture: ImageCapture? = null

    /** Latest user preferences, supplied by the view model. */
    var prefsProvider: () -> CameraPrefs = { CameraPrefs() }

    var onEntriesChanged: ((List<LensEntry>, Int) -> Unit)? = null
    var onCapsChanged: ((Caps?) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Momentary torch used to emulate a flash while capturing a photo. */
    @Volatile private var captureFlash = false

    private var cameraProvider: androidx.camera.lifecycle.ProcessCameraProvider? = null

    fun start() {
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

                // Refine caps (stream size, crop math) whenever the GL
                // pipeline reports the live frame size it is receiving.
                pipe.onStreamSize = { w, h ->
                    mainHandler.post { refineCapsWithStream(w, h) }
                }
                bind()
            } catch (t: Throwable) {
                Log.e(TAG, "Camera init failed", t)
                onError?.invoke(t.message ?: "camera init")
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    fun selectLens(index: Int) {
        if (index < 0 || index >= entries.size || index == activeIndex) return
        activeIndex = index
        bind()
    }

    fun switchNext() {
        if (entries.isEmpty()) return
        activeIndex = (activeIndex + 1) % entries.size
        bind()
    }

    fun bind() {
        val provider = cameraProvider ?: return
        val entry = entries.getOrNull(activeIndex) ?: return
        try {
            val selector = CameraSelector.Builder()
                .addCameraFilter { list ->
                    list.filter {
                        runCatching {
                            Camera2CameraInfo.from(it).getCameraId() == entry.cameraId
                        }.getOrDefault(false)
                    }
                }
                .build()

            // Live viewfinder: YUV_420_888 frames via ImageAnalysis. This
            // is the camera output every HAL implements correctly at
            // preview rates (the same path every scanner / CV app uses);
            // the PRIVATE preview stream this device class breaks is not
            // used at all. A 4:3 1440x1080-class analysis stream keeps the
            // viewfinder field of view identical to the 4:3 JPEG stills.
            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1440, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(analysisSelector)
                .build()
            analysis.setAnalyzer(analyzerExecutor) { image ->
                pipe.submitFrame(image)
            }

            // Full-resolution stills: the JPEG/BLOB path is the one camera
            // output every HAL implements correctly at sensor resolution.
            val stillSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(4000, 3000),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()
            val stillCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(stillSelector)
                .build()

            provider.unbindAll()
            imageCapture = null
            imageCaptureReady = false
            try {
                camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis, stillCapture)
                imageCapture = stillCapture
                imageCaptureReady = true
            } catch (t: Throwable) {
                // Rare: a device that cannot combine the two use cases.
                // Photos then render from the live stream (bounded by its
                // resolution) instead of the full sensor size.
                Log.e(TAG, "ImageCapture bind failed; viewfinder-only fallback", t)
                camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis)
            }

            val camera = this.camera ?: return
            val characteristics = Camera2CameraInfo.extractCameraCharacteristics(camera.cameraInfo)

            // Provisional caps from characteristics; refined when the surface
            // request reports the real stream size.
            caps = Caps.from(characteristics, entry.cameraId, entry.label, caps?.streamW ?: 0, caps?.streamH ?: 0, null)
            onCapsChanged?.invoke(caps)

            applyPrefs(prefsProvider())
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed", t)
            onError?.invoke(t.message ?: "bind")
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
     * Full-resolution still capture through the ImageCapture use case.
     * Delivers the JPEG bytes, the rotation that makes them upright for the
     * current display orientation, and an optional crop rect (older devices
     * express zoom as a metadata crop instead of cropping pixels).
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
        if (stabilizationWanted && caps.videoStab) {
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
        }

        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(builder.build())

        camera.cameraControl.setZoomRatio(prefs.zoom.coerceIn(1f, caps.maxZoom))
        camera.cameraControl.enableTorch(captureFlash || torchOn)
    }

    @Volatile var torchOn: Boolean = false
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

    fun startCaptureFlash(on: Boolean) {
        captureFlash = on
        camera?.cameraControl?.enableTorch(on)
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
