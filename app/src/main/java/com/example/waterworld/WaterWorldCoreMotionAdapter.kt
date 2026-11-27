package com.example.waterworld

import com.example.orb.geometry.Vec3
import com.example.orb.scene.AIOrbMotionController
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unified AI Orb Core motion adapter for Water World V2.1 Work 3.
 *
 * Architectural contract:
 * - Strictly preserves `CORE_VOLUME_SLICE_COUNT = 32` and
 *   `CORE_VOLUME_SLICE_SEMANTICS = SPATIAL_Z_SLICE_NOT_TIME_FRAME` (never animates by flipping WebP slices!).
 * - Drives all 8 `CORE_*` canonical motion elements (`CORE_VOLUME`, `CORE_FLUID`, `CORE_DENSITY`,
 *   `CORE_COLOR`, `CORE_CAUSTICS`, `CORE_GLASS`, `CORE_PARTICLES`, `CORE_FILAMENT`) from Water World's
 *   single timeline at underwater pacing (`2..6 deg/s`, NOT AI Orb's standalone `26 deg/s`).
 * - Does NOT render a second duplicate outer glass shell; optical coupling feeds into Water World's
 *   existing `03_SPHERE_BACK` / `04_WATER_INTERIOR` / `06_LIGHTING` / `09_SPHERE_FRONT` passes.
 * - Explicitly discloses `CORE_FILAMENT` as `ASSET_GAP_PROCEDURAL_RUNTIME_HYPOTHESIS`.
 */
object WaterWorldCoreMotionAdapter {

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
}
