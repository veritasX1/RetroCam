package com.retrocam.app.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.retrocam.app.camera.gl.VideoLookBaker
import com.retrocam.app.data.RenderLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs after a video is already saved (raw, un-graded - see
 * CameraViewModel.tryBind for why VideoCapture no longer goes through a
 * live GL effect): bakes the active look into it via VideoLookBaker, then
 * atomically replaces the MediaStore file's content with the graded
 * result.
 *
 * "Atomically" here means: the transcode writes to a private temp file
 * first, entirely separate from the original. The original MediaStore
 * file's bytes are only ever touched (via a stream copy) once the temp
 * file is a fully complete, valid replacement - if the transcode itself
 * fails or throws partway through (a real possibility with MediaCodec:
 * unsupported format quirks, OOM on a long clip, encoder/decoder set-up
 * failures), the original raw recording is never touched and the user
 * keeps an un-graded but intact video, instead of a corrupted one - same
 * principle PhotoPostProcessor already follows for photos.
 */
object VideoPostProcessor {

    suspend fun process(context: Context, uri: Uri, look: RenderLook) = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("bake_", ".mp4", context.cacheDir)
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                VideoLookBaker.bake(context, pfd.fileDescriptor, look, tempFile)
            } ?: return@withContext

            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            runCatching {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.SIZE, tempFile.length())
                        put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    },
                    null,
                    null,
                )
            }
        } finally {
            tempFile.delete()
        }
    }
}
