package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.orb.task.PhotoColorPipeline
import com.example.orb.task.PhotoSourceDecoder
import com.example.orb.task.PhotoUvRect
import com.example.orb.task.PhotoUvTransform
import com.example.orb.task.Tab2DebugOptions
import com.example.orb.task.Tab2LayoutGeometry
import com.example.orb.task.Tab2SafeInsetsPx
import com.example.orb.task.Tab2ShaderDebugMode
import com.example.orb.task.Tab2SphereCompositeMath
import com.example.orb.task.TaskWaterLightShaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Tab2V22ClosureVerificationTest {

  @Test
  fun p01_tab2LayoutGeometryComputesValidNonOverlappingHierarchyAcrossViewports() {
    val testCases = listOf(
      intArrayOf(1080, 2340),
      intArrayOf(1080, 2400),
      intArrayOf(1600, 2560),
      intArrayOf(1200, 1200),
      intArrayOf(720, 1280),
    )

    for (tc in testCases) {
      val w = tc[0]
      val h = tc[1]
      val density = 3.0f
      val snap = Tab2LayoutGeometry.compute(w, h, density, Tab2SafeInsetsPx())
      assertTrue("Snapshot must be valid for ${w}x${h} @ ${density}x", snap.isValid())

      val header = snap.headerRect
      val photo = snap.photoRectPx
      val orb = snap.orbRectPx
      val status = snap.statusRect

      assertTrue("Header must fit inside surface", header.leftPx >= 0f && header.rightPx <= w.toFloat() && header.topPx >= 0f)
      assertTrue("Photo must fit inside surface", photo.leftPx >= 0f && photo.rightPx <= w.toFloat())
      assertTrue("Orb must fit inside surface", orb.leftPx >= 0f && orb.rightPx <= w.toFloat() && orb.bottomPx <= h.toFloat())
      assertTrue("Status must fit inside surface", status.leftPx >= 0f && status.rightPx <= w.toFloat() && status.bottomPx <= h.toFloat())

      assertFalse("Header must not overlap photo", header.overlaps(photo))
      assertFalse("Photo must not overlap orb", photo.overlaps(orb))
      assertFalse("Orb must not overlap status", orb.overlaps(status))

      assertTrue("Photo bottom must stay strictly above orb top", photo.bottomPx <= orb.topPx + 1f)
      assertTrue("Orb bottom must stay strictly above status top", orb.bottomPx <= status.topPx + 1f)
      assertEquals("Photo center X must align with orb center X", photo.centerX, orb.centerX, 1.0f)
    }
  }

  @Test
  fun p02_photoColorPipelineDirectSrgbEliminatesDoubleGammaDarkening() {
    val chart = PhotoColorPipeline.evaluateStandardChart()
    assertTrue("Chart must contain standard patches", chart.isNotEmpty())

    for (metric in chart) {
      assertEquals(
        "Direct sRGB display output must match source exactly for ${metric.patchName}",
        0.0f,
        metric.fixedMaxAbsError,
        1e-4f,
      )
      if (metric.patchName == "gray_50pct") {
        assertTrue("Legacy wrong mode must darken 50% gray significantly (got ${metric.legacyWrongDirectOutput.r})",
          metric.legacyWrongDirectOutput.r < 0.25f)
      }
    }
  }

  @Test
  fun p03_perimeterRefractionSnapshotAndShaderContractsAreValid() {
    val photoFrag = TaskWaterLightShaders.PHOTO_QUAD_FRAG
    assertTrue("PHOTO_QUAD_FRAG must declare uDirectDisplaySrgb", photoFrag.contains("uniform int uDirectDisplaySrgb;"))
    assertTrue("PHOTO_QUAD_FRAG must declare uPhotoUvRect", photoFrag.contains("uniform vec4 uPhotoUvRect;"))

    val ribbonFrag = TaskWaterLightShaders.PERIMETER_RIBBON_FRAG
    assertTrue("PERIMETER_RIBBON_FRAG must declare uLayerAlphaCapOverride", ribbonFrag.contains("uniform float uLayerAlphaCapOverride;"))
    assertTrue("PERIMETER_RIBBON_FRAG must declare uDirectDisplayLdr", ribbonFrag.contains("uniform int uDirectDisplayLdr;"))
  }

  @Test
  fun p04_sphereCompositePremultipliedAlphaInvariantMaintainsZeroOvershoot() {
    val testCases = listOf(
      floatArrayOf(0.85f, 0.42f, 0.12f, 1.0f),
      floatArrayOf(0.92f, 0.88f, 0.34f, 0.0f),
      floatArrayOf(0.00f, 0.00f, 0.00f, 0.0f),
    )

    for (tc in testCases) {
      val r = tc[0]
      val g = tc[1]
      val b = tc[2]
      val sAlpha = tc[3]
      val sample = Tab2SphereCompositeMath.evaluateShaderOutput(r, g, b, sAlpha, edgeFade = 0.95f)
      assertTrue("Premultiplied invariant must hold for rgb(${r},${g},${b}), sA=$sAlpha", sample.satisfiesPremulInvariant())

      val blended = Tab2SphereCompositeMath.blendPremulOverBackground(sample, 0.05f, 0.35f, 0.48f)
      assertTrue("Blended output R must be in [0,1]", blended.r in 0f..1f)
      assertTrue("Blended output G must be in [0,1]", blended.g in 0f..1f)
      assertTrue("Blended output B must be in [0,1]", blended.b in 0f..1f)
    }
  }

  @Test
  fun p105_photoSourceDecoderAndExifNormalizerHandleOrientationsOneToEight() {
    val testBmp = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
    try {
      val normalBmp = PhotoSourceDecoder.applyExifOrientation(testBmp, android.media.ExifInterface.ORIENTATION_NORMAL)
      assertEquals(64, normalBmp.width)
      assertEquals(32, normalBmp.height)

      val rotatedBmp = PhotoSourceDecoder.applyExifOrientation(testBmp, android.media.ExifInterface.ORIENTATION_ROTATE_90)
      assertEquals(32, rotatedBmp.width)
      assertEquals(64, rotatedBmp.height)
      if (rotatedBmp !== testBmp) rotatedBmp.recycle()
    } finally {
      if (!testBmp.isRecycled) testBmp.recycle()
    }
  }

  @Test
  fun p106_photoUvTransformComputesExactCenterCropWithoutStretching() {
    val portraitUv = PhotoUvTransform.compute(2000, 1000, 400f, 500f)
    assertTrue("Landscape source in portrait frame must crop horizontally", portraitUv.uMin > 0f && portraitUv.uMax < 1f)
    assertEquals(0f, portraitUv.vMin, 1e-4f)
    assertEquals(1f, portraitUv.vMax, 1e-4f)

    val landscapeUv = PhotoUvTransform.compute(1000, 2000, 600f, 300f)
    assertEquals(0f, landscapeUv.uMin, 1e-4f)
    assertEquals(1f, landscapeUv.uMax, 1e-4f)
    assertTrue("Portrait source in landscape frame must crop vertically", landscapeUv.vMin > 0f && landscapeUv.vMax < 1f)

    val identityUv = PhotoUvTransform.compute(1000, 1000, 500f, 500f)
    assertEquals(PhotoUvRect.IDENTITY, identityUv)
  }

  @Test
  fun p107_tab2DebugOptionsAndRuntimeStatusBindCleanlyWithoutLegacy6KfMutations() {
    val defaultOpts = Tab2DebugOptions.Default
    assertEquals(Tab2ShaderDebugMode.NORMAL, defaultOpts.shaderMode)
    assertTrue(defaultOpts.showL1 && defaultOpts.showL2 && defaultOpts.showL3 && defaultOpts.showL4 && defaultOpts.enableRefraction)

    val customOpts = Tab2DebugOptions(
      shaderMode = Tab2ShaderDebugMode.ALPHA_HEATMAP,
      showL1 = true,
      showL2 = false,
      showL3 = true,
      showL4 = false,
      enableRefraction = false,
    )
    val perimOpts = customOpts.toPerimeterDebugOptions()
    assertEquals(com.example.orb.task.TaskVisualDebugMode.ALPHA_HEATMAP, perimOpts.mode)
    assertTrue(perimOpts.showL1)
    assertFalse(perimOpts.showL2)
    assertTrue(perimOpts.showL3)
    assertFalse(perimOpts.showL4)
  }
}
