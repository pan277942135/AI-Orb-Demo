package com.example.orb.scene

import com.example.orb.geometry.Vec3
import com.example.orb.gl.GlShaderProgram

/**
 * Centralized lighting rig matching the optical palette of the reference crystal orb:
 * - Warm sunlit champagne-gold key light from upper-left (~10:30 o'clock)
 * - Cool ice-cyan / turquoise rim & caustic bounce from lower-front & lower-left
 * - Warm amber orbital bounce from right (~3:30 o'clock)
 * - Deep emerald-teal environmental fill
 */
class AIOrbLighting {
  val keyLightDir = Vec3(-0.62f, 0.72f, 0.68f).normalized()
  val keyLightColor = floatArrayOf(1.0f, 0.93f, 0.78f)

  val warmRimDir = Vec3(0.84f, 0.28f, 0.42f).normalized()
  val warmRimColor = floatArrayOf(1.0f, 0.76f, 0.36f)

  val coolRimDir = Vec3(-0.48f, -0.78f, 0.56f).normalized()
  val coolRimColor = floatArrayOf(0.24f, 0.90f, 1.0f)

  val emeraldFillColor = floatArrayOf(0.18f, 0.62f, 0.34f)
  val deepAbyssColor = floatArrayOf(0.015f, 0.09f, 0.11f)

  fun applyTo(program: GlShaderProgram) {
    program.setVec3("uKeyLightDir", keyLightDir.x, keyLightDir.y, keyLightDir.z)
    program.setVec3("uKeyLightColor", keyLightColor)
    program.setVec3("uWarmRimDir", warmRimDir.x, warmRimDir.y, warmRimDir.z)
    program.setVec3("uWarmRimColor", warmRimColor)
    program.setVec3("uCoolRimDir", coolRimDir.x, coolRimDir.y, coolRimDir.z)
    program.setVec3("uCoolRimColor", coolRimColor)
    program.setVec3("uEmeraldFillColor", emeraldFillColor)
  }
}
