package com.example

import com.example.waterworld.WaterWorldDynamicFrameSnapshot
import com.example.waterworld.WaterWorldMotionContract
import com.example.waterworld.WaterWorldMotionEvaluator
import com.example.waterworld.WaterWorldMotionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class WaterWorldMotionEvaluatorTest {

    private val profile = WaterWorldMotionProfile.defaultProfile()

    @Test
    fun t01_t0StaticZeroSnapshotProducesExactIdentityAcrossAllSystems() {
        val snap = WaterWorldDynamicFrameSnapshot.staticZero(profile)
        assertEquals(0.0, snap.globalTimeSeconds, 0.0)
        assertTrue(snap.staticReferenceMode)
        assertEquals(47, snap.elementStates.size)
        assertEquals(28, snap.dependencyStates.size)
        assertEquals(WaterWorldMotionContract.CANONICAL_47_ELEMENTS.toSet(), snap.elementStates.keys)

        // All 4 fish strictly at T=0 position with zero deformation
        for (fs in snap.fishStates.values) {
            assertEquals(0f, fs.offsetX, 0f)
            assertEquals(0f, fs.offsetY, 0f)
            assertEquals(0f, fs.offsetZ, 0f)
            assertEquals(0f, fs.pitchRad, 0f)
            assertEquals(1f, fs.facingScaleX, 0f)
            assertEquals(1f, fs.depthScale, 0f)
            assertEquals(0f, fs.tailAmpPx, 0f)
            assertEquals(0f, fs.finAmpPx, 0f)
        }

        // All 5 residents strictly at T=0 neutral
        for (rs in snap.residentStates.values) {
            assertEquals(0f, rs.flowPhaseRad, 0f)
            assertEquals(0f, rs.flowOffsetU, 0f)
            assertEquals(1f, rs.widthPulseScale, 0f)
            assertEquals(1f, rs.brightnessScale, 0f)
            assertEquals(0f, rs.lateralWavePx, 0f)
        }

        // Plants, rocks, bubbles, water optics & core strictly neutral at T=0
        for (ps in snap.plantStates.values) {
            assertEquals(0f, ps.swayAmpPx, 0f)
            assertEquals(0f, ps.leafFlutterAmpPx, 0f)
        }
        for (rk in snap.rockStates.values) {
            assertTrue(rk.physicallyStationary)
            assertEquals(0f, rk.offsetX, 0f)
            assertEquals(0f, rk.offsetY, 0f)
        }
        for (b in snap.outerBubbleStates.values + snap.innerBubbleStates.values) {
            assertEquals(0f, b.offsetX, 0f)
            assertEquals(0f, b.offsetY, 0f)
            assertEquals(1f, b.scaleMul, 0f)
            assertEquals(1f, b.alphaMul, 0f)
        }
        assertEquals(0f, snap.waterOptics.waterNormalOffsetU, 0f)
        assertEquals(0f, snap.waterOptics.causticsPhaseRad, 0f)
        assertEquals(1f, snap.waterOptics.glassRefractionScale, 0f)
        assertEquals(1f, snap.waterOptics.glassFresnelScale, 0f)
        assertEquals(0f, snap.coreState.rotationAngleDeg, 0f)
        assertEquals(1f, snap.coreState.densityScale, 0f)
        assertEquals(0f, snap.coreState.colorShift, 0f)
    }

    @Test
    fun t02_fourFishMoveIndependentlyInsideCrystalSphereBoundsWithTailAndFinWaves() {
        val samples = (1..120).map { i ->
            WaterWorldMotionEvaluator.evaluate(profile, timeSeconds = i * 0.5, frameId = i.toLong())
        }

        val maxDisplacements = mutableMapOf<String, Float>()
        for (fp in profile.fish) maxDisplacements[fp.id] = 0f

        for (snap in samples) {
            for (fp in profile.fish) {
                val fs = snap.fishStates.getValue(fp.id)
                assertTrue(fs.offsetX.isFinite() && fs.offsetY.isFinite() && fs.offsetZ.isFinite())
                assertTrue(fs.pitchRad.isFinite() && fs.facingScaleX.isFinite() && fs.depthScale.isFinite())
                assertTrue("Fish must never collapse flat during turn", abs(fs.facingScaleX) >= 0.42f)
                assertTrue("Fish tail must deform at T>0", fs.tailAmpPx > 0f)
                assertTrue("Fish fins must flutter at T>0", fs.finAmpPx > 0f)

                val absX = fp.t0CenterX + fs.offsetX
                val absY = fp.t0CenterY + fs.offsetY
                val distFromSphereCenter = hypot((absX - profile.sphereCenterX).toDouble(), (absY - profile.sphereCenterY).toDouble()).toFloat()
                assertTrue(
                    "${fp.id} center must stay strictly inside sphere interior (dist=$distFromSphereCenter)",
                    distFromSphereCenter < profile.sphereInnerRadiusPx - 20f,
                )
                assertTrue("${fp.id} must stay below water meniscus", absY >= 390f)
                assertTrue("${fp.id} must stay clear of bottom rocks", absY <= 710f)

                val disp = hypot(fs.offsetX.toDouble(), fs.offsetY.toDouble()).toFloat()
                if (disp > maxDisplacements.getValue(fp.id)) maxDisplacements[fp.id] = disp
            }
        }

        // Every fish must travel meaningfully and independently
        for ((id, maxD) in maxDisplacements) {
            assertTrue("$id must actively swim along its path (maxD=$maxD)", maxD > 40f)
        }
        val atT4 = WaterWorldMotionEvaluator.evaluate(profile, 4.0)
        val f1 = atT4.fishStates.getValue("WW_FISH_01")
        val f2 = atT4.fishStates.getValue("WW_FISH_02")
        val f3 = atT4.fishStates.getValue("WW_FISH_03")
        val f4 = atT4.fishStates.getValue("WW_FISH_04")
        assertNotEquals(f1.pathProgress, f2.pathProgress, 1e-3f)
        assertNotEquals(f2.pathProgress, f3.pathProgress, 1e-3f)
        assertNotEquals(f3.pathProgress, f4.pathProgress, 1e-3f)
    }

    @Test
    fun t03_fiveResidentsShareUnifiedSegmentStatePreserveUnknownOcclusionAndPulseNaturally() {
        val snap = WaterWorldMotionEvaluator.evaluate(profile, timeSeconds = 6.0, frameId = 360L)
        assertEquals(setOf("G01", "G02", "A01", "A02", "C01"), snap.residentStates.keys)

        val phases = mutableSetOf<Float>()
        for (rp in profile.residents) {
            val rs = snap.residentStates.getValue(rp.groupKey)
            assertEquals(rp.drawIds, rs.drawIds)
            assertTrue("Resident occlusion must stay honestly UNKNOWN", rs.occlusionStatus.contains("UNKNOWN"))
            assertTrue("Resident must flow smoothly at T=6s", abs(rs.flowOffsetU) > 0.05f)
            assertTrue("Width pulse bounded cleanly", rs.widthPulseScale in 0.92f..1.08f)
            assertTrue("Brightness pulse bounded cleanly", rs.brightnessScale in 0.90f..1.10f)
            assertTrue("Lateral wave active at T=6s", rs.lateralWavePx > 0f)
            phases += rs.flowPhaseRad
        }
        assertEquals("All 5 residents must have independent distinct flow phases", 5, phases.size)
    }

    @Test
    fun t04_plantsSwayBoundedRocksStationaryBubblesRiseAndFadeCleanly() {
        val sphereDiameterPx = profile.sphereOuterRadiusPx * 2f
        val maxBubbleDriftAllowed = sphereDiameterPx * 0.015f

        for (step in 1..120) {
            val t = step * 0.5
            val snap = WaterWorldMotionEvaluator.evaluate(profile, t, step.toLong())

            // Plants sway <= 2% of 450px height = 9.0px
            for (ps in snap.plantStates.values) {
                assertTrue("Plant sway must stay <= 2% plant height (<=9px), got ${ps.swayAmpPx}", ps.swayAmpPx in 0f..9.01f)
                assertTrue(ps.swayPhaseRad.isFinite() && ps.leafFlutterPhaseRad.isFinite())
            }

            // All 3 rocks stay strictly stationary
            for (rk in snap.rockStates.values) {
                assertTrue(rk.physicallyStationary)
                assertEquals(0f, rk.offsetX, 0f)
                assertEquals(0f, rk.offsetY, 0f)
                assertEquals(0f, rk.rotationRad, 0f)
                assertTrue(rk.causticsPhaseRad > 0f)
            }

            // 9 outer bubbles + 14 inner bubbles stay bounded and smooth
            assertEquals(9, snap.outerBubbleStates.size)
            assertEquals(14, snap.innerBubbleStates.size)
            for (b in snap.outerBubbleStates.values) {
                assertTrue("Bubble drift must stay <= 1.5% sphere diameter", abs(b.offsetX) <= maxBubbleDriftAllowed)
                assertTrue("Bubble alpha must stay in [0, 1]", b.alphaMul in 0f..1f)
                assertTrue("Bubble scale must stay reasonable", b.scaleMul in 0.85f..1.15f)
            }
        }

        // Verify smooth fade-out at bubble wrap boundary (u = 0.62 -> alpha == 0.0)
        val b01 = profile.bubbles.first()
        val atWrap = WaterWorldMotionEvaluator.evaluate(profile, b01.risePeriodSec * 0.62)
        val bState = atWrap.outerBubbleStates.getValue(b01.drawId)
        assertTrue("Bubble must be fully faded (alpha <= 0.02) at wrap point so re-entry never pops", bState.alphaMul <= 0.02f)
    }

    @Test
    fun t05_coreAdapterEnforces32SpatialZSlicesNoDuplicateGlassAndHonestFilamentStatus() {
        val snap = WaterWorldMotionEvaluator.evaluate(profile, timeSeconds = 10.0, frameId = 600L)
        val core = snap.coreState

        assertEquals(32, core.sliceCount)
        assertEquals(WaterWorldMotionContract.CORE_VOLUME_SLICE_SEMANTICS, core.sliceSemantics)
        assertFalse("AI Orb Core must NEVER create a duplicate outer glass shell", core.createsDuplicateGlassShell)
        assertEquals("ASSET_GAP_PROCEDURAL_RUNTIME_HYPOTHESIS", core.filamentStatus)
        assertEquals(36.0f, core.rotationAngleDeg, 0.05f)
        assertEquals(9, core.rotationMat3.size)
        assertTrue(core.rotationMat3.all { it.isFinite() })
        assertTrue(core.fluidPhaseRad > 0f && core.causticsPhaseRad > 0f && core.particlesPhaseRad > 0f)

        // All 47 canonical elements and 28 dependencies accounted for at T=10s
        assertEquals(47, snap.elementStates.size)
        assertEquals(28, snap.dependencyStates.size)
        assertTrue(snap.elementStates.values.all { it.active })
        assertTrue(snap.dependencyStates.all { it.evaluatedAtTimeSec == 10.0 && it.signalValue.isFinite() })
    }
}
