package com.retrocam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.retrocam.app.data.DYNAMIC_RANGE_OPTIONS
import com.retrocam.app.data.EffectStrength
import com.retrocam.app.data.FilmSimulationBase
import com.retrocam.app.data.GrainSize
import com.retrocam.app.data.GrainStrength
import com.retrocam.app.data.Recipe
import com.retrocam.app.data.RetroCamDatabase
import com.retrocam.app.ui.theme.RetroAccent
import com.retrocam.app.ui.theme.RetroWhite
import kotlinx.coroutines.launch

/**
 * Field labels intentionally mirror Fuji X Weekly's own wording, so a
 * recipe can be transcribed straight from a web page without translating
 * terminology - see README "Adding your own recipes".
 */
@Composable
fun RecipeEditorScreen(existing: Recipe?, onDone: () -> Unit) {
    val context = LocalContext.current
    val db = remember(context) { RetroCamDatabase.get(context) }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var filmSimulation by remember { mutableStateOf(existing?.filmSimulation ?: "Provia/Standard") }
    var dynamicRange by remember { mutableStateOf(existing?.dynamicRange ?: "DR100") }
    var grainStrength by remember { mutableStateOf(existing?.grainStrength ?: GrainStrength.OFF) }
    var grainSize by remember { mutableStateOf(existing?.grainSize ?: GrainSize.SMALL) }
    var colorChromeEffect by remember { mutableStateOf(existing?.colorChromeEffect ?: EffectStrength.OFF) }
    var colorChromeFxBlue by remember { mutableStateOf(existing?.colorChromeFxBlue ?: EffectStrength.OFF) }
    var whiteBalance by remember { mutableStateOf(existing?.whiteBalance ?: "Auto") }
    var wbShiftRed by remember { mutableStateOf((existing?.wbShiftRed ?: 0).toString()) }
    var wbShiftBlue by remember { mutableStateOf((existing?.wbShiftBlue ?: 0).toString()) }
    var highlight by remember { mutableStateOf((existing?.highlight ?: 0f).toString()) }
    var shadow by remember { mutableStateOf((existing?.shadow ?: 0f).toString()) }
    var color by remember { mutableStateOf((existing?.color ?: 0).toString()) }
    var sharpness by remember { mutableStateOf((existing?.sharpness ?: 0).toString()) }
    var highIsoNr by remember { mutableStateOf((existing?.highIsoNr ?: 0).toString()) }
    var clarity by remember { mutableStateOf((existing?.clarity ?: 0).toString()) }
    var isoNote by remember { mutableStateOf(existing?.isoNote ?: "Auto") }
    var exposureCompensation by remember { mutableStateOf(existing?.exposureCompensation ?: "0") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    Column(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück", tint = RetroWhite)
            }
            Text(
                if (existing == null) "Neues Rezept" else "Rezept bearbeiten",
                color = RetroWhite,
                style = MaterialTheme.typography.titleLarge,
            )
        }

        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            item { Field("Name", name) { name = it } }
            item {
                EnumRow(
                    "Film Simulation",
                    FilmSimulationBase.entries.map { it.displayName },
                    filmSimulation,
                    { it },
                ) { filmSimulation = it }
            }
            item { EnumRow("Dynamic Range", DYNAMIC_RANGE_OPTIONS, dynamicRange, { it }) { dynamicRange = it } }
            item { EnumRow("Grain Effect", GrainStrength.entries, grainStrength, { it.name }) { grainStrength = it } }
            item { EnumRow("Grain Size", GrainSize.entries, grainSize, { it.name }) { grainSize = it } }
            item { EnumRow("Color Chrome Effect", EffectStrength.entries, colorChromeEffect, { it.name }) { colorChromeEffect = it } }
            item { EnumRow("Color Chrome FX Blue", EffectStrength.entries, colorChromeFxBlue, { it.name }) { colorChromeFxBlue = it } }
            item { Field("White Balance", whiteBalance) { whiteBalance = it } }
            item { Field("WB Shift Red (-9..9)", wbShiftRed) { wbShiftRed = it } }
            item { Field("WB Shift Blue (-9..9)", wbShiftBlue) { wbShiftBlue = it } }
            item { Field("Highlight", highlight) { highlight = it } }
            item { Field("Shadow", shadow) { shadow = it } }
            item { Field("Color", color) { color = it } }
            item { Field("Sharpness", sharpness) { sharpness = it } }
            item { Field("High ISO NR", highIsoNr) { highIsoNr = it } }
            item { Field("Clarity", clarity) { clarity = it } }
            item { Field("ISO", isoNote) { isoNote = it } }
            item { Field("Exposure Compensation", exposureCompensation) { exposureCompensation = it } }
            item { Field("Notes / Quelle", notes, singleLine = false) { notes = it } }
        }

        Text(
            if (existing == null) "Rezept speichern" else "Änderungen speichern",
            color = Color.Black,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(RetroAccent)
                .clickable {
                    val recipe = (existing ?: Recipe(name = "")).copy(
                        name = name.ifBlank { "Unbenannt" },
                        filmSimulation = filmSimulation,
                        dynamicRange = dynamicRange,
                        grainStrength = grainStrength,
                        grainSize = grainSize,
                        colorChromeEffect = colorChromeEffect,
                        colorChromeFxBlue = colorChromeFxBlue,
                        whiteBalance = whiteBalance,
                        wbShiftRed = wbShiftRed.toIntOrNull() ?: 0,
                        wbShiftBlue = wbShiftBlue.toIntOrNull() ?: 0,
                        highlight = highlight.toFloatOrNull() ?: 0f,
                        shadow = shadow.toFloatOrNull() ?: 0f,
                        color = color.toIntOrNull() ?: 0,
                        sharpness = sharpness.toIntOrNull() ?: 0,
                        highIsoNr = highIsoNr.toIntOrNull() ?: 0,
                        clarity = clarity.toIntOrNull() ?: 0,
                        isoNote = isoNote,
                        exposureCompensation = exposureCompensation,
                        notes = notes,
                        isBuiltIn = existing?.isBuiltIn ?: false,
                    )
                    scope.launch {
                        if (existing == null) db.recipeDao().insert(recipe) else db.recipeDao().update(recipe)
                        onDone()
                    }
                }
                .padding(14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun Field(label: String, value: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = RetroWhite,
            unfocusedTextColor = RetroWhite,
            focusedBorderColor = RetroAccent,
            unfocusedBorderColor = Color.Gray,
            focusedLabelColor = RetroAccent,
            unfocusedLabelColor = Color.Gray,
        ),
    )
}

@Composable
private fun <T> EnumRow(label: String, options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Text(
                    name(option),
                    color = if (isSelected) Color.Black else RetroWhite,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isSelected) RetroAccent else Color(0x33FFFFFF))
                        .clickable { onSelect(option) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
