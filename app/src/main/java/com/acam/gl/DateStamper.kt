package com.acam.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import com.acam.data.DateStampConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * Renders the editable orange date-stamp overlay to a bitmap texture.
 * The text is a SimpleDateFormat pattern (or any literal text when the
 * pattern is invalid), like the blocky stamps on old film cameras.
 */
object DateStamper {

    fun render(cfg: DateStampConfig): Bitmap? {
        val text = resolveText(cfg.text)
        if (text.isBlank()) return null

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cfg.colorArgb.toInt()
            textSize = 64f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.06f
        }

        val w = ceil(paint.measureText(text)).toInt() + 24
        val h = 104
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(8), h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val baseline = h / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, 12f, baseline, paint)
        return bmp
    }

    fun resolveText(pattern: String): String {
        if (pattern.isEmpty()) return ""
        return try {
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
        } catch (t: Throwable) {
            pattern
        }
    }
}
