package com.example.orb.gl

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

private const val TAG = "AIOrbGL"

class GlShaderProgram(vertexSrc: String, fragmentSrc: String, val label: String) {
  val programId: Int
  private val uniformCache = HashMap<String, Int>(32)

  init {
    val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc, "$label.vert")
    val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc, "$label.frag")
    val pid = GLES30.glCreateProgram()
    GLES30.glAttachShader(pid, vs)
    GLES30.glAttachShader(pid, fs)
    GLES30.glLinkProgram(pid)

    val linkStatus = IntArray(1)
    GLES30.glGetProgramiv(pid, GLES30.GL_LINK_STATUS, linkStatus, 0)
    if (linkStatus[0] == 0) {
      val log = GLES30.glGetProgramInfoLog(pid)
      GLES30.glDeleteShader(vs)
      GLES30.glDeleteShader(fs)
      GLES30.glDeleteProgram(pid)
      Log.e(TAG, "Program link failed [$label]: $log")
      throw IllegalStateException("Program link failed [$label]: $log")
    }
    GLES30.glDetachShader(pid, vs)
    GLES30.glDetachShader(pid, fs)
    GLES30.glDeleteShader(vs)
    GLES30.glDeleteShader(fs)
    programId = pid
  }

  fun use() {
    GLES30.glUseProgram(programId)
  }

  fun uniformLocation(name: String): Int {
    return uniformCache.getOrPut(name) { GLES30.glGetUniformLocation(programId, name) }
  }

  fun setFloat(name: String, value: Float) {
    val loc = uniformLocation(name)
    if (loc >= 0) GLES30.glUniform1f(loc, value)
  }

  fun setFloatArray(name: String, values: FloatArray) {
    val loc = uniformLocation(name)
    if (loc >= 0 && values.isNotEmpty()) {
      GLES30.glUniform1fv(loc, values.size, values, 0)
    }
  }

  fun setInt(name: String, value: Int) {
    val loc = uniformLocation(name)
    if (loc >= 0) GLES30.glUniform1i(loc, value)
  }

  /** Reads the currently stored integer uniform from this linked program. */
  fun getIntUniform(name: String): Int? {
    val loc = uniformLocation(name)
    if (loc < 0) return null
    val value = IntArray(1)
    GLES30.glGetUniformiv(programId, loc, value, 0)
    return value[0]
  }

  /** Reads the currently stored float uniform from this linked program. */
  fun getFloatUniform(name: String): Float? {
    val loc = uniformLocation(name)
    if (loc < 0) return null
    val value = FloatArray(1)
    GLES30.glGetUniformfv(programId, loc, value, 0)
    return value[0]
  }

  fun setVec2(name: String, x: Float, y: Float) {
    val loc = uniformLocation(name)
    if (loc >= 0) GLES30.glUniform2f(loc, x, y)
  }

  fun setVec3(name: String, x: Float, y: Float, z: Float) {
    val loc = uniformLocation(name)
    if (loc >= 0) GLES30.glUniform3f(loc, x, y, z)
  }

  fun setVec3(name: String, v: FloatArray) {
    val loc = uniformLocation(name)
    if (loc >= 0) GLES30.glUniform3f(loc, v[0], v[1], v[2])
  }

  fun setVec4(name: String, x: Float, y: Float, z: Float, w: Float) {
    val loc = uniformLocation(name)
    if (loc >= 0) GLES30.glUniform4f(loc, x, y, z, w)
  }

  fun setMat3(name: String, mat: FloatArray) {
    val loc = uniformLocation(name)
    if (loc >= 0) GLES30.glUniformMatrix3fv(loc, 1, false, mat, 0)
  }

  fun setMat4(name: String, mat: FloatArray) {
    val loc = uniformLocation(name)
    if (loc >= 0) GLES30.glUniformMatrix4fv(loc, 1, false, mat, 0)
  }

  fun release() {
    if (programId != 0) {
      GLES30.glDeleteProgram(programId)
    }
  }

  companion object {
    private fun compileShader(type: Int, source: String, name: String): Int {
      val shader = GLES30.glCreateShader(type)
      GLES30.glShaderSource(shader, source)
      GLES30.glCompileShader(shader)
      val compiled = IntArray(1)
      GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
      if (compiled[0] == 0) {
        val log = GLES30.glGetShaderInfoLog(shader)
        GLES30.glDeleteShader(shader)
        Log.e(TAG, "Shader compile failed [$name]: $log")
        throw IllegalStateException("Shader compile failed [$name]: $log")
      }
      return shader
    }
  }
}

class GlMesh(
  val vao: Int,
  val vbo: Int,
  val ebo: Int,
  val indexCount: Int,
  val drawMode: Int = GLES30.GL_TRIANGLES,
) {
  fun draw() {
    GLES30.glBindVertexArray(vao)
    if (ebo != 0) {
      GLES30.glDrawElements(drawMode, indexCount, GLES30.GL_UNSIGNED_INT, 0)
    } else {
      GLES30.glDrawArrays(drawMode, 0, indexCount)
    }
    GLES30.glBindVertexArray(0)
  }

  fun release() {
    if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
    if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
    if (ebo != 0) GLES30.glDeleteBuffers(1, intArrayOf(ebo), 0)
  }
}

class GlFramebuffer(
  val width: Int,
  val height: Int,
  val fboId: Int,
  val colorTexId: Int,
  val depthRboId: Int,
  val isHdr: Boolean,
) {
  fun bind() {
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
    GLES30.glViewport(0, 0, width, height)
  }

  fun release() {
    if (depthRboId != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(depthRboId), 0)
    if (colorTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(colorTexId), 0)
    if (fboId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
  }
}

object GlBufferUtils {
  fun toFloatBuffer(data: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(data.size * 4)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .apply {
        put(data)
        position(0)
      }

  fun toIntBuffer(data: IntArray): IntBuffer =
    ByteBuffer.allocateDirect(data.size * 4)
      .order(ByteOrder.nativeOrder())
      .asIntBuffer()
      .apply {
        put(data)
        position(0)
      }

  /**
   * Builds a framebuffer with optional depth renderbuffer.
   * Attempts RGBA16F first when preferHdr=true, automatically falling back to RGBA8
   * on GPUs/emulators without EXT_color_buffer_float support.
   */
  fun createFramebuffer(
    width: Int,
    height: Int,
    withDepth: Boolean,
    preferHdr: Boolean,
  ): GlFramebuffer {
    val safeW = width.coerceAtLeast(2)
    val safeH = height.coerceAtLeast(2)

    if (preferHdr) {
      val hdr = tryCreateFbo(safeW, safeH, withDepth, GLES30.GL_RGBA16F, GLES30.GL_HALF_FLOAT, true)
      if (hdr != null) return hdr
      Log.w(TAG, "RGBA16F FBO unsupported on current GPU; falling back to RGBA8")
    }
    return tryCreateFbo(safeW, safeH, withDepth, GLES30.GL_RGBA8, GLES30.GL_UNSIGNED_BYTE, false)
      ?: throw IllegalStateException("Unable to allocate OpenGL ES 3.0 framebuffer ${safeW}x${safeH}")
  }

  private fun tryCreateFbo(
    width: Int,
    height: Int,
    withDepth: Boolean,
    internalFormat: Int,
    pixelType: Int,
    isHdr: Boolean,
  ): GlFramebuffer? {
    val fboArr = IntArray(1)
    val texArr = IntArray(1)
    GLES30.glGenFramebuffers(1, fboArr, 0)
    GLES30.glGenTextures(1, texArr, 0)

    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texArr[0])
    GLES30.glTexImage2D(
      GLES30.GL_TEXTURE_2D,
      0,
      internalFormat,
      width,
      height,
      0,
      GLES30.GL_RGBA,
      pixelType,
      null,
    )
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboArr[0])
    GLES30.glFramebufferTexture2D(
      GLES30.GL_FRAMEBUFFER,
      GLES30.GL_COLOR_ATTACHMENT0,
      GLES30.GL_TEXTURE_2D,
      texArr[0],
      0,
    )

    var depthRbo = 0
    if (withDepth) {
      val rboArr = IntArray(1)
      GLES30.glGenRenderbuffers(1, rboArr, 0)
      depthRbo = rboArr[0]
      GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRbo)
      GLES30.glRenderbufferStorage(
        GLES30.GL_RENDERBUFFER,
        GLES30.GL_DEPTH_COMPONENT24,
        width,
        height,
      )
      GLES30.glFramebufferRenderbuffer(
        GLES30.GL_FRAMEBUFFER,
        GLES30.GL_DEPTH_ATTACHMENT,
        GLES30.GL_RENDERBUFFER,
        depthRbo,
      )
    }

    val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)

    if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
      if (depthRbo != 0) GLES30.glDeleteRenderbuffers(1, intArrayOf(depthRbo), 0)
      GLES30.glDeleteTextures(1, texArr, 0)
      GLES30.glDeleteFramebuffers(1, fboArr, 0)
      return null
    }
    return GlFramebuffer(width, height, fboArr[0], texArr[0], depthRbo, isHdr)
  }
}
