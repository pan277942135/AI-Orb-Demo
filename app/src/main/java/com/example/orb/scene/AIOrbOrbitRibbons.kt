package com.example.orb.scene

import android.opengl.GLES30
import android.opengl.Matrix
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.RenderDebugMode
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.geometry.Vec3
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import com.example.orb.gl.RibbonOpticsShaders
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Architectural role separation between canonical Resident orbital identities (5 frozen slots)
 * and independent Secondary Optical filaments/energy streams (freely scaled by visual density).
 */
enum class RibbonRole {
  RESIDENT,
  SECONDARY_OPTICAL,
}

data class RibbonDescriptor(
  val id: String,
  val role: RibbonRole,
  val style: Int, // 0 = Refractive Liquid-Glass, 1 = Gossamer Seaweed Silk, 2 = Hair-Fine AA Filament
  val closed: Boolean,
  val controlPoints: List<Vec3>,
  val halfWidth: Float,
  val twistTurns: Float,
  val tiltXDeg: Float,
  val tiltYDeg: Float,
  val tiltZDeg: Float,
  val orbitAxis: Vec3,
  val orbitSpeedDegPerSec: Float,
  val wobbleAmpDeg: Float,
  val wobbleFreq: Float,
  val waveAmp: Float,
  val waveFreq: Float,
  val phase: Float,
  val colorA: FloatArray,
  val colorB: FloatArray,
  val flowSpeed: Float,
  val pulseOffset: Float,
  val additiveBlend: Boolean,
)

/**
 * Manages 5 canonical RESIDENT orbital ribbons plus 5 independent SECONDARY_OPTICAL
 * fine filaments & accent streams, driven strictly by [AIOrbMotionSnapshot.ribbonTimeSec].
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
      val crossSlices = if (desc.style == 2) 3 else 6
      meshes.add(
        OrbGeometryBuilder.createRibbonMesh(
          controlPoints = desc.controlPoints,
          segments = segments,
          closed = desc.closed,
          halfWidth = desc.halfWidth,
          twistTurns = desc.twistTurns,
          taperEnds = !desc.closed,
          crossSlices = crossSlices,
        )
      )
    }
    currentSegments = segments
  }

  fun computeRibbonModelMatrix(
    index: Int,
    motion: AIOrbMotionSnapshot,
    config: AIOrbConfig,
    outMatrix: FloatArray,
  ) {
    val d = descriptors[index]
    val t = motion.ribbonTimeSec
    Matrix.setIdentityM(outMatrix, 0)

    val radialScale = 0.25f + 0.75f * config.coreScale
    Matrix.scaleM(outMatrix, 0, radialScale, radialScale, radialScale)

    val wobbleX = sin(t * d.wobbleFreq + d.phase) * d.wobbleAmpDeg
    val wobbleZ = cos(t * d.wobbleFreq * 0.78f + d.phase * 1.4f) * (d.wobbleAmpDeg * 0.85f)

    Matrix.rotateM(outMatrix, 0, d.tiltZDeg + wobbleZ, 0f, 0f, 1f)
    Matrix.rotateM(outMatrix, 0, d.tiltXDeg + wobbleX, 1f, 0f, 0f)
    Matrix.rotateM(outMatrix, 0, d.tiltYDeg, 0f, 1f, 0f)

    val axis = d.orbitAxis
    val angle = (t * d.orbitSpeedDegPerSec) % 360f
    Matrix.rotateM(outMatrix, 0, angle, axis.x, axis.y, axis.z)
  }

  fun evaluateWorldPoint(index: Int, u: Float, motion: AIOrbMotionSnapshot, config: AIOrbConfig): Vec3 {
    val d = descriptors[index]
    val spine = OrbGeometryBuilder.sampleCatmullRom(d.controlPoints, u, d.closed)
    val t = motion.ribbonTimeSec
    val angle = u * (2.0 * PI).toFloat() * d.waveFreq
    val waveR = cos(angle * 0.5f - t * 0.58f + d.phase * 1.7f) * (d.waveAmp * 0.50f)
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
    orbCenterViewZ: Float,
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
    prog.setFloat("uOrbCenterViewZ", orbCenterViewZ)
    prog.setInt("uDepthDebug", if (config.debugMode == RenderDebugMode.DEPTH_DEBUG) 1 else 0)

    for (i in 0 until activeCount) {
      val d = descriptors[i]
      computeRibbonModelMatrix(i, motion, config, modelMat)

      if (d.additiveBlend) {
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
      } else {
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
      }

      prog.setMat4("uModel", modelMat)
      prog.setInt("uRibbonStyle", d.style)
      prog.setFloat("uWaveAmp", d.waveAmp)
      prog.setFloat("uWaveFreq", d.waveFreq)
      prog.setFloat("uRibbonPhase", d.phase)
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
    private fun organicOrbitPoints(
      count: Int,
      rx: Float,
      rz: Float,
      yAmp: Float,
      yFreq: Float,
      eccentricity: Float,
      seedPhase: Float,
      arcFraction: Float = 1.0f,
      arcStartRad: Float = 0f,
    ): List<Vec3> {
      val pts = ArrayList<Vec3>(count)
      for (i in 0 until count) {
        val frac = if (arcFraction < 0.99f) i.toFloat() / (count - 1).toFloat() else i.toFloat() / count.toFloat()
        val theta = arcStartRad + frac * arcFraction * (2.0 * PI).toFloat()
        val rMod = 1.0f + eccentricity * sin(theta * 2f + seedPhase) + (eccentricity * 0.42f) * cos(theta * 3f - seedPhase)
        val x = cos(theta) * rx * rMod
        val z = sin(theta) * rz * rMod
        val y = sin(theta * yFreq + seedPhase * 1.3f) * yAmp + cos(theta * (yFreq + 1f) - seedPhase) * (yAmp * 0.32f)
        pts.add(Vec3(x, y, z))
      }
      return pts
    }

    /**
     * Choreographed strictly to the high-fidelity reference silhouette (linear HDR color values):
     * - Indices 0..4: 5 Canonical RESIDENT orbital identities
     * - Indices 5..9: 5 Delicate SECONDARY_OPTICAL glass filaments & subtle accent streams
     */
    fun buildRibbonDescriptors(): List<RibbonDescriptor> = listOf(
      // --- 5 CANONICAL RESIDENT RIBBONS ---
      // 0: Hero diagonal sweeping crystal glass ribbon (ice-cyan left -> warm molten champagne-gold right)
      RibbonDescriptor(
        id = "resident_0_crystal_ice_amber",
        role = RibbonRole.RESIDENT,
        style = 0,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.34f, rz = 1.22f, yAmp = 0.14f, yFreq = 2f, eccentricity = 0.08f, seedPhase = 0.4f),
        halfWidth = 0.082f,
        twistTurns = 0.5f,
        tiltXDeg = 22f,
        tiltYDeg = -8f,
        tiltZDeg = -24f,
        orbitAxis = Vec3(0.06f, 0.99f, 0.08f).normalized(),
        orbitSpeedDegPerSec = 7.6f,
        wobbleAmpDeg = 4.2f,
        wobbleFreq = 0.22f,
        waveAmp = 0.032f,
        waveFreq = 2f,
        phase = 0.3f,
        colorA = floatArrayOf(0.12f, 0.78f, 1.15f),
        colorB = floatArrayOf(1.28f, 0.64f, 0.09f),
        flowSpeed = 1.05f,
        pulseOffset = 0.12f,
        additiveBlend = false,
      ),
      // 1: Gossamer sweeping diagonal olive-emerald seaweed silk veil (top-right -> bottom-left)
      RibbonDescriptor(
        id = "resident_1_silk_jade_primary",
        role = RibbonRole.RESIDENT,
        style = 1,
        closed = false,
        controlPoints = organicOrbitPoints(16, rx = 1.32f, rz = 1.24f, yAmp = 0.26f, yFreq = 1.5f, eccentricity = 0.11f, seedPhase = 1.8f, arcFraction = 0.84f, arcStartRad = -0.45f),
        halfWidth = 0.148f,
        twistTurns = 0.85f,
        tiltXDeg = 52f,
        tiltYDeg = 18f,
        tiltZDeg = 46f,
        orbitAxis = Vec3(-0.15f, 0.96f, -0.18f).normalized(),
        orbitSpeedDegPerSec = -4.6f,
        wobbleAmpDeg = 5.2f,
        wobbleFreq = 0.18f,
        waveAmp = 0.056f,
        waveFreq = 2f,
        phase = 1.7f,
        colorA = floatArrayOf(0.09f, 0.34f, 0.04f),
        colorB = floatArrayOf(0.32f, 0.56f, 0.06f),
        flowSpeed = 0.75f,
        pulseOffset = 0.44f,
        additiveBlend = false,
      ),
      // 2: Warm molten champagne-amber crystal ribbon arching upper-left & right flank
      RibbonDescriptor(
        id = "resident_2_champagne_amber_arc",
        role = RibbonRole.RESIDENT,
        style = 0,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.28f, rz = 1.20f, yAmp = 0.18f, yFreq = 2f, eccentricity = 0.07f, seedPhase = 2.6f),
        halfWidth = 0.068f,
        twistTurns = -0.5f,
        tiltXDeg = -28f,
        tiltYDeg = 12f,
        tiltZDeg = -32f,
        orbitAxis = Vec3(0.14f, 0.96f, -0.20f).normalized(),
        orbitSpeedDegPerSec = -8.8f,
        wobbleAmpDeg = 4.5f,
        wobbleFreq = 0.24f,
        waveAmp = 0.030f,
        waveFreq = 3f,
        phase = 2.9f,
        colorA = floatArrayOf(1.22f, 0.58f, 0.08f),
        colorB = floatArrayOf(1.36f, 0.86f, 0.24f),
        flowSpeed = -1.12f,
        pulseOffset = 0.66f,
        additiveBlend = false,
      ),
      // 3: Crisp ice-aqua crystal ribbon sweeping upper-left -> lower-right counter-orbit
      RibbonDescriptor(
        id = "resident_3_ice_aqua_counter",
        role = RibbonRole.RESIDENT,
        style = 0,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.24f, rz = 1.28f, yAmp = 0.16f, yFreq = 2f, eccentricity = 0.06f, seedPhase = 4.1f),
        halfWidth = 0.058f,
        twistTurns = 0.5f,
        tiltXDeg = 34f,
        tiltYDeg = -18f,
        tiltZDeg = 22f,
        orbitAxis = Vec3(-0.10f, 0.98f, 0.14f).normalized(),
        orbitSpeedDegPerSec = 10.2f,
        wobbleAmpDeg = 4.0f,
        wobbleFreq = 0.20f,
        waveAmp = 0.028f,
        waveFreq = 2f,
        phase = 4.2f,
        colorA = floatArrayOf(0.10f, 0.78f, 1.16f),
        colorB = floatArrayOf(0.08f, 0.66f, 0.54f),
        flowSpeed = 1.20f,
        pulseOffset = 0.31f,
        additiveBlend = false,
      ),
      // 4: Secondary wispy olive-emerald botanical silk veil weaving lower-left -> upper-right
      RibbonDescriptor(
        id = "resident_4_silk_jade_secondary",
        role = RibbonRole.RESIDENT,
        style = 1,
        closed = false,
        controlPoints = organicOrbitPoints(16, rx = 1.26f, rz = 1.32f, yAmp = 0.24f, yFreq = 2f, eccentricity = 0.10f, seedPhase = 5.2f, arcFraction = 0.78f, arcStartRad = 1.18f),
        halfWidth = 0.118f,
        twistTurns = -0.75f,
        tiltXDeg = -44f,
        tiltYDeg = -14f,
        tiltZDeg = -48f,
        orbitAxis = Vec3(0.18f, 0.95f, 0.20f).normalized(),
        orbitSpeedDegPerSec = 4.2f,
        wobbleAmpDeg = 5.0f,
        wobbleFreq = 0.16f,
        waveAmp = 0.048f,
        waveFreq = 2f,
        phase = 5.1f,
        colorA = floatArrayOf(0.07f, 0.30f, 0.06f),
        colorB = floatArrayOf(0.28f, 0.52f, 0.06f),
        flowSpeed = -0.68f,
        pulseOffset = 0.82f,
        additiveBlend = false,
      ),

      // --- 5 DELICATE SECONDARY OPTICAL FILAMENTS (SUBTLE ACCENTS — NEVER COMPETE WITH RESIDENTS) ---
      RibbonDescriptor(
        id = "secondary_5_outer_amber_aqua",
        role = RibbonRole.SECONDARY_OPTICAL,
        style = 0,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.36f, rz = 1.28f, yAmp = 0.18f, yFreq = 2f, eccentricity = 0.07f, seedPhase = 0.9f),
        halfWidth = 0.040f,
        twistTurns = 0.5f,
        tiltXDeg = -14f,
        tiltYDeg = 20f,
        tiltZDeg = -16f,
        orbitAxis = Vec3(0.08f, 0.99f, -0.08f).normalized(),
        orbitSpeedDegPerSec = 6.4f,
        wobbleAmpDeg = 3.5f,
        wobbleFreq = 0.19f,
        waveAmp = 0.028f,
        waveFreq = 3f,
        phase = 0.9f,
        colorA = floatArrayOf(0.14f, 0.80f, 1.06f),
        colorB = floatArrayOf(1.26f, 0.62f, 0.09f),
        flowSpeed = 0.88f,
        pulseOffset = 0.55f,
        additiveBlend = false,
      ),
      RibbonDescriptor(
        id = "secondary_6_filament_ice_cyan",
        role = RibbonRole.SECONDARY_OPTICAL,
        style = 2,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.36f, rz = 1.28f, yAmp = 0.22f, yFreq = 2f, eccentricity = 0.06f, seedPhase = 3.3f),
        halfWidth = 0.024f,
        twistTurns = 0f,
        tiltXDeg = 40f,
        tiltYDeg = -10f,
        tiltZDeg = -24f,
        orbitAxis = Vec3(-0.14f, 0.97f, 0.18f).normalized(),
        orbitSpeedDegPerSec = 12.8f,
        wobbleAmpDeg = 4.2f,
        wobbleFreq = 0.24f,
        waveAmp = 0.022f,
        waveFreq = 3f,
        phase = 3.3f,
        colorA = floatArrayOf(0.18f, 0.86f, 1.15f),
        colorB = floatArrayOf(0.10f, 0.74f, 0.96f),
        flowSpeed = 1.40f,
        pulseOffset = 0.22f,
        additiveBlend = false,
      ),
      RibbonDescriptor(
        id = "secondary_7_filament_champagne_gold",
        role = RibbonRole.SECONDARY_OPTICAL,
        style = 2,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.34f, rz = 1.36f, yAmp = 0.20f, yFreq = 3f, eccentricity = 0.07f, seedPhase = 1.1f),
        halfWidth = 0.022f,
        twistTurns = 0f,
        tiltXDeg = -34f,
        tiltYDeg = 16f,
        tiltZDeg = 30f,
        orbitAxis = Vec3(0.16f, 0.96f, -0.18f).normalized(),
        orbitSpeedDegPerSec = -11.5f,
        wobbleAmpDeg = 4.0f,
        wobbleFreq = 0.22f,
        waveAmp = 0.020f,
        waveFreq = 2f,
        phase = 1.1f,
        colorA = floatArrayOf(1.26f, 0.74f, 0.16f),
        colorB = floatArrayOf(1.18f, 0.56f, 0.09f),
        flowSpeed = -1.35f,
        pulseOffset = 0.77f,
        additiveBlend = false,
      ),
      RibbonDescriptor(
        id = "secondary_8_emerald_aqua_accent",
        role = RibbonRole.SECONDARY_OPTICAL,
        style = 2,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.22f, rz = 1.26f, yAmp = 0.18f, yFreq = 2f, eccentricity = 0.06f, seedPhase = 2.0f),
        halfWidth = 0.024f,
        twistTurns = 0f,
        tiltXDeg = 54f,
        tiltYDeg = 24f,
        tiltZDeg = -46f,
        orbitAxis = Vec3(0.22f, 0.94f, 0.24f).normalized(),
        orbitSpeedDegPerSec = 9.0f,
        wobbleAmpDeg = 4.2f,
        wobbleFreq = 0.21f,
        waveAmp = 0.024f,
        waveFreq = 2f,
        phase = 2.0f,
        colorA = floatArrayOf(0.10f, 0.68f, 0.36f),
        colorB = floatArrayOf(0.16f, 0.84f, 1.04f),
        flowSpeed = 1.10f,
        pulseOffset = 0.39f,
        additiveBlend = false,
      ),
      RibbonDescriptor(
        id = "secondary_9_solar_gold_filament",
        role = RibbonRole.SECONDARY_OPTICAL,
        style = 2,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.38f, rz = 1.32f, yAmp = 0.22f, yFreq = 2f, eccentricity = 0.07f, seedPhase = 4.8f),
        halfWidth = 0.022f,
        twistTurns = 0f,
        tiltXDeg = -52f,
        tiltYDeg = -20f,
        tiltZDeg = 16f,
        orbitAxis = Vec3(-0.20f, 0.95f, -0.22f).normalized(),
        orbitSpeedDegPerSec = -10.4f,
        wobbleAmpDeg = 4.4f,
        wobbleFreq = 0.19f,
        waveAmp = 0.022f,
        waveFreq = 3f,
        phase = 4.8f,
        colorA = floatArrayOf(1.24f, 0.68f, 0.12f),
        colorB = floatArrayOf(0.24f, 0.86f, 1.04f),
        flowSpeed = 1.32f,
        pulseOffset = 0.91f,
        additiveBlend = false,
      ),
    )
  }
}
