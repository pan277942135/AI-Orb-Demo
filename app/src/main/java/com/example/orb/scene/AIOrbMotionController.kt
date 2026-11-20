package com.example.orb.scene

import com.example.orb.config.AIOrbConfig
import com.example.orb.geometry.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Immutable motion snapshot produced each frame by [AIOrbMotionController].
 * Establishes one authoritative coordinate & phase contract shared across:
 * - Outer Glass Shell (calm breathing, optically stable — NOT spinning to fake rotation)
 * - Inner Liquid Core (continuous, visibly directional 3D self-rotation around a tilted axis)
 * - Concave Back Bowl, Meniscus Surface, Caustics, Resident Ribbons & Secondary Optics
 */
data class AIOrbMotionSnapshot(
  val simTimeSec: Double,
  val outerGlassScaleX: Float,
  val outerGlassScaleY: Float,
  val outerGlassScaleZ: Float,
  val outerGlassYawDeg: Float,
  val outerGlassPitchDeg: Float,
  val outerGlassRollDeg: Float,
  val coreRotationAxis: Vec3,
  val coreRotationAngleDeg: Float,
  val coreAngularVelocityDegPerSec: Float,
  val coreRotationMat3: FloatArray, // Column-major 3x3 rotation matrix for GLSL mat3
  val fluidPhase: Float,
  val meniscusPhase: Float,
  val causticsPhase: Float,
  val ribbonTimeSec: Float,
  val secondaryOpticsPhase: Float,
) {
  /**
   * Computes a column-major 4x4 outer glass model matrix (pure Kotlin — runs on both Android & JVM tests).
   */
  fun writeOuterGlassModelMatrix(out: FloatArray) {
    val yaw = Math.toRadians(outerGlassYawDeg.toDouble()).toFloat()
    val pitch = Math.toRadians(outerGlassPitchDeg.toDouble()).toFloat()
    val roll = Math.toRadians(outerGlassRollDeg.toDouble()).toFloat()

    val cy = cos(yaw); val sy = sin(yaw)
    val cp = cos(pitch); val sp = sin(pitch)
    val cr = cos(roll); val sr = sin(roll)

    // R = Ry(yaw) * Rx(pitch) * Rz(roll), followed by S(sx, sy, sz)
    val r00 = cy * cr + sy * sp * sr
    val r01 = -cy * sr + sy * sp * cr
    val r02 = sy * cp

    val r10 = cp * sr
    val r11 = cp * cr
    val r12 = -sp

    val r20 = -sy * cr + cy * sp * sr
    val r21 = sy * sr + cy * sp * cr
    val r22 = cy * cp

    out[0] = r00 * outerGlassScaleX
    out[1] = r10 * outerGlassScaleX
    out[2] = r20 * outerGlassScaleX
    out[3] = 0f

    out[4] = r01 * outerGlassScaleY
    out[5] = r11 * outerGlassScaleY
    out[6] = r21 * outerGlassScaleY
    out[7] = 0f

    out[8] = r02 * outerGlassScaleZ
    out[9] = r12 * outerGlassScaleZ
    out[10] = r22 * outerGlassScaleZ
    out[11] = 0f

    out[12] = 0f
    out[13] = 0f
    out[14] = 0f
    out[15] = 1f
  }

  /**
   * Computes the exact analytical inverse of [writeOuterGlassModelMatrix] (orthogonal R + diagonal S).
   * Pure Kotlin so unit tests and GL renderer share identical inverse transforms with zero numerical drift.
   */
  fun writeInvOuterGlassModelMatrix(out: FloatArray) {
    val yaw = Math.toRadians(outerGlassYawDeg.toDouble()).toFloat()
    val pitch = Math.toRadians(outerGlassPitchDeg.toDouble()).toFloat()
    val roll = Math.toRadians(outerGlassRollDeg.toDouble()).toFloat()

    val cy = cos(yaw); val sy = sin(yaw)
    val cp = cos(pitch); val sp = sin(pitch)
    val cr = cos(roll); val sr = sin(roll)

    val r00 = cy * cr + sy * sp * sr
    val r01 = -cy * sr + sy * sp * cr
    val r02 = sy * cp

    val r10 = cp * sr
    val r11 = cp * cr
    val r12 = -sp

    val r20 = -sy * cr + cy * sp * sr
    val r21 = sy * sr + cy * sp * cr
    val r22 = cy * cp

    val invSx = 1f / outerGlassScaleX.coerceAtLeast(1e-4f)
    val invSy = 1f / outerGlassScaleY.coerceAtLeast(1e-4f)
    val invSz = 1f / outerGlassScaleZ.coerceAtLeast(1e-4f)

    // (R * S)^-1 = S^-1 * R^T
    out[0] = r00 * invSx
    out[1] = r01 * invSy
    out[2] = r02 * invSz
    out[3] = 0f

    out[4] = r10 * invSx
    out[5] = r11 * invSy
    out[6] = r12 * invSz
    out[7] = 0f

    out[8] = r20 * invSx
    out[9] = r21 * invSy
    out[10] = r22 * invSz
    out[11] = 0f

    out[12] = 0f
    out[13] = 0f
    out[14] = 0f
    out[15] = 1f
  }
}

/**
 * Single authoritative Orb Motion System.
 *
 * Architectural guarantees:
 * 1. Single monotonic simulation clock (`simTimeSec`) independent of frame count / FPS.
 * 2. Outer glass shell stays optically steady (gentle organic breathing & subtle sway <= 3.5 deg),
 *    while the internal liquid core undergoes unmistakable, continuous 3D self-rotation (~26 deg/s)
 *    around a tilted axis `(0.24, 0.94, -0.22)` — immediately distinguishable from camera motion.
 * 3. Inner concave bowl (`AIOrbInnerFluid`), refracted-ray fluid volume (`AIOrbCore`), meniscus
 *    advection, and underwater caustics all consume the EXACT same `coreRotationMat3` and phase.
 * 4. Pause freezes every phase identically; Resume continues seamlessly with zero jumps.
 */
class AIOrbMotionController {
  private var simTimeSec: Double = 0.0
  private var accumulatedCoreAngleDeg: Double = 0.0
  private var accumulatedFluidPhase: Double = 0.0
  private var accumulatedRibbonTime: Double = 0.0

  val currentTimeSec: Double get() = simTimeSec

  fun reset() {
    simTimeSec = 0.0
    accumulatedCoreAngleDeg = 0.0
    accumulatedFluidPhase = 0.0
    accumulatedRibbonTime = 0.0
  }

  /**
   * Advances simulation state by [rawDeltaSec] (clamped to <= 0.05s to prevent frame-hitch teleports)
   * and returns the unified [AIOrbMotionSnapshot].
   */
  fun step(rawDeltaSec: Double, config: AIOrbConfig): AIOrbMotionSnapshot {
    val dt = rawDeltaSec.coerceIn(0.0, 0.05)
    val baseCoreSpeed = BASE_CORE_ROTATION_DEG_PER_SEC * config.fluidSpeed.toDouble()

    if (!config.isPaused && dt > 0.0) {
      // Midpoint integration of organic angular speed modulation keeps 60fps & 20fps trajectories identical
      val midTime = simTimeSec + dt * 0.5
      val speedMod = 1.0 + 0.14 * sin(midTime * 0.42) + 0.06 * cos(midTime * 0.71 + 0.8)
      accumulatedCoreAngleDeg += baseCoreSpeed * speedMod * dt
      accumulatedFluidPhase += dt * config.fluidSpeed.toDouble()
      accumulatedRibbonTime += dt * config.ribbonSpeed.toDouble()
      simTimeSec += dt
    }

    val t = simTimeSec.toFloat()
    val speedModNow = (1.0 + 0.14 * sin(simTimeSec * 0.42) + 0.06 * cos(simTimeSec * 0.71 + 0.8)).toFloat()
    val currentAngularVel = if (config.isPaused) 0f else (baseCoreSpeed * speedModNow).toFloat()

    // Outer glass shell: calm, optically stable living presence (breathing + subtle sway, never fast spin)
    val outerYaw: Float
    val outerPitch: Float
    val outerRoll: Float
    val sX: Float
    val sY: Float
    val sZ: Float

    if (config.effectiveLockCoreRotation) {
      // Fixed outer shell & scale for isolated core-rotation QA verification
      outerYaw = 0f
      outerPitch = 0f
      outerRoll = 0f
      sX = config.coreScale
      sY = config.coreScale
      sZ = config.coreScale
    } else {
      val breathe = 1.0f +
        0.013f * sin(t * 0.78f) +
        0.007f * sin(t * 1.29f + 1.15f) +
        0.003f * cos(t * 0.49f + 2.4f)
      sX = config.coreScale * breathe * (1.0f + 0.006f * sin(t * 0.62f))
      sY = config.coreScale * breathe * (1.0f + 0.006f * cos(t * 0.68f + 0.8f))
      sZ = config.coreScale * breathe * (1.0f - 0.005f * sin(t * 0.55f + 1.6f))
      outerYaw = sin(t * 0.21f) * 3.2f
      outerPitch = sin(t * 0.27f + 0.7f) * 2.8f
      outerRoll = cos(t * 0.19f + 1.4f) * 2.2f
    }

    val coreAngleDeg = accumulatedCoreAngleDeg.toFloat()
    val coreRotMat3 = buildAxisAngleMat3(CORE_ROTATION_AXIS, Math.toRadians(accumulatedCoreAngleDeg).toFloat())

    return AIOrbMotionSnapshot(
      simTimeSec = simTimeSec,
      outerGlassScaleX = sX,
      outerGlassScaleY = sY,
      outerGlassScaleZ = sZ,
      outerGlassYawDeg = outerYaw,
      outerGlassPitchDeg = outerPitch,
      outerGlassRollDeg = outerRoll,
      coreRotationAxis = CORE_ROTATION_AXIS,
      coreRotationAngleDeg = coreAngleDeg,
      coreAngularVelocityDegPerSec = currentAngularVel,
      coreRotationMat3 = coreRotMat3,
      fluidPhase = accumulatedFluidPhase.toFloat(),
      meniscusPhase = (accumulatedFluidPhase * 1.08).toFloat(),
      causticsPhase = (accumulatedFluidPhase * 1.15).toFloat(),
      ribbonTimeSec = accumulatedRibbonTime.toFloat(),
      secondaryOpticsPhase = t,
    )
  }

  companion object {
    /**
     * Clearly perceptible internal water-core angular speed: ~26 deg/s (~13.8s per full 360 deg vortex turn).
     * Tilted 3D axis gives diagonal spatial motion across the orb face so rotation is immediately unmistakable.
     */
    const val BASE_CORE_ROTATION_DEG_PER_SEC = 26.0
    val CORE_ROTATION_AXIS: Vec3 = Vec3(0.24f, 0.94f, -0.22f).normalized()

    /**
     * Builds a column-major 3x3 Rodrigues rotation matrix around unit [axis] by [angleRad].
     */
    fun buildAxisAngleMat3(axis: Vec3, angleRad: Float): FloatArray {
      val c = cos(angleRad)
      val s = sin(angleRad)
      val omc = 1f - c
      val x = axis.x
      val y = axis.y
      val z = axis.z

      // Column-major order for GLSL mat3
      return floatArrayOf(
        // Col 0
        c + x * x * omc,
        y * x * omc + z * s,
        z * x * omc - y * s,
        // Col 1
        x * y * omc - z * s,
        c + y * y * omc,
        z * y * omc + x * s,
        // Col 2
        x * z * omc + y * s,
        y * z * omc - x * s,
        c + z * z * omc,
      )
    }

    /**
     * Multiplies a column-major 3x3 matrix [m] by vector [v].
     */
    fun mulMat3Vec3(m: FloatArray, v: Vec3): Vec3 = Vec3(
      m[0] * v.x + m[3] * v.y + m[6] * v.z,
      m[1] * v.x + m[4] * v.y + m[7] * v.z,
      m[2] * v.x + m[5] * v.y + m[8] * v.z,
    )

    fun wrapAngleDeg(angle: Float): Float = ((angle % 360f) + 360f) % 360f
  }
}
