package com.acam.gl

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Small collection of OpenGL ES 2.0 helpers (compile, link, FBO, matrices). */
object GlUtils {

    private const val TAG = "GlUtils"

    fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error:\n" + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Shader compilation failed")
        }
        return shader
    }

    fun link(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            Log.e(TAG, "Program link error:\n" + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Program link failed")
        }
        return program
    }

    /** An offscreen render target. The texture is NEAREST-filtered so the
     *  blit to screen shows real, visible pixels at low target sizes. */
    class Fbo(val id: Int, val tex: Int, val w: Int, val h: Int) {
        fun bind() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id)
        }

        fun delete() {
            val fbos = intArrayOf(id)
            GLES20.glDeleteFramebuffers(1, fbos, 0)
            deleteTexture(tex)
        }
    }

    fun createFbo(w: Int, h: Int): Fbo {
        val tex = genTexture(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null as ByteBuffer?
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val fboId = IntArray(1)
        GLES20.glGenFramebuffers(1, fboId, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete: $status" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return Fbo(fboId[0], tex, w, h)
    }

    fun genTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun deleteTexture(tex: Int) {
        val ids = intArrayOf(tex)
        GLES20.glDeleteTextures(1, ids, 0)
    }

    /** Full-screen quad as triangle strip: 2 pos floats + 2 uv floats per vertex. */
    fun quadBuffer(): FloatBuffer {
        // x, y, u, v
        val data = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        val bb = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(data)
        fb.position(0)
        return fb
    }
}

/**
 * Minimal column-major 4x4 matrix math for the UV transform chain:
 *
 *   vUV = FLIP_Y * R * C * M * uv
 *
 * applied in this order to a target-space uv (GL-natural: origin at the
 * BOTTOM-left, v grows upward - this is what makes the capture readback
 * and the on-screen blit both come out upright without extra flips):
 *   M = optional horizontal mirror (front camera)
 *   C = center-crop to the target ratio, computed in UPRIGHT space
 *   R = rotate upright -> sensor/stream orientation
 *   FLIP_Y = row order of the uploaded textures (row 0 of the bitmap /
 *          YUV plane lands at v = 0, i.e. the buffer's TOP row)
 *
 * IMPORTANT - rotation direction: R rotates in UV space, which is the
 * INVERSE of the image-space rotation. SENSOR_ORIENTATION means "rotate
 * the buffer clockwise by that angle to be upright" (image space), so the
 * UV-space rotation must be R((360 - rot) % 360). Using R(rot) renders the
 * frame diagonally mirrored / 180 degrees off on the two portrait cases
 * (rot = 90 and 270) - the long-standing "viewfinder shows lines / garbage"
 * bug. Verified against corner-derived ground truth for all four angles.
 *
 * Getting this order right is what guarantees "circles stay circles" and
 * "same field of view at every resolution". The live viewfinder and the
 * full-resolution photo render use the IDENTICAL chain, so the viewfinder
 * is literally the saved photo.
 */
object Mat4 {

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    /** r = a * b (apply b first, then a). */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (i in 0..3) {
            for (j in 0..3) {
                var s = 0f
                for (k in 0..3) {
                    s += a[k * 4 + j] * b[i * 4 + k]
                }
                r[i * 4 + j] = s
            }
        }
        return r
    }

    /** Allocation-free r = a * b into a pre-owned destination. */
    fun mulInto(r: FloatArray, a: FloatArray, b: FloatArray) {
        for (i in 0..3) {
            for (j in 0..3) {
                var s = 0f
                for (k in 0..3) {
                    s += a[k * 4 + j] * b[i * 4 + k]
                }
                r[i * 4 + j] = s
            }
        }
    }

    /** Mirror: x -> 1 - x. */
    fun mirror(): FloatArray = floatArrayOf(
        -1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        1f, 0f, 0f, 1f
    )

    /** Y flip: y -> 1 - y. Row 0 of every uploaded source texture
     *  (bitmap via GLUtils.texImage2D, YUV planes) lands at v = 0, and the
     *  pipeline's target space has its origin at the top-left. */
    fun flipY(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f
    )

    /** Center crop: uv' = (uv - 0.5) * cs + 0.5. */
    fun crop(csx: Float, csy: Float): FloatArray = floatArrayOf(
        csx, 0f, 0f, 0f,
        0f, csy, 0f, 0f,
        0f, 0f, 1f, 0f,
        0.5f * (1f - csx), 0.5f * (1f - csy), 0f, 1f
    )

    /** Upright -> stream rotation in UV space. deg must be 0/90/180/270.
     *  NOTE: the pipeline always passes (360 - rot) here, because a
     *  clockwise image-space rotation is a counter-clockwise UV-space
     *  rotation (see the object docs). */
    fun rotation(deg: Int): FloatArray = when (((deg % 360) + 360) % 360) {
        // x' = y,        y' = 1 - x
        90 -> floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 0f
        )
        // x' = 1 - x,    y' = 1 - y
        180 -> floatArrayOf(
            -1f, 0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 1f, 0f, 0f
        )
        // x' = 1 - y,    y' = x
        270 -> floatArrayOf(
            0f, 1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 0f, 0f, 0f
        )
        else -> identity()
    }
}
