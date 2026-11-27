package com.example

import com.example.orb.geometry.Vec3
import com.example.orb.scene.OrbFluidVolumeRenderer
import com.example.waterworld.WaterWorldCoreMotionAdapter
import com.example.waterworld.WaterWorldDynamicFrameSnapshot
import com.example.waterworld.WaterWorldMotionContract
import com.example.waterworld.WaterWorldMotionEvaluator
import com.example.waterworld.WaterWorldMotionProfile
import com.example.waterworld.WaterWorldMotionRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldCore3DAdapterTest {

    @Test
    fun t01_adapterBridgesSingleTimelineSnapshotIntoAIOrbMotionSnapshot() {
        val profile = WaterWorldMotionProfile.defaultProfile()
        val snap = WaterWorldMotionEvaluator.evaluate(profile, timeSeconds = 5.0, frameId = 300L)
        val orbMotion = WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(snap)

        assertEquals(5.0, orbMotion.simTimeSec, 1e-6)
        assertEquals(snap.coreState.rotationAngleDeg, orbMotion.coreRotationAngleDeg, 1e-5f)
        assertEquals(snap.coreState.angularVelocityDegPerSec, orbMotion.coreAngularVelocityDegPerSec, 1e-5f)
        assertEquals(snap.coreState.fluidPhaseRad, orbMotion.fluidPhase, 1e-5f)
        assertEquals(snap.waterOptics.meniscusWavePhaseRad, orbMotion.meniscusPhase, 1e-5f)
        assertEquals(snap.coreState.causticsPhaseRad, orbMotion.causticsPhase, 1e-5f)

        // Outer glass shell stays strictly rigid identity (Water World Pass 03/09 own glass exclusively)
        assertEquals(1f, orbMotion.outerGlassScaleX, 1e-6f)
        assertEquals(1f, orbMotion.outerGlassScaleY, 1e-6f)
        assertEquals(1f, orbMotion.outerGlassScaleZ, 1e-6f)
        assertEquals(0f, orbMotion.outerGlassYawDeg, 1e-6f)
        assertEquals(0f, orbMotion.outerGlassPitchDeg, 1e-6f)
        assertEquals(0f, orbMotion.outerGlassRollDeg, 1e-6f)

        assertEquals(32, snap.coreState.sliceCount)
        assertEquals(WaterWorldMotionContract.CORE_VOLUME_SLICE_SEMANTICS, snap.coreState.sliceSemantics)
        assertFalse(snap.coreState.createsDuplicateGlassShell)
        assertTrue(
            "Core rotation speed must stay in gentle underwater range [2, 6] deg/s",
            orbMotion.coreAngularVelocityDegPerSec in 2.0f..6.0f,
        )
    }

    @Test
    fun t02_rotationMatrixIsColumnMajorOrthogonalWithUnitDeterminantAcrossTime() {
        val profile = WaterWorldMotionProfile.defaultProfile()
        val timestamps = listOf(0.0, 1.0, 2.0, 4.0, 8.0, 12.0, 30.0, 100.0)
        for (t in timestamps) {
            val snap = WaterWorldMotionEvaluator.evaluate(profile, timeSeconds = t, staticReferenceMode = (t == 0.0))
            val orbMotion = WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(snap)
            val mat = orbMotion.coreRotationMat3
            assertEquals(9, mat.size)
            assertTrue("All mat3 entries finite at T=$t", mat.all { it.isFinite() })

            val validation = WaterWorldCoreMotionAdapter.validateRotationMatrix(mat)
            assertTrue("Rotation matrix invalid at T=$t: $validation", validation.isValid)
            assertEquals(1f, validation.determinant, 1e-4f)
            assertTrue("Orthogonality error too large at T=$t: ${validation.maxOrthogonalityError}", validation.maxOrthogonalityError < 1e-4f)

            // Cross-check with OrbFluidVolumeRenderer's own helper
            assertEquals(1f, OrbFluidVolumeRenderer.computeMat3Det(mat), 1e-4f)
            assertTrue(OrbFluidVolumeRenderer.computeMat3OrthoError(mat) < 1e-4f)

            if (t == 0.0) {
                assertEquals(1f, mat[0], 1e-5f)
                assertEquals(1f, mat[4], 1e-5f)
                assertEquals(1f, mat[8], 1e-5f)
                assertEquals(0f, mat[1], 1e-5f)
                assertEquals(0f, mat[3], 1e-5f)
                assertEquals(0f, mat[6], 1e-5f)
            }
        }
    }

    @Test
    fun t03_pauseResumeAndResetProduceZeroJumpAndExactDeterminism() {
        val runtime = WaterWorldMotionRuntime()
        val startNs = 1_000_000_000L
        runtime.play(startNs)
        val snapBeforePause = runtime.stepFrame(startNs + 2_000_000_000L) // T = 2.0s
        val orbBeforePause = WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(snapBeforePause)

        runtime.pause()
        val snapWhilePaused = runtime.stepFrame(startNs + 5_000_000_000L)
        val orbWhilePaused = WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(snapWhilePaused)
        assertEquals(orbBeforePause.simTimeSec, orbWhilePaused.simTimeSec, 1e-6)
        assertEquals(orbBeforePause.coreRotationAngleDeg, orbWhilePaused.coreRotationAngleDeg, 1e-6f)
        assertEquals(orbBeforePause.fluidPhase, orbWhilePaused.fluidPhase, 1e-6f)

        runtime.resume(startNs + 5_000_000_000L)
        val snapRightAfterResume = runtime.stepFrame(startNs + 5_016_000_000L) // +16ms
        val deltaDeg = abs(snapRightAfterResume.coreState.rotationAngleDeg - snapBeforePause.coreState.rotationAngleDeg)
        assertTrue("Resume must have zero jump (deltaDeg=$deltaDeg)", deltaDeg < 0.25f)

        runtime.reset(autoPlay = false, nowNanos = startNs + 6_000_000_000L)
        val snapReset = runtime.latestSnapshot
        val orbReset = WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(snapReset)
        assertEquals(0.0, orbReset.simTimeSec, 1e-6)
        assertEquals(0f, orbReset.coreRotationAngleDeg, 1e-6f)
        assertEquals(0f, orbReset.fluidPhase, 1e-6f)
    }

    @Test
    fun t04_offAxisPerspectiveCameraAlignsUnitSphereWithinSubPixelAccuracy() {
        val canvasW = 1254f
        val canvasH = 1254f
        val cx = 626.47575f
        val cy = 596.34413f
        val rInner = 395.75f
        val camZ = 3.6f

        val cam = WaterWorldCoreMotionAdapter.buildAlignedVolumeCamera(
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            sphereCenterX = cx,
            sphereCenterY = cy,
            sphereInnerRadiusPx = rInner,
            cameraZ = camZ,
        )

        // Center of front sphere surface (0, 0, +1) must project to exact (cx, cy)
        val centerPx = cam.projectWorldToCanvasPx(0f, 0f, 1f)
        assertEquals("Center X alignment error must be <= 0.01px", cx, centerPx[0], 0.01f)
        assertEquals("Center Y alignment error must be <= 0.01px", cy, centerPx[1], 0.01f)

        // Exact tangent silhouette points on unit sphere from camera (0, 0, camZ)
        val zTan = 1f / camZ
        val rTan = sqrt(1f - zTan * zTan)

        val rightPx = cam.projectWorldToCanvasPx(rTan, 0f, zTan)
        val leftPx = cam.projectWorldToCanvasPx(-rTan, 0f, zTan)
        val topPx = cam.projectWorldToCanvasPx(0f, rTan, zTan)
        val bottomPx = cam.projectWorldToCanvasPx(0f, -rTan, zTan)

        assertEquals("Right silhouette X must match cx + rInner within 0.01px", cx + rInner, rightPx[0], 0.01f)
        assertEquals("Left silhouette X must match cx - rInner within 0.01px", cx - rInner, leftPx[0], 0.01f)
        assertEquals("Top silhouette Y must match cy - rInner within 0.01px", cy - rInner, topPx[1], 0.01f)
        assertEquals("Bottom silhouette Y must match cy + rInner within 0.01px", cy + rInner, bottomPx[1], 0.01f)
    }

    @Test
    fun t05_staticZeroSnapshotProducesExactIdentityCoreState() {
        val profile = WaterWorldMotionProfile.defaultProfile()
        val zero = WaterWorldDynamicFrameSnapshot.staticZero(profile)
        val orbZero = WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(zero, Vec3(0.14f, 0.98f, -0.14f))
        assertEquals(0.0, orbZero.simTimeSec, 1e-6)
        assertEquals(0f, orbZero.coreRotationAngleDeg, 1e-6f)
        assertEquals(0f, orbZero.fluidPhase, 1e-6f)
        assertEquals(0f, orbZero.causticsPhase, 1e-6f)
        assertEquals(1f, WaterWorldCoreMotionAdapter.validateRotationMatrix(orbZero.coreRotationMat3).determinant, 1e-5f)
    }
}
