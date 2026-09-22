package com.retrocam.app.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ExtendableBuilder

/** The fps values old and new cameras actually advertise - not every
 * camera supports all of these, so this is intersected against what the
 * hardware reports in [availableFpsFor]. */
private val CANDIDATE_FPS = listOf(18, 24, 30, 50, 60)

@OptIn(ExperimentalCamera2Interop::class)
fun availableFpsFor(cameraInfo: CameraInfo): List<Int> {
    val ranges = Camera2CameraInfo.from(cameraInfo)
        .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ?: return emptyList()
    return CANDIDATE_FPS.filter { fps -> ranges.any { fps >= it.lower && fps <= it.upper } }
}

/** Pins the capture session to a fixed frame rate via the Camera2 AE
 * target range - CameraX itself has no higher-level "set fps" API for
 * VideoCapture. 0/negative leaves the camera's own default alone. */
@OptIn(ExperimentalCamera2Interop::class)
fun <T> ExtendableBuilder<T>.applyFixedFps(fps: Int) {
    if (fps <= 0) return
    Camera2Interop.Extender(this).setCaptureRequestOption(
        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
        Range(fps, fps),
    )
}
