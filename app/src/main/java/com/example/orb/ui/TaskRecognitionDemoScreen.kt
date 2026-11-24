package com.example.orb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.orb.task.PhotoFrameGeometry
import com.example.orb.task.TaskEnergyEvidence
import com.example.orb.task.TaskEnergyStage
import com.example.orb.task.TaskGpuCaptureProgress
import com.example.orb.task.TaskGpuCaptureState
import com.example.orb.task.TaskRenderDebugOptions
import com.example.orb.task.TaskVisualDebugMode
import com.example.orb.task.TaskVisualFidelityV41
import java.util.Locale

private val AquaticInkPrimary = Color(0xFF0B4657)
private val AquaticInkSecondary = Color(0xFF155D70)
private val FrostedDaylightSurface = Color(0xE0F2FCFF)
private val FrostedDaylightBorder = Color(0x888ED7E6)
private val AccentAquaticTeal = Color(0xFF0A8FA8)

/**
 * Tab 2 (`照片水光`) transparent Compose overlay matching the frozen 6-keyframe overview:
 *  - Top bar: `<` back arrow + centered `拍照识鱼`
 *  - Transparent aspect-locked photo frame measuring REAL window pixels -> drives the single
 *    GPU `PhotoTextureRenderer`, `TaskEnergyRibbonRenderer`, and `PerimeterWaterLightRenderer`
 *  - Centered `正在理解照片` status caption cleanly positioned between photo bottom and shared Orb
 *  - Default-collapsed bottom-right floating control pill (Play/Pause, Restart, Stage+ms, KF01–KF06,
 *    V4 G1 GPU Diagnostic Modes, Layer Isolation toggles, real GPU draw telemetry, and Evidence Export)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRecognitionDemoScreen(
  evidence: TaskEnergyEvidence,
  visualLock: TaskVisualFidelityV41,
  debugOptions: TaskRenderDebugOptions,
  captureProgress: TaskGpuCaptureProgress,
  isControlsExpanded: Boolean,
  onBackToOrb: () -> Unit,
  onToggleControls: () -> Unit,
  onCloseControls: () -> Unit,
  onTogglePlayPause: () -> Unit,
  onRestart: () -> Unit,
  onJumpKeyframe: (TaskEnergyStage) -> Unit,
  onUpdateDebugOptions: (TaskRenderDebugOptions) -> Unit,
  onExport6KfEvidence: () -> Unit,
  onPhotoWindowBoundsChanged: (Float, Float, Float, Float, Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val snapshot = evidence.taskSnapshot
  val drawStats = evidence.drawTelemetry

  BackHandler(enabled = true) {
    if (isControlsExpanded) {
      onCloseControls()
    } else {
      onBackToOrb()
    }
  }

  BoxWithConstraints(
    modifier = modifier.fillMaxSize(),
  ) {
    val maxAvailHeight = maxHeight
    val viewWidthPx = with(density) { maxWidth.toPx() }
    val viewHeightPx = with(density) { maxAvailHeight.toPx() }
    val aspectFit = visualLock.aspectFit(viewWidthPx.toInt(), viewHeightPx.toInt())
    val scaleDp = with(density) { aspectFit.scalePx.toDp() }
    val photoWidthDp = scaleDp * visualLock.photo.width
    val photoHeightDp = scaleDp * visualLock.photo.height / visualLock.referenceAspect
    val photoLeftDp = with(density) { aspectFit.x(visualLock.photo.left).toDp() }
    val photoTopDp = with(density) { aspectFit.y(visualLock.photo.top).toDp() }
    val cornerRadiusDp = photoWidthDp * visualLock.photo.cornerRadiusOfWidth
    val titleCenterYDp = with(density) { aspectFit.y(visualLock.topBar.titleY).toDp() }
    val backCenterXDp = with(density) { aspectFit.x(visualLock.topBar.backX).toDp() }
    val headerColor = Color(android.graphics.Color.parseColor(visualLock.topBar.textColorHex))
    val statusTextColor = Color(android.graphics.Color.parseColor(visualLock.statusTextColorHex))

    // The same reference-aspect fit drives Compose and GPU geometry. Child offsets are
    // overlays and therefore never perturb photo/orb anchors between KF01 and KF06.
    IconButton(
      onClick = onBackToOrb,
      modifier = Modifier
        .align(Alignment.TopStart)
        .offset(x = backCenterXDp - 24.dp, y = titleCenterYDp - 24.dp)
        .minimumInteractiveComponentSize()
        .testTag("task_back_button"),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = stringResource(R.string.cd_task_back),
        tint = headerColor,
        modifier = Modifier.size(24.dp),
      )
    }

    Text(
      text = stringResource(R.string.task_header_title),
      style = MaterialTheme.typography.titleMedium.copy(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
      ),
      color = headerColor,
      textAlign = TextAlign.Center,
      modifier = Modifier
        .align(Alignment.TopCenter)
        .offset(y = titleCenterYDp - 12.dp)
        .fillMaxWidth()
        .height(24.dp)
        .testTag("task_header_title"),
    )

    // Transparent layout anchor only; the actual photo is drawn by PhotoTextureRenderer.
    Box(
      modifier = Modifier
        .align(Alignment.TopStart)
        .offset(x = photoLeftDp, y = photoTopDp)
        .width(photoWidthDp)
        .height(photoHeightDp)
        .testTag("task_photo_frame_anchor")
        .onGloballyPositioned { coords ->
          val rect = coords.boundsInWindow()
          if (rect.width > 8f && rect.height > 8f) {
            val cornerPx: Float = with(density) { cornerRadiusDp.toPx() }
            onPhotoWindowBoundsChanged(rect.left, rect.top, rect.right, rect.bottom, cornerPx)
          }
        },
    )

    val statusCenterYDp = with(density) { aspectFit.y(visualLock.statusText.y).toDp() }
    Text(
      text = stringResource(R.string.task_status_understanding),
      style = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
      ),
      color = statusTextColor,
      textAlign = TextAlign.Center,
      modifier = Modifier
        .align(Alignment.TopCenter)
        .offset(y = statusCenterYDp - 14.dp)
        .fillMaxWidth()
        .height(28.dp)
        .testTag("task_status_understanding"),
    )

    val isEvidenceCaptureActive = captureProgress.state == TaskGpuCaptureState.CAPTURING

    // Default-collapsed bottom-right floating 6KF Playback & V4 GPU Diagnostic Panel
    if (!isEvidenceCaptureActive) Box(
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(end = 14.dp, bottom = 12.dp),
    ) {
      if (!isControlsExpanded) {
        Surface(
          onClick = onToggleControls,
          shape = RoundedCornerShape(24.dp),
          color = FrostedDaylightSurface,
          border = BorderStroke(1.dp, FrostedDaylightBorder),
          shadowElevation = 2.dp,
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("task_controls_toggle_pill"),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Tune,
              contentDescription = stringResource(R.string.cd_task_controls_toggle),
              tint = AquaticInkPrimary,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = snapshot.stage.code,
              style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
              color = AquaticInkPrimary,
            )
          }
        }
      }

      AnimatedVisibility(
        visible = isControlsExpanded,
        enter = fadeIn() + scaleIn(
          initialScale = 0.92f,
          animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        ),
        exit = fadeOut() + scaleOut(targetScale = 0.92f),
      ) {
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = FrostedDaylightSurface,
          border = BorderStroke(1.dp, FrostedDaylightBorder),
          shadowElevation = 6.dp,
          modifier = Modifier
            .widthIn(min = 280.dp, max = 344.dp)
            .heightIn(max = maxAvailHeight * 0.72f)
            .testTag("task_controls_panel"),
        ) {
          Column(
            modifier = Modifier
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            // Header row: Stage + Elapsed ms + Close
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "${snapshot.stage.code} · ${snapshot.stage.displayLabel}",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                  color = AquaticInkPrimary,
                  modifier = Modifier.testTag("task_stage_label"),
                )
                Text(
                  text = String.format(
                    Locale.US,
                    "%dms · rel %.0f%% · L/R %.0f%% · Ev %d/11",
                    snapshot.elapsedMs,
                    snapshot.releaseProgress * 100f,
                    snapshot.leftProgress * 100f,
                    evidence.emittedEvents.size,
                  ),
                  style = MaterialTheme.typography.labelSmall,
                  color = AquaticInkSecondary,
                  modifier = Modifier.testTag("task_elapsed_label"),
                )
                Text(
                  text = String.format(
                    Locale.US,
                    "GPU Vtx %d · Draws %d · FBO %s · GL 0x%x",
                    drawStats.totalVertexCount,
                    drawStats.totalDrawCalls,
                    if (drawStats.fboComplete) "OK" else "WAIT",
                    drawStats.glErrorCode,
                  ),
                  style = MaterialTheme.typography.labelSmall,
                  color = AquaticInkSecondary,
                  modifier = Modifier.testTag("task_gpu_telemetry_label"),
                )
                Text(
                  text = "Background ${evidence.backgroundLoadStatus}",
                  style = MaterialTheme.typography.labelSmall,
                  color = if (evidence.backgroundTextureLoaded) AquaticInkSecondary else Color(0xFF9A4D20),
                  modifier = Modifier.testTag("task_background_asset_status"),
                )
              }
              IconButton(
                onClick = onCloseControls,
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("task_controls_close_button"),
              ) {
                Icon(
                  imageVector = Icons.Filled.Close,
                  contentDescription = stringResource(R.string.cd_task_controls_close),
                  tint = AquaticInkPrimary,
                )
              }
            }

            // Play/Pause + Restart action row (48dp height)
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              FilledTonalButton(
                onClick = onTogglePlayPause,
                colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = AccentAquaticTeal.copy(alpha = 0.16f),
                  contentColor = AquaticInkPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp)
                  .testTag("task_play_pause_button"),
              ) {
                Icon(
                  imageVector = if (snapshot.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = stringResource(
                    if (snapshot.isPaused) R.string.task_action_play else R.string.task_action_pause
                  ),
                  style = MaterialTheme.typography.labelLarge,
                )
              }

              OutlinedButton(
                onClick = onRestart,
                border = BorderStroke(1.dp, AccentAquaticTeal.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AquaticInkPrimary),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 48.dp)
                  .testTag("task_restart_button"),
              ) {
                Icon(
                  imageVector = Icons.Filled.Refresh,
                  contentDescription = stringResource(R.string.cd_task_restart),
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = stringResource(R.string.task_action_restart),
                  style = MaterialTheme.typography.labelLarge,
                )
              }
            }

            // Deterministic KF01–KF06 jump chips (drives live GPU renderers directly!)
            Text(
              text = stringResource(R.string.task_keyframe_jump_label),
              style = MaterialTheme.typography.labelSmall,
              color = AquaticInkSecondary,
            )

            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              TaskEnergyStage.entries.forEach { stage ->
                val selected = snapshot.stage == stage
                FilterChip(
                  selected = selected,
                  onClick = { onJumpKeyframe(stage) },
                  label = {
                    Text(
                      text = stage.code,
                      style = MaterialTheme.typography.labelMedium,
                    )
                  },
                  colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentAquaticTeal,
                    selectedLabelColor = Color.White,
                    containerColor = Color.White.copy(alpha = 0.65f),
                    labelColor = AquaticInkPrimary,
                  ),
                  border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = FrostedDaylightBorder,
                    selectedBorderColor = AccentAquaticTeal,
                  ),
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("task_jump_${stage.code.lowercase(Locale.US)}"),
                )
              }
            }

            HorizontalDivider(color = FrostedDaylightBorder)

            // G1 GPU Visibility Diagnostic Modes (never mutates TaskEnergyController!)
            Text(
              text = stringResource(R.string.task_debug_mode_label),
              style = MaterialTheme.typography.labelSmall,
              color = AquaticInkSecondary,
            )

            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              TaskVisualDebugMode.entries.forEach { mode ->
                val selected = debugOptions.mode == mode
                FilterChip(
                  selected = selected,
                  onClick = { onUpdateDebugOptions(debugOptions.copy(mode = mode)) },
                  label = { Text(text = mode.shortLabel, style = MaterialTheme.typography.labelSmall) },
                  colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentAquaticTeal,
                    selectedLabelColor = Color.White,
                    containerColor = Color.White.copy(alpha = 0.65f),
                    labelColor = AquaticInkPrimary,
                  ),
                  border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = FrostedDaylightBorder,
                    selectedBorderColor = AccentAquaticTeal,
                  ),
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("task_debug_mode_${mode.name.lowercase(Locale.US)}"),
                )
              }
            }

            // Layer Isolation Toggles (Main, Sec, L1, L2, L3, L4)
            Text(
              text = stringResource(R.string.task_layer_isolation_label),
              style = MaterialTheme.typography.labelSmall,
              color = AquaticInkSecondary,
            )

            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              TaskLayerToggleChip("Main", debugOptions.showMainStream, "task_layer_main") {
                onUpdateDebugOptions(debugOptions.copy(showMainStream = !debugOptions.showMainStream))
              }
              TaskLayerToggleChip("Sec", debugOptions.showSecondaryStreams, "task_layer_sec") {
                onUpdateDebugOptions(debugOptions.copy(showSecondaryStreams = !debugOptions.showSecondaryStreams))
              }
              TaskLayerToggleChip("Left Cyan", debugOptions.showSecondaryLeftCyan, "task_layer_left_cyan") {
                onUpdateDebugOptions(debugOptions.copy(showSecondaryLeftCyan = !debugOptions.showSecondaryLeftCyan))
              }
              TaskLayerToggleChip("Right Fine", debugOptions.showSecondaryRightFine, "task_layer_right_fine") {
                onUpdateDebugOptions(debugOptions.copy(showSecondaryRightFine = !debugOptions.showSecondaryRightFine))
              }
              TaskLayerToggleChip("Emerald Veil", debugOptions.showSecondaryEmeraldVeil, "task_layer_emerald_veil") {
                onUpdateDebugOptions(debugOptions.copy(showSecondaryEmeraldVeil = !debugOptions.showSecondaryEmeraldVeil))
              }
              TaskLayerToggleChip("L1", debugOptions.showL1, "task_layer_l1") {
                onUpdateDebugOptions(debugOptions.copy(showL1 = !debugOptions.showL1))
              }
              TaskLayerToggleChip("L2", debugOptions.showL2, "task_layer_l2") {
                onUpdateDebugOptions(debugOptions.copy(showL2 = !debugOptions.showL2))
              }
              TaskLayerToggleChip("L3", debugOptions.showL3, "task_layer_l3") {
                onUpdateDebugOptions(debugOptions.copy(showL3 = !debugOptions.showL3))
              }
              TaskLayerToggleChip("L4", debugOptions.showL4, "task_layer_l4") {
                onUpdateDebugOptions(debugOptions.copy(showL4 = !debugOptions.showL4))
              }
            }

            // One-click Zero-UI GL-Thread 6KF V4 Evidence Export
            val isCapturing = captureProgress.state == TaskGpuCaptureState.CAPTURING
            FilledTonalButton(
              onClick = onExport6KfEvidence,
              enabled = !isCapturing,
              colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = AccentAquaticTeal.copy(alpha = 0.18f),
                contentColor = AquaticInkPrimary,
              ),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("task_export_v4_evidence_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.CameraAlt,
                contentDescription = stringResource(R.string.cd_export_task6kf_v4_evidence),
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = if (isCapturing) {
                  stringResource(
                    R.string.qa_export_task6kf_capturing,
                    captureProgress.completedSteps,
                    captureProgress.totalSteps,
                  )
                } else {
                  stringResource(R.string.qa_export_task6kf_evidence)
                },
                style = MaterialTheme.typography.labelLarge,
              )
            }

            if (captureProgress.statusMessage.isNotBlank()) {
              Text(
                text = captureProgress.statusMessage,
                style = MaterialTheme.typography.labelSmall,
                color = AquaticInkSecondary,
                modifier = Modifier.testTag("task_export_v4_status"),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TaskLayerToggleChip(
  label: String,
  selected: Boolean,
  tag: String,
  onClick: () -> Unit,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(text = label, style = MaterialTheme.typography.labelSmall) },
    colors = FilterChipDefaults.filterChipColors(
      selectedContainerColor = AccentAquaticTeal,
      selectedLabelColor = Color.White,
      containerColor = Color.White.copy(alpha = 0.65f),
      labelColor = AquaticInkPrimary,
    ),
    border = FilterChipDefaults.filterChipBorder(
      enabled = true,
      selected = selected,
      borderColor = FrostedDaylightBorder,
      selectedBorderColor = AccentAquaticTeal,
    ),
    modifier = Modifier
      .heightIn(min = 48.dp)
      .testTag(tag),
  )
}
