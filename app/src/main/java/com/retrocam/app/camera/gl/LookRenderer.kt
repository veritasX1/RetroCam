package com.retrocam.app.camera.gl

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.retrocam.app.camera.GrainTexture
import com.retrocam.app.camera.LutTexture
import com.retrocam.app.camera.ShaderSource
import com.retrocam.app.data.RenderLook

/** Compiles the look shader once and draws one full-screen textured quad
 * per frame with it. Must only be touched from the GL thread. */
class LookRenderer {
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTexMatrixLoc = 0
    private var uTextureLoc = 0
    private var uGrainLoc = 0
    private var uWarmthLoc = 0
    private var uSaturationLoc = 0
    private var uContrastLoc = 0
    private var uHighlightRolloffLoc = 0
    private var uShadowLiftLoc = 0
    private var uGrainIntensityLoc = 0
    private var uGrainBlendModeLoc = 0
    private var uGrainSizeLoc = 0
    private var uSoftnessLoc = 0
    private var uVignetteLoc = 0
    private var uTexelSizeLoc = 0
    private var uGrainOffsetLoc = 0
    private var uLutLoc = 0
    private var uLutStrengthLoc = 0
    private var uLutSizeLoc = 0
    private var uLutTilesPerRowLoc = 0

    private var grainTextureId = -1
    private var currentGrainSetKey: String? = null
    private var lutTextureId = -1
    private var currentLutKey: String? = null
    private lateinit var appContext: Context
    private val positionBuffer = GlUtil.fullScreenQuadPositions()
    private val texCoordBuffer = GlUtil.fullScreenQuadTexCoords()
    private var grainOffsetX = 0f
    private var grainOffsetY = 0f

    fun init(context: Context) {
        appContext = context.applicationContext
        program = GlUtil.createProgram(ShaderSource.VERTEX, ShaderSource.FRAGMENT)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "sTexture")
        uGrainLoc = GLES20.glGetUniformLocation(program, "sGrain")
        uWarmthLoc = GLES20.glGetUniformLocation(program, "uWarmth")
        uSaturationLoc = GLES20.glGetUniformLocation(program, "uSaturation")
        uContrastLoc = GLES20.glGetUniformLocation(program, "uContrast")
        uHighlightRolloffLoc = GLES20.glGetUniformLocation(program, "uHighlightRolloff")
        uShadowLiftLoc = GLES20.glGetUniformLocation(program, "uShadowLift")
        uGrainIntensityLoc = GLES20.glGetUniformLocation(program, "uGrainIntensity")
        uGrainBlendModeLoc = GLES20.glGetUniformLocation(program, "uGrainBlendMode")
        uGrainSizeLoc = GLES20.glGetUniformLocation(program, "uGrainSize")
        uSoftnessLoc = GLES20.glGetUniformLocation(program, "uSoftness")
        uVignetteLoc = GLES20.glGetUniformLocation(program, "uVignette")
        uTexelSizeLoc = GLES20.glGetUniformLocation(program, "uTexelSize")
        uGrainOffsetLoc = GLES20.glGetUniformLocation(program, "uGrainOffset")
        uLutLoc = GLES20.glGetUniformLocation(program, "sLut")
        uLutStrengthLoc = GLES20.glGetUniformLocation(program, "uLutStrength")
        uLutSizeLoc = GLES20.glGetUniformLocation(program, "uLutSize")
        uLutTilesPerRowLoc = GLES20.glGetUniformLocation(program, "uLutTilesPerRow")
        // A sensible default so grainTextureId is always a valid texture,
        // even before the first look with grain enabled is drawn.
        grainTextureId = GrainTexture.upload(appContext, "35mm_standard")
        currentGrainSetKey = "35mm_standard"
        // Same reasoning for the LUT unit - which specific LUT is bound
        // here doesn't matter, since drawFrame always forces
        // uLutStrength to 0 whenever look.lutKey is empty (not just
        // whatever look.lutStrength happens to be), so this default is
        // never actually sampled until a look with a real lutKey draws.
        lutTextureId = LutTexture.upload(appContext, "digital_to_film")
        currentLutKey = "digital_to_film"
    }

    /** Creates and returns a new GL_TEXTURE_EXTERNAL_OES texture id, ready
     * to be wrapped by a SurfaceTexture for the camera input. */
    fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun drawFrame(
        oesTextureId: Int,
        texMatrix: FloatArray,
        look: RenderLook,
        viewportWidth: Int,
        viewportHeight: Int,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Swap in the gauge/tier-matched real grain plate only when the
        // active look actually changes it - an empty key (no grain) just
        // leaves whatever texture is already bound, since the shader skips
        // sampling it entirely when grainIntensity is 0.
        if (look.grainSetKey.isNotEmpty() && look.grainSetKey != currentGrainSetKey) {
            val newId = GrainTexture.upload(appContext, look.grainSetKey)
            if (grainTextureId != -1) {
                GLES20.glDeleteTextures(1, intArrayOf(grainTextureId), 0)
            }
            grainTextureId = newId
            currentGrainSetKey = look.grainSetKey
        }
        if (look.lutKey.isNotEmpty() && look.lutKey != currentLutKey) {
            val newId = LutTexture.upload(appContext, look.lutKey)
            if (lutTextureId != -1) {
                GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            }
            lutTextureId = newId
            currentLutKey = look.lutKey
        }

        positionBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 4, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)

        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, grainTextureId)
        GLES20.glUniform1i(uGrainLoc, 1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
        GLES20.glUniform1i(uLutLoc, 2)
        // Forced to 0 whenever there's no real LUT selected, regardless
        // of look.lutStrength's own value - see the comment on the
        // default LUT upload in init().
        GLES20.glUniform1f(uLutStrengthLoc, if (look.lutKey.isEmpty()) 0f else look.lutStrength)
        GLES20.glUniform1f(uLutSizeLoc, LutTexture.SIZE)
        GLES20.glUniform1f(uLutTilesPerRowLoc, LutTexture.TILES_PER_ROW)

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
        // uTexelSize drives texel-based sampling math (softness blur
        // radius, grain sizing) against sTexture, the INPUT camera stream
        // - it must be 1/input size, not 1/output size. Those aren't the
        // same thing: this single input stream feeds every attached
        // output (preview, video, photo) at whatever resolution each one
        // independently renders to, so using the output's own size here
        // silently mis-scaled softness/grain per use case (e.g. over-
        // blurring a lower-res preview relative to a higher-res photo,
        // or vice versa depending on which is actually larger).
        GLES20.glUniform2f(uTexelSizeLoc, 1f / inputWidth.coerceAtLeast(1), 1f / inputHeight.coerceAtLeast(1))

        grainOffsetX = (grainOffsetX + 0.037f) % 1f
        grainOffsetY = (grainOffsetY + 0.071f) % 1f
        GLES20.glUniform2f(uGrainOffsetLoc, grainOffsetX, grainOffsetY)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (grainTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(grainTextureId), 0)
            grainTextureId = -1
        }
        if (lutTextureId != -1) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            lutTextureId = -1
        }
    }
}
