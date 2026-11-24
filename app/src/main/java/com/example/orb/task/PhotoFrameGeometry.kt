package com.example.orb.task

import kotlin.math.min
import kotlin.math.sqrt

/**
 * 2D screen/pixel vector in unified GPU Surface physical pixel coordinates:
 * origin = top-left (0, 0), +X right, +Y down.
 */
data class Vec2(val x: Float, val y: Float) {
  operator fun plus(o: Vec2): Vec2 = Vec2(x + o.x, y + o.y)
  operator fun minus(o: Vec2): Vec2 = Vec2(x - o.x, y - o.y)
  operator fun times(s: Float): Vec2 = Vec2(x * s, y * s)

  fun length(): Float = sqrt(x * x + y * y)

  fun normalized(): Vec2 {
    val len = length()
    return if (len > 1e-6f) Vec2(x / len, y / len) else Vec2(1f, 0f)
  }

  fun dot(o: Vec2): Float = x * o.x + y * o.y

  fun isFinite(): Boolean = x.isFinite() && y.isFinite()

  companion object {
    fun lerp(a: Vec2, b: Vec2, t: Float): Vec2 = Vec2(
      a.x + (b.x - a.x) * t,
      a.y + (b.y - a.y) * t,
    )
  }
}

/**
 * Real displayed photo rectangle in GLSurfaceView physical pixel coordinates
 * (origin = top-left, +X right, +Y down).
 *
 * Shared as the SINGLE authoritative coordinate source for:
 *  - GPU rounded-rect photo rendering (`PhotoTextureRenderer`)
 *  - Task Energy arrival anchor (`Photo Bottom Center`)
 *  - Unified bidirectional rounded-rectangle arc-length perimeter (`PerimeterPathGeometry`)
 */
data class PhotoFrameGeometry(
  val leftPx: Float,
  val topPx: Float,
  val rightPx: Float,
  val bottomPx: Float,
  val cornerRadiusPx: Float,
) {
  val widthPx: Float get() = (rightPx - leftPx).coerceAtLeast(0f)
  val heightPx: Float get() = (bottomPx - topPx).coerceAtLeast(0f)
  val centerX: Float get() = (leftPx + rightPx) * 0.5f
  val centerY: Float get() = (topPx + bottomPx) * 0.5f

  /** Clamped corner radius guaranteeing strictly positive straight edges and non-overlapping quarter arcs. */
  val clampedCornerRadiusPx: Float
    get() {
      val maxR = min(widthPx, heightPx) * 0.48f
      return cornerRadiusPx.coerceIn(1f, maxR.coerceAtLeast(1f))
    }

  val bottomCenterPx: Vec2 get() = Vec2(centerX, bottomPx)
  val topCenterPx: Vec2 get() = Vec2(centerX, topPx)

  fun isValid(): Boolean =
    leftPx.isFinite() && topPx.isFinite() && rightPx.isFinite() && bottomPx.isFinite() &&
      cornerRadiusPx.isFinite() && widthPx >= 16f && heightPx >= 16f

  /**
   * Signed distance from point [p] (in physical pixels) to the rounded photo rectangle.
   * Negative = inside photo interior; 0 = exact boundary; positive = outside photo.
   */
  fun signedDistancePx(p: Vec2): Float {
    val halfW = widthPx * 0.5f
    val halfH = heightPx * 0.5f
    val r = clampedCornerRadiusPx
    val qx = kotlin.math.abs(p.x - centerX) - (halfW - r)
    val qy = kotlin.math.abs(p.y - centerY) - (halfH - r)
    val outX = qx.coerceAtLeast(0f)
    val outY = qy.coerceAtLeast(0f)
    val outside = sqrt(outX * outX + outY * outY)
    val inside = kotlin.math.max(qx, qy).coerceAtMost(0f)
    return outside + inside - r
  }

  companion object {
    // Canonical normalized proportions extracted from docs/task6kf/FROZEN_6KF_REGIONS.json
    const val CANONICAL_WIDTH_RATIO = 0.75711f
    const val CANONICAL_ASPECT_W_OVER_H = 0.81647f
    const val CANONICAL_TOP_RATIO = 0.08899f
    const val CANONICAL_CORNER_RADIUS_OF_W = 0.05571f
    const val CANONICAL_REFERENCE_ASPECT = 0.56488f

    /**
     * Computes a responsive, resolution-independent fallback geometry for any viewport size
     * before Compose layout coordinates arrive.
     */
    fun canonicalForViewport(
      surfaceWidthPx: Int,
      surfaceHeightPx: Int,
      visualLock: TaskVisualFidelityV41? = null,
    ): PhotoFrameGeometry {
      if (visualLock != null) return visualLock.canonicalPhoto(surfaceWidthPx, surfaceHeightPx)
      val w = surfaceWidthPx.coerceAtLeast(64).toFloat()
      val h = surfaceHeightPx.coerceAtLeast(64).toFloat()
      val referenceHeightUnits = 1f / CANONICAL_REFERENCE_ASPECT
      val scalePx = minOf(w, h / referenceHeightUnits)
      val offsetX = (w - scalePx) * 0.5f
      val offsetY = (h - referenceHeightUnits * scalePx) * 0.5f
      val photoW = CANONICAL_WIDTH_RATIO * scalePx
      val photoH = photoW / CANONICAL_ASPECT_W_OVER_H
      val left = offsetX + 0.12145f * scalePx
      val top = offsetY + (CANONICAL_TOP_RATIO / CANONICAL_REFERENCE_ASPECT) * scalePx
      val cornerR = photoW * CANONICAL_CORNER_RADIUS_OF_W
      return PhotoFrameGeometry(
        leftPx = left,
        topPx = top,
        rightPx = left + photoW,
        bottomPx = top + photoH,
        cornerRadiusPx = cornerR,
      )
    }
  }
}
