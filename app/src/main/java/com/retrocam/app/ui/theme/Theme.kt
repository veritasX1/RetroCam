package com.retrocam.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RetroBlack = Color(0xFF000000)
val RetroSurface = Color(0xFF161616)
val RetroWhite = Color(0xFFF2ECE2)
val RetroAccent = Color(0xFFE8B94A) // selected-mode highlight, echoes the reference camera UI
val RetroRibbonRed = Color(0xFFA1382E) // same muted red used across the RetroCam family

private val RetroColorScheme = darkColorScheme(
    background = RetroBlack,
    surface = RetroSurface,
    primary = RetroAccent,
    onBackground = RetroWhite,
    onSurface = RetroWhite,
    secondary = RetroRibbonRed,
)

@Composable
fun RetroCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RetroColorScheme, content = content)
}
