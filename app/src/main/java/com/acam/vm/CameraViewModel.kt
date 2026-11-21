package com.acam.vm

import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.acam.camera.CameraController
import com.acam.camera.Caps
import com.acam.camera.LensEntry
import com.acam.data.*
import com.acam.gl.DateStamper
import com.acam.gl.GlCommand
import com.acam.gl.GlPipe
import com.acam.gl.PhotoSaver
import com.acam.gl.PipeConfig
import com.acam.gl.RecConfig
import com.acam.gl.RecordEvent
import com.acam.util.Framing
import com.acam.util.RatioMath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Single source of truth for app state (MVVM). Every mutation flows through
 * here, updates the live viewfinder immediately (no Apply button anywhere)
 * and auto-persists to DataStore after a short debounce.
 */
class CameraViewModel(app: Application) : AndroidViewModel(app) {

    data class AppState(
        val loaded: Boolean = false,
        val presets: List<Preset> = BuiltInPresets.ALL,
        val activePresetId: String = "normal",
        val params: EffectParams = EffectParams(),
        val dateStamp: DateStampConfig = DateStampConfig(),
        val resolution: ResolutionSpec = ResolutionSpec(),
        val video: VideoSettings = VideoSettings(),
        val cameraPrefs: CameraPrefs = CameraPrefs(),
        val ui: UiPrefs = UiPrefs(),

        // Runtime (not persisted)
        val caps: Caps? = null,
        val lensEntries: List<LensEntry> = emptyList(),
        val activeLens: Int = 0,
        val displayRotation: Int = 0,
        val photoMode: Boolean = true,
        val adjustOpen: Boolean = false,
        val recording: Boolean = false,
        val recordingPaused: Boolean = false,
        val recordingStartMs: Long = 0L,
        val countdown: Int? = null,
        val flashOverlay: Boolean = false,
        val lastSaved: Uri? = null,
        val toast: String? = null
    ) {
        val activePreset: Preset?
            get() = presets.firstOrNull { it.id == activePresetId }
    }

    private val repo = SettingsRepository(app)
    val pipe = GlPipe(app)

    var controller: CameraController? = null

    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()

    // ------------------------------------------------------------------ init

    init {
        viewModelScope.launch {
            val persisted = migrate(repo.state.first())
            if (!_state.value.loaded) {
                _state.value = _state.value.copy(
                    loaded = true,
                    presets = BuiltInPresets.ALL + persisted.customPresets,
                    activePresetId = persisted.activePresetId,
                    params = persisted.params,
                    dateStamp = persisted.dateStamp,
                    resolution = persisted.resolution,
                    video = persisted.video,
                    cameraPrefs = persisted.camera,
                    ui = persisted.ui
                )
                pushPipeConfig()
                pushStampIfActive()
            }
        }

        // Auto-persist: there is deliberately no Save button.
        viewModelScope.launch {
            _state.filter { it.loaded }
                .debounce(600)
                .collect { s -> runCatching { repo.save(snapshot(s)) } }
        }

        // Refresh the date stamp text periodically while it is visible.
        viewModelScope.launch {
            while (true) {
                if (_state.value.params.dateStamp) pushStampIfActive()
                delay(20_000)
            }
        }

        pipe.onPhoto = { w, h, rgba ->
            val s = _state.value
            PhotoSaver.save(
                getApplication(), w, h, rgba,
                png = s.ui.photoFormat == PhotoFormat.PNG,
                jpegQuality = s.ui.jpegQuality
            ) { uri ->
                _state.update {
                    it.copy(
                        lastSaved = uri ?: it.lastSaved,
                        flashOverlay = false,
                        toast = if (uri != null) "Photo saved" else "Save failed"
                    )
                }
            }
        }

        pipe.onRecordEvent = { ev ->
            when (ev) {
                is RecordEvent.Started -> _state.update {
                    it.copy(recording = true, recordingPaused = false, recordingStartMs = System.currentTimeMillis())
                }
                is RecordEvent.Stopped -> _state.update {
                    it.copy(recording = false, recordingPaused = false, toast = "Video saved")
                }
                is RecordEvent.Paused -> _state.update { it.copy(recordingPaused = true) }
                is RecordEvent.Resumed -> _state.update { it.copy(recordingPaused = false) }
                is RecordEvent.Failed -> _state.update {
                    it.copy(recording = false, recordingPaused = false, toast = "Recording failed: ${ev.message}")
                }
            }
        }
    }

    private fun snapshot(s: AppState) = PersistedState(
        customPresets = s.presets.filter { !it.builtin },
        activePresetId = s.activePresetId,
        params = s.params,
        dateStamp = s.dateStamp,
        resolution = s.resolution,
        video = s.video,
        camera = s.cameraPrefs,
        ui = s.ui
    )

    /**
     * One-time migrations (guarded by the persisted schema version).
     *
     * v1: the 1.0.0 default resolution was a literal landscape 4:3, which
     * shows as a strip in a portrait viewfinder. Installs that never touched
     * the resolution (ratio 4:3, k = 0, locked — impossible to produce by
     * user interaction, since any ratio tap or slider move writes an
     * explicit k) move to the new portrait 3:4 default. Anything the user
     * actually chose is left exactly as stored.
     */
    private fun migrate(p: PersistedState): PersistedState {
        var s = p
        if (s.version < 1) {
            val untouchedOldDefault = !s.resolution.unlocked &&
                s.resolution.k <= 0 &&
                s.resolution.ratio == Ratio.FOUR_THREE
            if (untouchedOldDefault) {
                s = s.copy(resolution = ResolutionSpec(ratio = Ratio.THREE_FOUR))
            }
            s = s.copy(version = 1)
        }
        return s
    }

    // ------------------------------------------------------- pipe plumbing

    /** Max output box (upright frame) for the current rotation. Derived
     *  from the full-resolution still-capture capability when available:
     *  the live stream is capped lower (HAL reliability) but photos render
     *  from the ImageCapture JPEG, so the picker may offer the full size. */
    fun uprightMax(s: AppState = _state.value): Size2 {
        val caps = s.caps ?: return Size2(1920, 1440)
        val rot = ((caps.sensorOrientation - s.displayRotation) % 360 + 360) % 360
        val w = if (caps.captureW > 0) caps.captureW else caps.streamW
        val h = if (caps.captureH > 0) caps.captureH else caps.streamH
        return if (rot == 90 || rot == 270) {
            Size2(h, w)
        } else {
            Size2(w, h)
        }
    }

    fun resolvedSize(s: AppState = _state.value): Size2 {
        val max = uprightMax(s)
        return if (s.resolution.unlocked) {
            RatioMath.clampFree(s.resolution.unlockedW, s.resolution.unlockedH, max.w, max.h)
        } else {
            // k <= 0 means "device maximum"; sizeForEffective resolves it.
            // (Regression fix: this used to go through sizeForClamped, which
            // read k=0 as k=1 and rendered the preview into a 4x3 pixel
            // frame on a fresh install.)
            RatioMath.sizeForEffective(s.resolution.ratio, s.resolution.k, max.w, max.h)
        }
    }

    fun kMax(s: AppState = _state.value): Int {
        val max = uprightMax(s)
        return RatioMath.maxK(s.resolution.ratio, max.w, max.h)
    }

    fun pushPipeConfig() {
        val s = _state.value
        val size = resolvedSize(s)
        val mirror = (s.caps?.isFront == true) && s.cameraPrefs.mirrorFront
        pipe.config.set(
            PipeConfig(
                params = s.params,
                outW = size.w,
                outH = size.h,
                mirror = mirror,
                displayRotation = s.displayRotation,
                sensorOrientation = s.caps?.sensorOrientation ?: 90
            )
        )
    }

    private fun pushStampIfActive() {
        val s = _state.value
        val bmp = if (s.params.dateStamp) DateStamper.render(s.dateStamp) else null
        pipe.post(GlCommand.UpdateStamp(bmp))
    }

    private fun stampNeedsRefresh(old: EffectParams, new: EffectParams, cfg: DateStampConfig) {
        if (old.dateStamp != new.dateStamp) pushStampIfActive()
    }

    // ---------------------------------------------------------- mutations

    fun updateParams(transform: (EffectParams) -> EffectParams) {
        _state.update {
            val old = it.params
            val new = transform(old)
            if (new != old) stampNeedsRefresh(old, new, it.dateStamp)
            it.copy(params = new)
        }
        pushPipeConfig()
    }

    fun updateDateStamp(transform: (DateStampConfig) -> DateStampConfig) {
        _state.update { it.copy(dateStamp = transform(it.dateStamp)) }
        pushStampIfActive()
    }

    fun updateUi(transform: (UiPrefs) -> UiPrefs) {
        _state.update { it.copy(ui = transform(it.ui)) }
    }

    fun updateVideo(transform: (VideoSettings) -> VideoSettings) {
        _state.update { it.copy(video = transform(it.video)) }
    }

    fun updateCameraPrefs(transform: (CameraPrefs) -> CameraPrefs) {
        _state.update { it.copy(cameraPrefs = transform(it.cameraPrefs)) }
        controller?.applyPrefs(_state.value.cameraPrefs)
        pushPipeConfig()
    }

    fun setPhotoMode(photo: Boolean) {
        if (_state.value.recording) return
        _state.update { it.copy(photoMode = photo) }
    }

    fun setAdjustOpen(open: Boolean) {
        _state.update { it.copy(adjustOpen = open) }
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun setDisplayRotation(deg: Int) {
        if (_state.value.displayRotation == deg) return
        _state.update { it.copy(displayRotation = deg) }
        pushPipeConfig()
    }

    fun onCaps(caps: Caps?) {
        _state.update { it.copy(caps = caps) }
        pushPipeConfig()
    }

    fun onLensEntries(entries: List<LensEntry>, active: Int) {
        _state.update { it.copy(lensEntries = entries, activeLens = active) }
    }

    fun selectLens(index: Int) {
        controller?.selectLens(index)
        _state.update { it.copy(activeLens = index) }
    }

    fun switchCamera() {
        val next = controller?.let { (it.activeIndex + 1) % it.entries.size } ?: return
        selectLens(next)
    }

    // ----------------------------------------------------------- presets

    fun effectivePresetK(preset: Preset, s: AppState = _state.value): Int {
        val max = uprightMax(s)
        return RatioMath.effectiveK(preset.ratio, preset.k, max.w, max.h)
    }

    fun isPresetModified(s: AppState = _state.value): Boolean {
        val preset = s.activePreset ?: return false
        if (s.params != preset.params) return true
        if (s.resolution.unlocked) return true
        if (s.resolution.ratio != preset.ratio) return true
        // Compare EFFECTIVE k values: k <= 0 means "device maximum", so a
        // stored 0 must equal the preset's own "max" instead of showing a
        // spurious modified-dot on a fresh install.
        val max = uprightMax(s)
        return RatioMath.effectiveK(s.resolution.ratio, s.resolution.k, max.w, max.h) !=
            RatioMath.effectiveK(preset.ratio, preset.k, max.w, max.h)
    }

    fun selectPreset(id: String) {
        val s = _state.value
        val preset = s.presets.firstOrNull { it.id == id } ?: return
        val max = uprightMax(s)
        val k = RatioMath.effectiveK(preset.ratio, preset.k, max.w, max.h)
        _state.update {
            it.copy(
                activePresetId = id,
                params = preset.params,
                resolution = ResolutionSpec(ratio = preset.ratio, k = k, unlocked = false)
            )
        }
        preset.videoFps?.let { fps -> updateVideo { v -> v.copy(fps = fps) } }
        pushPipeConfig()
        pushStampIfActive()
    }

    fun resetPreset() {
        selectPreset(_state.value.activePresetId)
    }

    fun saveAsNewPreset(name: String) {
        val s = _state.value
        val trimmed = name.trim().ifEmpty { "Preset ${s.presets.size - BuiltInPresets.ALL.size + 1}" }
        val preset = Preset(
            id = "custom_${UUID.randomUUID()}",
            name = trimmed,
            builtin = false,
            params = s.params,
            ratio = s.resolution.ratio,
            k = if (s.resolution.unlocked) 0 else s.resolution.k,
            videoFps = null
        )
        _state.update {
            it.copy(
                presets = it.presets + preset,
                activePresetId = preset.id,
                toast = "Preset saved"
            )
        }
    }

    fun renamePreset(id: String, name: String) {
        val trimmed = name.trim().ifEmpty { return }
        _state.update { s ->
            s.copy(presets = s.presets.map { if (it.id == id && !it.builtin) it.copy(name = trimmed) else it })
        }
    }

    fun duplicatePreset(id: String) {
        val s = _state.value
        val src = s.presets.firstOrNull { it.id == id } ?: return
        val copy = src.copy(
            id = "custom_${UUID.randomUUID()}",
            name = "${src.name} 2",
            builtin = false
        )
        _state.update { it.copy(presets = it.presets + copy) }
    }

    fun deletePreset(id: String) {
        val s = _state.value
        val preset = s.presets.firstOrNull { it.id == id } ?: return
        if (preset.builtin) return
        _state.update {
            val remaining = it.presets.filterNot { p -> p.id == id }
            val active = if (it.activePresetId == id) "normal" else it.activePresetId
            it.copy(presets = remaining, activePresetId = active)
        }
        if (_state.value.activePresetId == "normal") selectPreset("normal")
    }

    fun exportActivePreset(): String {
        val s = _state.value
        val preset = s.activePreset ?: return ""
        val exportable = if (preset.builtin) preset else preset
        return repo.exportPreset(exportable)
    }

    fun importPreset(json: String) {
        val imported = repo.importPreset(json) ?: run {
            _state.update { it.copy(toast = "Invalid preset JSON") }
            return
        }
        _state.update { it.copy(presets = it.presets + imported, toast = "Preset imported") }
        selectPreset(imported.id)
    }

    fun resetAll() {
        viewModelScope.launch {
            repo.resetAll()
            _state.value = AppState(loaded = true)
            pushPipeConfig()
            pushStampIfActive()
            controller?.applyPrefs(CameraPrefs())
        }
    }

    // --------------------------------------------------------- resolution

    fun setRatio(r: Ratio) {
        _state.update { s ->
            val current = resolvedSize(s)
            val max = uprightMax(s)
            val next = RatioMath.switchRatio(current, r, max.w, max.h)
            val k = RatioMath.kClamped(r, next.h / r.b, max.w, max.h)
            s.copy(resolution = ResolutionSpec(ratio = r, k = k, unlocked = false))
        }
        pushPipeConfig()
    }

    fun setK(k: Int) {
        _state.update { s ->
            if (s.resolution.unlocked) {
                s.copy(resolution = s.resolution.copy(unlocked = false, k = k))
            } else {
                s.copy(resolution = s.resolution.copy(k = k))
            }
        }
        pushPipeConfig()
    }

    fun setSliderT(t: Float) = setK(RatioMath.sliderToK(t, kMax()))

    fun quickPick(targetW: Int, targetH: Int) {
        val k = RatioMath.kForTarget(_state.value.resolution.ratio, targetW, targetH, uprightMax().w, uprightMax().h)
        setK(k)
    }

    /** Custom W x H input. In locked mode the other dimension auto-fills
     *  from the ratio; both must stay integer multiples of the ratio. */
    fun setCustomW(w: Int) {
        val s = _state.value
        if (w <= 0) return
        if (s.resolution.unlocked) {
            _state.update { it.copy(resolution = it.resolution.copy(unlockedW = w)) }
        } else {
            val r = s.resolution.ratio
            setK(ratioKForW(w, r))
        }
        pushPipeConfig()
    }

    fun setCustomH(h: Int) {
        val s = _state.value
        if (h <= 0) return
        if (s.resolution.unlocked) {
            _state.update { it.copy(resolution = it.resolution.copy(unlockedH = h)) }
        } else {
            val r = s.resolution.ratio
            setK(ratioKForH(h, r))
        }
        pushPipeConfig()
    }

    private fun ratioKForW(w: Int, r: Ratio): Int {
        val max = uprightMax()
        return RatioMath.kClamped(r, (w.toFloat() / r.a).toInt().coerceAtLeast(1), max.w, max.h)
    }

    private fun ratioKForH(h: Int, r: Ratio): Int {
        val max = uprightMax()
        return RatioMath.kClamped(r, (h.toFloat() / r.b).toInt().coerceAtLeast(1), max.w, max.h)
    }

    fun setUnlocked(unlocked: Boolean) {
        _state.update { s ->
            if (unlocked) {
                val size = resolvedSize(s)
                s.copy(resolution = s.resolution.copy(unlocked = true, unlockedW = size.w, unlockedH = size.h))
            } else {
                // Re-lock to the nearest exact multiple of the current ratio.
                val r = s.resolution.ratio
                val size = resolvedSize(s)
                val max = uprightMax(s)
                val k = RatioMath.kClamped(
                    r,
                    (((size.w.toFloat() / r.a) + (size.h.toFloat() / r.b)) / 2f).toInt().coerceAtLeast(1),
                    max.w, max.h
                )
                s.copy(resolution = ResolutionSpec(ratio = r, k = k, unlocked = false))
            }
        }
        pushPipeConfig()
    }

    fun setCustomRatio(a: Int, b: Int) {
        if (a <= 0 || b <= 0 || a > 200 || b > 200) return
        setRatio(RatioMath.reduce(a, b))
    }

    // ------------------------------------------------------------ capture

    fun onShutter() {
        val s = _state.value
        if (s.recording) {
            stopRecording()
            return
        }
        if (s.ui.shutterSound) playShutterTone()
        if (s.photoMode) capturePhoto() else startRecording()
    }

    private fun playShutterTone() {
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_SYSTEM, 60)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 90)
            android.os.Handler(getApplication<Application>().mainLooper).postDelayed({
                runCatching { tone.release() }
            }, 250)
        }
    }

    fun capturePhoto() {
        val s = _state.value
        if (s.countdown != null) return
        val timer = s.cameraPrefs.timer
        if (timer > 0) {
            viewModelScope.launch {
                for (t in timer downTo 1) {
                    _state.update { it.copy(countdown = t) }
                    delay(1000)
                }
                _state.update { it.copy(countdown = null) }
                doCapture()
            }
        } else {
            doCapture()
        }
    }

    private fun doCapture() {
        val s = _state.value
        val flash = s.cameraPrefs.flashMode != com.acam.data.FlashMode.OFF
        viewModelScope.launch {
            if (flash) controller?.startCaptureFlash(true)
            if (flash) delay(450)
            _state.update { it.copy(flashOverlay = true) }
            val controller = controller
            if (controller != null && controller.imageCaptureReady) {
                controller.takePicture(
                    displayRotation = s.displayRotation,
                    onResult = { jpeg, rot, crop ->
                        decodeOfflineCapture(jpeg, rot, crop)
                    },
                    onError = { fallbackStreamCapture() }
                )
            } else {
                fallbackStreamCapture()
            }
            if (flash) {
                delay(500)
                controller?.startCaptureFlash(false)
            }
        }
    }

    /** Fallback: render the photo from the live preview stream (bounded
     *  by the stream resolution). Used when ImageCapture is unavailable. */
    private fun fallbackStreamCapture() {
        pipe.post(GlCommand.CapturePhoto)
    }

    /**
     * Decode the full-resolution ImageCapture JPEG and hand it to the GL
     * pipeline, which renders it through the exact same effects shader as
     * the viewfinder (WYSIWYG) at the exact target resolution. Zoom on
     * older devices is a metadata crop, applied here before the upload.
     */
    private fun decodeOfflineCapture(
        jpeg: ByteArray,
        rotationDeg: Int,
        crop: android.graphics.Rect?
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val bitmap = runCatching {
                var bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bmp != null && crop != null && crop.width() > 0 && crop.height() > 0 &&
                    (crop.width() < bmp.width || crop.height() < bmp.height)
                ) {
                    val cl = crop.left.coerceIn(0, bmp.width - 1)
                    val ct = crop.top.coerceIn(0, bmp.height - 1)
                    val cw = crop.width().coerceAtMost(bmp.width - cl)
                    val ch = crop.height().coerceAtMost(bmp.height - ct)
                    if (cw > 0 && ch > 0) {
                        bmp = android.graphics.Bitmap.createBitmap(bmp, cl, ct, cw, ch)
                    }
                }
                bmp
            }.getOrNull()
            if (bitmap != null) {
                pipe.post(GlCommand.OfflineCapture(bitmap, rotationDeg))
            } else {
                fallbackStreamCapture()
            }
        }
    }

    // ------------------------------------------------------------ video

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun encoderClampFor(w: Int, h: Int): Pair<Int, Int> {
        val mime = if (_state.value.video.useHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        var minW = 16; var minH = 16; var maxW = 4096; var maxH = 4096
        runCatching {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.contains(mime)) continue
                val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: continue
                minW = maxOf(minW, caps.supportedWidths.lower)
                maxW = minOf(maxW, caps.supportedWidths.upper)
                minH = maxOf(minH, caps.supportedHeights.lower)
                maxH = minOf(maxH, caps.supportedHeights.upper)
                break
            }
        }
        val clamped = RatioMath.encoderClamp(w, h, minW, minH, maxW, maxH)
        return clamped.w to clamped.h
    }

    fun startRecording() {
        val s = _state.value
        if (s.recording) return
        val size = resolvedSize(s)
        val (encW, encH) = encoderClampFor(size.w, size.h)
        val bitrate = if (s.video.autoBitrate) {
            // Heuristic: ~0.1 bit per pixel per frame, bounded.
            ((size.w.toLong() * size.h * s.video.fps) * 0.1 / 8).toLong()
                .coerceIn(250_000L, 40_000_000L).toInt()
        } else {
            (s.video.bitrateMbps * 1_000_000).toInt()
        }
        val audio = s.video.audio && hasMic()
        if (s.video.audio && !audio) {
            _state.update { it.copy(toast = "Microphone unavailable, recording without audio") }
        }
        val cfg = RecConfig(
            targetW = size.w,
            targetH = size.h,
            encoderW = encW,
            encoderH = encH,
            fps = s.video.fps.coerceIn(1, 240),
            bitrateBps = bitrate,
            hevc = s.video.useHevc,
            audio = audio,
            micGain = s.video.micGain
        )
        pipe.post(GlCommand.StartRecording(cfg))
    }

    fun stopRecording() {
        pipe.post(GlCommand.StopRecording)
    }

    fun pauseRecording() {
        if (!_state.value.recording) return
        pipe.post(GlCommand.PauseRecording)
    }

    fun resumeRecording() {
        if (!_state.value.recording) return
        pipe.post(GlCommand.ResumeRecording)
    }

    // ---------------------------------------------------------- focus

    /** Tap-to-focus: view coords -> stream coords, then CameraX metering. */
    fun focusAt(viewX: Float, viewY: Float, viewW: Float, viewH: Float) {
        val s = _state.value
        val caps = s.caps ?: return
        val size = resolvedSize(s)
        val rot = ((caps.sensorOrientation - s.displayRotation) % 360 + 360) % 360
        val (csx, csy) = Framing.cropScale(pipe.streamW, pipe.streamH, rot, size.w, size.h)
        val mirror = caps.isFront && s.cameraPrefs.mirrorFront
        val uv = Framing.viewToStream(
            viewX, viewY, viewW, viewH,
            size.w.toFloat(), size.h.toFloat(),
            rot, mirror, csx, csy
        ) ?: return
        controller?.focusAt(uv.first * pipe.streamW, uv.second * pipe.streamH)
    }

    fun setZoom(ratio: Float) {
        val max = _state.value.caps?.maxZoom ?: 1f
        updateCameraPrefs { it.copy(zoom = ratio.coerceIn(1f, max)) }
    }

    override fun onCleared() {
        pipe.post(GlCommand.StopRecording)
        super.onCleared()
    }
}
