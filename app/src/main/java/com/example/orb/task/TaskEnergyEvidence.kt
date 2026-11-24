package com.example.orb.task

import java.util.Locale

/**
 * Tab 2 (`TASK_PERIMETER_DEMO`) GPU visibility & rendering diagnostic modes.
 *
 * Strictly affects GPU rendering passes ONLY — changing debug modes MUST NEVER mutate
 * `TaskEnergyController` elapsed time, `releaseProgress`, `leftProgress`, `rightProgress`,
 * `coverageComplete`, `flowTimeSec`, or emitted milestone events.
 */
enum class TaskVisualDebugMode(
  val shaderDebugId: Int,
  val shortLabel: String,
  val taskShaderDebugId: Int = 0,
  val perimeterShaderDebugId: Int = 0,
) {
  NORMAL(0, "Normal"),
  GEOMETRY_MAGENTA(1, "Both Magenta", 1, 1),
  ALPHA_HEATMAP(2, "Both Alpha", 2, 2),
  TASK_GEOMETRY_MAGENTA(1, "Task Magenta", 1, 0),
  TASK_ALPHA_HEATMAP(2, "Task Alpha", 2, 0),
  PERIMETER_GEOMETRY_MAGENTA(1, "Perimeter Magenta", 0, 1),
  PERIMETER_ALPHA_HEATMAP(2, "Perimeter Alpha", 0, 2),
  PERIMETER_ONLY_PRE_TONEMAP(0, "Perimeter Pre-Tone"),
  PERIMETER_ONLY_FINAL_COMPOSITE(0, "Perimeter Final"),
  LAYER_ISOLATION(0, "Layers"),
  PRE_TONEMAP(0, "Pre-Tone"),
  FINAL_COMPOSITE(0, "Final"),
}

/**
 * Pure render-time diagnostic configuration for Tab 2 Task Energy & Perimeter WaterLight.
 */
data class TaskRenderDebugOptions(
  val mode: TaskVisualDebugMode = TaskVisualDebugMode.NORMAL,
  val showMainStream: Boolean = true,
  val showSecondaryStreams: Boolean = true,
  val showSecondaryLeftCyan: Boolean = true,
  val showSecondaryRightFine: Boolean = true,
  val showSecondaryEmeraldVeil: Boolean = true,
  val showL1: Boolean = true,
  val showL2: Boolean = true,
  val showL3: Boolean = true,
  val showL4: Boolean = true,
  val disableBloom: Boolean = false,
) {
  val taskShaderDebugId: Int get() = mode.taskShaderDebugId
  val perimeterShaderDebugId: Int get() = mode.perimeterShaderDebugId
  val isShaderVisualization: Boolean
    get() = taskShaderDebugId != 0 || perimeterShaderDebugId != 0

  val isTaskEnergyEnabled: Boolean
    get() = showMainStream || (showSecondaryStreams &&
      (showSecondaryLeftCyan || showSecondaryRightFine || showSecondaryEmeraldVeil))

  fun isTaskStreamEnabled(kind: TaskStreamKind): Boolean = when (kind) {
    TaskStreamKind.MAIN_ICE_STREAM -> showMainStream
    TaskStreamKind.SECONDARY_LEFT_CYAN -> showSecondaryStreams && showSecondaryLeftCyan
    TaskStreamKind.SECONDARY_RIGHT_FINE -> showSecondaryStreams && showSecondaryRightFine
    TaskStreamKind.SECONDARY_EMERALD_VEIL -> showSecondaryStreams && showSecondaryEmeraldVeil
  }

  val isPerimeterEnabled: Boolean
    get() = showL1 || showL2 || showL3 || showL4

  companion object {
    val Default = TaskRenderDebugOptions()
    val GeometryMagenta = TaskRenderDebugOptions(mode = TaskVisualDebugMode.GEOMETRY_MAGENTA, disableBloom = true)
    val AlphaHeatmap = TaskRenderDebugOptions(mode = TaskVisualDebugMode.ALPHA_HEATMAP, disableBloom = true)
    val TaskGeometryMagenta = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.TASK_GEOMETRY_MAGENTA,
      showL1 = false, showL2 = false, showL3 = false, showL4 = false,
      disableBloom = true,
    )
    val TaskAlphaHeatmap = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.TASK_ALPHA_HEATMAP,
      showL1 = false, showL2 = false, showL3 = false, showL4 = false,
      disableBloom = true,
    )
    val PerimeterGeometryMagenta = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.PERIMETER_GEOMETRY_MAGENTA,
      showMainStream = false, showSecondaryStreams = false, showL4 = false,
      disableBloom = true,
    )
    val PerimeterAlphaHeatmap = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.PERIMETER_ALPHA_HEATMAP,
      showMainStream = false, showSecondaryStreams = false, showL4 = false,
      disableBloom = true,
    )
    val PerimeterOnlyPreTonemap = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.PERIMETER_ONLY_PRE_TONEMAP,
      showMainStream = false, showSecondaryStreams = false, showL4 = false,
      disableBloom = true,
    )
    val PerimeterOnlyFinalComposite = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.PERIMETER_ONLY_FINAL_COMPOSITE,
      showMainStream = false, showSecondaryStreams = false, showL4 = false,
    )
    val PreTonemap = TaskRenderDebugOptions(mode = TaskVisualDebugMode.PRE_TONEMAP, disableBloom = true)
    val FinalComposite = TaskRenderDebugOptions(mode = TaskVisualDebugMode.FINAL_COMPOSITE)
    val TaskOff = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.LAYER_ISOLATION,
      showMainStream = false,
      showSecondaryStreams = false,
      showL1 = false,
      showL2 = false,
      showL3 = false,
      showL4 = false,
    )
    val TaskOnly = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.LAYER_ISOLATION,
      showMainStream = true,
      showSecondaryStreams = true,
      showL1 = false,
      showL2 = false,
      showL3 = false,
      showL4 = false,
    )
    val PerimeterOnly = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.LAYER_ISOLATION,
      showMainStream = false,
      showSecondaryStreams = false,
      showL1 = true,
      showL2 = true,
      showL3 = true,
      showL4 = true,
    )
    val L1Only = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.LAYER_ISOLATION,
      showMainStream = false,
      showSecondaryStreams = false,
      showL1 = true,
      showL2 = false,
      showL3 = false,
      showL4 = false,
    )
    val L2Only = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.LAYER_ISOLATION,
      showMainStream = false,
      showSecondaryStreams = false,
      showL1 = false,
      showL2 = true,
      showL3 = false,
      showL4 = false,
    )
    val L3Only = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.LAYER_ISOLATION,
      showMainStream = false,
      showSecondaryStreams = false,
      showL1 = false,
      showL2 = false,
      showL3 = true,
      showL4 = false,
    )
    val L4Only = TaskRenderDebugOptions(
      mode = TaskVisualDebugMode.LAYER_ISOLATION,
      showMainStream = false,
      showSecondaryStreams = false,
      showL1 = false,
      showL2 = false,
      showL3 = false,
      showL4 = true,
    )
  }
}

/**
 * Real GL-thread draw telemetry captured per frame in Tab 2 to verify geometry generation,
 * shader link status, VAO/VBO validity, draw call execution, FBO completeness, and GL error state.
 */
data class TaskGpuDrawTelemetry(
  val taskRibbonVertexCount: Int = 0,
  val taskRibbonDrawCalls: Int = 0,
  val taskRibbonShaderLinked: Boolean = false,
  val taskRibbonVaoIds: List<Int> = emptyList(),
  val taskRibbonVboIds: List<Int> = emptyList(),
  val taskRibbonDrawCallsByStream: Map<String, Int> = emptyMap(),
  val perimeterVertexCount: Int = 0,
  val perimeterDrawCalls: Int = 0,
  val perimeterShaderLinked: Boolean = false,
  val perimeterVaoIds: List<Int> = emptyList(),
  val perimeterVboIds: List<Int> = emptyList(),
  val perimeterDrawCallsByLayer: Map<String, Int> = emptyMap(),
  val sparkleCount: Int = 0,
  val sparkleDrawCalls: Int = 0,
  val sparkleUsedQuadFallback: Boolean = false,
  val maxAliasedPointSizePx: Float = 64f,
  val fboComplete: Boolean = false,
  val fboWidth: Int = 0,
  val fboHeight: Int = 0,
  val glErrorCode: Int = 0,
) {
  val totalVertexCount: Int get() = taskRibbonVertexCount + perimeterVertexCount
  val totalDrawCalls: Int get() = taskRibbonDrawCalls + perimeterDrawCalls + sparkleDrawCalls
  val hasGlError: Boolean get() = glErrorCode != 0
}

/**
 * Runtime diagnostics & evidence recorder verifying:
 *  - Single shared GPU context (`glContextInitCount == 1`)
 *  - Single V3 WebP 3D Volume load (`webpLoadCount <= 1` across tab switches)
 *  - Continuous Orb motion across Tab 1 <-> Tab 2 switches
 *  - Ordered emission of all 11 Task Energy milestone events
 *  - Real displayed photo & orb pixel coordinates
 *  - Real Tab 2 GPU draw telemetry and active diagnostic options
 */
data class TaskEnergyEvidence(
  val glContextInitCount: Int = 0,
  val webpLoadCount: Int = 0,
  val tabSwitchCount: Int = 0,
  val taskSnapshot: TaskEnergySnapshot = TaskEnergySnapshot.Initial,
  val emittedEvents: List<TaskEnergyEvent> = emptyList(),
  val layoutSnapshot: TaskSceneLayoutSnapshot? = null,
  val photoTextureLoaded: Boolean = false,
  val backgroundTextureLoaded: Boolean = false,
  val backgroundLoadStatus: String = "NOT_REQUESTED",
  val debugOptions: TaskRenderDebugOptions = TaskRenderDebugOptions.Default,
  val drawTelemetry: TaskGpuDrawTelemetry = TaskGpuDrawTelemetry(),
) {
  val gpuResourcesReused: Boolean
    get() = glContextInitCount <= 1 && webpLoadCount <= 1

  fun summaryLine(): String {
    val p = layoutSnapshot?.photoFrame
    val photoDesc = if (p != null) {
      String.format(Locale.US, "Photo[%.0f,%.0f..%.0f,%.0f r=%.0f]", p.leftPx, p.topPx, p.rightPx, p.bottomPx, p.cornerRadiusPx)
    } else {
      "Photo[pending]"
    }
    return "${taskSnapshot.stage.code} ${taskSnapshot.elapsedMs}ms " +
      "rel=${String.format(Locale.US, "%.2f", taskSnapshot.releaseProgress)} " +
      "L/R=${String.format(Locale.US, "%.2f", taskSnapshot.leftProgress)} " +
      "events=${emittedEvents.size}/11 $photoDesc " +
      "bg=$backgroundLoadStatus " +
      "vtx=${drawTelemetry.totalVertexCount} draws=${drawTelemetry.totalDrawCalls} " +
      "fbo=${drawTelemetry.fboComplete} glErr=0x${Integer.toHexString(drawTelemetry.glErrorCode)} " +
      "mode=${debugOptions.mode.name} reuse=$gpuResourcesReused"
  }
}
