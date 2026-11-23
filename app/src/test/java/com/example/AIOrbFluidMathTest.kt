package com.example

import com.example.orb.config.AIOrbConfig
import com.example.orb.config.QualityTier
import com.example.orb.geometry.Vec3
import com.example.orb.scene.AIOrbFluidMath
import com.example.orb.scene.AIOrbMotionController
import com.example.orb.v3.MasterFidelityV32
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIOrbFluidMathTest {

  private fun isFiniteVec3(v: Vec3): Boolean = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

  @Test
  fun advectFluidPosProducesContinuousDirectional3DRotation() {
    val controller = AIOrbMotionController()
    val cfg = AIOrbConfig.Default
    val samplePoint = Vec3(0.55f, -0.18f, 0.25f)

    var prevAdv = AIOrbFluidMath.advectFluidPos(
      samplePoint,
      controller.step(0.0, cfg).coreRotationMat3,
      0f,
    )
    assertTrue(isFiniteVec3(prevAdv))

    val positionsAtKeyframes = mutableListOf<Vec3>()
    val keyFrames = setOf(180, 360, 600, 1200) // 3s, 6s, 10s, 20s

    var totalUnwrappedAzimuth = 0.0
    var prevAzimuth = atan2(prevAdv.z.toDouble(), prevAdv.x.toDouble())

    for (frame in 1..1200) {
      val snap = controller.step(1.0 / 60.0, cfg)
      val currAdv = AIOrbFluidMath.advectFluidPos(samplePoint, snap.coreRotationMat3, snap.fluidPhase)
      assertTrue("Advected pos must be finite at frame=$frame", isFiniteVec3(currAdv))

      // Lipschitz temporal continuity check (no jumps between adjacent 60fps frames)
      val stepDist = (currAdv - prevAdv).length()
      assertTrue("Unexpected advection jump $stepDist at frame=$frame", stepDist < 0.05f)

      val az = atan2(currAdv.z.toDouble(), currAdv.x.toDouble())
      var dAz = az - prevAzimuth
      while (dAz > PI) dAz -= 2.0 * PI
      while (dAz < -PI) dAz += 2.0 * PI
      totalUnwrappedAzimuth += dAz

      prevAzimuth = az
      prevAdv = currAdv
      if (frame in keyFrames) positionsAtKeyframes.add(currAdv)
    }

    // Over 20s (~520 deg of tilted core rotation), unwrapped azimuthal travel must clearly exceed 2*PI rad
    assertTrue(
      "Expected unmistakable directional azimuthal advection > 6.0 rad over 20s, got $totalUnwrappedAzimuth",
      abs(totalUnwrappedAzimuth) > 6.0,
    )
    assertEquals(4, positionsAtKeyframes.size)
  }

  @Test
  fun meniscusBisectionSolverIsAccurateForBothDownwardAndUpwardCrossings() {
    val phases = listOf(0.0f, 1.4f, 3.2f, 5.7f, 9.1f)
    val stepCounts = QualityTier.entries.map { it.raymarchSteps } // 12, 16, 20

    for (steps in stepCounts) {
      for (phase in phases) {
        // 1. Downward crossing: ray enters upper air cavity and descends into lower liquid
        val downOrg = Vec3(-0.18f, 0.72f, 0.42f)
        val downDir = Vec3(0.14f, -0.82f, -0.55f).normalized()
        val downHit = AIOrbFluidMath.findMeniscusIntersection(downOrg, downDir, tExit = 1.65f, steps = steps, meniscusPhase = phase)
        assertTrue("Expected downward meniscus hit (steps=$steps, phase=$phase)", downHit.hit)
        assertTrue("Expected downward crossing flag", downHit.crossingDownward)
        assertTrue(isFiniteVec3(downHit.hitPos))
        assertTrue(
          "Downward bisection residual too large: ${downHit.residualError} (steps=$steps, phase=$phase)",
          downHit.residualError < 0.004f,
        )

        // 2. Upward crossing: ray enters lower water belly and exits upward into upper glass cavity
        val upOrg = Vec3(0.12f, -0.68f, 0.46f)
        val upDir = Vec3(-0.08f, 0.84f, -0.53f).normalized()
        val upHit = AIOrbFluidMath.findMeniscusIntersection(upOrg, upDir, tExit = 1.65f, steps = steps, meniscusPhase = phase)
        assertTrue("Expected upward meniscus hit (steps=$steps, phase=$phase)", upHit.hit)
        assertFalse("Expected upward crossing flag (crossingDownward == false)", upHit.crossingDownward)
        assertTrue(isFiniteVec3(upHit.hitPos))
        assertTrue(
          "Upward bisection residual too large: ${upHit.residualError} (steps=$steps, phase=$phase)",
          upHit.residualError < 0.004f,
        )
      }
    }
  }

  @Test
  fun meniscusSolverHandlesGrazingAndNoHitRaysWithoutNan() {
    // High-altitude horizontal ray strictly above meniscus -> no hit, finite output
    val highOrg = Vec3(-0.4f, 0.85f, 0.1f)
    val highDir = Vec3(1f, 0f, 0f)
    val highRes = AIOrbFluidMath.findMeniscusIntersection(highOrg, highDir, tExit = 0.8f, steps = 16, meniscusPhase = 2.5f)
    assertFalse(highRes.hit)
    assertTrue(highRes.residualError.isFinite())

    // Deep-submerged ray strictly below meniscus -> no hit, finite output
    val deepOrg = Vec3(-0.3f, -0.78f, 0.2f)
    val deepDir = Vec3(0.9f, -0.1f, -0.42f).normalized()
    val deepRes = AIOrbFluidMath.findMeniscusIntersection(deepOrg, deepDir, tExit = 0.8f, steps = 12, meniscusPhase = 4.0f)
    assertFalse(deepRes.hit)
    assertTrue(deepRes.residualError.isFinite())
  }

  @Test
  fun invSmoothstepIsStrictlyMonotonicAndEnforcesOrderedBounds() {
    assertEquals(1.0f, AIOrbFluidMath.invSmoothstep(0.2f, 0.8f, 0.1f), 1e-6f)
    assertEquals(0.5f, AIOrbFluidMath.invSmoothstep(0.2f, 0.8f, 0.5f), 1e-6f)
    assertEquals(0.0f, AIOrbFluidMath.invSmoothstep(0.2f, 0.8f, 0.9f), 1e-6f)

    var prev = 1.1f
    for (i in 0..20) {
      val x = i / 20f
      val v = AIOrbFluidMath.invSmoothstep(0.15f, 0.85f, x)
      assertTrue(v <= prev + 1e-6f)
      prev = v
    }

    var threw = false
    try {
      AIOrbFluidMath.smoothstepSafe(0.8f, 0.2f, 0.5f)
    } catch (_: IllegalArgumentException) {
      threw = true
    }
    assertTrue("smoothstepSafe must reject inverted edges", threw)

    assertEquals(0f, AIOrbFluidMath.smoothstepSafe(0.2f, 0.8f, Float.NaN), 0f)
    assertEquals(0f, AIOrbFluidMath.smoothstepSafe(0.2f, 0.8f, Float.POSITIVE_INFINITY), 0f)
    assertEquals(1f, AIOrbFluidMath.inverseSmoothstep(0.2f, 0.8f, Float.NaN), 0f)
  }

  @Test
  fun beerLambertTransmittancePreservesAquaticSpectralHierarchy() {
    val shallow = AIOrbFluidMath.beerLambertTransmittance(liquidPath = 0.25f, transparency = 0.88f)
    val deep = AIOrbFluidMath.beerLambertTransmittance(liquidPath = 1.40f, transparency = 0.88f)

    // Longer water path must absorb more light across all channels
    assertTrue(deep.x < shallow.x)
    assertTrue(deep.y < shallow.y)
    assertTrue(deep.z < shallow.z)

    // Physical water absorbs red fastest, then green, transmitting blue/cyan deepest
    assertTrue("Expected T_blue > T_green > T_red in deep water", deep.z > deep.y && deep.y > deep.x)
  }

  @Test
  fun volumeBeerLambertUsesDensityAndSegmentLengthLikeGlsl() {
    val masterDensityScale = MasterFidelityV32.Default.fluidVolume.densityScale
    val shallow = AIOrbFluidMath.volumeStepTransmittance(0.42f, masterDensityScale, 0.08f)
    val deep = AIOrbFluidMath.volumeStepTransmittance(0.42f, masterDensityScale, 0.24f)
    val denser = AIOrbFluidMath.volumeStepTransmittance(0.84f, masterDensityScale, 0.08f)
    assertTrue(shallow.isFinite() && deep.isFinite() && denser.isFinite())
    assertTrue("Longer path must transmit less", deep < shallow)
    assertTrue("Higher density must transmit less", denser < shallow)
    assertEquals(
      kotlin.math.exp(-0.42f * masterDensityScale * 0.08f),
      shallow,
      1e-6f,
    )

    val spectrum = AIOrbFluidMath.volumeStepSpectralTransmittance(
      linearDensity = 0.42f,
      densityScale = masterDensityScale,
      segmentLength = 0.08f,
      absorption = Vec3(2.6f, 0.92f, 0.42f),
    )
    assertTrue("Master absorption should attenuate red most and blue least", spectrum.z > spectrum.y && spectrum.y > spectrum.x)
    assertEquals(kotlin.math.exp(-2.6f * 0.42f * masterDensityScale * 0.08f), spectrum.x, 1e-6f)
    assertEquals(kotlin.math.exp(-0.92f * 0.42f * masterDensityScale * 0.08f), spectrum.y, 1e-6f)
    assertEquals(kotlin.math.exp(-0.42f * 0.42f * masterDensityScale * 0.08f), spectrum.z, 1e-6f)
  }

  @Test
  fun domainWarpCausticsProduceIrregularContinuousMotionWithoutNan() {
    val controller = AIOrbMotionController()
    val cfg = AIOrbConfig.Default
    val lowerSample = Vec3(0.20f, -0.46f, 0.22f)
    val upperSample = Vec3(-0.12f, 0.58f, 0.14f)

    var prevVal = -1f
    var maxStepDelta = 0f
    var minVal = Float.MAX_VALUE
    var maxVal = -Float.MAX_VALUE
    var sumLower = 0f
    var sumUpper = 0f

    for (frame in 0..360) { // 6 seconds @ 60fps
      val snap = controller.step(1.0 / 60.0, cfg)
      val coreLower = AIOrbFluidMath.toCoreFrame(lowerSample, snap.coreRotationMat3)
      val advLower = AIOrbFluidMath.advectFluidPos(lowerSample, snap.coreRotationMat3, snap.fluidPhase)
      val cVal = AIOrbFluidMath.domainWarpCaustics(advLower, coreLower, 0.08f, -0.05f, snap.causticsPhase)

      val coreUpper = AIOrbFluidMath.toCoreFrame(upperSample, snap.coreRotationMat3)
      val advUpper = AIOrbFluidMath.advectFluidPos(upperSample, snap.coreRotationMat3, snap.fluidPhase)
      val cUpper = AIOrbFluidMath.domainWarpCaustics(advUpper, coreUpper, 0.08f, -0.05f, snap.causticsPhase)

      assertTrue("Domain-warp caustic must be finite at frame=$frame", cVal.isFinite())
      assertTrue("Domain-warp caustic must be in [0,2], got $cVal", cVal in 0f..2.0001f)
      if (prevVal >= 0f) {
        maxStepDelta = maxOf(maxStepDelta, abs(cVal - prevVal))
      }
      minVal = minOf(minVal, cVal)
      maxVal = maxOf(maxVal, cVal)
      sumLower += cVal
      sumUpper += cUpper
      prevVal = cVal
    }

    assertTrue("Expected dynamic Domain-Warp caustic variation > 0.15, got ${maxVal - minVal}", (maxVal - minVal) > 0.15f)
    assertTrue("Expected smooth frame-to-frame caustic evolution (< 0.25/frame), got $maxStepDelta", maxStepDelta < 0.25f)
    assertTrue("Caustics must concentrate in lower interior water rather than upper dome", sumLower > sumUpper * 2.5f)
  }

  @Test
  fun rotatedCoreFrameMeniscusTiltsAndBisectsAccurately() {
    val controller = AIOrbMotionController()
    val cfg = AIOrbConfig.Default
    val downOrg = Vec3(-0.18f, 0.72f, 0.42f)
    val downDir = Vec3(0.14f, -0.82f, -0.55f).normalized()
    val upOrg = Vec3(0.12f, -0.68f, 0.46f)
    val upDir = Vec3(-0.08f, 0.84f, -0.53f).normalized()

    val hitHeightsY = mutableListOf<Float>()
    val localNormals = mutableListOf<Vec3>()

    for (sec in 0..5) {
      if (sec > 0) repeat(60) { controller.step(1.0 / 60.0, cfg) }
      val snap = controller.step(0.0, cfg)

      val downHit = AIOrbFluidMath.findMeniscusIntersection(
        rayOrg = downOrg,
        rayDir = downDir,
        tExit = 1.65f,
        steps = 16,
        meniscusPhase = snap.meniscusPhase,
        coreRotMat3 = snap.coreRotationMat3,
      )
      assertTrue("Expected downward Core-Frame meniscus hit at t=${sec}s", downHit.hit)
      assertTrue("Downward residual must stay < 0.004, got ${downHit.residualError}", downHit.residualError < 0.004f)
      assertTrue(isFiniteVec3(downHit.localNormal))
      assertEquals("Inverse-rotated meniscus normal must be unit length", 1.0f, downHit.localNormal.length(), 1e-3f)
      hitHeightsY.add(downHit.hitPos.y)
      localNormals.add(downHit.localNormal)

      val upHit = AIOrbFluidMath.findMeniscusIntersection(
        rayOrg = upOrg,
        rayDir = upDir,
        tExit = 1.65f,
        steps = 16,
        meniscusPhase = snap.meniscusPhase,
        coreRotMat3 = snap.coreRotationMat3,
      )
      assertTrue("Expected upward Core-Frame meniscus hit at t=${sec}s", upHit.hit)
      assertFalse(upHit.crossingDownward)
      assertTrue("Upward residual must stay < 0.004, got ${upHit.residualError}", upHit.residualError < 0.004f)
    }

    // As uCoreRotMat rotates around the tilted 3D axis, the meniscus intersection height & normal must visibly change
    val ySpan = (hitHeightsY.maxOrNull() ?: 0f) - (hitHeightsY.minOrNull() ?: 0f)
    assertTrue("Rotated Core-Frame meniscus must tilt & shift visibly over 5s (span > 0.08), got $ySpan", ySpan > 0.08f)
    val normalDelta = (localNormals.last() - localNormals.first()).length()
    assertTrue("Inverse-rotated meniscus normal must rotate with core (delta > 0.15), got $normalDelta", normalDelta > 0.15f)
  }
}
