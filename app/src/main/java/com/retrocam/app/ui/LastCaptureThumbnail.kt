package com.retrocam.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small "what did I just shoot" preview in the corner of the viewfinder -
 * without it there's no way to tell whether the last shot came out okay
 * without leaving the camera. Tapping a photo opens the in-app editor
 * ([onClick]); videos aren't editable here, so the caller wires that tap
 * to the system player instead - see CameraScreen.
 */
@Composable
fun LastCaptureThumbnail(uri: Uri, isVideo: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadThumbnail(context, uri) }.getOrNull()
        }
    }

    val bmp = bitmap
    Box(
        modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x33FFFFFF))
            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = "Letzte Aufnahme",
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun loadThumbnail(context: android.content.Context, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= 29) {
        return context.contentResolver.loadThumbnail(uri, Size(160, 160), null)
    }
    // Pre-29 fallback: decode a downsampled bitmap ourselves.
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1
    while (opts.outWidth / sample > 320) sample *= 2
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
}
