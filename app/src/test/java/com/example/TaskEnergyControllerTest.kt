package com.example

import com.example.orb.task.PerimeterPathGeometry
import com.example.orb.task.PhotoFrameGeometry
import com.example.orb.task.TaskEnergyController
import com.example.orb.task.TaskEnergyEvent
import com.example.orb.task.TaskEnergyStage
import com.example.orb.task.TaskWaterLightShaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * G3 & G4 State-Machine, Frame-Rate Stability, Event Ordering & GLSL ES 3.00 Contract Suite.
 */
class TaskEnergyControllerTest {

  private val photo = PhotoFrameGeometry.canonicalForViewport(1080, 2340)
  private val perimeter = PerimeterPathGeometry(photo)

  @Test
  fun allSixCanonicalKeyframesMatchFrozenVisualTimelineAndGeometricBounds() {
    val controller = TaskEnergyController()

    // KF01 ORB_RELEASE: stream rising from Orb, strictly NO bottom contact yet
    val kf01 = controller.jumpToKeyframe(TaskEnergyStage.ORB_RELEASE, perimeter)
    assertEquals(TaskEnergyStage.ORB_RELEASE, kf01.stage)
    assertTrue("KF01 release progress should be mid-flight (${kf01.releaseProgress})", kf01.releaseProgress in 0.25f..0.60f)
    assertFalse("KF01 must NOT touch photo bottom yet", kf01.contactReached)
    assertEquals(0f, kf01.leftProgress, 1e-5f)
    assertEquals(0f, kf01.rightProgress, 1e-5f)
    assertEquals(0f, kf01.joinIntensity, 1e-5f)

    // KF02 BOTTOM_CONTACT: stream hits bottom center, localized peak contact glint, strictly before corners
    val kf02 = controller.jumpToKeyframe(TaskEnergyStage.BOTTOM_CONTACT, perimeter)
    assertEquals(TaskEnergyStage.BOTTOM_CONTACT, kf02.stage)
    assertTrue(kf02.contactReached)
    assertTrue("KF02 contact intensity must peak (${kf02.contactIntensity})", kf02.contactIntensity > 0.70f)
    assertTrue("KF02 spread must stay local near bottom center", kf02.leftProgress < perimeter.bottomCornerStartFrac * 0.4f)
    assertEquals(0f, kf02.joinIntensity, 1e-5f)

    // KF03 BOTTOM_SPLIT: expands along bottom straight approaching corners, strictly NOT entering vertical sides!
    val kf03 = controller.jumpToKeyframe(TaskEnergyStage.BOTTOM_SPLIT, perimeter)
    assertEquals(TaskEnergyStage.BOTTOM_SPLIT, kf03.stage)
    assertTrue("KF03 must expand well past contact point (${kf03.leftProgress})", kf03.leftProgress > 0.08f)
    assertTrue(
      "KF03 must NOT enter vertical sides yet (${kf03.leftProgress} < ${perimeter.bottomCornerStartFrac})",
      kf03.leftProgress < perimeter.bottomCornerStartFrac,
    )
    assertEquals(kf03.leftProgress, kf03.rightProgress, 1e-5f)

    // KF04 SIDE_ASCEND: rounds bottom corners and climbs ~50%-75% of vertical sides; top edge strictly unlit!
    val kf04 = controller.jumpToKeyframe(TaskEnergyStage.SIDE_ASCEND, perimeter)
    assertEquals(TaskEnergyStage.SIDE_ASCEND, kf04.stage)
    assertTrue("KF04 must pass bottom corner end", kf04.leftProgress > perimeter.bottomCornerEndFrac)
    assertTrue("KF04 must stay strictly below top corner start", kf04.leftProgress < perimeter.topCornerStartFrac)
    val sideSpan = perimeter.topCornerStartFrac - perimeter.bottomCornerEndFrac
    val sideFraction = (kf04.leftProgress - perimeter.bottomCornerEndFrac) / sideSpan
    assertTrue("KF04 should sit around 45%-78% of vertical side height ($sideFraction)", sideFraction in 0.45f..0.78f)
    assertEquals(0f, kf04.joinIntensity, 1e-5f)
    assertFalse(kf04.coverageComplete)

    // KF05 TOP_JOIN: converged at top center with warm peak join glint & full coverage
    val kf05 = controller.jumpToKeyframe(TaskEnergyStage.TOP_JOIN, perimeter)
    assertEquals(TaskEnergyStage.TOP_JOIN, kf05.stage)
    assertTrue("KF05 must reach complete 4-side coverage", kf05.coverageComplete)
    assertEquals(1f, kf05.leftProgress, 1e-4f)
    assertEquals(1f, kf05.rightProgress, 1e-4f)
    assertTrue("KF05 join glint must peak prominently (${kf05.joinIntensity})", kf05.joinIntensity > 0.70f)

    // KF06 PERIMETER_LOOP: steady continuous loop, 100% coverage held, top join clearly softened vs KF05!
    val kf06 = controller.jumpToKeyframe(TaskEnergyStage.PERIMETER_LOOP, perimeter)
    assertEquals(TaskEnergyStage.PERIMETER_LOOP, kf06.stage)
    assertTrue(kf06.coverageComplete)
    assertEquals(1f, kf06.leftProgress, 1e-4f)
    assertTrue("KF06 join intensity (${kf06.joinIntensity}) must be clearly softer than KF05 (${kf05.joinIntensity})", kf06.joinIntensity < kf05.joinIntensity * 0.50f)
  }

  @Test
  fun continuousPlaybackEmitsAll11EventsInOrderNeverRegressesAndLoopsCleanlyOver30Seconds() {
    val controller = TaskEnergyController()
    controller.restart(unpause = true)

    var prevRelease = 0f
    var prevLeft = 0f
    var prevRight = 0f
    var prevFlow = 0f

    // Simulate 38 seconds at 60 FPS
    val dt = 1.0 / 60.0
    var lastSnap = controller.snapshot()
    for (frame in 1..(38 * 60)) {
      val snap = controller.step(dt, perimeter)
      assertTrue("releaseProgress must NEVER regress", snap.releaseProgress >= prevRelease - 1e-6f)
      assertTrue("leftProgress must NEVER regress", snap.leftProgress >= prevLeft - 1e-6f)
      assertTrue("rightProgress must NEVER regress", snap.rightProgress >= prevRight - 1e-6f)
      assertTrue("flowTimeSec must strictly advance while unpaused", snap.flowTimeSec >= prevFlow)

      if (snap.elapsedMs >= 5600L) {
        assertEquals("After 5.5s must remain permanently in PERIMETER_LOOP (never restart KF01)", TaskEnergyStage.PERIMETER_LOOP, snap.stage)
        assertTrue("Coverage must remain 100% in PERIMETER_LOOP", snap.coverageComplete)
      }

      prevRelease = snap.releaseProgress
      prevLeft = snap.leftProgress
      prevRight = snap.rightProgress
      prevFlow = snap.flowTimeSec
      lastSnap = snap
    }

    assertTrue("flowTimeSec must advance continuously past 30s (${lastSnap.flowTimeSec})", lastSnap.flowTimeSec > 35f)
    assertEquals("All 11 milestone events must fire in exact order", TaskEnergyEvent.entries, controller.events)
  }

  @Test
  fun pauseResumeHasZeroJumpAnd20FpsMatches60FpsWithinTolerance() {
    val c20 = TaskEnergyController().apply { restart(true) }
    val c60 = TaskEnergyController().apply { restart(true) }

    // Advance both to exactly 3.60s (20 FPS vs 60 FPS)
    repeat(72) { c20.step(1.0 / 20.0, perimeter) }
    repeat(216) { c60.step(1.0 / 60.0, perimeter) }

    val s20 = c20.snapshot()
    val s60 = c60.snapshot()
    assertEquals(s20.stage, s60.stage)
    assertEquals("20 FPS vs 60 FPS leftProgress drift < 1e-3", s60.leftProgress, s20.leftProgress, 1e-3f)
    assertEquals("20 FPS vs 60 FPS rightProgress drift < 1e-3", s60.rightProgress, s20.rightProgress, 1e-3f)
    assertEquals("20 FPS vs 60 FPS contactIntensity drift < 1e-3", s60.contactIntensity, s20.contactIntensity, 1e-3f)

    // Verify Pause holds state identically with ZERO progress or phase jump
    c60.setPaused(true)
    val beforePause = c60.snapshot()
    repeat(120) { c60.step(1.0 / 60.0, perimeter) }
    val duringPause = c60.snapshot()
    assertEquals(beforePause.elapsedMs, duringPause.elapsedMs)
    assertEquals(beforePause.leftProgress, duringPause.leftProgress, 0f)
    assertEquals(beforePause.flowTimeSec, duringPause.flowTimeSec, 0f)

    // Resume advances smoothly by exactly 1 frame
    c60.setPaused(false)
    val afterResume = c60.step(1.0 / 60.0, perimeter)
    assertTrue("Resume must advance smoothly without jump", abs(afterResume.flowTimeSec - beforePause.flowTimeSec - (1f / 60f)) < 1e-3f)
  }

  @Test
  fun allFiveTaskWaterLightShadersSatisfyGlslEs300ContractsAndHaveZeroInvertedSmoothsteps() {
    val shaders = listOf(
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
    )

    val literalSmoothstepRegex = Regex("""smoothstep\(\s*([+-]?\d+(?:\.\d+)?)\s*,\s*([+-]?\d+(?:\.\d+)?)\s*,""")
    for ((name, src) in shaders) {
      assertTrue("$name must declare #version 300 es", src.trimStart().startsWith("#version 300 es"))
      assertTrue("$name must declare precision highp float", src.contains("precision highp float;"))
      assertTrue(
        "$name must declare precision highp int so shared uniform int precisions match across VERT/FRAG",
        src.contains("precision highp int;"),
      )
      assertTrue("$name must contain void main()", src.contains("void main()"))
      if (name.endsWith("_FRAG")) {
        assertTrue("$name must output fragColor", src.contains("out vec4 fragColor;"))
      }
      for (match in literalSmoothstepRegex.findAll(src)) {
        val edge0 = match.groupValues[1].toFloat()
        val edge1 = match.groupValues[2].toFloat()
        assertTrue(
          "$name must NEVER use inverted smoothstep($edge0, $edge1, ...) (undefined in GLSL ES 3.00!)",
          edge0 < edge1,
        )
      }
    }
  }
}
