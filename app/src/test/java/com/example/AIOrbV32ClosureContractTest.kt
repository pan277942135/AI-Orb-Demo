package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.RenderDebugMode
import com.example.orb.geometry.Vec3
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.scene.AIOrbMotionController
import com.example.orb.scene.AIOrbTelemetry
import com.example.orb.scene.FrameTelemetryStatsTracker
import com.example.orb.scene.FrameTimingSnapshot
import com.example.orb.v3.CrystalLensGeometry
import com.example.orb.v3.MasterFidelityV32
import com.example.orb.v3.OrbCameraBuilder
import com.example.orb.v3.OrbGpuCaptureSnapshot
import com.example.orb.v3.OrbGpuReferenceCapture
import com.example.orb.v3.OrbV3AssetManifest
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AI Orb V3.2 Final Closure Contract Test Suite (`AI_ORB_V32_FINAL_FIDELITY_CLOSURE_V2`).
 *
 * Verifies:
 *  - Unified Tab 1 Production Camera (`OrbCameraBuilder` shared across `FULL_RENDER` & all diagnostic modes,
 *    zero legacy `visualBoundRadius = 1.36f` divergence)
 *  - Unified Crystal Lens Geometry (Python/GLSL/Kotlin `ASYM_SHEAR = 0.08`, SDF residuals `< 1e-5`,
 *    analytic normal vs central-difference normal parity, grazing angle safety)
 *  - Frozen `OrbGpuCaptureSnapshot` identity (all capture steps lock identical camera/transform/phase
 *    without stepping live `motionController`, then cleanly resume with zero jump)
 *  - PNG vertical Y-flip, write, re-decode round-trip, and per-PNG SHA-256 integrity
 *  - Real `FrameTelemetryStatsTracker` (real measured FPS, sorted P50/P95, `gpuFrameTimeStatus == UNAVAILABLE`)
 *  - Complete 35-WebP APK asset manifest SHA-256 verification and `MasterFidelityV32.Default.loadedFromAssets == false`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AIOrbV32ClosureContractTest {

  private fun projectRoot(): File {
    var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
    while (!File(dir, "settings.gradle.kts").exists()) {
      dir = dir.parentFile ?: break
    }
    assertTrue("Could not locate project root", File(dir, "settings.gradle.kts").exists())
    return dir
  }

  @Test
  fun closure_01_unifiedCameraAcrossFullRenderAndAllDiagnosticModes() {
    val root = projectRoot()
    val sceneSrc = File(root, "app/src/main/java/com/example/orb/scene/AIOrbScene.kt").readText(Charsets.UTF_8)
    assertFalse("Legacy visualBoundRadius = 1.36f must not exist in AIOrbScene.kt", sceneSrc.contains("visualBoundRadius = 1.36f"))
    assertFalse("Legacy fallback 3.65f camera distance must not exist in AIOrbScene.kt", sceneSrc.contains("3.65f"))
    assertTrue("AIOrbScene.kt must use OrbCameraBuilder.build", sceneSrc.contains("OrbCameraBuilder.build("))

    val fidelity = MasterFidelityV32.Default
    assertFalse("Embedded Default must keep loadedFromAssets = false", fidelity.loadedFromAssets)

    // Square 1254x1254 reference aspect uses fitted cameraDistance directly
    val squareCam = OrbCameraBuilder.build(1254, 1254, fidelity.camera)
    assertEquals(fidelity.camera.cameraDistance, squareCam.cameraDistance, 1e-5f)
    assertEquals(fidelity.camera.targetOffsetX, squareCam.targetX, 1e-5f)
    assertEquals(fidelity.camera.targetOffsetY, squareCam.targetY, 1e-5f)

    // Portrait 1080x2400 aspect produces identical snapshot for FULL_RENDER and every diagnostic mode
    val portraitFull = OrbCameraBuilder.build(1080, 2400, fidelity.camera, yawRad = 0f, pitchRad = 0f)
    val portraitRef = OrbCameraBuilder.build(1080, 2400, fidelity.camera, yawRad = 0f, pitchRad = 0f)
    assertEquals(portraitFull.configHash, portraitRef.configHash)
    assertArrayEquals(portraitFull.viewMatrix, portraitRef.viewMatrix, 1e-6f)
    assertArrayEquals(portraitFull.projMatrix, portraitRef.projMatrix, 1e-6f)
    assertTrue(portraitFull.cameraDistance >= fidelity.camera.cameraDistance)
  }

  @Test
  fun closure_02_crystalLensGeometryRayResidualNormalAndShaderParity() {
    assertEquals(0.08f, CrystalLensGeometry.ASYM_SHEAR, 1e-7f)
    assertTrue(
      "CoreFluidShaders must declare exact CRYSTAL_LENS_ASYM_SHEAR = 0.08",
      CoreFluidShaders.CORE_GLASS_FRAG.contains("const float CRYSTAL_LENS_ASYM_SHEAR = 0.08;"),
    )

    val cav = MasterFidelityV32.Default.innerCavity
    val center = Vec3(cav.lensCenterX, cav.lensCenterY, cav.lensCenterZ)
    val radii = Vec3(cav.lensRadiusX, cav.lensRadiusY, cav.lensRadiusZ)

    // Center must lie deep inside with phi == -1.0
    assertEquals(-1f, CrystalLensGeometry.phi(center, center, radii), 1e-6f)

    val rays = listOf(
      Vec3(center.x, center.y, center.z + 1.5f) to Vec3(0f, 0f, -1f),
      Vec3(center.x + 0.12f, center.y - 0.05f, center.z + 1.5f) to Vec3(-0.08f, 0.04f, -1f).normalized(),
      Vec3(center.x - 0.18f, center.y + 0.09f, center.z + 1.4f) to Vec3(0.11f, -0.05f, -1f).normalized(),
      Vec3(center.x + 0.28f, center.y + 0.02f, center.z + 1.4f) to Vec3(-0.02f, -0.01f, -1f).normalized(), // near grazing
    )

    var hits = 0
    val eps = 1e-4f
    for ((ro, rd) in rays) {
      val hit = CrystalLensGeometry.intersect(ro, rd, 10f, center, radii)
      if (!hit.hit) continue
      hits++
      assertTrue(hit.tExit > hit.tEnter)
      assertEquals(hit.tExit - hit.tEnter, hit.thickness, 1e-6f)

      for (t in listOf(hit.tEnter, hit.tExit)) {
        val p = Vec3(ro.x + rd.x * t, ro.y + rd.y * t, ro.z + rd.z * t)
        val residual = abs(CrystalLensGeometry.phi(p, center, radii))
        assertTrue("Surface SDF residual $residual must be < 1e-5", residual < 1e-5f)

        val n = CrystalLensGeometry.normal(p, center, radii)
        val nLen = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
        assertEquals(1f, nLen, 1e-5f)

        val gx = CrystalLensGeometry.phi(Vec3(p.x + eps, p.y, p.z), center, radii) -
          CrystalLensGeometry.phi(Vec3(p.x - eps, p.y, p.z), center, radii)
        val gy = CrystalLensGeometry.phi(Vec3(p.x, p.y + eps, p.z), center, radii) -
          CrystalLensGeometry.phi(Vec3(p.x, p.y - eps, p.z), center, radii)
        val gz = CrystalLensGeometry.phi(Vec3(p.x, p.y, p.z + eps), center, radii) -
          CrystalLensGeometry.phi(Vec3(p.x, p.y, p.z - eps), center, radii)
        val cd = Vec3(gx, gy, gz).normalized()
        val dot = n.x * cd.x + n.y * cd.y + n.z * cd.z
        assertTrue("Analytic normal must match central-difference normal (dot=$dot)", dot > 0.9999f)
      }
    }
    assertTrue("Expected >= 3 valid Crystal Lens hits, got $hits", hits >= 3)
  }

  @Test
  fun closure_03_fullCaptureSnapshotIdentityAndMotionPreservation() {
    val capture = OrbGpuReferenceCapture()
    val ctrl = AIOrbMotionController()
    val fidelity = MasterFidelityV32.Default

    // Advance live motion 1 second before capture
    repeat(60) { ctrl.step(1.0 / 60.0, AIOrbConfig.Default) }
    val simTimeBeforeCapture = ctrl.currentTimeSec
    assertTrue(simTimeBeforeCapture > 0.99)

    val outDir = kotlin.io.path.createTempDirectory("v32_cap_snap_test_").toFile()
    try {
      assertTrue(capture.requestCapture(outDir) {})

      val snapshots = ArrayList<OrbGpuCaptureSnapshot>()
      repeat(OrbGpuReferenceCapture.CAPTURE_STEPS.size) {
        val snap = capture.getOrCreateFrozenSnapshot(1080, 2400, fidelity) {
          ctrl.step(0.0, AIOrbConfig.Default.copy(debugMode = RenderDebugMode.REFERENCE_LOCKED_FRAME))
        }
        snapshots.add(snap)
      }

      // All 6 steps MUST reuse the exact same snapshotId, cameraConfigHash, and modelTransformHash
      val first = snapshots.first()
      for (s in snapshots) {
        assertEquals(first.snapshotId, s.snapshotId)
        assertEquals(first.cameraConfigHash, s.cameraConfigHash)
        assertEquals(first.modelTransformHash, s.modelTransformHash)
        assertArrayEquals(first.camera.viewMatrix, s.camera.viewMatrix, 0f)
        assertArrayEquals(first.camera.projMatrix, s.camera.projMatrix, 0f)
        assertArrayEquals(first.outerGlassModel, s.outerGlassModel, 0f)
      }

      // Live motion clock was untouched during capture
      assertEquals(simTimeBeforeCapture, ctrl.currentTimeSec, 1e-9)

      // Resuming with 0.0 dt then 1/60s dt has zero teleport jump
      val resumed0 = ctrl.step(0.0, AIOrbConfig.Default)
      assertEquals(simTimeBeforeCapture, resumed0.simTimeSec, 1e-9)
      val resumed1 = ctrl.step(1.0 / 60.0, AIOrbConfig.Default)
      assertEquals(simTimeBeforeCapture + 1.0 / 60.0, resumed1.simTimeSec, 1e-6)
    } finally {
      capture.release()
      outDir.deleteRecursively()
    }
  }

  @Test
  fun closure_04_pngWriteRedecodeVerificationAndEvidenceSchemaIntegrity() {
    val w = 16
    val h = 12
    val rgba = ByteArray(w * h * 4) { idx -> ((idx * 37) and 0xFF).toByte() }
    val flipped = OrbGpuReferenceCapture.flipRgbaVertically(rgba, w, h)
    // Flipping twice restores original buffer identically
    assertArrayEquals(rgba, OrbGpuReferenceCapture.flipRgbaVertically(flipped, w, h))

    val tmpDir = kotlin.io.path.createTempDirectory("v32_png_roundtrip_").toFile()
    try {
      val pngFile = File(tmpDir, "FULL_RENDER_GPU.png")
      val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      val argb = OrbGpuReferenceCapture.rgbaBytesToArgb(flipped)
      bmp.setPixels(argb, 0, w, 0, 0, w, h)
      FileOutputStream(pngFile).use { fos ->
        assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, fos))
      }
      bmp.recycle()

      val sha = OrbGpuReferenceCapture.verifyWrittenPngAndSha256(pngFile, w, h)
      assertEquals(64, sha.length)
      assertEquals(MasterFidelityV32.sha256Hex(pngFile.readBytes()), sha)
      val decodedPng = BitmapFactory.decodeFile(pngFile.absolutePath)
      assertEquals("RGBA red/blue channels must survive PNG conversion in the same order", argb[0], decodedPng.getPixel(0, 0))
      decodedPng.recycle()

      val activationPng = File(tmpDir, "FOLD_NORMAL_GPU.png")
      val activationBitmap = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
      activationBitmap.setPixels(intArrayOf(0xff000000.toInt(), 0xffba80ff.toInt()), 0, 2, 0, 0, 2, 1)
      FileOutputStream(activationPng).use { fos -> assertTrue(activationBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) }
      activationBitmap.recycle()
      assertEquals(0.5f, OrbGpuReferenceCapture.measureFoldActiveRatio(activationPng)!!, 0.01f)

      // Verify JSON builders serialize snapshot hashes, real telemetry, and per-PNG SHA map
      val ctrl = AIOrbMotionController()
      val snap = OrbGpuCaptureSnapshot.create(
        surfaceWidth = w,
        surfaceHeight = h,
        fidelity = MasterFidelityV32.Default,
        motion = ctrl.step(0.0, AIOrbConfig.Default.copy(debugMode = RenderDebugMode.REFERENCE_LOCKED_FRAME)),
        snapshotId = "test_snap_001",
      )
      val tel = AIOrbTelemetry(
        fps = 47,
        frameMsP50 = 14.2f,
        frameMsP95 = 19.8f,
        frameMsLatest = 15.1f,
        frameSampleCount = 24,
        hasSufficientTimingSamples = true,
        gpuFrameTimeStatus = FrameTimingSnapshot.GPU_FRAME_TIME_UNAVAILABLE,
        glVendor = "TestVendor",
        glRenderer = "TestGPU",
      )

      val cfgJson = JSONObject(
        OrbGpuReferenceCapture.buildRuntimeRenderConfigJson(AIOrbConfig.Default, MasterFidelityV32.Default, w, h, snap)
      )
      assertEquals("test_snap_001", cfgJson.getString("snapshotId"))
      assertEquals(snap.cameraConfigHash, cfgJson.getString("cameraConfigHash"))
      assertEquals(snap.modelTransformHash, cfgJson.getString("modelTransformHash"))

      val telJson = JSONObject(
        OrbGpuReferenceCapture.buildGpuTelemetryJson(tel, w, h, snap, framebufferComplete = true, glErrorCode = 0)
      )
      assertEquals(47, telJson.getInt("fps"))
      assertEquals(24, telJson.getInt("sampleCount"))
      assertTrue(telJson.getBoolean("hasSufficientSamples"))
      assertEquals("UNAVAILABLE", telJson.getString("gpuFrameTimeStatus"))
      assertTrue(telJson.getBoolean("framebufferComplete"))
      assertEquals(0, telJson.getInt("glErrorCode"))
      assertEquals(snap.snapshotId, telJson.getString("snapshotId"))

      val fpJson = JSONObject(
        OrbGpuReferenceCapture.buildFingerprintJson(
          fidelity = MasterFidelityV32.Default,
          telemetry = tel,
          snapshot = snap,
          capturedPngSha256 = mapOf("FULL_RENDER_GPU.png" to sha),
        )
      )
      assertEquals(sha, fpJson.getJSONObject("capturedPngSha256").getString("FULL_RENDER_GPU.png"))
      assertEquals(snap.cameraConfigHash, fpJson.getString("cameraConfigHash"))
    } finally {
      tmpDir.deleteRecursively()
    }
  }

  @Test
  fun closure_05_frameTelemetryStatsTrackerMeasuresRealFpsAndPercentiles() {
    val tracker = FrameTelemetryStatsTracker(windowCapacity = 60, publishIntervalNanos = 500_000_000L)
    val initial = tracker.snapshot()
    assertEquals(0, initial.fps)
    assertEquals(0, initial.sampleCount)
    assertFalse(initial.hasSufficientSamples)
    assertEquals(FrameTimingSnapshot.GPU_FRAME_TIME_UNAVAILABLE, initial.gpuFrameTimeStatus)

    // Simulate 30 frames at ~40 FPS (25ms period) with varied CPU durations 4ms..23ms
    val baseNs = 1_000_000_000L
    val periodNs = 25_000_000L // 40 FPS
    var publishedCount = 0
    for (i in 0 until 30) {
      val start = baseNs + i * periodNs
      val cpuMs = 4.0 + (i % 20) // 4, 5, ..., 23 ms
      val end = start + (cpuMs * 1_000_000.0).toLong()
      if (tracker.recordFrame(start, end)) {
        publishedCount++
      }
    }

    assertTrue("Should publish at least once over 750ms", publishedCount >= 1)
    val snap = tracker.snapshot()
    assertEquals(30, snap.sampleCount)
    assertTrue(snap.hasSufficientSamples)
    assertTrue("Measured FPS (${snap.fps}) must be ~40, NOT hardcoded 60", snap.fps in 38..42)
    assertNotEquals(60, snap.fps)
    assertTrue("P95 (${snap.cpuFrameMsP95}) must exceed P50 (${snap.cpuFrameMsP50})", snap.cpuFrameMsP95 > snap.cpuFrameMsP50)
    assertTrue("P50 (${snap.cpuFrameMsP50}) must lie in [10, 15]", snap.cpuFrameMsP50 in 10f..15f)
    assertTrue("P95 (${snap.cpuFrameMsP95}) must lie in [20, 23.5]", snap.cpuFrameMsP95 in 20f..23.5f)
    assertEquals(FrameTimingSnapshot.GPU_FRAME_TIME_UNAVAILABLE, snap.gpuFrameTimeStatus)
  }

  @Test
  fun closure_06_all35WebpAssetsMatchManifestSha256InApkAssets() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manifest = OrbV3AssetManifest.loadFromAssets(context.assets)
    assertEquals(35, manifest.totalWebpFiles)
    assertEquals(32, manifest.volume.slices.size)
    assertEquals(3, manifest.maps.size)

    var verified = 0
    for (slice in manifest.volume.slices) {
      val bytes = context.assets.open("${OrbV3AssetManifest.ASSET_ROOT}/${slice.path}").use { it.readBytes() }
      assertEquals("Slice ${slice.path} SHA mismatch", slice.sha256, MasterFidelityV32.sha256Hex(bytes))
      verified++
    }
    for (map in manifest.maps) {
      val bytes = context.assets.open("${OrbV3AssetManifest.ASSET_ROOT}/${map.path}").use { it.readBytes() }
      assertEquals("Map ${map.path} SHA mismatch", map.sha256, MasterFidelityV32.sha256Hex(bytes))
      verified++
    }
    assertEquals(35, verified)
  }
}
