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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.QualityTier
import com.example.orb.config.RenderDebugMode
import com.example.orb.v3.GpuCaptureState
import com.example.orb.v3.OrbV3LoadState
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Collapsible top-right Debug Controls overlay for AI Orb Active Demo.
 * Collapsed by default so the landing viewport displays purely the 3D AI Orb.
 */
@Composable
fun AIOrbControlsOverlay(
  uiState: AIOrbUiState,
  onToggleControls: () -> Unit,
  onCloseControls: () -> Unit,
  onUpdateConfig: ((AIOrbConfig) -> AIOrbConfig) -> Unit,
  onTogglePause: () -> Unit,
  onReset: () -> Unit,
  onSelectQuality: (QualityTier) -> Unit,
  onSelectDebugMode: (RenderDebugMode) -> Unit = {},
  onOpenExportDialog: () -> Unit,
  onDismissExportDialog: () -> Unit,
  onExportV32Evidence: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val clipboardManager = LocalClipboardManager.current
  val copiedMsg = stringResource(R.string.copied_snackbar)

  BackHandler(enabled = uiState.isControlsExpanded) {
    onCloseControls()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .statusBarsPadding()
      .displayCutoutPadding()
      .navigationBarsPadding()
      .padding(14.dp),
  ) {
    // Optional QA Motion Telemetry HUD (strictly OFF by default; only for QA rotation/perf checks)
    if (uiState.config.showMotionOverlay) {
      val tel = uiState.telemetry
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        modifier = Modifier
          .align(Alignment.TopStart)
          .testTag("motion_telemetry_overlay"),
      ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
          Text(
            text = String.format(
              Locale.US,
              "%s · %s",
              uiState.config.debugMode.name,
              tel.renderingBackendLabel,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (tel.isSoftwareRenderer) {
              MaterialTheme.colorScheme.secondary
            } else {
              MaterialTheme.colorScheme.primary
            },
          )
          Text(
            text = String.format(
              Locale.US,
              "CORE %.1f° (%.1f°/s) · %d FPS · P50 %.1fms · P95 %.1fms",
              tel.coreAngleDeg,
              tel.coreAngularVelDegPerSec,
              tel.fps,
              tel.frameMsP50,
              tel.frameMsP95,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "FBO: ${tel.fboFormatLabel} · ${tel.glVendor} / ${tel.glRenderer}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "${tel.glVersion} · EGL ${tel.eglVersion}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = String.format(
              Locale.US,
              "V3 VOL %s (%s) · %.1f MiB · dec %.1fms · up %.1fms",
              tel.volumeDimensions,
              tel.volumeLoadState.label,
              tel.gpuVolumeMemoryMiB,
              tel.webpDecodeMs,
              tel.texture3dUploadMs,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (tel.volumeLoadState == OrbV3LoadState.V3_ASSET_ERROR) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.secondary
            },
          )
        }
      }
    }

    // Subtle low-presence Settings gear icon button in bottom-right corner
    AnimatedVisibility(
      visible = !uiState.isControlsExpanded,
      enter = fadeIn() + scaleIn(initialScale = 0.85f),
      exit = fadeOut() + scaleOut(targetScale = 0.85f),
      modifier = Modifier.align(Alignment.BottomEnd),
    ) {
      Surface(
        onClick = onToggleControls,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        tonalElevation = 2.dp,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .size(48.dp)
          .testTag("toggle_debug_controls_button"),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = stringResource(R.string.cd_open_controls),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }

    // Expanded Debug Controls Panel anchored at bottom-right
    AnimatedVisibility(
      visible = uiState.isControlsExpanded,
      enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
        scaleIn(initialScale = 0.94f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
      exit = fadeOut(androidx.compose.animation.core.tween(durationMillis = 70)),
      modifier = Modifier.align(Alignment.BottomEnd),
    ) {
      DebugControlPanel(
        uiState = uiState,
        onClose = onCloseControls,
        onUpdateConfig = onUpdateConfig,
        onTogglePause = onTogglePause,
        onReset = onReset,
        onSelectQuality = onSelectQuality,
        onSelectDebugMode = onSelectDebugMode,
        onOpenExportDialog = onOpenExportDialog,
        onExportV32Evidence = onExportV32Evidence,
      )
    }

    SnackbarHost(
      hostState = snackbarHostState,
      modifier = Modifier.align(Alignment.BottomCenter),
    )
  }

  if (uiState.showExportDialog) {
    val jsonText = remember(uiState.config) { uiState.config.toJson() }
    AlertDialog(
      onDismissRequest = onDismissExportDialog,
      containerColor = MaterialTheme.colorScheme.background,
      title = {
        Text(
          text = stringResource(R.string.export_dialog_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      },
      text = {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
        ) {
          Text(
            text = jsonText,
            style = MaterialTheme.typography.labelMedium.copy(
              fontFamily = FontFamily.Monospace,
              lineHeight = 16.sp,
            ),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
              .verticalScroll(rememberScrollState())
              .padding(12.dp)
              .testTag("export_json_preview"),
          )
        }
      },
      confirmButton = {
        FilledTonalButton(
          onClick = {
            clipboardManager.setText(AnnotatedString(jsonText))
            onDismissExportDialog()
            scope.launch { snackbarHostState.showSnackbar(copiedMsg) }
          },
          modifier = Modifier.testTag("copy_json_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.ContentCopy,
            contentDescription = stringResource(R.string.cd_copy_json),
            modifier = Modifier.size(16.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_copy))
        }
      },
      dismissButton = {
        TextButton(
          onClick = onDismissExportDialog,
          modifier = Modifier.testTag("close_json_dialog_button"),
        ) {
          Text(stringResource(R.string.action_close))
        }
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebugControlPanel(
  uiState: AIOrbUiState,
  onClose: () -> Unit,
  onUpdateConfig: ((AIOrbConfig) -> AIOrbConfig) -> Unit,
  onTogglePause: () -> Unit,
  onReset: () -> Unit,
  onSelectQuality: (QualityTier) -> Unit,
  onSelectDebugMode: (RenderDebugMode) -> Unit,
  onOpenExportDialog: () -> Unit,
  onExportV32Evidence: () -> Unit,
) {
  val cfg = uiState.config

  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    tonalElevation = 8.dp,
    modifier = Modifier
      .widthIn(min = 280.dp, max = 336.dp)
      .heightIn(max = 620.dp)
      .testTag("debug_controls_panel"),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      // Panel Header + FPS Pill + Close Button
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.controls_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = stringResource(R.string.controls_subtitle),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        // Live FPS telemetry chip
        Box(
          modifier = Modifier
            .background(
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
              shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("fps_badge"),
        ) {
          Text(
            text = "${uiState.fps} FPS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }

        Spacer(Modifier.width(4.dp))

        IconButton(
          onClick = onClose,
          modifier = Modifier
            .size(48.dp)
            .testTag("close_debug_controls_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.cd_close_controls),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(Modifier.height(8.dp))

      // Quick Actions Row: Pause/Resume, Reset, Export JSON
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        FilledTonalButton(
          onClick = onTogglePause,
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (cfg.isPaused) {
              MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
            } else {
              MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            },
            contentColor = if (cfg.isPaused) {
              MaterialTheme.colorScheme.secondary
            } else {
              MaterialTheme.colorScheme.primary
            },
          ),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("pause_resume_button"),
        ) {
          Icon(
            imageVector = if (cfg.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = stringResource(
              if (cfg.isPaused) R.string.cd_resume_animation else R.string.cd_pause_animation
            ),
            modifier = Modifier.size(16.dp),
          )
          Spacer(Modifier.width(4.dp))
          Text(
            text = stringResource(if (cfg.isPaused) R.string.action_resume else R.string.action_pause),
            style = MaterialTheme.typography.labelSmall,
          )
        }

        OutlinedButton(
          onClick = onReset,
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("reset_parameters_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = stringResource(R.string.cd_reset_parameters),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(16.dp),
          )
        }

        OutlinedButton(
          onClick = onOpenExportDialog,
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("export_json_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.DataObject,
            contentDescription = stringResource(R.string.cd_export_json),
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(16.dp),
          )
        }
      }

      Spacer(Modifier.height(10.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
      Spacer(Modifier.height(8.dp))

      // Scrollable Parameter Controls
      Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
      ) {
        // Quality Tier Selector
        Text(
          text = stringResource(R.string.quality_label),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.secondary,
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          QualityTier.entries.forEach { tier ->
            val selected = cfg.quality == tier
            FilterChip(
              selected = selected,
              onClick = { onSelectQuality(tier) },
              label = {
                Text(
                  text = when (tier) {
                    QualityTier.LOW -> stringResource(R.string.quality_low)
                    QualityTier.MEDIUM -> stringResource(R.string.quality_medium)
                    QualityTier.HIGH -> stringResource(R.string.quality_high)
                  },
                  style = MaterialTheme.typography.labelSmall,
                )
              },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                selectedLabelColor = MaterialTheme.colorScheme.primary,
              ),
              modifier = Modifier
                .weight(1f)
                .testTag("quality_${tier.name.lowercase(Locale.US)}_chip"),
            )
          }
        }

        SectionHeader(stringResource(R.string.section_core))
        ParameterSlider(
          label = stringResource(R.string.param_core_scale),
          value = cfg.coreScale,
          range = 0.60f..1.35f,
          testTag = "slider_core_scale",
          onValueChange = { v -> onUpdateConfig { it.copy(coreScale = v) } },
        )
        ParameterSlider(
          label = stringResource(R.string.param_core_shell_scale),
          value = cfg.coreShellScale,
          range = 0.55f..1.15f,
          testTag = "slider_core_shell_scale",
          onValueChange = { v -> onUpdateConfig { it.copy(coreShellScale = v) } },
        )
        ParameterSlider(
          label = stringResource(R.string.param_core_transparency),
          value = cfg.coreTransparency,
          range = 0.35f..0.98f,
          testTag = "slider_core_transparency",
          onValueChange = { v -> onUpdateConfig { it.copy(coreTransparency = v) } },
        )
        ParameterSlider(
          label = stringResource(R.string.param_refraction),
          value = cfg.refraction,
          range = 0.00f..1.40f,
          testTag = "slider_refraction",
          onValueChange = { v -> onUpdateConfig { it.copy(refraction = v) } },
        )

        SectionHeader(stringResource(R.string.section_fluid))
        ParameterSlider(
          label = stringResource(R.string.param_fluid_speed),
          value = cfg.fluidSpeed,
          range = 0.00f..2.50f,
          testTag = "slider_fluid_speed",
          onValueChange = { v -> onUpdateConfig { it.copy(fluidSpeed = v) } },
        )
        ParameterSlider(
          label = stringResource(R.string.param_fluid_intensity),
          value = cfg.fluidIntensity,
          range = 0.20f..2.00f,
          testTag = "slider_fluid_intensity",
          onValueChange = { v -> onUpdateConfig { it.copy(fluidIntensity = v) } },
        )

        SectionHeader(stringResource(R.string.section_ribbons))
        ParameterSlider(
          label = stringResource(R.string.param_ribbon_count),
          value = cfg.ribbonCount.toFloat(),
          range = AIOrbConfig.MIN_RIBBON_COUNT.toFloat()..AIOrbConfig.MAX_RIBBON_COUNT.toFloat(),
          steps = AIOrbConfig.MAX_RIBBON_COUNT - AIOrbConfig.MIN_RIBBON_COUNT - 1,
          valueFormatter = { it.roundToInt().toString() },
          testTag = "slider_ribbon_count",
          onValueChange = { v ->
            onUpdateConfig {
              it.copy(
                ribbonCount = v.roundToInt().coerceIn(
                  AIOrbConfig.MIN_RIBBON_COUNT,
                  AIOrbConfig.MAX_RIBBON_COUNT,
                )
              )
            }
          },
        )
        ParameterSlider(
          label = stringResource(R.string.param_ribbon_width),
          value = cfg.ribbonWidth,
          range = 0.40f..1.80f,
          testTag = "slider_ribbon_width",
          onValueChange = { v -> onUpdateConfig { it.copy(ribbonWidth = v) } },
        )
        ParameterSlider(
          label = stringResource(R.string.param_ribbon_speed),
          value = cfg.ribbonSpeed,
          range = 0.00f..2.50f,
          testTag = "slider_ribbon_speed",
          onValueChange = { v -> onUpdateConfig { it.copy(ribbonSpeed = v) } },
        )
        ParameterSlider(
          label = stringResource(R.string.param_ribbon_transparency),
          value = cfg.ribbonTransparency,
          range = 0.25f..1.00f,
          testTag = "slider_ribbon_transparency",
          onValueChange = { v -> onUpdateConfig { it.copy(ribbonTransparency = v) } },
        )

        SectionHeader(stringResource(R.string.section_optics))
        ParameterSlider(
          label = stringResource(R.string.param_energy_intensity),
          value = cfg.energyIntensity,
          range = 0.00f..2.00f,
          testTag = "slider_energy_intensity",
          onValueChange = { v -> onUpdateConfig { it.copy(energyIntensity = v) } },
        )
        ParameterSlider(
          label = stringResource(R.string.param_particle_density),
          value = cfg.particleDensity,
          range = 0.00f..1.50f,
          testTag = "slider_particle_density",
          onValueChange = { v -> onUpdateConfig { it.copy(particleDensity = v) } },
        )
        ParameterSlider(
          label = stringResource(R.string.param_bloom),
          value = cfg.bloom,
          range = 0.00f..1.40f,
          testTag = "slider_bloom",
          onValueChange = { v -> onUpdateConfig { it.copy(bloom = v) } },
        )
        ParameterSlider(
          label = stringResource(R.string.param_overall_intensity),
          value = cfg.overallIntensity,
          range = 0.40f..1.60f,
          testTag = "slider_overall_intensity",
          onValueChange = { v -> onUpdateConfig { it.copy(overallIntensity = v) } },
        )

        SectionHeader(stringResource(R.string.section_qa))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = stringResource(R.string.qa_isolate_core),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Switch(
            checked = cfg.isolateCoreDebug,
            onCheckedChange = { checked -> onUpdateConfig { it.copy(isolateCoreDebug = checked) } },
            modifier = Modifier.testTag("switch_isolate_core"),
          )
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = stringResource(R.string.qa_motion_overlay),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Switch(
            checked = cfg.showMotionOverlay,
            onCheckedChange = { checked -> onUpdateConfig { it.copy(showMotionOverlay = checked) } },
            modifier = Modifier.testTag("switch_motion_overlay"),
          )
        }

        Spacer(Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.qa_render_mode_label),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          RenderDebugMode.entries.forEach { mode ->
            FilterChip(
              selected = cfg.debugMode == mode,
              onClick = { onSelectDebugMode(mode) },
              label = {
                Text(
                  text = mode.displayLabel,
                  style = MaterialTheme.typography.labelSmall,
                )
              },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.24f),
                selectedLabelColor = MaterialTheme.colorScheme.secondary,
              ),
              modifier = Modifier.testTag("debug_mode_${mode.name.lowercase(Locale.US)}_chip"),
            )
          }
        }

        // Gate 4: One-click 6-frame real GPU Evidence Export (glReadPixels, zero UI overlay)
        Spacer(Modifier.height(6.dp))
        val capture = uiState.gpuCaptureProgress
        val isCapturing = capture.state == GpuCaptureState.CAPTURING
        FilledTonalButton(
          onClick = onExportV32Evidence,
          enabled = !isCapturing,
          colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f),
            contentColor = MaterialTheme.colorScheme.secondary,
          ),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("export_v32_evidence_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.CameraAlt,
            contentDescription = stringResource(R.string.cd_export_v32_evidence),
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = if (isCapturing) {
              stringResource(
                R.string.qa_export_v32_capturing,
                capture.completedSteps,
                capture.totalSteps,
              )
            } else {
              stringResource(R.string.qa_export_v32_evidence)
            },
            style = MaterialTheme.typography.labelMedium,
          )
        }
        if (capture.statusMessage.isNotBlank()) {
          Text(
            text = capture.statusMessage,
            style = MaterialTheme.typography.labelSmall,
            color = if (capture.state == GpuCaptureState.FAILED) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.primary
            },
            modifier = Modifier
              .padding(top = 2.dp)
              .testTag("export_v32_evidence_status"),
          )
        }

        // Compact GPU Runtime Info card (GL/EGL/FBO/HW-vs-SW/FPS/P50/P95/Mode)
        Spacer(Modifier.height(6.dp))
        val tel = uiState.telemetry
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("gpu_runtime_info_card"),
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(10.dp),
          ) {
            Text(
              text = "GPU RUNTIME · ${tel.renderingBackendLabel}",
              style = MaterialTheme.typography.labelMedium,
              color = if (tel.isSoftwareRenderer) {
                MaterialTheme.colorScheme.secondary
              } else {
                MaterialTheme.colorScheme.primary
              },
            )
            Text(
              text = "Mode: ${cfg.debugMode.name}  |  FBO: ${tel.fboFormatLabel}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = String.format(
                Locale.US,
                "Perf: %d FPS  |  P50 %.1f ms  |  P95 %.1f ms",
                tel.fps,
                tel.frameMsP50,
                tel.frameMsP95,
              ),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "GL_VENDOR: ${tel.glVendor}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = "GL_RENDERER: ${tel.glRenderer}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = "GL_VERSION: ${tel.glVersion}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = "EGL_VERSION: ${tel.eglVersion}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = String.format(
                Locale.US,
                "V3 WebP 3D Vol: %s | %.2f MiB | Dec %.1fms | Up %.1fms",
                tel.volumeDimensions,
                tel.gpuVolumeMemoryMiB,
                tel.webpDecodeMs,
                tel.texture3dUploadMs,
              ),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = "Asset Status [${tel.volumeLoadState.label}]: ${tel.volumeStatus}",
              style = MaterialTheme.typography.labelSmall,
              color = if (tel.volumeLoadState == OrbV3LoadState.V3_ASSET_ERROR) {
                MaterialTheme.colorScheme.error
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(title: String) {
  Spacer(Modifier.height(6.dp))
  Text(
    text = title.uppercase(Locale.US),
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.secondary,
  )
}

@Composable
private fun ParameterSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  testTag: String,
  steps: Int = 0,
  valueFormatter: (Float) -> String = { String.format(Locale.US, "%.2f", it) },
  onValueChange: (Float) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = valueFormatter(value),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Slider(
      value = value,
      onValueChange = onValueChange,
      valueRange = range,
      steps = steps,
      colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = Color.White.copy(alpha = 0.14f),
      ),
      modifier = Modifier
        .fillMaxWidth()
        .height(34.dp)
        .testTag(testTag),
    )
  }
}
