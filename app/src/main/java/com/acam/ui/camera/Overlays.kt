package com.acam.ui.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.acam.data.GridMode
import java.util.Locale
import kotlin.math.abs

/** Grid overlay: thirds or square. Drawn in the accent-neutral white 40%. */
@Composable
fun GridOverlay(mode: GridMode) {
    if (mode == GridMode.OFF) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val line = Color.White.copy(alpha = 0.35f)
        val stroke = Stroke(width = 1.2f, cap = StrokeCap.Round)
        when (mode) {
            GridMode.THIRDS -> {
                for (i in 1..2) {
                    drawLine(line, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), strokeWidth = 1.2f, cap = StrokeCap.Round)
                    drawLine(line, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), strokeWidth = 1.2f, cap = StrokeCap.Round)
                }
            }
            GridMode.SQUARE -> {
                for (i in 1..3) {
                    drawLine(line, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), strokeWidth = 1.2f, cap = StrokeCap.Round)
                    drawLine(line, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), strokeWidth = 1.2f, cap = StrokeCap.Round)
                }
            }
            GridMode.OFF -> Unit
        }
    }
}

/** Animated focus ring at a tap position. */
@Composable
fun FocusRing(position: Offset?) {
    val alpha by animateFloatAsState(
        targetValue = if (position == null) 0f else 1f,
        animationSpec = tween(150), label = "focusAlpha"
    )
    if (position == null) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = 44.dp.toPx()
        drawCircle(
            color = Color.White.copy(alpha = 0.9f * alpha),
            radius = radius,
            center = position,
            style = Stroke(width = 2.4f)
        )
        drawCircle(
            color = Color(0xFFE8590C).copy(alpha = alpha),
            radius = 5.dp.toPx(),
            center = position
        )
    }}

/**
 * Level indicator: a small centered marker that turns accent-colored when
 * the device is within 1.5 degrees of level (roll).
 */
@Composable
fun LevelIndicator(enabled: Boolean) {
    if (!enabled) return
    val context = LocalContext.current
    var roll by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val r = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                SensorManager.getOrientation(r, orientation)
                roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sm.unregisterListener(listener) }
    }
    val level = abs(roll) < 1.5f
    Text(
        text = if (level) "level" else String.format(Locale.US, "%.1f°", roll),
        color = if (level) Color(0xFFE8590C) else Color.White.copy(alpha = 0.5f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.offset(x = 0.dp)
    )
}

/** Self-timer countdown. */
@Composable
fun CountdownOverlay(seconds: Int?) {
    if (seconds == null) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = seconds.toString(),
            color = Color.White,
            fontSize = 88.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Recording time badge. */
@Composable
fun RecTimeBadge(startMs: Long, paused: Boolean) {
    var text by remember { mutableStateOf("00:00") }
    LaunchedEffect(startMs, paused) {
        while (true) {
            val elapsed = (System.currentTimeMillis() - startMs) / 1000
            val m = elapsed / 60
            val s = elapsed % 60
            text = String.format(Locale.US, "%02d:%02d%s", m, s, if (paused) " (paused)" else "")
            kotlinx.coroutines.delay(250)
        }
    }
    Text(
        text = text,
        color = Color(0xFFFF5C5C),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
    )
}

/** Brief white flash when a photo is captured. */
@Composable
fun CaptureFlash(visible: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 0.85f else 0f,
        animationSpec = tween(90), label = "flash"
    )
    if (alpha > 0.01f) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color.White.copy(alpha = alpha))
        }
    }
}
