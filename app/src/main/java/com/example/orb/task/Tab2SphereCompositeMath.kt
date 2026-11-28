package com.example.orb.task

import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin mathematical mirror of `Tab2WaterWorldSceneRenderer.SPHERE_COMPOSITE_FRAG`
 * + OpenGL premultiplied Source-Over blend (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`).
 *
 * Guarantees the strict premultiplied alpha invariant `0 <= premulRgb <= alpha <= 1` everywhere:
 * - Inside crystal sphere (`sphereAlpha == 1.0`): `premulRgb == c`, `alpha == 1.0` ->
 *   `dst == c` (100% exact Tab 3 Core 3D, 4 fish, plants, rocks, caustics, glass fidelity!).
 * - Outside crystal sphere (`sphereAlpha == 0.0`): soft, translucent Source-Over fusion of the
 *   5 orbiting Resident filaments and 9 outer bubbles onto the bright cyan underwater background,
 *   strictly preventing additive over-brightening (`premul > alpha`) and harsh highlight clipping!
 * - Pure exterior (`peak <= OUTER_PEAK_MIN`): `alpha == 0.0` (discarded) -> background untouched.
 */
object Tab2SphereCompositeMath {

  const val OUTER_PEAK_MIN = 0.02f
  const val OUTER_PEAK_MAX = 0.72f
  const val OUTER_ALPHA_CAP = 0.72f

  data class PremulSample(
    val r: Float,
    val g: Float,
    val b: Float,
    val alpha: Float,
    val discarded: Boolean,
  ) {
    /** Verifies strict premultiplied Source-Over invariant `0 <= rgb <= alpha <= 1`. */
    fun satisfiesPremulInvariant(eps: Float = 1e-4f): Boolean {
      if (discarded) return true
      return alpha in 0f..(1f + eps) &&
        r in -eps..(alpha + eps) &&
        g in -eps..(alpha + eps) &&
        b in -eps..(alpha + eps)
    }
  }

  private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / max(edge1 - edge0, 1e-5f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  private fun mix(a: Float, b: Float, t: Float): Float = a * (1f - t) + b * t

  /**
   * Evaluates the exact premultiplied `(r, g, b, alpha)` emitted by `SPHERE_COMPOSITE_FRAG`.
   */
  fun evaluateShaderOutput(
    srcR: Float,
    srcG: Float,
    srcB: Float,
    sphereAlpha: Float,
    edgeFade: Float,
  ): PremulSample {
    val cr = srcR.coerceIn(0f, 1f)
    val cg = srcG.coerceIn(0f, 1f)
    val cb = srcB.coerceIn(0f, 1f)
    val sA = sphereAlpha.coerceIn(0f, 1f)
    val eF = edgeFade.coerceIn(0f, 1f)

    val peak = max(cr, max(cg, cb))
    val outerCoverage = smoothstep(OUTER_PEAK_MIN, OUTER_PEAK_MAX, peak) * OUTER_ALPHA_CAP * eF
    val safePeak = max(peak, 1e-4f)
    val chromaScale = mix(0.76f, 0.96f, smoothstep(0.15f, 0.95f, peak))
    val outerStraightR = (cr / safePeak) * chromaScale
    val outerStraightG = (cg / safePeak) * chromaScale
    val outerStraightB = (cb / safePeak) * chromaScale

    val outerPremulR = outerStraightR * outerCoverage
    val outerPremulG = outerStraightG * outerCoverage
    val outerPremulB = outerStraightB * outerCoverage

    val outAlpha = mix(outerCoverage, 1f, sA)
    if (outAlpha < 0.002f) {
      return PremulSample(0f, 0f, 0f, 0f, discarded = true)
    }
    val outR = mix(outerPremulR, cr, sA)
    val outG = mix(outerPremulG, cg, sA)
    val outB = mix(outerPremulB, cb, sA)
    return PremulSample(outR, outG, outB, outAlpha, discarded = false)
  }

  /**
   * Applies OpenGL `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` onto background `(bgR, bgG, bgB)`.
   */
  fun blendPremulOverBackground(
    sample: PremulSample,
    bgR: Float,
    bgG: Float,
    bgB: Float,
  ): PhotoRgb {
    if (sample.discarded) return PhotoRgb(bgR, bgG, bgB)
    val invA = 1f - sample.alpha
    return PhotoRgb(
      min(1f, max(0f, sample.r + bgR * invA)),
      min(1f, max(0f, sample.g + bgG * invA)),
      min(1f, max(0f, sample.b + bgB * invA)),
    )
  }
}
