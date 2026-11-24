package com.example.orb.yujian.core

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import com.example.orb.gl.GlShaderProgram
import com.example.orb.scene.AIOrbMotionSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Runtime facts read on the GL thread for the YuJian Core-only Pass 6. */
data class YuJianCoreRuntimeState(
  val status: String = "NOT_INITIALIZED",
  val shaderProgramId: Int = 0,
  val shaderSourceSha256: String = "UNAVAILABLE",
  val sourceSphereShaderSha256: String = YuJianCoreRenderer.SOURCE_SPHERE_SHADER_SHA256,
  val renderResolution: String = "0x0",
  val projectedCenterNdc: String = "UNAVAILABLE",
  val projectedRadiiNdc: String = "UNAVAILABLE",
  val elapsedTimeSec: Float = 0f,
  val hdrScale: Float = Float.NaN,
  val refractionTextureId: Int = 0,
  val boundRefractionTextureId: Int = 0,
  val samplerTextureUnit: Int = -1,
  val uniformsReadBackValid: Boolean = false,
  val drawFramebufferId: Int = 0,
  val drawColorAttachmentTextureId: Int = 0,
  val drawFramebufferComplete: Boolean = false,
  val noFramebufferFeedbackLoop: Boolean = false,
  val passStateReadBackValid: Boolean = false,
  val frameCount: Long = 0L,
  val contextRebuildCount: Int = 0,
  val glError: String = "GL_NO_ERROR",
  val glErrorPass: String = "NONE",
)

/** Explicit inputs shared with AIOrbScene; no second Camera, clock, lighting or EGL owner. */
data class YuJianCoreFrameInput(
  val modelMatrix: FloatArray,
  val viewMatrix: FloatArray,
  val projectionMatrix: FloatArray,
  val cameraPosition: FloatArray,
  val elapsedTimeSec: Float,
  val deltaTimeSec: Float,
  val refractionTextureId: Int,
  val viewportWidth: Int,
  val viewportHeight: Int,
  val hdrScale: Float,
  val motion: AIOrbMotionSnapshot,
)

/**
 * Core-only extraction of YuJian's production `SPHERE_FS` from
 * `OrbCrystalWakeGlOverlay.kt` at the pinned source commit.
 *
 * The source Android host is a Compose TextureView with a private EGL context. This renderer
 * compiles the adapted GLSL ES 3.0 fragment in AIOrbScene's existing context and writes into its
 * currently bound scene FBO. Its input is Pass 5's completed refraction snapshot.
 */
class YuJianCoreRenderer {
  @Volatile
  var runtimeState: YuJianCoreRuntimeState = YuJianCoreRuntimeState()
    private set

  private var program: GlShaderProgram? = null
  private var quadVbo: Int = 0
  private var contextRebuildCount: Int = 0
  private var frameCount: Long = 0L

  fun onSurfaceCreated(assetManager: AssetManager) {
    // A new GLSurfaceView context invalidates all old GL names. Do not delete old-context IDs
    // from the new context; they could alias freshly allocated objects.
    onContextLost()
    contextRebuildCount += 1
    runCatching {
      val vertexBytes = assetManager.open(VERTEX_ASSET).use { it.readBytes() }
      val fragmentBytes = assetManager.open(FRAGMENT_ASSET).use { it.readBytes() }
      val vertexSource = vertexBytes.toString(Charsets.UTF_8)
      val fragmentSource = fragmentBytes.toString(Charsets.UTF_8)
      val linked = GlShaderProgram(vertexSource, fragmentSource, "YuJianCorePass6")
      val buffers = IntArray(1)
      GLES30.glGenBuffers(1, buffers, 0)
      check(buffers[0] != 0) { "Failed to allocate YuJian Core quad VBO" }
      GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
      val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
      val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
      vertexBuffer.put(vertices).position(0)
      GLES30.glBufferData(
        GLES30.GL_ARRAY_BUFFER,
        vertices.size * Float.SIZE_BYTES,
        vertexBuffer,
        GLES30.GL_STATIC_DRAW,
      )
      GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
      program = linked
      quadVbo = buffers[0]
      runtimeState = YuJianCoreRuntimeState(
        status = "READY",
        shaderProgramId = linked.programId,
        shaderSourceSha256 = sha256(fragmentBytes),
        contextRebuildCount = contextRebuildCount,
      )
      Log.i(TAG, "YuJian Core Pass 6 linked program=${linked.programId} sourceSha=${runtimeState.shaderSourceSha256}")
    }.onFailure { failure ->
      runtimeState = runtimeState.copy(
        status = "ERROR:${failure.javaClass.simpleName}:${failure.message ?: "shader initialization failed"}",
        shaderProgramId = 0,
        contextRebuildCount = contextRebuildCount,
        glErrorPass = "YUjian_CORE_INIT",
      )
      Log.e(TAG, "YuJian Core Pass 6 initialization failed; no renderer fallback is enabled", failure)
    }
  }

  fun onSurfaceChanged(width: Int, height: Int) {
    runtimeState = runtimeState.copy(renderResolution = "${width.coerceAtLeast(1)}x${height.coerceAtLeast(1)}")
  }

  /** Replaces only Pass 6. The supplied texture is a finished Pass 5 FBO, never the target FBO. */
  fun render(input: YuJianCoreFrameInput): Boolean {
    val glProgram = program ?: return false
    if (input.refractionTextureId == 0 || input.viewportWidth < 1 || input.viewportHeight < 1) {
      runtimeState = runtimeState.copy(status = "ERROR:INVALID_PASS6_INPUT", glErrorPass = "YUJIAN_CORE_INPUT")
      return false
    }
    val projected = projectSphere(input)
      ?: run {
        runtimeState = runtimeState.copy(status = "ERROR:INVALID_PROJECTED_CORE_BOUNDS", glErrorPass = "YUJIAN_CORE_PROJECTION")
        return false
      }
    val priorError = GLES30.glGetError()
    GLES30.glViewport(0, 0, input.viewportWidth, input.viewportHeight)
    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthFunc(GLES30.GL_ALWAYS)
    GLES30.glDepthMask(true)
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    glProgram.use()
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, input.refractionTextureId)
    glProgram.setInt("uBackTex", 0)
    val sharedTimeSec = input.motion.simTimeSec.toFloat().coerceAtLeast(0f)
    glProgram.setFloat("uTime", sharedTimeSec)
    glProgram.setFloat("uWake", 1f)
    glProgram.setVec2("uSphereCenter", projected.centerNdc[0], projected.centerNdc[1])
    glProgram.setVec2("uSphereRadii", projected.radiiNdc[0], projected.radiiNdc[1])
    glProgram.setVec2("uQuadExtent", projected.radiiNdc[0] * QUAD_PADDING, projected.radiiNdc[1] * QUAD_PADDING)
    glProgram.setFloat("uHdrScale", input.hdrScale.coerceIn(0.01f, 1f))
    glProgram.setFloat("uCoreCenterViewZ", projected.centerViewZ)
    glProgram.setFloat("uCoreRadiusView", projected.radiusView)
    glProgram.setVec4(
      "uProjectionDepth",
      input.projectionMatrix[10], input.projectionMatrix[14],
      input.projectionMatrix[11], input.projectionMatrix[15],
    )
    glProgram.setFloat("uShowSun", 1f)
    glProgram.setFloat("uShowCaustics", 1f)
    val boundTexture = IntArray(1)
    GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, boundTexture, 0)
    val drawFramebuffer = IntArray(1)
    GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, drawFramebuffer, 0)
    val drawColorAttachment = IntArray(1)
    GLES30.glGetFramebufferAttachmentParameteriv(
      GLES30.GL_FRAMEBUFFER,
      GLES30.GL_COLOR_ATTACHMENT0,
      GLES30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,
      drawColorAttachment,
      0,
    )
    val drawFramebufferComplete =
      GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
    val noFramebufferFeedbackLoop = drawFramebuffer[0] != 0 && drawColorAttachment[0] != input.refractionTextureId
    val samplerTextureUnit = glProgram.getIntUniform("uBackTex") ?: -1
    val readBackTime = glProgram.getFloatUniform("uTime") ?: Float.NaN
    val readBackHdrScale = glProgram.getFloatUniform("uHdrScale") ?: Float.NaN
    val uniformsReadBackValid = samplerTextureUnit == 0 &&
      boundTexture[0] == input.refractionTextureId && GLES30.glIsTexture(input.refractionTextureId) &&
      readBackTime.isFinite() && kotlin.math.abs(readBackTime - sharedTimeSec) <= 1e-5f &&
      readBackHdrScale.isFinite() && kotlin.math.abs(readBackHdrScale - input.hdrScale.coerceIn(0.01f, 1f)) <= 1e-5f
    val passStateReadBackValid = uniformsReadBackValid && drawFramebufferComplete && noFramebufferFeedbackLoop
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
    val positionLoc = GLES30.glGetAttribLocation(glProgram.programId, "aPosition")
    if (positionLoc < 0) {
      GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
      GLES30.glDepthMask(true)
      GLES30.glDepthFunc(GLES30.GL_LEQUAL)
      GLES30.glEnable(GLES30.GL_DEPTH_TEST)
      GLES30.glEnable(GLES30.GL_CULL_FACE)
      runtimeState = runtimeState.copy(status = "ERROR:MISSING_POSITION_ATTRIBUTE", glErrorPass = "YUjian_CORE_ATTRIBUTE_LOOKUP")
      return false
    }
    GLES30.glEnableVertexAttribArray(positionLoc)
    GLES30.glVertexAttribPointer(positionLoc, 2, GLES30.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    GLES30.glDisableVertexAttribArray(positionLoc)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glDepthMask(true)
    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glEnable(GLES30.GL_CULL_FACE)
    val drawError = GLES30.glGetError()
    val effectiveError = if (priorError != GLES30.GL_NO_ERROR) priorError else drawError
    frameCount += 1L
    runtimeState = runtimeState.copy(
      status = if (effectiveError == GLES30.GL_NO_ERROR && passStateReadBackValid) "RENDERED" else "ERROR:PASS6_GL_OR_BINDING",
      shaderProgramId = glProgram.programId,
      renderResolution = "${input.viewportWidth}x${input.viewportHeight}",
      projectedCenterNdc = "${projected.centerNdc[0]},${projected.centerNdc[1]}",
      projectedRadiiNdc = "${projected.radiiNdc[0]},${projected.radiiNdc[1]}",
      elapsedTimeSec = sharedTimeSec,
      hdrScale = readBackHdrScale,
      refractionTextureId = input.refractionTextureId,
      boundRefractionTextureId = boundTexture[0],
      samplerTextureUnit = samplerTextureUnit,
      uniformsReadBackValid = uniformsReadBackValid,
      drawFramebufferId = drawFramebuffer[0],
      drawColorAttachmentTextureId = drawColorAttachment[0],
      drawFramebufferComplete = drawFramebufferComplete,
      noFramebufferFeedbackLoop = noFramebufferFeedbackLoop,
      passStateReadBackValid = passStateReadBackValid,
      frameCount = frameCount,
      glError = when {
        effectiveError != GLES30.GL_NO_ERROR -> "0x${effectiveError.toString(16)}"
        !passStateReadBackValid -> "PASS6_BINDING_OR_FBO_READBACK_MISMATCH"
        else -> "GL_NO_ERROR"
      },
      glErrorPass = when {
        priorError != GLES30.GL_NO_ERROR -> "PREEXISTING_BEFORE_YUJIAN_PASS6:0x${priorError.toString(16)}"
        drawError != GLES30.GL_NO_ERROR -> "YUjian_CORE_PASS6"
        positionLoc < 0 -> "YUjian_CORE_ATTRIBUTE_LOOKUP"
        !passStateReadBackValid -> "YUJIAN_CORE_UNIFORM_TEXTURE_OR_FBO_READBACK"
        else -> "NONE"
      },
    )
    return effectiveError == GLES30.GL_NO_ERROR && passStateReadBackValid
  }

  /** Context-loss invalidation only; GL names are already invalid in the replacement context. */
  fun onContextLost() {
    program = null
    quadVbo = 0
    runtimeState = YuJianCoreRuntimeState(
      status = "CONTEXT_LOST",
      contextRebuildCount = contextRebuildCount,
    )
  }

  /** Explicit application teardown while the owning GLSurfaceView context is current. */
  fun release() {
    program?.release()
    program = null
    if (quadVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
    quadVbo = 0
    runtimeState = runtimeState.copy(status = "RELEASED", shaderProgramId = 0)
  }

  private data class ProjectedSphere(
    val centerNdc: FloatArray,
    val radiiNdc: FloatArray,
    val centerViewZ: Float,
    val radiusView: Float,
  )

  private fun projectSphere(input: YuJianCoreFrameInput): ProjectedSphere? {
    val viewModel = FloatArray(16)
    val mvp = FloatArray(16)
    Matrix.multiplyMM(viewModel, 0, input.viewMatrix, 0, input.modelMatrix, 0)
    Matrix.multiplyMM(mvp, 0, input.projectionMatrix, 0, viewModel, 0)
    val center = project(mvp, 0f, 0f, 0f) ?: return null
    val centerView = FloatArray(4)
    Matrix.multiplyMV(centerView, 0, viewModel, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
    var radiusX = 0f
    var radiusY = 0f
    // Sample the source sphere's normalized silhouette after the shared Demo Model/View/Projection.
    for (i in 0 until PROJECTION_SAMPLES) {
      val angle = (2.0 * PI * i / PROJECTION_SAMPLES).toFloat()
      val edge = project(mvp, cos(angle), sin(angle), 0f) ?: continue
      radiusX = maxOf(radiusX, kotlin.math.abs(edge[0] - center[0]))
      radiusY = maxOf(radiusY, kotlin.math.abs(edge[1] - center[1]))
    }
    if (!center.all(Float::isFinite) || !radiusX.isFinite() || !radiusY.isFinite()) return null
    if (radiusX < 1f / input.viewportWidth || radiusY < 1f / input.viewportHeight) return null
    val scaleX = vectorLength(input.modelMatrix[0], input.modelMatrix[1], input.modelMatrix[2])
    val scaleY = vectorLength(input.modelMatrix[4], input.modelMatrix[5], input.modelMatrix[6])
    val scaleZ = vectorLength(input.modelMatrix[8], input.modelMatrix[9], input.modelMatrix[10])
    val radiusView = (scaleX + scaleY + scaleZ) / 3f
    if (!centerView[2].isFinite() || !radiusView.isFinite() || radiusView <= 0f) return null
    return ProjectedSphere(center, floatArrayOf(radiusX, radiusY), centerView[2], radiusView)
  }

  private fun vectorLength(x: Float, y: Float, z: Float) = kotlin.math.sqrt(x * x + y * y + z * z)

  private fun project(mvp: FloatArray, x: Float, y: Float, z: Float): FloatArray? {
    val local = floatArrayOf(x, y, z, 1f)
    val clip = FloatArray(4)
    Matrix.multiplyMV(clip, 0, mvp, 0, local, 0)
    if (clip[3] <= 1e-5f || !clip.all(Float::isFinite)) return null
    return floatArrayOf(clip[0] / clip[3], clip[1] / clip[3])
  }

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

  companion object {
    const val SOURCE_SPHERE_SHADER_SHA256 = "eff0af75967117b501be6409009dfa62d9fb336065e458464a19d3e5221bbc05"
    const val SOURCE_CORE_WEBP_SHA256 = "daa85a1fc13fac4a7ca562959c2d19172ee0f1e94277c3eeb32ef1f09a1a7b09"
    const val SOURCE_CORE_WEBP_RGBA_SHA256 = "0a48ddc14f61e307413e9996e3cdca5e2ae66a01255f0cd14b8f7d0a18628a1c"
    private const val TAG = "YuJianCorePass6"
    private const val VERTEX_ASSET = "yujian_orb_core/YuJianCoreVertex.glsl"
    private const val FRAGMENT_ASSET = "yujian_orb_core/YuJianCoreFragment.glsl"
    private const val PROJECTION_SAMPLES = 64
    private const val QUAD_PADDING = 1.02f
  }
}
