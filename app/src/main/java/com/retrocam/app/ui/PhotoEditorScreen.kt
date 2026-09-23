package com.retrocam.app.ui

import android.content.res.Configuration
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
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.retrocam.app.camera.gl.PhotoLookBaker
import com.retrocam.app.data.DYNAMIC_RANGE_OPTIONS
import com.retrocam.app.data.EffectStrength
import com.retrocam.app.data.FilmSimulationBase
import com.retrocam.app.data.GrainSize
import com.retrocam.app.data.GrainStrength
import com.retrocam.app.data.Recipe
import com.retrocam.app.data.toRenderLook
import com.retrocam.app.ui.theme.RetroAccent
import com.retrocam.app.ui.theme.RetroWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

// Debounces the live-preview re-bake against rapid slider dragging - a
// GL bake isn't free, so this waits for the value to actually settle
// rather than re-baking on every intermediate drag tick. Compose's
// LaunchedEffect(recipe) naturally cancels/restarts this delay on every
// change, so a continuous drag never gets past this line until it pauses.
private const val PREVIEW_DEBOUNCE_MS = 120L

// The live preview bakes a downscaled copy instead of the full-resolution
// capture - a GL pass's cost scales with pixel count, and nobody can tell
// the difference on a phone screen at this size anyway. The FINAL save
// still bakes the real, full-resolution bitmap (see save()).
private const val PREVIEW_MAX_DIMENSION = 1000

/**
 * A lightweight, iOS-Photos-style editor for a single already-captured
 * photo: rotate, a draggable/resizable crop rect with a few aspect-ratio
 * presets, and the same Fuji-recipe-style tone/color controls the X100VI
 * manual describes (White Balance shift, Highlight/Shadow tone, Color,
 * Sharpness, High ISO NR, Clarity, Color Chrome Effect/FX Blue, Grain
 * Effect, Dynamic Range, Film Simulation) - applied via the real GL
 * shader pipeline (PhotoLookBaker), not a cheap ColorMatrix
 * approximation, since Clarity/Color Chrome are local/hue-gated effects
 * a flat color matrix can't represent. Saves by overwriting the same
 * MediaStore URI the photo was already saved at, matching how
 * PhotoPostProcessor's date-stamp burn-in already treats capture as "the
 * file", not an immutable original.
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

    // Same Fuji-scale fields/ranges as RecipeEditorScreen (see Recipe.kt
    // for each one's real numeric range and manual-sourced mechanism) -
    // starts neutral regardless of whatever recipe the photo was
    // originally shot with, the same way a real photo editor lets you
    // grade on top of an already-graded JPEG rather than recalling the
    // camera's in-the-moment settings.
    var filmSimulation by remember { mutableStateOf("Provia/Standard") }
    var dynamicRange by remember { mutableStateOf("DR100") }
    var grainStrength by remember { mutableStateOf(GrainStrength.OFF) }
    var grainSize by remember { mutableStateOf(GrainSize.SMALL) }
    var colorChromeEffect by remember { mutableStateOf(EffectStrength.OFF) }
    var colorChromeFxBlue by remember { mutableStateOf(EffectStrength.OFF) }
    var wbShiftRed by remember { mutableStateOf(0f) }
    var wbShiftBlue by remember { mutableStateOf(0f) }
    var highlight by remember { mutableStateOf(0f) }
    var shadow by remember { mutableStateOf(0f) }
    var color by remember { mutableStateOf(0f) }
    var sharpness by remember { mutableStateOf(0f) }
    var highIsoNr by remember { mutableStateOf(0f) }
    var clarity by remember { mutableStateOf(0f) }

    // A plain in-memory Recipe (never persisted/inserted into the DB) -
    // reuses Recipe.toRenderLook()'s exact mapping instead of duplicating
    // it, and its structural equality is what LaunchedEffect below keys
    // the debounced re-bake on.
    val editRecipe = Recipe(
        name = "",
        filmSimulation = filmSimulation,
        dynamicRange = dynamicRange,
        grainStrength = grainStrength,
        grainSize = grainSize,
        colorChromeEffect = colorChromeEffect,
        colorChromeFxBlue = colorChromeFxBlue,
        wbShiftRed = wbShiftRed.roundToInt(),
        wbShiftBlue = wbShiftBlue.roundToInt(),
        highlight = highlight,
        shadow = shadow,
        color = color.roundToInt(),
        sharpness = sharpness.roundToInt(),
        highIsoNr = highIsoNr.roundToInt(),
        clarity = clarity.roundToInt(),
    )

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

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

    // Downscaled once per working bitmap (crop/rotate), then re-baked
    // cheaply on every parameter tweak - see PREVIEW_MAX_DIMENSION.
    val previewSource = remember(workingBitmap) { workingBitmap?.let { downscale(it, PREVIEW_MAX_DIMENSION) } }

    LaunchedEffect(editRecipe, previewSource) {
        val source = previewSource ?: return@LaunchedEffect
        delay(PREVIEW_DEBOUNCE_MS)
        val look = editRecipe.toRenderLook()
        previewBitmap = withContext(Dispatchers.Default) { PhotoLookBaker.bake(context, source, look) }
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
        val look = editRecipe.toRenderLook()
        scope.launch {
            withContext(Dispatchers.Default) {
                val baked = PhotoLookBaker.bake(context, bmp, look)
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    baked.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                baked.recycle()
            }
            isSaving = false
            onDone()
        }
    }

    val isLandscapeConfig = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Shared between the portrait (full-width, below the image) and
    // landscape (fixed-width sidebar, right of the image) layouts - the
    // controls themselves don't care which orientation put them there,
    // only the container around this lambda differs.
    val controlsContent: @Composable ColumnScope.() -> Unit = {
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
            // The full Fuji "picture quality" control set from the X100VI
            // manual (see Recipe.kt for each field's real range/mechanism)
            // - scrollable since it's a lot more than three sliders now,
            // weight(1f) bounds it against whatever height the container
            // this lambda is placed in actually has (see the two call
            // sites below).
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                EnumRow(
                    "Film Simulation",
                    FilmSimulationBase.entries.map { it.displayName },
                    filmSimulation,
                    { it },
                ) { filmSimulation = it }
                EnumRow("Dynamic Range", DYNAMIC_RANGE_OPTIONS, dynamicRange, { it }) { dynamicRange = it }
                SliderField("WB Shift Red", wbShiftRed, -9f..9f, steps = 17) { wbShiftRed = it }
                SliderField("WB Shift Blue", wbShiftBlue, -9f..9f, steps = 17) { wbShiftBlue = it }
                SliderField("Highlight", highlight, -2f..4f, steps = 11) { highlight = it }
                SliderField("Shadow", shadow, -2f..4f, steps = 11) { shadow = it }
                SliderField("Color", color, -4f..4f, steps = 7) { color = it }
                SliderField("Sharpness", sharpness, -4f..4f, steps = 7) { sharpness = it }
                SliderField("High ISO NR", highIsoNr, -4f..4f, steps = 7) { highIsoNr = it }
                SliderField("Clarity", clarity, -5f..5f, steps = 9) { clarity = it }
                EnumRow("Color Chrome Effect", EffectStrength.entries, colorChromeEffect, { it.name }) { colorChromeEffect = it }
                EnumRow("Color Chrome FX Blue", EffectStrength.entries, colorChromeFxBlue, { it.name }) { colorChromeFxBlue = it }
                EnumRow("Grain Effect", GrainStrength.entries, grainStrength, { it.name }) { grainStrength = it }
                EnumRow("Grain Size", GrainSize.entries, grainSize, { it.name }) { grainSize = it }
            }
        }
    }

    // Shared between both orientations too - only the BoxWithConstraints
    // call site's own modifier (fill available width vs. available height)
    // differs, so the image/crop-overlay logic itself lives here once.
    val imageContent: @Composable BoxWithConstraintsScope.() -> Unit = {
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
                // previewBitmap is the GL-baked look preview (see the
                // debounced LaunchedEffect above) - falls back to the
                // plain downscaled/original bitmap for the brief window
                // before the first bake completes, so the image never
                // flashes blank.
                Image(
                    (previewBitmap ?: previewSource ?: bmp).asImageBitmap(),
                    contentDescription = "Foto",
                    modifier = Modifier.fillMaxSize(),
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

        if (isLandscapeConfig) {
            // Controls move to a fixed-width sidebar on the right instead
            // of stacking below the image - on a landscape screen the
            // available height is short, so reserving a chunk of it for
            // the full control set (as the portrait layout does, capped
            // instead) squeezed the image down to a sliver. Side-by-side
            // instead gives the image the full height and the controls a
            // properly sized, scrollable column.
            Row(Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxHeight().padding(16.dp),
                    contentAlignment = Alignment.Center,
                    content = imageContent,
                )
                Column(
                    Modifier.width(320.dp).fillMaxHeight().padding(top = 8.dp, bottom = 16.dp),
                    content = controlsContent,
                )
            }
        } else {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.Center,
                content = imageContent,
            )
            Column(
                Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(bottom = 24.dp, top = 8.dp),
                content = controlsContent,
            )
        }
    }
}

private fun downscale(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
    if (scale >= 1f) return bitmap
    val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, w, h, true)
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
