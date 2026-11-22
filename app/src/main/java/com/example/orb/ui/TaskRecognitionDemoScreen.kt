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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import java.util.Locale

private val AquaticInkPrimary = Color(0xFF0B4657)
private val AquaticInkSecondary = Color(0xFF155D70)
private val FrostedDaylightSurface = Color(0xD6F2FCFF)
private val FrostedDaylightBorder = Color(0x888ED7E6)
private val AccentAquaticTeal = Color(0xFF0A8FA8)

/**
 * Tab 2 (`照片水光`) transparent Compose overlay matching the frozen 6-keyframe overview:
 *  - Top bar: `<` back arrow + centered `拍照识鱼`
 *  - Transparent aspect-locked photo frame measuring REAL window pixels -> drives the single
 *    GPU `PhotoTextureRenderer`, `TaskEnergyRibbonRenderer`, and `PerimeterWaterLightRenderer`
 *  - Centered `正在理解照片` status caption cleanly positioned between photo bottom and shared Orb
 *  - Default-collapsed bottom-right floating control pill (Play/Pause, Restart, Stage+ms, KF01–KF06)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRecognitionDemoScreen(
  evidence: TaskEnergyEvidence,
  isControlsExpanded: Boolean,
  onBackToOrb: () -> Unit,
  onToggleControls: () -> Unit,
  onCloseControls: () -> Unit,
  onTogglePlayPause: () -> Unit,
  onRestart: () -> Unit,
  onJumpKeyframe: (TaskEnergyStage) -> Unit,
  onPhotoWindowBoundsChanged: (Float, Float, Float, Float, Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val snapshot = evidence.taskSnapshot

  BackHandler(enabled = true) {
    if (isControlsExpanded) {
      onCloseControls()
    } else {
      onBackToOrb()
    }
  }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .statusBarsPadding()
      .displayCutoutPadding()
      .navigationBarsPadding(),
  ) {
    val maxAvailHeight = maxHeight
    val maxAvailWidth = maxWidth
    // Match frozen overview photo proportion (~75.7% width, capped cleanly for tablets/landscape)
    // while guaranteeing generous bottom clearance (~34% height) for status text + shared V3 Orb!
    val targetPhotoWidth = minOf(
      maxAvailWidth * PhotoFrameGeometry.CANONICAL_WIDTH_RATIO,
      (maxAvailHeight * 0.50f) * PhotoFrameGeometry.CANONICAL_ASPECT_W_OVER_H,
      440.dp,
    ).coerceAtLeast(140.dp)
    val cornerRadiusDp = targetPhotoWidth * PhotoFrameGeometry.CANONICAL_CORNER_RADIUS_OF_W

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxSize()
        .padding(top = 56.dp), // Clean clearance below top floating Segmented Tab Switcher
    ) {
      // Top navigation row: `<` + centered `拍照识鱼`
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 600.dp)
          .padding(horizontal = 8.dp)
          .height(48.dp),
      ) {
        IconButton(
          onClick = onBackToOrb,
          modifier = Modifier
            .align(Alignment.CenterStart)
            .minimumInteractiveComponentSize()
            .testTag("task_back_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_task_back),
            tint = AquaticInkPrimary,
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
          color = AquaticInkPrimary,
          textAlign = TextAlign.Center,
          modifier = Modifier
            .align(Alignment.Center)
            .testTag("task_header_title"),
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      // Transparent aspect-locked Photo Frame container: reports EXACT physical window pixels
      // to AIOrbScene so GPU photo quad, Task contact point, and Perimeter WaterLight align to 0px!
      Box(
        modifier = Modifier
          .width(targetPhotoWidth)
          .aspectRatio(PhotoFrameGeometry.CANONICAL_ASPECT_W_OVER_H)
          .testTag("task_photo_frame_anchor")
          .onGloballyPositioned { coords ->
            val rect = coords.boundsInWindow()
            if (rect.width > 8f && rect.height > 8f) {
              val cornerPx: Float = with(density) { cornerRadiusDp.toPx() }
              onPhotoWindowBoundsChanged(rect.left, rect.top, rect.right, rect.bottom, cornerPx)
            }
          },
      )

      Spacer(modifier = Modifier.height(18.dp))

      // Centered recognition status caption directly beneath photo & above shared V3 AI Orb
      Text(
        text = stringResource(R.string.task_status_understanding),
        style = MaterialTheme.typography.bodyLarge.copy(
          fontSize = 15.sp,
          fontWeight = FontWeight.Medium,
          letterSpacing = 0.6.sp,
        ),
        color = AquaticInkSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .padding(horizontal = 24.dp)
          .testTag("task_status_understanding"),
      )
    }

    // Default-collapsed bottom-right floating 6KF Playback & Keyframe Control Pill
    Box(
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
            .widthIn(min = 272.dp, max = 336.dp)
            .testTag("task_controls_panel"),
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
          }
        }
      }
    }
  }
}
