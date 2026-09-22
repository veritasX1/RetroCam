package com.retrocam.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils

/**
 * Uploads one still from a real scanned-film grain plate as a GL texture,
 * rather than generating noise procedurally - procedural random noise
 * reads as flat "digital" static because every texel is independent;
 * actual film grain has the clumping/texture of real photochemical grain.
 *
 * Source: real 4K ProRes grain scans from tdcat.com
 * (tdcat.com/downloads/filmgrain), high-pass filtered and downsampled to
 * a handful of 512x512 stills per gauge/tier in `assets/grain/`, named
 * `<gaugeKey>_<standard|heavy>_NN.png` (NN = 01..FRAMES_PER_SET).
 *
 * LOCAL USE ONLY for now - these are third-party assets and tdcat's
 * license needs to be checked before this app (or its asset folder) is
 * ever published/pushed to a public repo.
 */
object GrainTexture {
    private const val FRAMES_PER_SET = 6

    /** [grainSetKey] is e.g. "16mm_standard" or "8mm_heavy" - see
     * RenderLook's grainSetKey mapping. Picks one of the pre-extracted
     * frames at random so repeated uploads (switching recipes back and
     * forth) don't always land on the exact same still. */
    fun upload(context: Context, grainSetKey: String): Int {
        val frameIndex = (1..FRAMES_PER_SET).random()
        val path = "grain/%s_%02d.png".format(grainSetKey, frameIndex)
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Could not decode grain asset: $path")

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        val id = textureIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        // GL_NEAREST keeps individual grain texels crisp rather than
        // smoothing them into soft blobs when sampled.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return id
    }
}
