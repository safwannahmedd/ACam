package com.acam.ui.adjust

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.acam.data.FlashMode
import com.acam.data.FocusMode
import com.acam.data.FrameStyle
import com.acam.data.GridMode
import com.acam.data.PhotoFormat
import com.acam.data.Ratio
import com.acam.util.RatioMath
import com.acam.vm.CameraViewModel
import com.acam.ui.components.ConfirmDialog
import com.acam.ui.components.Hint
import com.acam.ui.components.NumberEntryDialog
import com.acam.ui.components.SectionHeader
import com.acam.ui.components.Segmented
import com.acam.ui.components.ToggleRow
import com.acam.ui.components.ValueSlider
import java.util.Locale
import kotlin.math.ln

/**
 * The partial-height Adjust sheet. It never covers the whole viewfinder:
 * half-expanded by default, drag to expand/dismiss. Every control writes
 * straight into the live pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustSheet(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var tab by remember { mutableIntStateOf(0) }

    ModalBottomSheet(
        onDismissRequest = { vm.setAdjustOpen(false) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        TabRow(selectedTabIndex = tab) {
            listOf("LOOK", "RESOLUTION", "CAMERA", "VIDEO").forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(title, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
        Column(
            modifier = Modifier
                .heightIn(max = 430.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            when (tab) {
                0 -> LookTab(vm)
                1 -> ResolutionTab(vm)
                2 -> CameraTab(vm)
                else -> VideoTab(vm)
            }
        }
    }
}

// ---------------------------------------------------------------- LOOK

private class Param(
    val label: String,
    val min: Float,
    val max: Float,
    val get: (com.acam.data.EffectParams) -> Float,
    val set: (com.acam.data.EffectParams, Float) -> com.acam.data.EffectParams,
    val fmt: (Float) -> String = { String.format(Locale.US, "%.2f", it) }
)

@Composable
private fun LookTab(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val defaults = state.activePreset?.params ?: com.acam.data.EffectParams()

    val slider: @Composable (Param) -> Unit = { p ->
        ValueSlider(
            label = p.label,
            value = p.get(state.params),
            onValueChange = { v -> vm.updateParams { p.set(it, v) } },
            min = p.min,
            max = p.max,
            defaultValue = p.get(defaults)
        )
    }

    val colorParams = listOf(
        Param("Exposure", -2f, 2f, { it.exposure }, { p, v -> p.copy(exposure = v) }),
        Param("Contrast", -1f, 1f, { it.contrast }, { p, v -> p.copy(contrast = v) }),
        Param("Brightness", -1f, 1f, { it.brightness }, { p, v -> p.copy(brightness = v) }),
        Param("Saturation", -1f, 1f, { it.saturation }, { p, v -> p.copy(saturation = v) }),
        Param("Temperature", -1f, 1f, { it.temperature }, { p, v -> p.copy(temperature = v) }),
        Param("Tint", -1f, 1f, { it.tint }, { p, v -> p.copy(tint = v) }),
        Param("Highlights", -1f, 1f, { it.highlights }, { p, v -> p.copy(highlights = v) }),
        Param("Shadows", -1f, 1f, { it.shadows }, { p, v -> p.copy(shadows = v) }),
        Param("Black point", 0f, 0.5f, { it.blackPoint }, { p, v -> p.copy(blackPoint = v) })
    )
    val toneParams = listOf(
        Param("B&W amount", 0f, 1f, { it.bwAmount }, { p, v -> p.copy(bwAmount = v) }),
        Param("Sepia amount", 0f, 1f, { it.sepiaAmount }, { p, v -> p.copy(sepiaAmount = v) }),
        Param("Tone curve", 0f, 1f, { it.toneCurve }, { p, v -> p.copy(toneCurve = v) })
    )
    val mixerParams = listOf(
        Param("Red <- Red", -1f, 2f, { it.mixerRR }, { p, v -> p.copy(mixerRR = v) }),
        Param("Red <- Green", -1f, 1f, { it.mixerRG }, { p, v -> p.copy(mixerRG = v) }),
        Param("Red <- Blue", -1f, 1f, { it.mixerRB }, { p, v -> p.copy(mixerRB = v) }),
        Param("Green <- Red", -1f, 1f, { it.mixerGR }, { p, v -> p.copy(mixerGR = v) }),
        Param("Green <- Green", -1f, 2f, { it.mixerGG }, { p, v -> p.copy(mixerGG = v) }),
        Param("Green <- Blue", -1f, 1f, { it.mixerGB }, { p, v -> p.copy(mixerGB = v) }),
        Param("Blue <- Red", -1f, 1f, { it.mixerBR }, { p, v -> p.copy(mixerBR = v) }),
        Param("Blue <- Green", -1f, 1f, { it.mixerBG }, { p, v -> p.copy(mixerBG = v) }),
        Param("Blue <- Blue", -1f, 2f, { it.mixerBB }, { p, v -> p.copy(mixerBB = v) })
    )
    val filmParams = listOf(
        Param("Grain", 0f, 1f, { it.grainAmount }, { p, v -> p.copy(grainAmount = v) }),
        Param("Grain size", 0.5f, 4f, { it.grainSize }, { p, v -> p.copy(grainSize = v) }),
        Param("Chroma grain", 0f, 1f, { it.grainColor }, { p, v -> p.copy(grainColor = v) }),
        Param("Color noise", 0f, 1f, { it.colorNoise }, { p, v -> p.copy(colorNoise = v) }),
        Param("Halation", 0f, 1f, { it.bloom }, { p, v -> p.copy(bloom = v) }),
        Param("Dust & scratches", 0f, 1f, { it.dust }, { p, v -> p.copy(dust = v) }),
        Param("Light leak", 0f, 1f, { it.lightLeak }, { p, v -> p.copy(lightLeak = v) }),
        Param("Scanlines", 0f, 1f, { it.scanlines }, { p, v -> p.copy(scanlines = v) })
    )
    val lensParams = listOf(
        Param("Sharpness", -1f, 1f, { it.sharpness }, { p, v -> p.copy(sharpness = v) }),
        Param("Vignette", 0f, 1f, { it.vignette }, { p, v -> p.copy(vignette = v) }),
        Param("Vignette size", 0.4f, 2f, { it.vignetteSize }, { p, v -> p.copy(vignetteSize = v) }),
        Param("Chromatic aberration", 0f, 1f, { it.aberration }, { p, v -> p.copy(aberration = v) }),
        Param("Distortion", -1f, 1f, { it.distortion }, { p, v -> p.copy(distortion = v) })
    )
    val digitalParams = listOf(
        Param("JPEG blocking", 0f, 1f, { it.jpegBlocking }, { p, v -> p.copy(jpegBlocking = v) }),
        Param("Color depth", 2f, 255f, { it.colorDepth.toFloat() }, { p, v -> p.copy(colorDepth = v.toInt().coerceIn(2, 255)) }, { String.format(Locale.US, "%.0f", it) }),
        Param("Dithering", 0f, 1f, { it.dither }, { p, v -> p.copy(dither = v) })
    )

    SectionHeader("Color")
    colorParams.forEach { slider(it) }

    SectionHeader("Tone")
    toneParams.forEach { slider(it) }

    SectionHeader("Film")
    filmParams.forEach { slider(it) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
    ) {
        Segmented(
            options = listOf("Warm edge", "Streak", "Red wash"),
            selected = state.params.lightLeakType,
            onSelect = { i -> vm.updateParams { it.copy(lightLeakType = i) } },
            modifier = Modifier.fillMaxWidth()
        )
    }

    SectionHeader("Lens")
    lensParams.forEach { slider(it) }

    SectionHeader("Retro digital")
    digitalParams.forEach { slider(it) }

    SectionHeader("Channel mixer")
    Hint("Output channel gains over input R, G, B.")
    mixerParams.forEach { slider(it) }

    SectionHeader("Frame & stamp")
    Segmented(
        options = listOf("No frame", "Polaroid", "Film strip"),
        selected = when (state.params.frame) {
            FrameStyle.NONE -> 0
            FrameStyle.POLAROID -> 1
            FrameStyle.FILM_STRIP -> 2
        },
        onSelect = { i ->
            vm.updateParams {
                it.copy(frame = when (i) {
                    0 -> FrameStyle.NONE
                    1 -> FrameStyle.POLAROID
                    else -> FrameStyle.FILM_STRIP
                })
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
    ToggleRow(
        title = "Orange date stamp",
        checked = state.params.dateStamp,
        onChange = { on -> vm.updateParams { it.copy(dateStamp = on) } }
    )
    if (state.params.dateStamp) {
        var text by remember(state.dateStamp.text) { mutableStateOf(state.dateStamp.text) }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                vm.updateDateStamp { cfg -> cfg.copy(text = it) }
            },
            label = { Text("Stamp text / date pattern") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Hint("Date patterns like 'MMM d, yyyy  h:mm a' are rendered live; any literal text works too.")
    }
}

// ---------------------------------------------------------------- RESOLUTION

@Composable
private fun ResolutionTab(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val size = vm.resolvedSize(state)
    val kMax = vm.kMax(state)
    val k = if (state.resolution.unlocked) 0 else state.resolution.k
    var customRatioOpen by remember { mutableStateOf(false) }
    var wText by remember(size.w) { mutableStateOf(size.w.toString()) }
    var hText by remember(size.h) { mutableStateOf(size.h.toString()) }

    SectionHeader("Aspect ratio")
    Hint("Ratios are literal output W:H. 4:3 is a landscape frame, 3:4 portrait.")
    val ratios = Ratio.STANDARD
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            ratios.subList(0, 5).forEach { r ->
                RatioChip(
                    label = r.toString(),
                    selected = !state.resolution.unlocked && state.resolution.ratio == r,
                    onClick = { vm.setRatio(r) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            ratios.subList(5, 10).forEach { r ->
                RatioChip(
                    label = r.toString(),
                    selected = !state.resolution.unlocked && state.resolution.ratio == r,
                    onClick = { vm.setRatio(r) },
                    modifier = Modifier.weight(1f)
                )
            }
            RatioChip(
                label = "A:B",
                selected = false,
                onClick = { customRatioOpen = true },
                modifier = Modifier.weight(1f)
            )
        }
    }

    SectionHeader("Size at current ratio")
    Text(
        "${size.w} x ${size.h}    ${RatioMath.megapixelsLabel(size.w, size.h)}",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
    Hint("Integer multiples keep W:H exact. Slide down to the true minimum: 1x1, 4x3, 16x9 ...")
    ValueSlider(
        label = "Resolution",
        value = RatioMath.kToSlider(if (k <= 0) kMax else k, kMax),
        onValueChange = { t -> vm.setSliderT(t) },
        min = 0f,
        max = 1f,
        defaultValue = RatioMath.kToSlider(kMax, kMax),
        format = { _ -> "${size.w}x${size.h}" }
    )

    SectionHeader("Quick pick")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        listOf("1x1" to (1 to 1), "64px" to (64 to 64), "320x240" to (320 to 240)).forEach { (label, t) ->
            RatioChip(label, false, { vm.quickPick(t.first, t.second) }, Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        listOf("640x480" to (640 to 480), "1MP" to (1000 to 1000), "2MP" to (1400 to 1400)).forEach { (label, t) ->
            RatioChip(label, false, { vm.quickPick(t.first, t.second) }, Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        listOf("5MP" to (2200 to 2200), "12MP" to (3400 to 3400), "Max" to (99999 to 99999)).forEach { (label, t) ->
            RatioChip(label, false, { vm.quickPick(t.first, t.second) }, Modifier.weight(1f))
        }
    }

    SectionHeader("Custom W x H")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = wText,
            onValueChange = { txt ->
                wText = txt
                txt.toIntOrNull()?.let { w ->
                    if (state.resolution.unlocked) vm.setCustomW(w) else {
                        vm.setCustomW(w)
                        // H auto-fills from the locked ratio
                        hText = vm.resolvedSize().h.toString()
                    }
                }
            },
            label = { Text("W") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = hText,
            onValueChange = { txt ->
                hText = txt
                txt.toIntOrNull()?.let { h ->
                    vm.setCustomH(h)
                    if (!state.resolution.unlocked) {
                        wText = vm.resolvedSize().w.toString()
                    }
                }
            },
            label = { Text("H") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
    ToggleRow(
        title = "Unlock ratio",
        checked = state.resolution.unlocked,
        onChange = { vm.setUnlocked(it) }
    )
    if (state.resolution.unlocked) {
        Hint("Free W x H: the camera frame is center-cropped to fit, never stretched.")
    }

    val requested = if (state.resolution.unlocked) {
        state.resolution.unlockedW to state.resolution.unlockedH
    } else {
        (k * state.resolution.ratio.a) to (k * state.resolution.ratio.b)
    }
    if (requested.first > size.w || requested.second > size.h) {
        Hint("Above this device's capture capability; clamped to ${size.w} x ${size.h}.")
    }
    Hint("Video note: some encoders require even/minimum dimensions. ACam renders at your chosen size and nearest-neighbor upscales to the closest valid encoder size, keeping the low-res look.")

    if (customRatioOpen) {
        var a by remember { mutableStateOf("21") }
        var b by remember { mutableStateOf("9") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { customRatioOpen = false },
            title = { Text("Custom ratio") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = a, onValueChange = { a = it }, label = { Text("A") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = b, onValueChange = { b = it }, label = { Text("B") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    customRatioOpen = false
                    val av = a.toIntOrNull() ?: 0
                    val bv = b.toIntOrNull() ?: 0
                    if (av in 1..200 && bv in 1..200) vm.setCustomRatio(av, bv)
                }) { Text("Apply") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { customRatioOpen = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RatioChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

// ---------------------------------------------------------------- CAMERA

@Composable
private fun CameraTab(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val caps = state.caps

    SectionHeader("Lens")
    if (state.lensEntries.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            state.lensEntries.take(4).forEachIndexed { i, lens ->
                RatioChip(
                    label = lens.label,
                    selected = i == state.activeLens,
                    onClick = { vm.selectLens(i) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        Hint("This device exposes a single lens.")
    }

    if (caps != null && caps.hasFlash) {
        SectionHeader("Flash")
        Segmented(
            options = listOf("Off", "Auto", "On"),
            selected = state.cameraPrefs.flashMode.ordinal,
            onSelect = { i ->
                vm.updateCameraPrefs { it.copy(flashMode = FlashMode.entries[i]) }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }

    SectionHeader("Focus")
    Segmented(
        options = listOf("Auto", "Continuous", "Manual"),
        selected = state.cameraPrefs.focusMode.ordinal,
        onSelect = { i -> vm.updateCameraPrefs { it.copy(focusMode = FocusMode.entries[i]) } },
        modifier = Modifier.fillMaxWidth()
    )
    if (state.cameraPrefs.focusMode == FocusMode.MANUAL) {
        if (caps?.supportsManualFocus == true) {
            ValueSlider(
                label = "Focus distance",
                value = state.cameraPrefs.focusDistance,
                onValueChange = { v -> vm.updateCameraPrefs { it.copy(focusDistance = v) } },
                min = 0f,
                max = 1f,
                defaultValue = 0f,
                format = { String.format(Locale.US, "%.2f", it) }
            )
            Hint("0 = infinity, 1 = closest focus.")
        } else {
            Hint("Manual focus distance is not supported on this lens.")
        }
    }

    SectionHeader("Exposure")
    if (caps != null && caps.evMax > caps.evMin) {
        ValueSlider(
            label = "EV",
            value = state.cameraPrefs.evIndex.toFloat(),
            onValueChange = { v -> vm.updateCameraPrefs { it.copy(evIndex = v.toInt()) } },
            min = caps.evMin.toFloat(),
            max = caps.evMax.toFloat(),
            defaultValue = 0f,
            format = { String.format(Locale.US, "%.0f", it) }
        )
    }
    if (caps?.supportsManualExposure == true) {
        ToggleRow("Manual ISO & shutter", state.cameraPrefs.manualIso) { on ->
            vm.updateCameraPrefs { it.copy(manualIso = on) }
        }
        if (state.cameraPrefs.manualIso) {
            val isoMin = (caps.isoMin ?: 50).toFloat()
            val isoMax = (caps.isoMax ?: 6400).toFloat()
            LogSlider(
                label = "ISO",
                value = state.cameraPrefs.iso.toFloat().coerceIn(isoMin, isoMax),
                min = isoMin,
                max = isoMax,
                defaultValue = 400f,
                onChange = { v -> vm.updateCameraPrefs { it.copy(iso = v.toInt()) } },
                format = { String.format(Locale.US, "%.0f", it) }
            )
            val shMin = (caps.exposureMinNs ?: 1_000_000L).toFloat()
            val shMax = (caps.exposureMaxNs ?: 500_000_000L).toFloat()
            LogSlider(
                label = "Shutter",
                value = state.cameraPrefs.shutterNs.coerceIn(shMin.toLong(), shMax.toLong()).toFloat(),
                min = shMin,
                max = shMax,
                defaultValue = 20_000_000f,
                onChange = { v -> vm.updateCameraPrefs { it.copy(shutterNs = v.toLong()) } },
                format = { ns -> if (ns >= 1e9f) String.format(Locale.US, "%.1fs", ns / 1e9f) else String.format(Locale.US, "1/%.0fs", 1e9f / ns) }
            )
        }
    } else {
        Hint("Manual ISO/shutter is not exposed by this camera HAL.")
    }

    SectionHeader("White balance")
    if (caps?.manualWb == true) {
        ValueSlider(
            label = "Kelvin (0 = auto)",
            value = state.cameraPrefs.wbKelvin.toFloat(),
            onValueChange = { v -> vm.updateCameraPrefs { it.copy(wbKelvin = v.toInt()) } },
            min = 0f,
            max = 9000f,
            defaultValue = 0f,
            format = { String.format(Locale.US, "%.0f K", it) }
        )
    } else {
        Hint("Manual white balance is not supported; using auto.")
    }

    SectionHeader("Zoom")
    val maxZoom = caps?.maxZoom ?: 1f
    ValueSlider(
        label = "Zoom",
        value = state.cameraPrefs.zoom.coerceIn(1f, maxZoom),
        onValueChange = { v -> vm.updateCameraPrefs { it.copy(zoom = v) } },
        min = 1f,
        max = maxZoom,
        defaultValue = 1f,
        format = { String.format(Locale.US, "%.1fx", it) }
    )

    SectionHeader("Capture aids")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Segmented(
            options = listOf("No timer", "3s", "10s"),
            selected = listOf(0, 3, 10).indexOf(state.cameraPrefs.timer),
            onSelect = { i -> vm.updateCameraPrefs { it.copy(timer = listOf(0, 3, 10)[i]) } },
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(6.dp))
    Segmented(
        options = listOf("Grid off", "Thirds", "Square"),
        selected = state.cameraPrefs.grid.ordinal,
        onSelect = { i -> vm.updateCameraPrefs { it.copy(grid = GridMode.entries[i]) } },
        modifier = Modifier.fillMaxWidth()
    )
    ToggleRow("Level indicator", state.cameraPrefs.showLevel) { on ->
        vm.updateCameraPrefs { it.copy(showLevel = on) }
    }
    if (caps?.isFront == true) {
        ToggleRow("Mirror front camera (save as preview)", state.cameraPrefs.mirrorFront) { on ->
            vm.updateCameraPrefs { it.copy(mirrorFront = on) }
        }
    }
}

/** Log-scale slider for ISO / shutter ranges. */
@Composable
private fun LogSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    defaultValue: Float,
    onChange: (Float) -> Unit,
    format: (Float) -> String
) {
    fun toT(v: Float): Float =
        ((ln(v.toDouble()) - ln(min.toDouble())) / (ln(max.toDouble()) - ln(min.toDouble()))).toFloat()

    fun fromT(t: Float): Float =
        kotlin.math.exp(ln(min.toDouble()) + t * (ln(max.toDouble()) - ln(min.toDouble()))).toFloat()

    ValueSlider(
        label = label,
        value = toT(value),
        onValueChange = { t -> onChange(fromT(t)) },
        min = 0f,
        max = 1f,
        defaultValue = toT(defaultValue),
        format = { _ -> format(value) }
    )
}

// ---------------------------------------------------------------- VIDEO

@Composable
private fun VideoTab(vm: CameraViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val size = vm.resolvedSize(state)
    val enc = vm.encoderClampFor(size.w, size.h)

    SectionHeader("Frame rate")
    Hint("If the sensor cannot run this slowly, frames are dropped in the pipeline with exact timestamps, so playback is genuinely choppy.")
    val fpsOptions = listOf(8, 12, 15, 24, 25, 30, 48, 60, 120)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            fpsOptions.subList(0, 5).forEach { f ->
                RatioChip("${f}", state.video.fps == f, { vm.updateVideo { it.copy(fps = f) } }, Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            fpsOptions.subList(5, 9).forEach { f ->
                RatioChip("${f}", state.video.fps == f, { vm.updateVideo { it.copy(fps = f) } }, Modifier.weight(1f))
            }
        }
    }
    var customFps by remember(state.video.fps) { mutableStateOf(state.video.fps.toString()) }
    OutlinedTextField(
        value = customFps,
        onValueChange = { txt ->
            customFps = txt
            txt.toIntOrNull()?.let { f ->
                if (f in 1..240) vm.updateVideo { it.copy(fps = f) }
            }
        },
        label = { Text("Custom fps (1 - 240)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    SectionHeader("Quality")
    ToggleRow("Auto bitrate", state.video.autoBitrate) { on ->
        vm.updateVideo { it.copy(autoBitrate = on) }
    }
    if (!state.video.autoBitrate) {
        ValueSlider(
            label = "Bitrate",
            value = state.video.bitrateMbps,
            onValueChange = { v -> vm.updateVideo { it.copy(bitrateMbps = v) } },
            min = 0.5f,
            max = 40f,
            defaultValue = 8f,
            format = { String.format(Locale.US, "%.1f Mbps", it) }
        )
    }
    Segmented(
        options = listOf("H.264", "HEVC"),
        selected = if (state.video.useHevc) 1 else 0,
        onSelect = { i -> vm.updateVideo { it.copy(useHevc = i == 1) } },
        modifier = Modifier.fillMaxWidth()
    )

    SectionHeader("Audio")
    ToggleRow("Record audio", state.video.audio) { on ->
        vm.updateVideo { it.copy(audio = on) }
    }
    if (state.video.audio) {
        ValueSlider(
            label = "Mic gain",
            value = state.video.micGain,
            onValueChange = { v -> vm.updateVideo { it.copy(micGain = v) } },
            min = 0f,
            max = 4f,
            defaultValue = 1f,
            format = { String.format(Locale.US, "%.1fx", it) }
        )
    }

    SectionHeader("Stabilization")
    if (state.caps?.videoStab == true) {
        ToggleRow("Video stabilization", state.video.stabilization) { on ->
            vm.updateVideo { it.copy(stabilization = on) }
            vm.controller?.stabilizationWanted = on
            vm.controller?.applyPrefs(vm.state.value.cameraPrefs)
        }
    } else {
        Hint("Not supported by this camera.")
    }

    SectionHeader("Encoder note")
    if (enc.first != size.w || enc.second != size.h) {
        Hint("Chosen ${size.w}x${size.h} will be encoded as ${enc.first}x${enc.second} (encoder minimum/even-size rules). The pixel look stays: frames are upscaled with nearest-neighbor.")
    } else {
        Hint("Encoder matches your ${size.w}x${size.h} exactly.")
    }
}
