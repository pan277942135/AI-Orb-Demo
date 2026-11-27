package com.example

import com.example.waterworld.WaterWorldMotionProfile
import com.example.waterworld.WaterWorldMotionRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldMotionRuntimeTest {

    @Test
    fun t01_loadsWork3MotionProfileFromRepoAndEnforcesProvenanceAndHonesty() {
        val repoProfile = File("releases/water-world-v2-v21/work3_full_dynamic_runtime/02_MOTION/work3_motion_profile.json")
        val fallbackProfile = File("../releases/water-world-v2-v21/work3_full_dynamic_runtime/02_MOTION/work3_motion_profile.json")
        val jsonFile = if (repoProfile.isFile) repoProfile else fallbackProfile
        assertTrue("work3_motion_profile.json must exist at ${jsonFile.path}", jsonFile.isFile)

        val parsed = WaterWorldMotionProfile.parseJson(org.json.JSONObject(jsonFile.readText()))
        parsed.validate()
        assertEquals(WaterWorldMotionProfile.PROVENANCE_WORK3_HYPOTHESIS, parsed.motionParameterProvenance)
        assertFalse("Authored runtime hypothesis must never claim validated ground truth", parsed.waterWorldMotionValuesValidated)
        assertEquals(32, parsed.coreVolumeSliceCount)
        assertEquals("SPATIAL_Z_SLICE_NOT_TIME_FRAME", parsed.coreVolumeSliceSemantics)

        val runtime = WaterWorldMotionRuntime(profile = parsed)
        assertEquals(47, runtime.latestSnapshot.elementStates.size)
        assertEquals(28, runtime.latestSnapshot.dependencyStates.size)
    }

    @Test
    fun t02_activityPauseAndResumePreserveExactTimeWithZeroJump() {
        val runtime = WaterWorldMotionRuntime()
        runtime.play()
        runtime.stepFrame(1_000_000_000L) // anchor
        val beforePause = runtime.stepFrame(1_050_000_000L)
        assertEquals(0.05, beforePause.globalTimeSeconds, 1e-6)
        assertTrue(runtime.isPlaying)

        // Activity enters background
        runtime.onActivityPause()
        assertFalse(runtime.isPlaying)
        val whilePaused = runtime.stepFrame(5_050_000_000L)
        assertEquals(0.05, whilePaused.globalTimeSeconds, 1e-6)

        // Activity resumes 15 seconds later -> first frame anchors with zero jump
        runtime.onActivityResume()
        assertTrue(runtime.isPlaying)
        val resumeAnchor = runtime.stepFrame(20_050_000_000L)
        assertEquals(0.05, resumeAnchor.globalTimeSeconds, 1e-6)
        val afterResume = runtime.stepFrame(20_066_000_000L)
        assertEquals(0.066, afterResume.globalTimeSeconds, 1e-6)
    }

    @Test
    fun t03_continuousStability10s30s60sZeroNanZeroCrashZeroJump() {
        val runtime = WaterWorldMotionRuntime()
        runtime.play()

        val dtSec = 1.0 / 30.0
        var prevTime = 0.0
        var checkpoint10s = false
        var checkpoint30s = false
        var checkpoint60s = false

        for (frame in 1..1800) { // 60 seconds @ 30Hz
            val snap = runtime.stepByDeltaSeconds(dtSec)
            val delta = snap.globalTimeSeconds - prevTime
            assertTrue("Simulation time must advance monotonically", delta > 0.0 && delta <= 0.05)
            prevTime = snap.globalTimeSeconds

            // Verify zero NaN/Inf across all core subsystems
            for (fs in snap.fishStates.values) {
                assertTrue(fs.offsetX.isFinite() && fs.offsetY.isFinite() && fs.tailAmpPx.isFinite())
            }
            for (rs in snap.residentStates.values) {
                assertTrue(rs.flowPhaseRad.isFinite() && rs.widthPulseScale.isFinite() && rs.brightnessScale.isFinite())
            }
            for (b in snap.outerBubbleStates.values) {
                assertTrue(b.offsetX.isFinite() && b.offsetY.isFinite() && b.alphaMul in 0f..1f)
            }
            assertTrue(snap.coreState.rotationAngleDeg.isFinite() && snap.waterOptics.causticsPhaseRad.isFinite())

            if (frame == 300) {
                assertEquals(10.0, snap.globalTimeSeconds, 1e-4)
                checkpoint10s = true
            }
            if (frame == 900) {
                assertEquals(30.0, snap.globalTimeSeconds, 1e-4)
                checkpoint30s = true
            }
            if (frame == 1800) {
                assertEquals(60.0, snap.globalTimeSeconds, 1e-4)
                checkpoint60s = true
            }
        }

        assertTrue(checkpoint10s && checkpoint30s && checkpoint60s)
        val stats = runtime.currentPerformanceStats()
        assertEquals(1800L, stats.totalFramesRendered)
        assertEquals(0L, stats.clampedDeltaCount)
        assertEquals(30.0, stats.estimatedFps, 0.5)
        assertEquals("NOT_MEASURED", stats.gpuFrameTimeMs)
        assertTrue(runtime.statusSummaryText().contains("T=60.00s"))
    }
}
