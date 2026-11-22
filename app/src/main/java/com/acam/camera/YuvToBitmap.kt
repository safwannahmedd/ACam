package com.acam.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * YUV_420_888 -> ARGB bitmap conversion on the CPU, honoring the row stride
 * and pixel stride of every plane exactly.
 *
 * This is the viewfinder's fallback of last resort (see CameraController's
 * CPU session): when the GL-preview path is declared degenerate at every
 * resolution, the camera feeds an ImageAnalysis stream whose frames are
 * converted here and pushed to the GL pipeline as plain 2D textures - the
 * exact texture class the photo render already proves correct on every
 * device. It is the same BT.601 limited-range math the effects shader and
 * the diagnostics CPU render use, so the fallback viewfinder is color-matched
 * with the GL path.
 *
 * Handles both layouts CameraX delivers:
 *  - planar (I420): three separate planes, pixelStride 1
 *  - semi-planar (NV12/NV21): U and V interleaved in one buffer,
 *    pixelStride 2, order derived from the plane buffer positions
 */
object YuvToBitmap {

    private fun ByteBuffer.getOr(index: Int, default: Int): Int =
        if (index >= 0 && index < limit()) (get(index).toInt() and 0xFF) else default

    fun convert(image: ImageProxy): Bitmap {
        val w = image.width
        val h = image.height
        require(w > 0 && h > 0) { "degenerate frame ${w}x$h" }
        val planes = image.planes
        require(planes.size >= 3) { "unexpected plane count ${planes.size}" }

        val yBuf = planes[0].buffer.duplicate()
        val uBuf = planes[1].buffer.duplicate()
        val vBuf = planes[2].buffer.duplicate()
        // Absolute gets index from the buffer start; the planes' data starts
        // at each buffer's position (non-zero for interleaved chroma).
        val yOff = yBuf.position()
        val uOff = uBuf.position()
        val vOff = vBuf.position()
        val yRow = planes[0].rowStride
        val yPx = planes[0].pixelStride.coerceAtLeast(1)
        val uRow = planes[1].rowStride
        val uPx = planes[1].pixelStride.coerceAtLeast(1)
        val vRow = planes[2].rowStride
        val vPx = planes[2].pixelStride.coerceAtLeast(1)

        val semi = uPx == 2
        // Semi-planar: plane[1]'s buffer starts at the first chroma byte of
        // the pair; whether that byte is U or V follows from the positions.
        val firstIsU = uOff <= vOff

        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val pixels = IntArray(w * h)

        var yBase = 0
        for (y in 0 until h) {
            val cy = (y / 2).coerceAtMost(ch - 1)
            val semiRow = uOff + cy * uRow
            val planarU = uOff + cy * uRow
            val planarV = vOff + cy * vRow
            for (x in 0 until w) {
                val yv = yBuf.getOr(yOff + yBase + x * yPx, 16)
                val cx = (x / 2).coerceAtMost(cw - 1)
                val u: Int
                val v: Int
                if (semi) {
                    val idx = semiRow + cx * 2
                    val b0 = uBuf.getOr(idx, 128)
                    // Last chroma byte of the very last row: the plane buffer
                    // can be exactly one byte short of the full pair (the
                    // 1.2.4 fix - it used to fall back to neutral 128 and
                    // left the bottom image row color-stale). Clamp to the
                    // last valid byte instead.
                    val b1 = uBuf.getOr((idx + 1).coerceAtMost(uBuf.limit() - 1), 128)
                    if (firstIsU) {
                        u = b0; v = b1
                    } else {
                        u = b1; v = b0
                    }
                } else {
                    u = uBuf.getOr(planarU + cx * uPx, 128)
                    v = vBuf.getOr(planarV + cx * vPx, 128)
                }
                // BT.601 limited range, same coefficients as the shader.
                val ly = (yv - 16) * 1.1643836
                val du = u - 128.0
                val dv = v - 128.0
                val r = (ly + 1.5960346 * dv).coerceIn(0.0, 255.0).toInt()
                val g = (ly - 0.3917623 * du - 0.8129676 * dv).coerceIn(0.0, 255.0).toInt()
                val b = (ly + 2.0172321 * du).coerceIn(0.0, 255.0).toInt()
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            yBase += yRow
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}
