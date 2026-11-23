package com.example.orb.v3

import com.example.orb.geometry.Vec3
import kotlin.math.sqrt

/**
 * Single-source mathematical specification for Candidate B Embedded Crystal Lens geometry.
 *
 * Shared 1:1 across:
 *   - Python: `tools/orb-v3-generator/fitting/cavity_geometry_fitter.py`
 *   - GLSL:   `app/src/main/java/com/example/orb/gl/CoreFluidShaders.kt`
 *   - Kotlin: `app/src/main/java/com/example/orb/v3/CrystalLensGeometry.kt`
 *
 * Uses an affine-sheared ellipsoid transform `q = M * (p - c)`:
 *   qx = (p.x - c.x) / rx
 *   qy = (p.y - c.y) / ry + ASYM_SHEAR * qx
 *   qz = (p.z - c.z) / rz
 * so `phi(p) = |q| - 1`, analytic gradient normal `normalize(M^T * q)`, and exact
 * quadratic ray intersection `intersect(ro, rd)` all describe the EXACT same surface
 * with `phi(ro + rd * tEnter) == 0` and `phi(ro + rd * tExit) == 0`.
 */
object CrystalLensGeometry {
  const val ASYM_SHEAR = 0.08f
  const val MIN_RADIUS = 0.05f

  data class RayHit(
    val hit: Boolean,
    val tEnter: Float,
    val tExit: Float,
    val thickness: Float,
  )

  fun phi(p: Vec3, center: Vec3, radii: Vec3): Float {
    val invRx = 1f / radii.x.coerceAtLeast(MIN_RADIUS)
    val invRy = 1f / radii.y.coerceAtLeast(MIN_RADIUS)
    val invRz = 1f / radii.z.coerceAtLeast(MIN_RADIUS)
    val qx = (p.x - center.x) * invRx
    val qy = (p.y - center.y) * invRy + ASYM_SHEAR * qx
    val qz = (p.z - center.z) * invRz
    return sqrt(qx * qx + qy * qy + qz * qz) - 1f
  }

  fun normal(p: Vec3, center: Vec3, radii: Vec3): Vec3 {
    val invRx = 1f / radii.x.coerceAtLeast(MIN_RADIUS)
    val invRy = 1f / radii.y.coerceAtLeast(MIN_RADIUS)
    val invRz = 1f / radii.z.coerceAtLeast(MIN_RADIUS)
    val qx = (p.x - center.x) * invRx
    val qy = (p.y - center.y) * invRy + ASYM_SHEAR * qx
    val qz = (p.z - center.z) * invRz
    val gx = (qx + ASYM_SHEAR * qy) * invRx
    val gy = qy * invRy
    val gz = qz * invRz
    val len = sqrt(gx * gx + gy * gy + gz * gz)
    if (len <= 1e-8f) return Vec3(0f, 1f, 0f)
    return Vec3(gx / len, gy / len, gz / len)
  }

  fun intersect(
    ro: Vec3,
    rd: Vec3,
    tMax: Float,
    center: Vec3,
    radii: Vec3,
  ): RayHit {
    val invRx = 1f / radii.x.coerceAtLeast(MIN_RADIUS)
    val invRy = 1f / radii.y.coerceAtLeast(MIN_RADIUS)
    val invRz = 1f / radii.z.coerceAtLeast(MIN_RADIUS)

    val qx = (ro.x - center.x) * invRx
    val qy = (ro.y - center.y) * invRy + ASYM_SHEAR * qx
    val qz = (ro.z - center.z) * invRz

    val dx = rd.x * invRx
    val dy = rd.y * invRy + ASYM_SHEAR * dx
    val dz = rd.z * invRz

    val a = dx * dx + dy * dy + dz * dz
    val b = qx * dx + qy * dy + qz * dz
    val c = qx * qx + qy * qy + qz * qz - 1f
    val disc = b * b - a * c
    if (disc <= 0f || a <= 1e-8f) {
      return RayHit(hit = false, tEnter = 0f, tExit = 0f, thickness = 0f)
    }
    val s = sqrt(disc)
    val t0 = (-b - s) / a
    val t1 = (-b + s) / a
    if (t1 <= 0f || t0 >= tMax) {
      return RayHit(hit = false, tEnter = 0f, tExit = 0f, thickness = 0f)
    }
    val tEnter = t0.coerceAtLeast(0f)
    val tExit = t1.coerceAtMost(tMax)
    val thickness = (tExit - tEnter).coerceAtLeast(0f)
    return RayHit(
      hit = thickness > 1e-4f,
      tEnter = tEnter,
      tExit = tExit,
      thickness = thickness,
    )
  }
}
