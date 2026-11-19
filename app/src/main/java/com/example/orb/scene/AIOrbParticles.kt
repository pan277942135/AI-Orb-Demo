package com.example.orb.scene

import android.opengl.GLES30
import android.opengl.Matrix
import com.example.orb.config.AIOrbConfig
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import com.example.orb.gl.RibbonOpticsShaders
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class OrbParticle(
  val isBubble: Boolean,
  val baseRadius: Float,
  val baseTheta: Float,
  val basePhi: Float,
  val orbitSpeed: Float,
  val driftFreq: Float,
  val driftAmp: Float,
  val size: Float,
  val phase: Float,
  val tint: FloatArray,
  val baseAlpha: Float,
)

/**
 * Renders crisp 3D ray-shaded refractive glass water droplets (bubbles) and
 * sparse suspended luminous micro-motes tightly distributed within the active framing zone.
 */
class AIOrbParticles {
  private var program: GlShaderProgram? = null
  private var quad: GlMesh? = null
  private val particles: List<OrbParticle> = generateParticles()
  private val worldTemp = FloatArray(4)
  private val viewTemp = FloatArray(4)

  fun init() {
    release()
    program = GlShaderProgram(
      RibbonOpticsShaders.BUBBLE_PARTICLE_VERT,
      RibbonOpticsShaders.BUBBLE_PARTICLE_FRAG,
      "AIOrbParticles",
    )
    quad = OrbGeometryBuilder.createCenteredBillboardQuad()
  }

  fun drawPass(
    clipPass: Int,
    orbCenterViewZ: Float,
    viewMatrix: FloatArray,
    projMatrix: FloatArray,
    time: Float,
    hdrScale: Float,
    config: AIOrbConfig,
  ) {
    if (config.particleDensity <= 0.01f) return
    val prog = program ?: return
    val mesh = quad ?: return

    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glEnable(GLES30.GL_BLEND)

    prog.use()
    prog.setMat4("uView", viewMatrix)
    prog.setMat4("uProj", projMatrix)
    prog.setFloat("uHdrScale", hdrScale)

    val activeCount = ((particles.size * (config.particleDensity / 1.5f)).toInt())
      .coerceIn(0, particles.size)
    val scaleRadial = 0.35f + 0.65f * config.coreScale

    for (i in 0 until activeCount) {
      val p = particles[i]
      val theta = p.baseTheta + time * p.orbitSpeed
      val phi = p.basePhi + sin(time * p.driftFreq + p.phase) * p.driftAmp
      val r = (p.baseRadius + cos(time * p.driftFreq * 0.72f + p.phase * 1.4f) * (p.driftAmp * 0.35f)) * scaleRadial

      val sinPhi = sin(phi)
      val wx = r * sinPhi * cos(theta)
      val wy = r * cos(phi) + sin(time * 0.34f + p.phase) * 0.06f
      val wz = r * sinPhi * sin(theta)

      worldTemp[0] = wx
      worldTemp[1] = wy
      worldTemp[2] = wz
      worldTemp[3] = 1f
      Matrix.multiplyMV(viewTemp, 0, viewMatrix, 0, worldTemp, 0)
      val viewZ = viewTemp[2]

      if (clipPass == 0 && viewZ >= orbCenterViewZ) continue
      if (clipPass == 1 && viewZ < orbCenterViewZ) continue

      if (p.isBubble) {
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
      } else {
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
      }

      val breatheAlpha = p.baseAlpha * (0.82f + 0.18f * sin(time * 0.62f + p.phase))
      val depthAtten = if (clipPass == 0) 0.74f else 1.0f

      prog.setVec3("uWorldCenter", wx, wy, wz)
      prog.setFloat("uSize", p.size)
      prog.setInt("uMode", if (p.isBubble) 0 else 1)
      prog.setVec3("uTint", p.tint)
      prog.setFloat("uAlpha", breatheAlpha * depthAtten * (0.80f + 0.22f * config.overallIntensity))

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
    private fun generateParticles(): List<OrbParticle> {
      val rng = Random(20261119)
      val list = ArrayList<OrbParticle>(54)

      val tints = arrayOf(
        floatArrayOf(0.45f, 0.94f, 1.05f), // Crystal Ice-Cyan
        floatArrayOf(1.15f, 0.82f, 0.34f), // Champagne Gold
        floatArrayOf(0.44f, 0.86f, 0.48f), // Emerald Mint
        floatArrayOf(0.92f, 0.98f, 1.00f), // Pure Specular White
      )

      // 18 hero 3D crystal water droplets tightly orbiting inside visible framing zone (1.16..1.58)
      repeat(18) { idx ->
        val radius = when (idx % 3) {
          0 -> 1.16f + rng.nextFloat() * 0.18f
          1 -> 1.32f + rng.nextFloat() * 0.18f
          else -> 1.44f + rng.nextFloat() * 0.16f
        }
        val size = when {
          idx < 6 -> 0.105f + rng.nextFloat() * 0.036f  // Crisp prominent crystal water drops
          idx < 13 -> 0.068f + rng.nextFloat() * 0.032f // Medium bubbles
          else -> 0.044f + rng.nextFloat() * 0.022f     // Small accent droplets
        }
        val dir = if (idx % 2 == 0) 1f else -1f
        list.add(
          OrbParticle(
            isBubble = true,
            baseRadius = radius,
            baseTheta = (idx.toFloat() / 18f) * (2f * PI.toFloat()) + rng.nextFloat() * 0.25f,
            basePhi = 0.45f + rng.nextFloat() * (PI.toFloat() - 0.90f),
            orbitSpeed = dir * (0.042f + rng.nextFloat() * 0.068f),
            driftFreq = 0.22f + rng.nextFloat() * 0.30f,
            driftAmp = 0.06f + rng.nextFloat() * 0.10f,
            size = size,
            phase = rng.nextFloat() * (2f * PI.toFloat()),
            tint = tints[idx % tints.size],
            baseAlpha = 0.88f + rng.nextFloat() * 0.12f,
          )
        )
      }

      // 36 sparse, calm floating luminous micro-motes inside active depth space
      repeat(36) { idx ->
        val radius = 1.14f + rng.nextFloat() * 0.62f
        val dir = if (idx % 3 == 0) -1f else 1f
        list.add(
          OrbParticle(
            isBubble = false,
            baseRadius = radius,
            baseTheta = rng.nextFloat() * (2f * PI.toFloat()),
            basePhi = 0.32f + rng.nextFloat() * (PI.toFloat() - 0.64f),
            orbitSpeed = dir * (0.024f + rng.nextFloat() * 0.048f),
            driftFreq = 0.18f + rng.nextFloat() * 0.26f,
            driftAmp = 0.08f + rng.nextFloat() * 0.12f,
            size = 0.022f + rng.nextFloat() * 0.024f,
            phase = rng.nextFloat() * (2f * PI.toFloat()),
            tint = tints[(idx + 1) % tints.size],
            baseAlpha = 0.42f + rng.nextFloat() * 0.40f,
          )
        )
      }

      return list
    }
  }
}
