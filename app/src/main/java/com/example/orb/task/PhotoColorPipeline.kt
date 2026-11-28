package com.example.orb.task

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Display encoding mode for [PhotoTextureRenderer]:
 * - [LEGACY_HDR_POST_COMP]: Converts sRGB photo to linear + inverse-ACES pre-compensation for
 *   [com.example.orb.scene.AIOrbScene]'s HDR FBO -> ACES tonemap -> linear-to-sRGB post pipeline.
 * - [DIRECT_DISPLAY_SRGB]: Outputs source sRGB photo directly into Tab 2's default display sRGB
 *   framebuffer (`0`), completely eliminating the double-gamma darkening defect (`pow(srgb, 2.2) * 0.84`
 *   without subsequent `pow(1/2.2)` encode) with ZERO artificial brightness hacks!
 */
enum class PhotoDisplayColorMode(val shaderFlag: Int) {
  LEGACY_HDR_POST_COMP(0),
  DIRECT_DISPLAY_SRGB(1),
}

data class PhotoRgb(val r: Float, val g: Float, val b: Float)

data class PhotoColorPatchMetric(
  val patchName: String,
  val sourceSrgb: PhotoRgb,
  val legacyWrongDirectOutput: PhotoRgb,
  val fixedDirectDisplayOutput: PhotoRgb,
  val legacyMaxAbsError: Float,
  val fixedMaxAbsError: Float,
)

/**
 * Pure reference transfer math mirroring `PHOTO_QUAD_FRAG` 1:1 so automated unit tests and
 * software visual verification can audit Source Bitmap -> GPU Display Output fidelity across
 * standard color patches (`black`, `18% gray`, `50% gray`, `75% gray`, `white`, `R`, `G`, `B`).
 */
object PhotoColorPipeline {

  val STANDARD_COLOR_PATCHES: List<Pair<String, PhotoRgb>> = listOf(
    "black_0pct" to PhotoRgb(0.00f, 0.00f, 0.00f),
    "gray_18pct" to PhotoRgb(0.18f, 0.18f, 0.18f),
    "gray_50pct" to PhotoRgb(0.50f, 0.50f, 0.50f),
    "gray_75pct" to PhotoRgb(0.75f, 0.75f, 0.75f),
    "white_100pct" to PhotoRgb(1.00f, 1.00f, 1.00f),
    "red_primary" to PhotoRgb(0.86f, 0.18f, 0.16f),
    "green_primary" to PhotoRgb(0.18f, 0.78f, 0.28f),
    "blue_primary" to PhotoRgb(0.16f, 0.42f, 0.88f),
  )

  /** Mirrors `photoSrgbToPostCompLinear` in `TaskWaterLightShaders.PHOTO_QUAD_FRAG`. */
  fun legacyHdrPreComp(srgb: PhotoRgb): PhotoRgb {
    fun ch(v: Float): Float {
      val x = v.coerceIn(0f, 0.99f)
      val lin = x.toDouble().pow(2.2).toFloat()
      val boosted = lin / max(0.28f, 1.0f - lin * 0.52f)
      return min(boosted * 0.84f, 1.22f)
    }
    return PhotoRgb(ch(srgb.r), ch(srgb.g), ch(srgb.b))
  }

  /** Evaluates the interior pixel output written to framebuffer for [mode]. */
  fun evaluatePhotoInteriorPixel(srgb: PhotoRgb, mode: PhotoDisplayColorMode, hdrScale: Float = 1.0f): PhotoRgb {
    val raw = when (mode) {
      PhotoDisplayColorMode.LEGACY_HDR_POST_COMP -> legacyHdrPreComp(srgb)
      PhotoDisplayColorMode.DIRECT_DISPLAY_SRGB -> PhotoRgb(
        srgb.r.coerceIn(0f, 1f),
        srgb.g.coerceIn(0f, 1f),
        srgb.b.coerceIn(0f, 1f),
      )
    }
    return PhotoRgb(
      (raw.r * hdrScale).coerceIn(0f, 1f),
      (raw.g * hdrScale).coerceIn(0f, 1f),
      (raw.b * hdrScale).coerceIn(0f, 1f),
    )
  }

  fun evaluateStandardChart(): List<PhotoColorPatchMetric> {
    return STANDARD_COLOR_PATCHES.map { (name, src) ->
      val legacyOut = evaluatePhotoInteriorPixel(src, PhotoDisplayColorMode.LEGACY_HDR_POST_COMP)
      val fixedOut = evaluatePhotoInteriorPixel(src, PhotoDisplayColorMode.DIRECT_DISPLAY_SRGB)
      val legacyErr = maxOf(abs(legacyOut.r - src.r), abs(legacyOut.g - src.g), abs(legacyOut.b - src.b))
      val fixedErr = maxOf(abs(fixedOut.r - src.r), abs(fixedOut.g - src.g), abs(fixedOut.b - src.b))
      PhotoColorPatchMetric(
        patchName = name,
        sourceSrgb = src,
        legacyWrongDirectOutput = legacyOut,
        fixedDirectDisplayOutput = fixedOut,
        legacyMaxAbsError = legacyErr,
        fixedMaxAbsError = fixedErr,
      )
    }
  }
}
