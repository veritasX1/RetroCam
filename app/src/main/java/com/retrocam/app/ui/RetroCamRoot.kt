package com.retrocam.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.net.Uri
import com.retrocam.app.camera.CameraViewModel
import com.retrocam.app.data.Recipe

private sealed interface Screen {
    data object Camera : Screen
    data object Settings : Screen
    data class EditRecipe(val recipe: Recipe?) : Screen
    data class EditPhoto(val uri: Uri) : Screen
}

@Composable
fun RetroCamRoot(viewModel: CameraViewModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }

    // A system back-swipe/press should step back within the app (edit
    // recipe -> settings -> camera) instead of falling through to the
    // default behavior and closing the whole app. Only the camera screen
    // (nothing left to go back to) lets that default behavior happen.
    BackHandler(enabled = screen !is Screen.Camera) {
        screen = when (screen) {
            is Screen.EditRecipe -> Screen.Settings
            is Screen.EditPhoto -> Screen.Camera
            is Screen.Settings -> Screen.Camera
            is Screen.Camera -> Screen.Camera
        }
    }

    // CameraScreen (and the PreviewView/camera pipeline it owns) stays
    // composed at all times instead of being torn down and rebuilt every
    // time Settings/the recipe editor opens - Settings and the editor are
    // drawn as an opaque full-screen overlay on top of it instead. Besides
    // avoiding a pointless full camera-pipeline rebind on every navigation,
    // this closes off the exact rebind race that used to crash the GL
    // thread (a fresh PreviewView -> bindCamera() while the previous
    // processor's GL thread could still be mid-teardown).
    Box(Modifier.fillMaxSize()) {
        CameraScreen(
            viewModel = viewModel,
            onOpenSettings = { screen = Screen.Settings },
            onOpenPhotoEditor = { uri -> screen = Screen.EditPhoto(uri) },
        )
        when (val s = screen) {
            is Screen.Camera -> Unit
            is Screen.Settings -> SettingsScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.Camera },
                onAddRecipe = { screen = Screen.EditRecipe(null) },
                onEditRecipe = { screen = Screen.EditRecipe(it) },
            )
            is Screen.EditRecipe -> RecipeEditorScreen(
                existing = s.recipe,
                onDone = { screen = Screen.Settings },
            )
            is Screen.EditPhoto -> PhotoEditorScreen(
                uri = s.uri,
                onDone = { screen = Screen.Camera },
            )
        }
    }
}
