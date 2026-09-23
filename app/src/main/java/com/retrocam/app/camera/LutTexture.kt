package com.retrocam.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils

/**
 * Uploads a creative color-grade LUT as a GL texture - a standard 3D
 * color-cube lookup table (as exported by DaVinci Resolve/most color
 * grading tools, .cube format), pre-baked *offline* (not on-device) into
 * a square 2D grid of B-axis slices - see ShaderSource's `applyLut` for
 * the sampling side, and the session notes on how these particular PNGs
 * were generated from .cube files (a one-off Python conversion, not
 * checked into this repo as a build step).
 *
 * These specific LUTs are free downloads from cinecolor.io - see the
 * README's asset licensing note and the in-app "Cinematic Look" settings
 * section for the required source attribution.
 */
object LutTexture {
    /** Every bundled LUT PNG today is a 33x33x33 .cube baked into a 6x6
     * tile grid - see the class doc on RenderLook.lutKey for why this
     * isn't per-key metadata yet. */
    const val SIZE = 33f
    const val TILES_PER_ROW = 6f

    fun upload(context: Context, lutKey: String): Int {
        val path = "luts/$lutKey.png"
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Could not decode LUT asset: $path")

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        val id = textureIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        // LINEAR within a tile (smooth R/G interpolation, matching the
        // shader's reliance on the texture's own bilinear filtering for
        // those two axes) but CLAMP_TO_EDGE, not REPEAT - unlike grain,
        // sampling must never wrap across tile boundaries into a
        // neighboring, unrelated B-slice.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return id
    }
}
