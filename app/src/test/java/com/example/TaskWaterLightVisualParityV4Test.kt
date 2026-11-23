package com.example

import com.example.orb.config.AIOrbConfig
import com.example.orb.config.OrbSceneMode
import com.example.orb.config.RenderDebugMode
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.gl.RibbonOpticsShaders
import com.example.orb.scene.AIOrbOrbitRibbons
import com.example.orb.scene.AIOrbScene
import com.example.orb.scene.AIOrbTelemetry
import com.example.orb.scene.RibbonRole
import com.example.orb.task.PerimeterArcLengthTable
import com.example.orb.task.PerimeterPathGeometry
import com.example.orb.task.PerimeterRibbonLayer
import com.example.orb.task.PerimeterRibbonMeshBuilder
import com.example.orb.task.PerimeterSide
import com.example.orb.task.PerimeterWaterLightMaterial
import com.example.orb.task.PerimeterWaterLightParticles
import com.example.orb.task.PhotoFrameGeometry
import com.example.orb.task.TaskEnergyController
import com.example.orb.task.TaskEnergyEvidence
import com.example.orb.task.TaskEnergyGeometry
import com.example.orb.task.TaskEnergyRibbonRenderer
import com.example.orb.task.TaskEnergyStage
import com.example.orb.task.TaskGpuCaptureProgress
import com.example.orb.task.TaskGpuCaptureState
import com.example.orb.task.TaskGpuDrawTelemetry
import com.example.orb.task.TaskGpuEvidenceCapture
import com.example.orb.task.TaskRenderDebugOptions
import com.example.orb.task.TaskSceneLayoutSnapshot
import com.example.orb.task.TaskStreamKind
import com.example.orb.task.TaskVisualDebugMode
import com.example.orb.task.TaskWaterLightShaders
import com.example.orb.task.Vec2
import com.example.orb.ui.AIOrbViewModel
import com.example.orb.ui.DemoTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Automated Verification Suite (`T01`–`T20`) for `AI_ORB_TASK_PERIMETER_6KF_FINAL_VISUAL_PARITY_V4`:
 * Covers G0 (Tab 1 zero regression), G1 (GPU visibility diagnostics & telemetry),
 * P0-1..P0-9 (6KF visual parity fixes), and P1 (Orb bright-background contrast & focused pool caustics).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskWaterLightVisualParityV4Test {

  private val canonicalLayout = TaskSceneLayoutSnapshot.canonicalFallback(1080, 2340)
  private val canonicalPerimeter = PerimeterPathGeometry(canonicalLayout.photoFrame)
  private val canonicalTable = PerimeterArcLengthTable(canonicalPerimeter, sampleCountPerSide = 220)

  @Test
  fun t01_allModifiedShadersSatisfyGlslEs300ContractsPrecisionAndZeroInvertedSmoothsteps() {
    val shaders = linkedMapOf(
      "TASK_BG_VERT" to TaskWaterLightShaders.TASK_BG_VERT,
      "TASK_BG_FRAG" to TaskWaterLightShaders.TASK_BG_FRAG,
      "PHOTO_QUAD_VERT" to TaskWaterLightShaders.PHOTO_QUAD_VERT,
      "PHOTO_QUAD_FRAG" to TaskWaterLightShaders.PHOTO_QUAD_FRAG,
      "TASK_RIBBON_VERT" to TaskWaterLightShaders.TASK_RIBBON_VERT,
      "TASK_RIBBON_FRAG" to TaskWaterLightShaders.TASK_RIBBON_FRAG,
      "PERIMETER_RIBBON_VERT" to TaskWaterLightShaders.PERIMETER_RIBBON_VERT,
      "PERIMETER_RIBBON_FRAG" to TaskWaterLightShaders.PERIMETER_RIBBON_FRAG,
      "TASK_SPARKLE_VERT" to TaskWaterLightShaders.TASK_SPARKLE_VERT,
      "TASK_SPARKLE_FRAG" to TaskWaterLightShaders.TASK_SPARKLE_FRAG,
      "CORE_GLASS_FRAG" to CoreFluidShaders.CORE_GLASS_FRAG,
      "POST_QUAD_VERT" to RibbonOpticsShaders.POST_QUAD_VERT,
      "COMPOSITE_FRAG" to RibbonOpticsShaders.COMPOSITE_FRAG,
    )
    val literalSmoothstepRegex = Regex("""(?<!inv)smoothstep\s*\(\s*([+-]?\d+(?:\.\d+)?)\s*,\s*([+-]?\d+(?:\.\d+)?)\s*,""")
    for ((name, src) in shaders) {
      assertTrue("$name must start with #version 300 es", src.trimStart().startsWith("#version 300 es"))
      assertTrue("$name must declare precision highp float;", src.contains("precision highp float;"))
      assertTrue("$name must declare precision highp int;", src.contains("precision highp int;"))
      assertTrue("$name must define void main()", src.contains("void main()"))
      var braces = 0
      var parens = 0
      for (ch in src) {
        when (ch) {
          '{' -> braces++
          '}' -> braces--
          '(' -> parens++
          ')' -> parens--
        }
        assertTrue("$name unmatched '}'", braces >= 0)
        assertTrue("$name unmatched ')'", parens >= 0)
      }
      assertEquals("$name unbalanced braces", 0, braces)
      assertEquals("$name unbalanced parens", 0, parens)
      for (m in literalSmoothstepRegex.findAll(src)) {
        val e0 = m.groupValues[1].toFloat()
        val e1 = m.groupValues[2].toFloat()
        assertTrue("$name inverted smoothstep($e0, $e1)", e0 < e1)
      }
    }
  }

  @Test
  fun t02_p01Kf06CoverageCompleteGuaranteesFullRevealWithoutTopCenterClipping() {
    val frag = TaskWaterLightShaders.PERIMETER_RIBBON_FRAG
    assertTrue("PERIMETER_RIBBON_FRAG must short-circuit revealMask to 1.0 when uCoverageComplete > 0.5",
      frag.contains("if (uCoverageComplete > 0.5)"))
    assertTrue("PERIMETER_RIBBON_FRAG must assign revealMask = 1.0 on complete coverage",
      frag.contains("revealMask = 1.0;"))

    // Verify coverageComplete is strictly driven by real left/right progress >= 0.999
    val controller = TaskEnergyController()
    val kf04 = controller.jumpToKeyframe(TaskEnergyStage.SIDE_ASCEND, canonicalPerimeter)
    assertFalse("KF04 must NOT set coverageComplete", kf04.coverageComplete)

    val kf05 = controller.jumpToKeyframe(TaskEnergyStage.TOP_JOIN, canonicalPerimeter)
    assertTrue("KF05 at top join must set coverageComplete", kf05.coverageComplete)
    assertTrue(kf05.leftProgress >= 0.999f && kf05.rightProgress >= 0.999f)

    val kf06 = controller.jumpToKeyframe(TaskEnergyStage.PERIMETER_LOOP, canonicalPerimeter)
    assertTrue("KF06 must hold coverageComplete = true", kf06.coverageComplete)
    assertEquals(1f, kf06.leftProgress, 1e-5f)
    assertEquals(1f, kf06.rightProgress, 1e-5f)
  }

  @Test
  fun t03_p02MainStreamEndpointsAnchorExactlyToOrbTopAndPhotoBottomCenterWithZeroOvershoot() {
    val streams = TaskEnergyGeometry.buildDefaultStreams()
    assertEquals(4, streams.size)
    val main = streams.first { it.kind == TaskStreamKind.MAIN_ICE_STREAM }

    // Exact normalized endpoint contract: no 1.01f overshoot into photo!
    assertEquals(0.0f, main.controlPointsNorm.first().x, 1e-5f)
    assertEquals(0.0f, main.controlPointsNorm.first().y, 1e-5f)
    assertEquals(0.0f, main.controlPointsNorm.last().x, 1e-5f)
    assertEquals(1.0f, main.controlPointsNorm.last().y, 1e-5f)

    // Physical pixel centerline verification at s=0 and s=1
    val pStart = TaskEnergyGeometry.evaluateCenterlinePx(main, canonicalLayout, 0f)
    val pEnd = TaskEnergyGeometry.evaluateCenterlinePx(main, canonicalLayout, 1f)
    assertEquals(canonicalLayout.orbAnchorTopPx.x, pStart.x, 1e-3f)
    assertEquals(canonicalLayout.orbAnchorTopPx.y, pStart.y, 1e-3f)
    assertEquals(canonicalLayout.photoBottomCenterPx.x, pEnd.x, 1e-3f)
    assertEquals(canonicalLayout.photoBottomCenterPx.y, pEnd.y, 1e-3f)

    // Shader contract: zero endpoint sway & main-stream contact continuity
    assertTrue(TaskWaterLightShaders.TASK_RIBBON_VERT.contains("if (uStreamKind == 0 && (s <= 0.001 || s >= 0.999))"))
    assertTrue(TaskWaterLightShaders.TASK_RIBBON_FRAG.contains("float contactEndFade = 1.0 - 0.20 * smoothstep(0.92, 1.0, s);"))
    assertTrue(TaskWaterLightShaders.TASK_RIBBON_FRAG.contains("rootFade = 0.55 + 0.45 * smoothstep(0.0, 0.045, s);"))
  }

  @Test
  fun t04_p03TaskEnergyThreeLayerLiquidMaterialAndCalibratedStreamAlphas() {
    val streams = TaskEnergyGeometry.buildDefaultStreams().associateBy { it.kind }
    assertEquals(0.78f, streams.getValue(TaskStreamKind.MAIN_ICE_STREAM).alphaScale, 1e-4f)
    assertEquals(0.50f, streams.getValue(TaskStreamKind.SECONDARY_LEFT_CYAN).alphaScale, 1e-4f)
    assertEquals(0.52f, streams.getValue(TaskStreamKind.SECONDARY_RIGHT_FINE).alphaScale, 1e-4f)
    assertEquals(0.48f, streams.getValue(TaskStreamKind.SECONDARY_EMERALD_VEIL).alphaScale, 1e-4f)

    val frag = TaskWaterLightShaders.TASK_RIBBON_FRAG
    assertTrue("TASK_RIBBON_FRAG must compute dark refractive edge contrast", frag.contains("float refractiveEdge ="))
    assertTrue("TASK_RIBBON_FRAG must compute undulating internal liquid crest", frag.contains("float liquidCrest ="))
    assertTrue("TASK_RIBBON_FRAG must apply crisp specular liquid crest tint", frag.contains("vec3(0.48, 1.02, 1.35) * liquidCrest * 0.30"))
    assertTrue("TASK_RIBBON_FRAG must include fine internal optical filaments", frag.contains("float fineFilament ="))
  }

  @Test
  fun t05_p04ScreenSpaceLiquidRefractionContractAndFiniteUnitTaskNormals() {
    assertEquals(8, TaskEnergyGeometry.FLOATS_PER_VERTEX)
    assertEquals(1.0f, TaskEnergyRibbonRenderer.DEFAULT_REFRACTION_PX, 1e-5f)
    assertEquals(0.35f, TaskEnergyRibbonRenderer.DEFAULT_REFRACTION_STRENGTH, 1e-5f)

    for (spec in TaskEnergyGeometry.buildDefaultStreams()) {
      val verts = TaskEnergyGeometry.buildRibbonVerticesNdc(spec, canonicalLayout)
      assertTrue(verts.size >= (TaskEnergyGeometry.SEGMENTS_PER_STREAM + 1) * 2 * 8)
      var i = 0
      while (i < verts.size) {
        val nx = verts[i + 6]
        val ny = verts[i + 7]
        assertTrue("Normal X/Y must be finite", nx.isFinite() && ny.isFinite())
        val len = sqrt(nx * nx + ny * ny)
        assertEquals("Lateral refraction normal must be unit length", 1.0f, len, 1e-3f)
        i += 8
      }
    }

    for (frag in listOf(TaskWaterLightShaders.TASK_RIBBON_FRAG, TaskWaterLightShaders.PERIMETER_RIBBON_FRAG)) {
      assertTrue(frag.contains("uniform sampler2D uTaskRefractionTex;"))
      assertTrue(frag.contains("uniform vec2 uFboSize;"))
      assertTrue(frag.contains("uniform float uRefractionPx;"))
      assertTrue(frag.contains("uniform float uRefractionStrength;"))
      assertTrue("Shader must guard alpha < 0.002 discard before refraction division", frag.contains("if (alpha < 0.002) discard;"))
      assertTrue("Shader must protect division with max(alpha, 0.08)", frag.contains("max(alpha, 0.08)"))
    }
  }

  @Test
  fun t06_p05CornerFlareAndEmeraldFlankWidthTransitionsAreContinuousWithZeroDiscreteJumps() {
    val transitions = listOf(
      canonicalPerimeter.bottomCornerStartFrac,
      canonicalPerimeter.bottomCornerEndFrac,
      canonicalPerimeter.topCornerStartFrac,
      canonicalPerimeter.topCornerEndFrac,
    )
    val eps = 0.0005f

    for (side in PerimeterSide.entries) {
      for (uCorner in transitions) {
        val uBefore = (uCorner - eps).coerceAtLeast(0f)
        val uAfter = (uCorner + eps).coerceAtMost(1f)
        val sBefore = uBefore * canonicalPerimeter.totalHalfLengthPx
        val sAfter = uAfter * canonicalPerimeter.totalHalfLengthPx

        val flareDiff = abs(
          PerimeterRibbonMeshBuilder.evaluateCornerFlare(canonicalPerimeter, sAfter) -
            PerimeterRibbonMeshBuilder.evaluateCornerFlare(canonicalPerimeter, sBefore)
        )
        assertTrue("Corner flare must be continuous across $uCorner (diff=$flareDiff)", flareDiff < 0.02f)

        val flankDiff = abs(
          PerimeterRibbonMeshBuilder.evaluateEmeraldFlankMask(canonicalPerimeter, sAfter) -
            PerimeterRibbonMeshBuilder.evaluateEmeraldFlankMask(canonicalPerimeter, sBefore)
        )
        assertTrue("Emerald flankMask must be continuous across $uCorner (diff=$flankDiff)", flankDiff < 0.025f)

        for (layer in PerimeterRibbonLayer.entries) {
          val (inB, outB) = PerimeterRibbonMeshBuilder.evaluateRibbonOffsetsPx(canonicalPerimeter, side, layer, uBefore, sBefore)
          val (inA, outA) = PerimeterRibbonMeshBuilder.evaluateRibbonOffsetsPx(canonicalPerimeter, side, layer, uAfter, sAfter)
          val wB = outB - inB
          val wA = outA - inA
          assertTrue("$layer width must have zero discrete step across $uCorner (|dw|=${abs(wA - wB)})", abs(wA - wB) < 0.8f)
        }
      }
    }
  }

  @Test
  fun t07_p05AndP06EnforceFrozenL1L3WidthBoundsAndMaterialAlphaScaleCaps() {
    val photoW = canonicalPerimeter.widthPx
    for (side in PerimeterSide.entries) {
      for (i in 0..200) {
        val u = i / 200f
        val sPx = u * canonicalPerimeter.totalHalfLengthPx

        val (l1In, l1Out) = PerimeterRibbonMeshBuilder.evaluateRibbonOffsetsPx(
          canonicalPerimeter, side, PerimeterRibbonLayer.L1_ICE_BLUE_BODY, u, sPx
        )
        val l1WidthRatio = (l1Out - l1In) / photoW
        assertTrue("L1 width ratio must stay in [0.004, 0.012], got $l1WidthRatio", l1WidthRatio in 0.00399f..0.01201f)

        val (l3In, l3Out) = PerimeterRibbonMeshBuilder.evaluateRibbonOffsetsPx(
          canonicalPerimeter, side, PerimeterRibbonLayer.L3_GOLD_FILAMENT, u, sPx
        )
        val l3WidthRatio = (l3Out - l3In) / photoW
        assertEquals("L3 gold filament width must stay ~0.45% PhotoW", 0.0045f, l3WidthRatio, 0.0005f)
      }
    }

    val mat = PerimeterWaterLightMaterial()
    assertEquals(1.25f, mat.l1IceAlphaScale, 1e-5f)
    assertEquals(1.15f, mat.l2EmeraldAlphaScale, 1e-5f)
    assertEquals(1.10f, mat.l3GoldAlphaScale, 1e-5f)
    assertEquals(11, mat.dropletCountPerSide)

    val frag = TaskWaterLightShaders.PERIMETER_RIBBON_FRAG
    assertTrue("PERIMETER_RIBBON_FRAG must declare uLayerAlphaScale", frag.contains("uniform float uLayerAlphaScale;"))
    assertTrue("L1 cap must be 0.38", frag.contains("alphaCap = 0.38;"))
    assertTrue("L2 cap must be 0.24", frag.contains("alphaCap = 0.24;"))
    assertTrue("L3 cap must be 0.50", frag.contains("alphaCap = 0.50;"))
    assertTrue("Alpha cap must apply AFTER uLayerAlphaScale", frag.contains("alpha = clamp(alpha * revealMask * uLayerAlphaScale, 0.0, alphaCap);"))
  }

  @Test
  fun t08_p07CompactKf02ContactGlowAndQuadFallbackPreventPointSizeTruncation() {
    assertEquals(0.065f, PerimeterWaterLightParticles.CONTACT_GOLD_FOCUS_RATIO, 1e-5f)
    assertEquals(0.11f, PerimeterWaterLightParticles.CONTACT_CYAN_RIPPLE_RATIO, 1e-5f)

    val particles = PerimeterWaterLightParticles()
    val controller = TaskEnergyController()
    val kf02 = controller.jumpToKeyframe(TaskEnergyStage.BOTTOM_CONTACT, canonicalPerimeter)
    val count = particles.buildDynamicPoints(canonicalTable, canonicalLayout, kf02)
    assertTrue("KF02 must emit contact glow + droplets", count >= 2)

    val photoW = canonicalPerimeter.widthPx
    assertTrue("Max emitted KF02 point size must stay compact <= 0.12*PhotoW (${particles.maxEmittedPointSizePx})",
      particles.maxEmittedPointSizePx <= photoW * 0.12f)

    // Verify quad fallback geometry expands every point cleanly into 6 finite NDC vertices with [0..1] UVs
    val quadVertCount = particles.buildQuadVertices(count, canonicalLayout.surfaceWidthPx, canonicalLayout.surfaceHeightPx)
    assertEquals(count * 6, quadVertCount)
    val qBuf = particles.rawQuadBuffer()
    for (v in 0 until quadVertCount) {
      val o = v * PerimeterWaterLightParticles.QUAD_STRIDE_FLOATS
      assertTrue(qBuf[o].isFinite() && qBuf[o + 1].isFinite())
      assertTrue(qBuf[o + 5] in 0f..1f)
      assertTrue(qBuf[o + 6] in 0f..1f)
    }

    assertTrue(TaskWaterLightShaders.TASK_SPARKLE_VERT.contains("uniform int uUseQuad;"))
    assertTrue(TaskWaterLightShaders.TASK_SPARKLE_FRAG.contains("vec2 uv = (uUseQuad == 1) ? vQuadUv : gl_PointCoord;"))
  }

  @Test
  fun t09_p08Kf04JumpBisectionHitsExactSixtyPercentVerticalSideAcrossAllAspectRatios() {
    val viewports = listOf(
      1080 to 2400, // 20:9 tall phone
      1080 to 1920, // 16:9 standard phone
      1600 to 2560, // Portrait tablet
      1200 to 1200, // Square foldable
    )
    val controller = TaskEnergyController()
    for ((w, h) in viewports) {
      val layout = TaskSceneLayoutSnapshot.canonicalFallback(w, h)
      val perim = PerimeterPathGeometry(layout.photoFrame)
      val kf04 = controller.jumpToKeyframe(TaskEnergyStage.SIDE_ASCEND, perim)

      assertEquals("Stage must be SIDE_ASCEND for ${w}x${h}", TaskEnergyStage.SIDE_ASCEND, kf04.stage)
      assertEquals(
        "KF04 jump must land within +-0.002 of exact 60% vertical side fraction for ${w}x${h}",
        perim.kf04SideAscendFrac,
        kf04.leftProgress,
        0.002f,
      )
      assertEquals(kf04.leftProgress, kf04.rightProgress, 1e-5f)
      assertFalse(kf04.coverageComplete)
      assertEquals(0f, kf04.joinIntensity, 1e-5f)
    }
  }

  @Test
  fun t10_p09Normal6KfTimelineEventOrderAndKf06SofteningRemainIntact() {
    assertEquals(1000.0, TaskEnergyController.T_CONTACT_START_MS, 1e-6)
    assertEquals(1250.0, TaskEnergyController.T_SPLIT_START_MS, 1e-6)
    assertEquals(2500.0, TaskEnergyController.T_ASCEND_START_MS, 1e-6)
    assertEquals(4300.0, TaskEnergyController.T_TOP_JOIN_START_MS, 1e-6)
    assertEquals(5500.0, TaskEnergyController.T_LOOP_START_MS, 1e-6)

    val controller = TaskEnergyController()
    controller.restart(unpause = true)
    repeat(8 * 60) { controller.step(1.0 / 60.0, canonicalPerimeter) }
    val endSnap = controller.snapshot()
    assertEquals(TaskEnergyStage.PERIMETER_LOOP, endSnap.stage)
    assertTrue(endSnap.coverageComplete)
    assertEquals(com.example.orb.task.TaskEnergyEvent.entries, controller.events)

    val kf05 = controller.jumpToKeyframe(TaskEnergyStage.TOP_JOIN, canonicalPerimeter)
    val kf06 = controller.jumpToKeyframe(TaskEnergyStage.PERIMETER_LOOP, canonicalPerimeter)
    assertTrue("KF06 join glow must soften clearly below 50% of KF05", kf06.joinIntensity < kf05.joinIntensity * 0.50f)
  }

  @Test
  fun t11_p1BrightBackgroundOrbOpticsModulateTab2OnlyAndLeaveTab1Unchanged() {
    assertTrue(CoreFluidShaders.CORE_GLASS_FRAG.contains("uniform float uTaskInteriorTransmission;"))
    assertTrue(CoreFluidShaders.CORE_GLASS_FRAG.contains("uniform float uTaskAbsorptionGain;"))
    assertEquals(0.75f, AIOrbScene.TAB2_ORB_INTERIOR_TRANSMISSION, 1e-5f)
    assertEquals(1.12f, AIOrbScene.TAB2_ORB_ABSORPTION_GAIN, 1e-5f)
  }

  @Test
  fun t12_p1FocusedBottomWaterCausticsStayBelowPhotoAndBelowBloomThreshold() {
    val bgFrag = TaskWaterLightShaders.TASK_BG_FRAG
    assertTrue("TASK_BG_FRAG must attenuate pool caustics laterally", bgFrag.contains("float lateralAtten = exp(-dxPool * dxPool * 8.5);"))
    assertTrue("TASK_BG_FRAG must gate pool caustics strictly below photo & Task stream", bgFrag.contains("float belowTaskGate ="))
    assertTrue("TASK_BG_FRAG must cap luminance strictly below Bloom threshold (1.32)", bgFrag.contains("col = min(col, vec3(1.10));"))
  }

  @Test
  fun t13_g1VisualDebugModesAndLayerIsolationPresetsMapAccurately() {
    assertEquals(6, TaskVisualDebugMode.entries.size)
    assertEquals(0, TaskVisualDebugMode.NORMAL.shaderDebugId)
    assertEquals(1, TaskVisualDebugMode.GEOMETRY_MAGENTA.shaderDebugId)
    assertEquals(2, TaskVisualDebugMode.ALPHA_HEATMAP.shaderDebugId)

    assertFalse(TaskRenderDebugOptions.TaskOff.isTaskEnergyEnabled)
    assertFalse(TaskRenderDebugOptions.TaskOff.isPerimeterEnabled)
    assertTrue(TaskRenderDebugOptions.TaskOnly.isTaskEnergyEnabled)
    assertFalse(TaskRenderDebugOptions.TaskOnly.isPerimeterEnabled)
    assertFalse(TaskRenderDebugOptions.PerimeterOnly.isTaskEnergyEnabled)
    assertTrue(TaskRenderDebugOptions.PerimeterOnly.isPerimeterEnabled)
    assertTrue(TaskRenderDebugOptions.L1Only.showL1 && !TaskRenderDebugOptions.L1Only.showL2 && !TaskRenderDebugOptions.L1Only.showL3)
    assertTrue(TaskRenderDebugOptions.L2Only.showL2 && !TaskRenderDebugOptions.L2Only.showL1 && !TaskRenderDebugOptions.L2Only.showL3)
    assertTrue(TaskRenderDebugOptions.L3Only.showL3 && !TaskRenderDebugOptions.L3Only.showL1 && !TaskRenderDebugOptions.L3Only.showL2)
    assertTrue(TaskRenderDebugOptions.L4Only.showL4 && !TaskRenderDebugOptions.L4Only.showL1)
  }

  @Test
  fun t14_g1SwitchingDebugModesNeverMutatesTaskEnergyControllerState() {
    val scene = AIOrbScene(initialConfig = AIOrbConfig.Default.copy(sceneMode = OrbSceneMode.TASK_PERIMETER_DEMO))
    val baseline = scene.jumpToTaskKeyframe(TaskEnergyStage.SIDE_ASCEND)
    val baselineEvents = scene.currentTaskEvidence().emittedEvents

    val allPresets = listOf(
      TaskRenderDebugOptions.GeometryMagenta,
      TaskRenderDebugOptions.AlphaHeatmap,
      TaskRenderDebugOptions.PreTonemap,
      TaskRenderDebugOptions.FinalComposite,
      TaskRenderDebugOptions.TaskOff,
      TaskRenderDebugOptions.TaskOnly,
      TaskRenderDebugOptions.PerimeterOnly,
      TaskRenderDebugOptions.L1Only,
      TaskRenderDebugOptions.L2Only,
      TaskRenderDebugOptions.L3Only,
      TaskRenderDebugOptions.L4Only,
      TaskRenderDebugOptions.Default,
    )
    for (preset in allPresets) {
      scene.updateTaskDebugOptions(preset)
      assertEquals(preset, scene.currentTaskDebugOptions())
      val after = scene.currentTaskSnapshot()
      assertEquals("Debug switch must NEVER alter stage", baseline.stage, after.stage)
      assertEquals("Debug switch must NEVER alter elapsedMs", baseline.elapsedMs, after.elapsedMs)
      assertEquals("Debug switch must NEVER alter releaseProgress", baseline.releaseProgress, after.releaseProgress, 0f)
      assertEquals("Debug switch must NEVER alter leftProgress", baseline.leftProgress, after.leftProgress, 0f)
      assertEquals("Debug switch must NEVER alter rightProgress", baseline.rightProgress, after.rightProgress, 0f)
      assertEquals("Debug switch must NEVER alter coverageComplete", baseline.coverageComplete, after.coverageComplete)
      assertEquals("Debug switch must NEVER alter flowTimeSec", baseline.flowTimeSec, after.flowTimeSec, 0f)
      assertEquals("Debug switch must NEVER alter emitted events", baselineEvents, scene.currentTaskEvidence().emittedEvents)
    }
  }

  @Test
  fun t15_g1CompositeShaderProvidesPreTonemapBypassWhilePreservingAcesSrgbPath() {
    val comp = RibbonOpticsShaders.COMPOSITE_FRAG
    assertTrue(comp.contains("uniform int uPreTonemapBypass;"))
    assertTrue(comp.contains("if (uPreTonemapBypass == 1)"))
    assertTrue(comp.contains("vec3 mapped = acesFilm(hdr);"))
    assertTrue(comp.contains("pow(max(mapped, vec3(0.0)), vec3(1.0 / 2.2))"))
  }

  @Test
  fun t16_g1GpuDrawTelemetryAndEvidenceSummaryReportCompleteMetrics() {
    val draw = TaskGpuDrawTelemetry(
      taskRibbonVertexCount = 456,
      taskRibbonDrawCalls = 4,
      taskRibbonShaderLinked = true,
      taskRibbonVaoIds = listOf(1, 2, 3, 4),
      taskRibbonVboIds = listOf(11, 12, 13, 14),
      perimeterVertexCount = 2640,
      perimeterDrawCalls = 6,
      perimeterShaderLinked = true,
      perimeterVaoIds = listOf(5, 6, 7, 8, 9, 10),
      perimeterVboIds = listOf(15, 16, 17, 18, 19, 20),
      sparkleCount = 28,
      sparkleDrawCalls = 1,
      sparkleUsedQuadFallback = false,
      maxAliasedPointSizePx = 128f,
      fboComplete = true,
      fboWidth = 1080,
      fboHeight = 2340,
      glErrorCode = 0,
    )
    assertEquals(3096, draw.totalVertexCount)
    assertEquals(11, draw.totalDrawCalls)
    assertFalse(draw.hasGlError)

    val evidence = TaskEnergyEvidence(
      glContextInitCount = 1,
      webpLoadCount = 1,
      layoutSnapshot = canonicalLayout,
      debugOptions = TaskRenderDebugOptions.GeometryMagenta,
      drawTelemetry = draw,
    )
    val line = evidence.summaryLine()
    assertTrue(line.contains("vtx=3096"))
    assertTrue(line.contains("draws=11"))
    assertTrue(line.contains("fbo=true"))
    assertTrue(line.contains("glErr=0x0"))
    assertTrue(line.contains("mode=GEOMETRY_MAGENTA"))
  }

  @Test
  fun t17_v4GpuEvidenceCaptureManifestCoversAll16RequiredFramesAndTelemetryJson() {
    val names = TaskGpuEvidenceCapture.CAPTURE_STEPS.map { it.fileName }
    assertEquals(16, names.size)
    val required = listOf(
      "KF01_FINAL_GPU.png",
      "KF02_FINAL_GPU.png",
      "KF03_FINAL_GPU.png",
      "KF04_FINAL_GPU.png",
      "KF05_FINAL_GPU.png",
      "KF06_FINAL_GPU.png",
      "KF06_GEOMETRY_MAGENTA.png",
      "KF06_ALPHA_HEATMAP.png",
      "KF06_LAYER_L1_ONLY.png",
      "KF06_LAYER_L2_ONLY.png",
      "KF06_LAYER_L3_ONLY.png",
      "KF06_PRE_TONEMAP.png",
      "KF06_FINAL_COMPOSITE.png",
      "KF06_TASK_OFF_GPU.png",
      "KF06_TASK_ONLY_GPU.png",
      "KF06_PERIMETER_ONLY_GPU.png",
    )
    for (req in required) {
      assertTrue("Missing required V4 capture frame $req", names.contains(req))
    }

    val json = TaskGpuEvidenceCapture.buildTelemetryJson(
      evidence = TaskEnergyEvidence(glContextInitCount = 1, webpLoadCount = 1),
      telemetry = AIOrbTelemetry(glVendor = "TestVendor", glRenderer = "TestGPU"),
      surfaceWidth = 1080,
      surfaceHeight = 2340,
    )
    assertTrue(json.contains("AI_ORB_TASK_PERIMETER_6KF_FINAL_VISUAL_PARITY_V4"))
    assertTrue(json.contains("\"taskWaterLightShaderSha256\""))
    assertTrue(json.contains("KF06_GEOMETRY_MAGENTA.png"))
  }

  @Test
  fun t18_v4PerimeterRibbonVerticesNeverCrossPhotoInteriorAcrossAllViewports() {
    val viewports = listOf(1080 to 2400, 1080 to 1920, 1600 to 2560, 1200 to 1200)
    for ((sw, sh) in viewports) {
      val layout = TaskSceneLayoutSnapshot.canonicalFallback(sw, sh)
      val photo = layout.photoFrame
      val table = PerimeterArcLengthTable(PerimeterPathGeometry(photo), sampleCountPerSide = 220)
      for (side in PerimeterSide.entries) {
        for (layer in PerimeterRibbonLayer.entries) {
          val verts = PerimeterRibbonMeshBuilder.buildLayerStripNdc(table, side, layer, sw, sh)
          var i = 0
          while (i < verts.size) {
            val px = (verts[i] + 1f) * 0.5f * sw
            val py = (1f - verts[i + 1]) * 0.5f * sh
            val sd = photo.signedDistancePx(Vec2(px, py))
            assertTrue("Vertex ($layer/$side @ ${sw}x${sh}) crossed inside photo (sd=$sd)", sd >= -0.5f)
            i += PerimeterRibbonMeshBuilder.FLOATS_PER_VERTEX
          }
        }
      }
    }
  }

  @Test
  fun t19_viewModelTaskDebugOptionsAndCaptureProgressPreserveTab1Config() {
    val vm = AIOrbViewModel()
    vm.updateConfig { it.copy(coreScale = 1.08f, bloom = 0.42f) }
    val savedTab1 = vm.uiState.value.config

    vm.selectTab(DemoTab.TASK_PERIMETER)
    vm.updateTaskDebugOptions(TaskRenderDebugOptions.AlphaHeatmap)
    assertEquals(TaskVisualDebugMode.ALPHA_HEATMAP, vm.uiState.value.taskDebugOptions.mode)

    val capProg = TaskGpuCaptureProgress(state = TaskGpuCaptureState.COMPLETED, completedSteps = 16, totalSteps = 16)
    vm.reportTaskCaptureProgress(capProg)
    assertEquals(TaskGpuCaptureState.COMPLETED, vm.uiState.value.taskCaptureProgress.state)

    vm.selectTab(DemoTab.AI_ORB)
    assertEquals(savedTab1.copy(sceneMode = OrbSceneMode.ORB_ONLY), vm.uiState.value.config)
  }

  @Test
  fun t20_g0Tab1ZeroRegressionAndSingleSharedSceneIntegrity() {
    val tab1Passes = AIOrbScene.activeRenderPassesV3(RenderDebugMode.FULL_RENDER)
    assertFalse(tab1Passes.any { it.contains("TASK") || it.contains("PHOTO") || it.contains("PERIMETER") })

    val residents = AIOrbOrbitRibbons.buildRibbonDescriptors().filter { it.role == RibbonRole.RESIDENT }
    assertEquals(5, residents.size)
    assertEquals(setOf("G01", "G02", "A01", "A02", "C01"), residents.map { it.residentCode }.toSet())

    val scene = AIOrbScene(AIOrbConfig.Default)
    scene.updateConfig(AIOrbConfig.Default.copy(sceneMode = OrbSceneMode.TASK_PERIMETER_DEMO))
    scene.updateConfig(AIOrbConfig.Default.copy(sceneMode = OrbSceneMode.ORB_ONLY))
    assertTrue(scene.currentTaskEvidence().gpuResourcesReused)
  }
}
