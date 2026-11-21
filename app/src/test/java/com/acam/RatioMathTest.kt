package com.acam

import com.acam.data.Ratio
import com.acam.data.Size2
import com.acam.util.Framing
import com.acam.util.RatioMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM tests for the resolution/ratio system (spec section 7) and the
 * framing math that guarantees no stretching and a fixed field of view.
 */
class RatioMathTest {

    // ---- exact integer multiples ----

    @Test
    fun `every generated size is an exact ratio multiple`() {
        val ratios = listOf(Ratio(1, 1), Ratio(4, 3), Ratio(3, 4), Ratio(3, 2), Ratio(2, 3),
            Ratio(16, 9), Ratio(9, 16), Ratio(5, 4), Ratio(21, 9), Ratio(9, 21))
        for (r in ratios) {
            for (k in listOf(1, 2, 7, 63, 333, 1000)) {
                val s = RatioMath.sizeFor(r, k)
                assertEquals("W must be k*a for ${r}", k * r.a, s.w)
                assertEquals("H must be k*b for ${r}", k * r.b, s.h)
                // W:H equals the ratio exactly (no accumulated float error)
                assertEquals(r.a.toDouble() / r.b, s.w.toDouble() / s.h, 1e-12)
            }
        }
    }

    // ---- minimum sizes ----

    @Test
    fun `minimum reachable sizes`() {
        assertEquals(Size2(1, 1), RatioMath.sizeFor(Ratio(1, 1), 1))
        assertEquals(Size2(4, 3), RatioMath.sizeFor(Ratio(4, 3), 1))
        assertEquals(Size2(16, 9), RatioMath.sizeFor(Ratio(16, 9), 1))
        assertEquals(Size2(21, 9), RatioMath.sizeFor(Ratio(21, 9), 1))
    }

    // ---- k = 0 means "device maximum" (fresh-install regression) ----

    @Test
    fun `k of zero resolves to the device maximum, never to the minimum`() {
        // Fresh install stores ResolutionSpec(ratio = 3:4, k = 0), meaning
        // "largest size this device supports". Regression: this used to be
        // read as k = 1, rendering preview AND saved photos into a 3x4 pixel
        // frame (an "extremely blurred, no image" viewfinder).
        // Portrait box 1080x1440 with ratio 3:4: k = min(1080/3, 1440/4) = 360.
        assertEquals(360, RatioMath.effectiveK(Ratio(3, 4), 0, 1080, 1440))
        assertEquals(Size2(1080, 1440), RatioMath.sizeForEffective(Ratio(3, 4), 0, 1080, 1440))

        // Landscape 4:3 in the same portrait box is width-limited: k = 270
        // -> 1080x810 (an exact 4:3 multiple, never stretched to fill).
        assertEquals(270, RatioMath.effectiveK(Ratio(4, 3), 0, 1080, 1440))
        assertEquals(Size2(1080, 810), RatioMath.sizeForEffective(Ratio(4, 3), 0, 1080, 1440))

        // Negative k is also "device maximum"
        assertEquals(360, RatioMath.effectiveK(Ratio(3, 4), -1, 1080, 1440))

        // Explicit k still clamps into the box
        assertEquals(360, RatioMath.effectiveK(Ratio(3, 4), 10_000, 1080, 1440))
        assertEquals(64, RatioMath.effectiveK(Ratio(3, 4), 64, 1080, 1440))

        // On a 1x1-limited box the "device maximum" is literally 1x1
        assertEquals(1, RatioMath.effectiveK(Ratio(1, 1), 0, 1, 1))
        assertEquals(Size2(1, 1), RatioMath.sizeForEffective(Ratio(1, 1), 0, 1, 1))

        // Landscape box (caps not yet known): 1920x1440 with 4:3 -> k = 480
        assertEquals(Size2(1920, 1440), RatioMath.sizeForEffective(Ratio(4, 3), 0, 1920, 1440))
    }

    // ---- ratio switching never stretches ----

    @Test
    fun `switching 1x1 to 4-3 to 16-9 keeps exact ratios`() {
        // 1000x1000 -> 4:3 preserves height ~1000 -> k=333 -> 1332x999
        var size = Size2(1000, 1000)
        size = RatioMath.switchRatio(size, Ratio(4, 3), 4000, 3000)
        assertEquals(Size2(1332, 999), size)
        assertEquals(4.0 / 3.0, size.w.toDouble() / size.h, 1e-12)

        // -> 16:9 preserves height ~999 -> k=111 -> 1776x999
        size = RatioMath.switchRatio(size, Ratio(16, 9), 4000, 3000)
        assertEquals(Size2(1776, 999), size)
        assertEquals(16.0 / 9.0, size.w.toDouble() / size.h, 1e-12)

        // -> 3:4 (portrait): k = round(999/4) = 250 -> 750x1000
        size = RatioMath.switchRatio(size, Ratio(3, 4), 4000, 3000)
        assertEquals(Size2(750, 1000), size)
        assertEquals(3.0 / 4.0, size.w.toDouble() / size.h, 1e-12)
    }

    @Test
    fun `switching ratios clamps to the device box`() {
        val size = RatioMath.switchRatio(Size2(2000, 2000), Ratio(21, 9), 4000, 3000)
        // k limited by height: 3000/9 = 333 -> 6993x2997 exceeds 4000 wide ->
        // k = 4000/21 = 190 -> 3990x1710
        assertEquals(Size2(3990, 1710), size)
    }

    // ---- clamping ----

    @Test
    fun `locked sizes clamp to device max while staying exact`() {
        val s = RatioMath.sizeForClamped(Ratio(4, 3), 10_000, 4000, 3000)
        assertEquals(Size2(4000, 3000), s)
    }

    @Test
    fun `free sizes clamp uniformly without stretching`() {
        val s = RatioMath.clampFree(5000, 4000, 4000, 3000)
        assertTrue(s.w <= 4000 && s.h <= 3000)
        assertEquals(5000.0 / 4000, s.w.toDouble() / s.h, 0.01)
        assertEquals(Size2(1, 1), RatioMath.clampFree(1, 1, 4000, 3000))
    }

    // ---- slider mapping ----

    @Test
    fun `slider reaches 1 and kmax exactly`() {
        val kMax = 1000
        assertEquals(1, RatioMath.sliderToK(0f, kMax))
        assertEquals(kMax, RatioMath.sliderToK(1f, kMax))
        // first quarter of the slider sweeps the tiny sizes
        assertEquals(64, RatioMath.sliderToK(0.25f, kMax))
        for (t in 1..99) {
            val k = RatioMath.sliderToK(t / 100f, kMax)
            assertTrue(k in 1..kMax)
            // monotonic
            assertTrue(k >= RatioMath.sliderToK((t - 1) / 100f, kMax))
        }
        // round trip
        assertEquals(1.0, RatioMath.kToSlider(RatioMath.sliderToK(1f, kMax), kMax).toDouble(), 1e-6)
        assertEquals(0.0, RatioMath.kToSlider(1, kMax).toDouble(), 1e-6)
    }

    @Test
    fun `quick picks snap to valid multiples`() {
        // 320x240 at 4:3 -> k = 80
        val k = RatioMath.kForTarget(Ratio(4, 3), 320, 240, 4000, 3000)
        assertEquals(80, k)
        // 640x480 -> k = 160
        assertEquals(160, RatioMath.kForTarget(Ratio(4, 3), 640, 480, 4000, 3000))
    }

    // ---- encoder clamp ----

    @Test
    fun `encoder clamp produces even in-range sizes`() {
        val s = RatioMath.encoderClamp(3, 3, 16, 16, 4096, 4096)
        assertTrue(s.w % 2 == 0 && s.h % 2 == 0)
        assertTrue(s.w >= 16 && s.h >= 16)
        val big = RatioMath.encoderClamp(5000, 4000, 16, 16, 3840, 2160)
        assertTrue(big.w <= 3840 && big.h <= 2160)
        assertEquals(5000.0 / 4000, big.w.toDouble() / big.h, 0.02)
    }

    // ---- framing: field of view invariance ----

    @Test
    fun `crop scale depends only on ratio, never on pixel size`() {
        val lo = Framing.cropScale(4000, 3000, 90, 320, 240)
        val hi = Framing.cropScale(4000, 3000, 90, 4000, 3000)
        // Same ratio (4:3) at tiny and huge sizes => identical crop => identical FOV
        assertEquals(lo.first.toDouble(), hi.first.toDouble(), 1e-9)
        assertEquals(lo.second.toDouble(), hi.second.toDouble(), 1e-9)

        val square = Framing.cropScale(4000, 3000, 90, 1000, 1000)
        // Upright frame is 3000x4000 (portrait). Target 1:1 => crop to 3000x3000
        assertEquals(1.0, square.first.toDouble(), 1e-6)
        assertEquals(0.75, square.second.toDouble(), 1e-6)
    }

    @Test
    fun `view-to-stream mapping inverts the shader math`() {
        // Portrait back camera: sensor 90, display 0 -> rot 90.
        val rot = 90
        val (csx, csy) = Framing.cropScale(4000, 3000, rot, 1000, 1000)
        // Tap the center of the viewfinder
        val stream = Framing.viewToStream(
            540f, 960f, 1080f, 1920f, 1000f, 1000f, rot, false, csx, csy
        )
        assertTrue(stream != null)
        val (sx, sy) = stream!!
        // Center of the target maps to center of the stream
        assertEquals(0.5, sx.toDouble(), 0.01)
        assertEquals(0.5, sy.toDouble(), 0.01)

        // Round trip: stream -> target must land in [0,1]
        val back = Framing.streamToTarget(sx, sy, rot, false, csx, csy)
        assertTrue(back.first in 0f..1f && back.second in 0f..1f)

        // Corner: viewfinder top-left of the visible frame maps inside the
        // cropped region; outside the letterbox returns null
        val outside = Framing.viewToStream(
            1f, 1f, 1080f, 1920f, 1000f, 1000f, rot, false, csx, csy
        )
        assertTrue(outside == null)
    }

    @Test
    fun `rot 90 top of the frame comes from the left of the stream`() {
        // Corner-derived ground truth: rotating the sensor buffer clockwise
        // by 90 makes the buffer's LEFT edge the top of the upright image.
        val rot = 90
        val (csx, csy) = Framing.cropScale(4000, 3000, rot, 1000, 1000)
        // View 1080x1920 with a 1:1 frame: frame occupies y in [420, 1500].
        // Tap just inside the TOP-center of the visible frame.
        val top = Framing.viewToStream(
            540f, 421f, 1080f, 1920f, 1000f, 1000f, rot, false, csx, csy
        )!!
        // Stream s must be near 0 (left edge); crop=1.0/0.75 puts the
        // sampled band at s in [0.125, 0.875] with the top at s ~ 0.125.
        assertEquals(0.125, top.first.toDouble(), 0.01)
        assertEquals(0.5, top.second.toDouble(), 0.05)
        // Tap just inside the BOTTOM-center: comes from the RIGHT edge.
        val bottom = Framing.viewToStream(
            540f, 1499f, 1080f, 1920f, 1000f, 1000f, rot, false, csx, csy
        )!!
        assertEquals(0.875, bottom.first.toDouble(), 0.01)
        assertEquals(0.5, bottom.second.toDouble(), 0.05)
    }

    @Test
    fun `taps at every frame corner stay inside the cropped stream`() {
        // Regression: the old un-crop divided instead of multiplying, so
        // taps near any frame edge returned null whenever a crop was active
        // (e.g. a 1:1 target from a 4:3 stream) - dead tap-to-focus zones.
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val (csx, csy) = Framing.cropScale(4000, 3000, rot, 1000, 1000)
            for ((x, y) in listOf(
                540f to 421f, 1075f to 421f, 540f to 1499f, 1075f to 1499f
            )) {
                val r = Framing.viewToStream(
                    x, y, 1080f, 1920f, 1000f, 1000f, rot, false, csx, csy
                )
                assertTrue("rot=$rot tap=($x,$y) must be focusable", r != null)
                assertTrue(r!!.first in 0f..1f && r.second in 0f..1f)
                // And the round trip returns into the frame.
                val back = Framing.streamToTarget(r.first, r.second, rot, false, csx, csy)
                assertTrue(back.first in 0f..1f && back.second in 0f..1f)
            }
        }
    }

    @Test
    fun `round trip is identity for all rotations with and without crop`() {
        for (rot in intArrayOf(0, 90, 180, 270)) {
            for (crop in listOf(1f to 1f, 1f to 0.75f, 0.75f to 1f)) {
                for (mirror in listOf(false, true)) {
                    for ((u, ty) in listOf(0.2f to 0.3f, 0.8f to 0.25f, 0.35f to 0.85f, 0.9f to 0.9f)) {
                        // Build a tap position from the target uv directly:
                        // view 1000x1000, frame fills it.
                        val s = Framing.viewToStream(
                            u * 1000f, ty * 1000f, 1000f, 1000f, 1000f, 1000f,
                            rot, mirror, crop.first, crop.second
                        )!!
                        val back = Framing.streamToTarget(
                            s.first, s.second, rot, mirror, crop.first, crop.second
                        )
                        assertEquals("rot=$rot crop=$crop mirror=$mirror u=$u",
                            u.toDouble(), back.first.toDouble(), 0.01)
                        assertEquals(ty.toDouble(), back.second.toDouble(), 0.01)
                    }
                }
            }
        }
    }

    @Test
    fun `mirror flips the horizontal axis only`() {
        val rot = 0
        val (csx, csy) = Framing.cropScale(4000, 3000, rot, 4000, 3000)
        val a = Framing.viewToStream(100f, 500f, 1000f, 1000f, 4000f, 3000f, rot, false, csx, csy)!!
        val b = Framing.viewToStream(100f, 500f, 1000f, 1000f, 4000f, 3000f, rot, true, csx, csy)!!
        assertEquals(a.second.toDouble(), b.second.toDouble(), 1e-6)
        assertEquals((1f - a.first).toDouble(), b.first.toDouble(), 1e-6)
    }

    @Test
    fun `reduce produces canonical ratios`() {
        assertEquals(Ratio(4, 3), RatioMath.reduce(4000, 3000))
        assertEquals(Ratio(1, 1), RatioMath.reduce(999, 999))
        assertEquals(Ratio(7, 3), RatioMath.reduce(42, 18))
        assertEquals(Ratio(7, 3), RatioMath.reduce(210, 90))
    }

    @Test
    fun `megapixel labels`() {
        assertEquals("1.3 MP", RatioMath.megapixelsLabel(1332, 999))
        assertEquals("307 KP", RatioMath.megapixelsLabel(640, 480))
    }
}
