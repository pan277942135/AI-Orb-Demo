package com.example.orb.task

import android.opengl.GLES30
import com.example.orb.gl.GlBufferUtils
import com.example.orb.gl.GlShaderProgram
import kotlin.math.cos
import kotlin.math.sin

/**
 * Calibrated KF04-standard restrained liquid material parameters for the rounded-photo
 * perimeter waterlight (never thick neon stroke; strictly translucent layered liquid!).
 */
data class PerimeterWaterLightMaterial(
  val l1IceAlphaScale: Float = 1.0f,
  val l2EmeraldAlphaScale: Float = 1.0f,
  val l3GoldAlphaScale: Float = 1.0f,
  val dropletCountPerSide: Int = 11,
)

/**
 * Sparse, irregular transparent crystal water droplets (L4) + localized KF02 bottom contact
 * starburst & KF05 top-join convergence glint, strictly anchored to [PerimeterArcLengthTable].
 */
class PerimeterWaterLightParticles(
  private val material: PerimeterWaterLightMaterial = PerimeterWaterLightMaterial(),
) {
  // Deterministic irregular droplet anchors (never uniform mechanical bead rows!)
  private data class DropletSeed(
    val side: PerimeterSide,
    val arcFrac: Float,
    val outwardOffsetRatio: Float, // fraction of PhotoW strictly outside border!
    val sizeRatio: Float,
    val speed: Float,
    val phase: Float,
  )

  private val seeds: List<DropletSeed> = buildList {
    val leftFracs = floatArrayOf(0.08f, 0.17f, 0.29f, 0.37f, 0.48f, 0.57f, 0.68f, 0.77f, 0.86f, 0.94f, 0.23f)
    val rightFracs = floatArrayOf(0.11f, 0.21f, 0.33f, 0.43f, 0.52f, 0.63f, 0.72f, 0.81f, 0.90f, 0.96f, 0.60f)
    val offsets = floatArrayOf(0.018f, 0.032f, 0.024f, 0.038f, 0.021f, 0.035f, 0.026f, 0.041f, 0.022f, 0.031f, 0.029f)
    val sizes = floatArrayOf(0.011f, 0.008f, 0.013f, 0.007f, 0.010f, 0.012f, 0.008f, 0.009f, 0.011f, 0.007f, 0.009f)
    val n = material.dropletCountPerSide.coerceAtMost(leftFracs.size)
    for (i in 0 until n) {
      add(DropletSeed(PerimeterSide.LEFT, leftFracs[i], offsets[i], sizes[i], 0.025f + (i % 3) * 0.011f, i * 1.9f))
      add(DropletSeed(PerimeterSide.RIGHT, rightFracs[i], offsets[(i + 4) % offsets.size], sizes[(i + 2) % sizes.size], 0.022f + (i % 4) * 0.009f, i * 2.3f + 0.7f))
    }
  }

  // Float packing per point: posNdc(2) + sizePx(1) + alpha(1) + kind(1) = 5 floats
  private val pointBuffer = FloatArray(64 * 5)

  /**
   * Packs active sparse L4 perimeter droplets, Task channel droplets, KF02 bottom contact
   * warm gold starburst + cyan ripple, and KF05/KF06 top-center convergence glint.
   * Returns active point count.
   */
  fun buildDynamicPoints(
    table: PerimeterArcLengthTable,
    layout: TaskSceneLayoutSnapshot,
    snapshot: TaskEnergySnapshot,
  ): Int {
    val sw = layout.surfaceWidthPx.coerceAtLeast(1).toFloat()
    val sh = layout.surfaceHeightPx.coerceAtLeast(1).toFloat()
    val photoW = table.geometry.widthPx.coerceAtLeast(64f)
    val t = snapshot.flowTimeSec
    var count = 0

    fun emit(px: Vec2, sizePx: Float, alpha: Float, kind: Float) {
      if (count >= 64 || alpha <= 0.005f) return
      val o = count * 5
      pointBuffer[o] = (px.x / sw) * 2f - 1f
      pointBuffer[o + 1] = 1f - (px.y / sh) * 2f
      pointBuffer[o + 2] = sizePx.coerceIn(4f, 160f)
      pointBuffer[o + 3] = alpha.coerceIn(0f, 1f)
      pointBuffer[o + 4] = kind
      count++
    }

    // 1. Sparse translucent droplets floating around the rising Task Energy channel (KF01-KF06)
    if (snapshot.releaseProgress > 0.08f) {
      val orbDiam = layout.orbTransform.orbDiameterPx
      val start = layout.orbAnchorTopPx
      val end = layout.photoBottomCenterPx
      val spanY = (start.y - end.y).coerceAtLeast(24f)
      val offsetsX = floatArrayOf(-0.44f, 0.48f, -0.22f, 0.30f, -0.64f, 0.72f)
      val baseYFrac = floatArrayOf(0.20f, 0.32f, 0.52f, 0.68f, 0.40f, 0.58f)
      for (i in offsetsX.indices) {
        val driftY = (baseYFrac[i] + t * (0.06f + (i % 3) * 0.02f)) % 0.92f
        if (driftY > snapshot.releaseProgress) continue
        val p = Vec2(
          x = start.x + offsetsX[i] * orbDiam + sin(t * 1.4f + i * 2.1f) * orbDiam * 0.05f,
          y = start.y - driftY * spanY,
        )
        val sz = (photoW * (0.009f + (i % 3) * 0.0025f)).coerceAtLeast(6f)
        emit(p, sz, 0.54f, 0f)
      }
    }

    // 2. Sparse irregular L4 crystal droplets strictly outside activated perimeter segments
    for (seed in seeds) {
      val maxProg = if (seed.side == PerimeterSide.LEFT) snapshot.leftProgress else snapshot.rightProgress
      val sFrac = if (snapshot.coverageComplete) {
        (seed.arcFrac + t * seed.speed) % 0.98f
      } else {
        seed.arcFrac
      }
      if (sFrac > maxProg - 0.015f || maxProg <= 0.02f) continue
      val sample = table.sample(seed.side, sFrac)
      val wobble = 0.75f + 0.35f * sin(t * 1.7f + seed.phase)
      val outPx = photoW * seed.outwardOffsetRatio * wobble + 3.5f
      val pos = sample.positionPx + sample.outwardNormal * outPx
      val sz = (photoW * seed.sizeRatio).coerceAtLeast(6f)
      val alpha = (0.42f + 0.24f * cos(t * 2.1f + seed.phase)).coerceIn(0.18f, 0.68f)
      emit(pos, sz, alpha, 0f)
    }

    // 3. KF02 Bottom-Center localized warm gold contact starburst + subtle cyan water ripple
    if (snapshot.contactIntensity > 0.01f) {
      val cPos = layout.photoBottomCenterPx
      emit(cPos + Vec2(0f, 3f), photoW * 0.21f, snapshot.contactIntensity * 0.62f, 2f)
      emit(cPos + Vec2(0f, 1.5f), photoW * 0.16f, snapshot.contactIntensity * 0.88f, 1f)
    }

    // 4. KF05 Top-Center convergence warm champagne-gold starburst (clearly softened in KF06!)
    if (snapshot.joinIntensity > 0.01f) {
      val tPos = layout.photoTopCenterPx
      emit(tPos - Vec2(0f, 2f), photoW * 0.17f, snapshot.joinIntensity * 0.45f, 2f)
      emit(tPos - Vec2(0f, 1.5f), photoW * 0.18f, snapshot.joinIntensity * 0.92f, 1f)
    }

    return count
  }

  fun rawPointBuffer(): FloatArray = pointBuffer
}

/**
 * GPU renderer for the unified-path 3-layer Perimeter WaterLight (`L1` icy-blue liquid body,
 * `L2` emerald kelp veil, `L3` champagne-gold filament) plus `L4` sparse crystal droplets and
 * localized KF02/KF05 contact/join optics.
 *
 * Strictly shares ONE [PerimeterArcLengthTable] and never allows liquid ribbons to cross
 * inside the photo interior.
 */
class PerimeterWaterLightRenderer(
  private val material: PerimeterWaterLightMaterial = PerimeterWaterLightMaterial(),
) {
  private class StripGpuMesh(
    val side: PerimeterSide,
    val layer: PerimeterRibbonLayer,
    var vao: Int = 0,
    var vbo: Int = 0,
    var vertexCount: Int = 0,
  )

  private var ribbonProgram: GlShaderProgram? = null
  private var sparkleProgram: GlShaderProgram? = null
  private val strips: List<StripGpuMesh> = buildList {
    for (layer in listOf(
      PerimeterRibbonLayer.L2_EMERALD_VEIL,
      PerimeterRibbonLayer.L1_ICE_BLUE_BODY,
      PerimeterRibbonLayer.L3_GOLD_FILAMENT,
    )) {
      add(StripGpuMesh(PerimeterSide.LEFT, layer))
      add(StripGpuMesh(PerimeterSide.RIGHT, layer))
    }
  }
  private val particles = PerimeterWaterLightParticles(material)
  private var pointVao: Int = 0
  private var pointVbo: Int = 0

  private var cachedPhoto: PhotoFrameGeometry? = null
  private var cachedSurfaceW: Int = -1
  private var cachedSurfaceH: Int = -1
  private var cachedTable: PerimeterArcLengthTable? = null

  fun init() {
    release()
    ribbonProgram = GlShaderProgram(
      TaskWaterLightShaders.PERIMETER_RIBBON_VERT,
      TaskWaterLightShaders.PERIMETER_RIBBON_FRAG,
      "PerimeterWaterLightRibbon",
    )
    sparkleProgram = GlShaderProgram(
      TaskWaterLightShaders.TASK_SPARKLE_VERT,
      TaskWaterLightShaders.TASK_SPARKLE_FRAG,
      "TaskSparkleDroplets",
    )

    val vaos = IntArray(strips.size)
    val vbos = IntArray(strips.size)
    GLES30.glGenVertexArrays(strips.size, vaos, 0)
    GLES30.glGenBuffers(strips.size, vbos, 0)
    for (i in strips.indices) {
      strips[i].vao = vaos[i]
      strips[i].vbo = vbos[i]
    }

    val pVao = IntArray(1)
    val pVbo = IntArray(1)
    GLES30.glGenVertexArrays(1, pVao, 0)
    GLES30.glGenBuffers(1, pVbo, 0)
    pointVao = pVao[0]
    pointVbo = pVbo[0]
  }

  fun draw(
    layout: TaskSceneLayoutSnapshot,
    table: PerimeterArcLengthTable,
    snapshot: TaskEnergySnapshot,
    hdrScale: Float,
  ) {
    val rProg = ribbonProgram ?: return
    rebuildStripsIfNeeded(layout, table)

    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    // 1. Draw L2 Emerald Veil -> L1 Icy-Blue Body -> L3 Champagne Gold Filament along unified arc-length strips
    if (snapshot.leftProgress > 0.001f || snapshot.rightProgress > 0.001f) {
      rProg.use()
      rProg.setFloat("uFlowTime", snapshot.flowTimeSec)
      rProg.setFloat("uPhotoWidthPx", table.geometry.widthPx)
      rProg.setFloat("uCoverageComplete", if (snapshot.coverageComplete) 1f else 0f)
      rProg.setFloat("uHdrScale", hdrScale)

      for (strip in strips) {
        if (strip.vertexCount <= 0 || strip.vao == 0) continue
        val progress = if (strip.side == PerimeterSide.LEFT) snapshot.leftProgress else snapshot.rightProgress
        if (progress <= 0.001f) continue
        val sideSign = if (strip.side == PerimeterSide.LEFT) -1f else 1f

        rProg.setInt("uLayerKind", strip.layer.shaderLayerId)
        rProg.setFloat("uSideSign", sideSign)
        rProg.setFloat("uRevealProgress", progress)

        GLES30.glBindVertexArray(strip.vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, strip.vertexCount)
      }
      GLES30.glBindVertexArray(0)
    }

    // 2. Draw L4 sparse crystal droplets + KF02 bottom contact starburst + KF05/KF06 top-join glint
    val sProg = sparkleProgram ?: return
    if (pointVao == 0 || pointVbo == 0) return
    val pointCount = particles.buildDynamicPoints(table, layout, snapshot)
    if (pointCount <= 0) return

    GLES30.glBindVertexArray(pointVao)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointVbo)
    GLES30.glBufferData(
      GLES30.GL_ARRAY_BUFFER,
      pointCount * 5 * 4,
      GlBufferUtils.toFloatBuffer(particles.rawPointBuffer().copyOf(pointCount * 5)),
      GLES30.GL_STREAM_DRAW,
    )
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 20, 0)
    GLES30.glEnableVertexAttribArray(1)
    GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, 20, 8)
    GLES30.glEnableVertexAttribArray(2)
    GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, 20, 12)
    GLES30.glEnableVertexAttribArray(3)
    GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, 20, 16)

    sProg.use()
    sProg.setFloat("uHdrScale", hdrScale)
    GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointCount)
    GLES30.glBindVertexArray(0)
  }

  private fun rebuildStripsIfNeeded(layout: TaskSceneLayoutSnapshot, table: PerimeterArcLengthTable) {
    if (
      cachedPhoto == layout.photoFrame &&
      cachedSurfaceW == layout.surfaceWidthPx &&
      cachedSurfaceH == layout.surfaceHeightPx &&
      cachedTable === table
    ) {
      return
    }
    cachedPhoto = layout.photoFrame
    cachedSurfaceW = layout.surfaceWidthPx
    cachedSurfaceH = layout.surfaceHeightPx
    cachedTable = table

    val strideBytes = PerimeterRibbonMeshBuilder.FLOATS_PER_VERTEX * 4
    for (strip in strips) {
      if (strip.vao == 0 || strip.vbo == 0) continue
      val verts = PerimeterRibbonMeshBuilder.buildLayerStripNdc(
        table = table,
        side = strip.side,
        layer = strip.layer,
        surfaceWidthPx = layout.surfaceWidthPx,
        surfaceHeightPx = layout.surfaceHeightPx,
      )
      strip.vertexCount = verts.size / PerimeterRibbonMeshBuilder.FLOATS_PER_VERTEX

      GLES30.glBindVertexArray(strip.vao)
      GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, strip.vbo)
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
      GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, strideBytes, 16)
    }
    GLES30.glBindVertexArray(0)
  }

  fun release() {
    ribbonProgram?.release()
    ribbonProgram = null
    sparkleProgram?.release()
    sparkleProgram = null
    val vaos = strips.map { it.vao }.filter { it != 0 }.toIntArray()
    val vbos = strips.map { it.vbo }.filter { it != 0 }.toIntArray()
    if (vaos.isNotEmpty()) GLES30.glDeleteVertexArrays(vaos.size, vaos, 0)
    if (vbos.isNotEmpty()) GLES30.glDeleteBuffers(vbos.size, vbos, 0)
    for (s in strips) {
      s.vao = 0
      s.vbo = 0
      s.vertexCount = 0
    }
    if (pointVao != 0) {
      GLES30.glDeleteVertexArrays(1, intArrayOf(pointVao), 0)
      pointVao = 0
    }
    if (pointVbo != 0) {
      GLES30.glDeleteBuffers(1, intArrayOf(pointVbo), 0)
      pointVbo = 0
    }
    cachedPhoto = null
    cachedTable = null
  }
}
