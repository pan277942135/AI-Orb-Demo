package com.example.orb.scene

import com.example.orb.geometry.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin reference implementation mirroring the exact mathematical primitives
 * executed inside `CoreFluidShaders.kt`:
 * - Safe `invSmoothstep` (strictly `lo < hi`, eliminating GLSL ES 3.00 §8.3 UB)
 * - 3D rotational advection + differential azimuthal swirl + divergence-free curl vortex
 * - Sculpted asymmetric curved 3D meniscus surface & bisection-first + signed-secant solver
 * - 3D Voronoi cellular edge underwater caustics (`d2 - d1`)
 * - Beer-Lambert optical spectral transmittance
 */
object AIOrbFluidMath {

  fun smoothstepSafe(lo: Float, hi: Float, x: Float): Float {
    require(lo < hi) { "smoothstepSafe requires lo < hi, got lo=$lo, hi=$hi" }
    val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  fun invSmoothstep(lo: Float, hi: Float, x: Float): Float = 1f - smoothstepSafe(lo, hi, x)

  /**
   * Transforms an interior sphere point [sp] into the rotating & differentially swirling
   * 3D fluid vortex coordinate frame (exact counterpart of GLSL `advectFluidPos`).
   */
  fun advectFluidPos(sp: Vec3, coreRotMat3: FloatArray, fluidPhase: Float): Vec3 {
    val rotated = AIOrbMotionController.mulMat3Vec3(coreRotMat3, sp)

    val rXZ = sqrt(rotated.x * rotated.x + rotated.z * rotated.z)
    val twistAngle = (1.0f - rXZ.coerceIn(0f, 1f)) * 0.92f +
      0.26f * sin(rotated.y * 2.5f - fluidPhase * 0.58f)
    val ct = cos(twistAngle)
    val st = sin(twistAngle)
    val swirled = Vec3(
      rotated.x * ct - rotated.z * st,
      rotated.y,
      rotated.x * st + rotated.z * ct,
    )

    val freq = 2.65f
    val curl = Vec3(
      sin(swirled.y * freq + fluidPhase * 0.62f) * cos(swirled.z * freq - fluidPhase * 0.44f),
      sin(swirled.z * freq - fluidPhase * 0.50f) * cos(swirled.x * freq + fluidPhase * 0.48f),
      sin(swirled.x * freq + fluidPhase * 0.56f) * cos(swirled.y * freq - fluidPhase * 0.38f),
    ) * 0.15f

    return swirled + curl
  }

  /**
   * Evaluates the sculpted, asymmetric, gracefully curved 3D liquid meniscus surface
   * inside the unit crystal sphere (matches GLSL `meniscusHeight`).
   * Dips organically in the center-right and rises naturally toward the curved glass walls,
   * eliminating the flat horizontal band artifact.
   */
  fun meniscusHeight(x: Float, z: Float, meniscusPhase: Float): Float {
    val c = cos(meniscusPhase * 0.38f)
    val s = sin(meniscusPhase * 0.38f)
    val rx = x * c - z * s
    val rz = x * s + z * c

    val r2 = x * x + z * z
    val bowl = 0.24f * r2 - 0.11f * x + 0.055f * z
    val swell = 0.072f * sin(rx * 2.8f + meniscusPhase * 0.62f + rz * 1.4f)
    val crossWave = 0.044f * cos(rz * 3.6f - meniscusPhase * 0.54f - rx * 1.9f)
    val capillary = 0.020f * sin((rx * 1.3f - rz * 1.1f) * 4.8f + meniscusPhase * 0.92f)
    return 0.02f + bowl + swell + crossWave + capillary
  }

  data class MeniscusHitResult(
    val hit: Boolean,
    val tHit: Float,
    val hitPos: Vec3,
    val residualError: Float,
    val crossingDownward: Boolean,
  )

  /**
   * Exact bracketed raymarch + 3-step binary bisection + final signed secant interpolation
   * (exact counterpart of the meniscus solver in `CORE_GLASS_FRAG`).
   */
  fun findMeniscusIntersection(
    rayOrg: Vec3,
    rayDir: Vec3,
    tExit: Float,
    steps: Int,
    meniscusPhase: Float,
  ): MeniscusHitResult {
    val safeSteps = steps.coerceAtLeast(4)
    val stepLen = tExit / safeSteps.toFloat()
    var prevT = 0f
    var prevDiff = rayOrg.y - meniscusHeight(rayOrg.x, rayOrg.z, meniscusPhase)

    for (i in 1..safeSteps) {
      val currT = i.toFloat() * stepLen
      val sp = rayOrg + rayDir * currT
      val currDiff = sp.y - meniscusHeight(sp.x, sp.z, meniscusPhase)

      val crossedDown = prevDiff > 0f && currDiff <= 0f
      val crossedUp = prevDiff < 0f && currDiff >= 0f
      if (crossedDown || crossedUp) {
        var tLo = prevT
        var dLo = prevDiff
        var tHi = currT
        var dHi = currDiff

        repeat(3) {
          val tMid = 0.5f * (tLo + tHi)
          val pMid = rayOrg + rayDir * tMid
          val dMid = pMid.y - meniscusHeight(pMid.x, pMid.z, meniscusPhase)
          if ((dLo > 0f && dMid > 0f) || (dLo < 0f && dMid < 0f)) {
            tLo = tMid
            dLo = dMid
          } else {
            tHi = tMid
            dHi = dMid
          }
        }

        val denom = dLo - dHi
        val tHit = if (abs(denom) > 1e-6f) {
          tLo + (dLo / denom).coerceIn(0f, 1f) * (tHi - tLo)
        } else {
          0.5f * (tLo + tHi)
        }

        val finalPos = rayOrg + rayDir * tHit
        val residual = abs(finalPos.y - meniscusHeight(finalPos.x, finalPos.z, meniscusPhase))
        return MeniscusHitResult(
          hit = true,
          tHit = tHit,
          hitPos = finalPos,
          residualError = residual,
          crossingDownward = crossedDown,
        )
      }

      prevT = currT
      prevDiff = currDiff
    }

    return MeniscusHitResult(
      hit = false,
      tHit = -1f,
      hitPos = Vec3(0f, 0f, 0f),
      residualError = 0f,
      crossingDownward = false,
    )
  }

  private fun fract(x: Float): Float = x - floor(x)

  private fun hash3(ix: Float, iy: Float, iz: Float): Vec3 {
    val px = fract(sin(ix * 127.1f + iy * 311.7f + iz * 74.7f) * 43758.5453f)
    val py = fract(sin(ix * 269.5f + iy * 183.3f + iz * 246.1f) * 43758.5453f)
    val pz = fract(sin(ix * 113.5f + iy * 271.9f + iz * 124.6f) * 43758.5453f)
    return Vec3(px, py, pz)
  }

  /**
   * 3D Voronoi-edge cellular optical caustic intensity (F2 - F1 ridge extraction),
   * mirroring GLSL `voronoiCausticNet` for deterministic JVM motion/continuity verification.
   */
  fun voronoiCausticNet(p: Vec3, phase: Float): Float {
    val ix = floor(p.x)
    val iy = floor(p.y)
    val iz = floor(p.z)
    val fx = p.x - ix
    val fy = p.y - iy
    val fz = p.z - iz

    var d1 = 8.0f
    var d2 = 8.0f

    for (dz in -1..1) {
      for (dy in -1..1) {
        for (dx in -1..1) {
          val h = hash3(ix + dx, iy + dy, iz + dz)
          val ox = dx + 0.5f + 0.36f * sin(phase * 0.55f + h.x * 6.28318f) - fx
          val oy = dy + 0.5f + 0.36f * sin(phase * 0.48f + h.y * 6.28318f) - fy
          val oz = dz + 0.5f + 0.36f * sin(phase * 0.52f + h.z * 6.28318f) - fz
          val dist = sqrt(ox * ox + oy * oy + oz * oz)
          if (dist < d1) {
            d2 = d1
            d1 = dist
          } else if (dist < d2) {
            d2 = dist
          }
        }
      }
    }

    val edge = (d2 - d1).coerceAtLeast(0f)
    return exp(-edge * 13.5f)
  }

  /**
   * Computes Beer-Lambert optical transmittance and verifies spectral water absorption hierarchy.
   */
  fun beerLambertTransmittance(liquidPath: Float, transparency: Float): Vec3 {
    val density = (1.35f - transparency * 0.48f).coerceAtLeast(0.18f)
    return Vec3(
      exp(-liquidPath * 2.15f * density),
      exp(-liquidPath * 0.72f * density),
      exp(-liquidPath * 0.36f * density),
    )
  }
}
