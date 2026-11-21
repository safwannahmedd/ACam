package com.acam.gl

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves photos and creates video output targets through MediaStore
 * (Pictures/ACam, Movies/ACam). Handles scoped storage on Android 10+
 * and the legacy public directory below that, with WRITE_EXTERNAL_STORAGE
 * only on Android 8.x.
 */
object MediaSaver {

    fun timestampName(prefix: String, ext: String): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return "${prefix}_$ts.$ext"
    }

    fun createImage(context: Context, format: PhotoFormatInfo): Pair<Uri, java.io.OutputStream> {
        val name = timestampName("ACam", format.extension)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ACam")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw IllegalStateException("MediaStore insert failed")
            val os = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Cannot open output stream")
            return uri to os
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "ACam"
            )
            dir.mkdirs()
            val file = File(dir, name)
            return Uri.fromFile(file) to FileOutputStream(file)
        }
    }

    /** Output target for MediaMuxer: a file descriptor and a way to finish. */
    class VideoTarget(
        val fd: java.io.FileDescriptor,
        val uri: Uri?,
        val path: String?,
        private val closer: () -> Unit
    ) {
        fun close() = closer()
    }

    fun createVideo(context: Context): VideoTarget {
        val name = timestampName("ACam", "mp4")
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ACam")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw IllegalStateException("MediaStore insert failed")
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Cannot open video fd")
            return VideoTarget(pfd.fileDescriptor, uri, null) { pfd.close() }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "ACam"
            )
            dir.mkdirs()
            val file = File(dir, name)
            val fos = FileOutputStream(file)
            return VideoTarget(fos.fd, null, file.absolutePath) { fos.close() }
        }
    }
}

data class PhotoFormatInfo(val extension: String, val mimeType: String, val png: Boolean)

/**
 * Turns the RGBA bytes read from the capture FBO into a saved JPEG/PNG.
 * The bytes are already top-down (the capture pass renders with a flipped
 * quad) so they can go straight into a Bitmap.
 */
object PhotoSaver {

    fun save(
        context: Context,
        w: Int,
        h: Int,
        rgba: ByteArray,
        png: Boolean,
        jpegQuality: Int,
        onSaved: (Uri?) -> Unit
    ) {
        Thread {
            var uri: Uri? = null
            try {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
                val fmt = if (png) {
                    PhotoFormatInfo("png", "image/png", true)
                } else {
                    PhotoFormatInfo("jpg", "image/jpeg", false)
                }
                val (outUri, os) = MediaSaver.createImage(context, fmt)
                uri = outUri
                os.use { stream ->
                    if (png) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    } else {
                        bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(1, 100), stream)
                    }
                }
                if (!png) {
                    writeExif(context, outUri)
                }
                bmp.recycle()
            } catch (t: Throwable) {
                uri = null
            }
            onSaved(uri)
        }.start()
    }

    /**
     * The pixels are already upright (rotation and mirror are baked into the
     * render), so EXIF orientation stays "normal"; we still record model,
     * software and the capture time. ExifInterface edits the file in place
     * through a seekable file descriptor (or a plain path pre-Q).
     */
    private fun writeExif(context: Context, uri: Uri) {
        runCatching {
            val exif: ExifInterface? = if (uri.scheme == "file") {
                uri.path?.let { ExifInterface(it) }
            } else {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    ExifInterface(pfd.fileDescriptor)
                }
            }
            exif ?: return
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_MAKE, "ACam")
            exif.setAttribute(ExifInterface.TAG_MODEL, "ACam")
            exif.setAttribute(
                ExifInterface.TAG_DATETIME,
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            )
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "ACam 1.0")
            exif.saveAttributes()
        }
    }
}
