package com.retrocam.app.camera.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GlUtil {
    fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        check(program != 0) { "Could not create GL program" }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("Could not link program: $log")
        }
        return program
    }

    private fun loadShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Could not create shader of type $type" }
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Could not compile shader $type: $log")
        }
        return shader
    }

    /** Full-screen quad, position (xy) interleaved with texcoord (uv). */
    fun fullScreenQuadPositions(): FloatBuffer = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )

    fun fullScreenQuadTexCoords(): FloatBuffer = floatBufferOf(
        0f, 0f, 0f, 1f,
        1f, 0f, 0f, 1f,
        0f, 1f, 0f, 1f,
        1f, 1f, 0f, 1f,
    )

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }
}
