package com.example

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.orb.config.QualityTier
import com.example.orb.scene.AIOrbTelemetry
import com.example.orb.ui.AIOrbControlsOverlay
import com.example.orb.ui.AIOrbViewModel
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AIOrbIntegrationRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun debugControlsCujLifecycleAndQaDiagnostics() {
    val viewModel = AIOrbViewModel()
    viewModel.reportTelemetry(
      AIOrbTelemetry(
        fps = 60,
        frameMsP50 = 16.4f,
        frameMsP95 = 17.8f,
        coreAngleDeg = 142.5f,
        coreAngularVelDegPerSec = 26.2f,
        isHdrFbo = true,
      )
    )

    composeTestRule.setContent {
      MyApplicationTheme {
        val uiState by viewModel.uiState.collectAsState()
        AIOrbControlsOverlay(
          uiState = uiState,
          onToggleControls = viewModel::toggleControls,
          onCloseControls = viewModel::closeControls,
          onUpdateConfig = viewModel::updateConfig,
          onTogglePause = viewModel::togglePause,
          onReset = viewModel::resetConfig,
          onSelectQuality = viewModel::setQuality,
          onSelectDebugMode = viewModel::setDebugMode,
          onOpenExportDialog = viewModel::openExportDialog,
          onDismissExportDialog = viewModel::dismissExportDialog,
        )
      }
    }

    composeTestRule.waitForIdle()

    // 1. Default landing state: pure canvas (panel collapsed, QA motion overlay hidden, FULL_RENDER)
    assertEquals(com.example.orb.config.RenderDebugMode.FULL_RENDER, viewModel.uiState.value.config.debugMode)
    composeTestRule.onNodeWithTag("toggle_debug_controls_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("debug_controls_panel").assertDoesNotExist()
    composeTestRule.onNodeWithTag("motion_telemetry_overlay").assertDoesNotExist()

    // 2. Open Debug Controls panel
    composeTestRule.onNodeWithTag("toggle_debug_controls_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("debug_controls_panel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("fps_badge").assertIsDisplayed()

    // 3. Toggle Pause -> Resume
    composeTestRule.onNodeWithTag("pause_resume_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(viewModel.uiState.value.config.isPaused)

    composeTestRule.onNodeWithTag("pause_resume_button").performClick()
    composeTestRule.waitForIdle()
    assertFalse(viewModel.uiState.value.config.isPaused)

    // 4. Switch Quality tiers (MEDIUM -> LOW -> HIGH)
    composeTestRule.onNodeWithTag("quality_medium_chip").performClick()
    composeTestRule.waitForIdle()
    assertEquals(QualityTier.MEDIUM, viewModel.uiState.value.config.quality)

    composeTestRule.onNodeWithTag("quality_low_chip").performClick()
    composeTestRule.waitForIdle()
    assertEquals(QualityTier.LOW, viewModel.uiState.value.config.quality)

    composeTestRule.onNodeWithTag("quality_high_chip").performClick()
    composeTestRule.waitForIdle()
    assertEquals(QualityTier.HIGH, viewModel.uiState.value.config.quality)

    // 5. Enable QA Core Isolation, Motion Telemetry Overlay & GPU Diagnostic Modes
    composeTestRule.onNodeWithTag("switch_isolate_core").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertTrue(viewModel.uiState.value.config.isolateCoreDebug)

    composeTestRule.onNodeWithTag("switch_motion_overlay").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertTrue(viewModel.uiState.value.config.showMotionOverlay)
    composeTestRule.onNodeWithTag("motion_telemetry_overlay").assertIsDisplayed()

    composeTestRule.onNodeWithTag("debug_mode_caustics_only_chip").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(com.example.orb.config.RenderDebugMode.CAUSTICS_ONLY, viewModel.uiState.value.config.debugMode)

    composeTestRule.onNodeWithTag("debug_mode_core_rotation_only_chip").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(com.example.orb.config.RenderDebugMode.CORE_ROTATION_ONLY, viewModel.uiState.value.config.debugMode)
    assertTrue(viewModel.uiState.value.config.effectiveLockCoreRotation)

    // Verify GPU Runtime Info card (GL/EGL/FBO/HW-vs-SW) & renderer backend detector
    composeTestRule.onNodeWithTag("gpu_runtime_info_card").performScrollTo().assertIsDisplayed()
    assertTrue(com.example.orb.scene.AIOrbScene.detectSoftwareRenderer("Google Inc.", "Google SwiftShader"))
    assertTrue(com.example.orb.scene.AIOrbScene.detectSoftwareRenderer("Mesa", "llvmpipe (LLVM 15)"))
    assertFalse(com.example.orb.scene.AIOrbScene.detectSoftwareRenderer("Qualcomm", "Adreno (TM) 740"))
    assertFalse(com.example.orb.scene.AIOrbScene.detectSoftwareRenderer("ARM", "Mali-G715"))

    // 6. Export JSON Dialog
    composeTestRule.onNodeWithTag("export_json_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("export_json_preview").assertIsDisplayed()
    composeTestRule.onNodeWithTag("close_json_dialog_button").performClick()
    composeTestRule.waitForIdle()

    // 7. Reset restores default formal state (QA toggles OFF, FULL_RENDER, overlay dismissed)
    composeTestRule.onNodeWithTag("reset_parameters_button").performClick()
    composeTestRule.waitForIdle()
    assertFalse(viewModel.uiState.value.config.isolateCoreDebug)
    assertFalse(viewModel.uiState.value.config.showMotionOverlay)
    assertEquals(com.example.orb.config.RenderDebugMode.FULL_RENDER, viewModel.uiState.value.config.debugMode)
    composeTestRule.onNodeWithTag("motion_telemetry_overlay").assertDoesNotExist()

    // 8. Close panel cleanly back to pure canvas
    composeTestRule.onNodeWithTag("close_debug_controls_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("debug_controls_panel").assertDoesNotExist()
    composeTestRule.onNodeWithTag("toggle_debug_controls_button").assertIsDisplayed()
  }
}
