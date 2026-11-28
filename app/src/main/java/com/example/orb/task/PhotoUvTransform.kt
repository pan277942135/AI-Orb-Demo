package com.example.orb.task

import kotlin.math.abs

/**
 * Normalized UV sub-rectangle in top-down Bitmap texture space (`[0, 1] x [0, 1]`).
 */
data class PhotoUvRect(
  val uMin: Float,
  val vMin: Float,
  val uMax: Float,
  val vMax: Float,
) {
  val uSpan: Float get() = (uMax - uMin).coerceAtLeast(0f)
  val vSpan: Float get() = (vMax - vMin).coerceAtLeast(0f)

  companion object {
    val IDENTITY = PhotoUvRect(0f, 0f, 1f, 1f)
  }
}

/**
 * Pure aspect-preserving `CENTER_CROP` UV calculator mapping an EXIF-normalized source photo
 * (`srcWidthPx x srcHeightPx`) into Tab 2's rounded photo frame (`frameWidthPx x frameHeightPx`)
 * with ZERO stretching or geometric distortion.
 */
object PhotoUvTransform {

  fun compute(
    srcWidthPx: Int,
    srcHeightPx: Int,
    frameWidthPx: Float,
    frameHeightPx: Float,
  ): PhotoUvRect {
    if (srcWidthPx <= 0 || srcHeightPx <= 0 || frameWidthPx <= 1f || frameHeightPx <= 1f) {
      return PhotoUvRect.IDENTITY
    }
    val srcAspect = srcWidthPx.toFloat() / srcHeightPx.toFloat()
    val frameAspect = frameWidthPx / frameHeightPx
    if (!srcAspect.isFinite() || !frameAspect.isFinite() || srcAspect <= 0f || frameAspect <= 0f) {
      return PhotoUvRect.IDENTITY
    }
    if (abs(srcAspect - frameAspect) < 1e-4f) {
      return PhotoUvRect.IDENTITY
    }
    return if (srcAspect > frameAspect) {
      // Source photo is wider than frame -> center-crop horizontally, keep full vertical span
      val visibleU = (frameAspect / srcAspect).coerceIn(0.001f, 1f)
      val marginU = (1f - visibleU) * 0.5f
      PhotoUvRect(
        uMin = marginU,
        vMin = 0f,
        uMax = marginU + visibleU,
        vMax = 1f,
      )
    } else {
      // Source photo is taller than frame -> center-crop vertically, keep full horizontal span
      val visibleV = (srcAspect / frameAspect).coerceIn(0.001f, 1f)
      val marginV = (1f - visibleV) * 0.5f
      PhotoUvRect(
        uMin = 0f,
        vMin = marginV,
        uMax = 1f,
        vMax = marginV + visibleV,
      )
    }
  }
}
