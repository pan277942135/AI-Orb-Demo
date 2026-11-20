package com.example.orb.scene

import android.opengl.GLES30
import android.opengl.Matrix
import com.example.orb.config.AIOrbConfig
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import com.example.orb.gl.RibbonOpticsShaders
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

private data class FlareSpec(
  val ribbonIndex: Int,
  val anchorU: Float,
  val localDriftAmp: Float,
  val driftFreq: Float,
  val pulseFreq: Float,
  val pulsePhase: Float,
  val baseSize: Float,
  val intensityGain: Float,
  val isGold: Boolean,
)

/**
 * Renders 6 curated, jewel-like HDR pinpoint starburst highlights anchored along the iconic
 * high-curvature bends of the active 5 Resident ribbons (8:30 ice-cyan, 5:30 ice-cyan,
 * 10:30 warm gold, 3:30 blazing orange-gold, 5:00 lower-right gold, 11:30 top-left crown).
 * Each flare glides gently along its bend while breathing in sync with passing ribbon light,
 * and attenuates automatically if any flare crosses over the glass sphere center.
 */
class AIOrbEnergyHighlights {
  private var program: GlShaderProgram? = null
  private var quad: GlMesh? = null
  private val viewPosTemp = FloatArray(4)
  private val worldPosTemp = FloatArray(4)

  // All 6 jewel flares strictly bound to active Resident indices 0..4 (0=C01, 2=A02, 3=A01)
  private val flares: List<FlareSpec> = listOf(
    // 1. 8:30 Far-Left C01 Ice-Blue C-Arc Bend (Primary Cold Optical Anchor)
    FlareSpec(0, anchorU = 0.40f, localDriftAmp = 0.042f, driftFreq = 0.92f, pulseFreq = 1.42f, pulsePhase = 0.35f, baseSize = 0.34f, intensityGain = 1.25f, isGold = false),
    // 2. 5:30 Bottom-Right C01 Translucent Cyan Under-Arc Sparkle
    FlareSpec(0, anchorU = 0.70f, localDriftAmp = 0.038f, driftFreq = 0.85f, pulseFreq = 1.26f, pulsePhase = 2.40f, baseSize = 0.25f, intensityGain = 1.05f, isGold = false),
    // 3. 10:30 Upper-Left A02 Golden Shoulder Flare
    FlareSpec(2, anchorU = 0.22f, localDriftAmp = 0.035f, driftFreq = 1.05f, pulseFreq = 1.65f, pulsePhase = 1.10f, baseSize = 0.34f, intensityGain = 1.22f, isGold = true),
    // 4. 3:30 Far-Right A02 Iconic Blazing Amber-Gold Loop (Primary Warm Optical Anchor!)
    FlareSpec(2, anchorU = 0.76f, localDriftAmp = 0.045f, driftFreq = 0.96f, pulseFreq = 1.54f, pulsePhase = 3.20f, baseSize = 0.40f, intensityGain = 1.38f, isGold = true),
    // 5. 5:00 Lower-Right A02 Rim Crossing Warm Glint
    FlareSpec(2, anchorU = 0.55f, localDriftAmp = 0.032f, driftFreq = 1.12f, pulseFreq = 1.38f, pulsePhase = 4.65f, baseSize = 0.26f, intensityGain = 0.98f, isGold = true),
    // 6. 11:30 Upper-Left A01 Back Crown Warm Star Glint
    FlareSpec(3, anchorU = 0.25f, localDriftAmp = 0.036f, driftFreq = 0.88f, pulseFreq = 1.18f, pulsePhase = 5.40f, baseSize = 0.25f, intensityGain = 1.00f, isGold = true),
  )

  // Saturated ACES + sRGB 2.2 calibrated linear HDR flare colors (zero muddy grey-beige!)
  private val goldColor = floatArrayOf(1.85f, 0.76f, 0.04f)
  private val cyanColor = floatArrayOf(0.14f, 1.05f, 1.65f)

  fun init() {
    release()
    program = GlShaderProgram(
      RibbonOpticsShaders.ENERGY_HIGHLIGHT_VERT,
      RibbonOpticsShaders.ENERGY_HIGHLIGHT_FRAG,
      "AIOrbEnergyHighlights",
    )
    quad = OrbGeometryBuilder.createCenteredBillboardQuad()
  }

  fun drawPass(
    clipPass: Int,
    orbCenterViewZ: Float,
    ribbons: AIOrbOrbitRibbons,
    viewMatrix: FloatArray,
    projMatrix: FloatArray,
    motion: AIOrbMotionSnapshot,
    hdrScale: Float,
    config: AIOrbConfig,
  ) {
    if (config.energyIntensity <= 0.01f) return
    val prog = program ?: return
    val mesh = quad ?: return

    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)

    prog.use()
    prog.setMat4("uView", viewMatrix)
    prog.setMat4("uProj", projMatrix)
    prog.setFloat("uHdrScale", hdrScale)

    val activeRibbons = config.ribbonCount.coerceIn(1, ribbons.maxRibbons)
    val tRibbon = motion.ribbonTimeSec
    val tOptics = motion.secondaryOpticsPhase
    val scaledOrbRadius = (config.coreScale * config.coreShellScale).coerceAtLeast(0.4f)

    for (spec in flares) {
      if (spec.ribbonIndex >= activeRibbons) continue

      // Local graceful gliding along the high-curvature bend (stays visible on the iconic outer arcs!)
      val localOffset = sin(tRibbon * spec.driftFreq + spec.pulsePhase) * spec.localDriftAmp
      val u = (spec.anchorU + localOffset).coerceIn(0.03f, 0.97f)

      val worldPt = ribbons.evaluateWorldPoint(spec.ribbonIndex, u, motion, config)
      worldPosTemp[0] = worldPt.x
      worldPosTemp[1] = worldPt.y
      worldPosTemp[2] = worldPt.z
      worldPosTemp[3] = 1f
      Matrix.multiplyMV(viewPosTemp, 0, viewMatrix, 0, worldPosTemp, 0)
      val viewZ = viewPosTemp[2]

      if (clipPass == 0 && viewZ >= orbCenterViewZ) continue
      if (clipPass == 1 && viewZ < orbCenterViewZ) continue

      // Unified center guard applied identically to BOTH front (clipPass == 1) and back (clipPass == 0)
      // passes using real scaled core radius (coreScale * coreShellScale) so zero white blotches bleed through core!
      val xyRadiusFromOrbCenter = sqrt(viewPosTemp[0] * viewPosTemp[0] + viewPosTemp[1] * viewPosTemp[1])
      val centerGuard = computeCenterGuard(xyRadiusFromOrbCenter, scaledOrbRadius)
      if (centerGuard <= 0.005f) continue

      val wave1 = 0.5f + 0.5f * sin(tOptics * spec.pulseFreq + spec.pulsePhase)
      val wave2 = 0.5f + 0.5f * sin(tOptics * spec.pulseFreq * 0.63f + spec.pulsePhase * 1.75f)
      val envelope = 0.58f + 0.62f * (wave1 * wave1 * 0.65f + wave2 * 0.35f)
      val depthDim = if (clipPass == 0) 0.74f else 1.00f
      val finalIntensity =
        envelope * spec.intensityGain * config.energyIntensity * depthDim * centerGuard * (0.86f + 0.18f * config.overallIntensity)

      val col = if (spec.isGold) goldColor else cyanColor
      val size = spec.baseSize * (0.84f + 0.24f * envelope) * (0.45f + 0.55f * config.coreScale)
      val spinAngle = (tOptics * 0.22f + spec.pulsePhase) % (2f * PI.toFloat())

      prog.setVec3("uWorldCenter", worldPt.x, worldPt.y, worldPt.z)
      prog.setFloat("uSize", size)
      prog.setVec3("uFlareColor", col)
      prog.setFloat("uIntensity", finalIntensity)
      prog.setFloat("uAngle", spinAngle)

      mesh.draw()
    }

    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    GLES30.glDepthMask(true)
  }

  fun release() {
    program?.release()
    program = null
    quad?.release()
    quad = null
  }

  companion object {
    /**
     * Computes unified radial center attenuation shared identically by front (`clipPass == 1`)
     * and back (`clipPass == 0`) highlight passes. Attenuates flares crossing the core interior
     * to near-zero while preserving full gold & ice-blue sparkles along outer orbital arcs.
     */
    fun computeCenterGuard(xyRadiusFromCenter: Float, scaledOrbRadius: Float): Float {
      val safeRadius = scaledOrbRadius.coerceAtLeast(0.4f)
      val normR = xyRadiusFromCenter / safeRadius
      val t = ((normR - 0.76f) / 0.38f).coerceIn(0f, 1f)
      val smooth = t * t * (3f - 2f * t)
      return 0.015f + 0.985f * smooth
    }
  }
}
