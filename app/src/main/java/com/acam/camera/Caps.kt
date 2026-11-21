package com.acam.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import kotlin.math.abs

/**
 * Snapshot of what the active camera supports. Used to hide unsupported
 * controls in the UI and to clamp manual values into valid ranges.
 */
data class Caps(
    val cameraId: String,
    val label: String,
    val isFront: Boolean,
    val sensorOrientation: Int,
    val streamW: Int,
    val streamH: Int,
    /** Largest still-capture (JPEG) size, sensor-oriented. The resolution
     *  picker's device maximum and the full-res photo path derive from
     *  this, not from the (capped) live stream. 0 = unavailable. */
    val captureW: Int,
    val captureH: Int,
    val maxZoom: Float,
    val evMin: Int,
    val evMax: Int,
    val isoMin: Int?,
    val isoMax: Int?,
    val exposureMinNs: Long?,
    val exposureMaxNs: Long?,
    val minFocusDistance: Float,
    val manualWb: Boolean,
    val hasFlash: Boolean,
    val videoStab: Boolean
) {
    val supportsManualExposure: Boolean get() = isoMin != null && isoMax != null
    val supportsManualFocus: Boolean get() = minFocusDistance > 0f

    companion object {

        fun from(
            ch: CameraCharacteristics,
            cameraId: String,
            label: String,
            streamW: Int,
            streamH: Int,
            mainBackFocal: Float?
        ): Caps {
            val facing = ch.get(CameraCharacteristics.LENS_FACING) ?: CameraSelector.LENS_FACING_BACK
            val zoomRange = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val zoom = zoomRange?.upper?.takeIf { it > 0f } ?: run {
                // Fallback: compute from crop region relative to active array.
                val array = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                (array?.let { it.width().toFloat() / it.height().coerceAtLeast(1) } ?: 4f)
            }
            val evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: intArrayOf()
            val stabModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f

            // Largest JPEG output size (the one camera path every HAL
            // implements reliably). Prefer 4:3 to match the preview stream's
            // framing (same field of view), else the largest overall.
            val jpegSizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.filter { it.width > 0 && it.height > 0 && it.width >= it.height }
                .orEmpty()
            val best43 = jpegSizes.filter {
                abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f
            }.maxByOrNull { it.width.toLong() * it.height }
            val bestAny = jpegSizes.maxByOrNull { it.width.toLong() * it.height }
            val capSize = best43 ?: bestAny

            val computedLabel = if (facing == CameraSelector.LENS_FACING_FRONT) {
                "Front"
            } else if (mainBackFocal != null && mainBackFocal > 0f) {
                when {
                    abs(focal - mainBackFocal) / mainBackFocal < 0.15f -> "Back"
                    focal < mainBackFocal -> "Ultra-wide"
                    else -> "Telephoto"
                }
            } else {
                "Back"
            }

            return Caps(
                cameraId = cameraId,
                label = label,
                isFront = facing == CameraSelector.LENS_FACING_FRONT,
                sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                streamW = streamW,
                streamH = streamH,
                captureW = capSize?.width ?: 0,
                captureH = capSize?.height ?: 0,
                maxZoom = zoom,
                evMin = evRange?.lower ?: 0,
                evMax = evRange?.upper ?: 0,
                isoMin = isoRange?.lower,
                isoMax = isoRange?.upper,
                exposureMinNs = expRange?.lower,
                exposureMaxNs = expRange?.upper,
                minFocusDistance = if (minFocus < 0f) 0f else minFocus,
                manualWb = awbModes.contains(CameraMetadata.CONTROL_AWB_MODE_OFF),
                hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                videoStab = stabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            )
        }
    }
}
