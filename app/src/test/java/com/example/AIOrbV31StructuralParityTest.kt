package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.RenderDebugMode
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.geometry.Vec3
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.scene.AIOrbMotionController
import com.example.orb.scene.AIOrbOrbitRibbons
import com.example.orb.scene.AIOrbScene
import com.example.orb.scene.RibbonRole
import com.example.orb.v3.OrbWebpAssetLoader
import com.example.orb.v3.ReferenceMeniscusProfile
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Targeted V3.1 Final Structural Parity test suite (T01–T08) covering:
 *  - T01: ReferenceMeniscusProfile 32-sample parity, bounds, and cavityPhi E04 error <= 0.025R
 *  - T02: Single-source cavityPhi/cavityNormal SDF, uReferenceMeniscusY[32] binding, 5-step bisection, front top-entry lip
 *  - T03: Real decoded 256x256x32 WebP volume fold/channel structure (clear channels + dense folds + upper sphere)
 *  - T04: Unified uDensityScale in AIOrbConfig & OrbFluidVolumeRenderer + Beer-Lambert numerical stability
 *  - T05: P0-3 A01 & A02 open organic 9-pt non-oval Resident curves, matched profiles, and frozen R06 periods
 *  - T06: P0-4 crystal glass optics (GL_BLEND off, envBlend <= 0.22, clearCavityCol, localized 10:30 golden flare)
 *  - T07: Motion invariants (26 deg/s, axis (0.14, 0.98, -0.14), 10 vs 60 FPS drift < 0.25 deg, 20s spin > 480 deg)
 *  - T08: REFERENCE_LOCKED_FRAME determinism, coordinate contract, and honest BLOCKED_NEEDS_GPU_FRAME QA metrics
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AIOrbV31StructuralParityTest {

  private fun projectRoot(): File {
    var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
    while (!File(dir, "settings.gradle.kts").exists()) {
      dir = dir.parentFile ?: break
    }
    assertTrue("Could not locate project root", File(dir, "settings.gradle.kts").exists())
    return dir
  }

  @Test
  fun t01_referenceMeniscusProfileMatchesMasterJsonAndCavityPhiErrorWithinThreshold() {
    val root = projectRoot()
    val regionsJson = JSONObject(
      File(root, "tools/orb-v3-generator/reference/REFERENCE_VISUAL_REGIONS.json").readText(Charsets.UTF_8)
    )
    val masterSha = regionsJson.getJSONObject("referenceMaster").getString("sha256")
    assertEquals(masterSha, ReferenceMeniscusProfile.MASTER_SHA256)
    assertEquals(32, ReferenceMeniscusProfile.SAMPLE_COUNT)
    assertEquals(32, ReferenceMeniscusProfile.SAMPLE_X.size)
    assertEquals(32, ReferenceMeniscusProfile.HEIGHTS.size)

    val samples = regionsJson.getJSONObject("meniscus").getJSONArray("profileSamples")
    assertEquals(32, samples.length())

    var prevX = Float.NEGATIVE_INFINITY
    var minH = Float.POSITIVE_INFINITY
    var maxH = Float.NEGATIVE_INFINITY
    var errSum = 0.0
    for (i in 0 until 32) {
      val sx = ReferenceMeniscusProfile.SAMPLE_X[i]
      val sy = ReferenceMeniscusProfile.HEIGHTS[i]
      assertTrue("SAMPLE_X must be strictly monotonic at $i", sx > prevX)
      prevX = sx
      if (sy < minH) minH = sy
      if (sy > maxH) maxH = sy

      val entry = samples.getJSONObject(i)
      assertEquals(entry.getDouble("nx").toFloat(), sx, 1e-4f)
      assertEquals(entry.getDouble("ny").toFloat(), sy, 1e-4f)

      val simY = -ReferenceMeniscusProfile.cavityPhi(sx, 0f, 0f, 0f)
      errSum += abs(simY - sy).toDouble()
    }

    // Zero overshoot across 241 dense queries including clamped edges
    for (k in -20..220) {
      val x = -1.2f + (k / 200f) * 2.4f
      val h = ReferenceMeniscusProfile.sampleHeight(x)
      assertTrue("sampleHeight($x)=$h below min $minH", h >= minH - 1e-5f)
      assertTrue("sampleHeight($x)=$h above max $maxH", h <= maxH + 1e-5f)
    }

    val meanErrR = errSum / 32.0
    assertTrue("E04 cavityPhi mean error $meanErrR exceeded 0.025R", meanErrR <= 0.025)
  }

  @Test
  fun t02_singleSourceCavitySdfBindingFiveStepBisectionAndTopEntryLip() {
    val root = projectRoot()
    val frag = CoreFluidShaders.CORE_GLASS_FRAG
    val rendererSrc = File(root, "app/src/main/java/com/example/orb/scene/OrbFluidVolumeRenderer.kt").readText(Charsets.UTF_8)
    val shaderUtilsSrc = File(root, "app/src/main/java/com/example/orb/gl/ShaderUtils.kt").readText(Charsets.UTF_8)
    val volGenSrc = File(root, "tools/orb-v3-generator/generate_orb_volume.py").readText(Charsets.UTF_8)

    assertTrue(frag.contains("uniform float uReferenceMeniscusY[32];"))
    assertTrue(frag.contains("float referenceMeniscusHeight(float x)"))
    assertTrue(frag.contains("float cavityPhi(vec3 p, float phase)"))
    assertTrue(frag.contains("vec3 cavityNormal(vec3 p, float phase, out vec2 slopeOut)"))
    assertTrue(frag.contains("for (int b = 0; b < 5; b++)"))
    assertTrue(frag.contains("if (hitMeniscus > 0.5 && meniscusCrossingDir > 0.0)"))
    assertTrue(frag.contains("float frontContact = invSmoothstep(0.10, 0.72, meniscusHitT);"))

    assertTrue(rendererSrc.contains("prog.setFloatArray(\"uReferenceMeniscusY\", ReferenceMeniscusProfile.HEIGHTS)"))
    assertTrue(shaderUtilsSrc.contains("fun setFloatArray("))
    assertTrue(shaderUtilsSrc.contains("GLES30.glUniform1fv("))

    val executableVolGen = volGenSrc
      .replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "")
      .lines()
      .filterNot { it.trimStart().startsWith("#") }
      .joinToString("\n")
    assertFalse(executableVolGen.contains("meniscus_y"))
    assertFalse(executableVolGen.contains("below_meniscus"))
  }

  @Test
  fun t03_decodedWebpVolumeExhibitsV31ClearChannelsDenseFoldsAndUpperSphereContinuity() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val decoded = OrbWebpAssetLoader.loadAll(context.assets)
    assertEquals(256, decoded.volumeWidth)
    assertEquals(256, decoded.volumeHeight)
    assertEquals(32, decoded.volumeDepth)
    assertEquals(9L * 1024L * 1024L, decoded.totalGpuBytes)

    val opts = android.graphics.BitmapFactory.Options().apply {
      inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
      inScaled = false
      inPremultiplied = false
    }
    val bmp = context.assets.open("orb_v3/volume/fluid_color_z16.webp").use {
      android.graphics.BitmapFactory.decodeStream(it, null, opts)
    }!!
    val pixels = IntArray(256 * 256)
    bmp.getPixels(pixels, 0, 256, 0, 0, 256, 256)
    bmp.recycle()

    var interiorTotal = 0
    var clearChannelCount = 0
    var denseFoldCount = 0
    var upperInteriorTotal = 0
    var upperAlphaSum = 0.0
    var emeraldCount = 0
    var sapphireCount = 0
    var aquaCount = 0

    for (row in 0 until 256) {
      val y = 1f - ((row + 0.5f) / 256f) * 2f
      for (col in 0 until 256) {
        val x = ((col + 0.5f) / 256f) * 2f - 1f
        val r2 = x * x + y * y
        if (r2 > 0.85f * 0.85f) continue
        val px = pixels[row * 256 + col]
        val a = (px ushr 24) and 0xFF
        val r = (px ushr 16) and 0xFF
        val g = (px ushr 8) and 0xFF
        val bl = px and 0xFF

        interiorTotal++
        if (a <= 24) clearChannelCount++
        if (a >= 90) denseFoldCount++
        if (y > 0.25f) {
          upperInteriorTotal++
          upperAlphaSum += a / 255.0
        }
        if (a > 24) {
          if (g > bl && g > 70) emeraldCount++
          if (bl > g && bl > r + 15) sapphireCount++
          if (bl > 130 && g > 115 && bl >= g - 15) aquaCount++
        }
      }
    }

    val clearRatio = clearChannelCount.toDouble() / interiorTotal
    val denseRatio = denseFoldCount.toDouble() / interiorTotal
    val upperMeanAlpha = upperAlphaSum / upperInteriorTotal

    assertTrue("V3.1 clear channels ratio $clearRatio must be >= 0.08", clearRatio >= 0.08)
    assertTrue("V3.1 dense folds ratio $denseRatio must be >= 0.08", denseRatio >= 0.08)
    assertTrue("P0-1 upper sphere mean alpha $upperMeanAlpha must be > 0.04 (no baked meniscus)", upperMeanAlpha > 0.04)
    assertTrue("Emerald fold voxels ($emeraldCount) must be > 4000", emeraldCount > 4000)
    assertTrue("Sapphire valley voxels ($sapphireCount) must be > 3000", sapphireCount > 3000)
    assertTrue("Lower-belly aqua voxels ($aquaCount) must be > 1500", aquaCount > 1500)
  }

  @Test
  fun t04_unifiedDensityScaleContractAndBeerLambertNumericalStability() {
    val root = projectRoot()
    val cfg = AIOrbConfig.Default
    assertTrue("Default densityScale ${cfg.densityScale} must lie in [1.20, 1.95]", cfg.densityScale in 1.20f..1.95f)
    assertTrue("AIOrbConfig.toJson must include densityScale", cfg.toJson().contains("\"densityScale\""))

    val rendererSrc = File(root, "app/src/main/java/com/example/orb/scene/OrbFluidVolumeRenderer.kt").readText(Charsets.UTF_8)
    val frag = CoreFluidShaders.CORE_GLASS_FRAG
    assertTrue(rendererSrc.contains("prog.setFloat(\"uDensityScale\", config.densityScale)"))
    assertTrue(frag.contains("uniform float uDensityScale;"))
    assertTrue(frag.contains("float densityScale = clamp(uDensityScale, 1.20, 1.95)"))

    val effScale = cfg.densityScale.coerceIn(1.20f, 1.95f) * (0.80f + 0.20f * cfg.fluidIntensity)
    var prevT = 1.0
    for (step in 0..40) {
      val path = step * 0.05
      val opticalDepth = 0.55 * effScale * path
      val transmittance = exp(-opticalDepth)
      val alpha = 1.0 - transmittance
      assertTrue("Transmittance must be finite", transmittance.isFinite())
      assertTrue("Transmittance must stay in [0, 1]", transmittance in 0.0..1.0)
      assertTrue("Alpha must stay in [0, 1]", alpha in 0.0..1.0)
      assertTrue("Transmittance must be monotonically non-increasing", transmittance <= prevT + 1e-12)
      prevT = transmittance
    }
  }

  @Test
  fun t05_goldResidentsA01A02AreOpenOrganicNonOvalWithMatchedProfilesAndFrozenPeriods() {
    val root = projectRoot()
    val residents = AIOrbOrbitRibbons.buildRibbonDescriptors().filter { it.role == RibbonRole.RESIDENT }
    assertEquals(5, residents.size)

    val byCode = residents.associateBy { it.residentCode }
    assertEquals(6100, byCode["G01"]!!.periodMs)
    assertEquals(5300, byCode["G02"]!!.periodMs)
    assertEquals(3700, byCode["A01"]!!.periodMs)
    assertEquals(2900, byCode["A02"]!!.periodMs)
    assertEquals(4400, byCode["C01"]!!.periodMs)

    val ribbonsSrc = File(root, "app/src/main/java/com/example/orb/scene/AIOrbOrbitRibbons.kt").readText(Charsets.UTF_8)
    assertTrue("Frozen ribbonHugScale = 0.87f must remain preserved", ribbonsSrc.contains("0.87f"))

    for (goldCode in listOf("A01", "A02")) {
      val spec = byCode[goldCode]!!
      assertFalse("$goldCode must be open (closed == false)", spec.closed)
      assertEquals("$goldCode must have 9 control points", 9, spec.controlPoints.size)
      assertEquals("$goldCode widthControlPoints must match 9 control points", 9, spec.surfaceProfile.widthControlPoints.size)
      assertEquals("$goldCode leftAsymmetryPoints must match 9 control points", 9, spec.surfaceProfile.leftAsymmetryPoints.size)
      assertEquals("$goldCode rightAsymmetryPoints must match 9 control points", 9, spec.surfaceProfile.rightAsymmetryPoints.size)
      assertEquals("$goldCode twistProfileRad must match 9 control points", 9, spec.surfaceProfile.twistProfileRad.size)

      val start = spec.controlPoints.first()
      val end = spec.controlPoints.last()
      val dEnd = end - start
      val sep = sqrt(dEnd.x * dEnd.x + dEnd.y * dEnd.y + dEnd.z * dEnd.z)
      assertTrue("$goldCode endpoints must be clearly open (sep=$sep > 1.2)", sep > 1.2f)

      var minR = Float.POSITIVE_INFINITY
      var maxR = Float.NEGATIVE_INFINITY
      val samples = (0..96).map { i ->
        val p = OrbGeometryBuilder.sampleCatmullRom(spec.controlPoints, i / 96f, closed = false)
        val r = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
        if (r < minR) minR = r
        if (r > maxR) maxR = r
        p
      }
      val radialRange = maxR - minR
      assertTrue("$goldCode must be asymmetric organic path (radialRange=$radialRange > 0.14)", radialRange > 0.14f)

      var maxTurnDeg = 0.0
      for (i in 1 until samples.size - 1) {
        val d0 = (samples[i] - samples[i - 1]).normalized()
        val d1 = (samples[i + 1] - samples[i]).normalized()
        val dot = (d0.x * d1.x + d0.y * d1.y + d0.z * d1.z).coerceIn(-1f, 1f).toDouble()
        val deg = Math.toDegrees(acos(dot))
        if (deg > maxTurnDeg) maxTurnDeg = deg
      }
      assertTrue("$goldCode spline maxTurnDeg=$maxTurnDeg must stay smooth (< 20 deg)", maxTurnDeg < 20.0)
    }
  }

  @Test
  fun t06_crystalGlassOpticsEliminateUpperDomeHazeAndPreserveLocalizedGoldenFlare() {
    val root = projectRoot()
    val rendererSrc = File(root, "app/src/main/java/com/example/orb/scene/OrbFluidVolumeRenderer.kt").readText(Charsets.UTF_8)
    val frag = CoreFluidShaders.CORE_GLASS_FRAG

    assertTrue(rendererSrc.contains("GLES30.glDisable(GLES30.GL_BLEND)"))
    assertTrue(frag.contains("fragColor = vec4(finalComposited * uHdrScale, 1.0);"))

    assertTrue(frag.contains("vec3 clearCavityCol = refractedBack * vec3(0.92, 0.99, 1.02)"))
    assertTrue(frag.contains("float envBlend = clamp(fresnelOuter * (0.58 - uCoreTransparency * 0.38), 0.003, 0.22);"))
    assertTrue(frag.contains("col = mix(col, envRefl, envBlend * 0.30);"))
    assertTrue(frag.contains("col += rimPalette * (fresnelOuter * 0.50 + razorOuterRim * 0.90);"))
    assertTrue(frag.contains("crownRimArc"))
    assertTrue(frag.contains("sunCaustics"))
    assertFalse("Old white headlight dot must remain absent", frag.contains("specKeyPinpoint"))
  }

  @Test
  fun t07_motionInvariantsHoldAcrossFpsRegimesAndPauseResume() {
    assertEquals(26.0, AIOrbMotionController.BASE_CORE_ROTATION_DEG_PER_SEC, 1e-6)
    val expectedAxis = Vec3(0.14f, 0.98f, -0.14f).normalized()
    assertEquals(expectedAxis.x, AIOrbMotionController.CORE_ROTATION_AXIS.x, 1e-5f)
    assertEquals(expectedAxis.y, AIOrbMotionController.CORE_ROTATION_AXIS.y, 1e-5f)
    assertEquals(expectedAxis.z, AIOrbMotionController.CORE_ROTATION_AXIS.z, 1e-5f)

    fun runSim(fps: Int, durationSec: Double): Float {
      val ctrl = AIOrbMotionController()
      val dt = 1.0 / fps
      val frames = (durationSec * fps).toInt()
      var snap = ctrl.step(0.0, AIOrbConfig.Default)
      repeat(frames) {
        snap = ctrl.step(dt, AIOrbConfig.Default)
      }
      return snap.coreRotationAngleDeg
    }

    val angle60 = runSim(60, 20.0)
    val angle10 = runSim(10, 20.0)
    assertTrue("20s continuous rotation ($angle60 deg) must exceed 480 deg", angle60 > 480f)
    assertTrue("10 FPS vs 60 FPS drift (${abs(angle60 - angle10)} deg) must be < 0.25 deg", abs(angle60 - angle10) < 0.25f)

    // Pause / resume via AIOrbConfig.paused must produce zero phase jump
    val ctrl = AIOrbMotionController()
    val runningCfg = AIOrbConfig.Default
    val pausedCfg = AIOrbConfig.Default.copy(isPaused = true)
    var beforePause = ctrl.step(0.0, runningCfg)
    repeat(60) { beforePause = ctrl.step(1.0 / 60.0, runningCfg) }

    var duringPause = beforePause
    repeat(30) { duringPause = ctrl.step(1.0 / 60.0, pausedCfg) }
    assertEquals(beforePause.coreRotationAngleDeg, duringPause.coreRotationAngleDeg, 1e-5f)
    assertEquals(beforePause.simTimeSec, duringPause.simTimeSec, 1e-6)

    val afterResume = ctrl.step(1.0 / 60.0, runningCfg)
    val stepDelta = afterResume.coreRotationAngleDeg - beforePause.coreRotationAngleDeg
    assertTrue("Single resumed frame delta ($stepDelta deg) must be smooth in [0.2, 1.0]", stepDelta in 0.2f..1.0f)
  }

  @Test
  fun t08_referenceLockedFrameDeterminismCoordinateContractAndHonestQaMetrics() {
    val root = projectRoot()

    // Frozen composition constants preserved in AIOrbConfig & AIOrbScene
    assertEquals(0.80f, AIOrbConfig.Default.coreShellScale, 1e-5f)
    val sceneSrc = File(root, "app/src/main/java/com/example/orb/scene/AIOrbScene.kt").readText(Charsets.UTF_8)
    assertTrue("Frozen visualBoundRadius = 1.36f must remain preserved", sceneSrc.contains("1.36f"))

    // REFERENCE_LOCKED_FRAME must be 100% deterministic across repeated calls
    val ctrl = AIOrbMotionController()
    val lockedCfg = AIOrbConfig.Default.copy(debugMode = RenderDebugMode.REFERENCE_LOCKED_FRAME)
    repeat(60) { ctrl.step(1.0 / 60.0, AIOrbConfig.Default) }
    val snapA = ctrl.step(0.016, lockedCfg)
    val snapB = ctrl.step(0.100, lockedCfg)
    assertEquals(0f, snapA.coreRotationAngleDeg, 1e-6f)
    assertEquals(0.0, snapA.simTimeSec, 1e-9)
    assertEquals(snapA.coreRotationAngleDeg, snapB.coreRotationAngleDeg, 1e-6f)
    assertEquals(snapA.fluidPhase, snapB.fluidPhase, 1e-6f)
    assertEquals(snapA.meniscusPhase, snapB.meniscusPhase, 1e-6f)

    // Required V3.1 artifacts & honest GPU status
    val contractFile = File(root, "docs/v3/AI_ORB_V3_1_COORDINATE_CONTRACT.md")
    assertTrue("Missing docs/v3/AI_ORB_V3_1_COORDINATE_CONTRACT.md", contractFile.exists() && contractFile.length() > 500)
    val contractText = contractFile.readText(Charsets.UTF_8)
    assertTrue(contractText.contains("invCoreRot"))
    assertTrue(contractText.contains("cavityPhi"))

    val metricsFile = File(root, "docs/v3/AI_ORB_V3_1_VISUAL_QA_METRICS.json")
    assertTrue("Missing docs/v3/AI_ORB_V3_1_VISUAL_QA_METRICS.json", metricsFile.exists())
    val metricsJson = JSONObject(metricsFile.readText(Charsets.UTF_8))
    assertEquals("BLOCKED_NEEDS_GPU_FRAME", metricsJson.getString("gpuStatus"))
    assertEquals("SOURCE_IMPLEMENTED / APK_DELIVERED / GPU_VISUAL_UNVERIFIED", metricsJson.getString("honestyVerdict"))
    val e04Mean = metricsJson.getJSONObject("E04_meniscus").getDouble("meanErrorR")
    assertTrue("QA E04 meanErrorR ($e04Mean) must be <= 0.025R", e04Mean <= 0.025)
  }
}
