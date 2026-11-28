package com.example.orb.task

import android.content.res.AssetManager
import android.opengl.GLES30
import com.example.orb.gl.GlBufferUtils
import com.example.orb.gl.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.sin

/**
 * Calibrated KF04-standard restrained liquid material parameters for the rounded-photo
 * perimeter waterlight (never thick neon stroke; strictly translucent layered liquid!).
 *
 * All per-layer scale factors are explicitly bound via `uniform float uLayerAlphaScale`
 * in `PERIMETER_RIBBON_FRAG`, with post-scale frozen caps strictly enforced:
 *   L1 <= 0.38, L2 <= 0.24, L3 <= 0.50.
 */
data class PerimeterWaterLightMaterial(
  val l1IceAlphaScale: Float = 1.25f,
  val l2EmeraldAlphaScale: Float = 1.15f,
  val l3GoldAlphaScale: Float = 1.10f,
  val dropletCountPerSide: Int = 11,
  val widthProfile: PerimeterRibbonWidthProfile = PerimeterRibbonWidthProfile.LEGACY_6KF_FROZEN,
  val l1AlphaCapOverride: Float = 0f,
  val l2AlphaCapOverride: Float = 0f,
  val l3AlphaCapOverride: Float = 0f,
  val directDisplayLdr: Boolean = false,
) {
  fun alphaScaleFor(layer: PerimeterRibbonLayer): Float = when (layer) {
    PerimeterRibbonLayer.L1_ICE_BLUE_BODY -> l1IceAlphaScale
    PerimeterRibbonLayer.L2_EMERALD_VEIL -> l2EmeraldAlphaScale
    PerimeterRibbonLayer.L3_GOLD_FILAMENT -> l3GoldAlphaScale
  }

  fun alphaCapOverrideFor(layer: PerimeterRibbonLayer): Float = when (layer) {
    PerimeterRibbonLayer.L1_ICE_BLUE_BODY -> l1AlphaCapOverride
    PerimeterRibbonLayer.L2_EMERALD_VEIL -> l2AlphaCapOverride
    PerimeterRibbonLayer.L3_GOLD_FILAMENT -> l3AlphaCapOverride
  }

  fun effectiveAlphaCapFor(layer: PerimeterRibbonLayer): Float {
    val override = alphaCapOverrideFor(layer)
    val frozen = when (layer) {
      PerimeterRibbonLayer.L1_ICE_BLUE_BODY -> FROZEN_CAP_L1_ALPHA
      PerimeterRibbonLayer.L2_EMERALD_VEIL -> FROZEN_CAP_L2_ALPHA
      PerimeterRibbonLayer.L3_GOLD_FILAMENT -> FROZEN_CAP_L3_ALPHA
    }
    return if (override > 0f) minOf(frozen, override) else frozen
  }

  companion object {
    const val FROZEN_CAP_L1_ALPHA = 0.38f
    const val FROZEN_CAP_L2_ALPHA = 0.24f
    const val FROZEN_CAP_L3_ALPHA = 0.50f

    const val TAB2_V22_CAP_L1_ALPHA = 0.30f
    const val TAB2_V22_CAP_L2_ALPHA = 0.16f
    const val TAB2_V22_CAP_L3_ALPHA = 0.28f

    /**
     * Independent Tab 2 V2.2 restrained slender material profile calibrated for direct sRGB
     * framebuffer display (`L1 <= 0.8% / 0.30`, `L2 <= 1.8% / 0.16`, `L3 <= 0.45% / 0.28`,
     * sparse irregular L4 droplets, soft LDR tone-fit).
     */
    val Tab2V22 = PerimeterWaterLightMaterial(
      l1IceAlphaScale = 0.96f,
      l2EmeraldAlphaScale = 0.86f,
      l3GoldAlphaScale = 0.92f,
      dropletCountPerSide = 8,
      widthProfile = PerimeterRibbonWidthProfile.TAB2_V22_SLENDER,
      l1AlphaCapOverride = TAB2_V22_CAP_L1_ALPHA,
      l2AlphaCapOverride = TAB2_V22_CAP_L2_ALPHA,
      l3AlphaCapOverride = TAB2_V22_CAP_L3_ALPHA,
      directDisplayLdr = true,
    )
  }
}

/**
 * Snapshot of real GPU draw metrics for [PerimeterWaterLightRenderer].
 */
data class PerimeterDrawStats(
  val vertexCount: Int = 0,
  val drawCalls: Int = 0,
  val shaderLinked: Boolean = false,
  val vaoIds: List<Int> = emptyList(),
  val vboIds: List<Int> = emptyList(),
  val sparkleCount: Int = 0,
  val sparkleDrawCalls: Int = 0,
  val usedQuadFallback: Boolean = false,
  val maxAliasedPointSizePx: Float = 64f,
  val refractionEnabled: Boolean = false,
  val refractionTexId: Int = 0,
  val usedV2Material: Boolean = false,
  val boundMaterialTextureIds: List<Int> = emptyList(),
)

/**
 * Sparse, irregular transparent crystal water droplets (L4) + compact localized KF02 bottom contact
 * starburst & KF05 top-join convergence glint, strictly anchored to [PerimeterArcLengthTable].
 *
 * Also builds screen-space quad vertices so contact/join glows NEVER truncate when a GPU reports
 * a small `GL_ALIASED_POINT_SIZE_RANGE`.
 */
class PerimeterWaterLightParticles(
  private val material: PerimeterWaterLightMaterial = PerimeterWaterLightMaterial(),
) {
  // Deterministic irregular droplet anchors (never uniform mechanical bead rows!)
  private data class DropletSeed(
    val loopU: Float,
    val outwardOffsetRatio: Float, // fraction of PhotoW strictly outside border!
    val sizeRatio: Float,
    val loopSpeed: Float,
    val direction: Float,
    val phase: Float,
    val lifetimeSec: Float,
    val spriteIndex: Float,
  )

  private val seeds: List<DropletSeed> = buildList {
    val perSide = material.dropletCountPerSide.coerceIn(1, 16)
    for (side in 0..1) {
      for (i in 0 until perSide) {
        val index = side * perSide + i
        val jitter = seedValue(index, 1)
        val laneU = ((i + 0.5f + (jitter - 0.5f) * 0.72f) / perSide).coerceIn(0.02f, 0.98f)
        val loopU = side * 0.5f + laneU * 0.5f
        add(
          DropletSeed(
            loopU = loopU,
            outwardOffsetRatio = 0.013f + seedValue(index, 2) * 0.026f,
            sizeRatio = 0.006f + seedValue(index, 3) * 0.009f,
            loopSpeed = 0.012f + seedValue(index, 4) * 0.022f,
            direction = if (seedValue(index, 5) < 0.5f) -1f else 1f,
            phase = seedValue(index, 6) * 2f * PI.toFloat(),
            lifetimeSec = 3.4f + seedValue(index, 7) * 5.2f,
            spriteIndex = ((index * 7 + (seedValue(index, 8) * 16f).toInt()) % 16).toFloat(),
          ),
        )
      }
    }
  }

  // Float packing per point: posNdc(2) + sizePx + alpha + kind + atlas sprite index.
  private val pointBuffer = FloatArray(MAX_POINTS * POINT_STRIDE_FLOATS)
  private val pointUploadBuffer = ByteBuffer.allocateDirect(MAX_POINTS * POINT_STRIDE_FLOATS * 4)
    .order(ByteOrder.nativeOrder()).asFloatBuffer()
  // Screen-space quad buffer: 6 vertices * posNdc(2) + size + alpha + kind + atlas index + quadUv(2).
  private val quadBuffer = FloatArray(MAX_POINTS * 6 * QUAD_STRIDE_FLOATS)
  private val quadUploadBuffer = ByteBuffer.allocateDirect(MAX_POINTS * 6 * QUAD_STRIDE_FLOATS * 4)
    .order(ByteOrder.nativeOrder()).asFloatBuffer()

  var maxEmittedPointSizePx: Float = 0f
    private set

  /**
   * Packs active sparse L4 perimeter droplets, Task channel droplets, compact KF02 bottom contact
   * warm gold starburst + cyan ripple, and KF05/KF06 top-center convergence glint.
   * Returns active point count.
   */
  fun buildDynamicPoints(
    table: PerimeterArcLengthTable,
    layout: TaskSceneLayoutSnapshot,
    snapshot: TaskEnergySnapshot,
    dropletRelativeSpeed: Float = 1f,
  ): Int {
    val sw = layout.surfaceWidthPx.coerceAtLeast(1).toFloat()
    val sh = layout.surfaceHeightPx.coerceAtLeast(1).toFloat()
    val photoW = table.geometry.widthPx.coerceAtLeast(64f)
    val t = snapshot.flowTimeSec
    var count = 0
    maxEmittedPointSizePx = 0f

    fun emit(px: Vec2, sizePx: Float, alpha: Float, kind: Float, spriteIndex: Float = -1f) {
      if (count >= MAX_POINTS || alpha <= 0.005f) return
      val clampedSize = sizePx.coerceIn(4f, 160f)
      if (clampedSize > maxEmittedPointSizePx) maxEmittedPointSizePx = clampedSize
      val o = count * POINT_STRIDE_FLOATS
      pointBuffer[o] = (px.x / sw) * 2f - 1f
      pointBuffer[o + 1] = 1f - (px.y / sh) * 2f
      pointBuffer[o + 2] = clampedSize
      pointBuffer[o + 3] = alpha.coerceIn(0f, 1f)
      pointBuffer[o + 4] = kind
      pointBuffer[o + 5] = spriteIndex
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
        emit(p, sz, 0.54f, 0f, i.toFloat())
      }
    }

    // 2. Sparse irregular L4 crystal droplets strictly outside activated perimeter segments
    for (seed in seeds) {
      val q = fract(seed.loopU + t * seed.loopSpeed * dropletRelativeSpeed.coerceAtLeast(0f) * seed.direction)
      val sample = table.sampleClosedLoop(q)
      val side = if (q < 0.5f) PerimeterSide.LEFT else PerimeterSide.RIGHT
      val sideProgress = if (q < 0.5f) q * 2f else (1f - q) * 2f
      val maxProg = if (side == PerimeterSide.LEFT) snapshot.leftProgress else snapshot.rightProgress
      if (sideProgress > maxProg + 0.012f || maxProg <= 0.02f) continue
      val life = fract(t / seed.lifetimeSec + seed.phase / (2f * PI.toFloat()))
      val fadeIn = smoothUnit(life / 0.16f)
      val fadeOut = 1f - smoothUnit((life - 0.72f) / 0.28f)
      val lifeAlpha = fadeIn * fadeOut
      if (lifeAlpha <= 0.015f) continue
      val wobble = 0.82f + 0.22f * sin(t * 1.7f + seed.phase)
      val outPx = photoW * seed.outwardOffsetRatio * wobble + 3.5f
      val pos = sample.positionPx + sample.outwardNormal * outPx
      val sz = (photoW * seed.sizeRatio).coerceAtLeast(6f)
      val alpha = (0.46f + 0.20f * cos(t * 1.8f + seed.phase)).coerceIn(0.18f, 0.70f) * lifeAlpha
      emit(pos, sz, alpha, 0f, seed.spriteIndex)
    }

    // 3. P0-7: Compact KF02 Bottom-Center localized warm gold contact focus + subtle cyan ripple
    if (snapshot.contactIntensity > 0.01f) {
      val cPos = layout.photoBottomCenterPx
      emit(cPos + Vec2(0f, 2.5f), photoW * CONTACT_CYAN_RIPPLE_RATIO, snapshot.contactIntensity * 0.48f, 2f)
      emit(cPos + Vec2(0f, 1.0f), photoW * CONTACT_GOLD_FOCUS_RATIO, snapshot.contactIntensity * 0.95f, 1f)
    }

    // 4. P0-9: Compact KF05 Top-Center convergence warm champagne-gold starburst (clearly softened in KF06!)
    if (snapshot.joinIntensity > 0.01f) {
      val tPos = layout.photoTopCenterPx
      emit(tPos - Vec2(0f, 2.0f), photoW * JOIN_CYAN_RIPPLE_RATIO, snapshot.joinIntensity * 0.42f, 2f)
      emit(tPos - Vec2(0f, 1.0f), photoW * JOIN_GOLD_FOCUS_RATIO, snapshot.joinIntensity * 0.92f, 1f)
    }

    return count
  }

  /**
   * Expands the packed [pointCount] dynamic points into 2-triangle screen-space quads (6 verts/point)
   * so glow sprites NEVER clip to hardware `GL_ALIASED_POINT_SIZE_RANGE` limits.
   * Returns total emitted vertex count (`pointCount * 6`).
   */
  fun buildQuadVertices(pointCount: Int, surfaceWidthPx: Int, surfaceHeightPx: Int): Int {
    val sw = surfaceWidthPx.coerceAtLeast(1).toFloat()
    val sh = surfaceHeightPx.coerceAtLeast(1).toFloat()
    var qOff = 0
    for (i in 0 until pointCount) {
      val pOff = i * POINT_STRIDE_FLOATS
      val cx = pointBuffer[pOff]
      val cy = pointBuffer[pOff + 1]
      val sizePx = pointBuffer[pOff + 2]
      val alpha = pointBuffer[pOff + 3]
      val kind = pointBuffer[pOff + 4]
      val spriteIndex = pointBuffer[pOff + 5]

      val halfNdcX = (sizePx / sw)
      val halfNdcY = (sizePx / sh)

      fun putVert(dx: Float, dy: Float, u: Float, v: Float) {
        quadBuffer[qOff++] = cx + dx
        quadBuffer[qOff++] = cy + dy
        quadBuffer[qOff++] = sizePx
        quadBuffer[qOff++] = alpha
        quadBuffer[qOff++] = kind
        quadBuffer[qOff++] = spriteIndex
        quadBuffer[qOff++] = u
        quadBuffer[qOff++] = v
      }

      // Triangle 1: BL, BR, TL
      putVert(-halfNdcX, -halfNdcY, 0f, 1f)
      putVert(halfNdcX, -halfNdcY, 1f, 1f)
      putVert(-halfNdcX, halfNdcY, 0f, 0f)
      // Triangle 2: BR, TR, TL
      putVert(halfNdcX, -halfNdcY, 1f, 1f)
      putVert(halfNdcX, halfNdcY, 1f, 0f)
      putVert(-halfNdcX, halfNdcY, 0f, 0f)
    }
    return pointCount * 6
  }

  fun rawPointBuffer(): FloatArray = pointBuffer
  fun rawQuadBuffer(): FloatArray = quadBuffer

  fun pointUploadBuffer(pointCount: Int): FloatBuffer {
    val length = pointCount.coerceIn(0, MAX_POINTS) * POINT_STRIDE_FLOATS
    pointUploadBuffer.clear()
    pointUploadBuffer.put(pointBuffer, 0, length)
    pointUploadBuffer.flip()
    return pointUploadBuffer
  }

  fun quadUploadBuffer(vertexCount: Int): FloatBuffer {
    val length = vertexCount.coerceIn(0, MAX_POINTS * 6) * QUAD_STRIDE_FLOATS
    quadUploadBuffer.clear()
    quadUploadBuffer.put(quadBuffer, 0, length)
    quadUploadBuffer.flip()
    return quadUploadBuffer
  }

  private fun seedValue(index: Int, stream: Int): Float {
    val x = sin(index * 12.9898 + stream * 78.233) * 43_758.5453
    return (x - floor(x)).toFloat()
  }

  private fun fract(value: Float): Float = value - floor(value)

  private fun smoothUnit(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
  }

  companion object {
    const val MAX_POINTS = 64
    const val POINT_STRIDE_FLOATS = 6
    const val QUAD_STRIDE_FLOATS = 8
    const val CONTACT_GOLD_FOCUS_RATIO = 0.065f
    const val CONTACT_CYAN_RIPPLE_RATIO = 0.11f
    const val JOIN_GOLD_FOCUS_RATIO = 0.075f
    const val JOIN_CYAN_RIPPLE_RATIO = 0.11f
  }
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
  val material: PerimeterWaterLightMaterial = PerimeterWaterLightMaterial(),
  private val useV2Material: Boolean = false,
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
  private var v2Textures: Tab2PerimeterGpuTextures? = null
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
  private var quadVao: Int = 0
  private var quadVbo: Int = 0
  private var maxAliasedPointSizePx: Float = 64f

  private var cachedPhoto: PhotoFrameGeometry? = null
  private var cachedSurfaceW: Int = -1
  private var cachedSurfaceH: Int = -1
  private var cachedTable: PerimeterArcLengthTable? = null

  var lastDrawStats: PerimeterDrawStats = PerimeterDrawStats()
    private set

  val isV2MaterialLoaded: Boolean get() = v2Textures != null
  val loadedV2TextureIds: List<Int> get() = v2Textures?.allTextureIds ?: emptyList()

  fun init() {
    check(!useV2Material) { "V2 perimeter renderer must be initialized with the APK AssetManager" }
    release()
    initializePrograms()
  }

  fun init(assetManager: AssetManager) {
    check(useV2Material) { "AssetManager initialization is reserved for the V2 perimeter renderer" }
    release()
    try {
      v2Textures = Tab2PerimeterGpuTextures.load(assetManager)
      initializePrograms()
    } catch (t: Throwable) {
      release()
      throw t
    }
  }

  private fun initializePrograms() {
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

    // P0-7: Query real GPU GL_ALIASED_POINT_SIZE_RANGE so glows NEVER silently truncate!
    val range = FloatArray(2)
    GLES30.glGetFloatv(GLES30.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
    maxAliasedPointSizePx = if (range[1].isFinite() && range[1] >= 1f) range[1] else 64f

    val vaos = IntArray(strips.size)
    val vbos = IntArray(strips.size)
    GLES30.glGenVertexArrays(strips.size, vaos, 0)
    GLES30.glGenBuffers(strips.size, vbos, 0)
    for (i in strips.indices) {
      strips[i].vao = vaos[i]
      strips[i].vbo = vbos[i]
    }

    val pVaos = IntArray(2)
    val pVbos = IntArray(2)
    GLES30.glGenVertexArrays(2, pVaos, 0)
    GLES30.glGenBuffers(2, pVbos, 0)
    pointVao = pVaos[0]
    pointVbo = pVbos[0]
    quadVao = pVaos[1]
    quadVbo = pVbos[1]
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointVbo)
    GLES30.glBufferData(
      GLES30.GL_ARRAY_BUFFER,
      PerimeterWaterLightParticles.MAX_POINTS * PerimeterWaterLightParticles.POINT_STRIDE_FLOATS * 4,
      null,
      GLES30.GL_STREAM_DRAW,
    )
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
    GLES30.glBufferData(
      GLES30.GL_ARRAY_BUFFER,
      PerimeterWaterLightParticles.MAX_POINTS * 6 * PerimeterWaterLightParticles.QUAD_STRIDE_FLOATS * 4,
      null,
      GLES30.GL_STREAM_DRAW,
    )
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
  }

  fun draw(
    layout: TaskSceneLayoutSnapshot,
    table: PerimeterArcLengthTable,
    snapshot: TaskEnergySnapshot,
    hdrScale: Float,
    refractionTexId: Int = 0,
    fboWidth: Int = layout.surfaceWidthPx,
    fboHeight: Int = layout.surfaceHeightPx,
    debugOptions: TaskRenderDebugOptions = TaskRenderDebugOptions.Default,
  ) {
    val rProg = ribbonProgram
    if (rProg == null || !debugOptions.isPerimeterEnabled) {
      lastDrawStats = PerimeterDrawStats(
        vertexCount = 0,
        drawCalls = 0,
        shaderLinked = rProg != null,
        vaoIds = strips.map { it.vao },
        vboIds = strips.map { it.vbo },
        maxAliasedPointSizePx = maxAliasedPointSizePx,
      )
      return
    }
    val gpuMaterials = v2Textures
    if (useV2Material && gpuMaterials == null) {
      lastDrawStats = PerimeterDrawStats(shaderLinked = true, usedV2Material = true)
      return
    }
    rebuildStripsIfNeeded(layout, table)

    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    var drawnRibbonVerts = 0
    var drawnRibbonCalls = 0

    val hasRefraction = refractionTexId != 0
    // 1. Draw L2 Emerald Veil -> L1 Icy-Blue Body -> L3 Champagne Gold Filament along unified arc-length strips
    val hasActivePerimeter = snapshot.leftProgress > 0.001f || snapshot.rightProgress > 0.001f || snapshot.coverageComplete
    if (hasActivePerimeter) {
      rProg.use()
      if (hasRefraction) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, refractionTexId)
        rProg.setInt("uTaskRefractionTex", 0)
      }
      rProg.setInt("uHasRefractionTex", if (hasRefraction) 1 else 0)
      rProg.setVec2("uFboSize", fboWidth.coerceAtLeast(1).toFloat(), fboHeight.coerceAtLeast(1).toFloat())
      rProg.setFloat("uRefractionPx", TaskEnergyRibbonRenderer.DEFAULT_REFRACTION_PX)
      rProg.setFloat("uRefractionStrength", TaskEnergyRibbonRenderer.DEFAULT_REFRACTION_STRENGTH)
      rProg.setInt("uTaskVisualDebug", debugOptions.mode.shaderDebugId)
      rProg.setFloat("uFlowTime", snapshot.flowTimeSec)
      rProg.setFloat(
        "uLoopElapsedSec",
        ((snapshot.elapsedMs / 1000.0) - (TaskEnergyController.T_LOOP_START_MS / 1000.0)).coerceAtLeast(0.0).toFloat(),
      )
      rProg.setFloat("uPhotoWidthPx", table.geometry.widthPx)
      // P0-1: Strictly driven from real snapshot.coverageComplete (left>=0.999 && right>=0.999), never stage label!
      rProg.setFloat("uCoverageComplete", if (snapshot.coverageComplete) 1f else 0f)
      rProg.setFloat("uHdrScale", hdrScale)
      rProg.setInt("uDirectDisplayLdr", if (material.directDisplayLdr) 1 else 0)
      rProg.setInt("uUseV2Material", if (gpuMaterials != null) 1 else 0)
      rProg.setInt("uLayerRgbaTex", LAYER_RGBA_TEXTURE_UNIT)
      rProg.setInt("uLayerNormalTex", LAYER_NORMAL_TEXTURE_UNIT)
      rProg.setInt("uLayerFlowTex", LAYER_FLOW_TEXTURE_UNIT)

      for (strip in strips) {
        if (strip.vertexCount <= 0 || strip.vao == 0) continue
        val layerEnabled = when (strip.layer) {
          PerimeterRibbonLayer.L1_ICE_BLUE_BODY -> debugOptions.showL1
          PerimeterRibbonLayer.L2_EMERALD_VEIL -> debugOptions.showL2
          PerimeterRibbonLayer.L3_GOLD_FILAMENT -> debugOptions.showL3
        }
        if (!layerEnabled) continue

        val progress = if (strip.side == PerimeterSide.LEFT) snapshot.leftProgress else snapshot.rightProgress
        if (progress <= 0.001f && !snapshot.coverageComplete) continue
        val sideSign = if (strip.side == PerimeterSide.LEFT) -1f else 1f

        rProg.setInt("uLayerKind", strip.layer.shaderLayerId)
        rProg.setFloat("uLayerAlphaScale", material.alphaScaleFor(strip.layer))
        rProg.setFloat("uLayerAlphaCapOverride", material.alphaCapOverrideFor(strip.layer))
        if (gpuMaterials != null) {
          val textures = gpuMaterials.layers.getValue(strip.layer)
          GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
          GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures.rgba)
          GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
          GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures.normal)
          GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
          GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures.flow)
          rProg.setFloat("uLayerSpeed", gpuMaterials.relativeSpeeds.getValue(strip.layer))
        }
        rProg.setFloat("uSideSign", sideSign)
        rProg.setFloat("uRevealProgress", progress)

        GLES30.glBindVertexArray(strip.vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, strip.vertexCount)
        drawnRibbonVerts += strip.vertexCount
        drawnRibbonCalls++
      }
      GLES30.glBindVertexArray(0)
      if (gpuMaterials != null) {
        for (unit in intArrayOf(GLES30.GL_TEXTURE1, GLES30.GL_TEXTURE2, GLES30.GL_TEXTURE3)) {
          GLES30.glActiveTexture(unit)
          GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
      }
      if (hasRefraction) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
      }
    }

    // 2. Draw L4 sparse crystal droplets + KF02 bottom contact starburst + KF05/KF06 top-join glint
    var sparkleCount = 0
    var sparkleCalls = 0
    var usedQuadFallback = false
    val sProg = sparkleProgram
    if (debugOptions.showL4 && sProg != null && pointVao != 0 && pointVbo != 0) {
      sparkleCount = particles.buildDynamicPoints(
        table = table,
        layout = layout,
        snapshot = snapshot,
        dropletRelativeSpeed = gpuMaterials?.dropletRelativeSpeed ?: 1f,
      )
      if (sparkleCount > 0) {
        val useQuad = particles.maxEmittedPointSizePx > maxAliasedPointSizePx - 1f
        usedQuadFallback = useQuad
        sProg.use()
        sProg.setInt("uUseQuad", if (useQuad) 1 else 0)
        sProg.setFloat("uHdrScale", hdrScale)
        sProg.setInt("uTaskVisualDebug", debugOptions.mode.shaderDebugId)
        sProg.setInt("uDirectDisplayLdr", if (material.directDisplayLdr) 1 else 0)
        sProg.setInt("uUseDropletAtlas", if (gpuMaterials != null) 1 else 0)
        if (gpuMaterials != null) {
          GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
          GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, gpuMaterials.dropletAtlas)
          sProg.setInt("uDropletAtlas", DROPLET_ATLAS_TEXTURE_UNIT)
        }

        if (useQuad && quadVao != 0 && quadVbo != 0) {
          val quadVerts = particles.buildQuadVertices(sparkleCount, layout.surfaceWidthPx, layout.surfaceHeightPx)
          val strideBytes = PerimeterWaterLightParticles.QUAD_STRIDE_FLOATS * 4
          GLES30.glBindVertexArray(quadVao)
          GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
          GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            quadVerts * strideBytes,
            particles.quadUploadBuffer(quadVerts),
          )
          GLES30.glEnableVertexAttribArray(0)
          GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, strideBytes, 0)
          GLES30.glEnableVertexAttribArray(1)
          GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, strideBytes, 8)
          GLES30.glEnableVertexAttribArray(2)
          GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, strideBytes, 12)
          GLES30.glEnableVertexAttribArray(3)
          GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, strideBytes, 16)
          GLES30.glEnableVertexAttribArray(4)
          GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, strideBytes, 20)
          GLES30.glEnableVertexAttribArray(5)
          GLES30.glVertexAttribPointer(5, 2, GLES30.GL_FLOAT, false, strideBytes, 24)
          GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, quadVerts)
          GLES30.glBindVertexArray(0)
          sparkleCalls = 1
        } else {
          val strideBytes = PerimeterWaterLightParticles.POINT_STRIDE_FLOATS * 4
          GLES30.glBindVertexArray(pointVao)
          GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointVbo)
          GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            sparkleCount * strideBytes,
            particles.pointUploadBuffer(sparkleCount),
          )
          GLES30.glEnableVertexAttribArray(0)
          GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, strideBytes, 0)
          GLES30.glEnableVertexAttribArray(1)
          GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, strideBytes, 8)
          GLES30.glEnableVertexAttribArray(2)
          GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, strideBytes, 12)
          GLES30.glEnableVertexAttribArray(3)
          GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, strideBytes, 16)
          GLES30.glEnableVertexAttribArray(4)
          GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, strideBytes, 20)
          GLES30.glDisableVertexAttribArray(5)
          GLES30.glVertexAttrib2f(5, 0.5f, 0.5f)
          GLES30.glDrawArrays(GLES30.GL_POINTS, 0, sparkleCount)
          GLES30.glBindVertexArray(0)
          sparkleCalls = 1
        }
        if (gpuMaterials != null) {
          GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
          GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
      }
    }

    lastDrawStats = PerimeterDrawStats(
      vertexCount = drawnRibbonVerts,
      drawCalls = drawnRibbonCalls,
      shaderLinked = true,
      vaoIds = strips.map { it.vao },
      vboIds = strips.map { it.vbo },
      sparkleCount = sparkleCount,
      sparkleDrawCalls = sparkleCalls,
      usedQuadFallback = usedQuadFallback,
      maxAliasedPointSizePx = maxAliasedPointSizePx,
      refractionEnabled = hasRefraction,
      refractionTexId = if (hasRefraction) refractionTexId else 0,
      usedV2Material = gpuMaterials != null,
      boundMaterialTextureIds = gpuMaterials?.let { bundle ->
        bundle.layers.values.flatMap { listOf(it.rgba, it.normal, it.flow) } + bundle.dropletAtlas
      } ?: emptyList(),
    )
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
        widthProfile = material.widthProfile,
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
      GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, strideBytes, 8)
      GLES30.glEnableVertexAttribArray(2)
      GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, strideBytes, 20)
    }
    GLES30.glBindVertexArray(0)
  }

  fun release() {
    v2Textures?.releaseOnGlThread()
    v2Textures = null
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
    val extraVaos = intArrayOf(pointVao, quadVao).filter { it != 0 }.toIntArray()
    val extraVbos = intArrayOf(pointVbo, quadVbo).filter { it != 0 }.toIntArray()
    if (extraVaos.isNotEmpty()) GLES30.glDeleteVertexArrays(extraVaos.size, extraVaos, 0)
    if (extraVbos.isNotEmpty()) GLES30.glDeleteBuffers(extraVbos.size, extraVbos, 0)
    pointVao = 0
    pointVbo = 0
    quadVao = 0
    quadVbo = 0
    cachedPhoto = null
    cachedTable = null
    lastDrawStats = PerimeterDrawStats()
  }

  companion object {
    const val LAYER_RGBA_TEXTURE_UNIT = 1
    const val LAYER_NORMAL_TEXTURE_UNIT = 2
    const val LAYER_FLOW_TEXTURE_UNIT = 3
    const val DROPLET_ATLAS_TEXTURE_UNIT = 4
  }
}
