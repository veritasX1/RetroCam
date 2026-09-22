package com.retrocam.app.camera.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.retrocam.app.data.RenderLook
import java.util.concurrent.Executor

private const val TAG = "RetroCamGL"

/**
 * CameraX [SurfaceProcessor] that renders every camera frame through
 * [LookRenderer] before handing it to whichever downstream use case(s)
 * (Preview, VideoCapture) this effect is attached to - see
 * RetroCamCameraEffect. One input SurfaceTexture, any number of output
 * surfaces (one per attached use case), all driven from a single
 * dedicated GL thread.
 *
 * [lookProvider] is read fresh on every frame, so switching the active
 * recipe/film stock in the UI takes effect immediately on the next frame
 * without having to rebuild the pipeline.
 */
class RetroCamSurfaceProcessor(
    context: Context,
    private val lookProvider: () -> RenderLook,
) : SurfaceProcessor {
    private val appContext = context.applicationContext

    private val glThread = HandlerThread("RetroCamGL").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val glExecutor = Executor { command -> glHandler.post(command) }

    private val eglCore = EglCore()
    private val renderer = LookRenderer()
    private var setupSurface: android.opengl.EGLSurface? = null
    private var oesTextureId = -1

    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val texMatrix = FloatArray(16)
    private val transformedMatrix = FloatArray(16)

    private data class Output(
        val surfaceOutput: SurfaceOutput,
        val eglSurface: android.opengl.EGLSurface,
        val size: Size,
    )

    private val outputs = mutableListOf<Output>()

    // Set (on the GL thread) once release() has been requested. Every
    // GL-thread entry point checks this first, and the actual EGL/
    // SurfaceTexture teardown only happens once CameraX has confirmed
    // (via the input/output close callbacks below) that it's done with
    // each surface - not immediately when release() is called. Forcibly
    // releasing a SurfaceTexture/Surface while the camera's own capture
    // session might still be mid-teardown races native BufferQueue code
    // and was the actual cause of a SIGSEGV on this thread during a
    // camera switch (or any other rebind, e.g. navigating away and back).
    @Volatile private var releaseRequested = false
    private var finishedRelease = false

    init {
        glHandler.post {
            eglCore.init()
            setupSurface = eglCore.createOffscreenSurface().also { eglCore.makeCurrent(it) }
            renderer.init(appContext)
            oesTextureId = renderer.createOesTexture()
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        glHandler.post {
            if (releaseRequested) { request.willNotProvideSurface(); return@post }
            val size = request.resolution
            val texture = SurfaceTexture(oesTextureId)
            texture.setDefaultBufferSize(size.width, size.height)
            texture.setOnFrameAvailableListener({ st -> glHandler.post { drawFrame(st) } }, glHandler)
            val surface = Surface(texture)
            inputSurfaceTexture = texture
            inputSurface = surface

            request.provideSurface(surface, glExecutor) { result ->
                // This callback firing is CameraX's own, authoritative signal
                // that the camera is done with this surface - releasing here
                // (rather than eagerly from release()) is what keeps this
                // safe against the camera's capture session teardown.
                Log.d(TAG, "Input surface result: ${result.resultCode}")
                glHandler.post {
                    texture.release()
                    surface.release()
                    if (inputSurfaceTexture === texture) inputSurfaceTexture = null
                    if (inputSurface === surface) inputSurface = null
                    maybeFinishRelease()
                }
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        glHandler.post {
            if (releaseRequested) { surfaceOutput.close(); return@post }
            val surface = surfaceOutput.getSurface(glExecutor) { event ->
                glHandler.post {
                    val output = outputs.find { it.surfaceOutput === surfaceOutput }
                    outputs.removeAll { it.surfaceOutput === surfaceOutput }
                    output?.let { eglCore.releaseSurface(it.eglSurface) }
                    maybeFinishRelease()
                }
            }
            val eglSurface = eglCore.createWindowSurface(surface)
            outputs.add(Output(surfaceOutput, eglSurface, surfaceOutput.size))
        }
    }

    private fun drawFrame(surfaceTexture: SurfaceTexture) {
        if (releaseRequested) return
        if (inputSurfaceTexture !== surfaceTexture) return // stale callback from a torn-down input
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(texMatrix)
        val look = lookProvider()
        for (output in outputs.toList()) {
            output.surfaceOutput.updateTransformMatrix(transformedMatrix, texMatrix)
            eglCore.makeCurrent(output.eglSurface)
            renderer.drawFrame(oesTextureId, transformedMatrix, look, output.size.width, output.size.height)
            eglCore.swapBuffers(output.eglSurface)
        }
    }

    private var onReleased: (() -> Unit)? = null

    /**
     * Requests release: stops accepting new input/output surfaces
     * immediately, but only tears down the shared EGL context/GL thread
     * once every surface CameraX handed us has actually been confirmed
     * closed (via the callbacks above) - or after a short grace period, in
     * case CameraX never ends up calling one back (e.g. it was requested
     * but the bind failed before attaching). [onReleased] fires (on the GL
     * thread) once that has actually happened - the caller should wait for
     * it before starting a new camera session, rather than tearing this
     * processor down and binding a new one at the same time: doing both at
     * once raced the camera's own capture-session teardown and crashed the
     * GL thread.
     */
    fun release(onReleased: () -> Unit = {}) {
        glHandler.post {
            releaseRequested = true
            this.onReleased = onReleased
            outputs.toList().forEach { it.surfaceOutput.close() }
            maybeFinishRelease()
            glHandler.postDelayed({ finishRelease() }, 1500)
        }
    }

    private fun maybeFinishRelease() {
        if (releaseRequested && inputSurfaceTexture == null && inputSurface == null && outputs.isEmpty()) {
            finishRelease()
        }
    }

    private fun finishRelease() {
        if (finishedRelease) return
        finishedRelease = true
        outputs.forEach { eglCore.releaseSurface(it.eglSurface) }
        outputs.clear()
        // Deliberately NOT calling inputSurfaceTexture?.release() /
        // inputSurface?.release() here. Those must only ever be released
        // from onInputSurface's own provideSurface() result callback -
        // CameraX's own, authoritative signal that it (and the camera HAL
        // underneath it) is actually done with this surface. A camera
        // HAL open/close cycle (e.g. switching between physical cameras)
        // can legitimately take longer than this method's grace-period
        // fallback used to wait, so forcing a release here as a "safety
        // net" could - and, confirmed via a symbolized tombstone, did -
        // call SurfaceTexture.release() while the HAL was still mid
        // teardown, which segfaults natively inside libgui's
        // ConsumerBase::abandon(). No try/catch can protect against a
        // native SIGSEGV, so the only real fix is to never force it: if
        // CameraX's callback never arrives, this SurfaceTexture/Surface
        // pair is simply leaked (garbage-collected once CameraX finally
        // does let go of it) rather than risk that crash.
        inputSurfaceTexture = null
        inputSurface = null
        setupSurface?.let { eglCore.releaseSurface(it) }
        renderer.release()
        eglCore.release()
        glThread.quitSafely()
        onReleased?.invoke()
        onReleased = null
    }
}
