package com.example.orb.task

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Multi-layer perimeter ribbon layers sharing ONE [PerimeterArcLengthTable]:
 *  - L1_ICE_BLUE_BODY:   Translucent icy-blue liquid body (width 0.4%–1.2% PhotoW, local optical flare 2%–4%)
 *  - L2_EMERALD_VEIL:    Organic emerald-green aquatic kelp veil hugging outer flanks with smooth undulating width
 *  - L3_GOLD_FILAMENT:   Slender warm champagne-gold refractive filament (~0.45% PhotoW total width)
 */
enum class PerimeterRibbonLayer(val shaderLayerId: Int) {
  L1_ICE_BLUE_BODY(0),
  L2_EMERALD_VEIL(1),
  L3_GOLD_FILAMENT(2),
}

/** Frozen V4.1 perimeter geometry ratios, all measured against the photo width. */
data class PerimeterRibbonStyle(
  val l1MinWidthPhotoRatio: Float = 0.004f,
  val l1MaxWidthPhotoRatio: Float = 0.012f,
  val l1LocalFlareMinPhotoRatio: Float = 0.02f,
  val l1LocalFlareMaxPhotoRatio: Float = 0.04f,
  val l3WidthPhotoRatio: Float = 0.0045f,
)

/**
 * Builds GPU triangle-strip vertex data strictly from [PerimeterArcLengthTable] along `outwardNormal`
 * so ZERO liquid ribbon geometry ever crosses into the photo interior!
 *
 * All width modulations (`cornerFlare`, `flankMask`) are strictly C1-continuous functions of
 * arc length `sPx` (ZERO discrete `when (sample.segment)` jumps across rounded corners!).
 *
 * Vertex layout (6 floats per vertex):
 *   aPosNdc.xy (2) | aArcU.x = sNorm in [0,1] (1) | aArcU.y = crossU in [0,1] (1) | aNormalNdc.xy (2)
 */
object PerimeterRibbonMeshBuilder {

  const val FLOATS_PER_VERTEX = 6

  /** Smooth Gaussian bump centered at [centerPx] with width [sigmaPx]. */
  fun smoothBump(sPx: Float, centerPx: Float, sigmaPx: Float): Float {
    val safeSigma = sigmaPx.coerceAtLeast(1e-3f)
    val d = (sPx - centerPx) / safeSigma
    return exp(-(d * d))
  }

  /** GLSL-equivalent C1 smoothstep with guaranteed `edge0 < edge1`. */
  fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val span = (edge1 - edge0).coerceAtLeast(1e-5f)
    val t = ((x - edge0) / span).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  /**
   * Continuous arc-length corner flare (zero discrete step at corner tangent boundaries).
   */
  fun evaluateCornerFlare(geo: PerimeterPathGeometry, sPx: Float): Float {
    val sigma = (geo.cornerRadiusPx * 0.55f).coerceAtLeast(6f)
    val bottomCornerCenter = (geo.s1BottomCornerStartPx + geo.s2BottomCornerEndPx) * 0.5f
    val topCornerCenter = (geo.s3TopCornerStartPx + geo.s4TopCornerEndPx) * 0.5f
    val bottomBump = smoothBump(sPx, bottomCornerCenter, sigma)
    val topBump = smoothBump(sPx, topCornerCenter, sigma)
    return 1.0f + 0.22f * maxOf(bottomBump, topBump)
  }

  /**
   * Continuous arc-length emerald flank mask: gently rises from bottom straight (0.55)
   * through bottom corner & vertical side (1.0) and softens smoothly across top corner to top straight (0.35).
   */
  fun evaluateEmeraldFlankMask(geo: PerimeterPathGeometry, sPx: Float): Float {
    val trans = (geo.cornerRadiusPx * 0.70f).coerceAtLeast(8f)
    val riseIntoFlank = smoothstep(geo.s1BottomCornerStartPx - trans, geo.s2BottomCornerEndPx + trans * 0.35f, sPx)
    val softenAtTop = smoothstep(geo.s3TopCornerStartPx - trans * 0.35f, geo.s4TopCornerEndPx + trans, sPx)
    val bottomToSide = 0.55f + 0.45f * riseIntoFlank
    return bottomToSide * (1f - softenAtTop) + 0.35f * softenAtTop
  }

  /**
   * Computes exact ribbon width in physical pixels `(outerOffsetPx - innerOffsetPx)` for [layer]
   * at normalized arc fraction `sNorm in [0, 1]`. Pure function shared by mesh builder and unit tests.
   */
  fun evaluateRibbonOffsetsPx(
    geo: PerimeterPathGeometry,
    side: PerimeterSide,
    layer: PerimeterRibbonLayer,
    sNorm: Float,
    sPx: Float,
    style: PerimeterRibbonStyle = PerimeterRibbonStyle(),
  ): Pair<Float, Float> {
    val photoW = geo.widthPx.coerceAtLeast(64f)
    val sideSign = if (side == PerimeterSide.LEFT) -1f else 1f
    val waveA = 0.5f + 0.5f * sin(sNorm * PI.toFloat() * 6.0f + sideSign * 0.7f)
    val waveB = 0.5f + 0.5f * sin(sNorm * PI.toFloat() * 11.0f - sideSign * 1.3f)
    val cornerFlare = evaluateCornerFlare(geo, sPx)

    return when (layer) {
      PerimeterRibbonLayer.L1_ICE_BLUE_BODY -> {
        // Keep the liquid body inside its width band. Apply the independent 2%–4%
        // optical extension only at rounded corners, smoothly along arc length.
        val width = style.l1MinWidthPhotoRatio +
          (style.l1MaxWidthPhotoRatio - style.l1MinWidthPhotoRatio) * (0.45f * waveA + 0.55f * waveB)
        val baseW = photoW * width.coerceIn(style.l1MinWidthPhotoRatio, style.l1MaxWidthPhotoRatio)
        val cornerAmount = ((cornerFlare - 1f) / 0.22f).coerceIn(0f, 1f)
        val flareRatio = style.l1LocalFlareMinPhotoRatio +
          (style.l1LocalFlareMaxPhotoRatio - style.l1LocalFlareMinPhotoRatio) * (0.5f * waveA + 0.5f * waveB)
        val localFlare = photoW * flareRatio * cornerAmount
        val innerOffsetPx = 0.5f
        innerOffsetPx to (innerOffsetPx + baseW + localFlare)
      }
      PerimeterRibbonLayer.L2_EMERALD_VEIL -> {
        // Smoothly undulating emerald aquatic veil hugging corners & vertical flanks (local flare up to ~3.4% PhotoW)
        val flankMask = evaluateEmeraldFlankMask(geo, sPx)
        val veilW = (photoW * (0.0055f + 0.0195f * waveA * flankMask + 0.0070f * waveB) * cornerFlare)
          .coerceAtMost(photoW * 0.036f)
        val innerOffsetPx = 1.2f + photoW * 0.0025f * waveB
        innerOffsetPx to (innerOffsetPx + veilW)
      }
      PerimeterRibbonLayer.L3_GOLD_FILAMENT -> {
        // Slender champagne-gold specular thread (~0.45% PhotoW total width: halfW = 0.00225 * PhotoW)
        val centerOut = photoW * (0.0042f + 0.0038f * waveA)
        val halfW = (photoW * style.l3WidthPhotoRatio * 0.5f).coerceAtLeast(1.4f)
        val innerOffsetPx = (centerOut - halfW).coerceAtLeast(0.5f)
        val outerOffsetPx = innerOffsetPx + halfW * 2.0f
        innerOffsetPx to outerOffsetPx
      }
    }
  }

  fun buildLayerStripNdc(
    table: PerimeterArcLengthTable,
    side: PerimeterSide,
    layer: PerimeterRibbonLayer,
    surfaceWidthPx: Int,
    surfaceHeightPx: Int,
    style: PerimeterRibbonStyle = PerimeterRibbonStyle(),
  ): FloatArray {
    val samples = table.rawSamples(side)
    val n = samples.size
    val sw = surfaceWidthPx.coerceAtLeast(1).toFloat()
    val sh = surfaceHeightPx.coerceAtLeast(1).toFloat()
    val geo = table.geometry

    val verts = FloatArray(n * 2 * FLOATS_PER_VERTEX)
    var cursor = 0

    for (i in 0 until n) {
      val sample = samples[i]
      val sNorm = i.toFloat() / (n - 1).toFloat()
      val pos = sample.positionPx
      val outN = sample.outwardNormal

      val (innerOffsetPx, outerOffsetPx) = evaluateRibbonOffsetsPx(
        geo = geo,
        side = side,
        layer = layer,
        sNorm = sNorm,
        sPx = sample.arcLengthPx,
        style = style,
      )

      val pInner = pos + outN * innerOffsetPx
      val pOuter = pos + outN * outerOffsetPx

      // Outward normal converted to NDC scale per pixel for vertex shader breathing displacement & refraction
      val normNdcX = (outN.x / sw) * 2f
      val normNdcY = -(outN.y / sh) * 2f

      // Inner vertex (crossU = 0.0, strictly on/outside photo edge!)
      verts[cursor++] = (pInner.x / sw) * 2f - 1f
      verts[cursor++] = 1f - (pInner.y / sh) * 2f
      verts[cursor++] = sNorm
      verts[cursor++] = 0f
      verts[cursor++] = normNdcX
      verts[cursor++] = normNdcY

      // Outer vertex (crossU = 1.0, strictly outward!)
      verts[cursor++] = (pOuter.x / sw) * 2f - 1f
      verts[cursor++] = 1f - (pOuter.y / sh) * 2f
      verts[cursor++] = sNorm
      verts[cursor++] = 1f
      verts[cursor++] = normNdcX
      verts[cursor++] = normNdcY
    }
    return verts
  }
}
