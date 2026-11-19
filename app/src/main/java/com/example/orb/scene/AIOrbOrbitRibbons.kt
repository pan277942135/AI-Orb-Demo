package com.example.orb.scene

import android.opengl.GLES30
import android.opengl.Matrix
import com.example.orb.config.AIOrbConfig
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.geometry.Vec3
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import com.example.orb.gl.RibbonOpticsShaders
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class RibbonDescriptor(
  val style: Int, // 0 = Refractive Liquid-Glass, 1 = Silky Jade-Emerald, 2 = AA Fine Glass Filament
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
 * Manages 10 tightly sculpted 3D swept Ribbon Meshes & anti-aliased Glass Filaments.
 * V2 Key Improvements:
 * - Compact orbital radii (1.18..1.46) tightly embracing the crystal sphere (matching reference silhouette)
 * - Alpha-blended liquid-glass ribbons (preventing RGB additive cyan+amber white washout!)
 * - Saturated fiery champagne-amber gold & crisp ice-cyan glass palettes + feathered silky jade ribbons
 */
class AIOrbOrbitRibbons {
  private var program: GlShaderProgram? = null
  private val descriptors: List<RibbonDescriptor> = buildRibbonDescriptors()
  private val meshes = ArrayList<GlMesh>(descriptors.size)
  private var currentSegments: Int = -1
  private val modelMat = FloatArray(16)

  val maxRibbons: Int get() = descriptors.size

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
    time: Float,
    config: AIOrbConfig,
    outMatrix: FloatArray,
  ) {
    val d = descriptors[index]
    val t = time * config.ribbonSpeed
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

  fun evaluateWorldPoint(index: Int, u: Float, time: Float, config: AIOrbConfig): Vec3 {
    val d = descriptors[index]
    val spine = OrbGeometryBuilder.sampleCatmullRom(d.controlPoints, u, d.closed)
    val t = time * config.ribbonSpeed
    val angle = u * (2.0 * PI).toFloat() * d.waveFreq
    val waveR = cos(angle * 0.5f - t * 0.62f + d.phase * 1.7f) * (d.waveAmp * 0.52f)
    val radial = spine.normalized()
    val local = spine + radial * waveR

    computeRibbonModelMatrix(index, time, config, modelMat)
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
    time: Float,
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
    prog.setFloat("uTime", time)
    prog.setFloat("uRibbonWidth", config.ribbonWidth)
    prog.setFloat("uRibbonSpeed", config.ribbonSpeed)
    prog.setFloat("uRibbonTransparency", config.ribbonTransparency)
    prog.setFloat("uEnergyIntensity", config.energyIntensity)
    prog.setFloat("uOverallIntensity", config.overallIntensity)
    prog.setFloat("uHdrScale", hdrScale)
    prog.setInt("uClipPass", clipPass)
    prog.setFloat("uOrbCenterViewZ", orbCenterViewZ)

    for (i in 0 until activeCount) {
      val d = descriptors[i]
      computeRibbonModelMatrix(i, time, config, modelMat)

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

    private fun buildRibbonDescriptors(): List<RibbonDescriptor> = listOf(
      // 0: Hero Sculpted Liquid-Glass Ribbon — Electric Ice-Blue (left) -> Fiery Amber-Gold (right)
      RibbonDescriptor(
        style = 0,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.38f, rz = 1.24f, yAmp = 0.16f, yFreq = 2f, eccentricity = 0.07f, seedPhase = 0.4f),
        halfWidth = 0.092f,
        twistTurns = 0.5f,
        tiltXDeg = 20f,
        tiltYDeg = -8f,
        tiltZDeg = -22f,
        orbitAxis = Vec3(0.06f, 0.99f, 0.09f).normalized(),
        orbitSpeedDegPerSec = 8.8f,
        wobbleAmpDeg = 4.5f,
        wobbleFreq = 0.22f,
        waveAmp = 0.036f,
        waveFreq = 2f,
        phase = 0.3f,
        colorA = floatArrayOf(0.18f, 0.84f, 1.05f), // Crystal Ice-Blue
        colorB = floatArrayOf(1.18f, 0.68f, 0.14f), // Fiery Champagne-Amber Gold
        flowSpeed = 1.15f,
        pulseOffset = 0.12f,
        additiveBlend = false, // Alpha-blended so warm amber stays saturated over cyan!
      ),

      // 1: Hero Sweeping Silky Jade-Emerald / Chartreuse Drape (upper-right -> lower-left)
      RibbonDescriptor(
        style = 1,
        closed = false,
        controlPoints = organicOrbitPoints(
          count = 16,
          rx = 1.36f,
          rz = 1.28f,
          yAmp = 0.28f,
          yFreq = 1.5f,
          eccentricity = 0.10f,
          seedPhase = 1.8f,
          arcFraction = 0.86f,
          arcStartRad = -0.52f,
        ),
        halfWidth = 0.175f,
        twistTurns = 0.75f,
        tiltXDeg = 54f,
        tiltYDeg = 16f,
        tiltZDeg = 44f,
        orbitAxis = Vec3(-0.16f, 0.96f, -0.20f).normalized(),
        orbitSpeedDegPerSec = -5.6f,
        wobbleAmpDeg = 5.5f,
        wobbleFreq = 0.18f,
        waveAmp = 0.052f,
        waveFreq = 2f,
        phase = 1.7f,
        colorA = floatArrayOf(0.24f, 0.58f, 0.18f), // Rich Olive-Emerald Silk
        colorB = floatArrayOf(0.62f, 0.80f, 0.20f), // Sunlit Yellow-Green Jade
        flowSpeed = 0.82f,
        pulseOffset = 0.44f,
        additiveBlend = false,
      ),

      // 2: Upper-Left to Right Warm Fiery Champagne-Amber Liquid-Glass Ribbon (reference's iconic gold loop!)
      RibbonDescriptor(
        style = 0,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.32f, rz = 1.22f, yAmp = 0.22f, yFreq = 2f, eccentricity = 0.08f, seedPhase = 2.6f),
        halfWidth = 0.078f,
        twistTurns = -0.5f,
        tiltXDeg = -32f,
        tiltYDeg = 14f,
        tiltZDeg = -34f,
        orbitAxis = Vec3(0.14f, 0.96f, -0.22f).normalized(),
        orbitSpeedDegPerSec = -10.5f,
        wobbleAmpDeg = 4.8f,
        wobbleFreq = 0.25f,
        waveAmp = 0.034f,
        waveFreq = 3f,
        phase = 2.9f,
        colorA = floatArrayOf(1.15f, 0.62f, 0.12f), // Deep Fiery Amber
        colorB = floatArrayOf(1.22f, 0.88f, 0.36f), // Luminous Champagne Gold
        flowSpeed = -1.22f,
        pulseOffset = 0.66f,
        additiveBlend = false,
      ),

      // 3: Secondary Tighter Ice-Cyan & Aqua Liquid-Glass Ribbon
      RibbonDescriptor(
        style = 0,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.26f, rz = 1.32f, yAmp = 0.18f, yFreq = 2f, eccentricity = 0.06f, seedPhase = 4.1f),
        halfWidth = 0.066f,
        twistTurns = 0.5f,
        tiltXDeg = 36f,
        tiltYDeg = -20f,
        tiltZDeg = 25f,
        orbitAxis = Vec3(-0.12f, 0.98f, 0.15f).normalized(),
        orbitSpeedDegPerSec = 12.2f,
        wobbleAmpDeg = 4.2f,
        wobbleFreq = 0.21f,
        waveAmp = 0.030f,
        waveFreq = 2f,
        phase = 4.2f,
        colorA = floatArrayOf(0.16f, 0.82f, 1.05f), // Ice Cyan
        colorB = floatArrayOf(0.14f, 0.78f, 0.64f), // Aqua Teal
        flowSpeed = 1.34f,
        pulseOffset = 0.31f,
        additiveBlend = false,
      ),

      // 4: Secondary Soft Silky Jade Ribbon (paired organic emerald drapery)
      RibbonDescriptor(
        style = 1,
        closed = false,
        controlPoints = organicOrbitPoints(
          count = 16,
          rx = 1.28f,
          rz = 1.38f,
          yAmp = 0.26f,
          yFreq = 2f,
          eccentricity = 0.10f,
          seedPhase = 5.2f,
          arcFraction = 0.82f,
          arcStartRad = 1.12f,
        ),
        halfWidth = 0.140f,
        twistTurns = -0.65f,
        tiltXDeg = -48f,
        tiltYDeg = -14f,
        tiltZDeg = -46f,
        orbitAxis = Vec3(0.20f, 0.95f, 0.22f).normalized(),
        orbitSpeedDegPerSec = 5.0f,
        wobbleAmpDeg = 5.2f,
        wobbleFreq = 0.16f,
        waveAmp = 0.044f,
        waveFreq = 2f,
        phase = 5.1f,
        colorA = floatArrayOf(0.16f, 0.52f, 0.22f), // Deep Forest Jade
        colorB = floatArrayOf(0.54f, 0.76f, 0.18f), // Chartreuse Gold-Green
        flowSpeed = -0.70f,
        pulseOffset = 0.82f,
        additiveBlend = false,
      ),

      // 5: Outer Paired Champagne-Amber to Ice-Aqua Glass Band (frames right & left silhouettes cleanly)
      RibbonDescriptor(
        style = 0,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.44f, rz = 1.34f, yAmp = 0.20f, yFreq = 2f, eccentricity = 0.08f, seedPhase = 0.9f),
        halfWidth = 0.056f,
        twistTurns = 0.5f,
        tiltXDeg = -16f,
        tiltYDeg = 24f,
        tiltZDeg = -14f,
        orbitAxis = Vec3(0.08f, 0.99f, -0.09f).normalized(),
        orbitSpeedDegPerSec = 7.2f,
        wobbleAmpDeg = 3.8f,
        wobbleFreq = 0.20f,
        waveAmp = 0.036f,
        waveFreq = 3f,
        phase = 0.9f,
        colorA = floatArrayOf(0.24f, 0.88f, 1.00f), // Ice Aqua
        colorB = floatArrayOf(1.22f, 0.66f, 0.12f), // Rich Amber Gold
        flowSpeed = 0.92f,
        pulseOffset = 0.55f,
        additiveBlend = false,
      ),

      // 6: Anti-Aliased Fine Ice-Cyan Crystal Filament (wider mesh + smooth Gaussian core = zero aliasing!)
      RibbonDescriptor(
        style = 2,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.42f, rz = 1.32f, yAmp = 0.26f, yFreq = 2f, eccentricity = 0.07f, seedPhase = 3.3f),
        halfWidth = 0.036f,
        twistTurns = 0f,
        tiltXDeg = 44f,
        tiltYDeg = -10f,
        tiltZDeg = -26f,
        orbitAxis = Vec3(-0.14f, 0.97f, 0.20f).normalized(),
        orbitSpeedDegPerSec = 14.5f,
        wobbleAmpDeg = 4.6f,
        wobbleFreq = 0.24f,
        waveAmp = 0.026f,
        waveFreq = 3f,
        phase = 3.3f,
        colorA = floatArrayOf(0.32f, 0.92f, 1.08f),
        colorB = floatArrayOf(0.18f, 0.82f, 0.96f),
        flowSpeed = 1.55f,
        pulseOffset = 0.22f,
        additiveBlend = false,
      ),

      // 7: Anti-Aliased Fine Champagne-Amber Crystal Filament
      RibbonDescriptor(
        style = 2,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.40f, rz = 1.44f, yAmp = 0.24f, yFreq = 3f, eccentricity = 0.08f, seedPhase = 1.1f),
        halfWidth = 0.034f,
        twistTurns = 0f,
        tiltXDeg = -38f,
        tiltYDeg = 18f,
        tiltZDeg = 32f,
        orbitAxis = Vec3(0.18f, 0.96f, -0.20f).normalized(),
        orbitSpeedDegPerSec = -13.2f,
        wobbleAmpDeg = 4.4f,
        wobbleFreq = 0.23f,
        waveAmp = 0.024f,
        waveFreq = 2f,
        phase = 1.1f,
        colorA = floatArrayOf(1.22f, 0.78f, 0.24f),
        colorB = floatArrayOf(1.18f, 0.60f, 0.14f),
        flowSpeed = -1.48f,
        pulseOffset = 0.77f,
        additiveBlend = false,
      ),

      // 8: Extra High-Inclination Emerald-Aqua Glass Ribbon (ribbonCount >= 9)
      RibbonDescriptor(
        style = 0,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.22f, rz = 1.30f, yAmp = 0.20f, yFreq = 2f, eccentricity = 0.06f, seedPhase = 2.0f),
        halfWidth = 0.056f,
        twistTurns = 0.5f,
        tiltXDeg = 60f,
        tiltYDeg = 28f,
        tiltZDeg = -52f,
        orbitAxis = Vec3(0.24f, 0.93f, 0.26f).normalized(),
        orbitSpeedDegPerSec = 9.8f,
        wobbleAmpDeg = 4.6f,
        wobbleFreq = 0.22f,
        waveAmp = 0.030f,
        waveFreq = 2f,
        phase = 2.0f,
        colorA = floatArrayOf(0.16f, 0.80f, 0.52f),
        colorB = floatArrayOf(0.28f, 0.90f, 1.02f),
        flowSpeed = 1.18f,
        pulseOffset = 0.39f,
        additiveBlend = false,
      ),

      // 9: Extra High-Inclination Solar-Gold AA Filament (ribbonCount == 10)
      RibbonDescriptor(
        style = 2,
        closed = true,
        controlPoints = organicOrbitPoints(16, rx = 1.46f, rz = 1.38f, yAmp = 0.26f, yFreq = 2f, eccentricity = 0.08f, seedPhase = 4.8f),
        halfWidth = 0.034f,
        twistTurns = 0f,
        tiltXDeg = -58f,
        tiltYDeg = -22f,
        tiltZDeg = 18f,
        orbitAxis = Vec3(-0.22f, 0.94f, -0.24f).normalized(),
        orbitSpeedDegPerSec = -11.8f,
        wobbleAmpDeg = 4.8f,
        wobbleFreq = 0.19f,
        waveAmp = 0.026f,
        waveFreq = 3f,
        phase = 4.8f,
        colorA = floatArrayOf(1.22f, 0.72f, 0.18f),
        colorB = floatArrayOf(0.42f, 0.92f, 1.02f),
        flowSpeed = 1.42f,
        pulseOffset = 0.91f,
        additiveBlend = false,
      ),
    )
  }
}
