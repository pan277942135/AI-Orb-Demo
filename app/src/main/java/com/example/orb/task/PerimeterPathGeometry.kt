package com.example.orb.task

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class PerimeterSegment {
  BOTTOM_HALF_STRAIGHT,
  BOTTOM_QUARTER_ARC,
  VERTICAL_STRAIGHT,
  TOP_QUARTER_ARC,
  TOP_HALF_STRAIGHT,
}

enum class PerimeterSide {
  LEFT,
  RIGHT,
}

/**
 * Unified perimeter curve sample in GPU Surface physical pixels (origin = top-left, +X right, +Y down).
 * Shared identically by L1 ice-blue body, L2 emerald kelp veil, L3 champagne gold filament,
 * L4 crystal water droplets, and Task Energy propagation fronts.
 */
data class PathSample(
  val positionPx: Vec2,
  val tangent: Vec2,
  val outwardNormal: Vec2,
  val arcLengthPx: Float,
  val segment: PerimeterSegment,
)

/**
 * Continuous analytical rounded-rectangle perimeter path geometry for bidirectional propagation:
 *
 *  - LEFT:  BOTTOM_CENTER -> LEFT_BOTTOM_ARC -> LEFT_SIDE -> LEFT_TOP_ARC -> TOP_CENTER
 *  - RIGHT: BOTTOM_CENTER -> RIGHT_BOTTOM_ARC -> RIGHT_SIDE -> RIGHT_TOP_ARC -> TOP_CENTER
 *
 * Exact segment arc lengths:
 *   L0 = W/2 - r   (BOTTOM_HALF_STRAIGHT)
 *   L1 = PI * r/2  (BOTTOM_QUARTER_ARC)
 *   L2 = H - 2*r   (VERTICAL_STRAIGHT)
 *   L3 = PI * r/2  (TOP_QUARTER_ARC)
 *   L4 = W/2 - r   (TOP_HALF_STRAIGHT)
 *   Total L = L0 + L1 + L2 + L3 + L4
 *
 * Guarantees C1 tangent continuity across all 4 rounded corners, strictly outward unit normals,
 * zero NaN, zero zero-length segments, and exact arrival at TOP_CENTER when progress == 1.0.
 */
class PerimeterPathGeometry(
  val photo: PhotoFrameGeometry,
) {
  val widthPx: Float = photo.widthPx.coerceAtLeast(16f)
  val heightPx: Float = photo.heightPx.coerceAtLeast(16f)
  val cornerRadiusPx: Float = photo.clampedCornerRadiusPx

  val l0BottomHalf: Float = (widthPx * 0.5f - cornerRadiusPx).coerceAtLeast(1f)
  val l1BottomArc: Float = (PI.toFloat() * cornerRadiusPx * 0.5f).coerceAtLeast(1f)
  val l2Vertical: Float = (heightPx - 2f * cornerRadiusPx).coerceAtLeast(1f)
  val l3TopArc: Float = (PI.toFloat() * cornerRadiusPx * 0.5f).coerceAtLeast(1f)
  val l4TopHalf: Float = (widthPx * 0.5f - cornerRadiusPx).coerceAtLeast(1f)

  val totalHalfLengthPx: Float = l0BottomHalf + l1BottomArc + l2Vertical + l3TopArc + l4TopHalf

  // Exact cumulative arc-length boundaries (in px and normalized [0..1])
  val s1BottomCornerStartPx: Float = l0BottomHalf
  val s2BottomCornerEndPx: Float = l0BottomHalf + l1BottomArc
  val s3TopCornerStartPx: Float = s2BottomCornerEndPx + l2Vertical
  val s4TopCornerEndPx: Float = s3TopCornerStartPx + l3TopArc

  val bottomCornerStartFrac: Float = s1BottomCornerStartPx / totalHalfLengthPx
  val bottomCornerEndFrac: Float = s2BottomCornerEndPx / totalHalfLengthPx
  val topCornerStartFrac: Float = s3TopCornerStartPx / totalHalfLengthPx
  val topCornerEndFrac: Float = s4TopCornerEndPx / totalHalfLengthPx

  /** Exact normalized arc-length fraction corresponding to 60% height up the vertical sides (KF04). */
  val kf04SideAscendFrac: Float = (s2BottomCornerEndPx + l2Vertical * 0.60f) / totalHalfLengthPx

  /** Evaluates exact continuous [PathSample] at normalized progress `u in [0, 1]` along [side]. */
  fun sampleByFraction(side: PerimeterSide, fraction: Float): PathSample {
    val s = fraction.coerceIn(0f, 1f) * totalHalfLengthPx
    return sampleByArcLength(side, s)
  }

  /** Evaluates exact continuous [PathSample] at arc length `sPx in [0, totalHalfLengthPx]` along [side]. */
  fun sampleByArcLength(side: PerimeterSide, sPx: Float): PathSample {
    val s = sPx.coerceIn(0f, totalHalfLengthPx)
    val l = photo.leftPx
    val r = photo.rightPx
    val t = photo.topPx
    val b = photo.bottomPx
    val cx = photo.centerX
    val rad = cornerRadiusPx

    return when (side) {
      PerimeterSide.LEFT -> sampleLeft(s, l, t, b, cx, rad)
      PerimeterSide.RIGHT -> sampleRight(s, r, t, b, cx, rad)
    }
  }

  private fun sampleLeft(s: Float, l: Float, t: Float, b: Float, cx: Float, rad: Float): PathSample {
    val halfPi = (PI * 0.5).toFloat()
    return when {
      s <= s1BottomCornerStartPx -> {
        val x = cx - s
        PathSample(
          positionPx = Vec2(x, b),
          tangent = Vec2(-1f, 0f),
          outwardNormal = Vec2(0f, 1f),
          arcLengthPx = s,
          segment = PerimeterSegment.BOTTOM_HALF_STRAIGHT,
        )
      }
      s <= s2BottomCornerEndPx -> {
        // Bottom-left arc: center = (l + rad, b - rad); theta sweeps PI/2 (+Y) -> PI (-X)
        val u = (s - s1BottomCornerStartPx) / l1BottomArc
        val theta = halfPi + u * halfPi
        val cosT = cos(theta)
        val sinT = sin(theta)
        val arcCx = l + rad
        val arcCy = b - rad
        PathSample(
          positionPx = Vec2(arcCx + rad * cosT, arcCy + rad * sinT),
          tangent = Vec2(-sinT, cosT).normalized(),
          outwardNormal = Vec2(cosT, sinT).normalized(),
          arcLengthPx = s,
          segment = PerimeterSegment.BOTTOM_QUARTER_ARC,
        )
      }
      s <= s3TopCornerStartPx -> {
        val dy = s - s2BottomCornerEndPx
        PathSample(
          positionPx = Vec2(l, (b - rad) - dy),
          tangent = Vec2(0f, -1f),
          outwardNormal = Vec2(-1f, 0f),
          arcLengthPx = s,
          segment = PerimeterSegment.VERTICAL_STRAIGHT,
        )
      }
      s <= s4TopCornerEndPx -> {
        // Top-left arc: center = (l + rad, t + rad); theta sweeps PI (-X) -> 3*PI/2 (-Y)
        val u = (s - s3TopCornerStartPx) / l3TopArc
        val theta = PI.toFloat() + u * halfPi
        val cosT = cos(theta)
        val sinT = sin(theta)
        val arcCx = l + rad
        val arcCy = t + rad
        PathSample(
          positionPx = Vec2(arcCx + rad * cosT, arcCy + rad * sinT),
          tangent = Vec2(-sinT, cosT).normalized(),
          outwardNormal = Vec2(cosT, sinT).normalized(),
          arcLengthPx = s,
          segment = PerimeterSegment.TOP_QUARTER_ARC,
        )
      }
      else -> {
        val dx = s - s4TopCornerEndPx
        PathSample(
          positionPx = Vec2((l + rad) + dx, t),
          tangent = Vec2(1f, 0f),
          outwardNormal = Vec2(0f, -1f),
          arcLengthPx = s,
          segment = PerimeterSegment.TOP_HALF_STRAIGHT,
        )
      }
    }
  }

  private fun sampleRight(s: Float, r: Float, t: Float, b: Float, cx: Float, rad: Float): PathSample {
    val halfPi = (PI * 0.5).toFloat()
    return when {
      s <= s1BottomCornerStartPx -> {
        val x = cx + s
        PathSample(
          positionPx = Vec2(x, b),
          tangent = Vec2(1f, 0f),
          outwardNormal = Vec2(0f, 1f),
          arcLengthPx = s,
          segment = PerimeterSegment.BOTTOM_HALF_STRAIGHT,
        )
      }
      s <= s2BottomCornerEndPx -> {
        // Bottom-right arc: center = (r - rad, b - rad); theta sweeps PI/2 (+Y) -> 0 (+X)
        val u = (s - s1BottomCornerStartPx) / l1BottomArc
        val theta = halfPi - u * halfPi
        val cosT = cos(theta)
        val sinT = sin(theta)
        val arcCx = r - rad
        val arcCy = b - rad
        PathSample(
          positionPx = Vec2(arcCx + rad * cosT, arcCy + rad * sinT),
          tangent = Vec2(sinT, -cosT).normalized(),
          outwardNormal = Vec2(cosT, sinT).normalized(),
          arcLengthPx = s,
          segment = PerimeterSegment.BOTTOM_QUARTER_ARC,
        )
      }
      s <= s3TopCornerStartPx -> {
        val dy = s - s2BottomCornerEndPx
        PathSample(
          positionPx = Vec2(r, (b - rad) - dy),
          tangent = Vec2(0f, -1f),
          outwardNormal = Vec2(1f, 0f),
          arcLengthPx = s,
          segment = PerimeterSegment.VERTICAL_STRAIGHT,
        )
      }
      s <= s4TopCornerEndPx -> {
        // Top-right arc: center = (r - rad, t + rad); theta sweeps 0 (+X) -> -PI/2 (-Y)
        val u = (s - s3TopCornerStartPx) / l3TopArc
        val theta = -u * halfPi
        val cosT = cos(theta)
        val sinT = sin(theta)
        val arcCx = r - rad
        val arcCy = t + rad
        PathSample(
          positionPx = Vec2(arcCx + rad * cosT, arcCy + rad * sinT),
          tangent = Vec2(sinT, -cosT).normalized(),
          outwardNormal = Vec2(cosT, sinT).normalized(),
          arcLengthPx = s,
          segment = PerimeterSegment.TOP_QUARTER_ARC,
        )
      }
      else -> {
        val dx = s - s4TopCornerEndPx
        PathSample(
          positionPx = Vec2((r - rad) - dx, t),
          tangent = Vec2(-1f, 0f),
          outwardNormal = Vec2(0f, -1f),
          arcLengthPx = s,
          segment = PerimeterSegment.TOP_HALF_STRAIGHT,
        )
      }
    }
  }
}
