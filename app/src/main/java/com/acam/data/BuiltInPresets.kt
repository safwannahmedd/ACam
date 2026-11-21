package com.acam.data

/**
 * The built-in preset library. Every preset sets sensible values for ALL
 * effect parameters (anything not listed stays at its neutral default)
 * plus a default ratio/size that gets clamped to the real device.
 */
object BuiltInPresets {

    val ALL: List<Preset> = listOf(
        Preset(
            id = "normal", name = "Normal", builtin = true,
            params = EffectParams(),
            ratio = Ratio.FOUR_THREE, k = 0
        ),

        // ---------------- Old camera looks ----------------
        Preset(
            id = "polaroid", name = "Polaroid", builtin = true,
            params = EffectParams(
                temperature = 0.16f, tint = 0.04f, contrast = -0.08f,
                saturation = -0.12f, blackPoint = 0.12f, toneCurve = 0.25f,
                grainAmount = 0.10f, grainSize = 1.2f, bloom = 0.08f,
                vignette = 0.18f, vignetteSize = 1.1f,
                frame = FrameStyle.POLAROID
            ),
            ratio = Ratio.SQUARE, k = 0
        ),
        Preset(
            id = "instamatic", name = "Kodak 110", builtin = true,
            params = EffectParams(
                temperature = 0.22f, saturation = -0.06f, blackPoint = 0.05f,
                sharpness = -0.15f, grainAmount = 0.18f, grainSize = 1.3f,
                lightLeak = 0.06f, vignette = 0.15f
            ),
            ratio = Ratio.SQUARE, k = 640
        ),
        Preset(
            id = "film35", name = "35mm Film", builtin = true,
            params = EffectParams(
                saturation = 0.08f, toneCurve = 0.30f,
                grainAmount = 0.14f, grainSize = 0.9f, bloom = 0.18f,
                vignette = 0.10f
            ),
            ratio = Ratio.THREE_TWO, k = 0
        ),
        Preset(
            id = "bwfilm", name = "B&W Film", builtin = true,
            params = EffectParams(
                bwAmount = 1f, contrast = 0.28f, blackPoint = 0.04f,
                toneCurve = 0.35f, grainAmount = 0.30f, grainSize = 1.1f,
                vignette = 0.22f
            ),
            ratio = Ratio.THREE_TWO, k = 0
        ),
        Preset(
            id = "disposable", name = "Disposable", builtin = true,
            params = EffectParams(
                exposure = 0.18f, highlights = 0.35f, saturation = 0.06f,
                vignette = 0.30f, vignetteSize = 0.8f,
                lightLeak = 0.22f, lightLeakType = 1,
                grainAmount = 0.16f
            ),
            ratio = Ratio.FOUR_THREE, k = 800
        ),
        Preset(
            id = "holga", name = "Holga", builtin = true,
            params = EffectParams(
                saturation = 0.12f, sharpness = -0.35f, aberration = 0.5f,
                distortion = 0.25f, vignette = 0.55f, vignetteSize = 0.65f,
                grainAmount = 0.20f
            ),
            ratio = Ratio.SQUARE, k = 800
        ),
        Preset(
            id = "sepia", name = "Sepia Box", builtin = true,
            params = EffectParams(
                bwAmount = 1f, sepiaAmount = 0.85f, sharpness = -0.25f,
                dust = 0.45f, vignette = 0.30f, grainAmount = 0.12f
            ),
            ratio = Ratio.SQUARE, k = 640
        ),
        Preset(
            id = "super8", name = "Super 8", builtin = true,
            params = EffectParams(
                saturation = 0.15f, contrast = 0.06f,
                scanlines = 0.55f, colorNoise = 0.35f, aberration = 0.3f,
                colorDepth = 32, dither = 0.3f, jpegBlocking = 0.25f,
                grainAmount = 0.20f, vignette = 0.2f
            ),
            ratio = Ratio.FOUR_THREE, k = 160, videoFps = 12
        ),

        // ---------------- Old phone camera looks ----------------
        Preset(
            id = "n95", name = "Nokia N95", builtin = true,
            params = EffectParams(
                sharpness = 0.55f, jpegBlocking = 0.5f, colorNoise = 0.18f,
                saturation = -0.05f, grainAmount = 0.08f, grainSize = 0.7f
            ),
            ratio = Ratio.FOUR_THREE, k = 400
        ),
        Preset(
            id = "vga", name = "Flip Phone", builtin = true,
            params = EffectParams(
                colorDepth = 64, jpegBlocking = 0.6f, dither = 0.2f,
                colorNoise = 0.3f, saturation = -0.15f, sharpness = 0.2f
            ),
            ratio = Ratio.FOUR_THREE, k = 160
        ),
        Preset(
            id = "qcif", name = "0.3MP Phone", builtin = true,
            params = EffectParams(
                colorDepth = 32, dither = 0.5f, jpegBlocking = 0.35f,
                sharpness = 0.1f, grainAmount = 0.12f, colorNoise = 0.2f
            ),
            ratio = Ratio.FOUR_THREE, k = 80
        ),
        Preset(
            id = "earlysmart", name = "2009 Phone", builtin = true,
            params = EffectParams(
                saturation = -0.18f, sharpness = -0.10f, blackPoint = 0.06f,
                grainAmount = 0.22f, grainSize = 0.8f, colorNoise = 0.25f
            ),
            ratio = Ratio.FOUR_THREE, k = 512
        ),
        Preset(
            id = "webcam", name = "Old Webcam", builtin = true,
            params = EffectParams(
                temperature = -0.12f, tint = -0.08f, sharpness = -0.2f,
                colorNoise = 0.35f, grainAmount = 0.2f, dither = 0.25f,
                colorDepth = 48, scanlines = 0.08f
            ),
            ratio = Ratio.FOUR_THREE, k = 160, videoFps = 15
        )
    )

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }
}
