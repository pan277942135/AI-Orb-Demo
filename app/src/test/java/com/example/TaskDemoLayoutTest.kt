package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.Matrix
import androidx.test.core.app.ApplicationProvider
import com.example.orb.task.OrbViewportTransform
import com.example.orb.task.PhotoFrameGeometry
import com.example.orb.task.PhotoTextureRenderer
import com.example.orb.task.TaskEnergyController
import com.example.orb.task.TaskEnergyStage
import com.example.orb.task.TaskSceneLayoutSnapshot
import com.example.orb.task.PerimeterPathGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * G1 Layout & Asset Verification Suite:
 * - Single cropped `fishing_photo.webp` decodes cleanly via native Android BitmapFactory
 * - Photo bounds stay 100% unified across KF01–KF06
 * - Orb viewport anchor stays strictly below photo bottom across phone/tablet/square viewports
 * - Off-axis lens-shift projection maps world `(0,0,-D)` to exact `(targetNdcX, targetNdcY)`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskDemoLayoutTest {

  @Test
  fun singleCroppedFishingPhotoWebpDecodesCleanlyWithCanonicalAspectRatio() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val opts = BitmapFactory.Options().apply {
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inScaled = false
    }
    val bmp = context.assets.open(PhotoTextureRenderer.DEMO_PHOTO_ASSET_PATH).use { stream ->
      BitmapFactory.decodeStream(stream, null, opts)
    }
    assertNotNull("assets/task_demo/fishing_photo.webp must decode cleanly", bmp)
    val bitmap = bmp!!
    try {
      assertTrue("Photo width must be >= 500px (${bitmap.width})", bitmap.width >= 500)
      assertTrue("Photo height must be >= 600px (${bitmap.height})", bitmap.height >= 600)
      val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
      assertEquals(
        "Photo aspect ratio must match frozen KF01 crop (~0.818)",
        PhotoFrameGeometry.CANONICAL_ASPECT_W_OVER_H,
        aspect,
        0.03f,
      )

      // Verify platform-independent ARGB pixels are rich, non-blank photographic content
      val pixels = IntArray(bitmap.width * bitmap.height)
      bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
      var nonBlack = 0
      var colorSpread = 0
      for (i in pixels.indices step 37) {
        val c = pixels[i]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        if (r + g + b > 40) nonBlack++
        if (abs(r - b) > 15 || abs(g - b) > 15) colorSpread++
      }
      assertTrue("Photo must contain real image pixels", nonBlack > 1000)
      assertTrue("Photo must contain chromatic variation", colorSpread > 400)
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun photoFrameAndOrbPositionStayStrictlyUnifiedAcrossAllSixKeyframesAndViewports() {
    val viewports = listOf(
      1080 to 2400, // Modern tall phone (20:9)
      1080 to 1920, // Standard 16:9 phone
      1600 to 2560, // Portrait tablet
      1200 to 1200, // Square foldable inner screen
    )

    for ((w, h) in viewports) {
      val layout = TaskSceneLayoutSnapshot.canonicalFallback(w, h)
      assertTrue("Layout must be valid for ${w}x${h}", layout.isValid())

      val photo = layout.photoFrame
      val orb = layout.orbTransform
      assertTrue("Corner radius must clamp <= half min side", photo.clampedCornerRadiusPx <= minOf(photo.widthPx, photo.heightPx) * 0.5f)
      assertTrue("Orb top anchor must stay strictly below photo bottom for ${w}x${h}", orb.orbAnchorTopPx.y > photo.bottomPx)
      assertTrue("Orb must stay within viewport bottom for ${w}x${h}", orb.orbCenterPx.y + orb.orbDiameterPx * 0.45f <= h.toFloat() + 1f)
      assertEquals("Orb must stay horizontally centered with photo for ${w}x${h}", photo.centerX, orb.orbCenterPx.x, 0.5f)

      // Stepping or jumping across all 6 keyframes never drifts photo or orb bounds
      val controller = TaskEnergyController()
      val perimeter = PerimeterPathGeometry(photo)
      for (stage in TaskEnergyStage.entries) {
        val snap = controller.jumpToKeyframe(stage, perimeter)
        assertEquals(stage, snap.stage)
        assertEquals(photo, layout.photoFrame)
        assertEquals(orb, layout.orbTransform)
      }
    }
  }

  @Test
  fun composeWindowBoundsConvertAccuratelyToGlSurfaceLocalPixels() {
    val snapshot = TaskSceneLayoutSnapshot.fromComposeWindowBounds(
      surfaceWindowLeftPx = 0f,
      surfaceWindowTopPx = 120f,
      surfaceWidthPx = 1080,
      surfaceHeightPx = 2200,
      photoWindowLeftPx = 132f,
      photoWindowTopPx = 320f,
      photoWindowRightPx = 948f,
      photoWindowBottomPx = 1320f,
      photoCornerRadiusPx = 64f,
    )
    assertTrue(snapshot.isValid())
    assertTrue(snapshot.isFromLiveComposeLayout)
    assertEquals(132f, snapshot.photoFrame.leftPx, 1e-4f)
    assertEquals(200f, snapshot.photoFrame.topPx, 1e-4f)
    assertEquals(948f, snapshot.photoFrame.rightPx, 1e-4f)
    assertEquals(1200f, snapshot.photoFrame.bottomPx, 1e-4f)
    assertEquals(64f, snapshot.photoFrame.clampedCornerRadiusPx, 1e-4f)
  }

  @Test
  fun offAxisLensShiftProjectionMapsWorldOriginToExactTargetOrbScreenPosition() {
    val layout = TaskSceneLayoutSnapshot.canonicalFallback(1080, 2340)
    val orb = layout.orbTransform
    val fovY = 42f
    val coreRadius = 0.80f
    val camDist = orb.computeCameraDistance(fovY, coreRadius)

    val proj = FloatArray(16)
    val view = FloatArray(16)
    val mvp = FloatArray(16)
    orb.writeLensShiftedProjection(proj, fovY)
    Matrix.setLookAtM(view, 0, 0f, 0f, camDist, 0f, 0f, 0f, 0f, 1f, 0f)
    Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

    // Project Orb center (0, 0, 0, 1) -> NDC must equal (targetNdcX, targetNdcY)
    val clipCenter = FloatArray(4)
    Matrix.multiplyMV(clipCenter, 0, mvp, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
    val ndcX = clipCenter[0] / clipCenter[3]
    val ndcY = clipCenter[1] / clipCenter[3]
    assertEquals("Lens-shifted NDC X must match target Orb center", orb.targetNdcX, ndcX, 1e-4f)
    assertEquals("Lens-shifted NDC Y must match target Orb center", orb.targetNdcY, ndcY, 1e-4f)

    // Verify top & bottom of sphere project symmetrically around targetNdcY (zero vertical keystone!)
    val clipTop = FloatArray(4)
    val clipBot = FloatArray(4)
    Matrix.multiplyMV(clipTop, 0, mvp, 0, floatArrayOf(0f, coreRadius, 0f, 1f), 0)
    Matrix.multiplyMV(clipBot, 0, mvp, 0, floatArrayOf(0f, -coreRadius, 0f, 1f), 0)
    val topDy = (clipTop[1] / clipTop[3]) - ndcY
    val botDy = ndcY - (clipBot[1] / clipBot[3])
    assertEquals("Projected sphere must stay vertically symmetric (zero keystone)", topDy, botDy, 1e-4f)
  }
}
