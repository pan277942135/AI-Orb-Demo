package com.example.orb.scene

import com.example.orb.geometry.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin reference implementation mirroring the exact mathematical primitives
 * executed inside `CoreFluidShaders.kt`:
 * - Safe `invSmoothstep` (strictly `lo < hi`, eliminating GLSL ES 3.00 §8.3 UB)
 * - Unified Core Frame (`toCoreFrame = uCoreRotMat * localPos`) for rigid 3D tilted-axis rotation
 * - Advected Frame (`advectFluidPos`) for differential swirl + divergence-free 3D curl fluid detail
 * - Sculpted asymmetric curved 3D meniscus solved in rotated Core Frame via 3-step bisection + secant finish,
 *   with normal transformed back to local ray space via `transpose(uCoreRotMat)`
 * - 3D Flow Domain Warping irregular curved-wave underwater caustics (ZERO Voronoi / honeycomb grids)
 * - Beer-Lambert optical spectral transmittance
 */
object AIOrbFluidMath {

  val IDENTITY_MAT3: FloatArray = floatArrayOf(
    1f, 0f, 0f,
    0f, 1f, 0f,
    0f, 0f, 1f,
  )

  fun smoothstepSafe(lo: Float, hi: Float, x: Float): Float {
    if (!lo.isFinite() || !hi.isFinite() || !x.isFinite()) return 0f
    require(lo < hi) { "smoothstepSafe requires lo < hi, got lo=$lo, hi=$hi" }
    val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  fun inverseSmoothstep(lo: Float, hi: Float, x: Float): Float = 1f - smoothstepSafe(lo, hi, x)

  fun invSmoothstep(lo: Float, hi: Float, x: Float): Float = inverseSmoothstep(lo, hi, x)

  /**
   * Central-difference gradient for scalar samples taken [halfStep] from the center.
   * Components are expressed per normalized Core-space unit. This is the CPU contract
   * mirrored by `estimateFluidFoldNormal()` in the production GLES shader.
   */
  fun centralDifferenceGradient(
    positiveX: Float,
    negativeX: Float,
    positiveYTexture: Float,
    negativeYTexture: Float,
    positiveZ: Float,
    negativeZ: Float,
    halfStep: Vec3,
  ): Vec3 {
    require(halfStep.x.isFinite() && halfStep.x > 0f)
    require(halfStep.y.isFinite() && halfStep.y > 0f)
    require(halfStep.z.isFinite() && halfStep.z > 0f)
    val inverseSpan = Vec3(1f / (2f * halfStep.x), -1f / (2f * halfStep.y), 1f / (2f * halfStep.z))
    return Vec3(
      (positiveX - negativeX) * inverseSpan.x,
      (positiveYTexture - negativeYTexture) * inverseSpan.y,
      (positiveZ - negativeZ) * inverseSpan.z,
    )
  }

  fun normalizedGradientOrFallback(gradient: Vec3, fallback: Vec3): Vec3 =
    if (gradient.x.isFinite() && gradient.y.isFinite() && gradient.z.isFinite() && gradient.length() > 1e-4f) {
      gradient.normalized()
    } else {
      fallback.normalized()
    }

  /**
   * Rigid rotated Core Frame (`coreFramePos = uCoreRotMat * localPos`, NO curl).
   * Drives meniscus tilt/intersection, above/below liquid zoning, and core-locked spatial masks.
   */
  fun toCoreFrame(localPos: Vec3, coreRotMat3: FloatArray): Vec3 =
    AIOrbMotionController.mulMat3Vec3(coreRotMat3, localPos)

  /**
   * Transforms an interior sphere point [sp] from local space through Core Frame into the
   * differentially swirling & divergence-free curl Advected Frame (exact counterpart of GLSL `advectFluidPos`).
   */
  fun advectFluidPos(sp: Vec3, coreRotMat3: FloatArray, fluidPhase: Float): Vec3 {
    val rotated = toCoreFrame(sp, coreRotMat3)

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
   * Evaluates the sculpted, asymmetric, naturally tilted 3D liquid meniscus surface
   * in rotated **Core Frame** coordinates `(coreX, coreZ)` (matches GLSL `meniscusHeight`).
   * Because evaluation happens in Core Frame, the tilted arc rotates continuously with the core.
   */
  fun meniscusHeight(coreX: Float, coreZ: Float, meniscusPhase: Float): Float {
    val c = cos(meniscusPhase * 0.32f)
    val s = sin(meniscusPhase * 0.32f)
    val rx = coreX * c - coreZ * s
    val rz = coreX * s + coreZ * c

    val r2 = coreX * coreX + coreZ * coreZ
    val bowl = 0.25f * r2 - 0.17f * coreX + 0.085f * coreZ
    val swell = 0.075f * sin(rx * 2.6f + meniscusPhase * 0.56f + rz * 1.35f)
    val crossWave = 0.046f * cos(rz * 3.4f - meniscusPhase * 0.48f - rx * 1.8f)
    val capillary = 0.020f * sin((rx * 1.35f - rz * 1.15f) * 4.5f + meniscusPhase * 0.85f)
    return 0.015f + bowl + swell + crossWave + capillary
  }

  /**
   * Computes the meniscus surface normal in Core Frame and transforms it back to local ray space
   * via `transpose(uCoreRotMat)` (exact counterpart of GLSL `transpose(uCoreRotMat) * meniscusNormal(...)`).
   */
  fun meniscusNormalLocal(corePos: Vec3, coreRotMat3: FloatArray, meniscusPhase: Float): Vec3 {
    val e = 0.016f
    val hx = meniscusHeight(corePos.x + e, corePos.z, meniscusPhase) -
      meniscusHeight(corePos.x - e, corePos.z, meniscusPhase)
    val hz = meniscusHeight(corePos.x, corePos.z + e, meniscusPhase) -
      meniscusHeight(corePos.x, corePos.z - e, meniscusPhase)
    val coreNormal = Vec3(-hx, 2f * e, -hz).normalized()
    return AIOrbMotionController.mulMat3TransposeVec3(coreRotMat3, coreNormal).normalized()
  }

  data class MeniscusHitResult(
    val hit: Boolean,
    val tHit: Float,
    val hitPos: Vec3,
    val hitCorePos: Vec3,
    val localNormal: Vec3,
    val residualError: Float,
    val crossingDownward: Boolean,
  )

  /**
   * Exact bracketed raymarch + 3-step binary bisection + final signed secant interpolation
   * executed strictly in rotated **Core Frame** (`cPos = uCoreRotMat * localPos`),
   * matching the meniscus solver in `CORE_GLASS_FRAG`.
   */
  fun findMeniscusIntersection(
    rayOrg: Vec3,
    rayDir: Vec3,
    tExit: Float,
    steps: Int,
    meniscusPhase: Float,
    coreRotMat3: FloatArray = IDENTITY_MAT3,
  ): MeniscusHitResult {
    val safeSteps = steps.coerceAtLeast(4)
    val stepLen = tExit / safeSteps.toFloat()
    var prevT = 0f
    val orgCore = toCoreFrame(rayOrg, coreRotMat3)
    var prevDiff = orgCore.y - meniscusHeight(orgCore.x, orgCore.z, meniscusPhase)

    for (i in 1..safeSteps) {
      val currT = i.toFloat() * stepLen
      val sp = rayOrg + rayDir * currT
      val cSp = toCoreFrame(sp, coreRotMat3)
      val currDiff = cSp.y - meniscusHeight(cSp.x, cSp.z, meniscusPhase)

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
          val cMid = toCoreFrame(pMid, coreRotMat3)
          val dMid = cMid.y - meniscusHeight(cMid.x, cMid.z, meniscusPhase)
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
        val finalCorePos = toCoreFrame(finalPos, coreRotMat3)
        val residual = abs(finalCorePos.y - meniscusHeight(finalCorePos.x, finalCorePos.z, meniscusPhase))
        val localN = meniscusNormalLocal(finalCorePos, coreRotMat3, meniscusPhase) *
          if (crossedDown) 1f else -1f
        return MeniscusHitResult(
          hit = true,
          tHit = tHit,
          hitPos = finalPos,
          hitCorePos = finalCorePos,
          localNormal = localN,
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
      hitCorePos = Vec3(0f, 0f, 0f),
      localNormal = Vec3(0f, 1f, 0f),
      residualError = 0f,
      crossingDownward = false,
    )
  }

  private fun trigFbm3(p: Vec3): Float {
    var valAcc = 0f
    var amp = 0.52f
    var px = p.x
    var py = p.y
    var pz = p.z
    repeat(3) {
      val n = sin(px * 1.13f + cos(py * 1.37f - pz * 0.89f)) *
        cos(pz * 1.21f + sin(px * 0.97f + py * 1.07f))
      valAcc += amp * n
      val nx = px * 2.05f + 11.3f
      val ny = py * 2.05f + 6.7f
      val nz = pz * 2.05f + 4.9f
      px = nx; py = ny; pz = nz
      amp *= 0.48f
    }
    return valAcc
  }

  /**
   * 3D Flow Domain Warping irregular curved-wave optical caustics (ZERO Voronoi / honeycomb grids).
   * Mirrors GLSL `domainWarpCaustics` with multi-scale warped wave interference and an asymmetric
   * lower-interior spatial mask in Core Frame.
   */
  fun domainWarpCaustics(
    advectedPos: Vec3,
    coreFramePos: Vec3,
    meniscusSlopeX: Float,
    meniscusSlopeZ: Float,
    causticsPhase: Float,
  ): Float {
    val wPos = Vec3(
      advectedPos.x * 1.65f + causticsPhase * 0.16f,
      advectedPos.y * 1.65f - causticsPhase * 0.12f,
      advectedPos.z * 1.65f + causticsPhase * 0.14f,
    )
    val warpAX = trigFbm3(wPos)
    val warpAY = trigFbm3(Vec3(wPos.x + 5.2f, wPos.y + 1.3f, wPos.z - 3.7f))
    val warpAZ = trigFbm3(Vec3(wPos.x - 2.8f, wPos.y + 4.6f, wPos.z + 2.9f))

    val warpBX = trigFbm3(
      Vec3(
        wPos.x * 1.85f + warpAX * 1.35f + 1.7f,
        wPos.y * 1.85f + warpAY * 1.35f - 2.4f,
        wPos.z * 1.85f + warpAZ * 1.35f + 3.1f,
      )
    )
    val warpBY = trigFbm3(
      Vec3(
        wPos.x * 1.85f - warpAX * 1.25f - 3.9f,
        wPos.y * 1.85f - warpAY * 1.25f + 2.2f,
        wPos.z * 1.85f - warpAZ * 1.25f + 1.4f,
      )
    )

    val q1x = advectedPos.x * 2.55f + advectedPos.y * 0.85f + meniscusSlopeX * 0.34f +
      warpAX * 1.25f + warpBX * 0.62f
    val q1y = advectedPos.z * 2.55f - advectedPos.y * 0.55f + meniscusSlopeZ * 0.34f +
      warpAY * 1.25f + warpBY * 0.62f

    val w1 = sin(q1x * 2.35f + cos(q1y * 2.05f + warpAZ * 2.4f + causticsPhase * 0.55f)) +
      cos(q1y * 2.65f - sin(q1x * 1.85f - warpBX * 2.1f - causticsPhase * 0.48f))
    val ridge1 = exp(-abs(w1) * 4.2f)

    val q2x = (q1x * 0.68f - q1y * 0.73f) * 1.47f + warpBY * 0.95f
    val q2y = (q1x * 0.73f + q1y * 0.68f) * 1.47f + warpBX * 0.95f
    val w2 = sin(q2x * 2.55f - sin(q2y * 2.25f + warpAX * 2.6f - causticsPhase * 0.64f)) +
      cos(q2y * 2.15f + cos(q2x * 2.40f + warpBY * 2.0f + causticsPhase * 0.52f))
    val ridge2 = exp(-abs(w2) * 4.8f)

    val q3x = (q1x * -0.44f + q1y * 0.90f) * 2.13f + warpAZ * 0.85f
    val q3y = (q1x * -0.90f - q1y * 0.44f) * 2.13f + warpAY * 0.85f
    val w3 = sin(q3x * 2.10f + cos(q3y * 2.45f + causticsPhase * 0.78f)) -
      cos(q3y * 2.30f + sin(q3x * 1.90f - causticsPhase * 0.62f))
    val ridge3 = exp(-abs(w3) * 5.6f)

    val breakMask = smoothstepSafe(-0.32f, 0.58f, warpAX * 0.65f + warpBY * 0.55f)
    val convergence = Math.pow((ridge1 * ridge2 * 2.6f).coerceIn(0f, 1f).toDouble(), 1.4).toFloat()
    val rawVeins = (ridge1 * 0.68f + ridge2 * 0.52f + ridge3 * 0.34f + convergence * 0.95f) *
      (0.35f + 0.85f * breakMask)

    val lowerPocket = invSmoothstep(-0.88f, 0.08f, coreFramePos.y)
    val asymPocket = smoothstepSafe(-0.45f, 0.62f, coreFramePos.x * 0.45f - coreFramePos.y * 0.75f + warpAZ * 0.48f)
    return (rawVeins * lowerPocket * (0.28f + 0.72f * asymPocket)).coerceIn(0f, 2.0f)
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

  /** Per-channel Beer-Lambert attenuation for one sampled volume segment (GLSL parity: exp(-sigma)). */
  fun volumeStepTransmittance(linearDensity: Float, densityScale: Float, segmentLength: Float): Float {
    val sigma = linearDensity.coerceAtLeast(0f) * densityScale.coerceAtLeast(0f) * segmentLength.coerceAtLeast(0f)
    return exp(-sigma)
  }

  /** Per-channel Beer-Lambert transmittance for one voxel segment (matches GLSL vector exp). */
  fun volumeStepSpectralTransmittance(
    linearDensity: Float,
    densityScale: Float,
    segmentLength: Float,
    absorption: Vec3,
  ): Vec3 {
    val opticalPath = linearDensity.coerceAtLeast(0f) * densityScale.coerceAtLeast(0f) * segmentLength.coerceAtLeast(0f)
    return Vec3(
      exp(-absorption.x.coerceAtLeast(0f) * opticalPath),
      exp(-absorption.y.coerceAtLeast(0f) * opticalPath),
      exp(-absorption.z.coerceAtLeast(0f) * opticalPath),
    )
  }
}
