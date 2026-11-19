package com.example.orb.ui

import androidx.lifecycle.ViewModel
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.QualityTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AIOrbUiState(
  val config: AIOrbConfig = AIOrbConfig.Default,
  val isControlsExpanded: Boolean = false,
  val fps: Int = 60,
  val showExportDialog: Boolean = false,
)

class AIOrbViewModel : ViewModel() {
  private val _uiState = MutableStateFlow(AIOrbUiState())
  val uiState: StateFlow<AIOrbUiState> = _uiState.asStateFlow()

  fun updateConfig(transform: (AIOrbConfig) -> AIOrbConfig) {
    _uiState.update { state -> state.copy(config = transform(state.config)) }
  }

  fun toggleControls() {
    _uiState.update { it.copy(isControlsExpanded = !it.isControlsExpanded) }
  }

  fun closeControls() {
    _uiState.update { it.copy(isControlsExpanded = false) }
  }

  fun togglePause() {
    _uiState.update { it.copy(config = it.config.copy(isPaused = !it.config.isPaused)) }
  }

  fun resetConfig() {
    _uiState.update { it.copy(config = AIOrbConfig.Default) }
  }

  fun setQuality(quality: QualityTier) {
    _uiState.update { it.copy(config = it.config.copy(quality = quality)) }
  }

  fun openExportDialog() {
    _uiState.update { it.copy(showExportDialog = true) }
  }

  fun dismissExportDialog() {
    _uiState.update { it.copy(showExportDialog = false) }
  }

  fun reportFps(fps: Int) {
    _uiState.update { current ->
      if (current.fps == fps) current else current.copy(fps = fps)
    }
  }
}
