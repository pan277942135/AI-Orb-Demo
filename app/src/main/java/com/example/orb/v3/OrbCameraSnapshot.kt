package com.example.orb.v3

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Immutable, single-source camera snapshot shared across Tab 1 `FULL_RENDER`,
 * all V3/V3.2 diagnostic modes (`REFERENCE_LOCKED_FRAME`, `CAVITY_ONLY`,
 * `FLUID_VOLUME_ONLY`, `RIBBONS_ONLY`, `GLASS_ONLY`, etc.), and the multi-layer
 * GPU evidence capture sequence.
 *
 * Uses pure Kotlin column-major 4x4 projection & lookAt builders so production
 * OpenGL ES 3.0 rendering and JVM unit tests consume mathematically identical
 * matrices with zero Android-stub drift.
 */
data class OrbCameraSnapshot(
  val surfaceWidth: Int,
  val surfaceHeight: Int,
  val fovYDeg: Float,
  val nearPlane: Float,
  val farPlane: Float,
  val visualBoundRadius: Float,
  val cameraDistance: Float,
  val targetX: Float,
  val targetY: Float,
  val targetZ: Float,
  val cameraX: Float,
  val cameraY: Float,
  val cameraZ: Float,
  val yawRad: Float,
  val pitchRad: Float,
  val viewMatrix: FloatArray,
  val projMatrix: FloatArray,
  val configHash: String,
) {
  fun writeCameraPos(out: FloatArray) {
    out[0] = cameraX
    out[1] = cameraY
    out[2] = cameraZ
  }

  fun writeViewMatrix(out: FloatArray) {
    System.arraycopy(viewMatrix, 0, out, 0, 16)
  }

  fun writeProjMatrix(out: FloatArray) {
    System.arraycopy(projMatrix, 0, out, 0, 16)
  }

  fun toJson(): String {
    fun f(v: Float): String = String.format(Locale.US, "%.6f", v)
    return buildString {
      appendLine("{")
      appendLine("  \"surfaceWidth\": $surfaceWidth,")
      appendLine("  \"surfaceHeight\": $surfaceHeight,")
      appendLine("  \"fovYDeg\": ${f(fovYDeg)},")
      appendLine("  \"nearPlane\": ${f(nearPlane)},")
      appendLine("  \"farPlane\": ${f(farPlane)},")
      appendLine("  \"visualBoundRadius\": ${f(visualBoundRadius)},")
      appendLine("  \"cameraDistance\": ${f(cameraDistance)},")
      appendLine("  \"target\": [${f(targetX)}, ${f(targetY)}, ${f(targetZ)}],")
      appendLine("  \"cameraPos\": [${f(cameraX)}, ${f(cameraY)}, ${f(cameraZ)}],")
      appendLine("  \"yawRad\": ${f(yawRad)},")
      appendLine("  \"pitchRad\": ${f(pitchRad)},")
      appendLine("  \"cameraConfigHash\": \"$configHash\"")
      append("}")
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is OrbCameraSnapshot) return false
    return surfaceWidth == other.surfaceWidth &&
      surfaceHeight == other.surfaceHeight &&
      fovYDeg == other.fovYDeg &&
      nearPlane == other.nearPlane &&
      farPlane == other.farPlane &&
      visualBoundRadius == other.visualBoundRadius &&
      cameraDistance == other.cameraDistance &&
      targetX == other.targetX &&
      targetY == other.targetY &&
      targetZ == other.targetZ &&
      cameraX == other.cameraX &&
      cameraY == other.cameraY &&
      cameraZ == other.cameraZ &&
      yawRad == other.yawRad &&
      pitchRad == other.pitchRad &&
      viewMatrix.contentEquals(other.viewMatrix) &&
      projMatrix.contentEquals(other.projMatrix) &&
      configHash == other.configHash
  }

  override fun hashCode(): Int = configHash.hashCode()
}

object OrbCameraBuilder {
  const val DEFAULT_NEAR_PLANE = 1.2f
  const val DEFAULT_FAR_PLANE = 32.0f

  /**
   * Builds the unified production Tab 1 camera from `MasterFidelityV32.CameraFit`.
   * Both `FULL_RENDER` and every diagnostic/capture mode use this exact builder
   * with ZERO legacy `visualBoundRadius = 1.36f` divergence.
   */
  fun build(
    surfaceWidth: Int,
    surfaceHeight: Int,
    cameraFit: MasterFidelityV32.CameraFit,
    yawRad: Float = 0f,
    pitchRad: Float = 0f,
    nearPlane: Float = DEFAULT_NEAR_PLANE,
    farPlane: Float = DEFAULT_FAR_PLANE,
  ): OrbCameraSnapshot {
    val w = surfaceWidth.coerceAtLeast(1)
    val h = surfaceHeight.coerceAtLeast(1)
    val aspect = w.toFloat() / h.toFloat()
    val fovYDeg = cameraFit.fovYDeg
    val tanHalfY = tan(Math.toRadians(fovYDeg.toDouble() * 0.5)).toFloat().coerceAtLeast(1e-4f)
    val tanHalfX = (tanHalfY * aspect).coerceAtLeast(0.10f)

    val baseCamDist = if (abs(aspect - 1.0f) <= 0.15f) {
      cameraFit.cameraDistance
    } else {
      maxOf(
        cameraFit.visualBoundRadius / tanHalfY,
        cameraFit.visualBoundRadius / tanHalfX,
        cameraFit.cameraDistance,
      )
    }

    val targetX = cameraFit.targetOffsetX
    val targetY = cameraFit.targetOffsetY
    val targetZ = 0f

    val camX = targetX + baseCamDist * cos(pitchRad) * sin(yawRad)
    val camY = targetY + baseCamDist * sin(pitchRad)
    val camZ = baseCamDist * cos(pitchRad) * cos(yawRad)

    val projMatrix = buildPerspectiveMatrix(fovYDeg, aspect, nearPlane, farPlane)
    val viewMatrix = buildLookAtMatrix(
      eyeX = camX, eyeY = camY, eyeZ = camZ,
      centerX = targetX, centerY = targetY, centerZ = targetZ,
      upX = 0f, upY = 1f, upZ = 0f,
    )

    val canonical = String.format(
      Locale.US,
      "%dx%d|fov=%.5f|n=%.4f|f=%.4f|vbr=%.5f|dist=%.5f|tgt=%.5f,%.5f,%.5f|cam=%.5f,%.5f,%.5f|yp=%.5f,%.5f",
      w, h, fovYDeg, nearPlane, farPlane, cameraFit.visualBoundRadius, baseCamDist,
      targetX, targetY, targetZ, camX, camY, camZ, yawRad, pitchRad,
    )
    val hash = MasterFidelityV32.sha256Hex(canonical.toByteArray(Charsets.UTF_8))

    return OrbCameraSnapshot(
      surfaceWidth = w,
      surfaceHeight = h,
      fovYDeg = fovYDeg,
      nearPlane = nearPlane,
      farPlane = farPlane,
      visualBoundRadius = cameraFit.visualBoundRadius,
      cameraDistance = baseCamDist,
      targetX = targetX,
      targetY = targetY,
      targetZ = targetZ,
      cameraX = camX,
      cameraY = camY,
      cameraZ = camZ,
      yawRad = yawRad,
      pitchRad = pitchRad,
      viewMatrix = viewMatrix,
      projMatrix = projMatrix,
      configHash = hash,
    )
  }

  /**
   * Standard OpenGL column-major perspective matrix matching `android.opengl.Matrix.perspectiveM`.
   */
  fun buildPerspectiveMatrix(
    fovYDeg: Float,
    aspect: Float,
    near: Float,
    far: Float,
  ): FloatArray {
    val f = (1.0 / tan(Math.toRadians(fovYDeg.toDouble() * 0.5))).toFloat()
    val rangeRecip = 1f / (near - far)
    return floatArrayOf(
      f / aspect.coerceAtLeast(1e-4f), 0f, 0f, 0f,
      0f, f, 0f, 0f,
      0f, 0f, (far + near) * rangeRecip, -1f,
      0f, 0f, 2f * far * near * rangeRecip, 0f,
    )
  }

  /**
   * Standard OpenGL column-major lookAt view matrix matching `android.opengl.Matrix.setLookAtM`.
   */
  fun buildLookAtMatrix(
    eyeX: Float, eyeY: Float, eyeZ: Float,
    centerX: Float, centerY: Float, centerZ: Float,
    upX: Float, upY: Float, upZ: Float,
  ): FloatArray {
    var fx = centerX - eyeX
    var fy = centerY - eyeY
    var fz = centerZ - eyeZ
    val fLen = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-6f)
    fx /= fLen; fy /= fLen; fz /= fLen

    var sx = fy * upZ - fz * upY
    var sy = fz * upX - fx * upZ
    var sz = fx * upY - fy * upX
    val sLen = sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-6f)
    sx /= sLen; sy /= sLen; sz /= sLen

    val ux = sy * fz - sz * fy
    val uy = sz * fx - sx * fz
    val uz = sx * fy - sy * fx

    val tx = -(sx * eyeX + sy * eyeY + sz * eyeZ)
    val ty = -(ux * eyeX + uy * eyeY + uz * eyeZ)
    val tz = (fx * eyeX + fy * eyeY + fz * eyeZ)

    return floatArrayOf(
      sx, ux, -fx, 0f,
      sy, uy, -fy, 0f,
      sz, uz, -fz, 0f,
      tx, ty, tz, 1f,
    )
  }
}
