package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.orb.ui.AIOrbCanvas
import com.example.orb.ui.AIOrbControlsOverlay
import com.example.orb.ui.AIOrbViewModel
import com.example.ui.theme.AbyssEmerald
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )

    setContent {
      MyApplicationTheme {
        val viewModel: AIOrbViewModel = viewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(AbyssEmerald),
        ) {
          // Pure full-bleed real-time 3D AI Orb Active State canvas
          AIOrbCanvas(
            config = uiState.config,
            onFpsUpdated = viewModel::reportFps,
          )

          // Top-right collapsible Debug Controls (collapsed by default)
          AIOrbControlsOverlay(
            uiState = uiState,
            onToggleControls = viewModel::toggleControls,
            onCloseControls = viewModel::closeControls,
            onUpdateConfig = viewModel::updateConfig,
            onTogglePause = viewModel::togglePause,
            onReset = viewModel::resetConfig,
            onSelectQuality = viewModel::setQuality,
            onOpenExportDialog = viewModel::openExportDialog,
            onDismissExportDialog = viewModel::dismissExportDialog,
          )
        }
      }
    }
  }
}
