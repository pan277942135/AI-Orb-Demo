package com.example.orb.config

import java.util.Locale

enum class QualityTier(
  val displayName: String,
  val renderScale: Float,
  val icosphereSubdivisions: Int,
  val fluidSubdivisions: Int,
  val ribbonSegments: Int,
  val bloomIterations: Int,
) {
  LOW(
    displayName = "Low",
    renderScale = 0.72f,
    icosphereSubdivisions = 3,
    fluidSubdivisions = 2,
    ribbonSegments = 72,
    bloomIterations = 2,
  ),
  MEDIUM(
    displayName = "Medium",
    renderScale = 0.86f,
    icosphereSubdivisions = 4,
    fluidSubdivisions = 3,
    ribbonSegments = 104,
    bloomIterations = 3,
  ),
  HIGH(
    displayName = "High",
    renderScale = 1.0f,
    icosphereSubdivisions = 4,
    fluidSubdivisions = 3,
    ribbonSegments = 128,
    bloomIterations = 4,
  ),
}

/**
 * Centralized, immutable configuration contract for the AI Orb Active State renderer.
 * Designed to be UI-agnostic and portable across Android GPU rendering pipelines.
 */
data class AIOrbConfig(
  val coreScale: Float = 1.0f,
  val coreTransparency: Float = 0.88f,
  val refraction: Float = 0.72f,
  val fluidSpeed: Float = 1.0f,
  val fluidIntensity: Float = 1.08f,
  val ribbonCount: Int = 8,
  val ribbonWidth: Float = 1.0f,
  val ribbonSpeed: Float = 1.0f,
  val ribbonTransparency: Float = 0.84f,
  val energyIntensity: Float = 1.08f,
  val particleDensity: Float = 0.90f,
  val bloom: Float = 0.54f,
  val overallIntensity: Float = 1.0f,
  val quality: QualityTier = QualityTier.HIGH,
  val isPaused: Boolean = false,
) {
  fun toJson(): String {
    fun f(v: Float): String = String.format(Locale.US, "%.3f", v)
    return buildString {
      appendLine("{")
      appendLine("  \"schema\": \"AI_ORB_ACTIVE_STATE_VISUAL_DEMO_V1\",")
      appendLine("  \"state\": \"AI_ORB_ACTIVE\",")
      appendLine("  \"coreScale\": ${f(coreScale)},")
      appendLine("  \"coreTransparency\": ${f(coreTransparency)},")
      appendLine("  \"refraction\": ${f(refraction)},")
      appendLine("  \"fluidSpeed\": ${f(fluidSpeed)},")
      appendLine("  \"fluidIntensity\": ${f(fluidIntensity)},")
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
    val Default = AIOrbConfig()
  }
}
