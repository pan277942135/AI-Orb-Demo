package com.example.orb.ui

import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.LaunchedEffect
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
import com.example.orb.task.Tab2DebugOptions
import com.example.orb.task.Tab2LayoutGeometry
import com.example.orb.task.Tab2SafeInsetsPx
import com.example.orb.task.Tab2ShaderDebugMode
import com.example.orb.task.Tab2WaterWorldRuntimeStatus
import java.util.Locale

private val AquaticInkPrimary = Color(0xFF0B4657)
private val AquaticInkSecondary = Color(0xFF155D70)
private val FrostedDaylightSurface = Color(0xE0F2FCFF)
private val FrostedDaylightBorder = Color(0x888ED7E6)
private val AccentAquaticTeal = Color(0xFF0A8FA8)

/**
 * Tab 2 V2.2 (`照片水光`) transparent Compose overlay matching the high-fidelity closure spec:
 *  - Top bar: `<` back arrow + centered `渔获识别` + `更换照片` action button (NO glass background/card container!)
 *  - Transparent aspect-locked photo frame measuring real window pixels via [Tab2LayoutGeometry]
 *  - Centered `正在识别鱼种` status caption between photo bottom and lower Water World ecosystem
 *  - Default-collapsed bottom-right floating control pill (Play/Pause, Restart, L1–L4 toggles, Refraction toggle, Shader debug modes)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRecognitionDemoScreen(
  debugOptions: Tab2DebugOptions,
  isControlsExpanded: Boolean,
  onBackToOrb: () -> Unit,
  onToggleControls: () -> Unit,
  onCloseControls: () -> Unit,
  onTogglePlayPause: () -> Unit,
  onRestart: () -> Unit,
  onUpdateDebugOptions: (Tab2DebugOptions) -> Unit,
  onPhotoWindowBoundsChanged: (Float, Float, Float, Float, Float) -> Unit,
  waterWorldStatus: Tab2WaterWorldRuntimeStatus? = null,
  onPickPhoto: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current.density

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
    val maxAvailHeight = maxHeight.value * density
    val maxAvailWidth = maxWidth.value * density

    val layoutSnapshot = Tab2LayoutGeometry.compute(
      surfaceWidthPx = maxAvailWidth.toInt(),
      surfaceHeightPx = maxAvailHeight.toInt(),
      density = density,
      safeInsets = Tab2SafeInsetsPx(),
    )
    val photo = layoutSnapshot.photoFrame
    val cornerRadiusDp = (photo.clampedCornerRadiusPx / density).dp

    // Notify GL renderer/coordinator of exact window bounds
    LaunchedEffect(photo.leftPx, photo.topPx, photo.rightPx, photo.bottomPx, photo.clampedCornerRadiusPx) {
      if (photo.isValid()) {
        onPhotoWindowBoundsChanged(photo.leftPx, photo.topPx, photo.rightPx, photo.bottomPx, photo.clampedCornerRadiusPx)
      }
    }

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxSize()
        .padding(top = 8.dp),
    ) {
      // Top navigation row (48–52dp height, NO glass button/card/decorative container!)
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .widthIn(max = 600.dp)
          .padding(horizontal = 8.dp)
          .height(50.dp),
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

        if (onPickPhoto != null) {
          Surface(
            onClick = onPickPhoto,
            shape = RoundedCornerShape(16.dp),
            color = FrostedDaylightSurface,
            border = BorderStroke(1.dp, FrostedDaylightBorder),
            modifier = Modifier
              .align(Alignment.CenterEnd)
              .padding(end = 6.dp)
              .heightIn(min = 48.dp)
              .testTag("tab2_pick_photo_button"),
          ) {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
              Text(
                text = stringResource(R.string.task_pick_photo),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = AquaticInkPrimary,
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Transparent aspect-locked Photo Frame container: reports exact window pixels
      Box(
        modifier = Modifier
          .width((photo.widthPx / density).dp)
          .aspectRatio(PhotoFrameGeometry.CANONICAL_ASPECT_W_OVER_H)
          .testTag("task_photo_frame_anchor")
          .onGloballyPositioned { coords ->
            val rect = coords.boundsInWindow()
            if (rect.width > 8f && rect.height > 8f) {
              val cornerPx = photo.clampedCornerRadiusPx
              onPhotoWindowBoundsChanged(rect.left, rect.top, rect.right, rect.bottom, cornerPx)
            }
          },
      )

      Spacer(modifier = Modifier.height(16.dp))

      // Centered recognition status caption directly beneath photo & above lower Water World sphere
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

      if (waterWorldStatus != null && waterWorldStatus.errorMessage != null) {
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = Color(0xD9FFE5E5),
          border = BorderStroke(1.dp, Color(0xFFE07A7A)),
          modifier = Modifier
            .padding(horizontal = 24.dp)
            .testTag("tab2_water_world_error_badge"),
        ) {
          Text(
            text = waterWorldStatus.errorMessage,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF7A1C1C),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      } else if (waterWorldStatus != null && !waterWorldStatus.isReady) {
        Spacer(modifier = Modifier.height(6.dp))
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = FrostedDaylightSurface,
          border = BorderStroke(1.dp, FrostedDaylightBorder),
          modifier = Modifier.testTag("tab2_water_world_loading_badge"),
        ) {
          Text(
            text = stringResource(R.string.tab2_water_world_loading),
            style = MaterialTheme.typography.labelSmall,
            color = AquaticInkPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
          )
        }
      }
    }

    // Default-collapsed bottom-right floating Tab 2 Diagnostics & Debug Panel
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
              text = when {
                waterWorldStatus == null -> "Tab 2 WW"
                waterWorldStatus.isReady -> "WW · ${waterWorldStatus.passCount}P"
                else -> "WW…"
              },
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
            .heightIn(max = (maxAvailHeight * 0.72f / density).dp)
            .testTag("task_controls_panel"),
        ) {
          Column(
            modifier = Modifier
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            // Header row: Title + Close
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = stringResource(R.string.task_controls_title),
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                  color = AquaticInkPrimary,
                  modifier = Modifier.testTag("task_stage_label"),
                )
                Text(
                  text = String.format(
                    Locale.US,
                    "Passes %d · Optics %d · Core %s",
                    waterWorldStatus?.passCount ?: 0,
                    waterWorldStatus?.opticalNodeCount ?: 0,
                    if (waterWorldStatus?.isCoreVolumeReady == true) "READY" else "INIT",
                  ),
                  style = MaterialTheme.typography.labelSmall,
                  color = AquaticInkSecondary,
                  modifier = Modifier.testTag("task_elapsed_label"),
                )
                Text(
                  text = String.format(
                    Locale.US,
                    "Photo Tex %s · Refr %s · Sate %s",
                    if (waterWorldStatus?.photoTextureLoaded == true) "OK" else "DEMO",
                    if (waterWorldStatus?.refractionEnabled == true) "ON" else "OFF",
                    waterWorldStatus?.perimeterState?.name ?: "READY",
                  ),
                  style = MaterialTheme.typography.labelSmall,
                  color = AquaticInkSecondary,
                  modifier = Modifier.testTag("task_gpu_telemetry_label"),
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
                  imageVector = if (waterWorldStatus?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = stringResource(
                    if (waterWorldStatus?.isPlaying == true) R.string.task_action_pause else R.string.task_action_play
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

            HorizontalDivider(color = FrostedDaylightBorder)

            // Shader Debug Modes (Normal, Geometry Magenta, Alpha Heatmap)
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
              Tab2ShaderDebugMode.entries.forEach { mode ->
                val selected = debugOptions.shaderMode == mode
                FilterChip(
                  selected = selected,
                  onClick = { onUpdateDebugOptions(debugOptions.copy(shaderMode = mode)) },
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

            // Layer & Refraction Toggles (L1, L2, L3, L4, Refr)
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
              TaskLayerToggleChip("Refr", debugOptions.enableRefraction, "task_layer_refr") {
                onUpdateDebugOptions(debugOptions.copy(enableRefraction = !debugOptions.enableRefraction))
              }
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
