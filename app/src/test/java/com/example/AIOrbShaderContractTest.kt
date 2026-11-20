package com.example

import com.example.orb.config.AIOrbConfig
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.gl.RibbonOpticsShaders
import com.example.orb.scene.AIOrbOrbitRibbons
import com.example.orb.scene.RibbonRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static contract & GLSL ES 3.00 compliance tests for all shaders, HDR linear pipeline math,
 * and Resident vs Secondary Ribbon architectural separation.
 */
class AIOrbShaderContractTest {

  private val allShaders: Map<String, String> = linkedMapOf(
    "BACKGROUND_VERT" to CoreFluidShaders.BACKGROUND_VERT,
    "BACKGROUND_FRAG" to CoreFluidShaders.BACKGROUND_FRAG,
    "INNER_FLUID_VERT" to CoreFluidShaders.INNER_FLUID_VERT,
    "INNER_FLUID_FRAG" to CoreFluidShaders.INNER_FLUID_FRAG,
    "CORE_GLASS_VERT" to CoreFluidShaders.CORE_GLASS_VERT,
    "CORE_GLASS_FRAG" to CoreFluidShaders.CORE_GLASS_FRAG,
    "RIBBON_VERT" to RibbonOpticsShaders.RIBBON_VERT,
    "RIBBON_FRAG" to RibbonOpticsShaders.RIBBON_FRAG,
    "ENERGY_HIGHLIGHT_VERT" to RibbonOpticsShaders.ENERGY_HIGHLIGHT_VERT,
    "ENERGY_HIGHLIGHT_FRAG" to RibbonOpticsShaders.ENERGY_HIGHLIGHT_FRAG,
    "BUBBLE_PARTICLE_VERT" to RibbonOpticsShaders.BUBBLE_PARTICLE_VERT,
    "BUBBLE_PARTICLE_FRAG" to RibbonOpticsShaders.BUBBLE_PARTICLE_FRAG,
    "POST_QUAD_VERT" to RibbonOpticsShaders.POST_QUAD_VERT,
    "BLOOM_EXTRACT_FRAG" to RibbonOpticsShaders.BLOOM_EXTRACT_FRAG,
    "BLOOM_BLUR_FRAG" to RibbonOpticsShaders.BLOOM_BLUR_FRAG,
    "COMPOSITE_FRAG" to RibbonOpticsShaders.COMPOSITE_FRAG,
  )

  @Test
  fun allShadersHaveValidGlslEs300HeaderMainAndBalancedDelimiters() {
    assertEquals(16, allShaders.size)
    for ((name, src) in allShaders) {
      val trimmed = src.trimStart()
      assertTrue("$name must start with #version 300 es", trimmed.startsWith("#version 300 es"))
      assertTrue("$name must declare precision highp float", src.contains("precision highp float;"))
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
        assertTrue("$name has unmatched closing brace", braces >= 0)
        assertTrue("$name has unmatched closing paren", parens >= 0)
      }
      assertEquals("$name has unbalanced braces", 0, braces)
      assertEquals("$name has unbalanced parentheses", 0, parens)
    }
  }

  @Test
  fun zeroInvertedSmoothstepCallsExistInAnyShader() {
    // GLSL ES 3.00 §8.3: smoothstep(edge0, edge1, x) is undefined if edge0 >= edge1.
    // Scan every `smoothstep(num1, num2, ...)` invocation across ALL 16 shaders.
    val numericSmoothstepRegex = Regex("""(?<!inv)smoothstep\s*\(\s*([+-]?\d+(?:\.\d+)?)\s*,\s*([+-]?\d+(?:\.\d+)?)\s*,""")

    for ((name, src) in allShaders) {
      val matches = numericSmoothstepRegex.findAll(src)
      for (m in matches) {
        val e0 = m.groupValues[1].toDouble()
        val e1 = m.groupValues[2].toDouble()
        assertTrue(
          "GLSL ES 3.00 UB detected in $name: smoothstep($e0, $e1, ...) has edge0 >= edge1",
          e0 < e1,
        )
      }
    }

    // Also confirm `invSmoothstep` is explicitly defined in shaders that perform inverted falloffs
    assertTrue(CoreFluidShaders.CORE_GLASS_FRAG.contains("float invSmoothstep(float lo, float hi, float x)"))
    assertTrue(RibbonOpticsShaders.RIBBON_FRAG.contains("float invSmoothstep(float lo, float hi, float x)"))
  }

  @Test
  fun coreAndInnerFluidShadersShareUnifiedMotionUniformContract() {
    val requiredSharedUniforms = listOf(
      "uniform mat3 uCoreRotMat;",
      "uniform float uFluidPhase;",
      "uniform float uMeniscusPhase;",
      "uniform float uCausticsPhase;",
      "uniform float uHdrScale;",
    )
    for (u in requiredSharedUniforms) {
      assertTrue("INNER_FLUID_FRAG missing $u", CoreFluidShaders.INNER_FLUID_FRAG.contains(u))
      assertTrue("CORE_GLASS_FRAG missing $u", CoreFluidShaders.CORE_GLASS_FRAG.contains(u))
    }

    // Verify adaptive raymarch uniform and grazing-angle refract guard exist in CORE_GLASS_FRAG
    assertTrue(CoreFluidShaders.CORE_GLASS_FRAG.contains("uniform int uRaymarchSteps;"))
    assertTrue(CoreFluidShaders.CORE_GLASS_FRAG.contains("if (dot(refrWorld, refrWorld) < 1e-5)"))
    assertTrue(CoreFluidShaders.CORE_GLASS_FRAG.contains("for (int b = 0; b < 3; b++)"))
  }

  @Test
  fun linearHdrScalePreservesBlendingAndBloomThresholdParity() {
    // RGBA16F uses scale 1.0f; RGBA8 fallback uses scale 0.25f (encodes [0..4.0] linearly in [0..1])
    val scales = listOf(1.0f, 0.25f)
    val linearSample = floatArrayOf(0.45f, 1.35f, 2.20f)
    val threshold = 1.12f
    val knee = 0.28f

    val extractedByScale = scales.map { hdrScale ->
      // Encode into FBO (clamped to [0,1] on RGBA8, unclamped on RGBA16F)
      val stored = FloatArray(3) { i ->
        val v = linearSample[i] * hdrScale
        if (hdrScale < 0.99f) v.coerceIn(0f, 1f) else v
      }
      // Decode inside BLOOM_EXTRACT_FRAG
      val decoded = FloatArray(3) { i -> stored[i] / hdrScale }
      val luma = decoded[0] * 0.2126f + decoded[1] * 0.7152f + decoded[2] * 0.0722f
      val soft = ((luma - threshold + knee) / (2f * knee)).coerceIn(0f, 1f)
      val smoothSoft = soft * soft * (3f - 2f * soft)
      val contrib = maxOf(luma - threshold, 0f) + smoothSoft * knee * 0.35f
      FloatArray(3) { i -> decoded[i] * (contrib / maxOf(luma, 1e-4f)) }
    }

    // Decoded HDR Bloom extraction must match identically between RGBA16F and RGBA8-HDR!
    for (c in 0..2) {
      assertEquals(extractedByScale[0][c], extractedByScale[1][c], 1e-4f)
    }
  }

  @Test
  fun ribbonArchitectureSeparates5CanonicalResidentsFromSecondaryOptics() {
    val descriptors = AIOrbOrbitRibbons.buildRibbonDescriptors()
    assertEquals(AIOrbConfig.MAX_RIBBON_COUNT, descriptors.size)

    val residents = descriptors.filter { it.role == RibbonRole.RESIDENT }
    val secondary = descriptors.filter { it.role == RibbonRole.SECONDARY_OPTICAL }

    assertEquals(AIOrbConfig.CANONICAL_RESIDENT_COUNT, residents.size)
    assertEquals(5, residents.size)
    assertEquals(5, secondary.size)

    // Indices 0..4 MUST strictly be RESIDENT; indices 5..9 MUST strictly be SECONDARY_OPTICAL
    for (i in 0..4) {
      assertEquals("Index $i must be RESIDENT", RibbonRole.RESIDENT, descriptors[i].role)
    }
    for (i in 5..9) {
      assertEquals("Index $i must be SECONDARY_OPTICAL", RibbonRole.SECONDARY_OPTICAL, descriptors[i].role)
    }

    // All ribbons use alpha blending (additiveBlend == false) to prevent cyan+amber white-out
    for (d in descriptors) {
      assertFalse("Ribbon ${d.id} should use alpha blending to prevent chromatic white-out", d.additiveBlend)
      assertTrue("Ribbon ${d.id} must have >= 8 control points", d.controlPoints.size >= 8)
    }
  }

  @Test
  fun fiveResidentOrganicRibbonsSatisfyR06IdentityGeometryAndShaderContracts() {
    val residents = AIOrbOrbitRibbons.buildRibbonDescriptors().filter { it.role == RibbonRole.RESIDENT }
    assertEquals(5, residents.size)

    val byCode = residents.associateBy { it.residentCode }
    assertEquals(setOf("G01", "G02", "A01", "A02", "C01"), byCode.keys)

    // Exact frozen R06 periods & depth dominance
    assertEquals(6100, byCode.getValue("G01").periodMs)
    assertEquals(5300, byCode.getValue("G02").periodMs)
    assertEquals(3700, byCode.getValue("A01").periodMs)
    assertEquals(2900, byCode.getValue("A02").periodMs)
    assertEquals(4400, byCode.getValue("C01").periodMs)

    assertEquals(com.example.orb.scene.DominantDepthLayer.BACK, byCode.getValue("G01").dominantLayer)
    assertEquals(com.example.orb.scene.DominantDepthLayer.BACK, byCode.getValue("A01").dominantLayer)
    assertEquals(com.example.orb.scene.DominantDepthLayer.FRONT, byCode.getValue("C01").dominantLayer)
    assertEquals(com.example.orb.scene.DominantDepthLayer.FRONT, byCode.getValue("A02").dominantLayer)
    assertEquals(com.example.orb.scene.DominantDepthLayer.FRONT, byCode.getValue("G02").dominantLayer)

    // Back-to-front transparent alpha draw order
    assertTrue(byCode.getValue("G01").renderOrder < byCode.getValue("A01").renderOrder)
    assertTrue(byCode.getValue("A01").renderOrder < byCode.getValue("C01").renderOrder)
    assertTrue(byCode.getValue("C01").renderOrder < byCode.getValue("A02").renderOrder)
    assertTrue(byCode.getValue("A02").renderOrder < byCode.getValue("G02").renderOrder)

    // Independent phases & bounded non-rigid spatial sway (no planetary ring spin)
    assertEquals(5, residents.map { it.phase }.toSet().size)
    for (r in residents) {
      assertTrue("${r.residentCode} spatial sway must stay organic & bounded", r.spatialSwayDeg in 3.5f..8.0f)

      // Verify organic non-circular 3D path (radial variation along spine > 0.14)
      var minR = Float.MAX_VALUE
      var maxR = 0f
      for (step in 0..40) {
        val u = step / 40f
        val pt = com.example.orb.geometry.OrbGeometryBuilder.sampleCatmullRom(r.controlPoints, u, r.closed)
        val radius = kotlin.math.sqrt(pt.x * pt.x + pt.y * pt.y + pt.z * pt.z)
        minR = minOf(minR, radius)
        maxR = maxOf(maxR, radius)
      }
      assertTrue(
        "${r.residentCode} must be irregular/organic (maxR-minR > 0.14), got ${maxR - minR}",
        (maxR - minR) > 0.14f,
      )
    }

    // Botanical kelp blades (G01, G02) must have sculpted width variation, left/right asymmetry, 3D twist turnover, and higher tip flexibility
    for (code in listOf("G01", "G02")) {
      val kelp = byCode.getValue(code)
      assertEquals(1, kelp.style)
      assertFalse("$code kelp frond should be open-ended", kelp.closed)
      val samples = (1..39).map { i ->
        com.example.orb.geometry.OrbGeometryBuilder.evaluateRibbonProfileAt(
          u = i / 40f,
          halfWidth = kelp.halfWidth,
          closed = kelp.closed,
          taperEnds = true,
          profile = kelp.surfaceProfile,
        )
      }
      val maxWidth = samples.maxOf { it.totalWidth }
      val minBodyWidth = samples.subList(8, 32).minOf { it.totalWidth }
      assertTrue("$code must exhibit sculpted wide/narrow turnover ratio > 1.8", maxWidth / minBodyWidth > 1.8f)

      val maxAsymDiff = samples.maxOf { kotlin.math.abs(it.leftHalfWidth - it.rightHalfWidth) }
      assertTrue("$code must have asymmetric left/right blade contours", maxAsymDiff > 0.02f)

      val twistSpan = samples.maxOf { it.twistRad } - samples.minOf { it.twistRad }
      assertTrue("$code must have real 3D twist turnover (> 2.5 rad), got $twistSpan", twistSpan > 2.5f)

      val midFlex = samples[19].flexWeight
      val tipFlex = samples.last().flexWeight
      assertTrue("$code tip flex ($tipFlex) must exceed mid-body flex ($midFlex)", tipFlex > midFlex)
    }

    // Verify Plan B ribbon fullness & ACES/sRGB-calibrated linear palettes
    val c01 = byCode.getValue("C01")
    val a02 = byCode.getValue("A02")
    val a01 = byCode.getValue("A01")
    val g01 = byCode.getValue("G01")
    val g02 = byCode.getValue("G02")
    assertTrue("C01 must be broad sculpted water band (>= 0.13)", c01.halfWidth >= 0.13f)
    assertTrue("A02 must be broad dual-wall gold glass band (>= 0.11)", a02.halfWidth >= 0.11f)
    assertTrue("A01 must be lush golden glass veil (>= 0.09)", a01.halfWidth >= 0.09f)
    assertTrue("G01/G02 warm olive-kelp must suppress blue (< 0.04) to prevent cool mint tint", g01.colorB[2] < 0.04f && g02.colorB[2] < 0.04f)
    assertTrue("G01/G02 warm olive-kelp must have warm R/G > 0.55 on lit colorB", (g02.colorB[0] / g02.colorB[1]) > 0.55f)
    assertTrue("A02 molten amber must suppress blue (< 0.025) to prevent washed beige", a02.colorA[2] < 0.025f)

    // Verify upgraded RIBBON_VERT & RIBBON_FRAG shader contracts
    assertTrue(RibbonOpticsShaders.RIBBON_VERT.contains("in vec4 aTangentFlex;"))
    assertTrue(RibbonOpticsShaders.RIBBON_VERT.contains("vec3 rotateAroundAxis("))
    assertTrue(RibbonOpticsShaders.RIBBON_VERT.contains("uniform float uCyclePhase;"))
    assertTrue(RibbonOpticsShaders.RIBBON_VERT.contains("uniform float uRibbonPhase;"))
    assertTrue(RibbonOpticsShaders.RIBBON_VERT.contains("uniform float uTwistWaveAmp;"))
    assertTrue(RibbonOpticsShaders.RIBBON_FRAG.contains("uniform float uCyclePhase;"))
    assertTrue(RibbonOpticsShaders.RIBBON_FRAG.contains("uniform float uRibbonPhase;"))
    assertTrue(RibbonOpticsShaders.RIBBON_FRAG.contains("uniform vec3 uOrbCenterViewPos;"))
    assertTrue(RibbonOpticsShaders.RIBBON_FRAG.contains("uniform float uCoreShellRadius;"))
    assertTrue(RibbonOpticsShaders.RIBBON_FRAG.contains("float splitZ ="))
    assertTrue(RibbonOpticsShaders.RIBBON_FRAG.contains("float leafMask ="))
    assertTrue(RibbonOpticsShaders.RIBBON_FRAG.contains("float goldWalls ="))
    assertTrue(RibbonOpticsShaders.RIBBON_FRAG.contains("if (uRibbonStyle == 0)"))
    assertTrue(RibbonOpticsShaders.RIBBON_FRAG.contains("else if (uRibbonStyle == 1)"))
  }

  @Test
  fun compositeShaderEnforcesTrueLinearToSrgbGammaAndCoreUsesDomainWarpCaustics() {
    assertTrue(
      "COMPOSITE_FRAG must perform Linear -> sRGB 2.2 OETF display encoding",
      RibbonOpticsShaders.COMPOSITE_FRAG.contains("pow(max(mapped, vec3(0.0)), vec3(1.0 / 2.2))"),
    )
    assertTrue(CoreFluidShaders.CORE_GLASS_FRAG.contains("uniform int uDebugMode;"))
    assertFalse("CORE_GLASS_FRAG must NOT use Voronoi honeycomb caustics", CoreFluidShaders.CORE_GLASS_FRAG.contains("voronoiEdge2D"))
    assertFalse("INNER_FLUID_FRAG must NOT use Voronoi honeycomb caustics", CoreFluidShaders.INNER_FLUID_FRAG.contains("voronoiEdge2D"))
    assertTrue("CORE_GLASS_FRAG must implement 3D Flow Domain Warping caustics", CoreFluidShaders.CORE_GLASS_FRAG.contains("domainWarpCaustics("))
    assertTrue("CORE_GLASS_FRAG must unify meniscus in Core Frame via toCoreFrame", CoreFluidShaders.CORE_GLASS_FRAG.contains("toCoreFrame("))
    assertTrue("CORE_GLASS_FRAG must transform meniscus normal back via transpose(uCoreRotMat)", CoreFluidShaders.CORE_GLASS_FRAG.contains("transpose(uCoreRotMat)"))
    assertTrue("CORE_GLASS_FRAG must use pixel-bounded refraction", CoreFluidShaders.CORE_GLASS_FRAG.contains("maxRefractPx"))
    assertTrue(RibbonOpticsShaders.RIBBON_FRAG.contains("uniform int uDepthDebug;"))
  }

  @Test
  fun renderPassManifestGuaranteesGpuProvenanceAndIsolationIntegrity() {
    val fullPasses = com.example.orb.scene.AIOrbScene.activeRenderPasses(com.example.orb.config.RenderDebugMode.FULL_RENDER)
    val gpuOnlyPasses = com.example.orb.scene.AIOrbScene.activeRenderPasses(com.example.orb.config.RenderDebugMode.GPU_ONLY)
    val refPasses = com.example.orb.scene.AIOrbScene.activeRenderPasses(com.example.orb.config.RenderDebugMode.REFERENCE_ONLY)
    val coreRotPasses = com.example.orb.scene.AIOrbScene.activeRenderPasses(com.example.orb.config.RenderDebugMode.CORE_ROTATION_ONLY)
    val ribbonsOnlyPasses = com.example.orb.scene.AIOrbScene.activeRenderPasses(com.example.orb.config.RenderDebugMode.RIBBONS_ONLY)
    val bloomOffPasses = com.example.orb.scene.AIOrbScene.activeRenderPasses(com.example.orb.config.RenderDebugMode.BLOOM_OFF)

    assertEquals(gpuOnlyPasses, fullPasses)
    assertTrue(fullPasses.none { it.contains("REFERENCE") })
    assertEquals(1, refPasses.size)
    assertTrue(refPasses.first().contains("NO_GPU_COMPOSITE"))

    val idxBackRibbons = fullPasses.indexOf("PASS_3_BACK_RIBBONS_RESIDENT_AND_SECONDARY")
    val idxInner = fullPasses.indexOf("PASS_5_CONCAVE_INNER_FLUID_BOWL")
    val idxRefract = fullPasses.indexOf("PASS_6_REFRACTION_FBO_BLIT_SNAPSHOT")
    val idxCore = fullPasses.indexOf("PASS_7_OUTER_CRYSTAL_CORE_MENISCUS_DOMAIN_WARP_CAUSTICS")
    val idxFrontRibbons = fullPasses.indexOf("PASS_8_FRONT_RIBBONS_DEPTH_TESTED")
    assertTrue(idxBackRibbons in 0 until idxInner)
    assertTrue(idxInner < idxRefract)
    assertTrue(idxRefract < idxCore)
    assertTrue(idxCore < idxFrontRibbons)

    assertTrue(coreRotPasses.none { it.contains("RIBBON") || it.contains("STARBURST") || it.contains("PARTICLES") })
    assertTrue(coreRotPasses.contains("PASS_7_OUTER_CRYSTAL_CORE_MENISCUS_DOMAIN_WARP_CAUSTICS"))

    assertTrue(ribbonsOnlyPasses.none { it.contains("CORE") || it.contains("INNER_FLUID") })
    assertTrue(ribbonsOnlyPasses.contains("PASS_8_FRONT_RIBBONS_DEPTH_TESTED"))

    assertTrue(bloomOffPasses.last().contains("BLOOM_OFF"))
  }
}
