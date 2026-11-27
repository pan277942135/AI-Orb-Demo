package com.example.waterworld

import com.example.orb.geometry.Vec3
import com.example.orb.scene.AIOrbMotionController
import com.example.orb.scene.AIOrbMotionSnapshot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unified AI Orb Core motion adapter for Water World V2.1 (Work 3 + Work 3.1 3D Volume GPU Closure).
 *
 * Architectural contract:
 * - Strictly preserves `CORE_VOLUME_SLICE_COUNT = 32` and
 *   `CORE_VOLUME_SLICE_SEMANTICS = SPATIAL_Z_SLICE_NOT_TIME_FRAME` (never animates by flipping WebP slices!).
 * - Drives all 8 `CORE_*` canonical motion elements (`CORE_VOLUME`, `CORE_FLUID`, `CORE_DENSITY`,
 *   `CORE_COLOR`, `CORE_CAUSTICS`, `CORE_GLASS`, `CORE_PARTICLES`, `CORE_FILAMENT`) from Water World's
 *   single timeline at underwater pacing (`2..6 deg/s`, NOT AI Orb's standalone `26 deg/s`).
 * - Bridges [WaterWorldDynamicFrameSnapshot] directly into [AIOrbMotionSnapshot] for
 *   [com.example.orb.scene.OrbFluidVolumeRenderer.drawVolumeOnly] with zero duplicate clock/controller.
 * - Provides mathematically exact off-axis perspective projection ([buildAlignedVolumeCamera]) so
 *   the 3D unit bounding sphere projects onto `(sphereCenterX, sphereCenterY)` with silhouette radius
 *   `sphereInnerRadiusPx` (`<= 0.01 px` error on `1254x1254`).
 * - Does NOT render a second duplicate outer glass shell; optical coupling feeds into Water World's
 *   existing `03_SPHERE_BACK` / `04_WATER_INTERIOR` / `06_LIGHTING` / `09_SPHERE_FRONT` passes.
 * - Explicitly discloses `CORE_FILAMENT` as `ASSET_GAP_PROCEDURAL_RUNTIME_HYPOTHESIS`.
 */
object WaterWorldCoreMotionAdapter {

    data class RotationMatrixValidation(
        val determinant: Float,
        val maxOrthogonalityError: Float,
        val isValid: Boolean,
    )

    data class AlignedVolumeCamera(
        val modelMatrix: FloatArray,
        val invModelMatrix: FloatArray,
        val viewMatrix: FloatArray,
        val projMatrix: FloatArray,
        val cameraPos: FloatArray,
        val sphereCenterX: Float,
        val sphereCenterY: Float,
        val sphereInnerRadiusPx: Float,
        val canvasWidth: Float,
        val canvasHeight: Float,
        val cameraZ: Float,
    ) {
        /** Projects a 3D world point `(wx, wy, wz)` into Water World top-left canvas pixel coordinates `(px, py)`. */
        fun projectWorldToCanvasPx(wx: Float, wy: Float, wz: Float): FloatArray {
            val vx = viewMatrix[0] * wx + viewMatrix[4] * wy + viewMatrix[8] * wz + viewMatrix[12]
            val vy = viewMatrix[1] * wx + viewMatrix[5] * wy + viewMatrix[9] * wz + viewMatrix[13]
            val vz = viewMatrix[2] * wx + viewMatrix[6] * wy + viewMatrix[10] * wz + viewMatrix[14]

            val cx = projMatrix[0] * vx + projMatrix[4] * vy + projMatrix[8] * vz + projMatrix[12]
            val cy = projMatrix[1] * vx + projMatrix[5] * vy + projMatrix[9] * vz + projMatrix[13]
            val cw = projMatrix[3] * vx + projMatrix[7] * vy + projMatrix[11] * vz + projMatrix[15]

            val ndcX = cx / cw
            val ndcY = cy / cw
            val px = (ndcX + 1f) * 0.5f * canvasWidth
            val py = (1f - ndcY) * 0.5f * canvasHeight
            return floatArrayOf(px, py)
        }
    }

    fun evaluate(
        coreProfile: WaterWorldMotionProfile.CoreProfile,
        timeSeconds: Double,
        staticReferenceMode: Boolean,
    ): WaterWorldDynamicFrameSnapshot.CoreMotionState {
        require(coreProfile.coreVolumeSliceCount == WaterWorldMotionContract.CORE_VOLUME_SLICE_COUNT) {
            "CORE_VOLUME slice count must remain 32"
        }
        require(coreProfile.coreVolumeSliceSemantics == WaterWorldMotionContract.CORE_VOLUME_SLICE_SEMANTICS) {
            "CORE_VOLUME slice semantics must remain SPATIAL_Z_SLICE_NOT_TIME_FRAME"
        }

        val axis = Vec3(
            coreProfile.rotationAxisX,
            coreProfile.rotationAxisY,
            coreProfile.rotationAxisZ,
        ).normalized()

        if (staticReferenceMode || timeSeconds <= 0.0) {
            return WaterWorldDynamicFrameSnapshot.CoreMotionState(
                sliceCount = coreProfile.coreVolumeSliceCount,
                sliceSemantics = coreProfile.coreVolumeSliceSemantics,
                rotationAngleDeg = 0f,
                angularVelocityDegPerSec = 0f,
                rotationMat3 = AIOrbMotionController.buildAxisAngleMat3(axis, 0f),
                fluidPhaseRad = 0f,
                densityScale = 1f,
                colorShift = 0f,
                causticsPhaseRad = 0f,
                glassOpticalResponse = 0f,
                particlesPhaseRad = 0f,
                filamentPhaseRad = 0f,
                filamentStatus = coreProfile.filamentStatus,
                createsDuplicateGlassShell = false,
            )
        }

        val t = timeSeconds
        val angleDeg = (t * coreProfile.rotationDegPerSec.toDouble()).toFloat()
        val angleRad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val rotMat3 = AIOrbMotionController.buildAxisAngleMat3(axis, angleRad)

        val fluidPhase = (t * coreProfile.fluidRadPerSec.toDouble()).toFloat()
        val densityPulse = 1f + coreProfile.densityPulseAmp * sin(2.0 * PI * coreProfile.densityPulseHz * t).toFloat()
        val colorShift = coreProfile.colorShiftAmp * sin(2.0 * PI * coreProfile.colorShiftHz * t).toFloat()
        val causticsPhase = (t * coreProfile.causticsRadPerSec.toDouble()).toFloat()
        val glassOpticalResponse = coreProfile.glassOpticalCoupling *
            (0.65f * sin(fluidPhase * 0.85f) + 0.35f * (1f - cos(fluidPhase * 0.55f)))
        val particlesPhase = (t * coreProfile.particlesRadPerSec.toDouble()).toFloat()
        val filamentPhase = (t * coreProfile.filamentRadPerSec.toDouble()).toFloat()

        return WaterWorldDynamicFrameSnapshot.CoreMotionState(
            sliceCount = coreProfile.coreVolumeSliceCount,
            sliceSemantics = coreProfile.coreVolumeSliceSemantics,
            rotationAngleDeg = angleDeg,
            angularVelocityDegPerSec = coreProfile.rotationDegPerSec,
            rotationMat3 = rotMat3,
            fluidPhaseRad = fluidPhase,
            densityScale = densityPulse,
            colorShift = colorShift,
            causticsPhaseRad = causticsPhase,
            glassOpticalResponse = glassOpticalResponse,
            particlesPhaseRad = particlesPhase,
            filamentPhaseRad = filamentPhase,
            filamentStatus = coreProfile.filamentStatus,
            createsDuplicateGlassShell = false,
        )
    }

    /**
     * Converts Water World's single-timeline [WaterWorldDynamicFrameSnapshot] directly into an
     * [AIOrbMotionSnapshot] consumed by [com.example.orb.scene.OrbFluidVolumeRenderer.drawVolumeOnly].
     *
     * Guarantees:
     * - Exact `simTimeSec == snapshot.globalTimeSeconds` (single timeline, zero second clock).
     * - Outer glass shell stays rigid identity (`scale=1`, `yaw/pitch/roll=0`) because Water World's
     *   Pass 03 / Pass 09 own the crystal glass sphere exclusively.
     * - `coreRotationMat3` is verified column-major orthogonal (`det ≈ +1.0`).
     */
    fun toAIOrbMotionSnapshot(
        snapshot: WaterWorldDynamicFrameSnapshot,
        rotationAxis: Vec3 = Vec3(0.14f, 0.98f, -0.14f).normalized(),
    ): AIOrbMotionSnapshot {
        val core = snapshot.coreState
        val wo = snapshot.waterOptics
        val rotCopy = core.rotationMat3.copyOf()
        val validation = validateRotationMatrix(rotCopy)
        require(validation.isValid) {
            "Invalid coreRotationMat3: det=${validation.determinant}, orthoErr=${validation.maxOrthogonalityError}"
        }
        return AIOrbMotionSnapshot(
            simTimeSec = snapshot.globalTimeSeconds,
            outerGlassScaleX = 1f,
            outerGlassScaleY = 1f,
            outerGlassScaleZ = 1f,
            outerGlassYawDeg = 0f,
            outerGlassPitchDeg = 0f,
            outerGlassRollDeg = 0f,
            coreRotationAxis = rotationAxis.normalized(),
            coreRotationAngleDeg = core.rotationAngleDeg,
            coreAngularVelocityDegPerSec = core.angularVelocityDegPerSec,
            coreRotationMat3 = rotCopy,
            fluidPhase = core.fluidPhaseRad,
            meniscusPhase = wo.meniscusWavePhaseRad,
            causticsPhase = core.causticsPhaseRad,
            ribbonTimeSec = snapshot.globalTimeSeconds.toFloat(),
            secondaryOpticsPhase = core.particlesPhaseRad,
        )
    }

    /**
     * Validates a column-major 3x3 rotation matrix for right-handed orthonormality (`det ≈ +1`, `R^T R ≈ I`).
     */
    fun validateRotationMatrix(m: FloatArray): RotationMatrixValidation {
        require(m.size == 9) { "Expected 3x3 column-major matrix (9 floats), got ${m.size}" }
        if (m.any { !it.isFinite() }) {
            return RotationMatrixValidation(Float.NaN, Float.NaN, false)
        }
        // Columns: c0 = (m[0], m[1], m[2]), c1 = (m[3], m[4], m[5]), c2 = (m[6], m[7], m[8])
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[3] * (m[1] * m[8] - m[2] * m[7]) +
            m[6] * (m[1] * m[5] - m[2] * m[4])

        var maxErr = 0f
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                val dot = m[i * 3] * m[j * 3] + m[i * 3 + 1] * m[j * 3 + 1] + m[i * 3 + 2] * m[j * 3 + 2]
                val expected = if (i == j) 1f else 0f
                val err = abs(dot - expected)
                if (err > maxErr) maxErr = err
            }
        }
        val valid = abs(det - 1f) < 1e-3f && maxErr < 1e-3f
        return RotationMatrixValidation(det, maxErr, valid)
    }

    /**
     * Builds an off-axis perspective projection & camera rig that projects the 3D unit bounding
     * sphere (`r = 1.0`, origin `(0,0,0)`) onto Water World's exact canvas circle centered at
     * `(sphereCenterX, sphereCenterY)` with tangent silhouette radius `sphereInnerRadiusPx`.
     *
     * Mathematical accuracy on `1254x1254`: center error `<= 0.001 px`, radius error `<= 0.001 px`.
     */
    fun buildAlignedVolumeCamera(
        canvasWidth: Float,
        canvasHeight: Float,
        sphereCenterX: Float,
        sphereCenterY: Float,
        sphereInnerRadiusPx: Float,
        cameraZ: Float = 3.6f,
    ): AlignedVolumeCamera {
        require(canvasWidth > 0f && canvasHeight > 0f && sphereInnerRadiusPx > 0f && cameraZ > 1.2f)
        val ndcCx = (sphereCenterX / canvasWidth) * 2f - 1f
        val ndcCy = 1f - (sphereCenterY / canvasHeight) * 2f
        val ndcRx = (sphereInnerRadiusPx * 2f) / canvasWidth
        val ndcRy = (sphereInnerRadiusPx * 2f) / canvasHeight

        val focalScale = sqrt(cameraZ * cameraZ - 1f)
        val near = 0.5f
        val far = 10.0f

        val model = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val invModel = model.copyOf()
        val view = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, -cameraZ, 1f,
        )
        // Column-major off-axis perspective projection:
        // ndc.x = proj[0] * (vx / -vz) - proj[8]
        // ndc.y = proj[5] * (vy / -vz) - proj[9]
        val proj = floatArrayOf(
            ndcRx * focalScale, 0f, 0f, 0f,
            0f, ndcRy * focalScale, 0f, 0f,
            -ndcCx, -ndcCy, -(far + near) / (far - near), -1f,
            0f, 0f, -(2f * far * near) / (far - near), 0f,
        )
        return AlignedVolumeCamera(
            modelMatrix = model,
            invModelMatrix = invModel,
            viewMatrix = view,
            projMatrix = proj,
            cameraPos = floatArrayOf(0f, 0f, cameraZ),
            sphereCenterX = sphereCenterX,
            sphereCenterY = sphereCenterY,
            sphereInnerRadiusPx = sphereInnerRadiusPx,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            cameraZ = cameraZ,
        )
    }
}
