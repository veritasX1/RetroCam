package com.retrocam.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.retrocam.app.ui.theme.RetroAccent
import com.retrocam.app.ui.theme.RetroWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class EditorMode { CROP, ADJUST }

private data class AspectPreset(val label: String, val ratio: Float?)
private val ASPECT_PRESETS = listOf(
    AspectPreset("Frei", null),
    AspectPreset("1:1", 1f),
    AspectPreset("4:5", 4f / 5f),
    AspectPreset("3:4", 3f / 4f),
    AspectPreset("16:9", 16f / 9f),
)

/**
 * A lightweight, iOS-Photos-style editor for a single already-captured
 * photo: rotate, a draggable/resizable crop rect with a few aspect-ratio
 * presets, and brightness/contrast/saturation sliders. Not trying to be a
 * full editor - no layers, no undo history beyond "start over", no
 * healing/retouch - just the handful of corrections people actually reach
 * for right after a shot. Saves by overwriting the same MediaStore URI the
 * photo was already saved at, matching how PhotoPostProcessor's date-stamp
 * burn-in already treats capture as "the file", not an immutable original.
 */
@Composable
fun PhotoEditorScreen(uri: Uri, onDone: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var workingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    var mode by remember { mutableStateOf(EditorMode.ADJUST) }
    var imageBoxSizePx by remember { mutableStateOf<Size?>(null) }
    var cropRect by remember { mutableStateOf<Rect?>(null) }
    var selectedPresetLabel by remember { mutableStateOf(ASPECT_PRESETS[0].label) }

    var brightness by remember { mutableStateOf(0f) } // -1..1
    var contrast by remember { mutableStateOf(0f) } // -1..1
    var saturation by remember { mutableStateOf(0f) } // -1..1

    LaunchedEffect(uri) {
        isLoading = true
        workingBitmap = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
        isLoading = false
    }

    // Reset the crop rect to the full image whenever the working bitmap's
    // box is (re)established - a fresh photo, or a rotate/apply-crop that
    // just replaced the bitmap (and therefore its aspect ratio/box size).
    LaunchedEffect(workingBitmap, imageBoxSizePx) {
        val box = imageBoxSizePx
        if (box != null) cropRect = Rect(Offset.Zero, box)
    }

    fun colorMatrix(): ColorMatrix {
        val c = 1f + contrast
        val b = brightness * 255f
        val t = (1f - c) * 127.5f + b
        val contrastBrightness = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val sat = ColorMatrix().apply { setToSaturation(1f + saturation) }
        return concatColorMatrices(contrastBrightness, sat)
    }

    fun rotate90() {
        val bmp = workingBitmap ?: return
        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
        workingBitmap = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        imageBoxSizePx = null // box aspect changed - force a fresh measurement/crop reset
    }

    fun applyPreset(ratio: Float?) {
        val box = imageBoxSizePx ?: return
        if (ratio == null) {
            cropRect = Rect(Offset.Zero, box)
            return
        }
        val boxAspect = box.width / box.height
        val (w, h) = if (ratio > boxAspect) box.width to box.width / ratio else box.height * ratio to box.height
        val left = (box.width - w) / 2f
        val top = (box.height - h) / 2f
        cropRect = Rect(left, top, left + w, top + h)
    }

    fun applyCrop() {
        val bmp = workingBitmap ?: return
        val box = imageBoxSizePx ?: return
        val rect = cropRect ?: return
        val scale = bmp.width / box.width
        val left = (rect.left * scale).roundToInt().coerceIn(0, bmp.width - 1)
        val top = (rect.top * scale).roundToInt().coerceIn(0, bmp.height - 1)
        val right = (rect.right * scale).roundToInt().coerceIn(left + 1, bmp.width)
        val bottom = (rect.bottom * scale).roundToInt().coerceIn(top + 1, bmp.height)
        workingBitmap = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
        imageBoxSizePx = null
        mode = EditorMode.ADJUST
    }

    fun save() {
        val bmp = workingBitmap ?: return
        isSaving = true
        val matrixValues = colorMatrix().values
        scope.launch {
            withContext(Dispatchers.IO) {
                val output = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(output)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(matrixValues)
                }
                canvas.drawBitmap(bmp, 0f, 0f, paint)
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    output.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                output.recycle()
            }
            isSaving = false
            onDone()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone, enabled = !isSaving) {
                Icon(Icons.Filled.Close, contentDescription = "Abbrechen", tint = RetroWhite)
            }
            Text("Bearbeiten", color = RetroWhite, style = MaterialTheme.typography.titleMedium)
            if (isSaving) {
                CircularProgressIndicator(Modifier.size(24.dp), color = RetroAccent, strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { save() }, enabled = workingBitmap != null) {
                    Icon(Icons.Filled.Check, contentDescription = "Speichern", tint = RetroAccent)
                }
            }
        }

        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = workingBitmap
            if (isLoading) {
                CircularProgressIndicator(color = RetroAccent)
            } else if (bmp != null) {
                val maxWPx = with(density) { maxWidth.toPx() }
                val maxHPx = with(density) { maxHeight.toPx() }
                val bmpAspect = bmp.width.toFloat() / bmp.height
                val boxAspect = maxWPx / maxHPx
                val (dispWPx, dispHPx) = if (bmpAspect > boxAspect) {
                    maxWPx to maxWPx / bmpAspect
                } else {
                    maxHPx * bmpAspect to maxHPx
                }
                val dispWDp = with(density) { dispWPx.toDp() }
                val dispHDp = with(density) { dispHPx.toDp() }

                Box(
                    Modifier
                        .size(dispWDp, dispHDp)
                        // A crop handle can legitimately sit right at the
                        // image's left/right edge (a full-width or
                        // near-full-width rect isn't unusual), which is
                        // exactly where gesture-nav's edge back-swipe
                        // detection also lives - without this, dragging
                        // that handle can get eaten by the system back
                        // gesture instead of resizing the crop (confirmed
                        // by hand while testing this screen).
                        .systemGestureExclusion()
                        .onSizeChanged { imageBoxSizePx = it.toSize() },
                ) {
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = "Foto",
                        modifier = Modifier.fillMaxSize(),
                        colorFilter = ColorFilter.colorMatrix(colorMatrix()),
                    )
                    val rect = cropRect
                    if (mode == EditorMode.CROP && rect != null) {
                        CropOverlay(
                            rect = rect,
                            boxSize = imageBoxSizePx ?: Size.Zero,
                            onRectChange = { cropRect = it },
                        )
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp, top = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                ModeLabel("Zuschneiden", mode == EditorMode.CROP) { mode = EditorMode.CROP }
                ModeLabel("Anpassen", mode == EditorMode.ADJUST) { mode = EditorMode.ADJUST }
                Box(Modifier.weight(1f))
                IconButton(onClick = { rotate90() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Drehen", tint = RetroWhite)
                }
            }

            if (mode == EditorMode.CROP) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ASPECT_PRESETS.forEach { preset ->
                        AspectChip(preset.label, selectedPresetLabel == preset.label) {
                            selectedPresetLabel = preset.label
                            applyPreset(preset.ratio)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { applyCrop() }) {
                        Icon(Icons.Filled.Check, contentDescription = "Zuschnitt anwenden", tint = RetroAccent)
                    }
                }
            } else {
                AdjustSlider("Helligkeit", brightness) { brightness = it }
                AdjustSlider("Kontrast", contrast) { contrast = it }
                AdjustSlider("Sättigung", saturation) { saturation = it }
            }
        }
    }
}

@Composable
private fun ModeLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) RetroAccent else RetroWhite,
        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun AspectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) RetroAccent else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (selected) Color.Black else RetroWhite, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = -1f..1f,
            colors = SliderDefaults.colors(thumbColor = RetroAccent, activeTrackColor = RetroAccent),
        )
    }
}

private const val HANDLE_SIZE_DP = 20
private const val MIN_CROP_SIZE_PX = 80f

@Composable
private fun CropOverlay(rect: Rect, boxSize: Size, onRectChange: (Rect) -> Unit) {
    val density = LocalDensity.current
    val handlePx = with(density) { HANDLE_SIZE_DP.dp.toPx() }

    Canvas(Modifier.fillMaxSize()) {
        val scrim = Color.Black.copy(alpha = 0.6f)
        drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, rect.top))
        drawRect(scrim, topLeft = Offset(0f, rect.bottom), size = Size(size.width, (size.height - rect.bottom).coerceAtLeast(0f)))
        drawRect(scrim, topLeft = Offset(0f, rect.top), size = Size(rect.left, rect.height))
        drawRect(scrim, topLeft = Offset(rect.right, rect.top), size = Size((size.width - rect.right).coerceAtLeast(0f), rect.height))
        drawRect(Color.White, topLeft = rect.topLeft, size = rect.size, style = Stroke(width = 2.dp.toPx()))
    }

    // Whole-rect move - underneath the corner handles so they still take
    // priority for the exact pixels they occupy. pointerInput(boxSize)
    // only *launches* the gesture-detection coroutine once (boxSize is
    // stable for the whole crop session) - it does NOT restart on every
    // recomposition, so anything the drag callback closes over directly
    // (rect here) would otherwise go stale after the very first update.
    // rememberUpdatedState keeps `latestRect` pointing at the current
    // value on every call without needing to restart the gesture.
    val latestRect = rememberUpdatedState(rect)
    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .pointerInput(boxSize) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val r = latestRect.value
                    val newLeft = (r.left + drag.x).coerceIn(0f, boxSize.width - r.width)
                    val newTop = (r.top + drag.y).coerceIn(0f, boxSize.height - r.height)
                    onRectChange(Rect(newLeft, newTop, newLeft + r.width, newTop + r.height))
                }
            },
    )

    CropHandle(rect.topLeft, handlePx) { drag ->
        val newLeft = (rect.left + drag.x).coerceIn(0f, rect.right - MIN_CROP_SIZE_PX)
        val newTop = (rect.top + drag.y).coerceIn(0f, rect.bottom - MIN_CROP_SIZE_PX)
        onRectChange(rect.copy(left = newLeft, top = newTop))
    }
    CropHandle(Offset(rect.right, rect.top), handlePx) { drag ->
        val newRight = (rect.right + drag.x).coerceIn(rect.left + MIN_CROP_SIZE_PX, boxSize.width)
        val newTop = (rect.top + drag.y).coerceIn(0f, rect.bottom - MIN_CROP_SIZE_PX)
        onRectChange(rect.copy(right = newRight, top = newTop))
    }
    CropHandle(Offset(rect.left, rect.bottom), handlePx) { drag ->
        val newLeft = (rect.left + drag.x).coerceIn(0f, rect.right - MIN_CROP_SIZE_PX)
        val newBottom = (rect.bottom + drag.y).coerceIn(rect.top + MIN_CROP_SIZE_PX, boxSize.height)
        onRectChange(rect.copy(left = newLeft, bottom = newBottom))
    }
    CropHandle(rect.bottomRight, handlePx) { drag ->
        val newRight = (rect.right + drag.x).coerceIn(rect.left + MIN_CROP_SIZE_PX, boxSize.width)
        val newBottom = (rect.bottom + drag.y).coerceIn(rect.top + MIN_CROP_SIZE_PX, boxSize.height)
        onRectChange(rect.copy(right = newRight, bottom = newBottom))
    }
}

@Composable
private fun CropHandle(center: Offset, sizePx: Float, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    // See the move-box's comment above - pointerInput(Unit) never restarts,
    // so the drag callback must go through rememberUpdatedState to avoid
    // permanently calling back into the very first onDrag closure (which
    // closed over whatever `rect` this handle had when crop mode was first
    // entered, not the current one).
    val latestOnDrag = rememberUpdatedState(onDrag)
    Box(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset((center.x - sizePx / 2).roundToInt(), (center.y - sizePx / 2).roundToInt()) }
            .size(with(density) { sizePx.toDp() })
            .clip(CircleShape)
            .background(RetroAccent)
            .border(2.dp, Color.White, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    latestOnDrag.value(drag)
                }
            },
    )
}

/** Concatenates two 4x5 RGBA color matrices (both this app's and platform
 * android.graphics.ColorMatrix use this row-major format with an implicit
 * fixed last row) so [first] is applied before [second] in a single pass -
 * needed because Compose's Image only takes one ColorFilter. */
private fun concatColorMatrices(first: ColorMatrix, second: ColorMatrix): ColorMatrix {
    val a = second.values
    val b = first.values
    val result = FloatArray(20)
    for (i in 0 until 4) {
        for (j in 0 until 5) {
            var sum = 0f
            for (k in 0 until 4) {
                sum += a[i * 5 + k] * b[k * 5 + j]
            }
            if (j == 4) sum += a[i * 5 + 4]
            result[i * 5 + j] = sum
        }
    }
    return ColorMatrix(result)
}
