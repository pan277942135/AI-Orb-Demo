package com.example.orb.config

import java.util.Locale

enum class QualityTier(
  val displayName: String,
  val renderScale: Float,
  val icosphereSubdivisions: Int,
  val fluidSubdivisions: Int,
  val ribbonSegments: Int,
  val bloomIterations: Int,
  val raymarchSteps: Int,
) {
  LOW(
    displayName = "Low",
    renderScale = 0.72f,
    icosphereSubdivisions = 3,
    fluidSubdivisions = 2,
    ribbonSegments = 72,
    bloomIterations = 2,
    raymarchSteps = 16,
  ),
  MEDIUM(
    displayName = "Medium",
    renderScale = 0.86f,
    icosphereSubdivisions = 4,
    fluidSubdivisions = 3,
    ribbonSegments = 104,
    bloomIterations = 3,
    raymarchSteps = 32,
  ),
  HIGH(
    displayName = "High",
    renderScale = 1.0f,
    icosphereSubdivisions = 4,
    fluidSubdivisions = 3,
    ribbonSegments = 128,
    bloomIterations = 4,
    raymarchSteps = 48,
  ),
}

/**
 * Production-pipeline GPU diagnostic & isolation modes (V2 + V3 WebP 3D Volume).
 * Every GPU diagnostic mode executes the real production shaders and render passes —
 * never a parallel fake shader. `REFERENCE_ONLY` is strictly an isolated QA image viewer
 * and is never composited with `GPU_ONLY` or `FULL_RENDER`.
 */
enum class RenderDebugMode(val shaderModeId: Int, val displayLabel: String) {
  FULL_RENDER(0, "Full Render"),
  REFERENCE_LOCKED_FRAME(0, "Ref Locked"),
  GPU_ONLY(0, "GPU Only"),
  REFERENCE_ONLY(0, "Reference Only"),
  CORE_ONLY(0, "Core Only"),
  CORE_ONLY_TRANSPARENT(0, "Core Only Transparent"),
  CORE_ROTATION_ONLY(0, "Core Rotation"),
  CORE_GLASS_ONLY(5, "Core Glass"),
  FLUID_VOLUME_ONLY(7, "Fluid Volume"),
  VOLUME_DEPTH(8, "Volume Depth"),
  FLUID_DENSITY(1, "Fluid Density"),
  FLUID_FLOW(2, "Fluid Flow"),
  MENISCUS_DEBUG(3, "Meniscus"),
  CAVITY_ONLY(9, "Cavity Only"),
  CAUSTICS_ONLY(4, "Caustics Only"),
  GLASS_ONLY(5, "Glass Only"),
  RESIDENTS_ONLY(0, "Residents Only"),
  RIBBONS_ONLY(0, "Ribbons Only"),
  OPTICS_ONLY(0, "Optics Only"),
  DEPTH_DEBUG(6, "Depth Debug"),
  BLOOM_OFF(0, "Bloom Off"),
  FOLD_NORMAL(10, "Fold Normal"),
  PRE_TONEMAP(0, "Pre Tonemap"),
  POST_TONEMAP(0, "Post Tonemap"),
  ;

  val isCoreIsolated: Boolean
    get() = this == CORE_ROTATION_ONLY ||
      this == CORE_ONLY ||
      this == CORE_ONLY_TRANSPARENT ||
      this == CORE_GLASS_ONLY ||
      this == FLUID_VOLUME_ONLY ||
      this == VOLUME_DEPTH ||
      this == FLUID_DENSITY ||
      this == FLUID_FLOW ||
      this == MENISCUS_DEBUG ||
      this == CAVITY_ONLY ||
      this == CAUSTICS_ONLY ||
      this == GLASS_ONLY ||
      this == FOLD_NORMAL

  val locksReferenceFrame: Boolean
    get() = this == REFERENCE_LOCKED_FRAME

  val locksCameraAndOuterShell: Boolean
    get() = this == CORE_ROTATION_ONLY || this == CORE_ONLY_TRANSPARENT || this == REFERENCE_LOCKED_FRAME
}

enum class OrbSceneMode {
  ORB_ONLY,
  TASK_PERIMETER_DEMO,
  YUJIAN_HYBRID,
}

/** The Core implementation selected by the active scene; Tab 2 still uses its shared native Core. */
enum class OrbCoreMode {
  NATIVE_V32,
  YUJIAN_CORE,
}

/**
 * Centralized, immutable configuration contract for AI Orb Active State V3.
 * UI-agnostic and portable across Android OpenGL ES 3.0 rendering pipelines.
 */
data class AIOrbConfig(
  val coreScale: Float = 1.0f,
  val coreShellScale: Float = 0.80f,
  val coreTransparency: Float = 0.90f,
  val refraction: Float = 0.76f,
  val fluidSpeed: Float = 1.0f,
  val fluidIntensity: Float = 1.08f,
  /** Relative multiplier on the Master fitted density; 1.0 leaves the fitted value unchanged. */
  val densityScaleMultiplier: Float = 1.0f,
  val ribbonCount: Int = 5,
  val ribbonWidth: Float = 1.0f,
  val ribbonSpeed: Float = 1.0f,
  val ribbonTransparency: Float = 0.86f,
  val energyIntensity: Float = 0.92f,
  val particleDensity: Float = 0.88f,
  val bloom: Float = 0.38f,
  val overallIntensity: Float = 1.0f,
  val quality: QualityTier = QualityTier.HIGH,
  val isPaused: Boolean = false,
  // QA diagnostic options (strictly OFF / FULL_RENDER by default; never affect formal active visuals)
  val isolateCoreDebug: Boolean = false,
  val showMotionOverlay: Boolean = false,
  val debugMode: RenderDebugMode = RenderDebugMode.FULL_RENDER,
  val sceneMode: OrbSceneMode = OrbSceneMode.ORB_ONLY,
) {
  val coreMode: OrbCoreMode
    get() = if (sceneMode == OrbSceneMode.YUJIAN_HYBRID) OrbCoreMode.YUJIAN_CORE else OrbCoreMode.NATIVE_V32

  /** Effective core-rotation lock flag honoring both legacy switch and explicit RenderDebugMode. */
  val effectiveLockCoreRotation: Boolean
    get() = isolateCoreDebug || debugMode.locksCameraAndOuterShell

  /** Effective flag hiding outer ribbons/particles for core-focused QA modes. */
  val effectiveHideOuterOrbitals: Boolean
    get() = isolateCoreDebug || debugMode.isCoreIsolated

  fun toJson(): String {
    fun f(v: Float): String = String.format(Locale.US, "%.3f", v)
    return buildString {
      appendLine("{")
      appendLine("  \"schema\": \"AI_ORB_ACTIVE_STATE_VISUAL_DEMO_V1\",")
      appendLine("  \"version\": \"V3_WEBP_3D_VOLUME_HYBRID\",")
      appendLine("  \"state\": \"AI_ORB_ACTIVE\",")
      appendLine("  \"sceneMode\": \"${sceneMode.name}\",")
      appendLine("  \"renderMode\": \"${debugMode.name}\",")
      appendLine("  \"coreScale\": ${f(coreScale)},")
      appendLine("  \"coreShellScale\": ${f(coreShellScale)},")
      appendLine("  \"coreTransparency\": ${f(coreTransparency)},")
      appendLine("  \"refraction\": ${f(refraction)},")
      appendLine("  \"fluidSpeed\": ${f(fluidSpeed)},")
      appendLine("  \"fluidIntensity\": ${f(fluidIntensity)},")
      appendLine("  \"densityScaleMultiplier\": ${f(densityScaleMultiplier)},")
      appendLine("  \"ribbonCount\": $ribbonCount,")
      appendLine("  \"ribbonWidth\": ${f(ribbonWidth)},")
      appendLine("  \"ribbonSpeed\": ${f(ribbonSpeed)},")
      appendLine("  \"ribbonTransparency\": ${f(ribbonTransparency)},")
      appendLine("  \"energyIntensity\": ${f(energyIntensity)},")
      appendLine("  \"particleDensity\": ${f(particleDensity)},")
      appendLine("  \"bloom\": ${f(bloom)},")
      appendLine("  \"overallIntensity\": ${f(overallIntensity)},")
      appendLine("  \"quality\": \"${quality.name}\"")
      append("}")
    }
  }

  companion object {
    const val MIN_RIBBON_COUNT = 2
    const val MAX_RIBBON_COUNT = 10
    const val CANONICAL_RESIDENT_COUNT = 5
    const val MIN_DENSITY_SCALE_MULTIPLIER = 0.5f
    const val MAX_DENSITY_SCALE_MULTIPLIER = 1.5f
    val Default = AIOrbConfig()
  }
}
