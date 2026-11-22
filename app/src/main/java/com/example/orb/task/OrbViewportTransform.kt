package com.example.orb.task

import android.opengl.Matrix
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Off-axis lens-shift viewport transform that places the SHARED V3 AI Orb
 * (Core + 5 Residents + Energy Highlights + Particles) cleanly at the bottom-center
 * of the Tab 2 Task Recognition Demo without duplicating any Orb geometry, shaders,
 * WebP textures, or introducing perspective keystone distortion.
 *
 * Tab 1 (`ORB_ONLY`) does NOT apply this transform and retains 100% original framing.
 */
data class OrbViewportTransform(
  val orbCenterPx: Vec2,
  val orbDiameterPx: Float,
  val surfaceWidthPx: Int,
  val surfaceHeightPx: Int,
) {
  /** Top center anchor on the outer crystal glass silhouette where Task Energy originates. */
  val orbAnchorTopPx: Vec2
    get() = Vec2(orbCenterPx.x, orbCenterPx.y - orbDiameterPx * 0.46f)

  /** Normalized center UV in OpenGL bottom-left texture coordinates [0..1] for pool caustics. */
  val orbCenterGlUv: Vec2
    get() = Vec2(
      (orbCenterPx.x / surfaceWidthPx.coerceAtLeast(1)).coerceIn(0f, 1f),
      (1f - orbCenterPx.y / surfaceHeightPx.coerceAtLeast(1)).coerceIn(0f, 1f),
    )

  /** Normalized screen-space radius in vertical viewport fraction. */
  val orbRadiusNormH: Float
    get() = (orbDiameterPx * 0.5f) / surfaceHeightPx.coerceAtLeast(1)

  /**
   * Target NDC center of the Orb (`x in [-1, 1]` right, `y in [-1, 1]` up).
   */
  val targetNdcX: Float
    get() = (orbCenterPx.x / surfaceWidthPx.coerceAtLeast(1)) * 2f - 1f

  val targetNdcY: Float
    get() = 1f - (orbCenterPx.y / surfaceHeightPx.coerceAtLeast(1)) * 2f

  /**
   * Computes camera distance along +Z so a sphere of world radius [coreWorldRadius]
   * projects to exact screen diameter [orbDiameterPx].
   */
  fun computeCameraDistance(fovYDeg: Float, coreWorldRadius: Float): Float {
    val safeR = coreWorldRadius.coerceAtLeast(0.35f)
    val safeH = surfaceHeightPx.coerceAtLeast(64).toFloat()
    val desiredNdcHalfH = (orbDiameterPx / safeH).coerceIn(0.06f, 0.85f)
    val tanHalfY = tan(Math.toRadians(fovYDeg.toDouble() * 0.5)).toFloat().coerceAtLeast(0.05f)
    val ratio = safeR / (desiredNdcHalfH * tanHalfY)
    return sqrt(safeR * safeR + ratio * ratio).coerceIn(3.2f, 24.0f)
  }

  /**
   * Writes an exact off-axis lens-shifted perspective projection matrix into [outProj].
   * Because column-major `P[8] = -targetNdcX` and `P[9] = -targetNdcY`, world origin `(0, 0, -D)`
   * projects to `(targetNdcX, targetNdcY)` straight-on with ZERO keystone skew across the
   * Core, all 5 Residents, Energy Highlights, and Particles simultaneously!
   */
  fun writeLensShiftedProjection(outProj: FloatArray, fovYDeg: Float, near: Float = 1.2f, far: Float = 42.0f) {
    val aspect = surfaceWidthPx.coerceAtLeast(1).toFloat() / surfaceHeightPx.coerceAtLeast(1).toFloat()
    Matrix.perspectiveM(outProj, 0, fovYDeg, aspect, near, far)
    outProj[8] = -targetNdcX
    outProj[9] = -targetNdcY
  }

  companion object {
    // Frozen overview orb proportions: centerX = 0.50, diameter ≈ 0.255 * W, positioned cleanly beneath status text
    const val CANONICAL_ORB_DIAMETER_OF_W = 0.255f

    /**
     * Derives the canonical Orb viewport position & size below [photo] so the Orb never collides
     * with the photo bottom edge or bottom navigation safe area across any phone/tablet aspect ratio.
     */
    fun forPhotoAndSurface(
      photo: PhotoFrameGeometry,
      surfaceWidthPx: Int,
      surfaceHeightPx: Int,
    ): OrbViewportTransform {
      val w = surfaceWidthPx.coerceAtLeast(64).toFloat()
      val h = surfaceHeightPx.coerceAtLeast(64).toFloat()
      val availableBottomSpace = (h - photo.bottomPx).coerceAtLeast(h * 0.18f)
      val maxDiam = minOf(w * 0.32f, availableBottomSpace * 0.56f).coerceAtLeast(18f)
      val minDiam = minOf(w * 0.14f, maxDiam)
      val rawDiam = w * CANONICAL_ORB_DIAMETER_OF_W
      val diam = rawDiam.coerceIn(minDiam, maxDiam)
      // Place Orb center ~62% down the remaining space below the photo (leaving clear room for status text + Task stream!)
      val minCenterY = photo.bottomPx + diam * 0.62f
      val maxCenterY = (h - diam * 0.52f).coerceAtLeast(minCenterY)
      val desiredCenterY = photo.bottomPx + availableBottomSpace * 0.64f
      val centerY = desiredCenterY.coerceIn(minCenterY, maxCenterY)
      return OrbViewportTransform(
        orbCenterPx = Vec2(photo.centerX, centerY),
        orbDiameterPx = diam,
        surfaceWidthPx = surfaceWidthPx,
        surfaceHeightPx = surfaceHeightPx,
      )
    }
  }
}
