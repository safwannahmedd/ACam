package com.acam.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * On-device camera diagnostics, runnable from Settings without adb.
 *
 * Motivation: a class of budget camera HALs produces degenerate frames
 * (flat color fields, half-written buffers, stripes) in some or all
 * session configurations, and no amount of app-side rendering logic can
 * be debugged against them without knowing what the HAL actually
 * delivered. The suite binds several session shapes, and for each one
 * saves EXACTLY what the camera produced, before any app processing:
 *
 *   1. YUV analysis alone at 1280x960 (the default viewfinder config)
 *   2. YUV analysis alone at 640x480
 *   3. YUV analysis alone at 1920x1080
 *   4. JPEG still capture alone (raw JPEG bytes, untouched)
 *   5. analysis + still capture together (the classic two-stream session
 *      that breaks on those HALs)
 *
 * For every analysis test it saves the raw Y/U/V planes as grayscale
 * PNGs at their true row-stride widths (padding included), an honest
 * CPU-rendered RGB frame (bypassing the whole GL pipeline - same BT.601
 * math as the shader), and a JSON description of every plane parameter.
 * Everything lands in Pictures/ACam-Diagnostics for easy sharing.
 */
class Diagnostics(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val provider: androidx.camera.lifecycle.ProcessCameraProvider,
    private val selector: CameraSelector,
    private val entry: LensEntry,
    private val executor: Executor,
    private val mainHandler: Handler
) {

    companion object {
        private const val TAG = "Diagnostics"
        private const val FOLDER = "ACam-Diagnostics"
    }

    /** Analyzer callbacks and capture callbacks run here (never the
     *  blocking diag thread). */
    private val analysisExecutor: Executor = Executors.newSingleThreadExecutor()

    /** Raw copy of one ImageProxy's planes, made on the analyzer thread. */
    private class PlaneDump(
        val rowStride: Int,
        val pixelStride: Int,
        val bufferPosition: Int,
        val bufferRemaining: Int,
        val bytes: ByteArray            // bytes[0] == the byte at bufferPosition
    )

    private class FrameDump(
        val width: Int,
        val height: Int,
        val rotationDeg: Int,
        val timestampNs: Long,
        val planes: Array<PlaneDump>
    ) {
        constructor(image: ImageProxy) : this(
            image.width, image.height,
            image.imageInfo.rotationDegrees,
            image.imageInfo.timestamp,
            Array(image.planes.size) { i ->
                val p = image.planes[i]
                val buf = p.buffer.duplicate()
                PlaneDump(
                    p.rowStride, p.pixelStride, buf.position(), buf.remaining(),
                    ByteArray(buf.remaining()).also { buf.get(it) }
                )
            }
        )
    }

    /** One completed test's artifacts + stats. */
    private class TestResult(
        val label: String,
        val layoutJson: JSONObject?,
        val yStats: String,
        val note: String
    )

    fun run(onProgress: (String) -> Unit, onDone: (String) -> Unit) {
        val results = mutableListOf<TestResult>()
        val t0 = System.currentTimeMillis()

        fun progress(msg: String) = mainHandler.post { onProgress(msg) }

        executor.execute {
            try {
                // ---- tests 1-3: analysis alone at each ladder size ----
                val sizes = listOf(Size(1280, 960), Size(640, 480), Size(1920, 1080))
                sizes.forEachIndexed { i, size ->
                    progress("Viewfinder test ${i + 1}/5: analysis ${size.width}x${size.height}")
                    val frames = collectAnalysis(size, wantFrames = 3, settleMs = 900)
                    results.add(renderAnalysisTest("analysis_${size.width}x${size.height}", frames))
                }

                // ---- test 4: still capture alone ----
                progress("Still test 4/5: JPEG capture alone")
                results.add(runStillTest("still_alone", withAnalysis = null))

                // ---- test 5: the classic two-stream combo ----
                progress("Combo test 5/5: viewfinder + still together")
                val comboJpeg = arrayOfNulls<ByteArray>(1)
                val comboFrame = collectAnalysis(Size(1280, 960), 3, 900, withStill = true, jpegOut = comboJpeg)
                val comboRes = renderAnalysisTest("combo_analysis", comboFrame)
                results.add(comboRes)
                val cj = comboJpeg[0]
                if (cj != null) {
                    saveBytes("combo_still_raw.jpg", "image/jpeg", cj)
                    val cd = runCatching {
                        android.graphics.BitmapFactory.decodeByteArray(cj, 0, cj.size)
                    }.getOrNull()
                    if (cd != null) saveBitmap("combo_still_decoded.png", scaleDown(cd, 720))
                    results.add(
                        TestResult("combo_still", null, "",
                            "JPEG ${cj.size}B, decoded ${cd?.width ?: "?"}x${cd?.height ?: "?"}")
                    )
                } else {
                    results.add(TestResult("combo_still", null, "", "no JPEG in combo session"))
                }

                writeSummary(results, System.currentTimeMillis() - t0)
                val flat = results.joinToString("\n") { "${it.label}: ${it.note}" }
                mainHandler.post {
                    onDone("Diagnostics saved to Pictures/$FOLDER\n\n$flat")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "diagnostics failed", t)
                writeSummary(results + TestResult("error", null, "", t.message ?: "failed"), System.currentTimeMillis() - t0)
                mainHandler.post { onDone("Diagnostics failed: ${t.message}\nPartial results in Pictures/$FOLDER") }
            } finally {
                mainHandler.post { runCatching { provider.unbindAll() } }
            }
        }
    }

    // ------------------------------------------------------- frame collection

    private fun buildAnalysis(size: Size): ImageAnalysis {
        val res = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(res)
            .build()
    }

    private fun buildStill(): ImageCapture {
        val res = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        return ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(res)
            .build()
    }

    /**
     * Bind an analysis use case (plus optionally a still use case), let the
     * session settle, then collect raw frame dumps. Returns the LAST frame
     * (early ones can be transitional while 3A locks). If [jpegOut] is
     * non-null and a still use case was bound, one JPEG is taken in the
     * same session and stored there.
     */
    private fun collectAnalysis(
        size: Size,
        wantFrames: Int,
        settleMs: Long,
        withStill: Boolean = false,
        jpegOut: Array<ByteArray?>? = null
    ): FrameDump? {
        val latch = CountDownLatch(wantFrames)
        val collected = ArrayDeque<FrameDump>(2)
        // Built on this thread, only READ inside the main-thread lambda:
        // Handler.post provides the happens-before edge, and this thread
        // keeps its own reference for the in-session JPEG below.
        val still = if (withStill) buildStill() else null
        mainHandler.post {
            runCatching {
                provider.unbindAll()
                val analysis = buildAnalysis(size)
                analysis.setAnalyzer(analysisExecutor) { image ->
                    try {
                        val dump = FrameDump(image)
                        image.close()
                        synchronized(collected) {
                            if (collected.size >= 2) collected.removeFirst()
                            collected.addLast(dump)
                        }
                    } catch (t: Throwable) {
                        image.close()
                    } finally {
                        latch.countDown()
                    }
                }
                if (still != null) {
                    provider.bindToLifecycle(lifecycleOwner, selector, analysis, still)
                } else {
                    provider.bindToLifecycle(lifecycleOwner, selector, analysis)
                }
            }.onFailure { Log.e(TAG, "bind analysis $size failed", it) }
        }
        // Wait for the requested frames (or timeout), then extra settle time
        // so the collected frame is post-3A-lock, not the first garbage.
        latch.await(8, TimeUnit.SECONDS)
        Thread.sleep(settleMs)
        if (jpegOut != null && still != null) {
            val jpegLatch = CountDownLatch(1)
            still.takePicture(analysisExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buf = image.planes.firstOrNull()?.buffer
                        if (buf != null && buf.remaining() > 0) {
                            jpegOut[0] = ByteArray(buf.remaining()).also { buf.get(it) }
                        }
                    } finally {
                        image.close()
                        jpegLatch.countDown()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "combo jpeg failed", exception)
                    jpegLatch.countDown()
                }
            })
            jpegLatch.await(10, TimeUnit.SECONDS)
        }
        mainHandler.post { runCatching { provider.unbindAll() } }
        return synchronized(collected) { collected.lastOrNull() }
    }

    /** Still-capture test: save the raw JPEG bytes and a decode thumbnail. */
    private fun runStillTest(label: String, withAnalysis: Size?): TestResult {
        val jpegHolder = arrayOfNulls<ByteArray>(1)
        val metaHolder = arrayOfNulls<Triple<Int, Int, Int>?>(1) // w, h, rotation
        val error = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)

        mainHandler.post {
            runCatching {
                provider.unbindAll()
                val still = buildStill()
                if (withAnalysis != null) {
                    val analysis = buildAnalysis(withAnalysis)
                    provider.bindToLifecycle(lifecycleOwner, selector, analysis, still)
                } else {
                    provider.bindToLifecycle(lifecycleOwner, selector, still)
                }
                mainHandler.postDelayed({
                    still.takePicture(analysisExecutor, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val buf = image.planes.firstOrNull()?.buffer
                                if (buf != null && buf.remaining() > 0) {
                                    jpegHolder[0] = ByteArray(buf.remaining()).also { buf.get(it) }
                                    metaHolder[0] = Triple(image.width, image.height, image.imageInfo.rotationDegrees)
                                }
                            } finally {
                                image.close()
                                latch.countDown()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            error[0] = exception.message ?: "capture error"
                            latch.countDown()
                        }
                    })
                }, 1000L)
            }.onFailure {
                error[0] = it.message ?: "bind failed"
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)

        val jpeg = jpegHolder[0]
        if (jpeg == null) {
            return TestResult(label, null, "", "FAILED: ${error[0] ?: "no JPEG delivered"}")
        }
        saveBytes("${label}_raw.jpg", "image/jpeg", jpeg)
        val meta = metaHolder[0]
        val decoded = runCatching {
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        }.getOrNull()
        if (decoded == null) {
            return TestResult(label, null, "", "JPEG delivered (${jpeg.size} bytes) but DECODE FAILED - corrupt stream")
        }
        saveBitmap("${label}_decoded.png", scaleDown(decoded, 720))
        val json = JSONObject()
            .put("jpegBytes", jpeg.size)
            .put("bufferWidth", meta?.first ?: 0)
            .put("bufferHeight", meta?.second ?: 0)
            .put("rotationDegrees", meta?.third ?: 0)
            .put("decodedWidth", decoded.width)
            .put("decodedHeight", decoded.height)
        saveText("${label}_layout.json", json.toString(2))
        return TestResult(
            label, json, "",
            "JPEG ${jpeg.size}B, decoded ${decoded.width}x${decoded.height}"
        )
    }

    // ------------------------------------------------------- analysis rendering

    /** Turn one analysis test's frames into PNGs + JSON + a stats note. */
    private fun renderAnalysisTest(label: String, frame: FrameDump?): TestResult {
        if (frame == null) {
            return TestResult(label, null, "", "NO FRAMES DELIVERED at all")
        }
        if (frame.planes.size < 3) {
            return TestResult(label, null, "", "unexpected plane count ${frame.planes.size}")
        }
        val y = frame.planes[0]
        val u = frame.planes[1]
        val v = frame.planes[2]
        val semi = u.pixelStride == 2

        // ---- raw plane PNGs at their true stride widths ----
        saveBitmap("${label}_y_plane.png", planeBitmap(y, frame.width, frame.height))
        if (semi) {
            saveBitmap("${label}_uv_plane.png", planeBitmap(u, (frame.width + 1) / 2, (frame.height + 1) / 2, 2))
        } else {
            saveBitmap("${label}_u_plane.png", planeBitmap(u, (frame.width + 1) / 2, (frame.height + 1) / 2))
            saveBitmap("${label}_v_plane.png", planeBitmap(v, (frame.width + 1) / 2, (frame.height + 1) / 2))
        }

        // ---- honest CPU RGB render (no GL involved) ----
        saveBitmap("${label}_rgb_cpu.png", cpuRenderRgb(frame))

        // ---- Y plane statistics over the valid pixels ----
        val stats = planeStats(y, frame.width, frame.height)

        val json = JSONObject()
            .put("width", frame.width)
            .put("height", frame.height)
            .put("rotationDegrees", frame.rotationDeg)
            .put("timestampNs", frame.timestampNs)
            .put("layout", if (semi) "semi-planar (NV12/NV21)" else "planar (I420)")
            .put("yRowStride", y.rowStride)
            .put("yPixelStride", y.pixelStride)
            .put("yBufferPosition", y.bufferPosition)
            .put("yBufferRemaining", y.bufferRemaining)
            .put("uRowStride", u.rowStride)
            .put("uPixelStride", u.pixelStride)
            .put("uBufferPosition", u.bufferPosition)
            .put("uBufferRemaining", u.bufferRemaining)
            .put("vRowStride", v.rowStride)
            .put("vPixelStride", v.pixelStride)
            .put("vBufferPosition", v.bufferPosition)
            .put("vBufferRemaining", v.bufferRemaining)
            .put("yStats", JSONObject().put("min", stats[0]).put("max", stats[1]).put("mean", stats[2]).put("std", stats[3]))
        saveText("${label}_layout.json", json.toString(2))

        val flat = stats[0] == stats[1]
        val note = if (flat) {
            "DEGENERATE: perfectly flat plane (min=max=${stats[0]}) - the HAL wrote constant data"
        } else {
            "healthy luma spread (min=${stats[0]}, max=${stats[1]}, std=%.2f)".format(stats[3])
        }
        return TestResult(label, json, stats.joinToString(","), note)
    }

    /** Grayscale bitmap of a plane's raw bytes: width = rowStride so any
     *  stride padding / lying layout is visible as garbage columns.
     *  bytes[0] is the byte at the plane's buffer position. */
    private fun planeBitmap(p: PlaneDump, validW: Int, rows: Int, bytesPerTexel: Int = 1): Bitmap {
        val texW = (p.rowStride / bytesPerTexel).coerceAtLeast(validW)
        val bmp = Bitmap.createBitmap(texW.coerceAtLeast(1), rows.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val pixels = IntArray(texW * rows)
        for (r in 0 until rows) {
            for (c in 0 until texW) {
                val idx = r * p.rowStride + c * bytesPerTexel
                val g = if (idx < p.bytes.size) (p.bytes[idx].toInt() and 0xFF) else 0
                pixels[r * texW + c] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        bmp.setPixels(pixels, 0, texW, 0, 0, texW, rows)
        return bmp
    }

    /** min / max / mean / std of the valid luma rectangle. */
    private fun planeStats(p: PlaneDump, w: Int, h: Int): DoubleArray {
        var mn = 255.0; var mx = 0.0; var sum = 0.0; var n = 0L
        for (r in 0 until h) {
            val base = r * p.rowStride
            for (c in 0 until w) {
                val idx = base + c
                if (idx >= p.bytes.size) continue
                val g = (p.bytes[idx].toInt() and 0xFF).toDouble()
                if (g < mn) mn = g
                if (g > mx) mx = g
                sum += g; n++
            }
        }
        if (n == 0L) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val mean = sum / n
        var sq = 0.0
        for (r in 0 until h) {
            val base = r * p.rowStride
            for (c in 0 until w) {
                val idx = base + c
                if (idx >= p.bytes.size) continue
                val d = (p.bytes[idx].toInt() and 0xFF) - mean
                sq += d * d
            }
        }
        return doubleArrayOf(mn, mx, mean, kotlin.math.sqrt(sq / n))
    }

    /**
     * The GL-bypass ground truth: convert the dumped YUV frame to RGB on
     * the CPU with the same BT.601 limited-range coefficients the shader
     * uses, honoring every stride exactly. If THIS image looks broken, the
     * fault is upstream of OpenGL (the camera HAL); if it looks fine while
     * the on-screen preview is broken, the fault is in the GL pipeline.
     */
    private fun cpuRenderRgb(f: FrameDump): Bitmap {
        val w = f.width
        val h = f.height
        val yp = f.planes[0]
        val up = f.planes[1]
        val vp = f.planes[2]
        val semi = up.pixelStride == 2
        // Same firstIsU derivation as the GL upload.
        val firstIsU = up.bufferPosition <= vp.bufferPosition
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2

        // Downscale for a manageable PNG (keep every stride relationship).
        val scale = (720f / maxOf(w, h)).coerceAtMost(1f)
        val ow = (w * scale).toInt().coerceAtLeast(1)
        val oh = (h * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(ow * oh)

        fun yAt(x: Int, y: Int): Int {
            val idx = y * yp.rowStride + x
            return if (idx < yp.bytes.size) (yp.bytes[idx].toInt() and 0xFF) else 16
        }

        // u,v in 0..255 for the chroma texel covering (x, y).
        fun uvAt(x: Int, y: Int): Pair<Int, Int> {
            val cx = (x / 2).coerceAtMost(cw - 1)
            val cy = (y / 2).coerceAtMost(ch - 1)
            return if (semi) {
                val rowBytes = up.rowStride
                val idx = cy * rowBytes + cx * 2
                val b0 = if (idx < up.bytes.size) (up.bytes[idx].toInt() and 0xFF) else 128
                val b1 = if (idx + 1 < up.bytes.size) (up.bytes[idx + 1].toInt() and 0xFF) else 128
                if (firstIsU) b0 to b1 else b1 to b0
            } else {
                val ui = cy * up.rowStride + cx
                val vi = cy * vp.rowStride + cx
                val uu = if (ui < up.bytes.size) (up.bytes[ui].toInt() and 0xFF) else 128
                val vv = if (vi < vp.bytes.size) (vp.bytes[vi].toInt() and 0xFF) else 128
                uu to vv
            }
        }

        for (ry in 0 until oh) {
            val sy = ((ry * 2 + 1) * h / (oh * 2)).coerceAtMost(h - 1)
            for (rx in 0 until ow) {
                val sx = ((rx * 2 + 1) * w / (ow * 2)).coerceAtMost(w - 1)
                val yv = yAt(sx, sy)
                val (uv, vv) = uvAt(sx, sy)
                val y = (yv - 16) * 1.1643836
                val u = uv - 128.0
                val v = vv - 128.0
                val r = (y + 1.5960346 * v).coerceIn(0.0, 255.0).roundToInt()
                val g = (y - 0.3917623 * u - 0.8129676 * v).coerceIn(0.0, 255.0).roundToInt()
                val b = (y + 2.0172321 * u).coerceIn(0.0, 255.0).roundToInt()
                pixels[ry * ow + rx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, ow, 0, 0, ow, oh)
        return bmp
    }

    // ------------------------------------------------------- saving

    private fun output(name: String, mime: String): OutputStream? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                }
                val uri: Uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                context.contentResolver.openOutputStream(uri)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    FOLDER
                )
                dir.mkdirs()
                File(dir, name).outputStream()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "save $name failed", t)
            null
        }
    }

    private fun saveBytes(name: String, mime: String, bytes: ByteArray) {
        output(name, mime)?.use { it.write(bytes) }
    }

    private fun saveText(name: String, text: String) = saveBytes(name, "text/plain", text.toByteArray())

    private fun saveBitmap(name: String, bmp: Bitmap) {
        output(name, "image/png")?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun scaleDown(bmp: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (s >= 1f) return bmp
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun writeSummary(results: List<TestResult>, elapsedMs: Long) {
        val sb = StringBuilder()
        sb.append("ACam camera diagnostics\n")
        sb.append("time: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append('\n')
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("camera: ").append(entry.label).append(" id=").append(entry.cameraId).append('\n')
        sb.append("elapsed: ").append(elapsedMs).append(" ms\n\n")
        for (r in results) {
            sb.append("== ").append(r.label).append(" ==\n")
            sb.append(r.note).append("\n\n")
        }
        sb.append(
            "How to read this:\n" +
                "- *_rgb_cpu.png is rendered on the CPU straight from the camera bytes,\n" +
                "  with no OpenGL involved. If it is broken, the camera HAL delivered\n" +
                "  broken data for that session shape.\n" +
                "- *_y_plane.png shows the raw luma plane at its true row-stride width;\n" +
                "  diagonal stripes there mean the declared stride does not match the data.\n" +
                "- still_alone_raw.jpg is the JPEG exactly as the camera produced it.\n" +
                "- combo_* shows what the classic two-stream session delivers.\n"
        )
        saveText("diag_summary.txt", sb.toString())
    }
}
