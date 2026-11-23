package com.example

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.RenderDebugMode
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.gl.RibbonOpticsShaders
import com.example.orb.scene.AIOrbMotionController
import com.example.orb.scene.AIOrbOrbitRibbons
import com.example.orb.scene.AIOrbScene
import com.example.orb.scene.AIOrbTelemetry
import com.example.orb.scene.RibbonRole
import com.example.orb.v3.MasterFidelityV32
import com.example.orb.v3.OrbGpuReferenceCapture
import com.example.orb.v3.OrbV3AssetManifest
import java.io.File
import kotlin.math.acos
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Work P0 — AI Orb V3.2 Reference-to-3D Final Fidelity Automated Verification Suite (V32-01..V32-10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AIOrbV32ReferenceFittingTest {

  private fun projectRoot(): File {
    var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
    while (!File(dir, "settings.gradle.kts").exists()) {
      dir = dir.parentFile ?: break
    }
    assertTrue("Could not locate project root", File(dir, "settings.gradle.kts").exists())
    return dir
  }

  @Test
  fun v32_01_frozenMasterPngSha256AndDimensionsMatchLock() {
    val root = projectRoot()
    val masterFile = File(root, "public/assets/aistudio/____________910d1531.png")
    assertTrue("Sole frozen master PNG must exist", masterFile.exists())
    val actualSha = MasterFidelityV32.sha256Hex(masterFile.readBytes())
    assertEquals(MasterFidelityV32.EXPECTED_MASTER_SHA256, actualSha)

    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(masterFile.absolutePath, opts)
    assertEquals(1254, opts.outWidth)
    assertEquals(1254, opts.outHeight)
  }

  @Test
  fun v32_02_coordinateLockAndFittingReportsExistAndAlignWithMaster() {
    val root = projectRoot()
    val requiredDocs = listOf(
      "docs/v3/AI_ORB_V3_2_MASTER_LOCK.md",
      "docs/v3/AI_ORB_V3_2_VISUAL_LOSS.json",
      "docs/v3/AI_ORB_V3_2_CAMERA_FIT_REPORT.md",
      "docs/v3/AI_ORB_V3_2_CAVITY_FIT_REPORT.md",
      "docs/v3/AI_ORB_V3_2_VOLUME_FIT_REPORT.md",
      "docs/v3/AI_ORB_V3_2_RESIDENT_FIT_REPORT.md",
      "docs/v3/AI_ORB_V3_2_OPTICS_FIT_REPORT.md",
      "tools/orb-v3-generator/fitting/REFERENCE_FITTING_TARGETS.json",
    )
    for (rel in requiredDocs) {
      val f = File(root, rel)
      assertTrue("Missing V3.2 fitting artifact: $rel", f.exists() && f.length() > 200)
    }

    val targets = JSONObject(
      File(root, "tools/orb-v3-generator/fitting/REFERENCE_FITTING_TARGETS.json").readText(Charsets.UTF_8)
    )
    assertEquals(
      MasterFidelityV32.EXPECTED_MASTER_SHA256,
      targets.getJSONObject("referenceMaster").getString("sha256"),
    )
  }

  @Test
  fun v32_03_masterFidelityV32LoadsFromAssetsWithExactParityToEmbeddedDefaults() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val loaded = MasterFidelityV32.loadFromAssets(context.assets)
    val fallback = MasterFidelityV32.Default

    assertTrue("MASTER_FIDELITY_V32.json must load cleanly from APK assets", loaded.loadedFromAssets)
    assertEquals(MasterFidelityV32.EXPECTED_SCHEMA, loaded.schemaVersion)
    assertEquals(MasterFidelityV32.EXPECTED_MASTER_SHA256, loaded.masterSha256)
    assertEquals("CANDIDATE_B_CRYSTAL_LENS", loaded.selectedCavityModel)
    assertEquals(32, loaded.selectedVolumeDepth)

    // Exact 1:1 parity between JSON asset and embedded defaults
    assertEquals(fallback.camera.cameraDistance, loaded.camera.cameraDistance, 1e-4f)
    assertEquals(fallback.camera.visualBoundRadius, loaded.camera.visualBoundRadius, 1e-4f)
    assertEquals(fallback.camera.targetOffsetX, loaded.camera.targetOffsetX, 1e-4f)
    assertEquals(fallback.camera.targetOffsetY, loaded.camera.targetOffsetY, 1e-4f)
    assertEquals(fallback.innerCavity.lensCenterX, loaded.innerCavity.lensCenterX, 1e-4f)
    assertEquals(fallback.innerCavity.lensCenterY, loaded.innerCavity.lensCenterY, 1e-4f)
    assertEquals(fallback.innerCavity.lensCenterZ, loaded.innerCavity.lensCenterZ, 1e-4f)
    assertEquals(fallback.innerCavity.lensRadiusX, loaded.innerCavity.lensRadiusX, 1e-4f)
    assertEquals(fallback.innerCavity.lensRadiusY, loaded.innerCavity.lensRadiusY, 1e-4f)
    assertEquals(fallback.innerCavity.lensRadiusZ, loaded.innerCavity.lensRadiusZ, 1e-4f)
    assertEquals(fallback.lighting.goldWallsGain, loaded.lighting.goldWallsGain, 1e-4f)
    assertEquals(fallback.lighting.goldSpecGain, loaded.lighting.goldSpecGain, 1e-4f)
    assertEquals(9, loaded.residentA01.controlPoints.size)
    assertEquals(9, loaded.residentA02.controlPoints.size)
    assertEquals(12, loaded.residentC01.controlPoints.size)

    // Verify visual loss json references the exact SHA-256 of the asset file
    val root = projectRoot()
    val lossJson = JSONObject(File(root, "docs/v3/AI_ORB_V3_2_VISUAL_LOSS.json").readText(Charsets.UTF_8))
    assertEquals(lossJson.getString("masterFidelityConfigSha256"), loaded.configSha256)
  }

  @Test
  fun v32_11_masterVolumeParametersMatchFallbackRuntimeAndShaderContract() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val loaded = MasterFidelityV32.loadFromAssets(context.assets)
    val fallback = MasterFidelityV32.Default
    val cfg = AIOrbConfig.Default
    val root = projectRoot()
    val json = JSONObject(File(root, "app/src/main/assets/orb_v3/manifest/MASTER_FIDELITY_V32.json").readText())

    fun assertParam(section: String, key: String, runtime: Float, fallbackValue: Float) {
      val fitted = json.getJSONObject(section).getJSONObject(key).getDouble("fittedValue").toFloat()
      assertEquals("$section.$key JSON vs loaded", fitted, runtime, 1e-4f)
      assertEquals("$section.$key JSON vs fallback", fitted, fallbackValue, 1e-4f)
    }
    assertParam("outerGlass", "iorGlass", loaded.outerGlass.iorGlass, fallback.outerGlass.iorGlass)
    assertParam("outerGlass", "glassWallThickness", loaded.outerGlass.glassWallThickness, fallback.outerGlass.glassWallThickness)
    assertParam("outerGlass", "fresnelPower", loaded.outerGlass.fresnelPower, fallback.outerGlass.fresnelPower)
    assertParam("innerCavity", "iorAir", loaded.innerCavity.iorAir, fallback.innerCavity.iorAir)
    assertParam("innerCavity", "iorWater", loaded.innerCavity.iorWater, fallback.innerCavity.iorWater)
    assertParam("innerCavity", "iorCrystal", loaded.innerCavity.iorCrystal, fallback.innerCavity.iorCrystal)
    assertParam("fluidVolume", "densityScale", loaded.fluidVolume.densityScale, fallback.fluidVolume.densityScale)
    assertParam("fluidVolume", "baseDensity", loaded.fluidVolume.baseDensity, fallback.fluidVolume.baseDensity)
    assertParam("fluidVolume", "foldIso", loaded.fluidVolume.foldIso, fallback.fluidVolume.foldIso)
    assertParam("fluidVolume", "foldThickness", loaded.fluidVolume.foldThickness, fallback.fluidVolume.foldThickness)
    assertParam("fluidVolume", "foldNormalPerturbation", loaded.fluidVolume.foldNormalPerturbation, fallback.fluidVolume.foldNormalPerturbation)
    assertParam("fluidVolume", "foldRefraction", loaded.fluidVolume.foldRefraction, fallback.fluidVolume.foldRefraction)
    assertParam("fluidVolume", "foldSpecularGain", loaded.fluidVolume.foldSpecularGain, fallback.fluidVolume.foldSpecularGain)
    assertParam("sapphireValleys", "sapphireAbsorptionGain", loaded.sapphireValleys.sapphireAbsorptionGain, fallback.sapphireValleys.sapphireAbsorptionGain)
    assertParam("sapphireValleys", "sapphireAnchorWeight", loaded.sapphireValleys.sapphireAnchorWeight, fallback.sapphireValleys.sapphireAnchorWeight)
    assertParam("sapphireValleys", "sapphireDepthDarken", loaded.sapphireValleys.sapphireDepthDarken, fallback.sapphireValleys.sapphireDepthDarken)
    assertParam("sapphireValleys", "beerLambertR", loaded.sapphireValleys.beerLambertR, fallback.sapphireValleys.beerLambertR)
    assertParam("sapphireValleys", "beerLambertG", loaded.sapphireValleys.beerLambertG, fallback.sapphireValleys.beerLambertG)
    assertParam("sapphireValleys", "beerLambertB", loaded.sapphireValleys.beerLambertB, fallback.sapphireValleys.beerLambertB)
    assertParam("emeraldFolds", "emeraldFoldGain", loaded.emeraldFolds.emeraldFoldGain, fallback.emeraldFolds.emeraldFoldGain)
    assertParam("emeraldFolds", "emeraldAnchorWeight", loaded.emeraldFolds.emeraldAnchorWeight, fallback.emeraldFolds.emeraldAnchorWeight)
    assertParam("emeraldFolds", "emeraldGreenBoost", loaded.emeraldFolds.emeraldGreenBoost, fallback.emeraldFolds.emeraldGreenBoost)
    assertParam("emeraldFolds", "emeraldThreshold", loaded.emeraldFolds.emeraldThreshold, fallback.emeraldFolds.emeraldThreshold)
    assertParam("aquaRegion", "aquaDensityGain", loaded.aquaRegion.aquaDensityGain, fallback.aquaRegion.aquaDensityGain)
    assertParam("aquaRegion", "aquaAnchorWeight", loaded.aquaRegion.aquaAnchorWeight, fallback.aquaRegion.aquaAnchorWeight)
    assertParam("aquaRegion", "aquaBrightnessGain", loaded.aquaRegion.aquaBrightnessGain, fallback.aquaRegion.aquaBrightnessGain)
    assertParam("aquaRegion", "aquaUpperGateY", loaded.aquaRegion.aquaUpperGateY, fallback.aquaRegion.aquaUpperGateY)
    assertParam("caustics", "causticDecoupleWeight", loaded.caustics.causticDecoupleWeight, fallback.caustics.causticDecoupleWeight)
    assertEquals(1.0f, cfg.densityScaleMultiplier, 0f)

    val effective = (loaded.fluidVolume.densityScale * cfg.densityScaleMultiplier)
      .coerceIn(loaded.fluidVolume.densityScaleMin, loaded.fluidVolume.densityScaleMax)
    assertEquals("Default runtime density equals Master fitted density", loaded.fluidVolume.densityScale, effective, 0f)

    val runtimeSummary = JSONObject(loaded.toRuntimeSummaryJson())
    val runtimeGlass = runtimeSummary.getJSONObject("glassOptics")
    assertEquals(loaded.outerGlass.iorGlass.toDouble(), runtimeGlass.getDouble("iorGlass"), 1e-4)
    assertEquals(loaded.outerGlass.glassWallThickness.toDouble(), runtimeGlass.getDouble("glassWallThickness"), 1e-4)
    val runtimeFold = runtimeSummary.getJSONObject("foldOptics")
    assertEquals(loaded.fluidVolume.foldNormalPerturbation.toDouble(), runtimeFold.getDouble("foldNormalPerturbation"), 1e-4)
    assertEquals(loaded.sapphireValleys.beerLambertR.toDouble(), runtimeFold.getJSONArray("beerLambert").getDouble(0), 1e-4)
    val runtimeVolume = runtimeSummary.getJSONObject("volumeParameters")
    assertEquals(effective.toDouble(), runtimeVolume.getDouble("densityScale"), 1e-4)
    assertEquals(loaded.fluidVolume.foldThickness.toDouble(), runtimeVolume.getDouble("foldThickness"), 1e-4)
    assertEquals(loaded.sapphireValleys.sapphireAbsorptionGain.toDouble(), runtimeVolume.getDouble("sapphireAbsorptionGain"), 1e-4)
    assertEquals(loaded.emeraldFolds.emeraldAnchorWeight.toDouble(), runtimeVolume.getDouble("emeraldAnchorWeight"), 1e-4)
    assertEquals(loaded.aquaRegion.aquaDensityGain.toDouble(), runtimeVolume.getDouble("aquaDensityGain"), 1e-4)
    assertEquals(loaded.caustics.causticDecoupleWeight.toDouble(), runtimeVolume.getDouble("causticDecoupleWeight"), 1e-4)

    val shader = CoreFluidShaders.CORE_GLASS_FRAG
    val renderer = File(root, "app/src/main/java/com/example/orb/scene/OrbFluidVolumeRenderer.kt").readText()
    assertTrue(renderer.contains("vol.densityScale * config.densityScaleMultiplier"))
    assertTrue(renderer.contains("prog.setVec2(\"uDensityRange\", vol.densityScaleMin, vol.densityScaleMax)"))
    assertTrue(shader.contains("float densityScale = clamp(uDensityScale, uDensityRange.x, uDensityRange.y)"))
    assertTrue(shader.contains("float sigma = localDensity * densityScale * segDt"))
    assertTrue("Refraction must use Master air/glass IOR", shader.contains("refract(-V, safeN, etaAirGlass)"))
    assertTrue("The glass/water wall must use Master glass/water IOR", shader.contains("uGlassOptics.x / max(uInterfaceIor.y, 1.0001)"))
    assertTrue("Fold normals must use the deformed texture coordinate", shader.contains("estimateFluidFoldNormal(deformedSamplePos, radialCore, densityGradientMagnitude)"))
    assertTrue("Fold refraction must resample the same deformed volume field", shader.contains("deformedSamplePos - foldN * (0.022 * uFoldOptics.z)"))
    assertTrue("Caustics must integrate multiple actual ray depths", shader.contains("causticA * 0.24 + causticB * 0.46 + causticC * 0.30"))
    assertTrue("Glass wall thickness must be sent from the Master", renderer.contains("glass.glassWallThickness"))
    assertTrue("Glass Fresnel controls must be sent from the Master", renderer.contains("glass.fresnelPower") && renderer.contains("glass.fresnelGain"))
    assertTrue("Glass structure Uniform must be present", renderer.contains("\"uGlassStructure\""))
    assertTrue("All IOR values must be sent from the Master", renderer.contains("prog.setVec3(\"uInterfaceIor\", cav.iorAir, cav.iorWater, cav.iorCrystal)"))

    val missing = JSONObject(json.toString())
    val density = missing.getJSONObject("fluidVolume").getJSONObject("densityScale")
    density.remove("fittedValue")
    density.remove("default")
    val fallbackLoad = MasterFidelityV32.parseJson(missing.toString())
    assertEquals("Missing Master value must use valid Kotlin fallback", fallback.fluidVolume.densityScale, fallbackLoad.fluidVolume.densityScale, 0f)
  }

  @Test
  fun v32_04_stageACameraAndStageEOpticsHardGatesPassInVisualLoss() {
    val root = projectRoot()
    val lossJson = JSONObject(File(root, "docs/v3/AI_ORB_V3_2_VISUAL_LOSS.json").readText(Charsets.UTF_8))
    val gates = lossJson.getJSONObject("hardGates")

    assertTrue(gates.getJSONObject("E01_diameterRelErr_le_2pct").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E01_portraitDiamRelErr_le_2pct").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E01_centerErrW_le_1pct").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E01_silhouetteIoU_ge_095").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E03_cavityAreaRelErr_le_15pct").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E03_cavityCentroid_le_004D").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E04_meniscusMeanErr_le_0025R").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E05_emeraldAreaRelErr_le_15pct").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E05_emeraldCentroid_le_004D").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E06_sapphireAreaRelErr_le_15pct").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E06_sapphireCentroid_le_004D").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E07_aquaAreaRelErr_le_15pct").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E07_aquaCentroid_le_004D").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E10_goldCombinedChamfer_le_0025R").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E10_goldNonRegularOval").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E11_c01SmoothTurn_lt_20deg").getBoolean("pass"))
    assertTrue(gates.getJSONObject("E02_upperDomeHaze_le_1pct").getBoolean("pass"))

    assertTrue("All 17 offline hard gates must pass", lossJson.getBoolean("allOfflineHardGatesPass"))
    assertEquals(0, lossJson.getJSONArray("failedOfflineGates").length())

    // Honest GPU pending status must never be fabricated
    assertEquals("PENDING_REAL_GPU_CAPTURE", lossJson.getString("realGpuStatus"))
    assertEquals("OFFLINE_CPU_PROXY_ANALYTICAL_FITTING", lossJson.getString("evaluationDomain"))
  }

  @Test
  fun v32_05_candidateBCrystalLensActiveFoldNormalsAndPrecisionHighpIntInShaders() {
    val coreVert = CoreFluidShaders.CORE_GLASS_VERT
    val coreFrag = CoreFluidShaders.CORE_GLASS_FRAG
    val ribVert = RibbonOpticsShaders.RIBBON_VERT
    val ribFrag = RibbonOpticsShaders.RIBBON_FRAG

    // Precision matching prevents Adreno/Mali/SwiftShader link errors on shared int uniforms
    assertTrue("CORE_GLASS_VERT must declare precision highp int;", coreVert.contains("precision highp int;"))
    assertTrue("CORE_GLASS_FRAG must declare precision highp int;", coreFrag.contains("precision highp int;"))
    assertTrue("RIBBON_VERT must declare precision highp int;", ribVert.contains("precision highp int;"))
    assertTrue("RIBBON_FRAG must declare precision highp int;", ribFrag.contains("precision highp int;"))

    // Candidate B crystal lens SDF + intersection + CAVITY_ONLY diagnostic mode
    assertTrue(coreFrag.contains("float crystalLensPhi(vec3 p)"))
    assertTrue(coreFrag.contains("vec3 crystalLensNormal(vec3 p)"))
    assertTrue(coreFrag.contains("bool intersectCrystalLens("))
    assertTrue(coreFrag.contains("if (uDebugMode == 9)"))
    assertEquals(9, RenderDebugMode.CAVITY_ONLY.shaderModeId)
    assertTrue(RenderDebugMode.CAVITY_ONLY.isCoreIsolated)

    // Active-surface analytical 3D fluid fold normal lighting & V3.2 fitted uniforms
    assertTrue(coreFrag.contains("vec3 estimateFluidFoldNormal("))
    assertTrue(coreFrag.contains("if (localDensity > 0.035 && uFoldOptics.x > 0.01)"))
    assertTrue(coreFrag.contains("(axp - axm) / (4.0 * eps.x)"))
    assertTrue(coreFrag.contains("-(ayp - aym) / (4.0 * eps.y)"))
    assertTrue(coreFrag.contains("(azp - azm) / (4.0 * eps.z)"))
    assertTrue(coreFrag.contains("uniform vec3 uLensCenter;"))
    assertTrue(coreFrag.contains("uniform vec3 uLensRadii;"))
    assertTrue(coreFrag.contains("uniform vec4 uLensParams;"))
    assertTrue(coreFrag.contains("uniform vec4 uFoldOptics;"))
    assertTrue(coreFrag.contains("uniform vec3 uBeerLambert;"))

    // RibbonOptics calibrated gold material uniforms
    assertTrue(ribFrag.contains("uniform vec4 uGoldOptics;"))
    assertTrue(ribFrag.contains("uniform float uGoldSpecGain;"))
  }

  @Test
  fun v32_06_fittedResidentsMaintainFrozenPeriodsSmoothCurvatureAndBoundedRadii() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val fidelity = MasterFidelityV32.loadFromAssets(context.assets)
    val fitted = AIOrbOrbitRibbons.buildRibbonDescriptors(fidelity).filter { it.role == RibbonRole.RESIDENT }
    assertEquals(5, fitted.size)

    val byCode = fitted.associateBy { it.residentCode }
    assertEquals(6100, byCode["G01"]!!.periodMs)
    assertEquals(5300, byCode["G02"]!!.periodMs)
    assertEquals(3700, byCode["A01"]!!.periodMs)
    assertEquals(2900, byCode["A02"]!!.periodMs)
    assertEquals(4400, byCode["C01"]!!.periodMs)

    for (r in fitted) {
      assertFalse("${r.residentCode} must stay open (non-closed)", r.closed)
      for (pt in r.controlPoints) {
        val rad = sqrt(pt.x * pt.x + pt.y * pt.y + pt.z * pt.z)
        assertTrue("${r.residentCode} point radius $rad exceeds 1.56", rad <= 1.56f)
      }
    }

    // Verify C01 smoothness (< 20 deg max segment turn)
    val c01 = byCode["C01"]!!
    val samples = (0..60).map { i -> OrbGeometryBuilder.sampleCatmullRom(c01.controlPoints, i / 60f, closed = false) }
    var maxTurnDeg = 0.0
    for (i in 1 until samples.size - 1) {
      val t0 = (samples[i] - samples[i - 1]).normalized()
      val t1 = (samples[i + 1] - samples[i]).normalized()
      val dot = (t0.x * t1.x + t0.y * t1.y + t0.z * t1.z).coerceIn(-1f, 1f).toDouble()
      val deg = Math.toDegrees(acos(dot))
      if (deg > maxTurnDeg) maxTurnDeg = deg
    }
    assertTrue("Fitted C01 maxTurnDeg=$maxTurnDeg must be < 20 deg", maxTurnDeg < 20.0)
  }

  @Test
  fun v32_07_webpManifestAnd32SlicesMatchSha256Integrity() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manifest = OrbV3AssetManifest.loadFromAssets(context.assets)
    assertEquals(32, manifest.volume.depth)
    assertEquals(256, manifest.volume.width)
    assertEquals(256, manifest.volume.height)
    assertEquals(35, manifest.totalWebpFiles)
    assertEquals(MasterFidelityV32.EXPECTED_MASTER_SHA256, manifest.masterSha256)

    // Spot-check first, middle, and last WebP slice SHA-256 digests directly from APK assets
    for (idx in listOf(0, 16, 31)) {
      val entry = manifest.volume.slices[idx]
      val bytes = context.assets.open("${OrbV3AssetManifest.ASSET_ROOT}/${entry.path}").use { it.readBytes() }
      assertEquals("Slice $idx SHA-256 mismatch", entry.sha256, MasterFidelityV32.sha256Hex(bytes))
    }
  }

  @Test
  fun v32_08_gpuReferenceCaptureVerticalFlipAndEvidenceSchemaContract() {
    // Verify exact row-wise vertical Y-flip on a 2x3 RGBA buffer
    val width = 2
    val height = 3
    val row0 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    val row1 = byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18)
    val row2 = byteArrayOf(21, 22, 23, 24, 25, 26, 27, 28)
    val glBottomUp = row0 + row1 + row2
    val pngTopDown = OrbGpuReferenceCapture.flipRgbaVertically(glBottomUp, width, height)
    assertArrayEquals(row2 + row1 + row0, pngTopDown)

    // Verify six core layers, six optical diagnostics, and 3 JSON evidence files.
    val pngNames = OrbGpuReferenceCapture.CAPTURE_STEPS.map { it.fileName }
    assertEquals(
      listOf(
        "REFERENCE_LOCKED_FRAME_GPU.png",
        "CAVITY_ONLY_GPU.png",
        "FLUID_VOLUME_ONLY_GPU.png",
        "RIBBONS_ONLY_GPU.png",
        "GLASS_ONLY_GPU.png",
        "FULL_RENDER_GPU.png",
        "CAUSTICS_ONLY_GPU.png",
        "BLOOM_OFF_GPU.png",
        "FOLD_NORMAL_GPU.png",
        "VOLUME_DEPTH_GPU.png",
        "PRE_TONEMAP_GPU.png",
        "POST_TONEMAP_GPU.png",
      ),
      pngNames,
    )
    assertEquals(
      listOf(
        "RUNTIME_RENDER_CONFIG.json",
        "GPU_TELEMETRY.json",
        "BUILD_FINGERPRINT.json",
      ),
      OrbGpuReferenceCapture.EXPECTED_JSON_FILES,
    )

    // Verify JSON builders emit strictly parseable JSON with expected fields
    val fidelity = MasterFidelityV32.Default
    val tel = AIOrbTelemetry(glVendor = "TestVendor", glRenderer = "TestGPU")
    val runtimeCfg = JSONObject(
      OrbGpuReferenceCapture.buildRuntimeRenderConfigJson(AIOrbConfig.Default, fidelity, 1080, 2400)
    )
    assertEquals(1080, runtimeCfg.getInt("surfaceWidth"))
    assertEquals(2400, runtimeCfg.getInt("surfaceHeight"))
    assertTrue(runtimeCfg.has("masterFidelityV32"))

    val telJson = JSONObject(OrbGpuReferenceCapture.buildGpuTelemetryJson(tel, 1080, 2400))
    assertEquals("TestVendor", telJson.getString("glVendor"))
    assertEquals("TestGPU", telJson.getString("glRenderer"))
    assertTrue(telJson.has("buildSha"))
    assertTrue(telJson.has("shaderSourceSha256"))
    assertTrue(telJson.has("uVolumeLoaded"))
    assertTrue(telJson.has("gpuTextureObjectsValid"))
    assertTrue(telJson.has("volumeInternalFormat"))
    assertTrue(telJson.has("foldActiveRatio"))
    assertTrue(telJson.has("glErrorPass"))

    val fpJson = JSONObject(OrbGpuReferenceCapture.buildFingerprintJson(fidelity, tel))
    assertEquals(MasterFidelityV32.EXPECTED_MASTER_SHA256, fpJson.getString("referenceMasterSha256"))
    assertEquals(OrbGpuReferenceCapture.CAPTURE_STEPS.size, fpJson.getJSONArray("capturedPngFiles").length())
    val runtimeJson = JSONObject(OrbGpuReferenceCapture.buildRuntimeRenderConfigJson(
      AIOrbConfig.Default, fidelity, 1080, 2400,
    ))
    assertEquals(
      "FOLD_NORMAL",
      runtimeJson.getJSONObject("captureStepModes").getString("FOLD_NORMAL_GPU.png"),
    )
  }

  @Test
  fun v32_09_sceneInitializesWithMasterFidelityAndPreservesCavityOnlyPassIsolation() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val scene = AIOrbScene(assetManager = context.assets)
    assertTrue("AIOrbScene must load MASTER_FIDELITY_V32.json from assets", scene.masterFidelity.loadedFromAssets)

    val cavityPasses = AIOrbScene.activeRenderPassesV3(RenderDebugMode.CAVITY_ONLY)
    assertTrue(cavityPasses.contains("PASS_6_V3_WEBP_3D_VOLUME_RAYMARCH_AND_CRYSTAL_GLASS_SHELL"))
    assertFalse("CAVITY_ONLY must isolate core from front ribbons", cavityPasses.contains("PASS_7_FRONT_RIBBONS_DEPTH_TESTED"))
  }

  @Test
  fun v32_10_dynamic360ContinuityAndReferenceLockedDeterminism() {
    val ctrl = AIOrbMotionController()
    val cfg = AIOrbConfig.Default
    var prevAngle = 0f
    var maxJump = 0f
    // Full 360+ degree rotation at 60 FPS (15 seconds * 26 deg/s = 390 deg)
    var snap = ctrl.step(0.0, cfg)
    repeat(900) {
      snap = ctrl.step(1.0 / 60.0, cfg)
      val delta = snap.coreRotationAngleDeg - prevAngle
      if (delta > maxJump) maxJump = delta
      prevAngle = snap.coreRotationAngleDeg
    }
    assertTrue("15s spin (${snap.coreRotationAngleDeg} deg) must exceed 360 deg", snap.coreRotationAngleDeg > 360f)
    assertTrue("Per-frame rotation step ($maxJump deg) must stay smooth (< 1.0 deg)", maxJump < 1.0f)

    // REFERENCE_LOCKED_FRAME must snap to exact zero deterministically
    val locked = ctrl.step(0.016, cfg.copy(debugMode = RenderDebugMode.REFERENCE_LOCKED_FRAME))
    assertEquals(0f, locked.coreRotationAngleDeg, 1e-6f)
    assertEquals(0.0, locked.simTimeSec, 1e-9)
  }
}
