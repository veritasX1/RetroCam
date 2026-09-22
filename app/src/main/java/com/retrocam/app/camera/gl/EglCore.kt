package com.retrocam.app.camera.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.Surface

/**
 * Minimal EGL context/surface management, following the standard pattern
 * used across most Android camera-filter apps (e.g. Grafika's EglCore) -
 * this is boilerplate, not app-specific logic.
 */
class EglCore {
    var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private set
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    fun init() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) error("Unable to get EGL14 display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            error("Unable to initialize EGL14")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            error("Unable to find a suitable EGL config")
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) error("Unable to create EGL context")
    }

    /** Off-screen 1x1 surface used only so a context can be current while
     * doing setup work (compiling shaders, creating textures) before any
     * real output surface exists yet. */
    fun createOffscreenSurface(): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        return EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, attribs, 0)
            ?: error("Unable to create EGL pbuffer surface")
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val attribs = intArrayOf(EGL14.EGL_NONE)
        return EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
            ?: error("Unable to create EGL window surface")
    }

    fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            error("eglMakeCurrent failed")
        }
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(eglDisplay, surface)

    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, surface)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
    }
}
