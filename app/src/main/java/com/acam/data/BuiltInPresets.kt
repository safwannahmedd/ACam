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
            ratio = Ratio.THREE_FOUR, k = 0
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
        ),

        // ---------------- Modern grading & cinema looks (1.2.7) ----------------
        Preset(
            id = "cine", name = "Cinematic", builtin = true,
            params = EffectParams(
                splitAmount = 0.7f, splitShadowHue = 0.55f, splitHighlightHue = 0.08f,
                contrast = 0.12f, toneCurve = 0.22f, saturation = -0.05f,
                vignette = 0.15f, grainAmount = 0.05f
            ),
            ratio = Ratio.THREE_TWO, k = 0
        ),
        Preset(
            id = "crossprocess", name = "Cross Process", builtin = true,
            params = EffectParams(
                splitAmount = 0.8f, splitShadowHue = 0.38f, splitHighlightHue = 0.13f,
                contrast = 0.25f, saturation = 0.25f, toneCurve = 0.2f,
                grainAmount = 0.1f, aberration = 0.15f
            ),
            ratio = Ratio.THREE_TWO, k = 0
        ),
        Preset(
            id = "technicolor", name = "Technicolor", builtin = true,
            params = EffectParams(
                vibrance = 0.55f, saturation = 0.3f, contrast = 0.15f,
                mixerRR = 1.12f, mixerRG = 0.04f, mixerGB = 0.04f,
                mixerBB = 1.12f, bloom = 0.06f
            ),
            ratio = Ratio.FOUR_THREE, k = 0
        ),
        Preset(
            id = "noir", name = "Noir", builtin = true,
            params = EffectParams(
                bwAmount = 1f, contrast = 0.42f, toneCurve = 0.4f,
                vignette = 0.35f, grainAmount = 0.22f, sharpness = 0.15f
            ),
            ratio = Ratio.THREE_TWO, k = 0
        ),
        Preset(
            id = "lomo", name = "Lomo", builtin = true,
            params = EffectParams(
                saturation = 0.35f, contrast = 0.2f, vignette = 0.6f,
                vignetteSize = 0.6f, distortion = 0.2f, grainAmount = 0.15f
            ),
            ratio = Ratio.SQUARE, k = 0
        ),
        Preset(
            id = "pastel", name = "Faded Pastel", builtin = true,
            params = EffectParams(
                blackPoint = 0.22f, saturation = -0.25f, contrast = -0.1f,
                exposure = 0.08f, splitAmount = 0.35f,
                splitShadowHue = 0.9f, splitHighlightHue = 0.12f
            ),
            ratio = Ratio.FOUR_FIVE, k = 0
        ),
        Preset(
            id = "golden", name = "Golden Hour", builtin = true,
            params = EffectParams(
                temperature = 0.3f, exposure = 0.1f, bloom = 0.25f,
                saturation = 0.12f, splitAmount = 0.3f,
                splitShadowHue = 0.5f, splitHighlightHue = 0.1f,
                vignette = 0.12f
            ),
            ratio = Ratio.THREE_FOUR, k = 0
        ),
        Preset(
            id = "neon", name = "Neon Night", builtin = true,
            params = EffectParams(
                duotoneAmount = 0.55f, duoDarkHue = 0.55f, duoLightHue = 0.88f,
                contrast = 0.3f, bloom = 0.25f, vignette = 0.3f,
                grainAmount = 0.08f
            ),
            ratio = Ratio.NINE_SIXTEEN, k = 0
        ),
        Preset(
            id = "infrared", name = "Infrared", builtin = true,
            params = EffectParams(
                mixerRR = 0.1f, mixerRG = 0.1f, mixerRB = 0.9f,
                mixerBR = 0.85f, mixerBG = 0.1f, mixerBB = 0.15f,
                contrast = 0.2f, saturation = 0.2f, vignette = 0.2f,
                grainAmount = 0.1f
            ),
            ratio = Ratio.THREE_FOUR, k = 0
        ),

        // ---------------- Print & screen looks (1.2.7) ----------------
        Preset(
            id = "comic", name = "Comic Print", builtin = true,
            params = EffectParams(
                halftone = 0.85f, saturation = 0.3f, contrast = 0.25f,
                colorDepth = 20
            ),
            ratio = Ratio.THREE_FOUR, k = 800
        ),
        Preset(
            id = "blueprint", name = "Blueprint", builtin = true,
            params = EffectParams(
                duotoneAmount = 1f, duoDarkHue = 0.63f, duoLightHue = 0.55f,
                halftone = 0.5f
            ),
            ratio = Ratio.THREE_FOUR, k = 600
        ),
        Preset(
            id = "thermal", name = "Thermal Cam", builtin = true,
            params = EffectParams(
                duotoneAmount = 1f, duoDarkHue = 0.72f, duoLightHue = 0.02f,
                pixelate = 0.45f, contrast = 0.3f, colorNoise = 0.1f
            ),
            ratio = Ratio.FOUR_THREE, k = 400, videoFps = 15
        ),
        Preset(
            id = "gameboy", name = "Game Boy", builtin = true,
            params = EffectParams(
                duotoneAmount = 1f, duoDarkHue = 0.33f, duoLightHue = 0.38f,
                pixelate = 0.85f, dither = 0.6f
            ),
            ratio = Ratio.SQUARE, k = 160
        ),
        Preset(
            id = "mosaic", name = "Mosaic", builtin = true,
            params = EffectParams(
                pixelate = 0.55f, colorDepth = 24, dither = 0.15f
            ),
            ratio = Ratio.SQUARE, k = 320
        ),

        // ---------------- More era looks (1.2.7) ----------------
        Preset(
            id = "y2k", name = "Y2K Digicam", builtin = true,
            params = EffectParams(
                temperature = -0.1f, saturation = 0.1f, bloom = 0.12f,
                blackPoint = 0.05f, sharpness = -0.1f, jpegBlocking = 0.2f,
                grainAmount = 0.06f, lightLeak = 0.12f
            ),
            ratio = Ratio.FOUR_THREE, k = 640, videoFps = 15
        ),
        Preset(
            id = "vhs", name = "VHS Cam", builtin = true,
            params = EffectParams(
                scanlines = 0.6f, colorNoise = 0.4f, aberration = 0.35f,
                saturation = 0.12f, contrast = -0.05f, jpegBlocking = 0.3f,
                dither = 0.2f, grainAmount = 0.12f
            ),
            ratio = Ratio.FOUR_THREE, k = 480, videoFps = 24
        )
    )

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }
}
