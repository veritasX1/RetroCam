package com.retrocam.app.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.retrocam.app.camera.CameraOption
import com.retrocam.app.camera.CameraViewModel
import com.retrocam.app.data.CaptureMode
import com.retrocam.app.data.FilmStockPreset
import com.retrocam.app.data.NO_FILTER_ID
import com.retrocam.app.data.Recipe
import com.retrocam.app.ui.theme.RetroAccent
import com.retrocam.app.ui.theme.RetroRibbonRed
import com.retrocam.app.ui.theme.RetroWhite

private const val NO_FILTER_LABEL = "Kein Filter"
private val NO_FILTER_RECIPE = Recipe(id = NO_FILTER_ID, name = NO_FILTER_LABEL)
private val NO_FILTER_FILM_STOCK = FilmStockPreset(id = NO_FILTER_ID, name = NO_FILTER_LABEL, gauge = "")

@Composable
fun CameraScreen(viewModel: CameraViewModel, onOpenSettings: () -> Unit, onOpenPhotoEditor: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mode by viewModel.mode.collectAsState()
    val recipes by viewModel.recipes.collectAsState()
    val filmStocks by viewModel.filmStocks.collectAsState()
    val selectedRecipeId by viewModel.selectedRecipeId.collectAsState()
    val selectedFilmStockId by viewModel.selectedFilmStockId.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingElapsedMs by viewModel.recordingElapsedMs.collectAsState()
    val availableCameras by viewModel.availableCameras.collectAsState()
    val activeCameraKey by viewModel.activeCameraKey.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val lastCaptureUri by viewModel.lastCaptureUri.collectAsState()
    val lastCaptureIsVideo by viewModel.lastCaptureIsVideo.collectAsState()
    val uiLockedAfterRebind by viewModel.uiLockedAfterRebind.collectAsState()
    val hasFlash by viewModel.hasFlash.collectAsState()
    val flashEnabled by viewModel.flashEnabled.collectAsState()

    val displayRecipes = listOf(NO_FILTER_RECIPE) + recipes
    val displayFilmStocks = listOf(NO_FILTER_FILM_STOCK) + filmStocks

    // Photo/video capture needs the CURRENT physical rotation at the
    // moment of the shot, not just whatever rotation the camera happened
    // to be bound at (see CameraViewModel.updateTargetRotation) - a plain
    // Configuration-based landscape/portrait check (as used below for the
    // chip layout) can't tell normal from reverse landscape/portrait apart,
    // so this listens to the raw sensor angle instead, snapped to the
    // nearest quarter turn the same way CameraX's own samples do.
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientationDegrees: Int) {
                if (orientationDegrees == OrientationEventListener.ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientationDegrees >= 315 || orientationDegrees < 45 -> Surface.ROTATION_0
                    orientationDegrees < 135 -> Surface.ROTATION_270
                    orientationDegrees < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                viewModel.updateTargetRotation(rotation)
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    val activeName = when (mode) {
        CaptureMode.PHOTO -> displayRecipes.firstOrNull { it.id == selectedRecipeId }?.name ?: NO_FILTER_LABEL
        CaptureMode.VIDEO -> displayFilmStocks.firstOrNull { it.id == selectedFilmStockId }?.name ?: NO_FILTER_LABEL
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        viewModel.setZoomRatio(viewModel.zoomRatio.value * zoom)
                    }
                },
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    viewModel.bindCamera(ctx, lifecycleOwner, previewView)
                }
            },
        )

        // Top bar
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(activeName, color = RetroWhite, fontWeight = FontWeight.Medium)
                    if (isRecording) {
                        Text(formatElapsed(recordingElapsedMs), color = RetroRibbonRed, fontWeight = FontWeight.Bold)
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Einstellungen", tint = RetroWhite)
                }
            }
            if (availableCameras.size > 1 || hasFlash) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableCameras.forEach { option ->
                        // Switching the physical camera mid-recording isn't
                        // supported (see CameraViewModel.selectCamera), and
                        // stacking a second switch onto one that's still
                        // settling is what reliably reproduces the
                        // SurfaceTexture race - the chips are dimmed and
                        // inert in both cases instead of silently
                        // swallowing the tap.
                        CameraChip(
                            option.label,
                            option.persistenceKey == activeCameraKey,
                            enabled = !isRecording && !uiLockedAfterRebind,
                        ) {
                            viewModel.selectCamera(option)
                        }
                    }
                    // Only shown for a camera that actually reports a
                    // flash unit (mainly the back camera - most phones'
                    // front camera has none) - see CameraViewModel
                    // .toggleFlash for why this drives the torch rather
                    // than ImageCapture's own per-shot flash mode.
                    if (hasFlash) {
                        CameraChip("Blitz", flashEnabled, enabled = !uiLockedAfterRebind) {
                            viewModel.toggleFlash()
                        }
                    }
                }
            }
        }

        // Bottom (portrait) / right-side (landscape) controls
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val filterChips: @Composable () -> Unit = {
            when (mode) {
                CaptureMode.PHOTO -> displayRecipes.forEach { recipe ->
                    Chip(recipe.name, recipe.id == selectedRecipeId, enabled = !uiLockedAfterRebind) { viewModel.selectRecipe(recipe.id) }
                }
                CaptureMode.VIDEO -> displayFilmStocks.forEach { stock ->
                    Chip(stock.name, stock.id == selectedFilmStockId, enabled = !uiLockedAfterRebind) { viewModel.selectFilmStock(stock.id) }
                }
            }
        }
        // Photos open the in-app editor; videos aren't editable there, so
        // they still fall back to the system player like before.
        val onThumbnailClick: () -> Unit = onThumbnailClick@{
            val uri = lastCaptureUri ?: return@onThumbnailClick
            if (!lastCaptureIsVideo) {
                onOpenPhotoEditor(uri)
                return@onThumbnailClick
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (t: Throwable) {
                android.util.Log.e("RetroCam", "Could not open last capture", t)
            }
        }
        val modeLabels: @Composable () -> Unit = {
            ModeLabel("PHOTO", mode == CaptureMode.PHOTO) { viewModel.selectMode(CaptureMode.PHOTO) }
            androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
            ModeLabel("VIDEO", mode == CaptureMode.VIDEO) { viewModel.selectMode(CaptureMode.VIDEO) }
        }
        // Portrait only - landscape lays the shutter and thumbnail out as a
        // plain spaced Column instead (see below), since cramming both
        // into one small overlapping Box there is what caused the
        // thumbnail to end up glued onto/right next to the shutter.
        val shutterAndThumbnail: @Composable BoxScope.() -> Unit = {
            lastCaptureUri?.let { uri ->
                LastCaptureThumbnail(
                    uri = uri,
                    isVideo = lastCaptureIsVideo,
                    modifier = Modifier.align(Alignment.TopStart),
                    onClick = onThumbnailClick,
                )
            }
            ShutterButton(
                mode = mode,
                isRecording = isRecording,
                onPhoto = { viewModel.takePhoto(context) {} },
                onStartVideo = { viewModel.startRecording(context) {} },
                onStopVideo = { viewModel.stopRecording() },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (isLandscape) {
            // The shutter is pinned to the true screen center, not just
            // "centered within its own Column" (those aren't the same
            // thing once modeLabels/thumbnail add asymmetric content above
            // or below it - that's what pushed it off-center before). The
            // trick: modeLabels and the thumbnail slot are each wrapped in
            // a fixed 56dp box, so the column reads as
            // [56dp][24dp gap][76dp shutter][24dp gap][56dp] - perfectly
            // symmetric around the shutter regardless of modeLabels' own
            // text height or whether a thumbnail exists yet - so centering
            // the whole column also centers the shutter exactly, and
            // modeLabels/thumbnail land on the same horizontal line as it
            // for free (same CenterHorizontally column).
            Row(
                Modifier.fillMaxHeight().align(Alignment.CenterEnd).navigationBarsPadding().padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxHeight(0.7f).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { filterChips() }
                    }
                }
                Box(Modifier.fillMaxHeight()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column(
                            modifier = Modifier.height(56.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) { modeLabels() }
                        ShutterButton(
                            mode = mode,
                            isRecording = isRecording,
                            onPhoto = { viewModel.takePhoto(context) {} },
                            onStartVideo = { viewModel.startRecording(context) {} },
                            onStopVideo = { viewModel.stopRecording() },
                        )
                        // Invisible - only here so the column above/below
                        // the shutter stays symmetric (keeping it exactly
                        // centered) regardless of whether a thumbnail
                        // exists. The actual thumbnail is rendered as a
                        // separate sibling below, further down than this
                        // slot alone would place it - see the offset on it.
                        androidx.compose.foundation.layout.Spacer(Modifier.height(56.dp))
                    }
                    // Positioned independently of the centering Column
                    // above so it can sit further down than plain
                    // symmetry would put it: the user wanted its distance
                    // to the bottom edge to match its distance to the
                    // right edge (~30dp, measured on this screen at
                    // 2400x1080/400dpi - right edge distance is fixed by
                    // the Row's own end-padding plus being centered under
                    // the wider shutter, so this offset is what makes the
                    // bottom distance catch up to match it), while
                    // staying on the same horizontal line as the shutter
                    // (same Alignment.Center x).
                    lastCaptureUri?.let { uri ->
                        LastCaptureThumbnail(
                            uri = uri,
                            isVideo = lastCaptureIsVideo,
                            modifier = Modifier.align(Alignment.Center).offset(y = 158.dp),
                            onClick = onThumbnailClick,
                        )
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
            ) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { filterChips() }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    modeLabels()
                }

                Box(
                    Modifier.fillMaxWidth().padding(top = 20.dp, start = 16.dp, end = 16.dp),
                    content = shutterAndThumbnail,
                )
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun Chip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background((if (selected) RetroAccent else Color(0x66FFFFFF)).copy(alpha = alpha))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = (if (selected) Color.Black else RetroWhite).copy(alpha = alpha),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun CameraChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background((if (selected) RetroAccent else Color(0x33FFFFFF)).copy(alpha = alpha))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            color = (if (selected) Color.Black else RetroWhite).copy(alpha = alpha),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ModeLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) RetroAccent else RetroWhite,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ShutterButton(
    mode: CaptureMode,
    isRecording: Boolean,
    onPhoto: () -> Unit,
    onStartVideo: () -> Unit,
    onStopVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (mode == CaptureMode.VIDEO) RetroRibbonRed else RetroWhite
    Box(
        modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .padding(4.dp)
            .clip(CircleShape)
            .background(Color.Transparent)
            .clickable {
                when (mode) {
                    CaptureMode.PHOTO -> onPhoto()
                    CaptureMode.VIDEO -> if (isRecording) onStopVideo() else onStartVideo()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (mode == CaptureMode.VIDEO && isRecording) 32.dp else 60.dp)
                .clip(if (mode == CaptureMode.VIDEO && isRecording) RoundedCornerShape(8.dp) else CircleShape)
                .background(color),
        )
    }
}
