package com.acam.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.acam.data.EffectParams
import com.acam.data.FrameStyle
import java.nio.FloatBuffer

/** What the effects program samples its camera pixels from. */
enum class SrcKind {
    /** Plain RGBA texture: full-resolution photo render (decoded JPEG). */
    TEX2D,

    /** Live viewfinder: the CameraX Preview SurfaceTexture as a
     *  GL_TEXTURE_EXTERNAL_OES. */
    OES
}

/**
 * Thin wrapper around the effects shader program: caches uniform locations
 * and pushes an [EffectParams] as the packed vec4 uniforms declared in the
 * effects shader. The [SrcKind] selects which sampling prologue the program
 * is built from; the effects body is byte-identical in every variant.
 *
 * Texture units: 0 = camera source (OES external texture for the live
 * viewfinder, plain RGBA for the photo render), 3 = date-stamp overlay.
 */
class EffectProgram(kind: SrcKind = SrcKind.TEX2D) {

    val program: Int = GlUtils.link(
        ShaderSources.VERTEX,
        when (kind) {
            SrcKind.TEX2D -> ShaderSources.EFFECTS_FRAGMENT_2D
            SrcKind.OES -> ShaderSources.EFFECTS_FRAGMENT_OES
        }
    )

    private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
    private val aUV = GLES20.glGetAttribLocation(program, "aUV")
    private val uPosMatrix = GLES20.glGetUniformLocation(program, "uPosMatrix")
    private val uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    private val uOverlay = GLES20.glGetUniformLocation(program, "uOverlay")
    private val uSize = GLES20.glGetUniformLocation(program, "uSize")
    private val uTime = GLES20.glGetUniformLocation(program, "uTime")
    private val uStampColor = GLES20.glGetUniformLocation(program, "uStampColor")
    private val uStampRect = GLES20.glGetUniformLocation(program, "uStampRect")
    private val uColor0 = GLES20.glGetUniformLocation(program, "uColor0")
    private val uColor1 = GLES20.glGetUniformLocation(program, "uColor1")
    private val uColor2 = GLES20.glGetUniformLocation(program, "uColor2")
    private val uMixR = GLES20.glGetUniformLocation(program, "uMixR")
    private val uMixG = GLES20.glGetUniformLocation(program, "uMixG")
    private val uMixB = GLES20.glGetUniformLocation(program, "uMixB")
    private val uFilm0 = GLES20.glGetUniformLocation(program, "uFilm0")
    private val uFilm1 = GLES20.glGetUniformLocation(program, "uFilm1")
    private val uFilm2 = GLES20.glGetUniformLocation(program, "uFilm2")
    private val uLens0 = GLES20.glGetUniformLocation(program, "uLens0")
    private val uLens1 = GLES20.glGetUniformLocation(program, "uLens1")

    // Source-specific uniforms.
    private val uTex = GLES20.glGetUniformLocation(program, "uTex")
    private val uTexOes = GLES20.glGetUniformLocation(program, "uTexOes")

    init {
        // Sampler unit assignments are constant for the program's lifetime.
        GLES20.glUseProgram(program)
        when (kind) {
            SrcKind.TEX2D -> GLES20.glUniform1i(uTex, 0)
            SrcKind.OES -> GLES20.glUniform1i(uTexOes, 0)
        }
        GLES20.glUniform1i(uOverlay, 3)
    }

    fun use() = GLES20.glUseProgram(program)

    /**
     * Upload all effect parameters. Called once per frame; all values are
     * written into pre-allocated arrays (no per-frame allocations).
     */
    fun setParams(
        p: EffectParams,
        outW: Int,
        outH: Int,
        timeS: Float,
        stampColor: FloatArray,
        stampRect: FloatArray
    ) {
        GLES20.glUniform2f(uSize, outW.toFloat(), outH.toFloat())
        GLES20.glUniform1f(uTime, timeS)

        GLES20.glUniform4f(uColor0, p.exposure, p.contrast, p.brightness, p.saturation)
        GLES20.glUniform4f(uColor1, p.temperature, p.tint, p.highlights, p.shadows)
        GLES20.glUniform4f(uColor2, p.blackPoint, p.toneCurve, p.bwAmount, p.sepiaAmount)
        GLES20.glUniform4f(uMixR, p.mixerRR, p.mixerRG, p.mixerRB, 0f)
        GLES20.glUniform4f(uMixG, p.mixerGR, p.mixerGG, p.mixerGB, 0f)
        GLES20.glUniform4f(uMixB, p.mixerBR, p.mixerBG, p.mixerBB, 0f)
        GLES20.glUniform4f(uFilm0, p.grainAmount, p.grainSize, p.grainColor, p.colorNoise)
        GLES20.glUniform4f(uFilm1, p.bloom, p.dust, p.lightLeak, p.lightLeakType.toFloat())
        GLES20.glUniform4f(uFilm2, p.scanlines, p.jpegBlocking, p.colorDepth.toFloat(), p.dither)
        GLES20.glUniform4f(uLens0, p.sharpness, p.vignette, p.vignetteSize, p.aberration)
        GLES20.glUniform4f(
            uLens1, p.distortion,
            when (p.frame) {
                FrameStyle.NONE -> 0f
                FrameStyle.POLAROID -> 1f
                FrameStyle.FILM_STRIP -> 2f
            },
            if (p.dateStamp) 1f else 0f,
            0f
        )
        GLES20.glUniform4fv(uStampColor, 1, stampColor, 0)
        GLES20.glUniform4fv(uStampRect, 1, stampRect, 0)
    }

    fun draw(quad: FloatBuffer, posMatrix: FloatArray, texMatrix: FloatArray) {
        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glUniformMatrix4fv(uPosMatrix, 1, false, posMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}

/** Nearest-neighbor blit program (FBO -> screen / FBO -> encoder surface). */
class BlitProgram {

    val program: Int = GlUtils.link(ShaderSources.VERTEX, ShaderSources.BLIT_FRAGMENT)

    private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
    private val aUV = GLES20.glGetAttribLocation(program, "aUV")
    private val uPosMatrix = GLES20.glGetUniformLocation(program, "uPosMatrix")
    private val uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    private val uTex = GLES20.glGetUniformLocation(program, "uTex")

    fun use() = GLES20.glUseProgram(program)

    fun draw(quad: FloatBuffer, tex: Int, posMatrix: FloatArray) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTex, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glUniformMatrix4fv(uPosMatrix, 1, false, posMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, Mat4.identity(), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}

/**
 * Raw external-texture blit (SurfaceTexture -> probe FBO). Same vertex
 * layout as [BlitProgram]; the sampler is a samplerExternalOES, so the
 * texture must be bound to GL_TEXTURE_EXTERNAL_OES. Draws with plain
 * identity matrices - the probe only needs pixel statistics, not
 * orientation.
 */
class OesBlitProgram {

    val program: Int = GlUtils.link(ShaderSources.VERTEX, ShaderSources.OES_BLIT_FRAGMENT)

    private val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
    private val aUV = GLES20.glGetAttribLocation(program, "aUV")
    private val uPosMatrix = GLES20.glGetUniformLocation(program, "uPosMatrix")
    private val uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    private val uTex = GLES20.glGetUniformLocation(program, "uTex")

    fun use() = GLES20.glUseProgram(program)

    fun draw(quad: FloatBuffer, tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(uTex, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPosition)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUV)
        GLES20.glUniformMatrix4fv(uPosMatrix, 1, false, Mat4.identity(), 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, Mat4.identity(), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
