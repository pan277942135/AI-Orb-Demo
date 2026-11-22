package com.example.orb.task

import kotlin.math.PI
import kotlin.math.sin

/**
 * Multi-layer perimeter ribbon layers sharing ONE [PerimeterArcLengthTable]:
 *  - L1_ICE_BLUE_BODY:   Translucent icy-blue liquid body (width 0.4%–1.2% PhotoW, local flare 2%–4%)
 *  - L2_EMERALD_VEIL:    Organic emerald-green aquatic kelp veil hugging outer flanks with undulating width
 *  - L3_GOLD_FILAMENT:   Slender warm champagne-gold refractive filament traveling along arc length
 */
enum class PerimeterRibbonLayer(val shaderLayerId: Int) {
  L1_ICE_BLUE_BODY(0),
  L2_EMERALD_VEIL(1),
  L3_GOLD_FILAMENT(2),
}

/**
 * Builds GPU triangle-strip vertex data strictly from [PerimeterArcLengthTable] along `outwardNormal`
 * so ZERO liquid ribbon geometry ever crosses into the photo interior!
 *
 * Vertex layout (6 floats per vertex):
 *   aPosNdc.xy (2) | aArcU.x = sNorm in [0,1] (1) | aArcU.y = crossU in [0,1] (1) | aNormalNdc.xy (2)
 * Note: `aNormalNdc` lets the vertex shader apply subtle continuous organic width breathing & 2.5D flutter
 * strictly outward without ever rebuilding VBOs per frame!
 */
object PerimeterRibbonMeshBuilder {

  const val FLOATS_PER_VERTEX = 6

  fun buildLayerStripNdc(
    table: PerimeterArcLengthTable,
    side: PerimeterSide,
    layer: PerimeterRibbonLayer,
    surfaceWidthPx: Int,
    surfaceHeightPx: Int,
  ): FloatArray {
    val samples = table.rawSamples(side)
    val n = samples.size
    val sw = surfaceWidthPx.coerceAtLeast(1).toFloat()
    val sh = surfaceHeightPx.coerceAtLeast(1).toFloat()
    val photoW = table.geometry.widthPx.coerceAtLeast(64f)

    // Calibrated strictly to KF04 slender translucent standard (FROZEN_6KF_VISUAL_SPEC):
    // L1: 0.4%-1.2% PhotoW base + organic local flare up to ~2.8% PhotoW
    // L2: slender emerald kelp veil hugging outer side flanks (up to ~3.4% PhotoW at gentle outer curls)
    // L3: fine champagne-gold refractive filament (~0.45% PhotoW)
    val sideSign = if (side == PerimeterSide.LEFT) -1f else 1f
    val verts = FloatArray(n * 2 * FLOATS_PER_VERTEX)
    var cursor = 0

    for (i in 0 until n) {
      val sample = samples[i]
      val sNorm = i.toFloat() / (n - 1).toFloat()
      val pos = sample.positionPx
      val outN = sample.outwardNormal

      // Organic non-uniform width wave along arc length (zero mechanical constant-width rectangle!)
      val waveA = 0.5f + 0.5f * sin(sNorm * PI.toFloat() * 6.0f + sideSign * 0.7f)
      val waveB = 0.5f + 0.5f * sin(sNorm * PI.toFloat() * 11.0f - sideSign * 1.3f)
      val cornerFlare = when (sample.segment) {
        PerimeterSegment.BOTTOM_QUARTER_ARC, PerimeterSegment.TOP_QUARTER_ARC -> 1.22f
        else -> 1.0f
      }

      val innerOffsetPx: Float
      val outerOffsetPx: Float
      when (layer) {
        PerimeterRibbonLayer.L1_ICE_BLUE_BODY -> {
          // Starts strictly on/just outside photo hairline (+0.5px outward) -> extends outward 0.65%..2.4% PhotoW
          val baseW = photoW * (0.0085f + 0.0095f * waveA + 0.0055f * waveB) * cornerFlare
          innerOffsetPx = 0.5f
          outerOffsetPx = innerOffsetPx + baseW
        }
        PerimeterRibbonLayer.L2_EMERALD_VEIL -> {
          // Delicate undulating emerald aquatic veil concentrated along bottom corners & vertical sides (KF04-KF06)
          val flankMask = when (sample.segment) {
            PerimeterSegment.VERTICAL_STRAIGHT -> 1.0f
            PerimeterSegment.BOTTOM_QUARTER_ARC, PerimeterSegment.TOP_QUARTER_ARC -> 0.85f
            PerimeterSegment.BOTTOM_HALF_STRAIGHT -> 0.55f
            PerimeterSegment.TOP_HALF_STRAIGHT -> 0.35f
          }
          val veilW = photoW * (0.006f + 0.022f * waveA * flankMask + 0.008f * waveB)
          innerOffsetPx = 1.5f + photoW * 0.003f * waveB
          outerOffsetPx = innerOffsetPx + veilW
        }
        PerimeterRibbonLayer.L3_GOLD_FILAMENT -> {
          // Razor-fine champagne-gold specular thread weaving subtly within the icy-blue liquid band
          val centerOut = photoW * (0.0055f + 0.0055f * waveA)
          val halfW = (photoW * 0.0042f).coerceAtLeast(2.2f)
          innerOffsetPx = (centerOut - halfW).coerceAtLeast(0.5f)
          outerOffsetPx = centerOut + halfW
        }
      }

      val pInner = pos + outN * innerOffsetPx
      val pOuter = pos + outN * outerOffsetPx

      // Outward normal converted to NDC scale per pixel for vertex shader breathing displacement
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
