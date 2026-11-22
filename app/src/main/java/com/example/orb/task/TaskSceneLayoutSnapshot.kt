package com.example.orb.task

/**
 * Immutable UI-to-GL layout snapshot unifying the real Compose-measured photo frame,
 * GPU Surface physical pixel dimensions, and the derived shared Orb viewport transform.
 *
 * All coordinates are strictly in GLSurfaceView local physical pixels
 * (origin = top-left, +X right, +Y down).
 */
data class TaskSceneLayoutSnapshot(
  val surfaceWidthPx: Int,
  val surfaceHeightPx: Int,
  val photoFrame: PhotoFrameGeometry,
  val orbTransform: OrbViewportTransform,
  val isFromLiveComposeLayout: Boolean,
) {
  val orbAnchorTopPx: Vec2 get() = orbTransform.orbAnchorTopPx
  val photoBottomCenterPx: Vec2 get() = photoFrame.bottomCenterPx
  val photoTopCenterPx: Vec2 get() = photoFrame.topCenterPx

  /** Vertical pixel span between Orb top anchor and Photo bottom center (Task Energy channel). */
  val taskChannelHeightPx: Float
    get() = (orbAnchorTopPx.y - photoBottomCenterPx.y).coerceAtLeast(24f)

  fun isValid(): Boolean =
    surfaceWidthPx >= 64 &&
      surfaceHeightPx >= 64 &&
      photoFrame.isValid() &&
      orbTransform.orbDiameterPx > 16f &&
      orbAnchorTopPx.y > photoBottomCenterPx.y

  companion object {
    /**
     * Converts window-space Compose coordinates of the photo box and the host GLSurfaceView
     * into exact GLSurfaceView-local physical pixel coordinates.
     */
    fun fromComposeWindowBounds(
      surfaceWindowLeftPx: Float,
      surfaceWindowTopPx: Float,
      surfaceWidthPx: Int,
      surfaceHeightPx: Int,
      photoWindowLeftPx: Float,
      photoWindowTopPx: Float,
      photoWindowRightPx: Float,
      photoWindowBottomPx: Float,
      photoCornerRadiusPx: Float,
    ): TaskSceneLayoutSnapshot {
      val localPhoto = PhotoFrameGeometry(
        leftPx = photoWindowLeftPx - surfaceWindowLeftPx,
        topPx = photoWindowTopPx - surfaceWindowTopPx,
        rightPx = photoWindowRightPx - surfaceWindowLeftPx,
        bottomPx = photoWindowBottomPx - surfaceWindowTopPx,
        cornerRadiusPx = photoCornerRadiusPx,
      )
      val safePhoto = if (localPhoto.isValid()) {
        localPhoto
      } else {
        PhotoFrameGeometry.canonicalForViewport(surfaceWidthPx, surfaceHeightPx)
      }
      val orb = OrbViewportTransform.forPhotoAndSurface(safePhoto, surfaceWidthPx, surfaceHeightPx)
      return TaskSceneLayoutSnapshot(
        surfaceWidthPx = surfaceWidthPx.coerceAtLeast(64),
        surfaceHeightPx = surfaceHeightPx.coerceAtLeast(64),
        photoFrame = safePhoto,
        orbTransform = orb,
        isFromLiveComposeLayout = localPhoto.isValid(),
      )
    }

    /** Fallback canonical snapshot computed purely from GLSurfaceView dimensions. */
    fun canonicalFallback(surfaceWidthPx: Int, surfaceHeightPx: Int): TaskSceneLayoutSnapshot {
      val photo = PhotoFrameGeometry.canonicalForViewport(surfaceWidthPx, surfaceHeightPx)
      val orb = OrbViewportTransform.forPhotoAndSurface(photo, surfaceWidthPx, surfaceHeightPx)
      return TaskSceneLayoutSnapshot(
        surfaceWidthPx = surfaceWidthPx.coerceAtLeast(64),
        surfaceHeightPx = surfaceHeightPx.coerceAtLeast(64),
        photoFrame = photo,
        orbTransform = orb,
        isFromLiveComposeLayout = false,
      )
    }
  }
}
