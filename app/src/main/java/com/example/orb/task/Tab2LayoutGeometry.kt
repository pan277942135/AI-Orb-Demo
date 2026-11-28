package com.example.orb.task

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure geometry helper computing Tab 2's lower Water World ecosystem square viewport so:
 * - Upper region holds the rounded photo + status caption with generous breathing room
 * - Lower region centers the 1:1 circular Water World ecosystem sphere without distortion,
 *   without clipping at the bottom edge, and without overlapping the photo frame.
 */
data class Tab2OrbViewportRect(
  val leftPx: Int,
  val bottomPx: Int, // OpenGL bottom-left origin
  val sidePx: Int,
  val surfaceWidthPx: Int,
  val surfaceHeightPx: Int,
) {
  val topPxFromTopOrigin: Int get() = surfaceHeightPx - (bottomPx + sidePx)
  val bottomPxFromTopOrigin: Int get() = surfaceHeightPx - bottomPx

  companion object {
    /**
     * Computes the centered lower square viewport for the 1254x1254 Water World ecosystem canvas
     * (whose crystal sphere occupies diameter ~62.7% of the 1254px canvas, leaving room for the
     * 5 orbiting Residents and 9 outer bubbles around the sphere!).
     */
    fun compute(
      surfaceWidthPx: Int,
      surfaceHeightPx: Int,
      photoFrame: PhotoFrameGeometry,
      bottomSafeInsetPx: Float = 0f,
    ): Tab2OrbViewportRect {
      val sw = max(1, surfaceWidthPx)
      val sh = max(1, surfaceHeightPx)
      val captionReservePx = max(44f, sh * 0.042f)
      val topLimit = (photoFrame.bottomPx + captionReservePx).coerceIn(sh * 0.36f, sh * 0.68f)
      val bottomLimit = (sh - bottomSafeInsetPx - sh * 0.012f).coerceAtLeast(topLimit + 120f)
      val availH = (bottomLimit - topLimit).coerceAtLeast(120f)
      val maxSideW = sw * 0.94f
      val maxSideH = availH * 1.12f
      val side = min(maxSideW, maxSideH).roundToInt().coerceIn(96, sw)
      val left = ((sw - side) / 2f).roundToInt().coerceAtLeast(0)
      val centerYFromTop = topLimit + availH * 0.50f
      val topFromTop = (centerYFromTop - side * 0.5f)
        .coerceIn(photoFrame.bottomPx + 4f, (sh - side).toFloat().coerceAtLeast(0f))
      val bottomGl = (sh - (topFromTop + side)).roundToInt().coerceIn(0, (sh - side).coerceAtLeast(0))
      return Tab2OrbViewportRect(
        leftPx = left,
        bottomPx = bottomGl,
        sidePx = side,
        surfaceWidthPx = sw,
        surfaceHeightPx = sh,
      )
    }
  }
}

/**
 * Physical pixel safe insets for Tab 2's full-screen GLSurfaceView & Compose overlay.
 * Always supplied once (single safe-area entry point; never double-applied!).
 */
data class Tab2SafeInsetsPx(
  val topPx: Float = 0f,
  val bottomPx: Float = 0f,
  val leftPx: Float = 0f,
  val rightPx: Float = 0f,
) {
  fun sanitized(): Tab2SafeInsetsPx = Tab2SafeInsetsPx(
    topPx = topPx.coerceAtLeast(0f),
    bottomPx = bottomPx.coerceAtLeast(0f),
    leftPx = leftPx.coerceAtLeast(0f),
    rightPx = rightPx.coerceAtLeast(0f),
  )
}

/**
 * Axis-aligned rectangle in GLSurfaceView local physical pixels
 * (origin = top-left, +X right, +Y down).
 */
data class Tab2RectPx(
  val leftPx: Float,
  val topPx: Float,
  val rightPx: Float,
  val bottomPx: Float,
) {
  val widthPx: Float get() = (rightPx - leftPx).coerceAtLeast(0f)
  val heightPx: Float get() = (bottomPx - topPx).coerceAtLeast(0f)
  val centerX: Float get() = (leftPx + rightPx) * 0.5f
  val centerY: Float get() = (topPx + bottomPx) * 0.5f

  fun isValid(): Boolean =
    leftPx.isFinite() && topPx.isFinite() && rightPx.isFinite() && bottomPx.isFinite() &&
      rightPx > leftPx && bottomPx > topPx

  fun overlaps(other: Tab2RectPx, epsPx: Float = 0.01f): Boolean {
    return leftPx < other.rightPx - epsPx &&
      rightPx > other.leftPx + epsPx &&
      topPx < other.bottomPx - epsPx &&
      bottomPx > other.topPx + epsPx
  }

  fun toGlBottomPx(surfaceHeightPx: Int): Int {
    return (surfaceHeightPx - bottomPx).roundToInt().coerceIn(0, max(0, surfaceHeightPx - 1))
  }
}

/**
 * Unified Tab 2 V2.2 Page Layout Snapshot shared identically by Compose UI and GLES 3.0 Renderer.
 */
data class Tab2PageLayoutSnapshot(
  val surfaceWidthPx: Int,
  val surfaceHeightPx: Int,
  val density: Float,
  val safeInsets: Tab2SafeInsetsPx,
  val headerRect: Tab2RectPx,
  val photoFrame: PhotoFrameGeometry,
  val orbViewport: Tab2OrbViewportRect,
  val statusRect: Tab2RectPx,
) {
  val photoRectPx: Tab2RectPx
    get() = Tab2RectPx(photoFrame.leftPx, photoFrame.topPx, photoFrame.rightPx, photoFrame.bottomPx)

  val orbRectPx: Tab2RectPx
    get() = Tab2RectPx(
      leftPx = orbViewport.leftPx.toFloat(),
      topPx = orbViewport.topPxFromTopOrigin.toFloat(),
      rightPx = (orbViewport.leftPx + orbViewport.sidePx).toFloat(),
      bottomPx = orbViewport.bottomPxFromTopOrigin.toFloat(),
    )

  val safeBoundsPx: Tab2RectPx
    get() = Tab2RectPx(
      leftPx = safeInsets.leftPx,
      topPx = safeInsets.topPx,
      rightPx = surfaceWidthPx.toFloat() - safeInsets.rightPx,
      bottomPx = surfaceHeightPx.toFloat() - safeInsets.bottomPx,
    )

  fun isValid(): Boolean =
    surfaceWidthPx >= 64 &&
      surfaceHeightPx >= 120 &&
      density > 0f &&
      headerRect.isValid() &&
      photoFrame.isValid() &&
      orbViewport.sidePx >= 48 &&
      statusRect.isValid()

  fun toTaskSceneLayoutSnapshot(): TaskSceneLayoutSnapshot {
    val orbCenterX = orbViewport.leftPx + orbViewport.sidePx * 0.5f
    val orbCenterY = orbViewport.topPxFromTopOrigin + orbViewport.sidePx * 0.5f
    val orbDiameter = orbViewport.sidePx.toFloat().coerceAtLeast(48f)
    val orbTransform = OrbViewportTransform(
      surfaceWidthPx = surfaceWidthPx,
      surfaceHeightPx = surfaceHeightPx,
      orbCenterPx = Vec2(orbCenterX, orbCenterY),
      orbDiameterPx = orbDiameter,
    )
    return TaskSceneLayoutSnapshot(
      surfaceWidthPx = surfaceWidthPx,
      surfaceHeightPx = surfaceHeightPx,
      photoFrame = photoFrame,
      orbTransform = orbTransform,
      isFromLiveComposeLayout = true,
    )
  }
}

/**
 * Pure, deterministic calculator for Tab 2 V2.2 vertical hierarchy.
 */
object Tab2LayoutGeometry {

  const val HEADER_HEIGHT_DP = 50f
  const val HEADER_TO_PHOTO_GAP_DP = 8f
  const val PHOTO_TO_ORB_GAP_DP = 20f
  const val ORB_TO_STATUS_GAP_DP = 10f
  const val STATUS_HEIGHT_DP = 30f
  const val BOTTOM_MARGIN_DP = 24f

  const val MIN_HEADER_TO_PHOTO_GAP_DP = 4f
  const val MIN_PHOTO_TO_ORB_GAP_DP = 12f
  const val MIN_ORB_TO_STATUS_GAP_DP = 6f
  const val MIN_BOTTOM_MARGIN_DP = 12f

  const val PHOTO_MAX_WIDTH_RATIO = 0.88f
  const val PHOTO_TARGET_HEIGHT_RATIO = 0.54f
  const val ORB_TARGET_HEIGHT_RATIO = 0.21f
  const val ORB_MIN_HEIGHT_RATIO = 0.16f

  fun topLeftToGlBottomPx(topPx: Float, heightPx: Float, surfaceHeightPx: Int): Int {
    val bottomFromTop = topPx + heightPx
    val maxGlBottom = max(0, surfaceHeightPx - heightPx.roundToInt())
    return (surfaceHeightPx - bottomFromTop).roundToInt().coerceIn(0, maxGlBottom)
  }

  fun compute(
    surfaceWidthPx: Int,
    surfaceHeightPx: Int,
    density: Float,
    safeInsets: Tab2SafeInsetsPx = Tab2SafeInsetsPx(),
  ): Tab2PageLayoutSnapshot {
    val sw = max(64, surfaceWidthPx)
    val sh = max(120, surfaceHeightPx)
    val d = if (density.isFinite() && density > 0f) density else 1f
    val insets = safeInsets.sanitized()

    val safeLeft = insets.leftPx.coerceAtMost(sw * 0.25f)
    val safeRight = insets.rightPx.coerceAtMost(sw * 0.25f)
    val safeTop = insets.topPx.coerceAtMost(sh * 0.25f)
    val safeBottom = insets.bottomPx.coerceAtMost(sh * 0.25f)

    val availW = (sw - safeLeft - safeRight).coerceAtLeast(64f)
    val availH = (sh - safeTop - safeBottom).coerceAtLeast(120f)
    val centerX = safeLeft + availW * 0.5f

    val aspect = PhotoFrameGeometry.CANONICAL_ASPECT_W_OVER_H

    val headerH = (HEADER_HEIGHT_DP * d).coerceAtMost(availH * 0.14f)
    val statusH = (STATUS_HEIGHT_DP * d).coerceAtMost(availH * 0.09f)

    var gapHeaderPhoto = HEADER_TO_PHOTO_GAP_DP * d
    var gapPhotoOrb = PHOTO_TO_ORB_GAP_DP * d
    var gapOrbStatus = ORB_TO_STATUS_GAP_DP * d
    var bottomMargin = BOTTOM_MARGIN_DP * d

    val maxPhotoW = availW * PHOTO_MAX_WIDTH_RATIO
    val targetPhotoH = availH * PHOTO_TARGET_HEIGHT_RATIO
    var photoW = min(maxPhotoW, targetPhotoH * aspect)
    var photoH = photoW / aspect

    var orbSide = min(availH * ORB_TARGET_HEIGHT_RATIO, availW * 0.72f)
      .coerceAtLeast(min(availW * 0.45f, 48f * d))

    fun totalUsed(): Float =
      headerH + gapHeaderPhoto + photoH + gapPhotoOrb + orbSide + gapOrbStatus + statusH + bottomMargin

    if (totalUsed() > availH) {
      val minGap0 = MIN_HEADER_TO_PHOTO_GAP_DP * d
      val minGap1 = MIN_PHOTO_TO_ORB_GAP_DP * d
      val minGap2 = MIN_ORB_TO_STATUS_GAP_DP * d
      val minBottom = MIN_BOTTOM_MARGIN_DP * d

      val reducibleGaps = (gapHeaderPhoto - minGap0) + (gapPhotoOrb - minGap1) +
        (gapOrbStatus - minGap2) + (bottomMargin - minBottom)
      val deficit1 = totalUsed() - availH
      if (reducibleGaps > 0.01f) {
        val ratio = (1f - (deficit1 / reducibleGaps).coerceIn(0f, 1f))
        gapHeaderPhoto = minGap0 + (gapHeaderPhoto - minGap0) * ratio
        gapPhotoOrb = minGap1 + (gapPhotoOrb - minGap1) * ratio
        gapOrbStatus = minGap2 + (gapOrbStatus - minGap2) * ratio
        bottomMargin = minBottom + (bottomMargin - minBottom) * ratio
      }
    }

    if (totalUsed() > availH) {
      val minPhotoH = availH * 0.34f
      val deficit2 = totalUsed() - availH
      val nextPhotoH = (photoH - deficit2).coerceAtLeast(minPhotoH)
      photoH = nextPhotoH
      photoW = min(maxPhotoW, photoH * aspect)
      photoH = photoW / aspect
    }

    if (totalUsed() > availH) {
      val minOrb = max(48f, availH * ORB_MIN_HEIGHT_RATIO)
      val deficit3 = totalUsed() - availH
      orbSide = (orbSide - deficit3).coerceAtLeast(minOrb)
    }

    if (totalUsed() > availH) {
      val fixed = headerH + statusH
      val bodyBudget = (availH - fixed).coerceAtLeast(48f)
      val currentBody = (totalUsed() - fixed).coerceAtLeast(1f)
      val scale = (bodyBudget / currentBody).coerceIn(0.2f, 1f)
      gapHeaderPhoto *= scale
      gapPhotoOrb *= scale
      gapOrbStatus *= scale
      bottomMargin *= scale
      photoH *= scale
      photoW = min(maxPhotoW, photoH * aspect)
      photoH = photoW / aspect
      orbSide = max(48f, orbSide * scale)
    }

    val slack = (availH - totalUsed()).coerceAtLeast(0f)
    val extraTopSlack = min(slack * 0.25f, 12f * d)
    val extraMidSlack = min(slack * 0.20f, 4f * d)

    val headerTop = safeTop
    val headerBottom = headerTop + headerH
    val headerRect = Tab2RectPx(
      leftPx = safeLeft,
      topPx = headerTop,
      rightPx = safeLeft + availW,
      bottomPx = headerBottom,
    )

    val photoTop = headerBottom + gapHeaderPhoto + extraTopSlack
    val photoBottom = photoTop + photoH
    val photoLeft = centerX - photoW * 0.5f
    val photoRight = centerX + photoW * 0.5f
    val cornerRadiusPx = (photoW * PhotoFrameGeometry.CANONICAL_CORNER_RADIUS_OF_W)
      .coerceIn(10f, min(photoW, photoH) * 0.45f)
    val photoFrame = PhotoFrameGeometry(
      leftPx = photoLeft,
      topPx = photoTop,
      rightPx = photoRight,
      bottomPx = photoBottom,
      cornerRadiusPx = cornerRadiusPx,
    )

    val orbSideInt = orbSide.roundToInt().coerceIn(48, min(sw, sh))
    val maxOrbTop = (sh - safeBottom - bottomMargin - statusH - gapOrbStatus - orbSideInt)
      .coerceAtLeast(photoBottom + 1f)
    val orbTop = (photoBottom + gapPhotoOrb + extraMidSlack).coerceAtMost(maxOrbTop)
    val orbLeftInt = (centerX - orbSideInt * 0.5f).roundToInt()
      .coerceIn(safeLeft.roundToInt(), max(safeLeft.roundToInt(), (sw - safeRight - orbSideInt).roundToInt()))
    val orbBottomGl = topLeftToGlBottomPx(orbTop, orbSideInt.toFloat(), sh)

    val orbViewport = Tab2OrbViewportRect(
      leftPx = orbLeftInt,
      bottomPx = orbBottomGl,
      sidePx = orbSideInt,
      surfaceWidthPx = sw,
      surfaceHeightPx = sh,
    )

    val orbBottomTopOrigin = orbViewport.bottomPxFromTopOrigin.toFloat()
    val statusTop = (orbBottomTopOrigin + gapOrbStatus)
      .coerceAtMost(sh - safeBottom - statusH)
    val statusBottom = (statusTop + statusH).coerceAtMost(sh - safeBottom)
    val statusRect = Tab2RectPx(
      leftPx = safeLeft,
      topPx = statusTop,
      rightPx = safeLeft + availW,
      bottomPx = statusBottom,
    )

    return Tab2PageLayoutSnapshot(
      surfaceWidthPx = sw,
      surfaceHeightPx = sh,
      density = d,
      safeInsets = Tab2SafeInsetsPx(safeTop, safeBottom, safeLeft, safeRight),
      headerRect = headerRect,
      photoFrame = photoFrame,
      orbViewport = orbViewport,
      statusRect = statusRect,
    )
  }
}
