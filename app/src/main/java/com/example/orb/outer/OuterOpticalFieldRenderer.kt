package com.example.orb.outer

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.Matrix
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

data class OuterOpticalBokeh(val x: Float, val y: Float, val radius: Float, val intensity: Float, val color: FloatArray)

data class OuterOpticalFieldRuntimeState(
  val status: String = "NOT_INITIALIZED",
  val manifestSha256: String = "UNAVAILABLE",
  val shaderSourceSha256: String = "UNAVAILABLE",
  val ribbonProgramId: Int = 0,
  val pointProgramId: Int = 0,
  val traceCount: Int = 0,
  val surfaceCount: Int = 0,
  val filamentCount: Int = 0,
  val highlightCount: Int = 0,
  val dropletCount: Int = 0,
  val particleCount: Int = 0,
  val drawnBackVertices: Int = 0,
  val drawnFrontVertices: Int = 0,
  val framebufferId: Int = 0,
  val refractionTextureId: Int = 0,
  val glError: String = "GL_NO_ERROR",
  val glErrorPass: String = "NONE",
  val uniformReadbackValid: Boolean = false,
)

/** Tab 3-only renderer driven by the measured REF_B projection and P0-A extraction manifest. */
class OuterOpticalFieldRenderer {
  @Volatile var runtimeState: OuterOpticalFieldRuntimeState = OuterOpticalFieldRuntimeState()
    private set
  var referenceBokeh: List<OuterOpticalBokeh> = emptyList()
    private set

  private var ribbonProgram: GlShaderProgram? = null
  private var pointProgram: GlShaderProgram? = null
  private var backDraws: List<Draw> = emptyList()
  private var frontDraws: List<Draw> = emptyList()
  private var backSprites: GlMesh? = null
  private var frontSprites: GlMesh? = null
  private var manifestSha = "UNAVAILABLE"
  private var shaderSha = "UNAVAILABLE"

  fun onSurfaceCreated(assetManager: AssetManager?) {
    onContextLost()
    if (assetManager == null) {
      runtimeState = OuterOpticalFieldRuntimeState(status = "ERROR:ASSET_MANAGER_UNAVAILABLE")
      return
    }
    runCatching {
      val bytes = assetManager.open(MANIFEST_ASSET).use { it.readBytes() }
      val root = JSONObject(String(bytes, Charsets.UTF_8))
      check(root.getString("schema") == SCHEMA) { "Unexpected REF_B outer field schema" }
      manifestSha = sha(bytes)
      val draws = ArrayList<Draw>()
      val traces = root.getJSONArray("traces")
      for (i in 0 until traces.length()) {
        val item = traces.getJSONObject(i)
        val points = readPoints(item.getJSONArray("pointsPx"))
        val front = item.optString("depthRole") == "FRONT"
        val color = readVector(item.getJSONArray("baseColorLinear"), 3)
        val annotationWidth = item.optDouble("annotationWidthPx", 18.0).toFloat().coerceAtLeast(2f)
        val signalWidth = item.optDouble("signalWidthPx", 5.0).toFloat().coerceAtLeast(1f)
        val phase = item.optDouble("phaseRad", i * 0.91).toFloat()
        val period = item.optDouble("periodMs", 4400.0).toFloat() / 1000f
        val material = when (item.optString("materialClass")) {
          "CYAN_LIQUID_GLASS" -> 0
          "GOLD_FILAMENT_SURFACE" -> 1
          else -> 2
        }
        val layer = if (front) Layer.FRONT else Layer.BACK
        draws += Draw(createRibbon(points, annotationWidth, 0f), color, phase, period, layer, 0, material, annotationWidth)
        // Two separate inner filaments follow the measured centerline; their offsets are explicitly
        // marked as geometric inference in the frozen runtime manifest.
        for (side in listOf(-1f, 1f)) {
          draws += Draw(
            createRibbon(points, max(2.0f, signalWidth * 0.36f), side * annotationWidth * 0.28f),
            FloatArray(3) { (color[it] * 1.7f).coerceAtMost(1f) },
            phase + side * 0.41f,
            period * if (side < 0f) 0.91f else 1.07f,
            layer, 1, material, signalWidth * 0.36f,
          )
        }
      }
      backDraws = draws.filter { it.layer == Layer.BACK }
      frontDraws = draws.filter { it.layer == Layer.FRONT }
      val bokeh = root.getJSONArray("bokeh")
      referenceBokeh = (0 until min(12, bokeh.length())).map { i ->
        val item = bokeh.getJSONObject(i)
        val center = readVector(item.getJSONArray("centerPx"), 2)
        val peak = item.optDouble("p95LuminanceLinear", 0.1).toFloat()
        OuterOpticalBokeh(
          x = (center[0] - REF_CENTER_X) / REF_SIZE,
          y = (REF_CENTER_Y - center[1]) / REF_SIZE,
          radius = item.optDouble("radiusPx", 16.0).toFloat() / REF_SIZE,
          intensity = (peak * 0.20f).coerceIn(0.02f, 0.20f),
          color = readVector(item.getJSONArray("colorLinear"), 3),
        )
      }
      val sprites = parseSprites(root)
      backSprites = createSprites(sprites.filter { it.layer == Layer.BACK })
      frontSprites = createSprites(sprites.filter { it.layer == Layer.FRONT })
      ribbonProgram = GlShaderProgram(RIBBON_VERTEX, RIBBON_FRAGMENT, "Tab3OuterOpticalRibbon")
      pointProgram = GlShaderProgram(POINT_VERTEX, POINT_FRAGMENT, "Tab3OuterOpticalSprites")
      shaderSha = sha((RIBBON_VERTEX + RIBBON_FRAGMENT + POINT_VERTEX + POINT_FRAGMENT).toByteArray())
      runtimeState = OuterOpticalFieldRuntimeState(
        status = "READY", manifestSha256 = manifestSha, shaderSourceSha256 = shaderSha,
        ribbonProgramId = ribbonProgram!!.programId, pointProgramId = pointProgram!!.programId,
        traceCount = traces.length(), surfaceCount = traces.length(), filamentCount = traces.length() * 2,
        highlightCount = root.getJSONArray("highlightNodes").length(),
        dropletCount = root.getJSONArray("droplets").length(), particleCount = root.getJSONArray("particles").length(),
      )
    }.onFailure { error ->
      releaseMeshes()
      ribbonProgram?.release(); ribbonProgram = null
      pointProgram?.release(); pointProgram = null
      runtimeState = OuterOpticalFieldRuntimeState(
        status = "ERROR:${error.javaClass.simpleName}:${error.message ?: "outer field init"}",
        manifestSha256 = manifestSha, glErrorPass = "OUTER_FIELD_INIT",
      )
    }
  }

  fun drawBack(
    model: FloatArray, view: FloatArray, projection: FloatArray, width: Int, height: Int,
    time: Float, hdrScale: Float, drawSurfaces: Boolean, drawDetails: Boolean,
  ) = drawLayer(Layer.BACK, model, view, projection, width, height, time, hdrScale, 0, false, drawSurfaces, drawDetails)

  fun drawFront(
    model: FloatArray, view: FloatArray, projection: FloatArray, width: Int, height: Int,
    time: Float, hdrScale: Float, refractionTextureId: Int, drawSurfaces: Boolean, drawDetails: Boolean,
  ) = drawLayer(
    Layer.FRONT, model, view, projection, width, height, time, hdrScale,
    refractionTextureId, refractionTextureId != 0, drawSurfaces, drawDetails,
  )

  private fun drawLayer(
    layer: Layer, model: FloatArray, view: FloatArray, projection: FloatArray, width: Int, height: Int,
    time: Float, hdrScale: Float, refractionTextureId: Int, useRefraction: Boolean,
    drawSurfaces: Boolean, drawDetails: Boolean,
  ) {
    val ribbon = ribbonProgram ?: return
    val point = pointProgram ?: return
    // Both layers are drawn in one frame. The back pass records RENDERED before the front pass
    // begins, so only an actual error/context-loss state may block the second layer.
    if (runtimeState.status != "READY" && runtimeState.status != "RENDERED") return
    val core = projectCore(model, view, projection, width, height) ?: run {
      runtimeState = runtimeState.copy(status = "ERROR:CORE_PROJECTION", glErrorPass = "OUTER_FIELD_PROJECTION")
      return
    }
    val before = GLES30.glGetError()
    val cullWasEnabled = GLES30.glIsEnabled(GLES30.GL_CULL_FACE)
    GLES30.glViewport(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    var vertexCount = 0
    if (drawSurfaces) {
      ribbon.use()
      setProjectionUniforms(ribbon, core, projection, width, height, time, hdrScale, layer)
      ribbon.setInt("uRefraction", 0)
      ribbon.setInt("uUseRefraction", if (useRefraction) 1 else 0)
      if (useRefraction) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, refractionTextureId)
      }
      for (draw in if (layer == Layer.BACK) backDraws else frontDraws) {
        ribbon.setVec3("uColor", draw.color)
        ribbon.setFloat("uKind", draw.kind.toFloat())
        ribbon.setFloat("uMaterial", draw.material.toFloat())
        ribbon.setFloat("uPhase", draw.phase)
        ribbon.setFloat("uPeriod", draw.period.coerceAtLeast(0.1f))
        ribbon.setFloat("uReferenceWidth", draw.width)
        draw.mesh.draw()
        vertexCount += draw.mesh.indexCount
      }
    }
    if (drawDetails) {
      val mesh = if (layer == Layer.BACK) backSprites else frontSprites
      if (mesh != null && mesh.indexCount > 0) {
        point.use()
        setProjectionUniforms(point, core, projection, width, height, time, hdrScale, layer)
        point.setInt("uRefraction", 0)
        point.setInt("uUseRefraction", if (useRefraction) 1 else 0)
        if (useRefraction) {
          GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
          GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, refractionTextureId)
        }
        mesh.draw()
        vertexCount += mesh.indexCount
      }
    }
    val fbo = IntArray(1)
    GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, fbo, 0)
    val activeProgram = if (drawSurfaces) ribbon else point
    val readTime = activeProgram.getFloatUniform("uTime") ?: Float.NaN
    val readSampler = activeProgram.getIntUniform("uRefraction") ?: -1
    val readback = (!drawSurfaces && !drawDetails) || (
      readTime.isFinite() && abs(readTime - time) < 1e-4f &&
        (!useRefraction || (readSampler == 0 && GLES30.glIsTexture(refractionTextureId)))
      )
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glDepthMask(true)
    GLES30.glDisable(GLES30.GL_BLEND)
    if (cullWasEnabled) GLES30.glEnable(GLES30.GL_CULL_FACE) else GLES30.glDisable(GLES30.GL_CULL_FACE)
    val after = GLES30.glGetError()
    val error = when {
      before != GLES30.GL_NO_ERROR -> before
      after != GLES30.GL_NO_ERROR -> after
      !readback -> GLES30.GL_INVALID_OPERATION
      else -> GLES30.GL_NO_ERROR
    }
    runtimeState = runtimeState.copy(
      status = if (error == GLES30.GL_NO_ERROR) "RENDERED" else "ERROR:OUTER_FIELD_DRAW",
      drawnBackVertices = if (layer == Layer.BACK) vertexCount else runtimeState.drawnBackVertices,
      drawnFrontVertices = if (layer == Layer.FRONT) vertexCount else runtimeState.drawnFrontVertices,
      framebufferId = fbo[0], refractionTextureId = if (useRefraction) refractionTextureId else runtimeState.refractionTextureId,
      glError = if (error == GLES30.GL_NO_ERROR) "GL_NO_ERROR" else "0x${error.toString(16)}",
      glErrorPass = if (error == GLES30.GL_NO_ERROR) "NONE" else "OUTER_${layer.name}_DRAW_OR_BINDING",
      uniformReadbackValid = readback,
    )
  }

  fun onContextLost() {
    // Context-local names are invalidated; don't delete old names from the replacement context.
    ribbonProgram = null; pointProgram = null
    backDraws = emptyList(); frontDraws = emptyList()
    backSprites = null; frontSprites = null; referenceBokeh = emptyList()
    runtimeState = OuterOpticalFieldRuntimeState(status = "CONTEXT_LOST")
  }

  fun release() {
    ribbonProgram?.release(); ribbonProgram = null
    pointProgram?.release(); pointProgram = null
    releaseMeshes()
    runtimeState = runtimeState.copy(status = "RELEASED", ribbonProgramId = 0, pointProgramId = 0)
  }

  private fun releaseMeshes() {
    backDraws.forEach { it.mesh.release() }; frontDraws.forEach { it.mesh.release() }
    backDraws = emptyList(); frontDraws = emptyList()
    backSprites?.release(); backSprites = null
    frontSprites?.release(); frontSprites = null
  }

  private fun setProjectionUniforms(
    program: GlShaderProgram, core: CoreProjection, projection: FloatArray, width: Int, height: Int, time: Float, hdrScale: Float, layer: Layer,
  ) {
    program.setVec2("uCoreCenter", core.center[0], core.center[1])
    program.setVec2("uCoreRadii", core.radii[0], core.radii[1])
    program.setVec2("uResolution", width.toFloat(), height.toFloat())
    program.setFloat("uCoreCenterViewZ", core.centerViewZ)
    program.setFloat("uCoreRadiusView", core.radiusView)
    program.setFloat("uLayerSign", if (layer == Layer.FRONT) 1f else -1f)
    program.setVec4("uProjectionDepth", projection[10], projection[14], projection[11], projection[15])
    program.setFloat("uTime", time)
    program.setFloat("uHdrScale", hdrScale.coerceIn(0.01f, 1f))
  }

  private data class CoreProjection(val center: FloatArray, val radii: FloatArray, val centerViewZ: Float, val radiusView: Float)
  private enum class Layer { BACK, FRONT }
  private data class Draw(val mesh: GlMesh, val color: FloatArray, val phase: Float, val period: Float, val layer: Layer, val kind: Int, val material: Int, val width: Float)
  private data class Sprite(val x: Float, val y: Float, val radius: Float, val color: FloatArray, val phase: Float, val layer: Layer, val type: Int)

  private fun projectCore(model: FloatArray, view: FloatArray, projection: FloatArray, width: Int, height: Int): CoreProjection? {
    val viewModel = FloatArray(16); val mvp = FloatArray(16)
    Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
    Matrix.multiplyMM(mvp, 0, projection, 0, viewModel, 0)
    val center = project(mvp, 0f, 0f, 0f) ?: return null
    val centerView = FloatArray(4)
    Matrix.multiplyMV(centerView, 0, viewModel, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
    var rx = 0f; var ry = 0f
    repeat(64) { i ->
      val angle = (2.0 * PI * i / 64.0).toFloat()
      val edge = project(mvp, cos(angle), sin(angle), 0f) ?: return@repeat
      rx = max(rx, abs(edge[0] - center[0])); ry = max(ry, abs(edge[1] - center[1]))
    }
    val scaleX = vectorLength(model[0], model[1], model[2])
    val scaleY = vectorLength(model[4], model[5], model[6])
    val scaleZ = vectorLength(model[8], model[9], model[10])
    val radius = (scaleX + scaleY + scaleZ) / 3f
    if (!center.all(Float::isFinite) || !centerView[2].isFinite() || rx * width < 2f || ry * height < 2f || radius <= 0f) return null
    return CoreProjection(center, floatArrayOf(rx, ry), centerView[2], radius)
  }

  private fun project(mvp: FloatArray, x: Float, y: Float, z: Float): FloatArray? {
    val clip = FloatArray(4)
    Matrix.multiplyMV(clip, 0, mvp, 0, floatArrayOf(x, y, z, 1f), 0)
    if (clip[3] <= 1e-5f || !clip.all(Float::isFinite)) return null
    return floatArrayOf(clip[0] / clip[3], clip[1] / clip[3])
  }

  private fun parseSprites(root: JSONObject): List<Sprite> {
    val result = ArrayList<Sprite>()
    val highlights = root.getJSONArray("highlightNodes")
    for (i in 0 until highlights.length()) {
      val item = highlights.getJSONObject(i)
      val center = readVector(item.getJSONArray("centerPx"), 2)
      val group = item.optString("semanticGroup")
      val layer = if (group == "RESIDENT_BACK_GREEN_GOLD") Layer.BACK else Layer.FRONT
      result += Sprite(center[0], center[1], 4f, readVector(item.getJSONArray("meanColorLinear"), 3), i * GOLDEN_ANGLE, layer, 0)
    }
    val particles = root.getJSONArray("particles")
    for (i in 0 until particles.length()) {
      val item = particles.getJSONObject(i)
      val center = readVector(item.getJSONArray("centerPx"), 2)
      result += Sprite(
        center[0], center[1], item.optDouble("radiusPx", 3.0).toFloat().coerceIn(1.5f, 8f),
        readVector(item.getJSONArray("colorLinear"), 3), i * GOLDEN_ANGLE, Layer.BACK, 0,
      )
    }
    val droplets = root.getJSONArray("droplets")
    for (i in 0 until droplets.length()) {
      val item = droplets.getJSONObject(i)
      val center = readVector(item.getJSONArray("centerPx"), 2)
      result += Sprite(
        center[0], center[1], item.optDouble("radiusPx", 18.0).toFloat().coerceIn(8f, 46f),
        readVector(item.getJSONArray("colorLinear"), 3), i * GOLDEN_ANGLE, Layer.FRONT, 1,
      )
    }
    return result
  }

  private fun createSprites(sprites: List<Sprite>): GlMesh {
    val data = FloatArray(sprites.size * POINT_STRIDE)
    var cursor = 0
    for (sprite in sprites) {
      data[cursor++] = (sprite.x - REF_CENTER_X) / REF_RADIUS
      data[cursor++] = (REF_CENTER_Y - sprite.y) / REF_RADIUS
      data[cursor++] = sprite.radius
      data[cursor++] = sprite.color[0]
      data[cursor++] = sprite.color[1]
      data[cursor++] = sprite.color[2]
      data[cursor++] = sprite.phase
      data[cursor++] = sprite.type.toFloat()
    }
    return uploadMesh(data, POINT_STRIDE, GLES30.GL_POINTS)
  }

  private fun createRibbon(rawPoints: List<FloatArray>, fullWidthPx: Float, offsetPx: Float): GlMesh {
    val points = smoothPath(rawPoints, 8)
    val lengths = FloatArray(points.size)
    for (i in 1 until points.size) lengths[i] = lengths[i - 1] + hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1])
    val totalLength = lengths.last().coerceAtLeast(1f)
    val halfWidth = max(0.8f, fullWidthPx * 0.5f)
    val vertices = ArrayList<Float>((points.size - 1) * 6 * RIBBON_STRIDE)
    fun emit(p: FloatArray, across: Float, along: Float) {
      vertices += (p[0] - REF_CENTER_X) / REF_RADIUS
      vertices += (REF_CENTER_Y - p[1]) / REF_RADIUS
      vertices += across
      vertices += along
      vertices += 0f
    }
    for (i in 0 until points.lastIndex) {
      val a = points[i]; val b = points[i + 1]
      val prev = points[max(0, i - 1)]; val next = points[min(points.lastIndex, i + 2)]
      val ta = normalize(b[0] - prev[0], b[1] - prev[1])
      val tb = normalize(next[0] - a[0], next[1] - a[1])
      val na = floatArrayOf(-ta[1], ta[0]); val nb = floatArrayOf(-tb[1], tb[0])
      val pa = floatArrayOf(a[0] + na[0] * offsetPx, a[1] + na[1] * offsetPx)
      val pb = floatArrayOf(b[0] + nb[0] * offsetPx, b[1] + nb[1] * offsetPx)
      val leftA = floatArrayOf(pa[0] + na[0] * halfWidth, pa[1] + na[1] * halfWidth)
      val rightA = floatArrayOf(pa[0] - na[0] * halfWidth, pa[1] - na[1] * halfWidth)
      val leftB = floatArrayOf(pb[0] + nb[0] * halfWidth, pb[1] + nb[1] * halfWidth)
      val rightB = floatArrayOf(pb[0] - nb[0] * halfWidth, pb[1] - nb[1] * halfWidth)
      val u0 = lengths[i] / totalLength; val u1 = lengths[i + 1] / totalLength
      emit(leftA, 1f, u0); emit(rightA, -1f, u0); emit(leftB, 1f, u1)
      emit(rightA, -1f, u0); emit(rightB, -1f, u1); emit(leftB, 1f, u1)
    }
    return uploadMesh(vertices.toFloatArray(), RIBBON_STRIDE, GLES30.GL_TRIANGLES)
  }

  private fun smoothPath(points: List<FloatArray>, stepsPerSegment: Int): List<FloatArray> {
    if (points.size < 3) return points
    val output = ArrayList<FloatArray>((points.size - 1) * stepsPerSegment + 1)
    for (i in 0 until points.lastIndex) {
      val p0 = points[max(0, i - 1)]; val p1 = points[i]
      val p2 = points[i + 1]; val p3 = points[min(points.lastIndex, i + 2)]
      for (step in 0 until stepsPerSegment) {
        val t = step.toFloat() / stepsPerSegment; val t2 = t * t; val t3 = t2 * t
        output += floatArrayOf(
          0.5f * (2f * p1[0] + (-p0[0] + p2[0]) * t + (2f * p0[0] - 5f * p1[0] + 4f * p2[0] - p3[0]) * t2 + (-p0[0] + 3f * p1[0] - 3f * p2[0] + p3[0]) * t3),
          0.5f * (2f * p1[1] + (-p0[1] + p2[1]) * t + (2f * p0[1] - 5f * p1[1] + 4f * p2[1] - p3[1]) * t2 + (-p0[1] + 3f * p1[1] - 3f * p2[1] + p3[1]) * t3),
        )
      }
    }
    output += points.last().copyOf()
    return output
  }

  private fun uploadMesh(data: FloatArray, strideFloats: Int, drawMode: Int): GlMesh {
    val vao = IntArray(1); val vbo = IntArray(1)
    GLES30.glGenVertexArrays(1, vao, 0); GLES30.glGenBuffers(1, vbo, 0)
    GLES30.glBindVertexArray(vao[0]); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
    val buffer = ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    buffer.put(data).position(0)
    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * Float.SIZE_BYTES, buffer, GLES30.GL_STATIC_DRAW)
    val stride = strideFloats * Float.SIZE_BYTES
    if (strideFloats == RIBBON_STRIDE) {
      GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
      GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, stride, 8)
      GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 12)
      GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 16)
    } else {
      GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)
      GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, stride, 8)
      GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 12)
      GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, stride, 24)
      GLES30.glEnableVertexAttribArray(4); GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, stride, 28)
    }
    GLES30.glBindVertexArray(0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    val count = data.size / strideFloats
    return GlMesh(vao[0], vbo[0], 0, count, drawMode, vertexCount = count)
  }

  private fun readPoints(array: JSONArray): List<FloatArray> = (0 until array.length()).map { readVector(array.getJSONArray(it), 2) }
  private fun readVector(array: JSONArray, count: Int): FloatArray {
    require(array.length() >= count) { "Vector requires $count values" }
    return FloatArray(count) { array.getDouble(it).toFloat() }
  }
  private fun normalize(x: Float, y: Float): FloatArray {
    val len = sqrt(x * x + y * y).coerceAtLeast(1e-4f)
    return floatArrayOf(x / len, y / len)
  }
  private fun hypot(x: Float, y: Float) = sqrt(x * x + y * y)
  private fun vectorLength(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  companion object {
    private const val MANIFEST_ASSET = "outer_optical_field/OUTER_OPTICAL_FIELD_RUNTIME_V1.json"
    private const val SCHEMA = "AI_ORB_TAB3_OUTER_OPTICAL_RUNTIME_V1"
    private const val REF_SIZE = 1254f
    private const val REF_CENTER_X = 626.5f
    private const val REF_CENTER_Y = 620.5f
    private const val REF_RADIUS = 326.00238f
    private const val RIBBON_STRIDE = 5
    private const val POINT_STRIDE = 8
    private const val GOLDEN_ANGLE = 2.3999632f

    private const val RIBBON_VERTEX = """#version 300 es
precision highp float;
layout(location=0) in vec2 aReferenceCorePos;
layout(location=1) in float aAcross;
layout(location=2) in float aAlong;
layout(location=3) in float aUnusedDepth;
uniform vec2 uCoreCenter;
uniform vec2 uCoreRadii;
uniform vec2 uResolution;
uniform float uCoreCenterViewZ;
uniform float uCoreRadiusView;
uniform float uLayerSign;
uniform vec4 uProjectionDepth;
uniform float uTime;
uniform float uPhase;
uniform float uPeriod;
uniform float uKind;
out vec2 vNdc;
out float vAcross;
out float vAlong;
out float vFresnel;
void main() {
    float wave = sin(aAlong * 6.28318 + uTime * 6.28318 / max(uPeriod, 0.1) + uPhase);
    float amplitude = (uKind < 0.5 ? 0.0018 : 0.0032) * (0.25 + abs(aAcross));
    vec2 local = aReferenceCorePos + vec2(aUnusedDepth * 0.03, wave * amplitude);
    vec2 ndc = uCoreCenter + local * uCoreRadii;
    float r2 = dot(local, local);
    float surface = sqrt(max(0.0, 1.0 - min(r2, 1.0))) * uCoreRadiusView;
    float outsideOffset = max(0.0, sqrt(max(r2, 1.0)) - 1.0) * uCoreRadiusView * 0.30;
    float viewZ = uCoreCenterViewZ + uLayerSign * (surface + outsideOffset + 0.008 * uCoreRadiusView);
    float clipZ = uProjectionDepth.x * viewZ + uProjectionDepth.y;
    float clipW = uProjectionDepth.z * viewZ + uProjectionDepth.w;
    gl_Position = vec4(ndc * clipW, clipZ, clipW);
    vNdc = ndc;
    vAcross = aAcross;
    vAlong = aAlong;
    vFresnel = pow(clamp(1.0 - sqrt(max(0.0, 1.0 - min(r2, 1.0))), 0.0, 1.0), 5.0);
}
"""

    private const val RIBBON_FRAGMENT = """#version 300 es
precision highp float;
in vec2 vNdc;
in float vAcross;
in float vAlong;
in float vFresnel;
out vec4 fragColor;
uniform vec2 uResolution;
uniform float uTime;
uniform float uHdrScale;
uniform float uKind;
uniform float uMaterial;
uniform float uPhase;
uniform float uPeriod;
uniform float uLayerSign;
uniform float uReferenceWidth;
uniform vec3 uColor;
uniform sampler2D uRefraction;
uniform int uUseRefraction;
void main() {
    float x = abs(vAcross);
    float edge = exp(-pow((x - 0.79) * 7.0, 2.0)) + vFresnel * 0.35;
    float body = exp(-x * x * 2.65);
    float fold = 0.5 + 0.5 * sin(vAlong * 67.0 - uTime * (1.1 + uMaterial * 0.19) + uPhase + x * 3.4);
    float glint = pow(max(0.0, sin(vAlong * 12.0 - uTime * 6.28318 / max(uPeriod, 0.1) + uPhase)), 11.0);
    float materialGain = uMaterial < 0.5 ? 1.16 : (uMaterial < 1.5 ? 1.32 : 0.91);
    vec3 color = uColor * materialGain;
    float alpha;
    if (uKind < 0.5) {
        alpha = ((uMaterial < 1.5 ? 0.24 : 0.17) * body + 0.32 * edge + 0.18 * glint) * (0.78 + 0.22 * fold);
        color *= 0.72 + 0.42 * fold + 0.72 * glint;
        color += vec3(0.035, 0.045, 0.038) * edge;
    } else {
        float thread = exp(-x * x * 13.0);
        alpha = (0.30 * thread + 0.13 * edge + 0.25 * glint) * (0.76 + 0.24 * fold);
        color *= 1.02 + 0.55 * fold + 0.95 * glint;
    }
    alpha *= clamp(0.90 + uReferenceWidth * 0.004, 0.90, 1.10);
    if (uUseRefraction == 1) {
        vec2 uv = gl_FragCoord.xy / max(uResolution, vec2(1.0));
        vec2 bend = normalize(vec2(dFdx(vNdc.x), dFdy(vNdc.y)) + vec2(1e-4)) * (0.0012 + edge * 0.0015);
        vec3 refracted = texture(uRefraction, clamp(uv + bend, vec2(0.001), vec2(0.999))).rgb;
        color = mix(refracted, color, 0.73);
    }
    float depthGain = uLayerSign > 0.0 ? 1.0 : 0.82;
    fragColor = vec4(color * uHdrScale, clamp(alpha * depthGain, 0.0, 0.76));
}
"""

    private const val POINT_VERTEX = """#version 300 es
precision highp float;
layout(location=0) in vec2 aReferenceCorePos;
layout(location=1) in float aRadiusPx;
layout(location=2) in vec3 aColor;
layout(location=3) in float aPhase;
layout(location=4) in float aType;
uniform vec2 uCoreCenter;
uniform vec2 uCoreRadii;
uniform vec2 uResolution;
uniform float uCoreCenterViewZ;
uniform float uCoreRadiusView;
uniform float uLayerSign;
uniform vec4 uProjectionDepth;
uniform float uTime;
out vec3 vColor;
out float vType;
out float vPulse;
void main() {
    float drift = sin(uTime * 0.42 + aPhase) * 0.0018;
    vec2 local = aReferenceCorePos + vec2(drift, cos(uTime * 0.31 + aPhase) * 0.0012);
    vec2 ndc = uCoreCenter + local * uCoreRadii;
    float surface = sqrt(max(0.0, 1.0 - min(dot(local, local), 1.0))) * uCoreRadiusView;
    float viewZ = uCoreCenterViewZ + uLayerSign * (surface + 0.010 * uCoreRadiusView);
    float clipZ = uProjectionDepth.x * viewZ + uProjectionDepth.y;
    float clipW = uProjectionDepth.z * viewZ + uProjectionDepth.w;
    gl_Position = vec4(ndc * clipW, clipZ, clipW);
    float actualRadiusPx = 0.25 * (uCoreRadii.x * uResolution.x + uCoreRadii.y * uResolution.y);
    gl_PointSize = clamp((2.0 * aRadiusPx / 326.00238) * actualRadiusPx, 2.0, 90.0);
    vColor = aColor;
    vType = aType;
    vPulse = 0.68 + 0.32 * (0.5 + 0.5 * sin(uTime * 0.8 + aPhase));
}
"""

    private const val POINT_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vColor;
in float vType;
in float vPulse;
out vec4 fragColor;
uniform vec2 uResolution;
uniform float uHdrScale;
uniform sampler2D uRefraction;
uniform int uUseRefraction;
void main() {
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    float r = length(p);
    if (r > 1.0) discard;
    float alpha;
    vec3 color;
    if (vType > 0.5) {
        float rim = exp(-pow((r - 0.78) * 10.0, 2.0));
        float center = exp(-r * r * 2.2);
        float spec = exp(-dot(p - vec2(-0.32, 0.42), p - vec2(-0.32, 0.42)) * 65.0);
        alpha = (0.10 + 0.18 * center + 0.26 * rim + 0.32 * spec) * vPulse;
        color = vColor * (0.42 + 0.6 * center) + vec3(0.48, 0.78, 0.72) * spec;
    } else {
        float glow = exp(-r * r * 4.8);
        float core = exp(-r * r * 30.0);
        alpha = (0.10 + 0.21 * glow + 0.30 * core) * vPulse;
        color = vColor * (0.72 + core * 1.4) + vec3(0.10, 0.12, 0.10) * core;
    }
    if (uUseRefraction == 1) {
        vec2 uv = gl_FragCoord.xy / max(uResolution, vec2(1.0));
        color = mix(texture(uRefraction, clamp(uv, vec2(0.001), vec2(0.999))).rgb, color, 0.8);
    }
    fragColor = vec4(color * uHdrScale, clamp(alpha, 0.0, 0.75));
}
"""
  }
}
