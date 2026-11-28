package com.example.waterworld

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin 3D rigid orbital math for Work 3.2 Five Resident True Orbital Motion
 * (`WATER_WORLD_V2_1_WORK3_2_FIVE_RESIDENT_TRUE_ORBITAL_MOTION_V2`).
 *
 * Zero OpenGL dependencies — executes identically in Android runtime, JVM unit tests,
 * and software quantitative verification.
 *
 * Transform law:
 *   `P'(t) = C + R(uAxis, theta(t)) * (P - C)`
 * where `theta(t) = orbitalAngularVelocityRadPerSec * globalTimeSeconds`,
 * `R` is a proper 3x3 Rodrigues rotation matrix (`det(R) = +1`, `R^T * R = I`),
 * and matrix storage is column-major matching GLSL ES 3.00 `mat3` and
 * `AIOrbMotionController.buildAxisAngleMat3`.
 */
object WaterWorldResidentOrbitalMath {

    const val PROVENANCE_WORK3_2_ORBITAL_HYPOTHESIS = "WORK3_2_AUTHORED_ORBITAL_RUNTIME_HYPOTHESIS"
    const val MAX_ORBITAL_AXIS_TILT_DEG = 8.0f

    internal val IDENTITY_MAT3_READONLY: FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    data class MatrixValidation(
        val determinant: Float,
        val maxOrthogonalityError: Float,
        val isValid: Boolean,
    )

    fun identityMat3(): FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f,
    )

    fun normalizeAxis(ax: Float, ay: Float, az: Float): FloatArray {
        require(ax.isFinite() && ay.isFinite() && az.isFinite()) {
            "Orbital axis components must be finite: ($ax, $ay, $az)"
        }
        val len = sqrt((ax * ax + ay * ay + az * az).toDouble())
        require(len > 1e-6) {
            "Orbital axis must be non-zero: ($ax, $ay, $az)"
        }
        return floatArrayOf(
            (ax.toDouble() / len).toFloat(),
            (ay.toDouble() / len).toFloat(),
            (az.toDouble() / len).toFloat(),
        )
    }

    /**
     * Computes the angle in degrees between the given axis and the Core Z-axis `(0, 0, 1)`.
     */
    fun axisTiltFromCoreZDeg(ax: Float, ay: Float, az: Float): Float {
        val u = normalizeAxis(ax, ay, az)
        val dotZ = u[2].toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dotZ)).toFloat()
    }

    /**
     * Builds a column-major 3x3 Rodrigues rotation matrix for unit axis `u` and angle `angleRad`.
     * Column 0 = `[m0, m1, m2]`, Column 1 = `[m3, m4, m5]`, Column 2 = `[m6, m7, m8]`.
     */
    fun buildRodriguesMat3(axis: FloatArray, angleRad: Float): FloatArray {
        require(axis.size == 3) { "Expected 3-component axis, got ${axis.size}" }
        require(angleRad.isFinite()) { "Orbital angle must be finite, got $angleRad" }
        if (angleRad == 0f) return identityMat3()
        val u = normalizeAxis(axis[0], axis[1], axis[2])
        val x = u[0].toDouble()
        val y = u[1].toDouble()
        val z = u[2].toDouble()
        val c = cos(angleRad.toDouble())
        val s = sin(angleRad.toDouble())
        val ic = 1.0 - c
        return floatArrayOf(
            // Column 0
            (c + x * x * ic).toFloat(),
            (y * x * ic + z * s).toFloat(),
            (z * x * ic - y * s).toFloat(),
            // Column 1
            (x * y * ic - z * s).toFloat(),
            (c + y * y * ic).toFloat(),
            (z * y * ic + x * s).toFloat(),
            // Column 2
            (x * z * ic + y * s).toFloat(),
            (y * z * ic - x * s).toFloat(),
            (c + z * z * ic).toFloat(),
        )
    }

    fun mulMat3Vec3(m: FloatArray, vx: Float, vy: Float, vz: Float): FloatArray {
        require(m.size == 9) { "Expected 9-float column-major mat3, got ${m.size}" }
        val rx = m[0] * vx + m[3] * vy + m[6] * vz
        val ry = m[1] * vx + m[4] * vy + m[7] * vz
        val rz = m[2] * vx + m[5] * vy + m[8] * vz
        return floatArrayOf(rx, ry, rz)
    }

    /**
     * Exact CPU mirror of `RESIDENT_RIBBON_VERTEX` Work 3.2 Core-local 3D rigid orbital transform:
     *   `coreLocalNorm = (P - C) / max(coreRadiusPx, 1.0)`
     *   `rotatedNorm   = R * coreLocalNorm`
     *   `P'            = C + rotatedNorm * max(coreRadiusPx, 1.0)`
     */
    fun transformCoreLocalPoint(
        pointX: Float,
        pointY: Float,
        pointZ: Float,
        coreCenterX: Float,
        coreCenterY: Float,
        coreCenterZ: Float,
        coreRadiusPx: Float,
        rotMat3: FloatArray,
    ): FloatArray {
        val safeR = if (coreRadiusPx > 1f) coreRadiusPx else 1f
        val relX = (pointX - coreCenterX) / safeR
        val relY = (pointY - coreCenterY) / safeR
        val relZ = (pointZ - coreCenterZ) / safeR
        val rotated = mulMat3Vec3(rotMat3, relX, relY, relZ)
        return floatArrayOf(
            coreCenterX + rotated[0] * safeR,
            coreCenterY + rotated[1] * safeR,
            coreCenterZ + rotated[2] * safeR,
        )
    }

    fun rotateNormal(nx: Float, ny: Float, nz: Float, rotMat3: FloatArray): FloatArray {
        val r = mulMat3Vec3(rotMat3, nx, ny, nz)
        val len = sqrt((r[0] * r[0] + r[1] * r[1] + r[2] * r[2]).toDouble()).toFloat()
        return if (len > 1e-6f) floatArrayOf(r[0] / len, r[1] / len, r[2] / len) else floatArrayOf(0f, 0f, 1f)
    }

    /**
     * Validates that `m` is a proper 3D rotation matrix (`det(m) == +1`, `m^T * m == I`).
     */
    fun validateRotationMatrix(m: FloatArray, tolerance: Float = 1e-4f): MatrixValidation {
        require(m.size == 9) { "Expected 9-float column-major mat3, got ${m.size}" }
        val det = m[0] * (m[4] * m[8] - m[7] * m[5]) -
            m[3] * (m[1] * m[8] - m[7] * m[2]) +
            m[6] * (m[1] * m[5] - m[4] * m[2])
        var maxErr = 0f
        for (c1 in 0 until 3) {
            for (c2 in 0 until 3) {
                val dot = m[c1 * 3 + 0] * m[c2 * 3 + 0] +
                    m[c1 * 3 + 1] * m[c2 * 3 + 1] +
                    m[c1 * 3 + 2] * m[c2 * 3 + 2]
                val expected = if (c1 == c2) 1f else 0f
                val err = abs(dot - expected)
                if (err > maxErr) maxErr = err
            }
        }
        val finite = m.all { it.isFinite() } && det.isFinite() && maxErr.isFinite()
        val ok = finite && abs(det - 1f) <= tolerance && maxErr <= tolerance
        return MatrixValidation(determinant = det, maxOrthogonalityError = maxErr, isValid = ok)
    }

    /**
     * Recovers rotation angle in `[0, PI]` from trace of proper 3x3 rotation matrix:
     * `trace(R) = 1 + 2 * cos(theta)`.
     */
    fun extractRotationAngleRad(m: FloatArray): Float {
        require(m.size == 9) { "Expected 9-float column-major mat3, got ${m.size}" }
        val trace = (m[0] + m[4] + m[8]).toDouble()
        val cosTheta = ((trace - 1.0) * 0.5).coerceIn(-1.0, 1.0)
        return acos(cosTheta).toFloat()
    }

    fun defaultOrbitalAngularVelocityDegPerSec(groupKey: String): Float = when (groupKey) {
        "G01" -> +16.0f
        "G02" -> -13.0f
        "A01" -> +11.0f
        "A02" -> -19.0f
        "C01" -> +9.0f
        else -> error("Unknown Resident groupKey '$groupKey'")
    }

    fun defaultOrbitalAxis(groupKey: String): FloatArray = when (groupKey) {
        "G01" -> floatArrayOf(0.04f, 0.03f, 0.9987f)
        "G02" -> floatArrayOf(-0.04f, 0.03f, 0.9987f)
        "A01" -> floatArrayOf(0.00f, 0.00f, 1.0000f)
        "A02" -> floatArrayOf(-0.03f, 0.06f, 0.9977f)
        "C01" -> floatArrayOf(0.05f, -0.02f, 0.9985f)
        else -> error("Unknown Resident groupKey '$groupKey'")
    }

    fun degreesToRadians(deg: Double): Double = deg * PI / 180.0
}
