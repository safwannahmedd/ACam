package com.acam

import com.acam.gl.Mat4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE 1.2.6 REGRESSION TEST.
 *
 * Mat4.rotation built non-affine matrices for 90/180/270 (m15 = 0 instead
 * of 1). Composed into the render chain B * R * C, the zeroed w-row ate the
 * translation terms of everything multiplied after it: the composed v
 * coordinate went negative for every fragment, clamped to 0, and the whole
 * viewfinder rendered ONE row of the camera texture with each texel blown
 * up into a full-width band - the "insanely zoomed / flat viewfinder while
 * photos are fine" bug that survived five HAL-theory fix attempts.
 *
 * These tests pin the two properties that were broken:
 *  1. every rotation is AFFINE (m15 = 1), so translations survive composition;
 *  2. the composed preview chain maps the unit square into [0, 1]^2 (the
 *     full camera frame is visible, nothing collapses to a sliver).
 */
class Mat4RotationTest {

    /** Column-major element access: m[row][col] = flat[col * 4 + row]. */
    private fun at(m: FloatArray, row: Int, col: Int): Float = m[col * 4 + row]

    private fun apply(m: FloatArray, u: Float, v: Float): Pair<Float, Float> {
        val x = at(m, 0, 0) * u + at(m, 0, 1) * v + at(m, 0, 3)
        val y = at(m, 1, 0) * u + at(m, 1, 1) * v + at(m, 1, 3)
        return x to y
    }

    @Test
    fun `every rotation matrix is affine`() {
        // THE 1.2.6 BUG: the 90/180/270 branches ended in m15 = 0.
        for (deg in listOf(0, 90, 180, 270, 450, -90)) {
            val m = Mat4.rotation(deg)
            assertEquals("m15 must be 1 for deg=$deg (non-affine matrices corrupt every composition)", 1f, m[15], 0f)
        }
    }

    @Test
    fun `rotation maps the unit square as documented`() {
        // 90: x' = y, y' = 1 - x
        Mat4.rotation(90).let { m ->
            assertEquals(0f, apply(m, 0f, 0f).first, 1e-6f)
            assertEquals(1f, apply(m, 0f, 0f).second, 1e-6f)
            assertEquals(0f, apply(m, 1f, 0f).first, 1e-6f)
            assertEquals(0f, apply(m, 1f, 0f).second, 1e-6f)
            assertEquals(1f, apply(m, 0f, 1f).first, 1e-6f)
            assertEquals(1f, apply(m, 0f, 1f).second, 1e-6f)
            assertEquals(1f, apply(m, 1f, 1f).first, 1e-6f)
            assertEquals(0f, apply(m, 1f, 1f).second, 1e-6f)
        }
        // 180: x' = 1 - x, y' = 1 - y
        Mat4.rotation(180).let { m ->
            assertEquals(1f, apply(m, 0f, 0f).first, 1e-6f)
            assertEquals(1f, apply(m, 0f, 0f).second, 1e-6f)
            assertEquals(0f, apply(m, 1f, 1f).first, 1e-6f)
            assertEquals(0f, apply(m, 1f, 1f).second, 1e-6f)
        }
        // 270: x' = 1 - y, y' = x
        Mat4.rotation(270).let { m ->
            assertEquals(1f, apply(m, 0f, 0f).first, 1e-6f)
            assertEquals(0f, apply(m, 0f, 0f).second, 1e-6f)
            assertEquals(0f, apply(m, 1f, 1f).first, 1e-6f)
            assertEquals(1f, apply(m, 1f, 1f).second, 1e-6f)
        }
    }

    /**
     * The full preview composition (the exact chain from
     * GlCameraView.onDrawFrame, minus the mirror): B * R * C must map the
     * target unit square INTO [0, 1]^2 with a sane sampled window - the
     * whole camera frame visible, never a collapsed sliver. This is the
     * chain that rendered one texture row when m15 was 0.
     */
    @Test
    fun `composed preview chain samples a sane window for every rotation`() {
        for (rot in listOf(0, 90, 180, 270)) {
            val r = Mat4.rotation((360 - rot) % 360)
            val c = Mat4.crop(1f, 0.75f) // e.g. 4:3 stream -> 1:1 target
            val chain = Mat4.mul(Mat4.mul(Mat4.flipY(), r), c)

            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (u in listOf(0f, 1f)) for (v in listOf(0f, 1f)) {
                val (x, y) = apply(chain, u, v)
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
            assertTrue("rot=$rot: X window collapsed ([$minX..$maxX])", maxX - minX > 0.5f)
            assertTrue("rot=$rot: Y window collapsed ([$minY..$maxY])", maxY - minY > 0.5f)
            assertTrue("rot=$rot: X window escapes [0,1] ([$minX..$maxX])", minX > -0.05f && maxX < 1.05f)
            assertTrue("rot=$rot: Y window escapes [0,1] ([$minY..$maxY])", minY > -0.05f && maxY < 1.05f)
        }
    }

    /** The photo render chain (renderOffline) with a non-zero rotation must
     *  stay sane too: pre-1.2.6 it collapsed exactly like the preview. */
    @Test
    fun `composed photo chain with zoom crop stays sane`() {
        val r = Mat4.rotation(270) // portrait photo, 90-degree sensor
        val c = Mat4.crop(0.75f, 0.75f)
        val chain = Mat4.mul(Mat4.mul(Mat4.flipY(), r), c)
        // Center of the target must map into the camera frame's center.
        val (cx, cy) = apply(chain, 0.5f, 0.5f)
        assertEquals(0.5f, cx, 1e-4f)
        assertEquals(0.5f, cy, 1e-4f)
        // Corners must stay inside [0, 1] (the crop keeps them there).
        for (u in listOf(0f, 1f)) for (v in listOf(0f, 1f)) {
            val (x, y) = apply(chain, u, v)
            assertTrue("corner ($u,$v) -> ($x,$y) escapes the frame", x in -0.01f..1.01f && y in -0.01f..1.01f)
        }
    }
}
