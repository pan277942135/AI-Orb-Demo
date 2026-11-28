package com.example.orb.task

/**
 * Real Tab 2 Perimeter Water-Light GPU Shader Diagnostic Modes (strictly independent from legacy
 * Tab 1/6KF `TaskVisualDebugMode` and `AIOrbScene`).
 */
enum class Tab2ShaderDebugMode(val shaderDebugId: Int, val shortLabel: String) {
  NORMAL(0, "Normal"),
  GEOMETRY_MAGENTA(1, "Geo"),
  ALPHA_HEATMAP(2, "Alpha"),
}

/**
 * Independent live diagnostic options bound directly to Tab 2's [Tab2WaterWorldSceneRenderer]
 * and [PerimeterWaterLightRenderer].
 */
data class Tab2DebugOptions(
  val shaderMode: Tab2ShaderDebugMode = Tab2ShaderDebugMode.NORMAL,
  val showL1: Boolean = true,
  val showL2: Boolean = true,
  val showL3: Boolean = true,
  val showL4: Boolean = true,
  val enableRefraction: Boolean = true,
) {
  /** Adapts to [TaskRenderDebugOptions] for zero-duplication reuse of [PerimeterWaterLightRenderer]. */
  fun toPerimeterDebugOptions(): TaskRenderDebugOptions {
    val mappedMode = when (shaderMode) {
      Tab2ShaderDebugMode.NORMAL -> TaskVisualDebugMode.NORMAL
      Tab2ShaderDebugMode.GEOMETRY_MAGENTA -> TaskVisualDebugMode.GEOMETRY_MAGENTA
      Tab2ShaderDebugMode.ALPHA_HEATMAP -> TaskVisualDebugMode.ALPHA_HEATMAP
    }
    return TaskRenderDebugOptions(
      mode = mappedMode,
      showMainStream = false,
      showSecondaryStreams = false,
      showL1 = showL1,
      showL2 = showL2,
      showL3 = showL3,
      showL4 = showL4,
    )
  }

  companion object {
    val Default = Tab2DebugOptions()
  }
}
