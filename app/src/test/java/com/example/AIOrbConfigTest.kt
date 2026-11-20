package com.example

import com.example.orb.config.AIOrbConfig
import com.example.orb.config.QualityTier
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.geometry.Vec3
import com.example.orb.ui.AIOrbViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIOrbConfigTest {

  @Test
  fun configExportsValidJsonWithAllParameters() {
    assertEquals(0.80f, AIOrbConfig.Default.coreShellScale, 1e-5f)
    assertEquals(5, AIOrbConfig.Default.ribbonCount)

    val config = AIOrbConfig(
      coreScale = 1.15f,
      coreShellScale = 0.82f,
      ribbonCount = 9,
      bloom = 0.65f,
      quality = QualityTier.MEDIUM,
    )
    val json = config.toJson()
    assertTrue(json.contains("\"schema\": \"AI_ORB_ACTIVE_STATE_VISUAL_DEMO_V1\""))
    assertTrue(json.contains("\"coreScale\": 1.150"))
    assertTrue(json.contains("\"coreShellScale\": 0.820"))
    assertTrue(json.contains("\"ribbonCount\": 9"))
    assertTrue(json.contains("\"bloom\": 0.650"))
    assertTrue(json.contains("\"quality\": \"MEDIUM\""))
  }

  @Test
  fun viewModelStartsCollapsedAndResetsCleanly() {
    val vm = AIOrbViewModel()
    assertFalse(vm.uiState.value.isControlsExpanded)
    assertFalse(vm.uiState.value.config.isPaused)

    vm.toggleControls()
    assertTrue(vm.uiState.value.isControlsExpanded)

    vm.togglePause()
    assertTrue(vm.uiState.value.config.isPaused)

    vm.updateConfig { it.copy(coreScale = 1.3f, ribbonCount = 7) }
    assertEquals(1.3f, vm.uiState.value.config.coreScale, 1e-4f)
    assertEquals(7, vm.uiState.value.config.ribbonCount)

    vm.resetConfig()
    assertEquals(AIOrbConfig.Default, vm.uiState.value.config)
  }

  @Test
  fun catmullRomSplineEvaluatesContinuously() {
    val pts = listOf(
      Vec3(1f, 0f, 0f),
      Vec3(0f, 0f, 1f),
      Vec3(-1f, 0f, 0f),
      Vec3(0f, 0f, -1f),
    )
    val start = OrbGeometryBuilder.sampleCatmullRom(pts, 0f, closed = true)
    val end = OrbGeometryBuilder.sampleCatmullRom(pts, 1f, closed = true)
    assertEquals(start.x, end.x, 1e-4f)
    assertEquals(start.y, end.y, 1e-4f)
    assertEquals(start.z, end.z, 1e-4f)
  }
}
