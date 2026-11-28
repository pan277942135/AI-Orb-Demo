package com.example

import com.example.waterworld.WaterWorldDynamicFrameSnapshot
import com.example.waterworld.WaterWorldMesh
import com.example.waterworld.WaterWorldMotionEvaluator
import com.example.waterworld.WaterWorldMotionProfile
import com.example.waterworld.WaterWorldMotionRuntime
import com.example.waterworld.WaterWorldResidentContractV1
import com.example.waterworld.WaterWorldShaders
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Five Resident Filaments V1 Comprehensive Anti-Regression & Design Freeze Test Suite
 * (`WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldFiveResidentFilamentsV1Test {

    private fun resolveWorkspaceRoot(): File {
        val candidates = listOf(File("."), File(".."), File("/app/applet"))
        return candidates.firstOrNull {
            File(it, "releases/water-world-v2-v21/five_resident_filaments_v1/01_PRE_IMPLEMENTATION/five_resident_geometry_lock.json").isFile
        } ?: error("Unable to locate workspace root with five_resident_geometry_lock.json")
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun t01_geometryLockMatches13FrozenMeshesSha256AndPassAssignments() {
        val root = resolveWorkspaceRoot()
        val lockFile = File(
            root,
            "releases/water-world-v2-v21/five_resident_filaments_v1/01_PRE_IMPLEMENTATION/five_resident_geometry_lock.json",
        )
        val lockJson = JSONObject(lockFile.readText())
        assertEquals(WaterWorldResidentContractV1.CONTRACT_ID, lockJson.getString("contractId"))
        assertEquals("STATIC_GEOMETRY_LOCKED", lockJson.getString("evidenceStatus"))
        assertEquals(WaterWorldResidentContractV1.MASTER_PNG_SHA256, lockJson.getString("masterPngSha256"))
        assertEquals(5, lockJson.getInt("residentCount"))
        assertEquals(13, lockJson.getInt("totalSegmentCount"))
        assertEquals(8, lockJson.getInt("backSegmentCount"))
        assertEquals(5, lockJson.getInt("frontSegmentCount"))

        val sceneRoot = File(root, "releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1")
        val segments = lockJson.getJSONArray("segments")
        assertEquals(13, segments.length())

        for (i in 0 until segments.length()) {
            val seg = segments.getJSONObject(i)
            val segId = seg.getString("segmentId")
            val meshFile = File(sceneRoot, seg.getString("meshRelativePath"))
            assertTrue("Frozen mesh missing for $segId: ${meshFile.path}", meshFile.isFile)
            assertEquals("Frozen mesh byte size changed for $segId", seg.getLong("bytes"), meshFile.length())
            assertEquals("Frozen mesh SHA-256 modified for $segId!", seg.getString("sha256"), sha256Hex(meshFile))

            val mapping = WaterWorldResidentContractV1.mappingForSegment(segId)
            assertEquals(seg.getString("segmentPass"), mapping.segmentPass)
            assertEquals(seg.getString("parentResidentId"), mapping.parentResidentId)
        }
    }

    @Test
    fun t02_frozenV1ParametersMatchContractToTheDecimal() {
        val root = resolveWorkspaceRoot()
        val defaultProf = WaterWorldMotionProfile.defaultProfile()
        defaultProf.validate()

        val jsonProfFile = File(root, "releases/water-world-v2-v21/work3_full_dynamic_runtime/02_MOTION/work3_motion_profile.json")
        val jsonProf = WaterWorldMotionProfile.parseJson(JSONObject(jsonProfFile.readText())).also { it.validate() }

        data class ExpectedV1(
            val key: String,
            val dir: Float,
            val period: Float,
            val initPhase: Float,
            val widthAmp: Float,
            val widthHz: Float,
            val brightAmp: Float,
            val brightHz: Float,
            val lateralPx: Float,
        )

        val expected = listOf(
            ExpectedV1("G01", +1f, 16.0f, 0.00f, 0.060f, 0.19f, 0.090f, 0.19f, 8.0f),
            ExpectedV1("G02", -1f, 18.0f, 1.24f, 0.050f, 0.18f, 0.080f, 0.16f, 7.0f),
            ExpectedV1("A01", +1f, 20.0f, 2.41f, 0.070f, 0.21f, 0.100f, 0.22f, 9.0f),
            ExpectedV1("A02", -1f, 14.0f, 3.85f, 0.060f, 0.25f, 0.090f, 0.21f, 8.0f),
            ExpectedV1("C01", +1f, 22.0f, 5.02f, 0.045f, 0.17f, 0.070f, 0.15f, 6.5f),
        )

        for (prof in listOf(defaultProf, jsonProf)) {
            assertEquals(5, prof.residents.size)
            for (exp in expected) {
                val rp = prof.residents.first { it.groupKey == exp.key }
                assertEquals("${exp.key} flowDirection", exp.dir, rp.flowDirection, 1e-5f)
                assertEquals("${exp.key} cyclePeriodSec", exp.period, rp.cyclePeriodSec, 1e-5f)
                assertEquals("${exp.key} phaseOffsetRad", exp.initPhase, rp.phaseOffsetRad, 1e-5f)
                assertEquals("${exp.key} widthPulseAmp", exp.widthAmp, rp.widthPulseAmp, 1e-5f)
                assertEquals("${exp.key} widthPulseHz", exp.widthHz, rp.widthPulseHz, 1e-5f)
                assertEquals("${exp.key} brightnessPulseAmp", exp.brightAmp, rp.brightnessPulseAmp, 1e-5f)
                assertEquals("${exp.key} brightnessPulseHz", exp.brightHz, rp.brightnessPulseHz, 1e-5f)
                assertEquals("${exp.key} lateralWavePx", exp.lateralPx, rp.lateralWavePx, 1e-5f)
                assertEquals(WaterWorldResidentContractV1.PROVENANCE_V1_FROZEN, rp.provenance)
            }
        }
    }

    @Test
    fun t03_quantitativeFlowReadabilityAtT2AndT5AndAntiSyncAcross60Seconds() {
        val profile = WaterWorldMotionProfile.defaultProfile()

        val snapT0 = WaterWorldMotionEvaluator.evaluate(profile, 0.0, 0L, staticReferenceMode = true)
        val snapT2 = WaterWorldMotionEvaluator.evaluate(profile, 2.0, 120L, staticReferenceMode = false)
        val snapT5 = WaterWorldMotionEvaluator.evaluate(profile, 5.0, 300L, staticReferenceMode = false)

        // Exact T=0 zero identity
        for (rs in snapT0.residentStates.values) {
            assertEquals(0f, rs.flowOffsetU, 0f)
            assertEquals(0f, rs.flowActive, 0f)
            assertEquals(0f, rs.widthAmplitude, 0f)
            assertEquals(0f, rs.brightnessAmplitude, 0f)
            assertEquals(0f, rs.lateralWavePx, 0f)
            assertEquals(1f, rs.widthPulseScale, 0f)
            assertEquals(1f, rs.brightnessScale, 0f)
        }

        // Contract Section 3.4 readability targets: >= 9% at T=2s, >= 22% at T=5s
        for (rp in profile.residents) {
            val s2 = snapT2.residentStates.getValue(rp.groupKey)
            val s5 = snapT5.residentStates.getValue(rp.groupKey)
            val pct2 = abs(s2.flowOffsetU) * 100f
            val pct5 = abs(s5.flowOffsetU) * 100f
            assertTrue("${rp.groupKey} must traverse >= 9% by T=2s (got $pct2%)", pct2 >= 9.0f)
            assertTrue("${rp.groupKey} must traverse >= 22% by T=5s (got $pct5%)", pct5 >= 22.0f)
            assertEquals("waveFreq must equal 3 / period", 3.0f / rp.cyclePeriodSec, s2.waveFrequencyHz, 1e-5f)
            assertEquals("flowSpeed must equal dir / period", rp.flowDirection / rp.cyclePeriodSec, s2.flowSpeedPerSec, 1e-5f)
        }

        // Anti-synchronization verification across T = 0..60s (0.1s resolution = 600 dynamic samples)
        var simultaneousBrightPeakCount = 0
        var simultaneousWidthPeakCount = 0
        for (step in 1..600) {
            val t = step * 0.1
            val snap = WaterWorldMotionEvaluator.evaluate(profile, t, step.toLong(), staticReferenceMode = false)
            var brightHigh = 0
            var widthHigh = 0
            for (rp in profile.residents) {
                val rs = snap.residentStates.getValue(rp.groupKey)
                assertTrue(rs.flowOffsetU.isFinite() && rs.wavePhaseRad.isFinite())
                assertTrue(rs.widthPulseScale in (1f - rp.widthPulseAmp - 1e-4f)..(1f + rp.widthPulseAmp + 1e-4f))
                assertTrue(rs.brightnessScale in (1f - rp.brightnessPulseAmp - 1e-4f)..(1f + rp.brightnessPulseAmp + 1e-4f))
                if (rs.brightnessScale > 1f + rp.brightnessPulseAmp * 0.85f) brightHigh++
                if (rs.widthPulseScale > 1f + rp.widthPulseAmp * 0.85f) widthHigh++
            }
            if (brightHigh == 5) simultaneousBrightPeakCount++
            if (widthHigh == 5) simultaneousWidthPeakCount++
        }
        assertEquals("All 5 Residents must NEVER simultaneously peak brightness across T=0..60s", 0, simultaneousBrightPeakCount)
        assertEquals("All 5 Residents must NEVER simultaneously peak width across T=0..60s", 0, simultaneousWidthPeakCount)
    }

    @Test
    fun t04_relativeWidthHalfWidthMeshBuilderAndNoHardcoded18PxInResidentShader() {
        val root = resolveWorkspaceRoot()
        val sceneRoot = File(root, "releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1")
        val lockJson = JSONObject(
            File(
                root,
                "releases/water-world-v2-v21/five_resident_filaments_v1/01_PRE_IMPLEMENTATION/five_resident_geometry_lock.json",
            ).readText(),
        )
        val segments = lockJson.getJSONArray("segments")
        for (i in 0 until segments.length()) {
            val seg = segments.getJSONObject(i)
            val meshJson = JSONObject(File(sceneRoot, seg.getString("meshRelativePath")).readText())
            val built = WaterWorldMesh.buildRibbonBuffersWithHalfWidths(
                meshJson.getJSONArray("vertices"),
                meshJson.getJSONArray("indices"),
            )
            assertEquals("Resident ribbon mesh must use 9 floats/vertex", 9, built.floatsPerVertex)
            assertEquals(seg.getInt("vertexCount"), built.vertexCount)
            assertEquals(seg.getInt("indexCount"), built.indexCount)

            var minHalfW = Float.POSITIVE_INFINITY
            var maxHalfW = Float.NEGATIVE_INFINITY
            for (v in 0 until built.vertexCount) {
                val hw = built.vertexFloats[v * 9 + 8]
                assertTrue("Half-width must be positive finite", hw.isFinite() && hw > 0f)
                if (hw < minHalfW) minHalfW = hw
                if (hw > maxHalfW) maxHalfW = hw
            }
            assertEquals(seg.getDouble("widthMinPx").toFloat() * 0.5f, minHalfW, 0.05f)
            assertEquals(seg.getDouble("widthMaxPx").toFloat() * 0.5f, maxHalfW, 0.05f)
        }

        // Contract Section 2.1.C: FORBID hardcoded 18.0 px width offset in RESIDENT_RIBBON_VERTEX
        assertFalse(
            "RESIDENT_RIBBON_VERTEX must NOT use hardcoded 18.0 px width displacement",
            WaterWorldShaders.RESIDENT_RIBBON_VERTEX.contains("18.0"),
        )
        assertTrue(WaterWorldShaders.RESIDENT_RIBBON_VERTEX.contains("aLocalHalfWidthPx"))
        assertTrue(WaterWorldShaders.RESIDENT_RIBBON_VERTEX.contains("uSegmentArcRange"))
        assertTrue(WaterWorldShaders.RESIDENT_RIBBON_VERTEX.contains("vCanonicalS"))
        assertTrue(WaterWorldShaders.RESIDENT_RIBBON_FRAGMENT.contains("vCanonicalS"))
        assertTrue(WaterWorldShaders.RESIDENT_RIBBON_FRAGMENT.contains("uResidentFlowParams"))
        assertTrue(WaterWorldShaders.RESIDENT_RIBBON_FRAGMENT.contains("leadEnv1"))
        assertTrue(WaterWorldShaders.RESIDENT_RIBBON_FRAGMENT.contains("tailEnv1"))
    }

    @Test
    fun t05_canonical13SegmentMappingAndA01Exact0PxSeamContinuity() {
        val mappings = WaterWorldResidentContractV1.CANONICAL_SEGMENT_MAPPINGS
        assertEquals(13, mappings.size)
        assertEquals(8, mappings.count { it.segmentPass == "02_RESIDENT_BACK" })
        assertEquals(5, mappings.count { it.segmentPass == "10_RESIDENT_FRONT" })
        assertEquals(setOf("G01", "G02", "A01", "A02", "C01"), mappings.map { it.parentResidentGroup }.toSet())

        // Verify A01 exact 0px back-to-front seam maps identically to canonical s = 0.2863
        val a01Back = WaterWorldResidentContractV1.mappingForSegment("A01_back_upper_left")
        val a01Front = WaterWorldResidentContractV1.mappingForSegment("A01_front_left_embrace")
        assertEquals("GEOMETRICALLY_CONTIGUOUS_SEAM_0PX", a01Back.continuityStatus)
        assertEquals("GEOMETRICALLY_CONTIGUOUS_SEAM_0PX", a01Front.continuityStatus)
        assertEquals(
            "A01 back end (u=1) and front start (u=0) must meet at identical canonical s",
            a01Back.mapLocalUToCanonicalS(1.0f),
            a01Front.mapLocalUToCanonicalS(0.0f),
            1e-5f,
        )

        // All non-A01 bridges honestly flag GROUND_TRUTH_UNKNOWN
        for (m in mappings) {
            if (m.parentResidentGroup != "A01") {
                assertTrue(
                    "${m.segmentId} must honestly mark GROUND_TRUTH_UNKNOWN, got ${m.continuityStatus}",
                    m.continuityStatus.contains("GROUND_TRUTH_UNKNOWN"),
                )
            }
            assertTrue(m.segmentArcLengthStart in 0f..1f)
            assertTrue(m.segmentArcLengthEnd in 0f..1f)
            assertTrue(m.segmentArcLengthEnd > m.segmentArcLengthStart)
        }
    }

    @Test
    fun t06_playbackControlPauseResumeResetAndSpeedScaling05x10x15x() {
        val profile = WaterWorldMotionProfile.defaultProfile()
        val rt = WaterWorldMotionRuntime(profile)
        rt.play(1_000_000_000L)

        // Advance 2.0s at 1.0x
        var snap1x: WaterWorldDynamicFrameSnapshot = rt.stepFrame(1_000_000_000L)
        for (i in 1..40) {
            snap1x = rt.stepFrame(1_000_000_000L + i * 50_000_000L)
        }
        val g01At2s = snap1x.residentStates.getValue("G01").flowOffsetU
        assertEquals(2.0f / 16.0f, g01At2s, 1e-4f)

        // Pause -> flowOffsetU stays frozen
        rt.pause()
        val pausedSnap = rt.stepFrame(1_000_000_000L + 60 * 50_000_000L)
        assertEquals(g01At2s, pausedSnap.residentStates.getValue("G01").flowOffsetU, 1e-6f)

        // Resume at 0.5x speed for 2.0 wall seconds -> adds 1.0 sim second (+1/16)
        rt.setSpeedScale(0.5f)
        rt.resume(1_000_000_000L + 60 * 50_000_000L)
        var snap05x = pausedSnap
        for (i in 1..40) {
            snap05x = rt.stepFrame(1_000_000_000L + (60 + i) * 50_000_000L)
        }
        assertEquals(3.0f / 16.0f, snap05x.residentStates.getValue("G01").flowOffsetU, 1e-4f)

        // Switch to 1.5x speed for 2.0 wall seconds -> adds 3.0 sim seconds (+3/16 -> 6/16)
        rt.setSpeedScale(1.5f)
        var snap15x = snap05x
        for (i in 1..40) {
            snap15x = rt.stepFrame(1_000_000_000L + (100 + i) * 50_000_000L)
        }
        assertEquals(6.0f / 16.0f, snap15x.residentStates.getValue("G01").flowOffsetU, 1e-4f)

        // Reset -> exact T=0 neutral
        val resetSnap = rt.reset(autoPlay = false)
        assertEquals(0.0, resetSnap.globalTimeSeconds, 0.0)
        assertEquals(0f, resetSnap.residentStates.getValue("G01").flowOffsetU, 0f)
        assertEquals(0f, resetSnap.residentStates.getValue("G01").flowActive, 0f)
        assertEquals(resetSnap, rt.latestSnapshot)
    }
}
