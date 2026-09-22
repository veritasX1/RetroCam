package com.retrocam.app.camera

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import java.util.Locale

/**
 * One physical/logical camera the device exposes (front, back main, back
 * ultrawide, ...), labeled from [CameraInfo.getIntrinsicZoomRatio] - 1.0x
 * is always the default camera for that facing, below 1.0x is an
 * ultrawide (e.g. the G84's "0.7x"), above 1.0x a telephoto.
 */
data class CameraOption(
    val cameraInfo: CameraInfo,
    val cameraId: String,
    val lensFacing: Int,
    val zoomRatio: Float,
    val label: String,
) {
    /** Stable across process restarts (unlike CameraInfo itself), used to
     * remember the user's chosen camera in SettingsRepository. Includes the
     * camera id, not just facing+zoom, since some phones expose an
     * auxiliary sensor (depth/macro) at the same ~1.0x as the main camera. */
    val persistenceKey: String = "$lensFacing:${"%.2f".format(Locale.ROOT, zoomRatio)}:$cameraId"
}

@OptIn(ExperimentalCamera2Interop::class)
private fun isStandardPhotoCamera(info: CameraInfo): Boolean {
    val caps = Camera2CameraInfo.from(info)
        .getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        ?: return true
    // Filters out depth-only sensors some phones expose as their own CameraX
    // camera even though they can't produce a normal image at all. Kept as a
    // first pass, but not relied on alone - see the resolution-based dedup
    // below, since some phones' auxiliary sensors still report this capability.
    return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
}

@OptIn(ExperimentalCamera2Interop::class)
private fun cameraIdOf(info: CameraInfo): String = Camera2CameraInfo.from(info).cameraId

@OptIn(ExperimentalCamera2Interop::class)
private fun sensorPixelCount(info: CameraInfo): Long {
    val size = Camera2CameraInfo.from(info)
        .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        ?: return 0L
    return size.width.toLong() * size.height.toLong()
}

fun buildCameraOptions(cameraInfos: List<CameraInfo>): List<CameraOption> {
    val usable = cameraInfos.filter { isStandardPhotoCamera(it) }
    val backCandidates = usable.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
    val frontCandidates = usable.filter { it.lensFacing == CameraSelector.LENS_FACING_FRONT }

    fun label(info: CameraInfo, isFront: Boolean): String {
        val zoom = info.intrinsicZoomRatio
        return when {
            isFront -> "Front"
            zoom in 0.95f..1.05f -> "1x"
            else -> "%.1fx".format(zoom)
        }
    }

    // Some phones expose an auxiliary sensor (depth/macro) at roughly the
    // same intrinsic zoom as the main camera, which - unlike a genuine
    // second "1x" lens - is almost always much lower resolution. Group by
    // rounded zoom and keep only the highest-resolution camera per group,
    // rather than trying to guess from capability flags alone (which some
    // auxiliary sensors report anyway).
    val dedupedBack = backCandidates
        .groupBy { "%.2f".format(java.util.Locale.ROOT, it.intrinsicZoomRatio) }
        .values
        .map { group -> group.maxBy { sensorPixelCount(it) } }

    val back = dedupedBack
        .sortedBy { it.intrinsicZoomRatio }
        .map { CameraOption(it, cameraIdOf(it), CameraSelector.LENS_FACING_BACK, it.intrinsicZoomRatio, label(it, false)) }
    val front = frontCandidates
        .map { CameraOption(it, cameraIdOf(it), CameraSelector.LENS_FACING_FRONT, it.intrinsicZoomRatio, label(it, true)) }

    // Back cameras first (main "1x" before ultrawide/tele), front last -
    // matches where people expect the everyday camera to sit in the list.
    return back.sortedByDescending { it.zoomRatio in 0.95f..1.05f } + front
}

/** Builds a CameraSelector that pins CameraX to this exact physical/logical
 * camera rather than just "some back camera" - needed because a phone can
 * have several cameras with the same lens facing (main + ultrawide). */
fun CameraOption.toSelector(): CameraSelector = CameraSelector.Builder()
    .addCameraFilter { infos -> infos.filter { it == cameraInfo } }
    .build()
