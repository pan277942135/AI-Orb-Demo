package com.example

import com.example.orb.config.AIOrbConfig
import com.example.orb.config.RenderDebugMode
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.scene.AIOrbMotionController
import com.example.orb.scene.AIOrbOrbitRibbons
import com.example.orb.scene.AIOrbScene
import com.example.orb.scene.RibbonRole
import com.example.orb.v3.OrbV3LoadState
import com.example.orb.v3.OrbVolumeTexture3D
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Work P0 (G0–G4) Reference Parity Closeout Automated Regression Suite.
 *
 * Covers all 8 mandatory closeout verification contracts:
 * 1. Reference Region Extraction Test (`REFERENCE_VISUAL_REGIONS.json` + preview PNG)
 * 2. Reference Geometry Binding Test (`generate_orb_volume.py` & `generate_orb_maps.py`)
 * 3. Meniscus Single-Source Contract Test (NO baked meniscus in generator; GPU sole source)
 * 4. Texture Color-Space & Error-State Contract Test (4 independent uniforms + `V3_ASSET_ERROR`)
 * 5. Glass Composite Contract Test (`GL_BLEND` disabled on core pass; in-shader `originalBg` mix + `alpha = 1.0`, zero white headlight dot)
 * 6. Resident Projection Regression Test (`C01` open-ended & de-kinked, bounded viewport fit)
 * 7. Motion Time-Integration Test (slow frames > 0.05s preserve full time; pause/resume zero jump; hitch clamp)
 * 8. Reference Locked Render Determinism & Comparison Artifact Test (`REFERENCE_LOCKED_FRAME`)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AIOrbV3G0G4ParityCloseoutTest {

  private fun projectRoot(): File {
    var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
    while (!File(dir, "settings.gradle.kts").exists()) {
      dir = dir.parentFile ?: break
    }
    assertTrue("Could not locate project root", File(dir, "settings.gradle.kts").exists())
    return dir
  }

  @Test
  fun test1_referenceRegionExtractionArtifactsExistAndMatchMasterGeometry() {
    val root = projectRoot()
    val regionsJson = File(root, "tools/orb-v3-generator/reference/REFERENCE_VISUAL_REGIONS.json")
    val previewPng = File(root, "tools/orb-v3-generator/reference/reference_regions_preview.png")
    val auditDoc = File(root, "docs/v3/G0_G4_BASELINE_AUDIT.md")

    assertTrue("REFERENCE_VISUAL_REGIONS.json must exist", regionsJson.exists())
    assertTrue("reference_regions_preview.png must exist", previewPng.exists() && previewPng.length() > 8192)
    assertTrue("G0_G4_BASELINE_AUDIT.md must exist", auditDoc.exists())

    val json = JSONObject(regionsJson.readText(Charsets.UTF_8))
    assertEquals("AI_ORB_V3_REFERENCE_VISUAL_REGIONS_1_0", json.getString("schemaVersion"))
    val sphere = json.getJSONObject("sphere")
    val diamRatio = sphere.getDouble("diameterRatio")
    assertTrue("Extracted master sphere diameterRatio ($diamRatio) must be in [0.46, 0.58]", diamRatio in 0.46..0.58)

    val interior = json.getJSONObject("interiorRegions")
    for (key in listOf("E03_upper_cavity", "E05_emerald", "E06_sapphire", "E07_aqua", "E08_caustics", "E09_upper_left_flare")) {
      assertTrue("Missing interior region $key", interior.has(key))
      assertTrue("$key must have positive pixelCount", interior.getJSONObject(key).getInt("pixelCount") > 100)
    }
    // Verify 32x32 spatial grids exist for volume-driving regions
    for (gridKey in listOf("E05_emerald", "E06_sapphire", "E07_aqua")) {
      assertEquals(1024, interior.getJSONObject(gridKey).getJSONArray("spatialGrid32x32").length())
    }
  }

  @Test
  fun test2_generatorsExplicitlyBindToReferenceVisualRegionsJson() {
    val root = projectRoot()
    val volGen = File(root, "tools/orb-v3-generator/generate_orb_volume.py").readText(Charsets.UTF_8)
    val mapGen = File(root, "tools/orb-v3-generator/generate_orb_maps.py").readText(Charsets.UTF_8)

    assertTrue("generate_orb_volume.py must load REFERENCE_VISUAL_REGIONS.json", volGen.contains("REFERENCE_VISUAL_REGIONS.json"))
    assertTrue("generate_orb_volume.py must sample extracted E05_emerald grid", volGen.contains("E05_emerald"))
    assertTrue("generate_orb_volume.py must sample extracted E06_sapphire grid", volGen.contains("E06_sapphire"))
    assertTrue("generate_orb_volume.py must sample extracted E07_aqua grid", volGen.contains("E07_aqua"))

    assertTrue("generate_orb_maps.py must load REFERENCE_VISUAL_REGIONS.json", mapGen.contains("REFERENCE_VISUAL_REGIONS.json"))
    assertTrue("generate_orb_maps.py must bind to E09_upper_left_flare", mapGen.contains("E09_upper_left_flare"))
    assertFalse("generate_orb_maps.py must NOT embed circular dot_key > 0.955 headlight spot", mapGen.contains("smoothstep(0.955, 0.996, dot_key)"))
  }

  @Test
  fun test3_meniscusSingleSourceContractHasNoBakedGeneratorClipping() {
    val root = projectRoot()
    val volGen = File(root, "tools/orb-v3-generator/generate_orb_volume.py").readText(Charsets.UTF_8)
    val manifestText = File(root, "app/src/main/assets/orb_v3/manifest/ORB_V3_ASSET_MANIFEST.json").readText(Charsets.UTF_8)
    val frag = CoreFluidShaders.CORE_GLASS_FRAG

    // P0-1: Executable Python code in generate_orb_volume.py MUST NOT clip fluid with baked meniscus_y / below_meniscus!
    val executableVolGen = volGen
      .replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "")
      .lines()
      .filterNot { it.trimStart().startsWith("#") }
      .joinToString("\n")
    assertFalse("P0-1 violation: generate_orb_volume.py must not contain baked meniscus_y clipping", executableVolGen.contains("meniscus_y"))
    assertFalse("P0-1 violation: generate_orb_volume.py must not contain baked below_meniscus clipping", executableVolGen.contains("below_meniscus"))
    assertTrue("Manifest must declare GPU single-source meniscus", manifestText.contains("GPU_SINGLE_SOURCE_CORE_GLASS_FRAG"))

    // GPU shader must own meniscus intersection, normal, and integration boundary
    assertTrue(frag.contains("meniscusHeight("))
    assertTrue(frag.contains("meniscusNormal("))
    assertTrue(frag.contains("float submerge = invSmoothstep(midM - 0.025, midM + 0.025, coreMidP.y);"))
  }

  @Test
  fun test4_perTextureColorSpaceUniformsAndStrictV3ErrorState() {
    val frag = CoreFluidShaders.CORE_GLASS_FRAG

    // P0-6: 4 independent per-texture sRGB/linear uniforms, zero shared uVolumeIsSrgb!
    assertFalse("Shared uVolumeIsSrgb uniform must be replaced by per-texture uniforms", frag.contains("uniform int uVolumeIsSrgb;"))
    assertTrue(frag.contains("uniform int uFluidVolumeIsSrgb;"))
    assertTrue(frag.contains("uniform int uGlassEnvIsSrgb;"))
    assertTrue(frag.contains("uniform int uCausticsIsSrgb;"))
    assertTrue(frag.contains("uniform int uFluidDetailIsSrgb;"))

    // Verify diagnostic fallback vs explicit V3_ASSET_ERROR state distinction
    val tex3d = OrbVolumeTexture3D()
    val fallbackTel = tex3d.loadAndUpload(null)
    assertEquals(OrbV3LoadState.DIAGNOSTIC_PROCEDURAL_FALLBACK, fallbackTel.loadState)
    assertFalse(fallbackTel.isLoaded)
    assertFalse(fallbackTel.hasError)
    assertTrue(OrbV3LoadState.V3_ASSET_ERROR.label == "V3_ASSET_ERROR")
  }

  @Test
  fun test5_glassCompositeDisablesDuplicateBlendAndEliminatesWhiteHeadlightDot() {
    val root = projectRoot()
    val rendererSrc = File(root, "app/src/main/java/com/example/orb/scene/OrbFluidVolumeRenderer.kt").readText(Charsets.UTF_8)
    val frag = CoreFluidShaders.CORE_GLASS_FRAG

    // P0-3: Core glass pass must disable duplicate GL_BLEND and output alpha = 1.0 over originalBg
    assertTrue("OrbFluidVolumeRenderer must disable GL_BLEND on core glass pass", rendererSrc.contains("GLES30.glDisable(GLES30.GL_BLEND)"))
    assertFalse("OrbFluidVolumeRenderer must NOT enable GL_BLEND on core glass pass", rendererSrc.contains("GLES30.glEnable(GLES30.GL_BLEND)"))
    assertTrue("CORE_GLASS_FRAG must sample originalBg for in-shader compositing", frag.contains("vec3 originalBg = texture(uRefractionTex"))
    assertTrue("CORE_GLASS_FRAG must output opaque alpha=1.0 after in-shader edge mix", frag.contains("fragColor = vec4(finalComposited * uHdrScale, 1.0);"))

    // P0-2: White circular 10:30 headlight dot (`specKeyPinpoint`) & uniform cyan `lowerScatter` haze removed
    assertFalse("Circular white headlight specKeyPinpoint must be removed", frag.contains("specKeyPinpoint"))
    assertFalse("Cloudy uniform lowerScatter haze must be removed", frag.contains("float lowerScatter"))
    assertTrue("Rim-hugging 10:45 golden crown arc must exist", frag.contains("float crownRimArc"))
  }

  @Test
  fun test6_residentC01IsOpenEndedDeKinkedAndAllResidentsStayBounded() {
    val residents = AIOrbOrbitRibbons.buildRibbonDescriptors().filter { it.role == RibbonRole.RESIDENT }
    assertEquals(5, residents.size)

    val c01 = residents.first { it.residentCode == "C01" }
    assertFalse("P0-4: C01 must be open-ended (closed == false) to avoid mechanical ring & 5:00 V-kink", c01.closed)
    assertTrue("C01 must have >= 10 sculpted control points", c01.controlPoints.size >= 10)

    // Verify smooth curvature along C01 spine (zero sharp V-kink > 72 degrees between adjacent tangents!)
    val samples = (0..60).map { i ->
      OrbGeometryBuilder.sampleCatmullRom(c01.controlPoints, i / 60f, closed = false)
    }
    var maxTurnDeg = 0.0
    for (i in 1 until samples.size - 1) {
      val t0 = (samples[i] - samples[i - 1]).normalized()
      val t1 = (samples[i + 1] - samples[i]).normalized()
      val dot = (t0.x * t1.x + t0.y * t1.y + t0.z * t1.z).coerceIn(-1f, 1f)
      val deg = Math.toDegrees(acos(dot.toDouble()))
      if (deg > maxTurnDeg) maxTurnDeg = deg
    }
    assertTrue("C01 must have smooth curve without sharp V-kink (max segment turn $maxTurnDeg deg < 20 deg)", maxTurnDeg < 20.0)

    // Verify all 5 Residents remain cleanly within visual viewport framing radius (< 1.56)
    for (r in residents) {
      for (pt in r.controlPoints) {
        val rad = sqrt(pt.x * pt.x + pt.y * pt.y + pt.z * pt.z)
        assertTrue("${r.residentCode} control point radius $rad exceeds framing bound 1.56", rad <= 1.56f)
      }
    }
  }

  @Test
  fun test7_motionControllerPreservesSimulationTimeAcrossSlowAndVariableFrames() {
    val cfg = AIOrbConfig.Default
    val ctrl60 = AIOrbMotionController()
    val ctrl10 = AIOrbMotionController()
    val ctrlVar = AIOrbMotionController()

    // 10 seconds at 60 FPS vs 10 FPS (0.10s frames > 0.05s old clamp!) vs variable FPS
    var s60 = ctrl60.step(0.0, cfg)
    repeat(600) { s60 = ctrl60.step(1.0 / 60.0, cfg) }

    var s10 = ctrl10.step(0.0, cfg)
    repeat(100) { s10 = ctrl10.step(0.10, cfg) }

    val pattern = doubleArrayOf(0.016, 0.084, 0.120, 0.030, 0.075, 0.025, 0.150) // sums to 0.500s
    var sVar = ctrlVar.step(0.0, cfg)
    repeat(20) {
      for (d in pattern) sVar = ctrlVar.step(d, cfg)
    }

    // P0-5: Zero lost simulation time even at 10 FPS (0.10s/frame) or variable slow frames!
    assertEquals(10.0, s60.simTimeSec, 1e-4)
    assertEquals(10.0, s10.simTimeSec, 1e-4)
    assertEquals(10.0, sVar.simTimeSec, 1e-4)

    assertEquals("10 FPS core angle must match 60 FPS within 0.25 deg", s60.coreRotationAngleDeg, s10.coreRotationAngleDeg, 0.25f)
    assertEquals("Variable FPS core angle must match 60 FPS within 0.25 deg", s60.coreRotationAngleDeg, sVar.coreRotationAngleDeg, 0.25f)
    assertEquals(s60.fluidPhase, s10.fluidPhase, 1e-3f)
    assertEquals(s60.ribbonTimeSec, sVar.ribbonTimeSec, 1e-3f)
  }

  @Test
  fun test8_referenceLockedFrameIsFullyDeterministicAndComparisonArtifactExists() {
    val controller = AIOrbMotionController()
    val normalCfg = AIOrbConfig.Default
    val refLockedCfg = AIOrbConfig.Default.copy(debugMode = RenderDebugMode.REFERENCE_LOCKED_FRAME)

    // Advance normal simulation first
    repeat(120) { controller.step(1.0 / 60.0, normalCfg) }

    // Switch to REFERENCE_LOCKED_FRAME: must snap deterministically to exact zero phase & identity core rotation!
    val snapA = controller.step(0.016, refLockedCfg)
    val snapB = controller.step(0.120, refLockedCfg)

    assertEquals(0.0, snapA.simTimeSec, 1e-9)
    assertEquals(0f, snapA.coreRotationAngleDeg, 1e-6f)
    assertEquals(0f, snapA.fluidPhase, 1e-6f)
    assertEquals(0f, snapA.meniscusPhase, 1e-6f)
    assertEquals(0f, snapA.causticsPhase, 1e-6f)
    assertEquals(0f, snapA.ribbonTimeSec, 1e-6f)
    assertEquals(0f, snapA.outerGlassYawDeg, 1e-6f)
    assertEquals(0f, snapA.outerGlassPitchDeg, 1e-6f)
    assertEquals(0f, snapA.outerGlassRollDeg, 1e-6f)
    assertEquals(snapA.coreRotationAngleDeg, snapB.coreRotationAngleDeg, 1e-6f)
    assertTrue(RenderDebugMode.REFERENCE_LOCKED_FRAME.locksReferenceFrame)
    assertTrue(RenderDebugMode.REFERENCE_LOCKED_FRAME.locksCameraAndOuterShell)
    assertFalse(RenderDebugMode.REFERENCE_LOCKED_FRAME.isCoreIsolated)

    // V3 scene pass manifest for REFERENCE_LOCKED_FRAME must execute the full real V3 GPU pipeline
    val passes = AIOrbScene.activeRenderPassesV3(RenderDebugMode.REFERENCE_LOCKED_FRAME)
    assertEquals(AIOrbScene.activeRenderPassesV3(RenderDebugMode.FULL_RENDER), passes)

    // Verify G3 offline comparison & reports exist
    val root = projectRoot()
    val compPng = File(root, "AI_ORB_V3_REFERENCE_COMPARISON.png")
    val repairDoc = File(root, "docs/v3/AI_ORB_V3_G0_G4_REPAIR_REPORT.md")
    val motionDoc = File(root, "docs/v3/AI_ORB_V3_MOTION_REGRESSION.md")
    assertTrue("AI_ORB_V3_REFERENCE_COMPARISON.png must exist", compPng.exists() && compPng.length() > 16384)
    assertTrue("AI_ORB_V3_G0_G4_REPAIR_REPORT.md must exist", repairDoc.exists())
    assertTrue("AI_ORB_V3_MOTION_REGRESSION.md must exist", motionDoc.exists())
    assertTrue("Repair report must honestly record BLOCKED_NEEDS_GPU_FRAME", repairDoc.readText().contains("BLOCKED_NEEDS_GPU_FRAME"))
  }

  @Test
  fun test9_nonBlockingAsyncPreloadPreventsSurfaceSyncGroupTimeout() {
    val tex3d = OrbVolumeTexture3D()
    tex3d.preloadAsync(null)
    assertEquals(OrbV3LoadState.DIAGNOSTIC_PROCEDURAL_FALLBACK, tex3d.telemetry.loadState)
    assertFalse("uploadIfReady should return false once diagnostic fallback is already recorded", tex3d.uploadIfReady(null))

    val root = projectRoot()
    val sceneSrc = File(root, "app/src/main/java/com/example/orb/scene/AIOrbScene.kt").readText(Charsets.UTF_8)
    val rendererSrc = File(root, "app/src/main/java/com/example/orb/scene/OrbFluidVolumeRenderer.kt").readText(Charsets.UTF_8)
    assertTrue("AIOrbScene must kick off background WebP preload in init", sceneSrc.contains("core.preloadAssetsAsync(assetManager)"))
    assertTrue("AIOrbScene must provide fast first-frame handshake to avoid SurfaceSyncGroup 1000ms timeout", sceneSrc.contains("hasRenderedFirstFrame"))
    assertFalse("OrbFluidVolumeRenderer.init must not synchronously block on loadAndUpload", rendererSrc.contains("volumeTextures.loadAndUpload(assetManager)"))
  }
}
