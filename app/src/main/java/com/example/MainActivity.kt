package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.orb.config.RenderDebugMode
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.OrbSceneMode
import com.example.orb.scene.AIOrbScene
import com.example.orb.task.TaskGpuEvidenceCapture
import com.example.orb.task.TaskSceneLayoutSnapshot
import com.example.orb.ui.AIOrbCanvas
import com.example.orb.ui.AIOrbControlsOverlay
import com.example.orb.ui.AIOrbViewModel
import com.example.orb.ui.DemoTab
import com.example.orb.ui.TaskRecognitionDemoScreen
import com.example.orb.v3.OrbGpuReferenceCapture
import com.example.orb.v3.GpuCaptureProgress
import com.example.ui.theme.AbyssEmerald
import com.example.ui.theme.MyApplicationTheme
import java.io.File

private data class SurfaceWindowMetrics(
  val leftPx: Float = 0f,
  val topPx: Float = 0f,
  val widthPx: Int = 0,
  val heightPx: Int = 0,
)

private data class PhotoWindowMetrics(
  val leftPx: Float = 0f,
  val topPx: Float = 0f,
  val rightPx: Float = 0f,
  val bottomPx: Float = 0f,
  val cornerRadiusPx: Float = 0f,
)

class MainActivity : ComponentActivity() {
  @Volatile private var p0_3EvidenceScene: AIOrbScene? = null
  @Volatile private var p0_3EvidenceProgress: GpuCaptureProgress? = null
  @Volatile private var p0_3EvidenceViewModel: AIOrbViewModel? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.decorView.post { applyImmersiveFullscreen(isLightBars = false) }

    setContent {
      MyApplicationTheme {
        val viewModel: AIOrbViewModel = viewModel()
        p0_3EvidenceViewModel = viewModel
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        var sharedScene by remember { mutableStateOf<AIOrbScene?>(null) }
        var surfaceMetrics by remember { mutableStateOf(SurfaceWindowMetrics()) }
        var photoMetrics by remember { mutableStateOf<PhotoWindowMetrics?>(null) }

        val isTaskTab = uiState.activeTab == DemoTab.TASK_PERIMETER
        val isYujianTab = uiState.activeTab == DemoTab.YUJIAN_AI_ORB

        LaunchedEffect(isTaskTab) {
          applyImmersiveFullscreen(isLightBars = isTaskTab)
        }

        // Synchronize live Compose window bounds -> GLSurfaceView local physical pixels
        LaunchedEffect(sharedScene, surfaceMetrics, photoMetrics) {
          val scene = sharedScene ?: return@LaunchedEffect
          val photo = photoMetrics ?: return@LaunchedEffect
          if (surfaceMetrics.widthPx >= 64 && surfaceMetrics.heightPx >= 64) {
            val snapshot = TaskSceneLayoutSnapshot.fromComposeWindowBounds(
              surfaceWindowLeftPx = surfaceMetrics.leftPx,
              surfaceWindowTopPx = surfaceMetrics.topPx,
              surfaceWidthPx = surfaceMetrics.widthPx,
              surfaceHeightPx = surfaceMetrics.heightPx,
              photoWindowLeftPx = photo.leftPx,
              photoWindowTopPx = photo.topPx,
              photoWindowRightPx = photo.rightPx,
              photoWindowBottomPx = photo.bottomPx,
              photoCornerRadiusPx = photo.cornerRadiusPx,
            )
            scene.updateTaskLayout(snapshot)
          }
        }

        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(if (isTaskTab) ComposeColor(0xFFCDEEF7) else AbyssEmerald),
        ) {
          // One persistent OpenGL ES 3.0 GLSurfaceView + AIOrbScene shared across all three tabs.
          // Tab switching only changes the scene mode; it never creates another EGL context.
          AIOrbCanvas(
            config = uiState.config,
            onTelemetryUpdated = viewModel::reportTelemetry,
            onTaskEvidenceUpdated = viewModel::reportTaskEvidence,
            onSceneReady = { scene ->
              sharedScene = scene
              p0_3EvidenceScene = scene
            },
            onSurfaceWindowBoundsChanged = { l, t, w, h ->
              surfaceMetrics = SurfaceWindowMetrics(l, t, w, h)
            },
            enableOrbitDrag = !isTaskTab,
          )

          // Tab 1 isolated QA Reference Master viewer (strictly Tab 1 only, never composited with GPU)
          if (uiState.activeTab == DemoTab.AI_ORB && uiState.config.debugMode == RenderDebugMode.REFERENCE_ONLY) {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(AbyssEmerald),
            ) {
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
                  .padding(top = 60.dp, start = 14.dp)
                  .testTag("reference_only_badge"),
              ) {
                Text(
                  text = stringResource(R.string.qa_reference_only_badge),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
              }
            }
          }

          // Tab-specific UI overlay
          if (isTaskTab) {
            TaskRecognitionDemoScreen(
              evidence = uiState.taskEvidence,
              debugOptions = uiState.taskDebugOptions,
              captureProgress = uiState.taskCaptureProgress,
              isControlsExpanded = uiState.isTaskControlsExpanded,
              onBackToOrb = { viewModel.selectTab(DemoTab.AI_ORB) },
              onToggleControls = viewModel::toggleTaskControls,
              onCloseControls = viewModel::closeTaskControls,
              onTogglePlayPause = { sharedScene?.toggleTaskPaused() },
              onRestart = { sharedScene?.restartTask() },
              onJumpKeyframe = { stage -> sharedScene?.jumpToTaskKeyframe(stage) },
              onUpdateDebugOptions = { opts ->
                viewModel.updateTaskDebugOptions(opts)
                sharedScene?.updateTaskDebugOptions(opts)
              },
              onExport6KfEvidence = {
                val outDir = TaskGpuEvidenceCapture.resolveEvidenceDir(applicationContext)
                sharedScene?.requestTask6KfEvidenceExport(outDir) { progress ->
                  viewModel.reportTaskCaptureProgress(progress)
                }
              },
              onPhotoWindowBoundsChanged = { l, t, r, b, cr ->
                photoMetrics = PhotoWindowMetrics(l, t, r, b, cr)
              },
            )
          } else if (isYujianTab) {
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = ComposeColor(0xAA061A20),
              border = BorderStroke(1.dp, ComposeColor(0x6636B8C9)),
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .testTag("yujian_hybrid_badge"),
            ) {
              Text(
                text = stringResource(R.string.yujian_hybrid_badge),
                style = MaterialTheme.typography.labelMedium,
                color = ComposeColor(0xFFBEEEF5),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              )
            }
          } else {
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
              onExportV32Evidence = {
                val outDir = OrbGpuReferenceCapture.resolveEvidenceDir(applicationContext)
                sharedScene?.requestV32EvidenceExport(outDir) { progress ->
                  viewModel.reportGpuCaptureProgress(progress)
                }
              },
            )
          }

          // Floating three-tab segmented switcher.
          DemoTabSwitcherBar(
            activeTab = uiState.activeTab,
            onSelectTab = viewModel::selectTab,
            isLightTheme = isTaskTab,
            modifier = Modifier
              .align(Alignment.TopCenter)
              .statusBarsPadding()
              .displayCutoutPadding()
              .padding(top = 6.dp),
          )
        }
      }
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      window.decorView.post { applyImmersiveFullscreen(isLightBars = false) }
    }
  }

  /** Instrumentation entry that delegates to the same production scene capture used by the UI. */
  internal fun requestP0_3EvidenceCaptureForInstrumentation(outputDir: File): Boolean {
    val scene = p0_3EvidenceScene ?: return false
    return scene.requestV32EvidenceExport(outputDir) { progress ->
      p0_3EvidenceProgress = progress
    }
  }

  internal fun latestP0_3EvidenceProgressForInstrumentation(): GpuCaptureProgress? = p0_3EvidenceProgress

  internal fun sceneForInstrumentation(): AIOrbScene? = p0_3EvidenceScene

  internal fun setSceneForInstrumentation(sceneMode: OrbSceneMode, mode: RenderDebugMode, paused: Boolean = false) {
    val config = AIOrbConfig.Default.copy(sceneMode = sceneMode, debugMode = mode, isPaused = paused)
    p0_3EvidenceViewModel?.setConfigForInstrumentation(config)
    p0_3EvidenceScene?.updateConfig(config)
  }

  private fun applyImmersiveFullscreen(isLightBars: Boolean) {
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.isAppearanceLightStatusBars = isLightBars
    controller.isAppearanceLightNavigationBars = isLightBars
    controller.hide(WindowInsetsCompat.Type.systemBars())
  }
}

@Composable
private fun DemoTabSwitcherBar(
  activeTab: DemoTab,
  onSelectTab: (DemoTab) -> Unit,
  isLightTheme: Boolean,
  modifier: Modifier = Modifier,
) {
  val containerColor = if (isLightTheme) {
    ComposeColor(0xD8F1FCFF)
  } else {
    ComposeColor(0xA6061A20)
  }
  val borderColor = if (isLightTheme) {
    ComposeColor(0x8A78CEE0)
  } else {
    ComposeColor(0x6636B8C9)
  }
  val activePillColor = if (isLightTheme) {
    ComposeColor(0xFF0A8FA8)
  } else {
    ComposeColor(0xCC1293A8)
  }
  val activeTextColor = ComposeColor.White
  val inactiveTextColor = if (isLightTheme) {
    ComposeColor(0xFF0F5062)
  } else {
    ComposeColor(0xCCBEEEF5)
  }

  Surface(
    shape = RoundedCornerShape(26.dp),
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
    shadowElevation = if (isLightTheme) 2.dp else 0.dp,
    modifier = modifier.testTag("demo_tab_switcher"),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(4.dp),
    ) {
      DemoTabSegment(
        label = stringResource(R.string.tab_ai_orb),
        selected = activeTab == DemoTab.AI_ORB,
        activeBg = activePillColor,
        activeText = activeTextColor,
        inactiveText = inactiveTextColor,
        tag = "tab_ai_orb",
        onClick = { onSelectTab(DemoTab.AI_ORB) },
      )
      DemoTabSegment(
        label = stringResource(R.string.tab_task_perimeter),
        selected = activeTab == DemoTab.TASK_PERIMETER,
        activeBg = activePillColor,
        activeText = activeTextColor,
        inactiveText = inactiveTextColor,
        tag = "tab_task_perimeter",
        onClick = { onSelectTab(DemoTab.TASK_PERIMETER) },
      )
      DemoTabSegment(
        label = stringResource(R.string.tab_yujian_ai_orb),
        selected = activeTab == DemoTab.YUJIAN_AI_ORB,
        activeBg = activePillColor,
        activeText = activeTextColor,
        inactiveText = inactiveTextColor,
        tag = "tab_yujian_ai_orb",
        onClick = { onSelectTab(DemoTab.YUJIAN_AI_ORB) },
      )
    }
  }
}

@Composable
private fun DemoTabSegment(
  label: String,
  selected: Boolean,
  activeBg: ComposeColor,
  activeText: ComposeColor,
  inactiveText: ComposeColor,
  tag: String,
  onClick: () -> Unit,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .heightIn(min = 48.dp)
      .clip(RoundedCornerShape(22.dp))
      .background(if (selected) activeBg else ComposeColor.Transparent)
      .clickable(role = Role.Tab, onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 10.dp)
      .testTag(tag),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge.copy(
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
      ),
      color = if (selected) activeText else inactiveText,
    )
  }
}
