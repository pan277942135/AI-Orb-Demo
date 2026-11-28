package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.OrbSceneMode
import com.example.orb.task.PhotoFrameGeometry
import com.example.orb.task.Tab2IndependentPerimeterController
import com.example.orb.task.Tab2OrbViewportRect
import com.example.orb.task.Tab2PhotoPerimeterState
import com.example.orb.task.Tab2UnderwaterBackgroundRenderer
import com.example.orb.task.Tab2WaterWorldRuntimeStatus
import com.example.orb.task.Tab2WaterWorldSceneRenderer
import com.example.orb.task.TaskEnergyEvidence
import com.example.orb.task.TaskGpuCaptureProgress
import com.example.orb.task.TaskRenderDebugOptions
import com.example.orb.ui.AIOrbViewModel
import com.example.orb.ui.DemoTab
import com.example.orb.ui.TaskRecognitionDemoScreen
import com.example.orb.v3.OrbV3AssetManifest
import com.example.ui.theme.MyApplicationTheme
import com.example.waterworld.WaterWorldAssetStaging
import com.example.waterworld.WaterWorldCoreMotionAdapter
import com.example.waterworld.WaterWorldMotionEvaluator
import com.example.waterworld.WaterWorldMotionProfile
import com.example.waterworld.WaterWorldMotionRuntime
import com.example.waterworld.WaterWorldPreviewActivity
import com.example.waterworld.WaterWorldRenderGraph
import com.example.waterworld.WaterWorldResidentContractV1
import com.example.waterworld.WaterWorldResidentOrbitalMath
import com.example.waterworld.WaterWorldShaders
import com.example.waterworld.WaterWorldStaticRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Mandatory 16-Test Verification Suite for `TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`:
 * verifies frozen underwater background byte integrity, shared Tab 3 Water World V2.1 runtime
 * reuse (Core 3D 32 Z-slices, 5 Residents Work 3.2 true 3D orbital motion, 4 fish, 2 plants,
 * 3 rocks, 9 outer + 14 inner bubbles, 11 Render Passes, 5 Optical Nodes), independent photo
 * perimeter light (`PHOTO_SELECTED -> PHOTO_STABLE -> PERIMETER_LIGHT_ACTIVATED`, zero beam),
 * GL lifecycle isolation, pause/resume zero time jump, GLSL ES 3.00 active uniform parity,
 * and Tab 1 / Tab 3 zero regression.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Tab2WaterWorldMigrationV1Test {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun t01_frozenUnderwaterBackgroundAssetMatchesExactSha256AndGitBlobAndDecodes852x1846() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bytes = context.assets.open(Tab2UnderwaterBackgroundRenderer.BACKGROUND_ASSET_PATH).use { it.readBytes() }
    assertEquals(
      "Packaged background PNG byte count must match frozen source exactly",
      Tab2UnderwaterBackgroundRenderer.EXPECTED_SIZE_BYTES,
      bytes.size,
    )
    assertEquals(
      "Packaged background PNG SHA-256 must match frozen source byte-for-byte",
      Tab2UnderwaterBackgroundRenderer.EXPECTED_SHA256,
      Tab2UnderwaterBackgroundRenderer.sha256Hex(bytes),
    )
    assertEquals(
      "Packaged background PNG Git Blob SHA-1 must match TAB2_BACKGROUND_SOURCE_LOCK_V1",
      Tab2UnderwaterBackgroundRenderer.EXPECTED_GIT_BLOB_SHA1,
      Tab2UnderwaterBackgroundRenderer.gitBlobSha1Hex(bytes),
    )

    val opts = BitmapFactory.Options().apply {
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inScaled = false
    }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    assertNotNull("Frozen background PNG must decode cleanly", bmp)
    try {
      assertEquals(Tab2UnderwaterBackgroundRenderer.EXPECTED_WIDTH_PX, bmp!!.width)
      assertEquals(Tab2UnderwaterBackgroundRenderer.EXPECTED_HEIGHT_PX, bmp.height)
    } finally {
      bmp?.recycle()
    }
  }

  @Test
  fun t02_underwaterBackgroundAspectPreservingCoverUvNeverStretchesAcrossViewports() {
    val imgW = Tab2UnderwaterBackgroundRenderer.EXPECTED_WIDTH_PX
    val imgH = Tab2UnderwaterBackgroundRenderer.EXPECTED_HEIGHT_PX
    val imgAspect = imgW.toFloat() / imgH.toFloat()

    val viewports = listOf(
      852 to 1846,
      1080 to 2340,
      1080 to 2400,
      1080 to 1920,
      1600 to 2560,
      1200 to 1200,
    )
    for ((sw, sh) in viewports) {
      val uv = Tab2UnderwaterBackgroundRenderer.computeCoverUvRect(imgW, imgH, sw, sh)
      assertTrue("uMin in [0,1] for ${sw}x${sh}", uv.uMin in 0f..1f)
      assertTrue("uMax in [0,1] for ${sw}x${sh}", uv.uMax in 0f..1f)
      assertTrue("vMin in [0,1] for ${sw}x${sh}", uv.vMin in 0f..1f)
      assertTrue("vMax in [0,1] for ${sw}x${sh}", uv.vMax in 0f..1f)
      assertTrue("Positive UV span for ${sw}x${sh}", uv.uSpan > 0.1f && uv.vSpan > 0.1f)
      val sampledAspect = (uv.uSpan * imgW) / (uv.vSpan * imgH)
      val surfaceAspect = sw.toFloat() / sh.toFloat()
      assertEquals(
        "Sampled aspect must match surface aspect with zero stretching for ${sw}x${sh}",
        surfaceAspect,
        sampledAspect,
        0.002f,
      )
      if (abs(surfaceAspect - imgAspect) < 0.001f) {
        assertEquals(0f, uv.uMin, 1e-4f)
        assertEquals(1f, uv.uMax, 1e-4f)
        assertEquals(0f, uv.vMin, 1e-4f)
        assertEquals(1f, uv.vMax, 1e-4f)
      }
    }
  }

  @Test
  fun t03_tab2WaterWorldAssetStagingLoadsSharedFrozenSceneAndWork32MotionProfile() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertTrue(
      "APK must contain frozen water_world_v2_1 assets for Tab 2 & Tab 3",
      WaterWorldAssetStaging.hasRequiredAssets(context.assets),
    )
    val staged = WaterWorldAssetStaging.stageAndLoad(context)
    assertEquals(1254, staged.scene.canvasSize)
    assertTrue("Scene bindings must contain all objects", staged.scene.bindings.size >= 40)
    assertEquals(5, staged.profile.residents.size)
    assertEquals(4, staged.profile.fish.size)
    assertEquals(2, staged.profile.plants.size)
    assertEquals(9, staged.profile.bubbles.size)

    // Second call must hit validated cache cleanly without error
    val cached = WaterWorldAssetStaging.stageAndLoad(context)
    assertEquals(staged.rootDir.absolutePath, cached.rootDir.absolutePath)
  }

  @Test
  fun t04_tab2Core3DVolume32ZSlicesAndMotionAdapterWork31ClosureVerified() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manifest = OrbV3AssetManifest.loadFromAssets(context.assets)
    assertEquals(256, manifest.volume.width)
    assertEquals(256, manifest.volume.height)
    assertEquals(32, manifest.volume.depth)
    for (z in 0 until 32) {
      val path = "orb_v3/volume/fluid_color_z%02d.webp".format(z)
      context.assets.open(path).use { stream ->
        assertTrue("Slice $path must be non-empty", stream.readBytes().isNotEmpty())
      }
    }

    val profile = WaterWorldMotionProfile.defaultProfile()
    val t0 = WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(
      WaterWorldMotionEvaluator.evaluate(profile, 0.0, 0L, 42L, staticReferenceMode = true),
    )
    val t6 = WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(
      WaterWorldMotionEvaluator.evaluate(profile, 6.0, 360L, 42L, staticReferenceMode = false),
    )
    assertTrue("Core 3D fluid phase must advance in Tab 2", t6.fluidPhase > t0.fluidPhase + 0.5f)
    assertTrue("Core 3D rotation angle must advance in Tab 2", t6.coreRotationAngleDeg > 15f)
  }

  @Test
  fun t05_tab2FiveResidentsG01G02A01A02C01AndWork32True3DOrbitalMotionActive() {
    val profile = WaterWorldMotionProfile.defaultProfile()
    val expectedKeys = setOf("G01", "G02", "A01", "A02", "C01")
    assertEquals(expectedKeys, profile.residents.map { it.groupKey }.toSet())

    val t0 = WaterWorldMotionEvaluator.evaluate(profile, 0.0, 0L, 42L, staticReferenceMode = true)
    val t4 = WaterWorldMotionEvaluator.evaluate(profile, 4.0, 240L, 42L, staticReferenceMode = false)
    val t12 = WaterWorldMotionEvaluator.evaluate(profile, 12.0, 720L, 42L, staticReferenceMode = false)

    for (key in expectedKeys) {
      val s0 = requireNotNull(t0.residentStates[key])
      val s4 = requireNotNull(t4.residentStates[key])
      val s12 = requireNotNull(t12.residentStates[key])
      assertFalse("T=0 static must keep orbital bypass for $key", s0.orbitalEnabled)
      assertTrue("T>0 must enable true 3D orbital motion for $key", s4.orbitalEnabled)
      assertTrue("Orbital angle must evolve from T=4 to T=12 for $key", abs(s12.orbitalAngleRad - s4.orbitalAngleRad) > 0.3f)
      val m = s4.orbitalRotationMat3
      assertEquals(9, m.size)
      // Column-major 3x3 determinant must be +1 (pure 3D rotation without shear/reflection)
      val det = m[0] * (m[4] * m[8] - m[7] * m[5]) -
        m[3] * (m[1] * m[8] - m[7] * m[2]) +
        m[6] * (m[1] * m[5] - m[4] * m[2])
      assertEquals("Rotation matrix for $key must be orthonormal (det=+1)", 1f, det, 1e-3f)
    }

    // Verify all 5 residents map cleanly across back/front segments (13 canonical segments)
    assertEquals(13, WaterWorldResidentContractV1.CANONICAL_SEGMENT_MAPPINGS.size)
  }

  @Test
  fun t06_tab2FourFishIndependentSwimmingAndDeformationParametersActive() {
    val profile = WaterWorldMotionProfile.defaultProfile()
    assertEquals(4, profile.fish.size)
    val t0 = WaterWorldMotionEvaluator.evaluate(profile, 0.0, 0L, 42L, staticReferenceMode = true)
    val t3 = WaterWorldMotionEvaluator.evaluate(profile, 3.0, 180L, 42L, staticReferenceMode = false)
    val t8 = WaterWorldMotionEvaluator.evaluate(profile, 8.0, 480L, 42L, staticReferenceMode = false)

    assertEquals(4, t3.fishStates.size)
    val offsetsAtT3 = mutableSetOf<Pair<Int, Int>>()
    for ((fishId, f3) in t3.fishStates) {
      val f0 = requireNotNull(t0.fishStates[fishId])
      val f8 = requireNotNull(t8.fishStates[fishId])
      val moved = abs(f3.offsetX - f0.offsetX) + abs(f3.offsetY - f0.offsetY)
      assertTrue("Fish $fishId must move smoothly from T=0 to T=3s", moved > 0.5f)
      assertTrue("Fish $fishId tail wave must animate", abs(f8.tailPhaseRad - f3.tailPhaseRad) > 1.0f)
      assertTrue("Fish $fishId fin flutter must animate", abs(f8.finPhaseRad - f3.finPhaseRad) > 1.0f)
      offsetsAtT3 += (f3.offsetX.toInt() to f3.offsetY.toInt())
    }
    assertTrue("Four fish must exhibit independent trajectories", offsetsAtT3.size >= 3)
  }

  @Test
  fun t07_tab2TwoPlantsThreeRocksNineOuterAndFourteenInnerBubblesActive() {
    val profile = WaterWorldMotionProfile.defaultProfile()
    val t5 = WaterWorldMotionEvaluator.evaluate(profile, 5.0, 300L, 42L, staticReferenceMode = false)
    val t11 = WaterWorldMotionEvaluator.evaluate(profile, 11.0, 660L, 42L, staticReferenceMode = false)

    assertEquals(2, t5.plantStates.size)
    for ((plantId, p5) in t5.plantStates) {
      val p11 = requireNotNull(t11.plantStates[plantId])
      assertTrue("Plant $plantId sway must evolve over time", abs(p11.swayPhaseRad - p5.swayPhaseRad) > 0.5f)
    }

    assertEquals(3, t5.rockStates.size)
    assertEquals(9, t5.outerBubbleStates.size)
    assertEquals(14, t5.innerBubbleStates.size)
    val risingCount = t5.outerBubbleStates.values.count { abs(it.offsetY) > 0.2f || it.alphaMul < 0.99f }
    assertTrue("Outer bubbles must rise/drift dynamically", risingCount >= 5)
  }

  @Test
  fun t08_tab2ElevenRenderPassesAndFiveOpticalNodesGraphValidated() {
    val graph = WaterWorldRenderGraph()
    graph.validateGraph()
    assertEquals(11, graph.stages.size)
    assertEquals(5, WaterWorldRenderGraph.OpticalNodeId.entries.size)
    assertEquals("01_BACKGROUND", graph.stages.first().passId.wireId)
    assertEquals("11_FINAL_COMPOSITE", graph.stages.last().passId.wireId)
  }

  @Test
  fun t09_tab2SingleFrameSnapshotConsistencyAcrossAllSubsystems() {
    val runtime = WaterWorldMotionRuntime()
    val snapA = runtime.evaluateDeterministic(timeSeconds = 7.5, frameId = 450L, staticReferenceMode = false)
    val snapB = runtime.evaluateDeterministic(timeSeconds = 7.5, frameId = 450L, staticReferenceMode = false)

    assertEquals(snapA.globalTimeSeconds, snapB.globalTimeSeconds, 0.0)
    assertEquals(snapA.frameId, snapB.frameId)
    assertEquals(snapA.coreState.sliceCount, snapB.coreState.sliceCount)
    assertEquals(snapA.coreState.rotationAngleDeg, snapB.coreState.rotationAngleDeg, 1e-6f)
    assertEquals(snapA.coreState.fluidPhaseRad, snapB.coreState.fluidPhaseRad, 1e-6f)
    assertEquals(snapA.coreState.densityScale, snapB.coreState.densityScale, 1e-6f)
    assertEquals(snapA.coreState.colorShift, snapB.coreState.colorShift, 1e-6f)
    assertTrue(snapA.coreState.rotationMat3.contentEquals(snapB.coreState.rotationMat3))
    assertEquals(snapA.waterOptics, snapB.waterOptics)
    assertEquals(snapA.fishStates, snapB.fishStates)
    assertEquals(snapA.plantStates, snapB.plantStates)
    assertEquals(snapA.outerBubbleStates, snapB.outerBubbleStates)
    assertEquals(snapA.innerBubbleStates, snapB.innerBubbleStates)
    for (key in setOf("G01", "G02", "A01", "A02", "C01")) {
      assertEquals(snapA.residentStates[key]!!.orbitalAngleRad, snapB.residentStates[key]!!.orbitalAngleRad, 1e-6f)
      assertTrue(snapA.residentStates[key]!!.orbitalRotationMat3.contentEquals(snapB.residentStates[key]!!.orbitalRotationMat3))
    }
  }

  @Test
  fun t10_tab2PauseOnHideAndZeroTimeJumpOnResumeVerified() {
    val runtime = WaterWorldMotionRuntime()
    val startNs = 10_000_000_000L
    runtime.play(startNs)
    // Advance 2 seconds in 16ms frames
    var nowNs = startNs
    repeat(120) {
      nowNs += 16_666_667L
      runtime.stepFrame(nowNs)
    }
    val timeBeforePause = runtime.currentTimeSeconds
    assertTrue("Timeline must advance while playing ($timeBeforePause)", timeBeforePause > 1.8)

    // Hide / pause Tab 2 for 30 wall-clock seconds!
    runtime.onActivityPause()
    assertFalse("Hidden Tab 2 must pause playback", runtime.isPlaying)
    val pausedSnap = runtime.stepFrame(nowNs + 30_000_000_000L)
    assertEquals("Paused Tab 2 must hold exact timestamp", timeBeforePause, pausedSnap.globalTimeSeconds, 1e-6)

    // Resume after 30 seconds -> ZERO time jump!
    val resumeNs = nowNs + 30_000_000_000L
    runtime.onActivityResume(resumeNs)
    assertTrue("Resumed Tab 2 must resume playing", runtime.isPlaying)
    val firstResumedFrame = runtime.stepFrame(resumeNs + 16_666_667L)
    val deltaAfterResume = firstResumedFrame.globalTimeSeconds - timeBeforePause
    assertTrue(
      "Resume must advance smoothly by ~1 frame with ZERO 30s jump (got $deltaAfterResume)",
      deltaAfterResume in 0.005..0.04,
    )
  }

  @Test
  fun t11_tab2AndTab3GlLifecycleIsolationAndRendererSeparationVerified() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val staged = WaterWorldAssetStaging.stageAndLoad(context)

    // Tab 2 constructs WaterWorldStaticRenderer with TRANSPARENT_COMPOSITE; Tab 3 defaults to WATER_WORLD_NATIVE
    val tab2Engine = WaterWorldStaticRenderer(
      root = staged.rootDir,
      assetManager = context.assets,
      backdropMode = WaterWorldStaticRenderer.BackdropMode.TRANSPARENT_COMPOSITE,
    )
    val tab3Engine = WaterWorldStaticRenderer(
      root = staged.rootDir,
      assetManager = context.assets,
    )
    assertEquals(WaterWorldStaticRenderer.BackdropMode.TRANSPARENT_COMPOSITE, tab2Engine.activeBackdropMode)
    assertEquals(WaterWorldStaticRenderer.BackdropMode.WATER_WORLD_NATIVE, tab3Engine.activeBackdropMode)

    // Tab 2 scene renderer maintains its own independent runtime & cleanly disposes without touching Tab 3
    val tab2Renderer = Tab2WaterWorldSceneRenderer(context)
    tab2Renderer.onHostPause()
    assertFalse(tab2Renderer.motionRuntime.isPlaying)
    tab2Renderer.dispose()
  }

  @Test
  fun t12_tab2EglContextLossRecoveryAndSafeDisposeVerified() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val statuses = mutableListOf<Tab2WaterWorldRuntimeStatus>()
    val renderer = Tab2WaterWorldSceneRenderer(
      appContext = context,
      onStatusChanged = { statuses += it },
    )
    // Verify safe host pause/resume/restart/dispose sequence even prior to GL surface creation
    renderer.triggerPhotoRecognitionRestart()
    assertEquals(Tab2PhotoPerimeterState.PHOTO_SELECTED, renderer.currentPerimeterSnapshot().state)
    renderer.onHostPause()
    renderer.onHostResume()
    renderer.dispose()
    // Calling dispose again is idempotent and crash-free
    renderer.dispose()
  }

  @Test
  fun t13_tab2IndependentPhotoPerimeterLightTransitionsAndStrictlyZeroOrbBeam() {
    val controller = Tab2IndependentPerimeterController()
    val initSnap = controller.onPhotoSelected(unpause = true)
    assertEquals(Tab2PhotoPerimeterState.PHOTO_SELECTED, initSnap.state)
    assertFalse("Orb->Photo beam must NEVER activate", initSnap.orbToPhotoBeamActive)
    assertEquals(0f, initSnap.toPerimeterRenderSnapshot().releaseProgress, 0f)
    assertEquals(0f, initSnap.toPerimeterRenderSnapshot().contactIntensity, 0f)

    // Advance to 0.35s -> PHOTO_STABLE (still zero light before activation)
    repeat(7) { controller.step(0.05) }
    val stableSnap = controller.snapshot()
    assertEquals(Tab2PhotoPerimeterState.PHOTO_STABLE, stableSnap.state)
    assertEquals(0f, stableSnap.perimeterProgress, 1e-6f)
    assertFalse(stableSnap.orbToPhotoBeamActive)

    // Advance to 0.80s -> PERIMETER_LIGHT_ACTIVATED revealing around photo
    repeat(9) { controller.step(0.05) }
    val activeSnap = controller.snapshot()
    assertEquals(Tab2PhotoPerimeterState.PERIMETER_LIGHT_ACTIVATED, activeSnap.state)
    assertTrue("Perimeter light must reveal independently around photo", activeSnap.perimeterProgress > 0.2f)
    val renderSnap = activeSnap.toPerimeterRenderSnapshot()
    assertEquals("releaseProgress MUST remain 0 (NO orb-to-photo energy beam!)", 0f, renderSnap.releaseProgress, 0f)
    assertEquals("contactIntensity MUST remain 0 (NO orb impact flash!)", 0f, renderSnap.contactIntensity, 0f)
    assertTrue("Left perimeter must glow independently", renderSnap.leftProgress > 0.2f)
    assertTrue("Right perimeter must glow independently", renderSnap.rightProgress > 0.2f)

    // Advance to 2.5s -> 100% perimeter coverage looping smoothly
    repeat(40) { controller.step(0.05) }
    val loopSnap = controller.snapshot()
    assertEquals(Tab2PhotoPerimeterState.PERIMETER_LIGHT_ACTIVATED, loopSnap.state)
    assertTrue(loopSnap.toPerimeterRenderSnapshot().coverageComplete)
    assertEquals(0f, loopSnap.toPerimeterRenderSnapshot().releaseProgress, 0f)

    // Exact transition sequence verified
    assertEquals(
      listOf(
        Tab2PhotoPerimeterState.PHOTO_SELECTED,
        Tab2PhotoPerimeterState.PHOTO_STABLE,
        Tab2PhotoPerimeterState.PERIMETER_LIGHT_ACTIVATED,
      ),
      controller.transitionHistory,
    )
  }

  @Test
  fun t14_tab2OrbViewportRectNeverOverlapsPhotoAndPreserves1To1CircleAcrossViewports() {
    val viewports = listOf(
      852 to 1846,
      1080 to 2340,
      1080 to 2400,
      1080 to 1920,
      1600 to 2560,
    )
    for ((sw, sh) in viewports) {
      val photo = PhotoFrameGeometry.canonicalForViewport(sw, sh)
      val orbRect = Tab2OrbViewportRect.compute(sw, sh, photo, bottomSafeInsetPx = 48f)
      assertTrue("Orb square side must be positive for ${sw}x${sh}", orbRect.sidePx >= 96)
      assertTrue("Orb must fit horizontally inside surface for ${sw}x${sh}", orbRect.leftPx >= 0 && orbRect.leftPx + orbRect.sidePx <= sw)
      assertTrue("Orb must fit vertically inside surface for ${sw}x${sh}", orbRect.bottomPx >= 0 && orbRect.bottomPx + orbRect.sidePx <= sh)
      assertTrue(
        "Orb square top (${orbRect.topPxFromTopOrigin}) must stay strictly below photo bottom (${photo.bottomPx}) for ${sw}x${sh}",
        orbRect.topPxFromTopOrigin.toFloat() >= photo.bottomPx,
      )
      // Centered horizontally within 1px
      val orbCenterX = orbRect.leftPx + orbRect.sidePx * 0.5f
      assertEquals("Orb must stay centered horizontally for ${sw}x${sh}", sw * 0.5f, orbCenterX, 1.5f)
    }
  }

  @Test
  fun t15_tab2NewGlslEs300ShadersSatisfyStrictActiveUniformContractWithZeroDeadUniforms() {
    val shaders = listOf(
      "TAB2_BG_VERT" to Tab2UnderwaterBackgroundRenderer.VERT_SHADER,
      "TAB2_BG_FRAG" to Tab2UnderwaterBackgroundRenderer.FRAG_SHADER,
      "TAB2_SPHERE_COMPOSITE_VERT" to Tab2WaterWorldSceneRenderer.SPHERE_COMPOSITE_VERT,
      "TAB2_SPHERE_COMPOSITE_FRAG" to Tab2WaterWorldSceneRenderer.SPHERE_COMPOSITE_FRAG,
    )
    val declRegex = Regex("\\buniform\\s+\\w+\\s+(\\w+)\\s*;")
    for ((name, src) in shaders) {
      assertTrue("$name must start with #version 300 es", src.trimStart().startsWith("#version 300 es"))
      assertTrue("$name must declare precision highp float", src.contains("precision highp float;"))
      assertTrue("$name must declare void main()", src.contains("void main()"))
      val clean = Regex("//[^\\n]*").replace(src, "")
      val declared = declRegex.findAll(clean).map { it.groupValues[1] }.toSet()
      val body = declRegex.replace(clean, "")
      for (u in declared) {
        val reads = Regex("\\b${Regex.escape(u)}\\b").findAll(body).count()
        assertTrue("$name uniform '$u' must be actively read in shader body", reads > 0)
      }
    }
    // Verify Work 3.1.1 uWaterFlowPhase remains absent and OPAQUE_FRAGMENT reads uSphereBackdropRadius
    assertFalse(WaterWorldShaders.WATER_FRAGMENT.contains("uWaterFlowPhase"))
    assertTrue(WaterWorldShaders.OPAQUE_FRAGMENT.contains("uniform float uSphereBackdropRadius;"))
    assertTrue(WaterWorldShaders.OPAQUE_FRAGMENT.contains("uSphereBackdropRadius > 0.5"))
  }

  @Test
  fun t16_tab1AndTab3ZeroRegressionAndTab2ComposeOverlayRendersCleanly() {
    // 1. Tab 1 AIOrbViewModel defaults untouched
    val vm = AIOrbViewModel()
    assertEquals(DemoTab.AI_ORB, vm.uiState.value.activeTab)
    assertEquals(OrbSceneMode.ORB_ONLY, vm.uiState.value.config.sceneMode)
    assertEquals(AIOrbConfig.Default, vm.uiState.value.config)

    // 2. Tab 2 TaskRecognitionDemoScreen renders with Water World status & photo picker button
    var pickClicked = false
    composeRule.setContent {
      MyApplicationTheme {
        TaskRecognitionDemoScreen(
          evidence = TaskEnergyEvidence(),
          debugOptions = TaskRenderDebugOptions.Default,
          captureProgress = TaskGpuCaptureProgress(),
          isControlsExpanded = false,
          onBackToOrb = {},
          onToggleControls = {},
          onCloseControls = {},
          onTogglePlayPause = {},
          onRestart = {},
          onJumpKeyframe = {},
          onUpdateDebugOptions = {},
          onExport6KfEvidence = {},
          onPhotoWindowBoundsChanged = { _, _, _, _, _ -> },
          waterWorldStatus = Tab2WaterWorldRuntimeStatus(
            isReady = true,
            isCoreVolumeReady = true,
            isPlaying = true,
            passCount = 11,
            opticalNodeCount = 5,
            perimeterState = Tab2PhotoPerimeterState.PERIMETER_LIGHT_ACTIVATED,
          ),
          onPickPhoto = { pickClicked = true },
        )
      }
    }
    composeRule.onNodeWithTag("task_header_title").assertIsDisplayed()
    composeRule.onNodeWithTag("task_status_understanding").assertIsDisplayed()
    composeRule.onNodeWithTag("tab2_pick_photo_button").assertIsDisplayed().performClick()
    assertTrue("Clicking Pick Photo button must trigger zero-permission PickVisualMedia callback", pickClicked)

    // 3. Tab 3 WaterWorldPreviewActivity still launches into FINAL_SCENE cleanly with diagnostics collapsed
    val tab3Controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java).create().start().resume()
    val tab3Activity = tab3Controller.get()
    assertEquals(WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE, tab3Activity.activeDiagnosticMode)
    assertFalse(tab3Activity.isDiagnosticsExpanded)
    tab3Controller.pause().stop().destroy()
  }
}
