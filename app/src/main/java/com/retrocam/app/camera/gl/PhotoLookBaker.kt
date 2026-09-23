package com.retrocam.app.camera.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLUtils
import com.retrocam.app.camera.GrainTexture
import com.retrocam.app.camera.ShaderSource
import com.retrocam.app.data.RenderLook
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bakes a [RenderLook] into an already-captured, upright photo bitmap at
 * its own full resolution, via one offscreen GL pass reusing the exact
 * same look math as the live preview/video shader (ShaderSource.FRAGMENT
 * via LookRenderer) - just against a plain 2D texture instead of the
 * camera's live OES stream.
 *
 * This exists because ImageCapture is no longer routed through the same
 * live SurfaceProcessor/effect as Preview+VideoCapture (see
 * CameraViewModel.tryBind) - that shared GL stream's Camera2 stream-
 * combination negotiation was silently capping still-photo resolution
 * well below the sensor's real capability (measured: 3264x2448 vs the
 * stock camera app's 4080x3060 on the same device, same sensor). Now
 * ImageCapture binds independently and gets the sensor's true resolution,
 * and this class re-applies the retro look afterward instead of live -
 * one dedicated EGL context per call, entirely synchronous, no
 * persistent GL thread needed (a photo is a one-off, not a video stream).
 */
object PhotoLookBaker {

    /** Must be called from a plain background thread (not one that also
     * juggles another EGL context) - creates and tears down its own EGL
     * context synchronously within this one call. [source] must already
     * be upright (EXIF-orientation-normalized) - see PhotoPostProcessor. */
    fun bake(context: Context, source: Bitmap, look: RenderLook): Bitmap {
        val eglCore = EglCore()
        eglCore.init()
        val pbuffer = eglCore.createPbufferSurface(source.width, source.height)
        eglCore.makeCurrent(pbuffer)
        var program = 0
        var sourceTextureId = -1
        var grainTextureId = -1
        try {
            program = GlUtil.createProgram(ShaderSource.VERTEX, ShaderSource.FRAGMENT_PHOTO)
            GLES20.glUseProgram(program)

            val aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            val aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            val uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            val uTextureLoc = GLES20.glGetUniformLocation(program, "sTexture")
            val uGrainLoc = GLES20.glGetUniformLocation(program, "sGrain")
            val uWarmthLoc = GLES20.glGetUniformLocation(program, "uWarmth")
            val uSaturationLoc = GLES20.glGetUniformLocation(program, "uSaturation")
            val uContrastLoc = GLES20.glGetUniformLocation(program, "uContrast")
            val uHighlightRolloffLoc = GLES20.glGetUniformLocation(program, "uHighlightRolloff")
            val uShadowLiftLoc = GLES20.glGetUniformLocation(program, "uShadowLift")
            val uGrainIntensityLoc = GLES20.glGetUniformLocation(program, "uGrainIntensity")
            val uGrainBlendModeLoc = GLES20.glGetUniformLocation(program, "uGrainBlendMode")
            val uGrainSizeLoc = GLES20.glGetUniformLocation(program, "uGrainSize")
            val uSoftnessLoc = GLES20.glGetUniformLocation(program, "uSoftness")
            val uVignetteLoc = GLES20.glGetUniformLocation(program, "uVignette")
            val uTexelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
            val uGrainOffsetLoc = GLES20.glGetUniformLocation(program, "uGrainOffset")

            GLES20.glViewport(0, 0, source.width, source.height)

            sourceTextureId = uploadTexture(source)
            grainTextureId = if (look.grainSetKey.isNotEmpty()) GrainTexture.upload(context, look.grainSetKey) else -1

            val positionBuffer = GlUtil.fullScreenQuadPositions()
            positionBuffer.position(0)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
            GLES20.glEnableVertexAttribArray(aPositionLoc)

            // Flipped-V variant - see GlUtil.fullScreenQuadTexCoordsFlippedV.
            val texCoordBuffer = GlUtil.fullScreenQuadTexCoordsFlippedV()
            texCoordBuffer.position(0)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 4, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(aTexCoordLoc)

            val identity = FloatArray(16)
            android.opengl.Matrix.setIdentityM(identity, 0)
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, identity, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTextureId)
            GLES20.glUniform1i(uTextureLoc, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (grainTextureId != -1) grainTextureId else sourceTextureId)
            GLES20.glUniform1i(uGrainLoc, 1)

            GLES20.glUniform1f(uWarmthLoc, look.warmth)
            GLES20.glUniform1f(uSaturationLoc, look.saturation)
            GLES20.glUniform1f(uContrastLoc, look.contrast)
            GLES20.glUniform1f(uHighlightRolloffLoc, look.highlightRolloff)
            GLES20.glUniform1f(uShadowLiftLoc, look.shadowLift)
            GLES20.glUniform1f(uGrainIntensityLoc, look.grainIntensity)
            GLES20.glUniform1f(uGrainBlendModeLoc, look.grainBlendMode.shaderValue)
            GLES20.glUniform1f(uGrainSizeLoc, look.grainSize)
            GLES20.glUniform1f(uSoftnessLoc, look.softness)
            GLES20.glUniform1f(uVignetteLoc, look.vignette)
            GLES20.glUniform2f(uTexelSizeLoc, 1f / source.width, 1f / source.height)
            // No prior-frame animation state for a single still - a random
            // offset per shot is enough to avoid always sampling the same
            // patch of the grain plate.
            GLES20.glUniform2f(uGrainOffsetLoc, Math.random().toFloat(), Math.random().toFloat())

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glFinish()

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)

            return readPixelsToBitmap(source.width, source.height)
        } finally {
            if (sourceTextureId != -1) GLES20.glDeleteTextures(1, intArrayOf(sourceTextureId), 0)
            if (grainTextureId != -1) GLES20.glDeleteTextures(1, intArrayOf(grainTextureId), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            eglCore.releaseSurface(pbuffer)
            eglCore.release()
        }
    }

    private fun uploadTexture(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return ids[0]
    }

    private fun readPixelsToBitmap(width: Int, height: Int): Bitmap {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        // glReadPixels is bottom-up (GL's origin is bottom-left); Bitmap
        // rows are top-down - flip to correct for that mismatch.
        val flipped = Bitmap.createBitmap(raw, 0, 0, width, height, Matrix().apply { preScale(1f, -1f) }, false)
        raw.recycle()
        return flipped
    }
}
