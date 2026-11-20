package com.example

import com.example.orb.config.AIOrbConfig
import com.example.orb.geometry.Vec3
import com.example.orb.scene.AIOrbMotionController
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AIOrbMotionTest {

  @Test
  fun coreRotationAxisIsNormalizedAndMatrixIsProperOrthogonal() {
    val axis = AIOrbMotionController.CORE_ROTATION_AXIS
    val len = sqrt(axis.x * axis.x + axis.y * axis.y + axis.z * axis.z)
    assertEquals(1.0f, len, 1e-5f)

    val controller = AIOrbMotionController()
    val config = AIOrbConfig.Default
    val checkpoints = listOf(0, 180, 360, 600, 1200) // 0s, 3s, 6s, 10s, 20s @ 60fps
    var frame = 0
    for (targetFrame in checkpoints) {
      while (frame < targetFrame) {
        controller.step(1.0 / 60.0, config)
        frame++
      }
      val snap = controller.step(0.0, config)
      val m = snap.coreRotationMat3
      assertEquals(9, m.size)

      // Check R^T * R == I
      for (colA in 0..2) {
        for (colB in 0..2) {
          val dot =
            m[colA * 3 + 0] * m[colB * 3 + 0] +
              m[colA * 3 + 1] * m[colB * 3 + 1] +
              m[colA * 3 + 2] * m[colB * 3 + 2]
          val expected = if (colA == colB) 1f else 0f
          assertEquals("R^T*R mismatch at ($colA,$colB) frame=$frame", expected, dot, 1e-4f)
        }
      }

      // Determinant must be +1 (proper 3D rotation, no reflection)
      val det =
        m[0] * (m[4] * m[8] - m[7] * m[5]) -
          m[3] * (m[1] * m[8] - m[7] * m[2]) +
          m[6] * (m[1] * m[5] - m[4] * m[2])
      assertEquals("Determinant must be +1 at frame=$frame", 1.0f, det, 1e-4f)

      // Axis itself must remain invariant under R: R * axis == axis
      val rotatedAxis = AIOrbMotionController.mulMat3Vec3(m, axis)
      assertEquals(axis.x, rotatedAxis.x, 1e-4f)
      assertEquals(axis.y, rotatedAxis.y, 1e-4f)
      assertEquals(axis.z, rotatedAxis.z, 1e-4f)
    }
  }

  @Test
  fun outerGlassModelAndAnalyticalInverseMultiplyToIdentity() {
    val controller = AIOrbMotionController()
    val config = AIOrbConfig.Default.copy(coreScale = 1.18f)
    val model = FloatArray(16)
    val inv = FloatArray(16)

    repeat(300) { idx ->
      val snap = controller.step(1.0 / 60.0, config)
      if (idx % 30 == 0) {
        snap.writeOuterGlassModelMatrix(model)
        snap.writeInvOuterGlassModelMatrix(inv)

        // Column-major 4x4 matrix multiply: P = model * inv
        for (row in 0..3) {
          for (col in 0..3) {
            var sum = 0f
            for (k in 0..3) {
              sum += model[k * 4 + row] * inv[col * 4 + k]
            }
            val expected = if (row == col) 1f else 0f
            assertEquals("M*M^-1 mismatch at ($row,$col) step=$idx", expected, sum, 1e-4f)
          }
        }
      }
    }
  }

  @Test
  fun pauseFreezesAllPhasesAndResumeContinuesWithoutJump() {
    val controller = AIOrbMotionController()
    val runningCfg = AIOrbConfig.Default
    val pausedCfg = runningCfg.copy(isPaused = true)

    repeat(120) { controller.step(1.0 / 60.0, runningCfg) }
    val beforePause = controller.step(0.0, runningCfg)

    // Advance 3 seconds of wall time while paused
    var duringPause = beforePause
    repeat(180) {
      duringPause = controller.step(1.0 / 60.0, pausedCfg)
    }

    assertEquals(beforePause.simTimeSec, duringPause.simTimeSec, 1e-9)
    assertEquals(beforePause.coreRotationAngleDeg, duringPause.coreRotationAngleDeg, 1e-5f)
    assertEquals(beforePause.fluidPhase, duringPause.fluidPhase, 1e-5f)
    assertEquals(beforePause.meniscusPhase, duringPause.meniscusPhase, 1e-5f)
    assertEquals(beforePause.causticsPhase, duringPause.causticsPhase, 1e-5f)
    assertEquals(beforePause.ribbonTimeSec, duringPause.ribbonTimeSec, 1e-5f)
    assertEquals(0f, duringPause.coreAngularVelocityDegPerSec, 1e-5f)

    // Resume for one frame: delta should be small & smooth (zero jump)
    val afterResume = controller.step(1.0 / 60.0, runningCfg)
    val deltaAngle = afterResume.coreRotationAngleDeg - beforePause.coreRotationAngleDeg
    assertTrue("Expected smooth positive rotation on resume, got $deltaAngle", deltaAngle in 0.25f..0.70f)
    assertTrue("Expected positive angular velocity on resume", afterResume.coreAngularVelocityDegPerSec > 18f)
  }

  @Test
  fun trajectoryIsConsistentAcross60FpsAnd20Fps() {
    val ctrl60 = AIOrbMotionController()
    val ctrl20 = AIOrbMotionController()
    val cfg = AIOrbConfig.Default

    // Simulate exactly 10.0 seconds at 60 FPS (600 steps) vs 20 FPS (200 steps)
    var snap60 = ctrl60.step(0.0, cfg)
    repeat(600) { snap60 = ctrl60.step(1.0 / 60.0, cfg) }

    var snap20 = ctrl20.step(0.0, cfg)
    repeat(200) { snap20 = ctrl20.step(1.0 / 20.0, cfg) }

    assertEquals(10.0, snap60.simTimeSec, 1e-4)
    assertEquals(10.0, snap20.simTimeSec, 1e-4)
    assertEquals(
      "Midpoint integration must keep 60fps & 20fps core rotation angles consistent",
      snap60.coreRotationAngleDeg,
      snap20.coreRotationAngleDeg,
      0.5f,
    )
    assertEquals(snap60.fluidPhase, snap20.fluidPhase, 1e-3f)
    assertEquals(snap60.ribbonTimeSec, snap20.ribbonTimeSec, 1e-3f)
  }

  @Test
  fun innerCoreRotatesUnmistakablyWhileOuterShellStaysOpticallyCalm() {
    val controller = AIOrbMotionController()
    val cfg = AIOrbConfig.Default

    var maxOuterYaw = 0f
    var maxOuterPitch = 0f
    var maxOuterRoll = 0f
    var prevAngle = -1f
    var snapAt5s = controller.step(0.0, cfg)
    var snapAt20s = snapAt5s

    for (frame in 1..1200) {
      val snap = controller.step(1.0 / 60.0, cfg)
      maxOuterYaw = maxOf(maxOuterYaw, abs(snap.outerGlassYawDeg))
      maxOuterPitch = maxOf(maxOuterPitch, abs(snap.outerGlassPitchDeg))
      maxOuterRoll = maxOf(maxOuterRoll, abs(snap.outerGlassRollDeg))
      assertTrue("Core angle must increase monotonically", snap.coreRotationAngleDeg > prevAngle)
      prevAngle = snap.coreRotationAngleDeg
      if (frame == 300) snapAt5s = snap
      if (frame == 1200) snapAt20s = snap
    }

    assertTrue("Outer shell yaw must stay subtle (<= 3.5 deg), got $maxOuterYaw", maxOuterYaw <= 3.5f)
    assertTrue("Outer shell pitch must stay subtle (<= 3.0 deg), got $maxOuterPitch", maxOuterPitch <= 3.0f)
    assertTrue("Outer shell roll must stay subtle (<= 2.5 deg), got $maxOuterRoll", maxOuterRoll <= 2.5f)
    assertTrue("Inner core must rotate >= 115 deg in 5s, got ${snapAt5s.coreRotationAngleDeg}", snapAt5s.coreRotationAngleDeg >= 115f)
    assertTrue("Inner core must complete > 1 full 360 deg turn in 20s, got ${snapAt20s.coreRotationAngleDeg}", snapAt20s.coreRotationAngleDeg >= 450f)
  }

  @Test
  fun isolateCoreDebugLocksOuterShellCompletelyWhileCoreRotates() {
    val controller = AIOrbMotionController()
    val cfg = AIOrbConfig.Default.copy(isolateCoreDebug = true, coreScale = 1.05f, coreShellScale = 0.80f)
    val expectedShellScale = 1.05f * 0.80f

    var lastSnap = controller.step(0.0, cfg)
    repeat(180) {
      lastSnap = controller.step(1.0 / 60.0, cfg)
      assertEquals(0f, lastSnap.outerGlassYawDeg, 1e-6f)
      assertEquals(0f, lastSnap.outerGlassPitchDeg, 1e-6f)
      assertEquals(0f, lastSnap.outerGlassRollDeg, 1e-6f)
      assertEquals(expectedShellScale, lastSnap.outerGlassScaleX, 1e-6f)
      assertEquals(expectedShellScale, lastSnap.outerGlassScaleY, 1e-6f)
      assertEquals(expectedShellScale, lastSnap.outerGlassScaleZ, 1e-6f)
    }

    assertTrue("Core must still rotate visibly in isolate mode", lastSnap.coreRotationAngleDeg > 65f)
  }

  @Test
  fun coreShellScaleIndependentlyScalesCoreAndExactInverseMatrix() {
    val c1 = AIOrbMotionController()
    val c2 = AIOrbMotionController()
    val cfgDefault = AIOrbConfig.Default.copy(coreScale = 1.0f, coreShellScale = 0.80f)
    val cfgFull = AIOrbConfig.Default.copy(coreScale = 1.0f, coreShellScale = 1.00f)

    repeat(90) {
      c1.step(1.0 / 60.0, cfgDefault)
      c2.step(1.0 / 60.0, cfgFull)
    }
    val snap80 = c1.step(0.0, cfgDefault)
    val snap100 = c2.step(0.0, cfgFull)

    assertEquals(0.80f, snap80.outerGlassScaleX / snap100.outerGlassScaleX, 1e-4f)
    assertEquals(0.80f, snap80.outerGlassScaleY / snap100.outerGlassScaleY, 1e-4f)
    assertEquals(0.80f, snap80.outerGlassScaleZ / snap100.outerGlassScaleZ, 1e-4f)

    val model = FloatArray(16)
    val inv = FloatArray(16)
    snap80.writeOuterGlassModelMatrix(model)
    snap80.writeInvOuterGlassModelMatrix(inv)

    for (row in 0..3) {
      for (col in 0..3) {
        var sum = 0f
        for (k in 0..3) {
          sum += model[k * 4 + row] * inv[col * 4 + k]
        }
        val expected = if (row == col) 1f else 0f
        assertEquals("Scaled M*M^-1 mismatch at ($row,$col)", expected, sum, 1e-4f)
      }
    }
  }

  @Test
  fun energyHighlightCenterGuardAttenuatesCoreInteriorUsingScaledRadius() {
    val scaledRadius = AIOrbConfig.Default.coreScale * AIOrbConfig.Default.coreShellScale // 0.80f
    val center = com.example.orb.scene.AIOrbEnergyHighlights.computeCenterGuard(0.0f, scaledRadius)
    val midCore = com.example.orb.scene.AIOrbEnergyHighlights.computeCenterGuard(0.50f, scaledRadius)
    val rimEdge = com.example.orb.scene.AIOrbEnergyHighlights.computeCenterGuard(0.76f, scaledRadius)
    val outerArc = com.example.orb.scene.AIOrbEnergyHighlights.computeCenterGuard(1.15f, scaledRadius)

    assertTrue("Core center flare guard must be near-zero (< 0.05), got $center", center < 0.05f)
    assertTrue("Mid-core flare guard must be near-zero (< 0.05), got $midCore", midCore < 0.05f)
    assertTrue("Rim edge must transition smoothly, got $rimEdge", rimEdge in 0.10f..0.90f)
    assertEquals("Outer orbital ribbon flares must stay full brightness", 1.0f, outerArc, 1e-4f)
  }

  @Test
  fun deltaClampingPreventsFrameHitchTeleport() {
    val controller = AIOrbMotionController()
    val cfg = AIOrbConfig.Default
    val snap = controller.step(2.5, cfg) // huge 2.5s hitch
    assertEquals(0.05, snap.simTimeSec, 1e-6)
    assertTrue("Core rotation on clamped hitch must be < 3 deg", snap.coreRotationAngleDeg < 3.0f)
  }

  @Test
  fun residentCyclePhasesAreIndependentSeamlessAndFpsInvariant() {
    val periods = listOf(6100, 5300, 3700, 2900, 4400)
    val phases = listOf(1.65f, 5.20f, 4.30f, 2.85f, 0.35f)
    val twoPi = (2.0 * kotlin.math.PI).toFloat()

    // Exact 2*PI seamless cycle advance over 1 full period for every R06 Resident
    for (i in periods.indices) {
      val periodSec = periods[i] / 1000.0f
      val p0 = AIOrbMotionController.residentCyclePhase(0.0f, periods[i], phases[i])
      val p1 = AIOrbMotionController.residentCyclePhase(periodSec, periods[i], phases[i])
      assertEquals("Period ${periods[i]}ms must advance by exact 2*PI", twoPi, p1 - p0, 1e-4f)
    }

    // Verify 60fps vs 20fps produce identical resident phases at t = 6.0s
    val ctrl60 = AIOrbMotionController()
    val ctrl20 = AIOrbMotionController()
    val cfg = AIOrbConfig.Default
    var s60 = ctrl60.step(0.0, cfg)
    repeat(360) { s60 = ctrl60.step(1.0 / 60.0, cfg) }
    var s20 = ctrl20.step(0.0, cfg)
    repeat(120) { s20 = ctrl20.step(1.0 / 20.0, cfg) }

    val wrappedAt6s = periods.indices.map { i ->
      val a60 = AIOrbMotionController.residentCyclePhase(s60.ribbonTimeSec, periods[i], phases[i])
      val a20 = AIOrbMotionController.residentCyclePhase(s20.ribbonTimeSec, periods[i], phases[i])
      assertEquals("FPS-invariant resident phase for ${periods[i]}ms", a60, a20, 1e-3f)
      ((a60 % twoPi) + twoPi) % twoPi
    }

    // All 5 Residents must sit at distinct cycle phases (non-synchronized motion)
    for (i in wrappedAt6s.indices) {
      for (j in i + 1 until wrappedAt6s.size) {
        val diff = abs(wrappedAt6s[i] - wrappedAt6s[j])
        assertTrue("Residents $i and $j must not synchronize phases", diff > 0.15f)
      }
    }
  }
}
