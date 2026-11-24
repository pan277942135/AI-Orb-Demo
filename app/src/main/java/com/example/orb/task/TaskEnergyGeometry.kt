package com.example.orb.task

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Style classification for the 4 independent Task Energy streams released from the Orb
 * toward Photo Bottom Center (strictly separate from the 5 frozen Resident ribbons!).
 */
enum class TaskStreamKind(val shaderStyleId: Int) {
  MAIN_ICE_STREAM(0),         // Wide S-curved translucent icy-blue main energy ribbon
  SECONDARY_LEFT_CYAN(1),     // Light left-flanking cyan curl with open breathing gap
  SECONDARY_RIGHT_FINE(2),    // Delicate fine icy-blue right stream with offset phase
  SECONDARY_EMERALD_VEIL(3),  // Organic translucent emerald-green aquatic kelp veil
}

data class TaskStreamSpec(
  val id: String,
  val kind: TaskStreamKind,
  val controlPointsNorm: List<Vec2>, // Normalized in (x: offset in Orb diameters, y: 0=OrbAnchorTop -> 1=PhotoBottomCenter)
  val baseHalfWidthPxRatio: Float,   // Fraction of Orb diameter
  val widthProfile: FloatArray,      // Sculpted width variation along s in [0, 1]
  val twistCycles: Float,            // Gentle 2.5D ribbon twist along arc length
  val flowSpeed: Float,              // Independent material flow velocity
  val alphaScale: Float,
  val depthOffset: Float,            // Subtle z-layer ordering
  val reachFraction: Float,          // 1.0 for main stream reaching contact; 0.72..0.96 for accompanying wisps
)

/**
 * Pure-Kotlin geometry builder for the 4 independent Task Energy liquid ribbons
 * connecting `Orb Anchor Top` to `Photo Bottom Center`.
 *
 * Each stream has independent control points, width profile, twist, flow speed, alpha,
 * and breathing separation from the main stream (ZERO bundled neon laser rope!).
 *
 * Vertex layout (8 floats per vertex):
 *   posNdc(2) | uv(2: s, u) | alpha(1) | twist(1) | normalDirGl(2: unit lateral normal in GL UV space)
 */
object TaskEnergyGeometry {

  const val SEGMENTS_PER_STREAM = 56
  const val FLOATS_PER_VERTEX = 8

  fun buildDefaultStreams(): List<TaskStreamSpec> = listOf(
    // 1. Main S-curved translucent icy-blue water stream (exact Orb Top [0,0] -> exact Photo Bottom Center [0,1])
    TaskStreamSpec(
      id = "TASK_MAIN_ICE",
      kind = TaskStreamKind.MAIN_ICE_STREAM,
      controlPointsNorm = listOf(
        Vec2(0.00f, 0.00f),
        Vec2(-0.11f, 0.24f),
        Vec2(0.14f, 0.54f),
        Vec2(-0.07f, 0.80f),
        Vec2(0.00f, 1.00f),
      ),
      baseHalfWidthPxRatio = 0.088f,
      widthProfile = floatArrayOf(0.86f, 1.18f, 0.96f, 1.08f, 0.72f),
      twistCycles = 1.35f,
      flowSpeed = 1.15f,
      alphaScale = 0.78f,
      depthOffset = 0.0f,
      reachFraction = 1.0f,
    ),
    // 2. Left light icy-cyan flanking curl (leaves crisp transparent breathing space from main stream)
    TaskStreamSpec(
      id = "TASK_SEC_LEFT_CYAN",
      kind = TaskStreamKind.SECONDARY_LEFT_CYAN,
      controlPointsNorm = listOf(
        Vec2(-0.14f, 0.04f),
        Vec2(-0.56f, 0.22f),
        Vec2(-0.84f, 0.44f),
        Vec2(-0.62f, 0.66f),
        Vec2(-0.34f, 0.80f),
      ),
      baseHalfWidthPxRatio = 0.056f,
      widthProfile = floatArrayOf(0.55f, 1.05f, 1.12f, 0.72f, 0.14f),
      twistCycles = 1.10f,
      flowSpeed = 0.92f,
      alphaScale = 0.50f,
      depthOffset = -0.02f,
      reachFraction = 0.80f,
    ),
    // 3. Right delicate fine icy-blue stream
    TaskStreamSpec(
      id = "TASK_SEC_RIGHT_FINE",
      kind = TaskStreamKind.SECONDARY_RIGHT_FINE,
      controlPointsNorm = listOf(
        Vec2(0.14f, 0.03f),
        Vec2(0.42f, 0.28f),
        Vec2(0.52f, 0.56f),
        Vec2(0.28f, 0.78f),
        Vec2(0.08f, 0.96f),
      ),
      baseHalfWidthPxRatio = 0.038f,
      widthProfile = floatArrayOf(0.48f, 0.92f, 1.00f, 0.68f, 0.22f),
      twistCycles = 1.65f,
      flowSpeed = 1.32f,
      alphaScale = 0.52f,
      depthOffset = 0.01f,
      reachFraction = 0.96f,
    ),
    // 4. Right outer emerald-green aquatic kelp-like veil (matching KF01-KF06 right emerald plume!)
    TaskStreamSpec(
      id = "TASK_SEC_EMERALD_VEIL",
      kind = TaskStreamKind.SECONDARY_EMERALD_VEIL,
      controlPointsNorm = listOf(
        Vec2(0.20f, 0.06f),
        Vec2(0.64f, 0.24f),
        Vec2(0.96f, 0.44f),
        Vec2(0.86f, 0.64f),
        Vec2(0.60f, 0.76f),
      ),
      baseHalfWidthPxRatio = 0.072f,
      widthProfile = floatArrayOf(0.50f, 1.18f, 1.24f, 0.78f, 0.12f),
      twistCycles = 0.95f,
      flowSpeed = 0.78f,
      alphaScale = 0.48f,
      depthOffset = -0.01f,
      reachFraction = 0.78f,
    ),
  )

  /** Evaluates smooth 2D Catmull-Rom point along open control list at `t in [0, 1]`. */
  fun sampleCatmullRom2D(pts: List<Vec2>, t: Float): Vec2 {
    val n = pts.size
    if (n < 2) return pts.firstOrNull() ?: Vec2(0f, 0f)
    if (t <= 0f) return pts.first()
    if (t >= 1f) return pts.last()
    val segCount = n - 1
    val scaled = t * segCount
    val seg = scaled.toInt().coerceIn(0, segCount - 1)
    val u = scaled - seg

    fun at(idx: Int): Vec2 = pts[idx.coerceIn(0, n - 1)]
    val p0 = at(seg - 1)
    val p1 = at(seg)
    val p2 = at(seg + 1)
    val p3 = at(seg + 2)

    val u2 = u * u
    val u3 = u2 * u
    fun interp(a: Float, b: Float, c: Float, d: Float): Float =
      0.5f * ((2f * b) + (-a + c) * u + (2f * a - 5f * b + 4f * c - d) * u2 + (-a + 3f * b - 3f * c + d) * u3)

    return Vec2(
      interp(p0.x, p1.x, p2.x, p3.x),
      interp(p0.y, p1.y, p2.y, p3.y),
    )
  }

  /** Evaluates centerline position in GLSurfaceView local physical pixels for [spec] at `s in [0, 1]`. */
  fun evaluateCenterlinePx(
    spec: TaskStreamSpec,
    layout: TaskSceneLayoutSnapshot,
    s: Float,
  ): Vec2 {
    if (spec.kind == TaskStreamKind.MAIN_ICE_STREAM) {
      if (s <= 0f) return layout.orbAnchorTopPx
      if (s >= 1f) return layout.photoBottomCenterPx
    }
    val orbDiam = layout.orbTransform.orbDiameterPx.coerceAtLeast(48f)
    val startPx = layout.orbAnchorTopPx
    val endPx = layout.photoBottomCenterPx
    val spanY = (startPx.y - endPx.y).coerceAtLeast(32f)
    val normPt = sampleCatmullRom2D(spec.controlPointsNorm, s.coerceIn(0f, 1f))
    return Vec2(
      x = startPx.x + normPt.x * orbDiam,
      y = startPx.y - normPt.y * spanY,
    )
  }

  private fun sampleFloatProfile(profile: FloatArray, t: Float): Float {
    if (profile.isEmpty()) return 1f
    if (profile.size == 1) return profile[0]
    val scaled = t.coerceIn(0f, 1f) * (profile.size - 1)
    val i0 = scaled.toInt().coerceIn(0, profile.size - 2)
    val f = (scaled - i0).coerceIn(0f, 1f)
    return profile[i0] * (1f - f) + profile[i0 + 1] * f
  }

  /**
   * Builds triangle-strip vertex array in NDC space (`[-1, 1]`) for [spec] spanning
   * `layout.orbAnchorTopPx` -> `layout.photoBottomCenterPx`.
   */
  fun buildRibbonVerticesNdc(
    spec: TaskStreamSpec,
    layout: TaskSceneLayoutSnapshot,
    segments: Int = SEGMENTS_PER_STREAM,
  ): FloatArray {
    val safeSegs = segments.coerceAtLeast(12)
    val orbDiam = layout.orbTransform.orbDiameterPx.coerceAtLeast(48f)
    val halfBaseW = orbDiam * spec.baseHalfWidthPxRatio
    val sw = layout.surfaceWidthPx.coerceAtLeast(1).toFloat()
    val sh = layout.surfaceHeightPx.coerceAtLeast(1).toFloat()

    // Evaluate centerline in physical pixels
    val centerPx = Array(safeSegs + 1) { i ->
      val s = i.toFloat() / safeSegs.toFloat()
      evaluateCenterlinePx(spec, layout, s)
    }

    val verts = FloatArray((safeSegs + 1) * 2 * FLOATS_PER_VERTEX)
    var cursor = 0
    for (i in 0..safeSegs) {
      val s = i.toFloat() / safeSegs.toFloat()
      val p = centerPx[i]
      val tangent = when (i) {
        0 -> (centerPx[1] - centerPx[0]).normalized()
        safeSegs -> (centerPx[safeSegs] - centerPx[safeSegs - 1]).normalized()
        else -> (centerPx[i + 1] - centerPx[i - 1]).normalized()
      }
      // Perpendicular ribbon normal in screen pixel coordinates (+X right, +Y down)
      val perp = Vec2(-tangent.y, tangent.x).normalized()
      // Convert unit lateral normal to OpenGL UV direction (+X right, +Y up) for P0-4 screen-space refraction
      val normalDirGl = Vec2(perp.x, -perp.y).normalized()
      val twistAngle = s * spec.twistCycles * PI.toFloat()
      // 2.5D foreshortening factor creates natural organic liquid ribbon turn-overs (never collapses to 0!)
      val twistWidthScale = 0.58f + 0.42f * kotlin.math.abs(cos(twistAngle))
      val halfW = halfBaseW * sampleFloatProfile(spec.widthProfile, s) * twistWidthScale
      val twistSignal = sin(twistAngle)

      // Left edge (u = 0) and Right edge (u = 1)
      val leftPx = p - perp * halfW
      val rightPx = p + perp * halfW

      // Emit left vertex
      verts[cursor++] = (leftPx.x / sw) * 2f - 1f
      verts[cursor++] = 1f - (leftPx.y / sh) * 2f
      verts[cursor++] = s
      verts[cursor++] = 0f
      verts[cursor++] = spec.alphaScale
      verts[cursor++] = twistSignal
      verts[cursor++] = normalDirGl.x
      verts[cursor++] = normalDirGl.y

      // Emit right vertex
      verts[cursor++] = (rightPx.x / sw) * 2f - 1f
      verts[cursor++] = 1f - (rightPx.y / sh) * 2f
      verts[cursor++] = s
      verts[cursor++] = 1f
      verts[cursor++] = spec.alphaScale
      verts[cursor++] = twistSignal
      verts[cursor++] = normalDirGl.x
      verts[cursor++] = normalDirGl.y
    }
    return verts
  }
}
