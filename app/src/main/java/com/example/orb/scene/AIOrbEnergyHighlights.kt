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
 * Renders vibrant HDR travelling optical starburst highlights moving continuously along
 * the active 3D ribbon curves. Alternates fiery amber-gold and crisp ice-cyan glints with
 * smooth organic fade-in, peak burst, and decay (strictly synchronized with ribbon motion).
 */
class AIOrbEnergyHighlights {
  private var program: GlShaderProgram? = null
  private var quad: GlMesh? = null
  private val viewPosTemp = FloatArray(4)
  private val worldPosTemp = FloatArray(4)

  private val flares: List<FlareSpec> = listOf(
    // Primary hero crystal ribbon — bold ice-cyan & fiery amber-gold starbursts
    FlareSpec(0, baseU = 0.06f, travelSpeed = 0.088f, pulseFreq = 0.85f, pulsePhase = 0.3f, baseSize = 0.56f, isGold = false),
    FlareSpec(0, baseU = 0.54f, travelSpeed = 0.088f, pulseFreq = 0.78f, pulsePhase = 2.0f, baseSize = 0.62f, isGold = true),
    // Upper champagne-amber ribbon — warm sunlit golden bursts
    FlareSpec(2, baseU = 0.18f, travelSpeed = -0.098f, pulseFreq = 0.90f, pulsePhase = 0.9f, baseSize = 0.64f, isGold = true),
    FlareSpec(2, baseU = 0.68f, travelSpeed = -0.098f, pulseFreq = 0.82f, pulsePhase = 3.5f, baseSize = 0.52f, isGold = true),
    // Counter-tilted ice-blue ribbon — cool electric cyan glints
    FlareSpec(3, baseU = 0.32f, travelSpeed = 0.110f, pulseFreq = 0.94f, pulsePhase = 2.5f, baseSize = 0.48f, isGold = false),
    FlareSpec(3, baseU = 0.82f, travelSpeed = 0.110f, pulseFreq = 0.86f, pulsePhase = 4.9f, baseSize = 0.44f, isGold = true),
    // Outer sweeping ribbon — prominent right-flank amber burst & left ice burst
    FlareSpec(5, baseU = 0.12f, travelSpeed = 0.075f, pulseFreq = 0.74f, pulsePhase = 4.2f, baseSize = 0.58f, isGold = true),
    FlareSpec(5, baseU = 0.62f, travelSpeed = 0.075f, pulseFreq = 0.88f, pulsePhase = 1.4f, baseSize = 0.46f, isGold = false),
    // Gossamer cyan filament — crisp star glint
    FlareSpec(6, baseU = 0.40f, travelSpeed = 0.128f, pulseFreq = 1.02f, pulsePhase = 0.8f, baseSize = 0.40f, isGold = false),
    // Gossamer gold filament — warm golden spark
    FlareSpec(7, baseU = 0.80f, travelSpeed = -0.122f, pulseFreq = 0.98f, pulsePhase = 3.0f, baseSize = 0.44f, isGold = true),
    // Extra ribbons (active when ribbonCount >= 9)
    FlareSpec(8, baseU = 0.48f, travelSpeed = 0.096f, pulseFreq = 0.88f, pulsePhase = 5.1f, baseSize = 0.46f, isGold = false),
    FlareSpec(9, baseU = 0.28f, travelSpeed = -0.112f, pulseFreq = 0.92f, pulsePhase = 2.3f, baseSize = 0.48f, isGold = true),
  )

  private val goldColor = floatArrayOf(1.25f, 0.68f, 0.14f)
  private val cyanColor = floatArrayOf(0.24f, 0.92f, 1.15f)

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
    time: Float,
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
    val t = time * config.ribbonSpeed

    for (spec in flares) {
      if (spec.ribbonIndex >= activeRibbons) continue

      val rawU = spec.baseU + t * spec.travelSpeed
      val u = ((rawU % 1f) + 1f) % 1f

      val worldPt = ribbons.evaluateWorldPoint(spec.ribbonIndex, u, time, config)
      worldPosTemp[0] = worldPt.x
      worldPosTemp[1] = worldPt.y
      worldPosTemp[2] = worldPt.z
      worldPosTemp[3] = 1f
      Matrix.multiplyMV(viewPosTemp, 0, viewMatrix, 0, worldPosTemp, 0)
      val viewZ = viewPosTemp[2]

      if (clipPass == 0 && viewZ >= orbCenterViewZ) continue
      if (clipPass == 1 && viewZ < orbCenterViewZ) continue

      // Smooth fade-in, bright localized flare peak, and natural decay
      val wave1 = 0.5f + 0.5f * sin(time * spec.pulseFreq + spec.pulsePhase)
      val wave2 = 0.5f + 0.5f * sin(time * spec.pulseFreq * 0.61f + spec.pulsePhase * 1.85f)
      val envelope = 0.42f + 0.78f * (wave1 * wave1 * 0.68f + wave2 * 0.32f)
      val depthDim = if (clipPass == 0) 0.72f else 1.28f
      val finalIntensity = envelope * config.energyIntensity * depthDim * (0.80f + 0.25f * config.overallIntensity)

      val col = if (spec.isGold) goldColor else cyanColor
      val size = spec.baseSize * (0.78f + 0.38f * envelope) * (0.40f + 0.60f * config.coreScale)
      val spinAngle = (time * 0.28f + spec.pulsePhase) % (2f * PI.toFloat())

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
