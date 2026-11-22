package com.example.orb.scene

import android.opengl.GLES30
import android.opengl.Matrix
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.RenderDebugMode
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.geometry.OrbGeometryBuilder.RibbonSurfaceProfile
import com.example.orb.geometry.Vec3
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import com.example.orb.gl.RibbonOpticsShaders
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Architectural role separation between the 5 frozen R06 Resident organic ribbons
 * and optional ultra-subtle Secondary Optical inner accents.
 */
enum class RibbonRole {
  RESIDENT,
  SECONDARY_OPTICAL,
}

/**
 * Dominant spatial depth relationship relative to the central glass core.
 * Note: Every ribbon is a true 3D curving surface that weaves in/out of depth;
 * this property governs back-to-front transparent alpha draw order.
 */
enum class DominantDepthLayer(val sortOrder: Int) {
  BACK(0),
  FRONT(1),
}

data class RibbonDescriptor(
  val id: String,
  val residentCode: String, // Frozen R06 identity: G01, G02, A01, A02, C01 (or SEC_*)
  val role: RibbonRole,
  val dominantLayer: DominantDepthLayer,
  val renderOrder: Int, // Explicit back-to-front transparent ordering
  val style: Int, // 0 = Cyan Liquid Glass (C01), 1 = Green Aquatic Silk (G01/G02), 2 = Champagne Gold Filament (A01/A02)
  val closed: Boolean,
  val controlPoints: List<Vec3>,
  val halfWidth: Float,
  val surfaceProfile: RibbonSurfaceProfile,
  val periodMs: Int, // Frozen R06 independent period (G01=6100, G02=5300, A01=3700, A02=2900, C01=4400)
  val spatialSwayAxis: Vec3,
  val spatialSwayDeg: Float, // Gentle organic spatial swirling drift (NO rigid planetary revolution!)
  val secondarySwayDeg: Float,
  val waveAmp: Float,        // Flexible spine bending amplitude
  val waveFreq: Float,       // Traveling wave cycles along arc-length
  val twistWaveAmpRad: Float,// Progressive dynamic local twist roll (radians)
  val widthBreathAmp: Float, // Continuous local width breathing ratio
  val phase: Float,          // Independent initial phase offset (radians)
  val colorA: FloatArray,
  val colorB: FloatArray,
  val flowSpeed: Float,      // Internal material flow direction & rate
  val pulseOffset: Float,
  val opacityScale: Float,
  val additiveBlend: Boolean,
)

/**
 * Renders the 5 frozen R06 organic Resident ribbons (`C01`, `G01`, `G02`, `A01`, `A02`)
 * reverse-engineered directly from the high-fidelity master (`278095.png`):
 * - Irregular, asymmetric 3D hand-sculpted Catmull-Rom surfaces (ZERO concentric ellipses!)
 * - Continuous `WidthProfile(s)`, asymmetric blade contours, and non-uniform 3D twist turnovers
 * - Three layered motions per Resident: gentle spatial drift + progressive flexible bending/twist + internal fluid flow
 * - Independent frozen R06 periods (6100ms / 5300ms / 3700ms / 2900ms / 4400ms) & independent phases
 * - Spherical-front-surface back/front partitioning with back-to-front alpha draw sorting & genuine FBO glass refraction
 */
class AIOrbOrbitRibbons {
  private var program: GlShaderProgram? = null
  private val descriptors: List<RibbonDescriptor> = buildRibbonDescriptors()
  private val meshes = ArrayList<GlMesh>(descriptors.size)
  private var currentSegments: Int = -1
  private val modelMat = FloatArray(16)

  val maxRibbons: Int get() = descriptors.size
  val allDescriptors: List<RibbonDescriptor> get() = descriptors

  fun init(segments: Int) {
    release()
    program = GlShaderProgram(
      RibbonOpticsShaders.RIBBON_VERT,
      RibbonOpticsShaders.RIBBON_FRAG,
      "AIOrbOrbitRibbons",
    )
    rebuildMeshes(segments)
  }

  fun ensureQuality(segments: Int) {
    if (segments != currentSegments || meshes.isEmpty()) {
      rebuildMeshes(segments)
    }
  }

  private fun rebuildMeshes(segments: Int) {
    meshes.forEach { it.release() }
    meshes.clear()
    for (desc in descriptors) {
      val crossSlices = when (desc.style) {
        1 -> 8 // Smooth curved cross-section for broad translucent botanical kelp leaves
        0 -> 7 // Curved optical water sheet for C01
        else -> 5 // Slender sculpted gold filament + soft halo veil
      }
      meshes.add(
        OrbGeometryBuilder.createRibbonMesh(
          controlPoints = desc.controlPoints,
          segments = segments,
          closed = desc.closed,
          halfWidth = desc.halfWidth,
          twistTurns = 0f,
          taperEnds = !desc.closed,
          crossSlices = crossSlices,
          surfaceProfile = desc.surfaceProfile,
        )
      )
    }
    currentSegments = segments
  }

  /**
   * Computes gentle, non-rigid spatial swirling drift around the frozen master sculpture.
   * Deliberately avoids continuous 360° planetary ring revolution so ribbons stay organically
   * interlaced around the glass core while breathing and swaying smoothly in 3D.
   */
  fun computeRibbonModelMatrix(
    index: Int,
    motion: AIOrbMotionSnapshot,
    config: AIOrbConfig,
    outMatrix: FloatArray,
  ) {
    val d = descriptors[index]
    val isRefLocked = config.debugMode == RenderDebugMode.REFERENCE_LOCKED_FRAME
    val cycle = if (isRefLocked) d.phase else AIOrbMotionController.residentCyclePhase(motion.ribbonTimeSec, d.periodMs, d.phase)
    Matrix.setIdentityM(outMatrix, 0)

    val ribbonHugScale = 0.87f
    val radialScale = if (isRefLocked) {
      (0.25f + 0.75f * config.coreScale) * ribbonHugScale
    } else {
      (0.25f + 0.75f * config.coreScale) * ribbonHugScale *
        (1.0f + 0.018f * sin(cycle * 0.5f + d.phase * 0.7f))
    }
    Matrix.scaleM(outMatrix, 0, radialScale, radialScale, radialScale)

    if (!isRefLocked) {
      // Gentle multi-harmonic 3D spatial precession (bounded smoothly — NEVER spins like a planetary ring!)
      val primarySway = sin(cycle * 0.5f) * d.spatialSwayDeg +
        cos(cycle * 0.31f + d.phase) * (d.spatialSwayDeg * 0.35f)
      val rollSway = cos(cycle * 0.5f + d.phase * 1.3f) * d.secondarySwayDeg
      val pitchSway = sin(cycle * 0.38f - d.phase * 0.9f) * (d.secondarySwayDeg * 0.75f)

      val axis = d.spatialSwayAxis
      Matrix.rotateM(outMatrix, 0, primarySway, axis.x, axis.y, axis.z)
      Matrix.rotateM(outMatrix, 0, rollSway, 0f, 0f, 1f)
      Matrix.rotateM(outMatrix, 0, pitchSway, 1f, 0f, 0f)
    }
  }

  fun evaluateWorldPoint(index: Int, u: Float, motion: AIOrbMotionSnapshot, config: AIOrbConfig): Vec3 {
    val d = descriptors[index]
    val spine = OrbGeometryBuilder.sampleCatmullRom(d.controlPoints, u, d.closed)
    val cycle = AIOrbMotionController.residentCyclePhase(motion.ribbonTimeSec, d.periodMs, d.phase)
    val waveArg = u * (2.0 * PI).toFloat() * d.waveFreq - cycle
    val metrics = OrbGeometryBuilder.evaluateRibbonProfileAt(
      u = u,
      halfWidth = d.halfWidth,
      closed = d.closed,
      taperEnds = !d.closed,
      profile = d.surfaceProfile,
    )
    val waveR = cos(waveArg * 0.5f + d.phase) * (d.waveAmp * 0.55f) * metrics.flexWeight
    val radial = spine.normalized()
    val local = spine + radial * waveR

    computeRibbonModelMatrix(index, motion, config, modelMat)
    val inVec = floatArrayOf(local.x, local.y, local.z, 1f)
    val outVec = FloatArray(4)
    Matrix.multiplyMV(outVec, 0, modelMat, 0, inVec, 0)
    return Vec3(outVec[0], outVec[1], outVec[2])
  }

  fun drawPass(
    clipPass: Int,
    orbCenterViewPos: FloatArray,
    scaledCoreRadius: Float,
    viewMatrix: FloatArray,
    projMatrix: FloatArray,
    cameraPos: FloatArray,
    motion: AIOrbMotionSnapshot,
    hdrScale: Float,
    config: AIOrbConfig,
  ) {
    val prog = program ?: return
    val activeCount = config.ribbonCount.coerceIn(1, meshes.size)

    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glEnable(GLES30.GL_BLEND)

    prog.use()
    prog.setMat4("uView", viewMatrix)
    prog.setMat4("uProj", projMatrix)
    prog.setVec3("uCameraPos", cameraPos)
    prog.setFloat("uRibbonTime", motion.ribbonTimeSec)
    prog.setFloat("uRibbonWidth", config.ribbonWidth)
    prog.setFloat("uRibbonTransparency", config.ribbonTransparency)
    prog.setFloat("uEnergyIntensity", config.energyIntensity)
    prog.setFloat("uOverallIntensity", config.overallIntensity)
    prog.setFloat("uHdrScale", hdrScale)
    prog.setInt("uClipPass", clipPass)
    prog.setVec3("uOrbCenterViewPos", orbCenterViewPos[0], orbCenterViewPos[1], orbCenterViewPos[2])
    prog.setFloat("uCoreShellRadius", scaledCoreRadius)
    prog.setInt("uDepthDebug", if (config.debugMode == RenderDebugMode.DEPTH_DEBUG) 1 else 0)

    // Strict back-to-front transparent alpha sorting among active ribbons
    val drawIndices = (0 until activeCount).sortedBy { descriptors[it].renderOrder }
    for (i in drawIndices) {
      val d = descriptors[i]
      computeRibbonModelMatrix(i, motion, config, modelMat)
      val cycle = AIOrbMotionController.residentCyclePhase(motion.ribbonTimeSec, d.periodMs, d.phase)

      if (d.additiveBlend) {
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
      } else {
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
      }

      prog.setMat4("uModel", modelMat)
      prog.setInt("uRibbonStyle", d.style)
      prog.setFloat("uCyclePhase", cycle)
      prog.setFloat("uWaveAmp", d.waveAmp)
      prog.setFloat("uWaveFreq", d.waveFreq)
      prog.setFloat("uTwistWaveAmp", d.twistWaveAmpRad)
      prog.setFloat("uWidthBreathAmp", d.widthBreathAmp)
      prog.setFloat("uRibbonPhase", d.phase)
      prog.setFloat("uIsOpen", if (d.closed) 0f else 1f)
      prog.setFloat("uOpacityScale", d.opacityScale)
      prog.setVec3("uColorA", d.colorA)
      prog.setVec3("uColorB", d.colorB)
      prog.setFloat("uFlowSpeed", d.flowSpeed)
      prog.setFloat("uPulseOffset", d.pulseOffset)

      meshes[i].draw()
    }

    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    GLES30.glDepthMask(true)
  }

  fun release() {
    program?.release()
    program = null
    meshes.forEach { it.release() }
    meshes.clear()
    currentSegments = -1
  }

  companion object {
    const val PERIOD_G01_MS = 6100
    const val PERIOD_G02_MS = 5300
    const val PERIOD_A01_MS = 3700
    const val PERIOD_A02_MS = 2900
    const val PERIOD_C01_MS = 4400

    /**
     * Five frozen R06 organic Resident ribbons calibrated directly against the sole frozen
     * high-fidelity master `____________910d1531.png` (world frame: glass core radius ~0.80 at
     * origin, camera on +Z):
     *
     * - Index 0 (`C01`, FRONT, 4400ms): Crystal-clear multi-walled ice-blue liquid-glass band
     *   hugging top-left crown -> broad sculptural far-left C-arc (8:30 flare) -> crisp diagonal
     *   slash across lower-front -> translucent lower-right cyan under-arc (5:30 sparkle).
     * - Index 1 (`G01`, BACK, 6100ms): Crisp sunlit warm olive-emerald kelp blade arching boldly
     *   over upper-right (12:30-2:30) -> twisting cleanly down right flank behind A02's golden loop.
     * - Index 2 (`A02`, FRONT, 2900ms): Broad sculptural molten-amber & champagne-gold refractive
     *   glass ribbon emerging from 10:30 upper-left flare -> weaving across lower-front (5:00) ->
     *   iconic thick glowing golden crystal loop at 3:30 -> tapering behind upper-right rim.
     * - Index 3 (`A01`, BACK, 3700ms): Lush translucent golden glass crown & outer-right ambient
     *   loop enriching 10:00-12:00 top-left double-arch and 2:00-4:30 outer golden halo volume.
     * - Index 4 (`G02`, FRONT, 5300ms): Iconic broad folded foreground warm olive-kelp leaf
     *   wrapping left cheek -> lush translucent lower-left blade -> crisp 3D turnover pinch ->
     *   curling tapered bottom tendril.
     */
    fun buildRibbonDescriptors(): List<RibbonDescriptor> {
      // --- C01: Ice-Blue Crystal Liquid-Glass Water Band (FRONT, 4400ms, OPEN sculpted arc — P0-4) ---
      // Recalibrated to eliminate the bottom-right 5:00 V-kink and closed-ring rigidity:
      // emerges gracefully behind upper-right crown -> sweeps top-left -> iconic far-left 8:30 C-arc
      // -> crisp diagonal lower-front slash -> wide sweeping 5:30 translucent under-arc -> tapers cleanly
      // behind lower-left with smooth monotonic curvature (ZERO sharp turnaround corner!).
      val c01Points = listOf(
        Vec3(0.76f, 0.58f, -0.44f),   // u=0.00 tapered start emerging behind upper-right flank
        Vec3(0.28f, 1.02f, -0.18f),   // u=0.10 upper-right cyan crown glass arc
        Vec3(-0.36f, 1.04f, 0.06f),   // u=0.20 top-left crown overlapping A01/A02
        Vec3(-0.96f, 0.72f, 0.28f),   // u=0.30 upper-left widening water arch
        Vec3(-1.26f, 0.22f, 0.52f),   // u=0.40 far-left sculptural broad cyan glass C-arc
        Vec3(-1.04f, -0.24f, 0.80f),  // u=0.50 8:30 iconic bright ice-cyan flare bend!
        Vec3(-0.32f, -0.44f, 0.96f),  // u=0.60 razor-clean diagonal slash across lower-front of orb
        Vec3(0.38f, -0.62f, 0.82f),   // u=0.70 lower-right front sweep gliding sleekly past 5:00
        Vec3(0.66f, -0.80f, 0.46f),   // u=0.80 5:30 tightly hugged translucent cyan under-arc
        Vec3(0.50f, -0.98f, 0.12f),   // u=0.88 broad rounded lower-right sweeping turn (NO kink!)
        Vec3(-0.02f, -1.04f, -0.14f), // u=0.94 smooth continuous bottom curve hugging lower rim
        Vec3(-0.56f, -0.94f, -0.30f), // u=1.00 delicate vanishing translucent tail behind lower-left
      )
      val c01Profile = RibbonSurfaceProfile(
        widthControlPoints = floatArrayOf(0.14f, 0.68f, 0.90f, 1.14f, 1.40f, 1.20f, 0.84f, 0.88f, 0.98f, 0.86f, 0.56f, 0.10f),
        leftAsymmetryPoints = floatArrayOf(0.96f, 0.94f, 1.06f, 1.18f, 1.22f, 1.10f, 0.96f, 0.94f, 1.06f, 1.04f, 1.02f, 0.96f),
        rightAsymmetryPoints = floatArrayOf(1.04f, 1.06f, 0.94f, 0.86f, 0.84f, 0.94f, 1.06f, 1.08f, 0.96f, 0.96f, 0.98f, 1.04f),
        twistProfileRad = floatArrayOf(0.10f, 0.28f, 0.52f, 0.28f, -0.14f, 0.18f, 0.64f, 1.14f, 1.48f, 1.72f, 1.90f, 2.04f),
        camberFactor = 0.26f,
        edgeWaveAmp = 0.045f,
        edgeWaveFreq = 2.0f,
        edgeWavePhase = 0.4f,
        tipFlexWeight = 0.62f,
      )

      // --- G01: Warm Sunlit Olive-Emerald Aquatic Kelp Band (BACK, 6100ms, OPEN botanical frond) ---
      val g01Points = listOf(
        Vec3(-0.22f, 1.18f, -0.60f),  // slender tip emerging behind upper-left crown
        Vec3(0.28f, 1.30f, -0.34f),   // arching boldly high over 1:00 upper-right
        Vec3(0.78f, 1.08f, -0.14f),   // broad sunlit warm olive-emerald kelp blade
        Vec3(1.14f, 0.62f, -0.08f),   // crisp outer-right frond clearly framed behind orb
        Vec3(1.22f, 0.12f, -0.22f),   // graceful 3D turnover waist right behind golden loop
        Vec3(1.04f, -0.32f, -0.34f),  // re-flaring warm olive-green translucent ribbon
        Vec3(0.70f, -0.68f, -0.42f),  // tucking smoothly inward behind lower-right
        Vec3(0.28f, -0.92f, -0.54f),  // tapering translucent kelp tail
        Vec3(-0.14f, -1.04f, -0.68f), // delicate vanishing tip behind bottom
      )
      val g01Profile = RibbonSurfaceProfile(
        widthControlPoints = floatArrayOf(0.10f, 0.66f, 1.22f, 1.38f, 0.56f, 1.12f, 0.78f, 0.36f, 0.06f),
        leftAsymmetryPoints = floatArrayOf(0.86f, 0.76f, 0.72f, 0.78f, 1.04f, 0.80f, 0.88f, 0.94f, 0.90f),
        rightAsymmetryPoints = floatArrayOf(1.10f, 1.26f, 1.32f, 1.24f, 0.96f, 1.22f, 1.14f, 1.06f, 1.00f),
        twistProfileRad = floatArrayOf(-0.22f, 0.18f, 0.46f, 0.92f, 2.08f, 2.72f, 3.12f, 3.45f, 3.68f),
        camberFactor = 0.25f,
        edgeWaveAmp = 0.10f,
        edgeWaveFreq = 2.4f,
        edgeWavePhase = 1.2f,
        tipFlexWeight = 0.94f,
      )

      // --- A02: Broad Sculptural Molten-Amber & Champagne-Gold Glass Band (FRONT, 2900ms, P0-3 open organic sweep) ---
      // Emerges boldly at 10:30 crown -> weaves down left cheek & across lower front -> flares wide at 3:30-4:30 ->
      // tapers openly behind upper-right rim (eliminates mechanical top loop-back / closed oval!).
      val a02Points = listOf(
        Vec3(-0.76f, 0.86f, 0.24f),   // u=0.00 tapered open entry at 10:30 sunlit golden shoulder
        Vec3(-1.02f, 0.32f, 0.56f),   // u=0.125 sweeping down left cheek interlaced with C01
        Vec3(-0.56f, -0.24f, 0.86f),  // u=0.250 gliding diagonally across lower-left front of orb
        Vec3(0.10f, -0.50f, 0.90f),   // u=0.375 luminous lower-center golden crossing
        Vec3(0.74f, -0.52f, 0.72f),   // u=0.500 5:00 radiant molten-amber rim bend
        Vec3(1.18f, -0.24f, 0.44f),   // u=0.625 flaring into iconic broad lower-right golden sculpture
        Vec3(1.26f, 0.20f, 0.16f),    // u=0.750 3:00 strongest warm amber-gold outer sculptural crest
        Vec3(1.00f, 0.64f, -0.18f),   // u=0.875 sweeping warmly back behind upper-right flank
        Vec3(0.64f, 0.90f, -0.46f),   // u=1.000 delicate open vanishing tail (no loop-back to 10:30!)
      )
      val a02Profile = RibbonSurfaceProfile(
        widthControlPoints = floatArrayOf(0.16f, 0.96f, 0.92f, 0.86f, 1.12f, 1.40f, 1.32f, 0.78f, 0.10f),
        leftAsymmetryPoints = floatArrayOf(0.96f, 1.10f, 0.94f, 0.92f, 1.06f, 1.18f, 1.12f, 0.96f, 1.00f),
        rightAsymmetryPoints = floatArrayOf(1.04f, 0.90f, 1.06f, 1.08f, 0.94f, 0.84f, 0.90f, 1.04f, 1.00f),
        twistProfileRad = floatArrayOf(0.18f, 0.64f, 0.38f, 0.08f, -0.32f, -0.54f, -0.18f, 0.34f, 0.72f),
        camberFactor = 0.22f,
        edgeWaveAmp = 0.048f,
        edgeWaveFreq = 2.6f,
        edgeWavePhase = 2.6f,
        tipFlexWeight = 0.78f,
      )

      // --- A01: Asymmetric Translucent Golden Glass Crown & Outer-Right Veil (BACK, 3700ms, P0-3 open crown wrap) ---
      // Preserves sunlit 10:30-12:30 crown glow & broad 2:30-4:30 right outer flare while staying
      // wide-open and asymmetric relative to A02 (never closes into a symmetric oval ring!).
      val a01Points = listOf(
        Vec3(-0.92f, 0.64f, -0.34f),  // u=0.00 delicate open start behind 10:00 upper-left
        Vec3(-0.56f, 1.04f, -0.14f),  // u=0.125 rising warmly across 11:00 top-left golden crown
        Vec3(0.02f, 1.04f, -0.24f),   // u=0.250 closely hugged 12:00 crown crest (organic radial dip)
        Vec3(0.62f, 0.96f, -0.30f),   // u=0.375 arching softly behind upper-right kelp frond
        Vec3(1.12f, 0.58f, -0.18f),   // u=0.500 opening outward on 2:00 right shoulder
        Vec3(1.32f, 0.04f, -0.06f),   // u=0.625 widest outer-right amber glass sculptural flare
        Vec3(1.18f, -0.48f, -0.14f),  // u=0.750 sweeping down outer-right flank behind A02
        Vec3(0.80f, -0.82f, -0.36f),  // u=0.875 curving softly inward behind 5:00 rim
        Vec3(0.36f, -0.92f, -0.52f),  // u=1.000 gossamer open golden tail fading behind lower-right
      )
      val a01Profile = RibbonSurfaceProfile(
        widthControlPoints = floatArrayOf(0.12f, 0.94f, 1.18f, 0.88f, 1.02f, 1.32f, 1.06f, 0.62f, 0.08f),
        leftAsymmetryPoints = floatArrayOf(1.00f, 1.10f, 1.14f, 0.94f, 0.92f, 1.12f, 1.06f, 0.96f, 1.00f),
        rightAsymmetryPoints = floatArrayOf(1.00f, 0.90f, 0.86f, 1.06f, 1.08f, 0.88f, 0.94f, 1.04f, 1.00f),
        twistProfileRad = floatArrayOf(-0.20f, 0.28f, 0.66f, 0.88f, 0.44f, 0.04f, -0.36f, -0.64f, -0.82f),
        camberFactor = 0.20f,
        edgeWaveAmp = 0.045f,
        edgeWaveFreq = 2.2f,
        edgeWavePhase = 4.1f,
        tipFlexWeight = 0.74f,
      )

      // --- G02: Iconic Broad Folded Foreground Warm Olive-Kelp Leaf (FRONT, 5300ms) ---
      val g02Points = listOf(
        Vec3(-0.92f, 0.48f, -0.36f),  // emerging cleanly from behind upper-left flank
        Vec3(-1.12f, 0.02f, 0.12f),   // curling forward tightly around left cheek of orb
        Vec3(-1.08f, -0.46f, 0.48f),  // swelling into broad translucent lower-left kelp leaf
        Vec3(-0.78f, -0.82f, 0.66f),  // widest lush folded foreground olive-emerald blade
        Vec3(-0.48f, -1.00f, 0.50f),  // iconic crisp 3D turnover pinch from master ____________910d1531.png!
        Vec3(-0.32f, -1.20f, 0.30f),  // unfurling downward into sunlit curving secondary leaf lobe
        Vec3(-0.04f, -1.32f, 0.14f),  // sweeping smoothly right-downward under orb
        Vec3(0.26f, -1.36f, -0.06f),  // slender undulating sunlit kelp tendril
        Vec3(0.48f, -1.26f, -0.24f),  // delicate tapered vanishing tip
      )
      val g02Profile = RibbonSurfaceProfile(
        widthControlPoints = floatArrayOf(0.10f, 0.72f, 1.28f, 1.46f, 0.48f, 1.04f, 0.66f, 0.30f, 0.06f),
        leftAsymmetryPoints = floatArrayOf(0.90f, 1.20f, 1.30f, 1.32f, 0.94f, 1.22f, 1.12f, 1.02f, 0.95f),
        rightAsymmetryPoints = floatArrayOf(1.06f, 0.78f, 0.70f, 0.72f, 1.06f, 0.80f, 0.88f, 0.96f, 1.00f),
        twistProfileRad = floatArrayOf(0.16f, -0.10f, -0.24f, 0.20f, 1.88f, 2.86f, 3.42f, 3.96f, 4.32f),
        camberFactor = 0.28f,
        edgeWaveAmp = 0.11f,
        edgeWaveFreq = 2.6f,
        edgeWavePhase = 3.5f,
        tipFlexWeight = 0.98f,
      )

      fun offsetControlPoints(src: List<Vec3>, dx: Float, dy: Float, dz: Float): List<Vec3> =
        src.map { Vec3(it.x + dx, it.y + dy, it.z + dz) }

      return listOf(
        // ==================== 5 FROZEN R06 RESIDENT IDENTITIES ====================
        // 0: C01 — Crystal-Clear Multi-Walled Ice-Blue Liquid-Glass Band (FRONT, 4400ms)
        RibbonDescriptor(
          id = "resident_C01_ice_cyan_water_band",
          residentCode = "C01",
          role = RibbonRole.RESIDENT,
          dominantLayer = DominantDepthLayer.FRONT,
          renderOrder = 2,
          style = 0,
          closed = false,
          controlPoints = c01Points,
          halfWidth = 0.138f,
          surfaceProfile = c01Profile,
          periodMs = PERIOD_C01_MS,
          spatialSwayAxis = Vec3(0.18f, 0.92f, 0.34f).normalized(),
          spatialSwayDeg = 6.2f,
          secondarySwayDeg = 3.6f,
          waveAmp = 0.056f,
          waveFreq = 2.0f,
          twistWaveAmpRad = 0.34f,
          widthBreathAmp = 0.08f,
          phase = 0.35f,
          colorA = floatArrayOf(0.04f, 0.62f, 1.48f),  // Pure vivid sapphire-cyan (ACES + sRGB calibrated)
          colorB = floatArrayOf(0.28f, 1.18f, 1.62f),  // Crystal bright ice-cyan
          flowSpeed = 1.15f,
          pulseOffset = 0.14f,
          opacityScale = 1.00f,
          additiveBlend = false,
        ),
        // 1: G01 — Crisp Sunlit Warm Olive-Emerald Kelp Blade (BACK, 6100ms)
        RibbonDescriptor(
          id = "resident_G01_emerald_kelp_back",
          residentCode = "G01",
          role = RibbonRole.RESIDENT,
          dominantLayer = DominantDepthLayer.BACK,
          renderOrder = 0,
          style = 1,
          closed = false,
          controlPoints = g01Points,
          halfWidth = 0.178f,
          surfaceProfile = g01Profile,
          periodMs = PERIOD_G01_MS,
          spatialSwayAxis = Vec3(-0.22f, 0.90f, -0.36f).normalized(),
          spatialSwayDeg = 6.8f,
          secondarySwayDeg = 4.2f,
          waveAmp = 0.088f,
          waveFreq = 1.85f,
          twistWaveAmpRad = 0.52f,
          widthBreathAmp = 0.10f,
          phase = 1.65f,
          colorA = floatArrayOf(0.040f, 0.155f, 0.008f), // Rich deep moss-olive (near-zero blue!)
          colorB = floatArrayOf(0.320f, 0.520f, 0.028f), // Warm sunlit Vallisneria olive-emerald
          flowSpeed = 0.80f,
          pulseOffset = 0.42f,
          opacityScale = 0.96f,
          additiveBlend = false,
        ),
        // 2: A02 — Broad Sculptural Molten-Amber & Champagne-Gold Glass Band (FRONT, 2900ms)
        RibbonDescriptor(
          id = "resident_A02_warm_gold_front",
          residentCode = "A02",
          role = RibbonRole.RESIDENT,
          dominantLayer = DominantDepthLayer.FRONT,
          renderOrder = 3,
          style = 2,
          closed = false,
          controlPoints = a02Points,
          halfWidth = 0.118f,
          surfaceProfile = a02Profile,
          periodMs = PERIOD_A02_MS,
          spatialSwayAxis = Vec3(0.24f, 0.91f, -0.32f).normalized(),
          spatialSwayDeg = 5.8f,
          secondarySwayDeg = 3.4f,
          waveAmp = 0.048f,
          waveFreq = 2.4f,
          twistWaveAmpRad = 0.28f,
          widthBreathAmp = 0.07f,
          phase = 2.85f,
          colorA = floatArrayOf(1.58f, 0.46f, 0.015f), // Rich molten amber-orange (zero grey-beige!)
          colorB = floatArrayOf(2.15f, 1.24f, 0.180f), // Radiant sunlit champagne-gold
          flowSpeed = 1.32f,
          pulseOffset = 0.64f,
          opacityScale = 1.00f,
          additiveBlend = false,
        ),
        // 3: A01 — Lush Translucent Golden Glass Crown & Outer-Right Veil (BACK, 3700ms)
        RibbonDescriptor(
          id = "resident_A01_warm_gold_back",
          residentCode = "A01",
          role = RibbonRole.RESIDENT,
          dominantLayer = DominantDepthLayer.BACK,
          renderOrder = 1,
          style = 2,
          closed = false,
          controlPoints = a01Points,
          halfWidth = 0.092f,
          surfaceProfile = a01Profile,
          periodMs = PERIOD_A01_MS,
          spatialSwayAxis = Vec3(-0.16f, 0.94f, 0.28f).normalized(),
          spatialSwayDeg = 5.4f,
          secondarySwayDeg = 3.1f,
          waveAmp = 0.044f,
          waveFreq = 2.1f,
          twistWaveAmpRad = 0.24f,
          widthBreathAmp = 0.06f,
          phase = 4.30f,
          colorA = floatArrayOf(1.46f, 0.42f, 0.012f), // Deep warm amber-gold
          colorB = floatArrayOf(1.98f, 1.10f, 0.150f), // Soft glowing champagne-gold
          flowSpeed = -1.10f,
          pulseOffset = 0.28f,
          opacityScale = 0.82f,
          additiveBlend = false,
        ),
        // 4: G02 — Iconic Broad Folded Foreground Warm Olive-Kelp Leaf (FRONT, 5300ms)
        RibbonDescriptor(
          id = "resident_G02_emerald_kelp_front",
          residentCode = "G02",
          role = RibbonRole.RESIDENT,
          dominantLayer = DominantDepthLayer.FRONT,
          renderOrder = 4,
          style = 1,
          closed = false,
          controlPoints = g02Points,
          halfWidth = 0.195f,
          surfaceProfile = g02Profile,
          periodMs = PERIOD_G02_MS,
          spatialSwayAxis = Vec3(0.28f, 0.88f, 0.36f).normalized(),
          spatialSwayDeg = 7.2f,
          secondarySwayDeg = 4.5f,
          waveAmp = 0.096f,
          waveFreq = 1.75f,
          twistWaveAmpRad = 0.56f,
          widthBreathAmp = 0.11f,
          phase = 5.20f,
          colorA = floatArrayOf(0.045f, 0.165f, 0.008f), // Deep translucent moss-olive fold
          colorB = floatArrayOf(0.340f, 0.560f, 0.032f), // Warm sunlit botanical olive-emerald
          flowSpeed = -0.88f,
          pulseOffset = 0.82f,
          opacityScale = 1.00f,
          additiveBlend = false,
        ),

        // ==================== 5 OPTIONAL INNER OPTICAL ACCENTS (OFF BY DEFAULT AT ribbonCount = 5) ====================
        // Strictly paired along the sculpted Resident trajectories — NEVER independent mechanical rings!
        RibbonDescriptor(
          id = "secondary_5_c01_inner_refractive_vein",
          residentCode = "SEC_C01",
          role = RibbonRole.SECONDARY_OPTICAL,
          dominantLayer = DominantDepthLayer.FRONT,
          renderOrder = 5,
          style = 0,
          closed = false,
          controlPoints = offsetControlPoints(c01Points, 0.03f, -0.02f, 0.02f),
          halfWidth = 0.042f,
          surfaceProfile = c01Profile,
          periodMs = PERIOD_C01_MS,
          spatialSwayAxis = Vec3(0.18f, 0.92f, 0.34f).normalized(),
          spatialSwayDeg = 6.2f,
          secondarySwayDeg = 3.6f,
          waveAmp = 0.052f,
          waveFreq = 2.0f,
          twistWaveAmpRad = 0.30f,
          widthBreathAmp = 0.06f,
          phase = 0.55f,
          colorA = floatArrayOf(0.06f, 0.72f, 1.54f),
          colorB = floatArrayOf(0.32f, 1.24f, 1.66f),
          flowSpeed = 1.30f,
          pulseOffset = 0.48f,
          opacityScale = 0.34f,
          additiveBlend = false,
        ),
        RibbonDescriptor(
          id = "secondary_6_a02_inner_shimmer_thread",
          residentCode = "SEC_A02",
          role = RibbonRole.SECONDARY_OPTICAL,
          dominantLayer = DominantDepthLayer.FRONT,
          renderOrder = 6,
          style = 2,
          closed = false,
          controlPoints = offsetControlPoints(a02Points, -0.02f, 0.03f, 0.02f),
          halfWidth = 0.036f,
          surfaceProfile = a02Profile,
          periodMs = PERIOD_A02_MS,
          spatialSwayAxis = Vec3(0.24f, 0.91f, -0.32f).normalized(),
          spatialSwayDeg = 5.8f,
          secondarySwayDeg = 3.4f,
          waveAmp = 0.044f,
          waveFreq = 2.4f,
          twistWaveAmpRad = 0.24f,
          widthBreathAmp = 0.05f,
          phase = 3.10f,
          colorA = floatArrayOf(1.62f, 0.50f, 0.02f),
          colorB = floatArrayOf(2.15f, 1.28f, 0.20f),
          flowSpeed = 1.45f,
          pulseOffset = 0.22f,
          opacityScale = 0.30f,
          additiveBlend = false,
        ),
        RibbonDescriptor(
          id = "secondary_7_a01_inner_shimmer_thread",
          residentCode = "SEC_A01",
          role = RibbonRole.SECONDARY_OPTICAL,
          dominantLayer = DominantDepthLayer.BACK,
          renderOrder = 7,
          style = 2,
          closed = false,
          controlPoints = offsetControlPoints(a01Points, 0.02f, 0.02f, -0.02f),
          halfWidth = 0.032f,
          surfaceProfile = a01Profile,
          periodMs = PERIOD_A01_MS,
          spatialSwayAxis = Vec3(-0.16f, 0.94f, 0.28f).normalized(),
          spatialSwayDeg = 5.4f,
          secondarySwayDeg = 3.1f,
          waveAmp = 0.040f,
          waveFreq = 2.1f,
          twistWaveAmpRad = 0.20f,
          widthBreathAmp = 0.05f,
          phase = 4.60f,
          colorA = floatArrayOf(1.48f, 0.44f, 0.015f),
          colorB = floatArrayOf(2.00f, 1.14f, 0.16f),
          flowSpeed = -1.20f,
          pulseOffset = 0.76f,
          opacityScale = 0.28f,
          additiveBlend = false,
        ),
        RibbonDescriptor(
          id = "secondary_8_g02_translucent_tendril",
          residentCode = "SEC_G02",
          role = RibbonRole.SECONDARY_OPTICAL,
          dominantLayer = DominantDepthLayer.FRONT,
          renderOrder = 8,
          style = 1,
          closed = false,
          controlPoints = offsetControlPoints(g02Points, 0.04f, -0.03f, 0.03f),
          halfWidth = 0.064f,
          surfaceProfile = g02Profile,
          periodMs = PERIOD_G02_MS,
          spatialSwayAxis = Vec3(0.28f, 0.88f, 0.36f).normalized(),
          spatialSwayDeg = 7.2f,
          secondarySwayDeg = 4.5f,
          waveAmp = 0.088f,
          waveFreq = 1.75f,
          twistWaveAmpRad = 0.48f,
          widthBreathAmp = 0.08f,
          phase = 5.55f,
          colorA = floatArrayOf(0.045f, 0.17f, 0.008f),
          colorB = floatArrayOf(0.34f, 0.56f, 0.032f),
          flowSpeed = -0.96f,
          pulseOffset = 0.35f,
          opacityScale = 0.34f,
          additiveBlend = false,
        ),
        RibbonDescriptor(
          id = "secondary_9_g01_translucent_tendril",
          residentCode = "SEC_G01",
          role = RibbonRole.SECONDARY_OPTICAL,
          dominantLayer = DominantDepthLayer.BACK,
          renderOrder = 9,
          style = 1,
          closed = false,
          controlPoints = offsetControlPoints(g01Points, -0.03f, 0.02f, -0.03f),
          halfWidth = 0.058f,
          surfaceProfile = g01Profile,
          periodMs = PERIOD_G01_MS,
          spatialSwayAxis = Vec3(-0.22f, 0.90f, -0.36f).normalized(),
          spatialSwayDeg = 6.8f,
          secondarySwayDeg = 4.2f,
          waveAmp = 0.082f,
          waveFreq = 1.85f,
          twistWaveAmpRad = 0.44f,
          widthBreathAmp = 0.07f,
          phase = 2.00f,
          colorA = floatArrayOf(0.040f, 0.16f, 0.008f),
          colorB = floatArrayOf(0.32f, 0.52f, 0.028f),
          flowSpeed = 0.90f,
          pulseOffset = 0.88f,
          opacityScale = 0.32f,
          additiveBlend = false,
        ),
      )
    }
  }
}
