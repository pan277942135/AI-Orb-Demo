package com.example.orb.task

import kotlin.math.floor

/**
 * Precomputed uniform-arc-length lookup table over [PerimeterPathGeometry] for both LEFT and RIGHT
 * perimeter branches, enabling O(1) deterministic sampling for GPU ribbon mesh construction,
 * droplet anchoring, and frontier queries.
 */
class PerimeterArcLengthTable(
  val geometry: PerimeterPathGeometry,
  val sampleCountPerSide: Int = DEFAULT_SAMPLES,
) {
  private val leftSamples: Array<PathSample>
  private val rightSamples: Array<PathSample>

  val totalHalfLengthPx: Float get() = geometry.totalHalfLengthPx

  init {
    val n = sampleCountPerSide.coerceAtLeast(32)
    leftSamples = Array(n) { i ->
      val frac = i.toFloat() / (n - 1).toFloat()
      geometry.sampleByFraction(PerimeterSide.LEFT, frac)
    }
    rightSamples = Array(n) { i ->
      val frac = i.toFloat() / (n - 1).toFloat()
      geometry.sampleByFraction(PerimeterSide.RIGHT, frac)
    }
  }

  /** Fast O(1) interpolated uniform-arc-length lookup at normalized fraction `u in [0, 1]`. */
  fun sample(side: PerimeterSide, fraction: Float): PathSample {
    val arr = if (side == PerimeterSide.LEFT) leftSamples else rightSamples
    val u = fraction.coerceIn(0f, 1f)
    val scaled = u * (arr.size - 1)
    val i0 = floor(scaled).toInt().coerceIn(0, arr.size - 2)
    val i1 = i0 + 1
    val t = (scaled - i0).coerceIn(0f, 1f)
    val a = arr[i0]
    val b = arr[i1]
    return PathSample(
      positionPx = Vec2.lerp(a.positionPx, b.positionPx, t),
      tangent = Vec2.lerp(a.tangent, b.tangent, t).normalized(),
      outwardNormal = Vec2.lerp(a.outwardNormal, b.outwardNormal, t).normalized(),
      arcLengthPx = a.arcLengthPx + (b.arcLengthPx - a.arcLengthPx) * t,
      segment = if (t < 0.5f) a.segment else b.segment,
    )
  }

  fun rawSamples(side: PerimeterSide): Array<PathSample> =
    if (side == PerimeterSide.LEFT) leftSamples else rightSamples

  companion object {
    const val DEFAULT_SAMPLES = 220
  }
}
