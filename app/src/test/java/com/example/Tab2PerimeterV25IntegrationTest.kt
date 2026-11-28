package com.example

import com.example.orb.task.PerimeterArcLengthTable
import com.example.orb.task.PerimeterPathGeometry
import com.example.orb.task.PerimeterRibbonLayer
import com.example.orb.task.PerimeterRibbonMeshBuilder
import com.example.orb.task.PerimeterSide
import com.example.orb.task.PerimeterWaterLightMaterial
import com.example.orb.task.PhotoFrameGeometry
import com.example.orb.task.Tab2DebugOptions
import com.example.orb.task.TaskEnergyStage
import com.example.orb.task.TaskEnergyTimelineAdapter
import com.example.orb.task.TaskWaterLightShaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Tab2PerimeterV25IntegrationTest {
  private val photo = PhotoFrameGeometry.canonicalForViewport(1080, 2340)
  private val perimeter = PerimeterPathGeometry(photo)
  private val table = PerimeterArcLengthTable(perimeter, sampleCountPerSide = 220)

  @Test
  fun closedRouteHasContinuousPositionTangentAndIncreasingArcLengthAtBothJoins() {
    val epsilon = 0.001f
    val bottomStart = table.sampleClosedLoop(0f)
    val bottomEnd = table.sampleClosedLoop(1f)
    assertTrue((bottomStart.positionPx - bottomEnd.positionPx).length() < 0.05f)
    assertTrue(dot(bottomStart.tangent, bottomEnd.tangent) > 0.995f)
    assertEquals(0f, bottomStart.arcLengthPx, 1e-4f)
    assertEquals(0f, bottomEnd.arcLengthPx, 1e-4f) // q=1 wraps to the q=0 seam.

    val topBefore = table.sampleClosedLoop(0.5f - epsilon)
    val topAfter = table.sampleClosedLoop(0.5f + epsilon)
    assertTrue((topBefore.positionPx - topAfter.positionPx).length() < 10f)
    assertTrue("top-center tangent must continue in one direction", dot(topBefore.tangent, topAfter.tangent) > 0.99f)
    assertTrue(topAfter.arcLengthPx > topBefore.arcLengthPx)

    val beforeSeam = table.sampleClosedLoop(1f - epsilon)
    val afterSeam = table.sampleClosedLoop(epsilon)
    assertTrue((beforeSeam.positionPx - afterSeam.positionPx).length() < 10f)
    assertTrue("bottom seam tangent must continue in one direction", dot(beforeSeam.tangent, afterSeam.tangent) > 0.99f)
    assertTrue(afterSeam.arcLengthPx < beforeSeam.arcLengthPx)
  }

  @Test
  fun meshUvUsesRuntimePhotoGeometryAndJoinsTextureAtTopAndBottom() {
    assertEquals(0f, PerimeterRibbonMeshBuilder.closedLoopU(PerimeterSide.LEFT, 0f), 1e-6f)
    assertEquals(0.5f, PerimeterRibbonMeshBuilder.closedLoopU(PerimeterSide.LEFT, 1f), 1e-6f)
    assertEquals(1f, PerimeterRibbonMeshBuilder.closedLoopU(PerimeterSide.RIGHT, 0f), 1e-6f)
    assertEquals(0.5f, PerimeterRibbonMeshBuilder.closedLoopU(PerimeterSide.RIGHT, 1f), 1e-6f)

    val left = PerimeterRibbonMeshBuilder.buildLayerStripNdc(
      table, PerimeterSide.LEFT, PerimeterRibbonLayer.L1_ICE_BLUE_BODY, 1080, 2340,
    )
    val right = PerimeterRibbonMeshBuilder.buildLayerStripNdc(
      table, PerimeterSide.RIGHT, PerimeterRibbonLayer.L1_ICE_BLUE_BODY, 1080, 2340,
    )
    val stride = PerimeterRibbonMeshBuilder.FLOATS_PER_VERTEX
    val samples = table.sampleCountPerSide
    assertEquals(0f, left[4], 1e-6f)
    assertEquals(0.5f, left[(samples * 2 - 1) * stride + 4], 1e-5f)
    assertEquals(1f, right[4], 1e-6f)
    assertEquals(0.5f, right[(samples * 2 - 1) * stride + 4], 1e-5f)
    assertTrue(left.all { it.isFinite() })
    assertTrue(right.all { it.isFinite() })
  }

  @Test
  fun sharedWaterWorldTimelineRunsAllStagesAndPauseResumePhotoRestartStayBounded() {
    val adapter = TaskEnergyTimelineAdapter()
    val observedStages = linkedSetOf<TaskEnergyStage>()
    var snapshot = adapter.advanceAt(0.0, isPlaying = true, perimeter = perimeter)
    observedStages += snapshot.stage

    for (frame in 1..360) {
      snapshot = adapter.advanceAt(frame / 60.0, isPlaying = true, perimeter = perimeter)
      observedStages += snapshot.stage
    }

    assertEquals(
      listOf(
        TaskEnergyStage.ORB_RELEASE,
        TaskEnergyStage.BOTTOM_CONTACT,
        TaskEnergyStage.BOTTOM_SPLIT,
        TaskEnergyStage.SIDE_ASCEND,
        TaskEnergyStage.TOP_JOIN,
        TaskEnergyStage.PERIMETER_LOOP,
      ),
      observedStages.toList(),
    )
    assertTrue(snapshot.coverageComplete)
    assertEquals(1f, snapshot.leftProgress, 1e-5f)
    assertEquals(1f, snapshot.rightProgress, 1e-5f)
    assertEquals(1, adapter.controller.events.count { it.name == "TASK_RELEASE_STARTED" })

    val beforePause = snapshot
    snapshot = adapter.advanceAt(9.0, isPlaying = false, perimeter = perimeter)
    assertEquals(beforePause.elapsedMs, snapshot.elapsedMs)
    assertEquals(beforePause.flowTimeSec, snapshot.flowTimeSec, 0f)

    snapshot = adapter.advanceAt(9.1, isPlaying = true, perimeter = perimeter)
    assertTrue(snapshot.elapsedMs > beforePause.elapsedMs)
    assertTrue(snapshot.elapsedMs - beforePause.elapsedMs <= 110L)
    assertEquals(TaskEnergyStage.PERIMETER_LOOP, snapshot.stage)

    adapter.restartAt(9.1)
    snapshot = adapter.advanceAt(9.1, isPlaying = true, perimeter = perimeter)
    assertEquals(TaskEnergyStage.ORB_RELEASE, snapshot.stage)
    assertEquals(0L, snapshot.elapsedMs)
    assertEquals(0f, snapshot.releaseProgress, 0f)
    assertEquals(1, adapter.controller.events.count { it.name == "TASK_RELEASE_STARTED" })
  }

  @Test
  fun v2MaterialCapsSamplerPathAndDebugLayerControlsAreProductionWired() {
    val material = PerimeterWaterLightMaterial.Tab2V22
    assertEquals(0.30f, material.effectiveAlphaCapFor(PerimeterRibbonLayer.L1_ICE_BLUE_BODY), 0f)
    assertEquals(0.16f, material.effectiveAlphaCapFor(PerimeterRibbonLayer.L2_EMERALD_VEIL), 0f)
    assertEquals(0.28f, material.effectiveAlphaCapFor(PerimeterRibbonLayer.L3_GOLD_FILAMENT), 0f)

    val fragment = TaskWaterLightShaders.PERIMETER_RIBBON_FRAG
    assertTrue(fragment.contains("texture(uLayerRgbaTex, animatedUv)"))
    assertTrue(fragment.contains("texture(uLayerNormalTex, baseUv)"))
    assertTrue(fragment.contains("texture(uLayerFlowTex, baseUv)"))
    assertTrue(fragment.contains("uCoverageComplete"))
    assertTrue(fragment.contains("alpha = min(alpha, uLayerAlphaCapOverride)"))
    assertTrue(TaskWaterLightShaders.TASK_SPARKLE_FRAG.contains("texture(uDropletAtlas, atlasUv)"))
    assertTrue(TaskWaterLightShaders.PERIMETER_RIBBON_FRAG.contains("uLayerSpeed * 0.035"))

    val layerOnly = Tab2DebugOptions(showL1 = false, showL2 = true, showL3 = false, showL4 = true)
      .toPerimeterDebugOptions()
    assertTrue("Tab 2 must keep Orb-to-photo main energy enabled", layerOnly.showMainStream)
    assertTrue("Tab 2 must keep secondary energy enabled", layerOnly.showSecondaryStreams)
    assertFalse(layerOnly.showL1)
    assertTrue(layerOnly.showL2)
    assertFalse(layerOnly.showL3)
    assertTrue(layerOnly.showL4)
  }

  private fun dot(a: com.example.orb.task.Vec2, b: com.example.orb.task.Vec2): Float = a.x * b.x + a.y * b.y
}
