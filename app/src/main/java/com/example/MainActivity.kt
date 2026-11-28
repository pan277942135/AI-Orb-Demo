package com.example

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import com.example.orb.scene.AIOrbScene
import com.example.orb.task.Tab2DebugOptions
import com.example.orb.task.Tab2WaterWorldRuntimeStatus
import com.example.orb.task.TaskSceneLayoutSnapshot
import com.example.orb.ui.AIOrbCanvas
import com.example.orb.ui.AIOrbControlsOverlay
import com.example.orb.ui.AIOrbViewModel
import com.example.orb.ui.DemoTab
import com.example.orb.ui.Tab2WaterWorldCanvas
import com.example.orb.ui.TaskRecognitionDemoScreen
import com.example.orb.v3.OrbGpuReferenceCapture
import com.example.ui.theme.AbyssEmerald
import com.example.ui.theme.MyApplicationTheme
import com.example.waterworld.WaterWorldPreviewActivity

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
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        var sharedScene by remember { mutableStateOf<AIOrbScene?>(null) }
        var surfaceMetrics by remember { mutableStateOf(SurfaceWindowMetrics()) }
        var photoMetrics by remember { mutableStateOf<PhotoWindowMetrics?>(null) }
        var tab2WaterWorldStatus by remember { mutableStateOf(Tab2WaterWorldRuntimeStatus()) }

        var tab2SelectedPhotoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
        var tab2DebugOptions by remember { mutableStateOf(Tab2DebugOptions.Default) }
        var tab2RestartToken by remember { mutableIntStateOf(0) }
        var tab2TogglePlayPauseToken by remember { mutableIntStateOf(0) }

        val isTaskTab = uiState.activeTab == DemoTab.TASK_PERIMETER
        val navBottomInsetPx = WindowInsets.navigationBars.getBottom(LocalDensity.current).toFloat()

        val photoPickerLauncher = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri: Uri? ->
          if (uri != null) {
            runCatching {
              contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
              )
            }
            tab2SelectedPhotoUri = uri
          }
        }

        LaunchedEffect(isTaskTab) {
          applyImmersiveFullscreen(isLightBars = isTaskTab)
        }

        val currentTab2LayoutSnapshot = remember(surfaceMetrics, photoMetrics) {
          val photo = photoMetrics
          if (photo != null && surfaceMetrics.widthPx >= 64 && surfaceMetrics.heightPx >= 64) {
            TaskSceneLayoutSnapshot.fromComposeWindowBounds(
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
          } else {
            null
          }
        }

        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(if (isTaskTab) ComposeColor(0xFF0A4B63) else AbyssEmerald),
        ) {
          if (!isTaskTab) {
            AIOrbCanvas(
              config = uiState.config,
              onTelemetryUpdated = viewModel::reportTelemetry,
              onTaskEvidenceUpdated = viewModel::reportTaskEvidence,
              onSceneReady = { sharedScene = it },
              onSurfaceWindowBoundsChanged = { l, t, w, h ->
                surfaceMetrics = SurfaceWindowMetrics(l, t, w, h)
              },
              enableOrbitDrag = true,
            )
          } else {
            Tab2WaterWorldCanvas(
              layoutSnapshot = currentTab2LayoutSnapshot,
              bottomInsetPx = navBottomInsetPx,
              selectedPhotoUri = tab2SelectedPhotoUri,
              restartToken = tab2RestartToken,
              togglePlayPauseToken = tab2TogglePlayPauseToken,
              debugOptions = tab2DebugOptions,
              onStatusChanged = { status -> tab2WaterWorldStatus = status },
              onSurfaceWindowBoundsChanged = { l, t, w, h ->
                surfaceMetrics = SurfaceWindowMetrics(l, t, w, h)
              },
              modifier = Modifier.fillMaxSize(),
            )
          }

          if (!isTaskTab && uiState.config.debugMode == RenderDebugMode.REFERENCE_ONLY) {
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

          if (isTaskTab) {
            TaskRecognitionDemoScreen(
              debugOptions = tab2DebugOptions,
              isControlsExpanded = uiState.isTaskControlsExpanded,
              onBackToOrb = { viewModel.selectTab(DemoTab.AI_ORB) },
              onToggleControls = viewModel::toggleTaskControls,
              onCloseControls = viewModel::closeTaskControls,
              onTogglePlayPause = { tab2TogglePlayPauseToken += 1 },
              onRestart = { tab2RestartToken += 1 },
              onUpdateDebugOptions = { opts -> tab2DebugOptions = opts },
              onPhotoWindowBoundsChanged = { l, t, r, b, cr ->
                photoMetrics = PhotoWindowMetrics(l, t, r, b, cr)
              },
              waterWorldStatus = tab2WaterWorldStatus,
              onPickPhoto = {
                photoPickerLauncher.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
              },
            )
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

          if (!isTaskTab) {
            com.example.ui.DemoTabSwitcherBar(
              activeTab = uiState.activeTab,
              onSelectTab = viewModel::selectTab,
              isLightTheme = isTaskTab,
              showWaterWorldTab = BuildConfig.DEBUG,
              onOpenWaterWorld = { openWaterWorldPreview() },
              modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .displayCutoutPadding()
                .padding(top = 6.dp, start = 8.dp, end = 8.dp),
            )
          }
        }
      }
    }
  }

  internal fun openWaterWorldPreview() {
    if (!BuildConfig.DEBUG) return
    startActivity(
      Intent(
        this@MainActivity,
        WaterWorldPreviewActivity::class.java,
      )
    )
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      window.decorView.post { applyImmersiveFullscreen(isLightBars = false) }
    }
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
