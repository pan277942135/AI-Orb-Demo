package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.orb.config.RenderDebugMode
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
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.decorView.post { applyImmersiveFullscreen() }

    setContent {
      MyApplicationTheme {
        val viewModel: AIOrbViewModel = viewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(AbyssEmerald),
        ) {
          if (uiState.config.debugMode == RenderDebugMode.REFERENCE_ONLY) {
            // Strictly isolated QA Reference Master viewer — NEVER composited with GPU_ONLY or FULL_RENDER!
            Image(
              painter = painterResource(id = R.drawable.ref_master_qa_only),
              contentDescription = stringResource(R.string.cd_reference_master),
              contentScale = ContentScale.Fit,
              modifier = Modifier
                .fillMaxSize()
                .testTag("reference_only_image"),
            )
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
              modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(14.dp)
                .testTag("reference_only_badge"),
            ) {
              Text(
                text = stringResource(R.string.qa_reference_only_badge),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              )
            }
          } else {
            // 100% pure real-time OpenGL ES 3.0 GPU rendering (zero static reference overlay)
            AIOrbCanvas(
              config = uiState.config,
              onTelemetryUpdated = viewModel::reportTelemetry,
            )
          }

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
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      window.decorView.post { applyImmersiveFullscreen() }
    }
  }

  private fun applyImmersiveFullscreen() {
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
  }
}
