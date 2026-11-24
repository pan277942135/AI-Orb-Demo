package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.OrbSceneMode
import com.example.orb.config.OrbCoreMode
import com.example.orb.config.QualityTier
import com.example.orb.config.RenderDebugMode
import com.example.orb.scene.AIOrbOrbitRibbons
import com.example.orb.scene.AIOrbScene
import com.example.orb.scene.RibbonRole
import com.example.orb.task.TaskEnergyStage
import com.example.orb.ui.AIOrbViewModel
import com.example.orb.ui.DemoTab
import com.example.orb.v3.OrbV3AssetManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * G0 Regression Suite — Verifies 100% preservation of Tab 1 (`AI Orb` V3) and
 * strict single-renderer sharing across Tab 1 <-> Tab 2 transitions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExistingOrbTabRegressionTest {

  @Test
  fun tab1DefaultsAndFrozenV3OrbContractRemainUntouched() {
    val cfg = AIOrbConfig.Default
    assertEquals(OrbSceneMode.ORB_ONLY, cfg.sceneMode)
    assertEquals(0.80f, cfg.coreShellScale, 1e-5f)
    assertEquals(5, cfg.ribbonCount)
    assertEquals(QualityTier.HIGH, cfg.quality)
    assertEquals(RenderDebugMode.FULL_RENDER, cfg.debugMode)
    assertFalse(cfg.isPaused)

    // Verify all 5 frozen Resident ribbons remain intact and unmodified
    assertEquals(5, AIOrbConfig.CANONICAL_RESIDENT_COUNT)
    val residents = AIOrbOrbitRibbons.buildRibbonDescriptors().filter { it.role == RibbonRole.RESIDENT }
    assertEquals(5, residents.size)
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manifest = OrbV3AssetManifest.loadFromAssets(context.assets)
    assertEquals(256, manifest.volume.width)
    assertEquals(256, manifest.volume.height)
    assertEquals(32, manifest.volume.depth)
    assertEquals(35, manifest.totalWebpFiles)
  }

  @Test
  fun tab1RenderPassManifestHasZeroTaskPhotoOrPerimeterPasses() {
    val tab1Passes = AIOrbScene.activeRenderPassesV3(RenderDebugMode.FULL_RENDER)
    assertTrue(tab1Passes.any { it.contains("PASS_6_V3_WEBP_3D_VOLUME_RAYMARCH_AND_CRYSTAL_GLASS_SHELL") })
    assertFalse("Tab 1 must NEVER include Task Energy passes", tab1Passes.any { it.contains("TASK") })
    assertFalse("Tab 1 must NEVER include Photo Quad passes", tab1Passes.any { it.contains("PHOTO") })
    assertFalse("Tab 1 must NEVER include Perimeter WaterLight passes", tab1Passes.any { it.contains("PERIMETER") })

    val tab2Passes = AIOrbScene.activeRenderPassesTaskDemo()
    assertTrue("Tab 2 must reuse shared V3 3D volume core", tab2Passes.any { it.contains("SHARED_V3_WEBP_3D_VOLUME") })
    assertTrue("Tab 2 must reuse shared 5 Residents", tab2Passes.any { it.contains("SHARED_ORB_FRONT_5_RESIDENTS") })
    assertTrue("Tab 2 must include independent Task Energy streams", tab2Passes.any { it.contains("TASK_ENERGY_RIBBONS") })
    assertTrue("Tab 2 must include unified Perimeter WaterLight", tab2Passes.any { it.contains("PERIMETER_WATERLIGHT") })
  }

  @Test
  fun dualTabSwitchingPreservesTab1ParametersAndReusesSingleSharedScene() {
    val vm = AIOrbViewModel()
    assertEquals(DemoTab.AI_ORB, vm.uiState.value.activeTab)
    assertEquals(OrbSceneMode.ORB_ONLY, vm.uiState.value.config.sceneMode)

    // Customize Tab 1 parameters
    vm.updateConfig {
      it.copy(
        coreScale = 1.12f,
        refraction = 0.88f,
        fluidSpeed = 1.35f,
        bloom = 0.44f,
        isPaused = true,
      )
    }
    vm.setQuality(QualityTier.MEDIUM)
    val customTab1 = vm.uiState.value.config

    // Switch Tab 1 -> Tab 2 -> Tab 1 -> Tab 2 -> Tab 1
    vm.selectTab(DemoTab.TASK_PERIMETER)
    assertEquals(DemoTab.TASK_PERIMETER, vm.uiState.value.activeTab)
    assertEquals(OrbSceneMode.TASK_PERIMETER_DEMO, vm.uiState.value.config.sceneMode)

    vm.selectTab(DemoTab.YUJIAN_AI_ORB)
    assertEquals(DemoTab.YUJIAN_AI_ORB, vm.uiState.value.activeTab)
    assertEquals(OrbSceneMode.YUJIAN_HYBRID, vm.uiState.value.config.sceneMode)
    assertEquals(OrbCoreMode.YUJIAN_CORE, vm.uiState.value.config.coreMode)

    vm.selectTab(DemoTab.AI_ORB)
    assertEquals(DemoTab.AI_ORB, vm.uiState.value.activeTab)
    val restored = vm.uiState.value.config
    assertEquals(customTab1.copy(sceneMode = OrbSceneMode.ORB_ONLY), restored)
    assertEquals(OrbCoreMode.NATIVE_V32, restored.coreMode)

    vm.selectTab(DemoTab.YUJIAN_AI_ORB)
    vm.selectTab(DemoTab.TASK_PERIMETER)
    assertEquals(OrbSceneMode.TASK_PERIMETER_DEMO, vm.uiState.value.config.sceneMode)
    vm.selectTab(DemoTab.AI_ORB)
    assertEquals(customTab1.copy(sceneMode = OrbSceneMode.ORB_ONLY), vm.uiState.value.config)

    // Verify SINGLE AIOrbScene instance switches modes without recreating GL context or reloading WebP
    val scene = AIOrbScene(initialConfig = customTab1, assetManager = null)
    assertTrue(scene.isV3SingleVolumePipeline)
    assertEquals(0, scene.tabSwitchCount)

    scene.updateConfig(customTab1.copy(sceneMode = OrbSceneMode.TASK_PERIMETER_DEMO))
    assertEquals(1, scene.tabSwitchCount)
    val kf04 = scene.jumpToTaskKeyframe(TaskEnergyStage.SIDE_ASCEND)
    assertEquals(TaskEnergyStage.SIDE_ASCEND, kf04.stage)

    scene.updateConfig(customTab1.copy(sceneMode = OrbSceneMode.YUJIAN_HYBRID))
    assertEquals(2, scene.tabSwitchCount)
    scene.updateConfig(customTab1.copy(sceneMode = OrbSceneMode.ORB_ONLY))
    assertEquals(3, scene.tabSwitchCount)
    scene.updateConfig(customTab1.copy(sceneMode = OrbSceneMode.TASK_PERIMETER_DEMO))
    assertEquals(4, scene.tabSwitchCount)

    // Re-entering Tab 2 preserves existing Task progress without forcing reset
    assertEquals(TaskEnergyStage.SIDE_ASCEND, scene.currentTaskSnapshot().stage)
    val evidence = scene.currentTaskEvidence()
    assertNotNull(evidence)
    assertTrue("Shared GPU resources must never reload on tab switch", evidence.gpuResourcesReused)
    assertEquals(4, evidence.tabSwitchCount)
  }

  @Test
  fun tab3UsesTheIndependentRefBOuterOpticalPasses() {
    val nativePasses = AIOrbScene.activeRenderPassesV3(RenderDebugMode.FULL_RENDER)
    val hybridPasses = AIOrbScene.activeRenderPassesYuJianHybrid(RenderDebugMode.FULL_RENDER)
    assertTrue(hybridPasses.contains("PASS_2_REF_B_BACK_OPTICAL_SURFACES_AND_FILAMENTS"))
    assertTrue(hybridPasses.contains("PASS_5_YUJIAN_CORE_SOURCE_SPHERE_FS"))
    assertTrue(hybridPasses.contains("PASS_7_REF_B_FRONT_OPTICAL_SURFACES_AND_FILAMENTS"))
    assertTrue(hybridPasses.indexOf("PASS_2_REF_B_BACK_OPTICAL_SURFACES_AND_FILAMENTS") < hybridPasses.indexOf("PASS_5_YUJIAN_CORE_SOURCE_SPHERE_FS"))
    assertTrue(hybridPasses.indexOf("PASS_5_YUJIAN_CORE_SOURCE_SPHERE_FS") < hybridPasses.indexOf("PASS_7_REF_B_FRONT_OPTICAL_SURFACES_AND_FILAMENTS"))
    assertTrue("Tab 1 native pass contract remains present", nativePasses.contains("PASS_3_BACK_RIBBONS_RESIDENT_AND_SECONDARY"))
    assertTrue("Tab 1 native pass contract remains present", nativePasses.contains("PASS_6_V3_WEBP_3D_VOLUME_RAYMARCH_AND_CRYSTAL_GLASS_SHELL"))
  }
}
