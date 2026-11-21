package com.acam.data

import kotlinx.serialization.Serializable

/** A reduced integer aspect ratio A:B (e.g. 4:3). */
@Serializable
data class Ratio(val a: Int, val b: Int) {
    init {
        require(a > 0 && b > 0) { "Ratio parts must be positive: $a:$b" }
    }

    val w: Float get() = a.toFloat()
    val h: Float get() = b.toFloat()

    override fun toString(): String = "$a:$b"

    companion object {
        val SQUARE = Ratio(1, 1)
        val FOUR_THREE = Ratio(4, 3)
        val THREE_FOUR = Ratio(3, 4)
        val THREE_TWO = Ratio(3, 2)
        val TWO_THREE = Ratio(2, 3)
        val SIXTEEN_NINE = Ratio(16, 9)
        val NINE_SIXTEEN = Ratio(9, 16)
        val FIVE_FOUR = Ratio(5, 4)
        val FOUR_FIVE = Ratio(4, 5)
        val TWENTY_ONE_NINE = Ratio(21, 9)
        val NINE_TWENTY_ONE = Ratio(9, 21)

        /** The ten ratios offered in the UI, landscape forms first. */
        val STANDARD = listOf(
            SQUARE, FOUR_THREE, THREE_FOUR, THREE_TWO, TWO_THREE,
            SIXTEEN_NINE, NINE_SIXTEEN, FIVE_FOUR, TWENTY_ONE_NINE, NINE_TWENTY_ONE
        )
    }
}

/** Simple integer size. */
@Serializable
data class Size2(val w: Int, val h: Int) {
    val aspect: Float get() = if (h == 0) 1f else w.toFloat() / h
    val megapixels: Float get() = (w.toLong() * h.toLong()) / 1_000_000f
    override fun toString(): String = "${w}x$h"
}

/**
 * One entry in the LOOK pipeline. Every value has a neutral default so that
 * the "Normal" preset is literally [EffectParams] with all defaults.
 *
 * All of these are consumed by a single GLSL fragment shader
 * (see gl/ShaderSources.kt) shared by preview, photo and video, which is
 * what makes the app WYSIWYG.
 */
@Serializable
data class EffectParams(
    // --- Color ---
    val exposure: Float = 0f,        // EV, -2..2
    val contrast: Float = 0f,        // -1..1
    val brightness: Float = 0f,      // -1..1
    val saturation: Float = 0f,      // -1..1
    val temperature: Float = 0f,     // -1..1, positive = warmer
    val tint: Float = 0f,            // -1..1, positive = magenta
    val highlights: Float = 0f,      // -1..1
    val shadows: Float = 0f,         // -1..1
    val blackPoint: Float = 0f,      // 0..0.5, lifts blacks (faded film)
    // --- Channel mixer (rows: output R, G, B over input R, G, B) ---
    val mixerRR: Float = 1f, val mixerRG: Float = 0f, val mixerRB: Float = 0f,
    val mixerGR: Float = 0f, val mixerGG: Float = 1f, val mixerGB: Float = 0f,
    val mixerBR: Float = 0f, val mixerBG: Float = 0f, val mixerBB: Float = 1f,
    // --- Tone / looks ---
    val bwAmount: Float = 0f,        // 0..1
    val sepiaAmount: Float = 0f,     // 0..1
    val toneCurve: Float = 0f,       // 0..1 filmic S-curve strength
    // --- Film ---
    val grainAmount: Float = 0f,     // 0..1
    val grainSize: Float = 1f,       // 0.5..4, relative to frame height
    val grainColor: Float = 0f,      // 0..1, how much chroma grain
    val colorNoise: Float = 0f,      // 0..1
    val bloom: Float = 0f,           // 0..1 halation
    val lightLeak: Float = 0f,       // 0..1
    val lightLeakType: Int = 0,      // 0 warm edge, 1 streak, 2 red wash
    val dust: Float = 0f,            // 0..1 dust & scratches
    val scanlines: Float = 0f,       // 0..1
    // --- Lens ---
    val sharpness: Float = 0f,       // -1..1, negative = soft focus
    val vignette: Float = 0f,        // 0..1
    val vignetteSize: Float = 1f,    // 0.4..2
    val aberration: Float = 0f,      // 0..1 chromatic aberration
    val distortion: Float = 0f,      // -1..1 barrel..pincushion
    // --- Retro digital ---
    val jpegBlocking: Float = 0f,    // 0..1 JPEG-style block/chroma artifacts
    val colorDepth: Int = 255,       // 2..255 levels per channel
    val dither: Float = 0f,          // 0..1 ordered dithering strength
    // --- Overlays ---
    val frame: FrameStyle = FrameStyle.NONE,
    val dateStamp: Boolean = false
)

@Serializable
enum class FrameStyle { NONE, POLAROID, FILM_STRIP }

@Serializable
data class DateStampConfig(
    /** SimpleDateFormat pattern, or any literal text if parsing fails. */
    val text: String = "MMM d, yyyy  h:mm a",
    val corner: Int = 1,             // 0 top-left, 1 bottom-right
    val colorArgb: Long = 0xFFFF8A00L
)

/**
 * Resolution selection. In locked mode the size is always an exact integer
 * multiple of the ratio: W = k*a, H = k*b. [k] <= 0 means "device maximum".
 *
 * The default is portrait 3:4 so the viewfinder fills a phone held upright;
 * ratios here are the literal output shape (4:3 = landscape frame).
 */
@Serializable
data class ResolutionSpec(
    val ratio: Ratio = Ratio.THREE_FOUR,
    val k: Int = 0,
    val unlocked: Boolean = false,
    val unlockedW: Int = 0,
    val unlockedH: Int = 0
)

@Serializable
enum class FlashMode { OFF, ON, AUTO }

@Serializable
enum class GridMode { OFF, THIRDS, SQUARE }

@Serializable
enum class FocusMode { AUTO, CONTINUOUS, MANUAL }

@Serializable
enum class PhotoFormat { JPEG, PNG }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class VideoSettings(
    val fps: Int = 30,
    val autoBitrate: Boolean = true,
    val bitrateMbps: Float = 8f,
    val useHevc: Boolean = false,
    val audio: Boolean = true,
    val micGain: Float = 1f,         // 0..4
    val stabilization: Boolean = false
)

@Serializable
data class CameraPrefs(
    val flashMode: FlashMode = FlashMode.OFF,
    val timer: Int = 0,              // 0, 3 or 10 seconds
    val grid: GridMode = GridMode.OFF,
    val mirrorFront: Boolean = true,
    val showLevel: Boolean = true,
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    val focusDistance: Float = 0f,   // 0..1 normalized to min focus distance
    val evIndex: Int = 0,            // index into device EV range
    val manualIso: Boolean = false,
    val iso: Int = 400,
    val manualShutter: Boolean = false,
    val shutterNs: Long = 20_000_000L,
    val wbKelvin: Int = 0,           // 0 = auto
    val zoom: Float = 1f
)

@Serializable
data class UiPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val haptics: Boolean = true,
    val shutterSound: Boolean = false,
    val volumeKeyShutter: Boolean = true,
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val jpegQuality: Int = 92
)

/**
 * A look preset. Built-in presets can be reset but never overwritten;
 * custom presets can be renamed, duplicated and deleted.
 * [k] <= 0 means "largest size the device supports at this ratio".
 */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val builtin: Boolean,
    val params: EffectParams = EffectParams(),
    val ratio: Ratio = Ratio.FOUR_THREE,
    val k: Int = 0,
    val videoFps: Int? = null
)
