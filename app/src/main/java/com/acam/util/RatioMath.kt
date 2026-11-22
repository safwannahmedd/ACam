package com.acam.util

import com.acam.data.Ratio
import com.acam.data.Size2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Pure integer math for the resolution / aspect-ratio system.
 *
 * Rules enforced here (see project spec, section 7):
 *  - In locked mode every size is an exact integer multiple of the ratio:
 *    W = k*a, H = k*b for an integer k >= 1.
 *  - The minimum reachable size at any ratio is k = 1 (1x1, 4x3, 16x9, ...).
 *  - Switching ratios recomputes W and H from the ratio (never stretches):
 *    we keep the previous height approximately and re-snap k.
 *  - Sizes are clamped to the device maximum (per-ratio crop of the camera
 *    stream) so the field of view NEVER changes with resolution.
 *
 * All functions are pure and unit tested (see RatioMathTest).
 */
object RatioMath {

    fun gcd(a: Int, b: Int): Int {
        var x = kotlin.math.abs(a)
        var y = kotlin.math.abs(b)
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return max(x, 1)
    }

    fun reduce(a: Int, b: Int): Ratio {
        require(a > 0 && b > 0) { "Ratio parts must be positive" }
        val g = gcd(a, b)
        return Ratio(a / g, b / g)
    }

    /** Exact size for ratio at multiple k (k >= 1). */
    fun sizeFor(r: Ratio, k: Int): Size2 {
        val kk = max(k, 1)
        return Size2(kk * r.a, kk * r.b)
    }

    /** Largest valid k so that k*a <= maxW and k*b <= maxH. Always >= 1. */
    fun maxK(r: Ratio, maxW: Int, maxH: Int): Int =
        max(1, min(maxW / r.a, maxH / r.b))

    fun kClamped(r: Ratio, k: Int, maxW: Int, maxH: Int): Int =
        min(max(k, 1), maxK(r, maxW, maxH))

    /**
     * The k actually in effect. A stored k <= 0 means "device maximum"
     * (largest valid k for the device box) — it must NEVER be read as the
     * literal minimum k=1, or the whole pipeline would render into a 4x3
     * pixel frame.
     */
    fun effectiveK(r: Ratio, k: Int, maxW: Int, maxH: Int): Int =
        kClamped(r, if (k <= 0) maxK(r, maxW, maxH) else k, maxW, maxH)

    /** Size for k, clamped into the device box while staying an exact multiple. */
    fun sizeForClamped(r: Ratio, k: Int, maxW: Int, maxH: Int): Size2 =
        sizeFor(r, kClamped(r, k, maxW, maxH))

    /**
     * Size for a stored spec value (k <= 0 = device max), always an exact
     * ratio multiple inside the device box. This is the one resolver used by
     * the render pipeline, the capture path and the UI so they can never
     * disagree.
     */
    fun sizeForEffective(r: Ratio, k: Int, maxW: Int, maxH: Int): Size2 =
        sizeFor(r, effectiveK(r, k, maxW, maxH))

    /**
     * Ratio switch without stretching: keep the current height approximately
     * and re-derive the integer multiple. E.g. 1000x1000 (1:1) -> 4:3 keeps
     * H ~ 1000 => k = 333 => 1332x999 (an exact 4:3 multiple; the spec's
     * illustrative 1333x1000 is not an exact ratio multiple, exactness wins).
     */
    fun switchRatio(current: Size2, newRatio: Ratio, maxW: Int, maxH: Int): Size2 {
        val targetK = (current.h.toFloat() / newRatio.b).roundToInt()
        return sizeFor(newRatio, kClamped(newRatio, targetK, maxW, maxH))
    }

    /** k whose area is closest to the target pixel count. */
    fun kForArea(r: Ratio, targetPixels: Long, maxW: Int, maxH: Int): Int {
        if (targetPixels <= 0) return 1
        val k = sqrt(targetPixels.toDouble() / (r.a.toDouble() * r.b.toDouble()))
        return kClamped(r, max(k.roundToInt(), 1), maxW, maxH)
    }

    /** Smallest k whose size covers (>=) the requested dimensions. */
    fun kForTarget(r: Ratio, targetW: Int, targetH: Int, maxW: Int, maxH: Int): Int {
        val ka = if (r.a == 0) 1 else targetW / r.a + (if (targetW % r.a != 0) 1 else 0)
        val kb = if (r.b == 0) 1 else targetH / r.b + (if (targetH % r.b != 0) 1 else 0)
        return kClamped(r, max(max(ka, kb), 1), maxW, maxH)
    }

    /** Device maximum at a ratio (crop of the max box, still exact multiple). */
    fun maxSize(r: Ratio, maxW: Int, maxH: Int): Size2 = sizeFor(r, maxK(r, maxW, maxH))

    /**
     * Free (unlocked) mode: arbitrary W x H, clamped to the device box with a
     * uniform scale so nothing is stretched. At least 1x1.
     */
    fun clampFree(w: Int, h: Int, maxW: Int, maxH: Int): Size2 {
        var cw = max(w, 1)
        var ch = max(h, 1)
        if (cw > maxW || ch > maxH) {
            val s = min(maxW.toFloat() / cw, maxH.toFloat() / ch)
            cw = max((cw * s).roundToInt(), 1)
            ch = max((ch * s).roundToInt(), 1)
            // final safety clamp
            cw = min(cw, maxW)
            ch = min(ch, maxH)
        }
        return Size2(cw, ch)
    }

    /**
     * Map a slider position in [0,1] to k in [1, kMax] on a piecewise-log
     * curve: the first quarter of the slider sweeps 1..64 (the fun tiny
     * sizes), the rest sweeps 64..kMax logarithmically.
     */
    fun sliderToK(t: Float, kMax: Int): Int {
        val tt = t.coerceIn(0f, 1f)
        val km = max(kMax, 1)
        val k = if (tt < 0.25f) {
            // 0.00 -> 1, 0.25 -> 64
            (1f + (64f - 1f) * (tt / 0.25f)).roundToInt()
        } else {
            val f = (tt - 0.25f) / 0.75f
            (64f * Math.pow((km / 64f).toDouble(), f.toDouble())).roundToInt()
        }
        return min(max(k, 1), km)
    }

    /** Inverse of [sliderToK]. */
    fun kToSlider(k: Int, kMax: Int): Float {
        val kk = max(k, 1)
        val km = max(kMax, 1)
        return if (kk <= 64 || km <= 64) {
            ((kk - 1f) / 63f * 0.25f).coerceIn(0f, 1f)
        } else {
            val f = Math.log((kk / 64f).toDouble()) / Math.log((km / 64f).toDouble())
            (0.25f + 0.75f * f.toFloat()).coerceIn(0f, 1f)
        }
    }

    /**
     * Video encoder clamp: some encoders demand even and/or minimum/maximum
     * dimensions. Find the closest valid size to the requested one, scaling
     * UNIFORMLY so the aspect is preserved (the pipeline renders at the
     * requested size and nearest-neighbor upscales into this).
     */
    fun encoderClamp(w: Int, h: Int, minW: Int, minH: Int, maxW: Int, maxH: Int): Size2 {
        val even = { v: Int -> if (v % 2 == 0) v else v + 1 }
        var cw = even(w)
        var ch = even(h)
        // Uniform scale into the box - never distort one axis only.
        if (cw > maxW || ch > maxH) {
            val s = min(maxW.toFloat() / cw, maxH.toFloat() / ch)
            cw = even((cw * s).roundToInt())
            ch = even((ch * s).roundToInt())
        }
        // Respect encoder minimums.
        if (cw < even(minW)) cw = even(minW)
        if (ch < even(minH)) ch = even(minH)
        cw = min(cw, even(maxW))
        ch = min(ch, even(maxH))
        return Size2(cw, ch)
    }

    fun megapixelsLabel(w: Int, h: Int): String {
        val mp = (w.toLong() * h.toLong()) / 1_000_000.0
        return when {
            mp >= 1.0 -> String.format("%.1f MP", mp)
            (w.toLong() * h.toLong()) >= 1_000 -> String.format("%.0f KP", mp * 1000)
            else -> "${w * h} px"
        }
    }
}

/**
 * Pure math for framing: how the camera stream is center-cropped to the
 * target ratio, and how taps on the viewfinder map back to stream pixels.
 * The crop depends ONLY on (stream size, rotation, target ratio) — never on
 * the target pixel size — which is what guarantees "same field of view at
 * every resolution".
 */
object Framing {

    /** Crop scale (cx, cy) <= 1 applied in rotated (upright) UV space. */
    fun cropScale(streamW: Int, streamH: Int, rotationDeg: Int, targetW: Int, targetH: Int): Pair<Float, Float> {
        val rot = ((rotationDeg % 360) + 360) % 360
        val rw = if (rot == 90 || rot == 270) streamH else streamW
        val rh = if (rot == 90 || rot == 270) streamW else streamH
        if (rw <= 0 || rh <= 0 || targetW <= 0 || targetH <= 0) return 1f to 1f
        val sa = rw.toFloat() / rh
        val ta = targetW.toFloat() / targetH
        return if (sa > ta) Pair(ta / sa, 1f) else Pair(1f, sa / ta)
    }

    /**
     * View tap -> target-frame uv in GL-natural orientation (v = 0 at the
     * frame BOTTOM), inside the letterboxed frame the renderer blits to
     * the view. Returns null when the tap is outside the frame. This is
     * the orientation-independent first half of [viewToStream]; the exact
     * stream mapping is then done by inverting the renderer's own UV
     * chain (see GlPipe.mapTargetToStream) so it stays correct on every
     * device - including the ones whose SurfaceTexture transform already
     * carries the sensor rotation (the 1.2.7 fix).
     */
    fun viewToFrameUv(
        x: Float, y: Float, viewW: Float, viewH: Float,
        frameW: Float, frameH: Float
    ): Pair<Float, Float>? {
        if (viewW <= 0f || viewH <= 0f || frameW <= 0f || frameH <= 0f) return null
        val va = viewW / viewH
        val fa = frameW / frameH
        val cw: Float
        val ch: Float
        if (fa > va) {
            cw = viewW
            ch = viewW / fa
        } else {
            ch = viewH
            cw = viewH * fa
        }
        val ox = (viewW - cw) / 2f
        val oy = (viewH - ch) / 2f
        if (x < ox || x > ox + cw || y < oy || y > oy + ch) return null
        val u = (x - ox) / cw               // target x, left..right
        val ty = (y - oy) / ch              // target y, top..bottom
        return u to (1f - ty)               // GL-natural: v = 0 at bottom
    }

    /**
     * Map a view tap (view pixels, top-left origin) to stream pixels
     * (sensor-oriented buffer, (0,0) = top-left of the buffer, which is
     * what CameraX metering expects).
     * Returns null when the tap is outside the letterboxed frame.
     *
     * This is the EXACT inverse of the renderer's UV chain
     * FLIP_Y * R((360-rot)%360) * C * M applied to GL-natural target uv
     * (v = 0 = image bottom), derived corner by corner and locked in by
     * the unit tests. See Mat4's docs for why the rotation direction is
     * inverted versus the image-space rotation.
     *
     * NOTE (1.2.7): the live renderer now follows the device's actual
     * SurfaceTexture transform, which on some stacks already includes the
     * sensor rotation; the UI layer therefore maps taps through
     * [viewToFrameUv] + GlPipe.mapTargetToStream (the inverted real chain)
     * instead. This function remains the pure, unit-tested reference for
     * the classic sensor-oriented chain.
     */
    fun viewToStream(
        x: Float, y: Float, viewW: Float, viewH: Float,
        frameW: Float, frameH: Float,
        rotationDeg: Int, mirror: Boolean,
        cropX: Float, cropY: Float
    ): Pair<Float, Float>? {
        val frameUv = viewToFrameUv(x, y, viewW, viewH, frameW, frameH)
            ?: return null
        val u = frameUv.first                    // target x, left..right
        val v = frameUv.second                   // GL-natural: v = 0 at bottom
        if (cropX <= 0f || cropY <= 0f) return null
        val mu = if (mirror) 1f - u else u
        // Crop: target uv -> full-upright uv (the same forward crop the
        // shader applies when sampling; cs < 1 narrows the sampled region).
        val xc = (mu - 0.5f) * cropX + 0.5f
        val yc = (v - 0.5f) * cropY + 0.5f
        if (xc < 0f || xc > 1f || yc < 0f || yc > 1f) return null
        // Rotate upright -> stream, then account for the top-down texture
        // rows (the renderer's FLIP_Y).
        val rot = ((rotationDeg % 360) + 360) % 360
        return when (rot) {
            0 -> Pair(xc, 1f - yc)
            90 -> Pair(1f - yc, 1f - xc)
            180 -> Pair(1f - xc, yc)
            else -> Pair(yc, xc)          // 270
        }
    }

    /**
     * Forward map used by unit tests to verify [viewToStream] against the
     * exact shader math: stream uv -> target uv (top-left origin).
     */
    fun streamToTarget(
        sxIn: Float, syIn: Float, rotationDeg: Int, mirror: Boolean,
        cropX: Float, cropY: Float
    ): Pair<Float, Float> {
        val rot = ((rotationDeg % 360) + 360) % 360
        val xc: Float
        val yc: Float
        when (rot) {
            0 -> { xc = sxIn; yc = 1f - syIn }
            90 -> { xc = 1f - syIn; yc = 1f - sxIn }
            180 -> { xc = 1f - sxIn; yc = syIn }
            else -> { xc = syIn; yc = sxIn }   // 270
        }
        var mu = (xc - 0.5f) / cropX + 0.5f
        val v = (yc - 0.5f) / cropY + 0.5f
        if (mirror) mu = 1f - mu
        return mu to (1f - v)
    }
}
