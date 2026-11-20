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
  val baseU: Float,
  val travelSpeed: Float,
  val pulseFreq: Float,
  val pulsePhase: Float,
  val baseSize: Float,
  val isGold: Boolean,
)

/**
 * Renders 6 curated, jewel-like HDR pinpoint starburst highlights gliding along the outer
 * orbital ribbon arcs. Automatically attenuates any flare crossing directly over the center
 * of the crystal glass sphere so the water interior & caustics never wash out.
 */
class AIOrbEnergyHighlights {
  private var program: GlShaderProgram? = null
  private var quad: GlMesh? = null
  private val viewPosTemp = FloatArray(4)
  private val worldPosTemp = FloatArray(4)

  // Curated 6 jewel-like outer-arc sparkles (compact 0.22..0.32 scale — zero oversized white circles!)
  private val flares: List<FlareSpec> = listOf(
    FlareSpec(0, baseU = 0.08f, travelSpeed = 0.072f, pulseFreq = 0.82f, pulsePhase = 0.3f, baseSize = 0.28f, isGold = false),
    FlareSpec(0, baseU = 0.56f, travelSpeed = 0.072f, pulseFreq = 0.76f, pulsePhase = 2.0f, baseSize = 0.31f, isGold = true),
    FlareSpec(2, baseU = 0.20f, travelSpeed = -0.084f, pulseFreq = 0.88f, pulsePhase = 0.9f, baseSize = 0.32f, isGold = true),
    FlareSpec(2, baseU = 0.72f, travelSpeed = -0.084f, pulseFreq = 0.80f, pulsePhase = 3.5f, baseSize = 0.24f, isGold = true),
    FlareSpec(3, baseU = 0.34f, travelSpeed = 0.095f, pulseFreq = 0.92f, pulsePhase = 2.5f, baseSize = 0.25f, isGold = false),
    FlareSpec(5, baseU = 0.14f, travelSpeed = 0.068f, pulseFreq = 0.74f, pulsePhase = 4.2f, baseSize = 0.26f, isGold = true),
  )

  private val goldColor = floatArrayOf(1.38f, 0.72f, 0.10f)
  private val cyanColor = floatArrayOf(0.18f, 0.88f, 1.24f)

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

    for (spec in flares) {
      if (spec.ribbonIndex >= activeRibbons) continue

      val rawU = spec.baseU + tRibbon * spec.travelSpeed
      val u = ((rawU % 1f) + 1f) % 1f

      val worldPt = ribbons.evaluateWorldPoint(spec.ribbonIndex, u, motion, config)
      worldPosTemp[0] = worldPt.x
      worldPosTemp[1] = worldPt.y
      worldPosTemp[2] = worldPt.z
      worldPosTemp[3] = 1f
      Matrix.multiplyMV(viewPosTemp, 0, viewMatrix, 0, worldPosTemp, 0)
      val viewZ = viewPosTemp[2]

      if (clipPass == 0 && viewZ >= orbCenterViewZ) continue
      if (clipPass == 1 && viewZ < orbCenterViewZ) continue

      // Attenuate any front flare passing right across the middle of the crystal sphere so interior clarity stays crisp!
      val xyRadiusFromOrbCenter = sqrt(viewPosTemp[0] * viewPosTemp[0] + viewPosTemp[1] * viewPosTemp[1])
      val orbRadius = config.coreScale.coerceAtLeast(0.5f)
      val centerGuard = if (clipPass == 1) {
        val t = ((xyRadiusFromOrbCenter / orbRadius - 0.55f) / 0.45f).coerceIn(0f, 1f)
        0.22f + 0.78f * (t * t * (3f - 2f * t))
      } else {
        1.0f
      }

      val wave1 = 0.5f + 0.5f * sin(tOptics * spec.pulseFreq + spec.pulsePhase)
      val wave2 = 0.5f + 0.5f * sin(tOptics * spec.pulseFreq * 0.61f + spec.pulsePhase * 1.85f)
      val envelope = 0.42f + 0.68f * (wave1 * wave1 * 0.68f + wave2 * 0.32f)
      val depthDim = if (clipPass == 0) 0.68f else 1.05f
      val finalIntensity = envelope * config.energyIntensity * depthDim * centerGuard * (0.82f + 0.20f * config.overallIntensity)

      val col = if (spec.isGold) goldColor else cyanColor
      val size = spec.baseSize * (0.82f + 0.28f * envelope) * (0.45f + 0.55f * config.coreScale)
      val spinAngle = (tOptics * 0.24f + spec.pulsePhase) % (2f * PI.toFloat())

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
}
