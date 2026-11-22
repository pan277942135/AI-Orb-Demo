package com.example.orb.task

import android.opengl.GLES30
import com.example.orb.gl.GlBufferUtils
import com.example.orb.gl.GlShaderProgram

/**
 * GPU renderer for the 4 independent Task Energy liquid ribbons released from the Orb
 * toward Photo Bottom Center (strictly separate from the 5 frozen Resident ribbons!).
 */
class TaskEnergyRibbonRenderer {

  private class StreamGpuMesh(
    val spec: TaskStreamSpec,
    var vao: Int = 0,
    var vbo: Int = 0,
    var vertexCount: Int = 0,
  )

  private var program: GlShaderProgram? = null
  private val streams: List<StreamGpuMesh> = TaskEnergyGeometry.buildDefaultStreams().map { StreamGpuMesh(it) }
  private var cachedLayout: TaskSceneLayoutSnapshot? = null

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
  ) {
    val prog = program ?: return
    if (snapshot.releaseProgress <= 0.002f) return
    rebuildGeometryIfNeeded(layout)

    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    prog.use()
    prog.setFloat("uFlowTime", snapshot.flowTimeSec)
    prog.setFloat("uHdrScale", hdrScale)

    // Draw secondary wisps first, then main icy-blue S-stream on top
    for ((idx, item) in streams.sortedBy { it.spec.depthOffset }.withIndex()) {
      if (item.vertexCount <= 0 || item.vao == 0) continue
      // Each accompanying stream reveals smoothly with its own natural reach while main hits 1.0 at KF02
      val effectiveReveal = (snapshot.releaseProgress * item.spec.reachFraction).coerceIn(0f, 1f)
      prog.setInt("uStreamKind", item.spec.kind.shaderStyleId)
      prog.setFloat("uStreamPhase", idx * 1.65f)
      prog.setFloat("uReveal", effectiveReveal)
      prog.setFloat("uFlowSpeed", item.spec.flowSpeed)

      GLES30.glBindVertexArray(item.vao)
      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, item.vertexCount)
    }
    GLES30.glBindVertexArray(0)
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
  }
}
