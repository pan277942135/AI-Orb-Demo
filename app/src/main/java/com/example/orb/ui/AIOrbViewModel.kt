package com.example.orb.ui

import androidx.lifecycle.ViewModel
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.OrbSceneMode
import com.example.orb.config.QualityTier
import com.example.orb.config.RenderDebugMode
import com.example.orb.scene.AIOrbTelemetry
import com.example.orb.task.TaskEnergyEvidence
import com.example.orb.task.TaskGpuCaptureProgress
import com.example.orb.task.TaskRenderDebugOptions
import com.example.orb.v3.GpuCaptureProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AIOrbUiState(
  val activeTab: DemoTab = DemoTab.AI_ORB,
  val config: AIOrbConfig = AIOrbConfig.Default,
  val isControlsExpanded: Boolean = false,
  val isTaskControlsExpanded: Boolean = false,
  val telemetry: AIOrbTelemetry = AIOrbTelemetry(),
  val taskEvidence: TaskEnergyEvidence = TaskEnergyEvidence(),
  val taskDebugOptions: TaskRenderDebugOptions = TaskRenderDebugOptions.Default,
  val showExportDialog: Boolean = false,
  val gpuCaptureProgress: GpuCaptureProgress = GpuCaptureProgress(),
  val taskCaptureProgress: TaskGpuCaptureProgress = TaskGpuCaptureProgress(),
) {
  val fps: Int get() = telemetry.fps
}

class AIOrbViewModel : ViewModel() {
  private val _uiState = MutableStateFlow(AIOrbUiState())
  val uiState: StateFlow<AIOrbUiState> = _uiState.asStateFlow()

  /**
   * Switches the three demo tabs on the SINGLE shared
   * [com.example.orb.scene.AIOrbScene] without altering ANY Tab 1 Orb visual parameter.
   */
  fun selectTab(tab: DemoTab) {
    _uiState.update { state ->
      if (state.activeTab == tab) return@update state
      val targetSceneMode = when (tab) {
        DemoTab.AI_ORB -> OrbSceneMode.ORB_ONLY
        DemoTab.TASK_PERIMETER -> OrbSceneMode.TASK_PERIMETER_DEMO
        DemoTab.YUJIAN_AI_ORB -> OrbSceneMode.YUJIAN_HYBRID
      }
      state.copy(
        activeTab = tab,
        config = state.config.copy(sceneMode = targetSceneMode),
        isControlsExpanded = if (tab != DemoTab.AI_ORB) false else state.isControlsExpanded,
        isTaskControlsExpanded = if (tab != DemoTab.TASK_PERIMETER) false else state.isTaskControlsExpanded,
      )
    }
  }

  fun updateConfig(transform: (AIOrbConfig) -> AIOrbConfig) {
    _uiState.update { state ->
      val currentMode = state.config.sceneMode
      state.copy(config = transform(state.config).copy(sceneMode = currentMode))
    }
  }

  /** Test-only state injection so instrumentation can freeze the same production scene snapshot. */
  internal fun setConfigForInstrumentation(config: AIOrbConfig) {
    _uiState.update { it.copy(config = config) }
  }

  fun toggleControls() {
    _uiState.update { it.copy(isControlsExpanded = !it.isControlsExpanded) }
  }

  fun closeControls() {
    _uiState.update { it.copy(isControlsExpanded = false) }
  }

  fun toggleTaskControls() {
    _uiState.update { it.copy(isTaskControlsExpanded = !it.isTaskControlsExpanded) }
  }

  fun closeTaskControls() {
    _uiState.update { it.copy(isTaskControlsExpanded = false) }
  }

  fun togglePause() {
    _uiState.update { it.copy(config = it.config.copy(isPaused = !it.config.isPaused)) }
  }

  fun resetConfig() {
    _uiState.update { state ->
      state.copy(config = AIOrbConfig.Default.copy(sceneMode = state.config.sceneMode))
    }
  }

  fun setQuality(quality: QualityTier) {
    _uiState.update { it.copy(config = it.config.copy(quality = quality)) }
  }

  fun setDebugMode(mode: RenderDebugMode) {
    _uiState.update {
      it.copy(
        config = it.config.copy(
          debugMode = mode,
          isolateCoreDebug = mode == RenderDebugMode.CORE_ROTATION_ONLY,
        )
      )
    }
  }

  fun openExportDialog() {
    _uiState.update { it.copy(showExportDialog = true) }
  }

  fun dismissExportDialog() {
    _uiState.update { it.copy(showExportDialog = false) }
  }

  fun reportTelemetry(telemetry: AIOrbTelemetry) {
    _uiState.update { current ->
      if (current.telemetry == telemetry) current else current.copy(telemetry = telemetry)
    }
  }

  fun reportTaskEvidence(evidence: TaskEnergyEvidence) {
    _uiState.update { current ->
      if (current.taskEvidence == evidence) current else current.copy(taskEvidence = evidence)
    }
  }

  fun reportGpuCaptureProgress(progress: GpuCaptureProgress) {
    _uiState.update { current ->
      if (current.gpuCaptureProgress == progress) current else current.copy(gpuCaptureProgress = progress)
    }
  }

  fun updateTaskDebugOptions(options: TaskRenderDebugOptions) {
    _uiState.update { current ->
      if (current.taskDebugOptions == options) current else current.copy(taskDebugOptions = options)
    }
  }

  fun reportTaskCaptureProgress(progress: TaskGpuCaptureProgress) {
    _uiState.update { current ->
      if (current.taskCaptureProgress == progress) current else current.copy(taskCaptureProgress = progress)
    }
  }
}
