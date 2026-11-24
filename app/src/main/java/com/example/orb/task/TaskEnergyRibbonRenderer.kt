package com.example.orb.task

import android.opengl.GLES30
import com.example.orb.gl.GlBufferUtils
import com.example.orb.gl.GlShaderProgram

/**
 * Snapshot of real GPU draw metrics for [TaskEnergyRibbonRenderer].
 */
data class TaskRibbonDrawStats(
  val vertexCount: Int = 0,
  val drawCalls: Int = 0,
  val shaderLinked: Boolean = false,
  val vaoIds: List<Int> = emptyList(),
  val vboIds: List<Int> = emptyList(),
  val drawCallsByStream: Map<String, Int> = emptyMap(),
)

/**
 * GPU renderer for the 4 independent Task Energy liquid ribbons released from the Orb
 * toward Photo Bottom Center (strictly separate from the 5 frozen Resident ribbons!).
 *
 * Supports P0-4 screen-space liquid refraction via a separate refraction snapshot texture
 * and G1 GPU visibility diagnostic modes (`GEOMETRY_MAGENTA`, `ALPHA_HEATMAP`, layer isolation).
 */
class TaskEnergyRibbonRenderer(
  taskStreamSpecs: List<TaskStreamSpec>? = null,
) {

  private class StreamGpuMesh(
    val spec: TaskStreamSpec,
    var vao: Int = 0,
    var vbo: Int = 0,
    var vertexCount: Int = 0,
  )

  private var program: GlShaderProgram? = null
  private val streams: List<StreamGpuMesh> = (taskStreamSpecs ?: TaskEnergyGeometry.buildDefaultStreams()).map { StreamGpuMesh(it) }
  private var cachedLayout: TaskSceneLayoutSnapshot? = null
  var lastDrawStats: TaskRibbonDrawStats = TaskRibbonDrawStats()
    private set

  fun init() {
    release()
    program = GlShaderProgram(
      TaskWaterLightShaders.TASK_RIBBON_VERT,
      TaskWaterLightShaders.TASK_RIBBON_FRAG,
      "TaskEnergyRibbons",
    )
    val vaos = IntArray(streams.size)
    val vbos = IntArray(streams.size)
    GLES30.glGenVertexArrays(streams.size, vaos, 0)
    GLES30.glGenBuffers(streams.size, vbos, 0)
    for (i in streams.indices) {
      streams[i].vao = vaos[i]
      streams[i].vbo = vbos[i]
    }
  }

  fun draw(
    layout: TaskSceneLayoutSnapshot,
    snapshot: TaskEnergySnapshot,
    hdrScale: Float,
    refractionTexId: Int = 0,
    fboWidth: Int = layout.surfaceWidthPx,
    fboHeight: Int = layout.surfaceHeightPx,
    debugOptions: TaskRenderDebugOptions = TaskRenderDebugOptions.Default,
  ) {
    val prog = program
    if (prog == null || snapshot.releaseProgress <= 0.002f || !debugOptions.isTaskEnergyEnabled) {
      lastDrawStats = TaskRibbonDrawStats(
        vertexCount = 0,
        drawCalls = 0,
        shaderLinked = prog != null,
        vaoIds = streams.map { it.vao },
        vboIds = streams.map { it.vbo },
      )
      return
    }
    rebuildGeometryIfNeeded(layout)

    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    prog.use()
    val hasRefraction = refractionTexId != 0
    if (hasRefraction) {
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, refractionTexId)
      prog.setInt("uTaskRefractionTex", 0)
    }
    prog.setInt("uHasRefractionTex", if (hasRefraction) 1 else 0)
    prog.setVec2("uFboSize", fboWidth.coerceAtLeast(1).toFloat(), fboHeight.coerceAtLeast(1).toFloat())
    prog.setFloat("uRefractionPx", DEFAULT_REFRACTION_PX)
    prog.setFloat("uRefractionStrength", DEFAULT_REFRACTION_STRENGTH)
    prog.setInt("uTaskVisualDebug", debugOptions.taskShaderDebugId)
    prog.setFloat("uFlowTime", snapshot.flowTimeSec)
    prog.setFloat("uHdrScale", hdrScale)

    var drawnVerts = 0
    var drawnCalls = 0
    val drawnByStream = linkedMapOf<String, Int>()

    // Draw secondary wisps first, then main icy-blue S-stream on top
    for ((idx, item) in streams.sortedBy { it.spec.depthOffset }.withIndex()) {
      if (item.vertexCount <= 0 || item.vao == 0) continue
      if (!debugOptions.isTaskStreamEnabled(item.spec.kind)) continue

      // Each accompanying stream reveals smoothly with its own natural reach while main hits 1.0 at KF02
      val effectiveReveal = (snapshot.releaseProgress * item.spec.reachFraction).coerceIn(0f, 1f)
      prog.setInt("uStreamKind", item.spec.kind.shaderStyleId)
      prog.setFloat("uStreamPhase", idx * 1.65f)
      prog.setFloat("uReveal", effectiveReveal)
      prog.setFloat("uFlowSpeed", item.spec.flowSpeed)

      GLES30.glBindVertexArray(item.vao)
      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, item.vertexCount)
      drawnVerts += item.vertexCount
      drawnCalls++
      drawnByStream[item.spec.id] = (drawnByStream[item.spec.id] ?: 0) + 1
    }
    GLES30.glBindVertexArray(0)
    if (hasRefraction) {
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    lastDrawStats = TaskRibbonDrawStats(
      vertexCount = drawnVerts,
      drawCalls = drawnCalls,
      shaderLinked = true,
      vaoIds = streams.map { it.vao },
      vboIds = streams.map { it.vbo },
      drawCallsByStream = drawnByStream,
    )
  }

  private fun rebuildGeometryIfNeeded(layout: TaskSceneLayoutSnapshot) {
    if (cachedLayout == layout) return
    cachedLayout = layout

    val strideBytes = TaskEnergyGeometry.FLOATS_PER_VERTEX * 4
    for (item in streams) {
      if (item.vao == 0 || item.vbo == 0) continue
      val verts = TaskEnergyGeometry.buildRibbonVerticesNdc(item.spec, layout)
      item.vertexCount = verts.size / TaskEnergyGeometry.FLOATS_PER_VERTEX

      GLES30.glBindVertexArray(item.vao)
      GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, item.vbo)
      GLES30.glBufferData(
        GLES30.GL_ARRAY_BUFFER,
        verts.size * 4,
        GlBufferUtils.toFloatBuffer(verts),
        GLES30.GL_DYNAMIC_DRAW,
      )
      GLES30.glEnableVertexAttribArray(0)
      GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, strideBytes, 0)
      GLES30.glEnableVertexAttribArray(1)
      GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, strideBytes, 8)
      GLES30.glEnableVertexAttribArray(2)
      GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, strideBytes, 16)
      GLES30.glEnableVertexAttribArray(3)
      GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, strideBytes, 20)
      GLES30.glEnableVertexAttribArray(4)
      GLES30.glVertexAttribPointer(4, 2, GLES30.GL_FLOAT, false, strideBytes, 24)
    }
    GLES30.glBindVertexArray(0)
  }

  fun release() {
    program?.release()
    program = null
    val vaos = streams.map { it.vao }.filter { it != 0 }.toIntArray()
    val vbos = streams.map { it.vbo }.filter { it != 0 }.toIntArray()
    if (vaos.isNotEmpty()) GLES30.glDeleteVertexArrays(vaos.size, vaos, 0)
    if (vbos.isNotEmpty()) GLES30.glDeleteBuffers(vbos.size, vbos, 0)
    for (s in streams) {
      s.vao = 0
      s.vbo = 0
      s.vertexCount = 0
    }
    cachedLayout = null
    lastDrawStats = TaskRibbonDrawStats()
  }

  companion object {
    const val DEFAULT_REFRACTION_PX = 1.0f
    const val DEFAULT_REFRACTION_STRENGTH = 0.35f
  }
}
