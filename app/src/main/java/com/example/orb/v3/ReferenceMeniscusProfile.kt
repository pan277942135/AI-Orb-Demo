package com.example.orb.v3

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Auto-generated from `tools/orb-v3-generator/reference/REFERENCE_VISUAL_REGIONS.json`
 * by `tools/orb-v3-generator/generate_reference_meniscus_profile.py`.
 *
 * DO NOT HAND-EDIT: re-run the generator whenever `REFERENCE_VISUAL_REGIONS.json` changes.
 * Provides the exact 32-sample master crystal-cavity meniscus profile (`uReferenceMeniscusY[32]`)
 * bound into `CORE_GLASS_FRAG` for single-source 3D Cavity SDF evaluation (`cavityPhi`).
 */
object ReferenceMeniscusProfile {
  const val SAMPLE_COUNT: Int = 32
  const val X_MIN: Float = -0.96875f
  const val X_MAX: Float = 0.96875f
  const val MASTER_SHA256: String = "910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122"

  val SAMPLE_X: FloatArray = floatArrayOf(
    -0.9688f, -0.9062f, -0.8438f, -0.7812f, -0.7188f, -0.6562f, -0.5938f, -0.5312f,
    -0.4688f, -0.4062f, -0.3438f, -0.2812f, -0.2188f, -0.1562f, -0.0938f, -0.0312f,
    0.0312f, 0.0938f, 0.1562f, 0.2188f, 0.2812f, 0.3438f, 0.4062f, 0.4688f,
    0.5312f, 0.5938f, 0.6562f, 0.7188f, 0.7812f, 0.8438f, 0.9062f, 0.9688f,
  )

  val HEIGHTS: FloatArray = floatArrayOf(
    0.4476f, 0.4004f, 0.4138f, 0.3838f, 0.3490f, 0.3508f, 0.3391f, 0.2851f,
    0.2606f, 0.2496f, 0.2649f, 0.2986f, 0.3391f, 0.3220f, 0.2956f, 0.2588f,
    0.2226f, 0.1808f, 0.2477f, 0.3183f, 0.3496f, 0.3336f, 0.3183f, 0.2821f,
    0.2336f, 0.2183f, 0.2152f, 0.1649f, 0.1581f, 0.1712f, 0.1524f, 0.1721f,
  )

  /**
   * Pure-Kotlin mirror of GLSL `referenceMeniscusHeight(float x)` using smooth clamped
   * piecewise linear interpolation (zero overshoot) across the 32 master profile samples.
   */
  fun sampleHeight(x: Float): Float {
    val t = ((x - X_MIN) / (X_MAX - X_MIN)).coerceIn(0f, 1f) * (SAMPLE_COUNT - 1)
    val i0 = floor(t).toInt().coerceIn(0, SAMPLE_COUNT - 2)
    val i1 = i0 + 1
    val frac = (t - i0).coerceIn(0f, 1f)
    return HEIGHTS[i0] * (1f - frac) + HEIGHTS[i1] * frac
  }

  /**
   * Pure-Kotlin mirror of GLSL `cavityPhi(vec3 p, float phase)` in Core Frame.
   * Positive values (`phi > 0`) lie in the upper transparent crystal cavity;
   * negative values (`phi < 0`) lie inside the submerged 3D liquid volume.
   */
  fun cavityPhi(x: Float, y: Float, z: Float, phase: Float): Float {
    // 3-tap smooth filter over the 32 master samples eliminates jagged V-notch while honoring master profile
    val baseProfileY = 0.25f * sampleHeight(x - 0.08f) +
      0.50f * sampleHeight(x) +
      0.25f * sampleHeight(x + 0.08f)
    val r2 = x * x + z * z
    val domeDepthDip = 0.14f * z * z - 0.035f * z
    val sideWallClimb = 0.048f * r2 * r2
    val wave = 0.012f * sin(x * 2.4f + phase * 0.45f + z * 1.1f) +
      0.008f * cos(z * 2.8f - phase * 0.36f - x * 1.5f)
    val surfaceY = baseProfileY + domeDepthDip + sideWallClimb + wave
    return y - surfaceY
  }
}
