package com.retrocam.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.retrocam.app.camera.CameraViewModel
import com.retrocam.app.data.DateOrder
import com.retrocam.app.data.DateSeparator
import com.retrocam.app.data.DateStampSettings
import com.retrocam.app.data.GrainBlendMode
import com.retrocam.app.data.GrainOverride
import com.retrocam.app.data.LocationStampMode
import com.retrocam.app.data.Recipe
import com.retrocam.app.data.RetroCamDatabase
import com.retrocam.app.data.SettingsRepository
import com.retrocam.app.data.Softness
import com.retrocam.app.data.ShutterSound
import com.retrocam.app.data.StampColor
import com.retrocam.app.data.StampCorner
import com.retrocam.app.data.YearStyle
import com.retrocam.app.data.formatDateStamp
import com.retrocam.app.ui.theme.RetroAccent
import com.retrocam.app.ui.theme.RetroWhite
import kotlinx.coroutines.launch
import java.util.Date

@Composable
fun SettingsScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit,
    onAddRecipe: () -> Unit,
    onEditRecipe: (Recipe) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { SettingsRepository(context) }
    val db = remember(context) { RetroCamDatabase.get(context) }

    val shutterSound by viewModel.shutterSound.collectAsState()
    val softness by viewModel.softness.collectAsState()
    val grainOverride by viewModel.grainOverride.collectAsState()
    val grainBlendMode by viewModel.grainBlendMode.collectAsState()
    val recipes by viewModel.recipes.collectAsState()
    val availableFps by viewModel.availableFps.collectAsState()
    val videoFps by viewModel.videoFps.collectAsState()
    val locationEnabled by viewModel.locationEnabled.collectAsState()
    val dateStamp by viewModel.dateStampSettings.collectAsState()
    val locationStampMode by viewModel.locationStampMode.collectAsState()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.any { it }) scope.launch { settings.setLocationEnabled(true) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück", tint = RetroWhite)
            }
            Text("Einstellungen", color = RetroWhite, style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            item { SectionTitle("Auslöseton") }
            item {
                Column {
                    ShutterSound.entries.forEach { option ->
                        SettingsRow(
                            label = optionLabel(option),
                            selected = shutterSound == option,
                            enabled = true,
                        ) { scope.launch { settings.setShutterSound(option) } }
                    }
                    Text(
                        com.retrocam.app.data.SHUTTER_SOUND_REGION_NOTE,
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }

            item { SectionTitle("Weichzeichnung (Otto-Normal-Kamera-Look)") }
            item {
                Column {
                    Softness.entries.forEach { option ->
                        SettingsRow(
                            label = softnessLabel(option),
                            selected = softness == option,
                            enabled = true,
                        ) { scope.launch { settings.setSoftness(option) } }
                    }
                }
            }

            item { SectionTitle("Filmkorn") }
            item {
                Column {
                    GrainOverride.entries.forEach { option ->
                        SettingsRow(
                            label = grainOverrideLabel(option),
                            selected = grainOverride == option,
                            enabled = true,
                        ) { viewModel.setGrainOverride(option) }
                    }
                    Text(
                        "Mischmodus (wie in Photoshop)",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GrainBlendMode.entries.forEach { mode ->
                            InlineChip(grainBlendModeLabel(mode), grainBlendMode == mode) {
                                viewModel.setGrainBlendMode(mode)
                            }
                        }
                    }
                }
            }

            if (availableFps.isNotEmpty()) {
                item { SectionTitle("Video-Bildrate") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        InlineChip("Auto", videoFps == 0) { viewModel.setFps(0) }
                        availableFps.forEach { fps ->
                            InlineChip("$fps fps", videoFps == fps) { viewModel.setFps(fps) }
                        }
                    }
                }
            }

            item { SectionTitle("Standort") }
            item {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("Standort speichern (GPS)", color = RetroWhite)
                        Switch(
                            checked = locationEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    locationPermissionLauncher.launch(
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                    )
                                } else {
                                    scope.launch { settings.setLocationEnabled(false) }
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = RetroAccent, checkedTrackColor = RetroAccent.copy(alpha = 0.5f)),
                        )
                    }
                    Text(
                        "Aus, solange nicht aktiv - es wird nichts abgefragt oder gespeichert, bevor du das hier einschaltest.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item { SectionTitle("Ort ins Bild einbrennen") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    InlineChip("Aus", locationStampMode == LocationStampMode.OFF) {
                        scope.launch { settings.setLocationStampMode(LocationStampMode.OFF) }
                    }
                    InlineChip("Koordinaten", locationStampMode == LocationStampMode.COORDINATES) {
                        scope.launch { settings.setLocationStampMode(LocationStampMode.COORDINATES) }
                    }
                    InlineChip("Postleitzahl", locationStampMode == LocationStampMode.POSTAL_CODE) {
                        scope.launch { settings.setLocationStampMode(LocationStampMode.POSTAL_CODE) }
                    }
                }
                if (locationStampMode != LocationStampMode.OFF && !locationEnabled) {
                    Text(
                        "Braucht \"Standort speichern\" oben.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item { SectionTitle("Datum ins Bild einbrennen") }
            item {
                DateStampEditor(
                    settings = dateStamp,
                    onChange = { updated -> scope.launch { settings.setDateStampSettings(updated) } },
                )
            }

            item { SectionTitle("Foto-Rezepte") }
            items(recipes) { recipe ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEditRecipe(recipe) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column {
                        Text(recipe.name, color = RetroWhite)
                        Text(recipe.filmSimulation, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { scope.launch { db.recipeDao().delete(recipe) } }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Löschen", tint = Color.Gray)
                    }
                }
                Divider(color = Color(0x33FFFFFF))
            }
            item {
                Text(
                    "+ Neues Rezept",
                    color = RetroAccent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAddRecipe)
                        .padding(vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = RetroAccent,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = when {
                !enabled -> Color.DarkGray
                selected -> RetroAccent
                else -> RetroWhite
            },
        )
        if (selected) Text("✓", color = RetroAccent)
    }
}

private fun optionLabel(s: ShutterSound) = when (s) {
    ShutterSound.OFF -> "Aus"
    ShutterSound.CLICK -> "Click"
    ShutterSound.BEEP -> "Piep"
    ShutterSound.CHUNK -> "Chunk"
}

private fun grainBlendModeLabel(m: GrainBlendMode) = when (m) {
    GrainBlendMode.NORMAL -> "Normal"
    GrainBlendMode.MULTIPLY -> "Multiplizieren"
    GrainBlendMode.FILMKORN -> "Filmkorn"
    GrainBlendMode.LEUCHTEND -> "Leuchtend"
}

private fun grainOverrideLabel(g: GrainOverride) = when (g) {
    GrainOverride.OFF -> "Aus"
    GrainOverride.STANDARD -> "Standard"
    GrainOverride.HEAVY -> "Heavy"
}

private fun softnessLabel(s: Softness) = when (s) {
    Softness.OFF -> "Aus"
    Softness.LOW -> "Niedrig"
    Softness.MEDIUM -> "Mittel"
    Softness.HIGH -> "Hoch"
}

@Composable
private fun InlineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
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
private fun DateStampEditor(settings: DateStampSettings, onChange: (DateStampSettings) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Datum einbrennen", color = RetroWhite)
            Switch(
                checked = settings.enabled,
                onCheckedChange = { onChange(settings.copy(enabled = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = RetroAccent, checkedTrackColor = RetroAccent.copy(alpha = 0.5f)),
            )
        }

        if (settings.enabled) {
            StampPreview(formatDateStamp(Date(), settings), settings)

            Text("Schriftart", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)) {
                InlineChip("7-Segment kursiv", settings.typeface == com.retrocam.app.data.StampTypeface.SEVEN_SEGMENT_ITALIC) {
                    onChange(settings.copy(typeface = com.retrocam.app.data.StampTypeface.SEVEN_SEGMENT_ITALIC))
                }
                InlineChip("7-Segment gerade", settings.typeface == com.retrocam.app.data.StampTypeface.SEVEN_SEGMENT_UPRIGHT) {
                    onChange(settings.copy(typeface = com.retrocam.app.data.StampTypeface.SEVEN_SEGMENT_UPRIGHT))
                }
                InlineChip("Punktmatrix", settings.typeface == com.retrocam.app.data.StampTypeface.DOT_MATRIX) {
                    onChange(settings.copy(typeface = com.retrocam.app.data.StampTypeface.DOT_MATRIX))
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("Führende Nullen (04 statt 4)", color = RetroWhite, style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = settings.zeroPad,
                    onCheckedChange = { onChange(settings.copy(zeroPad = it)) },
                    colors = SwitchDefaults.colors(checkedThumbColor = RetroAccent, checkedTrackColor = RetroAccent.copy(alpha = 0.5f)),
                )
            }

            Text("Reihenfolge", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)) {
                InlineChip("TT.MM.JJ (EU)", settings.order == DateOrder.DMY) { onChange(settings.copy(order = DateOrder.DMY)) }
                InlineChip("MM.TT.JJ (US)", settings.order == DateOrder.MDY) { onChange(settings.copy(order = DateOrder.MDY)) }
                InlineChip("JJ.MM.TT (ISO)", settings.order == DateOrder.YMD) { onChange(settings.copy(order = DateOrder.YMD)) }
            }

            Text("Trennzeichen", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)) {
                InlineChip("Punkt", settings.separator == DateSeparator.DOT) { onChange(settings.copy(separator = DateSeparator.DOT)) }
                InlineChip("Schrägstrich", settings.separator == DateSeparator.SLASH) { onChange(settings.copy(separator = DateSeparator.SLASH)) }
                InlineChip("Leerzeichen", settings.separator == DateSeparator.SPACE) { onChange(settings.copy(separator = DateSeparator.SPACE)) }
                InlineChip("Keins", settings.separator == DateSeparator.NONE) { onChange(settings.copy(separator = DateSeparator.NONE)) }
            }

            Text("Jahr", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)) {
                InlineChip("1999", settings.yearStyle == YearStyle.FULL) { onChange(settings.copy(yearStyle = YearStyle.FULL)) }
                InlineChip("99", settings.yearStyle == YearStyle.SHORT) { onChange(settings.copy(yearStyle = YearStyle.SHORT)) }
                InlineChip("'99", settings.yearStyle == YearStyle.SHORT_APOSTROPHE) { onChange(settings.copy(yearStyle = YearStyle.SHORT_APOSTROPHE)) }
            }

            Text("Farbe", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)) {
                InlineChip("Orange", settings.color == StampColor.ORANGE) { onChange(settings.copy(color = StampColor.ORANGE)) }
                InlineChip("Gelb", settings.color == StampColor.YELLOW) { onChange(settings.copy(color = StampColor.YELLOW)) }
                InlineChip("Rot", settings.color == StampColor.RED) { onChange(settings.copy(color = StampColor.RED)) }
            }

            Text("Ecke", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)) {
                InlineChip("Unten rechts", settings.corner == StampCorner.BOTTOM_RIGHT) { onChange(settings.copy(corner = StampCorner.BOTTOM_RIGHT)) }
                InlineChip("Unten links", settings.corner == StampCorner.BOTTOM_LEFT) { onChange(settings.copy(corner = StampCorner.BOTTOM_LEFT)) }
                InlineChip("Oben rechts", settings.corner == StampCorner.TOP_RIGHT) { onChange(settings.copy(corner = StampCorner.TOP_RIGHT)) }
                InlineChip("Oben links", settings.corner == StampCorner.TOP_LEFT) { onChange(settings.copy(corner = StampCorner.TOP_LEFT)) }
            }

            Text("Mischmodus (wie in Photoshop)", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp, top = 4.dp).horizontalScroll(rememberScrollState()),
            ) {
                com.retrocam.app.data.StampBlendMode.entries.forEach { mode ->
                    InlineChip(mode.label, settings.blendMode == mode) { onChange(settings.copy(blendMode = mode)) }
                }
            }
        }
    }
}

/** A blend mode only reads as anything against real image content - flat
 * black/transparent (what the settings screen's own background is) makes
 * Multiply/Screen/Overlay/etc. either invisible or indistinguishable from
 * NORMAL. This draws over a neutral mid-tone swatch instead, standing in
 * for "an average photo", using the same real android.graphics.Paint +
 * PorterDuffXfermode path PhotoPostProcessor uses on the actual photo -
 * unlike a plain Compose Text, that's the only way to preview the blend
 * mode's actual effect rather than just the glyph shape/color. */
@Composable
private fun StampPreview(text: String, settings: DateStampSettings) {
    val context = LocalContext.current
    val stampColorInt = settings.color.argb.toInt()
    val xfermode = settings.blendMode.porterDuffMode?.let { android.graphics.PorterDuffXfermode(it) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF7A7A7A)),
    ) {
        // Mirrors PhotoPostProcessor.burnInStamp's glow recipe (halo + inner
        // glow + a slightly-blurred base glyph, never pixel-sharp) so the
        // preview actually shows what the real burn-in looks like - this
        // used to be DOT_MATRIX-only with no glow at all on 7-segment,
        // which is exactly the "no bloom" gap that needed fixing here.
        when (settings.typeface) {
            com.retrocam.app.data.StampTypeface.DOT_MATRIX -> {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    val spacing = 7f
                    val dotRadius = spacing * 0.34f
                    fun glow(radius: Float, a: Int) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = stampColorInt
                        this.xfermode = xfermode
                        alpha = a
                        maskFilter = android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    val paint = glow(spacing * 0.12f, 255)
                    val halo = glow(spacing * 3.4f, 90)
                    val innerGlow = glow(spacing * 1.3f, 170)
                    com.retrocam.app.camera.DotMatrixFont.draw(nativeCanvas, text, 12f, 12f, spacing, dotRadius, paint, halo)
                    com.retrocam.app.camera.DotMatrixFont.draw(nativeCanvas, text, 12f, 12f, spacing, dotRadius, paint, innerGlow)
                }
            }
            else -> {
                val fontRes = if (settings.typeface == com.retrocam.app.data.StampTypeface.SEVEN_SEGMENT_ITALIC) {
                    com.retrocam.app.R.font.dseg7_classic_bold_italic
                } else {
                    com.retrocam.app.R.font.dseg7_classic_bold
                }
                val typeface = androidx.core.content.res.ResourcesCompat.getFont(context, fontRes)
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val nativeCanvas = drawContext.canvas.nativeCanvas
                    val basePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        this.typeface = typeface
                        textSize = 34f
                        color = stampColorInt
                        this.xfermode = xfermode
                        maskFilter = android.graphics.BlurMaskFilter(2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    val haloPaint = android.graphics.Paint(basePaint).apply {
                        maskFilter = android.graphics.BlurMaskFilter(30f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                        alpha = 90
                    }
                    val innerGlowPaint = android.graphics.Paint(basePaint).apply {
                        maskFilter = android.graphics.BlurMaskFilter(10f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                        alpha = 170
                    }
                    nativeCanvas.drawText(text, 12f, 40f, haloPaint)
                    nativeCanvas.drawText(text, 12f, 40f, innerGlowPaint)
                    nativeCanvas.drawText(text, 12f, 40f, basePaint)
                }
            }
        }
    }
}
