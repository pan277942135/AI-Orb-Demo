package com.example

import com.example.waterworld.WaterWorldDynamicFrameSnapshot
import com.example.waterworld.WaterWorldMotionContract
import com.example.waterworld.WaterWorldMotionEvaluator
import com.example.waterworld.WaterWorldMotionProfile
import com.example.waterworld.WaterWorldMotionRuntime
import com.example.waterworld.WaterWorldRenderGraph
import com.example.waterworld.WaterWorldResidentContractV1
import com.example.waterworld.WaterWorldResidentOrbitalMath
import com.example.waterworld.WaterWorldShaders
import com.example.waterworld.WaterWorldStaticRenderer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Work 3.2 Comprehensive Unit & Regression Test Suite
 * (`WATER_WORLD_V2_1_WORK3_2_FIVE_RESIDENT_TRUE_ORBITAL_MOTION_V2`).
 *
 * Covers all 13 mandatory test categories:
 *  1. Orbital Math Tests
 *  2. Rodrigues Rotation Tests
 *  3. Core-Local Coordinate Tests
 *  4. Five Resident Determinism Tests
 *  5. 13 Segment Mapping Tests
 *  6. A01 Seam Tests (<= 1 px across 0..60 s & 0..360 deg)
 *  7. Back / Front Depth Tests across all 13 frozen meshes & 0..360 deg
 *  8. Shader Static Structure & Transform Order Tests
 *  9. GPU Uniform Contract & Active-Read Tests
 * 10. Timeline Lifecycle Tests (Play, Pause, Resume, Reset, 0.5x/1.0x/1.5x, Activity lifecycle)
 * 11. Static T=0 Regression Tests
 * 12. 60s Continuous Motion & P50/P90 Displacement Gate Tests (T=2s & T=5s)
 * 13. Other Ecology & Frozen Asset Regression Tests
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldWork32ResidentOrbitalMotionTest {

    private fun resolveWorkspaceRoot(): File {
        val candidates = listOf(File("."), File(".."), File("/app/applet"))
        return candidates.firstOrNull {
            File(it, "releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1/03_SCENE_GRAPH/draw_bindings.json").isFile
        } ?: error("Unable to locate workspace root")
    }

    private fun sha256Hex(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    // -------------------------------------------------------------------------
    // Category 1: Orbital Math Tests
    // -------------------------------------------------------------------------
    @Test
    fun c01_orbitalMathParametersAnglesPeriodsAndProvenance() {
        val root = resolveWorkspaceRoot()
        val defaultProf = WaterWorldMotionProfile.defaultProfile().also { it.validate() }
        val jsonProfFile = File(root, "releases/water-world-v2-v21/work3_full_dynamic_runtime/02_MOTION/work3_motion_profile.json")
        val jsonProf = WaterWorldMotionProfile.parseJson(JSONObject(jsonProfFile.readText())).also { it.validate() }

        data class Spec(val key: String, val omegaDeg: Float, val t2Deg: Float, val periodSec: Float, val axis: FloatArray)
        val specs = listOf(
            Spec("G01", +16.0f, +32.0f, 360f / 16f, floatArrayOf(0.04f, 0.03f, 0.9987f)),
            Spec("G02", -13.0f, -26.0f, 360f / 13f, floatArrayOf(-0.04f, 0.03f, 0.9987f)),
            Spec("A01", +11.0f, +22.0f, 360f / 11f, floatArrayOf(0.00f, 0.00f, 1.0000f)),
            Spec("A02", -19.0f, -38.0f, 360f / 19f, floatArrayOf(-0.03f, 0.06f, 0.9977f)),
            Spec("C01", +9.0f, +18.0f, 360f / 9f, floatArrayOf(0.05f, -0.02f, 0.9985f)),
        )

        for (prof in listOf(defaultProf, jsonProf)) {
            val snapT2 = WaterWorldMotionEvaluator.evaluate(prof, timeSeconds = 2.0, frameId = 120L, staticReferenceMode = false)
            for (s in specs) {
                val rp = prof.residents.first { it.groupKey == s.key }
                assertTrue(rp.orbitalEnabled)
                assertEquals(WaterWorldResidentOrbitalMath.PROVENANCE_WORK3_2_ORBITAL_HYPOTHESIS, rp.orbitalProvenance)
                assertEquals(s.omegaDeg, rp.orbitalAngularVelocityDegPerSec, 1e-5f)
                assertEquals(s.periodSec, 360f / abs(rp.orbitalAngularVelocityDegPerSec), 1e-4f)
                assertEquals(s.axis[0], rp.orbitalAxisX, 1e-5f)
                assertEquals(s.axis[1], rp.orbitalAxisY, 1e-5f)
                assertEquals(s.axis[2], rp.orbitalAxisZ, 1e-5f)

                val tilt = WaterWorldResidentOrbitalMath.axisTiltFromCoreZDeg(rp.orbitalAxisX, rp.orbitalAxisY, rp.orbitalAxisZ)
                assertTrue("${s.key} tilt $tilt must be <= 8 deg", tilt <= 8.0f)

                val rs = snapT2.residentStates.getValue(s.key)
                assertTrue(rs.orbitalEnabled)
                val angleDeg = Math.toDegrees(rs.orbitalAngleRad.toDouble()).toFloat()
                assertEquals("${s.key} angle at T=2s", s.t2Deg, angleDeg, 1e-3f)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Category 2: Rodrigues Rotation Tests
    // -------------------------------------------------------------------------
    @Test
    fun c02_rodriguesMatrixOrthonormalityDetAxisInvarianceAndInverse() {
        val axes = listOf(
            floatArrayOf(0.04f, 0.03f, 0.9987f),
            floatArrayOf(-0.04f, 0.03f, 0.9987f),
            floatArrayOf(0.0f, 0.0f, 1.0f),
            floatArrayOf(-0.03f, 0.06f, 0.9977f),
            floatArrayOf(0.05f, -0.02f, 0.9985f),
        )
        val anglesDeg = listOf(0.0, 18.0, -26.0, 32.0, -38.0, 90.0, 180.0, -245.5, 360.0)

        for (rawAxis in axes) {
            val u = WaterWorldResidentOrbitalMath.normalizeAxis(rawAxis[0], rawAxis[1], rawAxis[2])
            for (deg in anglesDeg) {
                val rad = Math.toRadians(deg).toFloat()
                val r = WaterWorldResidentOrbitalMath.buildRodriguesMat3(u, rad)
                val v = WaterWorldResidentOrbitalMath.validateRotationMatrix(r)
                assertTrue("Rodrigues matrix invalid for deg=$deg: $v", v.isValid)
                assertEquals(1f, v.determinant, 1e-4f)
                assertTrue(v.maxOrthogonalityError <= 1e-4f)

                // Axis invariance: R * u == u
                val ru = WaterWorldResidentOrbitalMath.mulMat3Vec3(r, u[0], u[1], u[2])
                assertEquals(u[0], ru[0], 1e-4f)
                assertEquals(u[1], ru[1], 1e-4f)
                assertEquals(u[2], ru[2], 1e-4f)

                // Inverse check: R(-theta) * (R(theta) * p) == p
                val rInv = WaterWorldResidentOrbitalMath.buildRodriguesMat3(u, -rad)
                val p = floatArrayOf(0.73f, -0.41f, 1.25f)
                val rp = WaterWorldResidentOrbitalMath.mulMat3Vec3(r, p[0], p[1], p[2])
                val restored = WaterWorldResidentOrbitalMath.mulMat3Vec3(rInv, rp[0], rp[1], rp[2])
                assertEquals(p[0], restored[0], 1e-4f)
                assertEquals(p[1], restored[1], 1e-4f)
                assertEquals(p[2], restored[2], 1e-4f)

                // Trace angle recovery for angles in [0, 180]
                if (deg in 0.0..180.0) {
                    val recovered = WaterWorldResidentOrbitalMath.extractRotationAngleRad(r)
                    assertEquals(abs(rad), recovered, 1e-3f)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Category 3: Core-Local Coordinate Tests
    // -------------------------------------------------------------------------
    @Test
    fun c03_coreLocalTransformPreserves3DRadiusAndUsesRuntimeOpticalConfig() {
        val root = resolveWorkspaceRoot()
        val optJson = JSONObject(
            File(root, "releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1/02_SUPPLEMENTED_ASSETS/materials/optical_config.json").readText(),
        )
        val sphere = optJson.getJSONObject("sphere")
        val cx = sphere.getJSONArray("center_px").getDouble(0).toFloat()
        val cy = sphere.getJSONArray("center_px").getDouble(1).toFloat()
        val innerR = sphere.getDouble("inner_radius_px").toFloat()
        assertEquals(626.47575f, cx, 1e-3f)
        assertEquals(596.34413f, cy, 1e-3f)
        assertEquals(395.75f, innerR, 1e-3f)

        val profile = WaterWorldMotionProfile.defaultProfile()
        val snap = WaterWorldMotionEvaluator.evaluate(profile, timeSeconds = 7.3, staticReferenceMode = false)
        val testPoints = listOf(
            floatArrayOf(200f, 300f, 520f),
            floatArrayOf(1020f, 760f, -512f),
            floatArrayOf(48.906f, 570.570f, 495f),
            floatArrayOf(cx, cy, 0f), // Core center itself is invariant!
        )
        for (rs in snap.residentStates.values) {
            for (pt in testPoints) {
                val beforeDist = sqrt(
                    (pt[0] - cx) * (pt[0] - cx) +
                        (pt[1] - cy) * (pt[1] - cy) +
                        pt[2] * pt[2],
                )
                val rot = WaterWorldResidentOrbitalMath.transformCoreLocalPoint(
                    pt[0], pt[1], pt[2], cx, cy, 0f, innerR, rs.orbitalRotationMat3,
                )
                val afterDist = sqrt(
                    (rot[0] - cx) * (rot[0] - cx) +
                        (rot[1] - cy) * (rot[1] - cy) +
                        rot[2] * rot[2],
                )
                assertEquals("Rigid 3D orbit must preserve distance to Core center", beforeDist, afterDist, 1e-3f)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Category 4: Five Resident Determinism Tests
    // -------------------------------------------------------------------------
    @Test
    fun c04_fiveResidentsAreBitDeterministicIndependentOfFrameCountOrFps() {
        val profile = WaterWorldMotionProfile.defaultProfile()
        val targetT = 12.5

        val directA = WaterWorldMotionEvaluator.evaluate(profile, timeSeconds = targetT, frameId = 1L, staticReferenceMode = false)
        val directB = WaterWorldMotionEvaluator.evaluate(profile, timeSeconds = targetT, frameId = 999L, staticReferenceMode = false)

        // Compare against stepping via WaterWorldMotionRuntime at 25 FPS vs 100 FPS to exact 12.5s
        val rt25 = WaterWorldMotionRuntime(profile)
        rt25.play(0L)
        var snap25 = rt25.stepFrame(0L)
        for (i in 1..312) snap25 = rt25.stepFrame(i * 40_000_000L) // 12.48s
        snap25 = rt25.stepFrame(12_500_000_000L)

        val rt100 = WaterWorldMotionRuntime(profile)
        rt100.play(0L)
        var snap100 = rt100.stepFrame(0L)
        for (i in 1..1250) snap100 = rt100.stepFrame(i * 10_000_000L) // 12.50s

        for (key in listOf("G01", "G02", "A01", "A02", "C01")) {
            val a = directA.residentStates.getValue(key)
            val b = directB.residentStates.getValue(key)
            val s25 = snap25.residentStates.getValue(key)
            val s100 = snap100.residentStates.getValue(key)
            assertEquals(a, b)
            assertEquals(a.orbitalAngleRad, s25.orbitalAngleRad, 1e-5f)
            assertEquals(a.orbitalAngleRad, s100.orbitalAngleRad, 1e-5f)
            assertArrayEquals(a.orbitalRotationMat3, b.orbitalRotationMat3, 0f)
            assertArrayEquals(a.orbitalRotationMat3, s25.orbitalRotationMat3, 1e-5f)
            assertArrayEquals(a.orbitalRotationMat3, s100.orbitalRotationMat3, 1e-5f)
        }
    }

    // -------------------------------------------------------------------------
    // Category 5: 13 Segment Mapping Tests
    // -------------------------------------------------------------------------
    @Test
    fun c05_thirteenSegmentsShareExactFiveParentResidentStates() {
        val mappings = WaterWorldResidentContractV1.CANONICAL_SEGMENT_MAPPINGS
        assertEquals(13, mappings.size)
        assertEquals(8, mappings.count { it.segmentPass == "02_RESIDENT_BACK" })
        assertEquals(5, mappings.count { it.segmentPass == "10_RESIDENT_FRONT" })

        val profile = WaterWorldMotionProfile.defaultProfile()
        val snap = WaterWorldMotionEvaluator.evaluate(profile, timeSeconds = 4.0, staticReferenceMode = false)
        assertEquals(5, snap.residentStates.size)

        val expectedCounts = mapOf("G01" to 3, "G02" to 4, "A01" to 2, "A02" to 2, "C01" to 2)
        for ((gk, count) in expectedCounts) {
            val segs = mappings.filter { it.parentResidentGroup == gk }
            assertEquals("$gk segment count", count, segs.size)
            val parentState = snap.residentStates.getValue(gk)
            for (seg in segs) {
                assertEquals(gk, WaterWorldStaticRenderer.resolveResidentGroupKey(seg.segmentId))
                assertTrue(parentState.drawIds.contains(seg.segmentId))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Category 6: A01 Seam Tests
    // -------------------------------------------------------------------------
    @Test
    fun c06_a01BackFrontSeamRemainsZeroPixelsAcrossAllTimestampsAndFull360Rotation() {
        val root = resolveWorkspaceRoot()
        val sceneRoot = File(root, "releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1")
        val opt = JSONObject(File(sceneRoot, "02_SUPPLEMENTED_ASSETS/materials/optical_config.json").readText()).getJSONObject("sphere")
        val cx = opt.getJSONArray("center_px").getDouble(0).toFloat()
        val cy = opt.getJSONArray("center_px").getDouble(1).toFloat()
        val innerR = opt.getDouble("inner_radius_px").toFloat()

        val backVerts = JSONObject(File(sceneRoot, "02_SUPPLEMENTED_ASSETS/geometry/A01_back_upper_left_mesh.json").readText()).getJSONArray("vertices")
        val frontVerts = JSONObject(File(sceneRoot, "02_SUPPLEMENTED_ASSETS/geometry/A01_front_left_embrace_mesh.json").readText()).getJSONArray("vertices")
        val bn = backVerts.length()
        val beX = ((backVerts.getJSONArray(bn - 2).getDouble(0) + backVerts.getJSONArray(bn - 1).getDouble(0)) * 0.5).toFloat()
        val beY = ((backVerts.getJSONArray(bn - 2).getDouble(1) + backVerts.getJSONArray(bn - 1).getDouble(1)) * 0.5).toFloat()
        val fsX = ((frontVerts.getJSONArray(0).getDouble(0) + frontVerts.getJSONArray(1).getDouble(0)) * 0.5).toFloat()
        val fsY = ((frontVerts.getJSONArray(0).getDouble(1) + frontVerts.getJSONArray(1).getDouble(1)) * 0.5).toFloat()

        val profile = WaterWorldMotionProfile.defaultProfile()
        for (step in 0..120) {
            val t = step * 0.5 // 0.0s .. 60.0s
            val snap = WaterWorldMotionEvaluator.evaluate(profile, timeSeconds = t, staticReferenceMode = (step == 0))
            val a01 = snap.residentStates.getValue("A01")
            val rBe = WaterWorldResidentOrbitalMath.transformCoreLocalPoint(beX, beY, 495f, cx, cy, 0f, innerR, a01.orbitalRotationMat3)
            val rFs = WaterWorldResidentOrbitalMath.transformCoreLocalPoint(fsX, fsY, -510f, cx, cy, 0f, innerR, a01.orbitalRotationMat3)
            val gapPx = hypot((rBe[0] - rFs[0]).toDouble(), (rBe[1] - rFs[1]).toDouble())
            assertTrue("A01 seam gap at t=${t}s was ${gapPx}px (> 1.0px)", gapPx <= 1.0)
            assertEquals("A01 exact XY seam gap at t=${t}s", 0.0, gapPx, 1e-3)
        }
    }

    // -------------------------------------------------------------------------
    // Category 7: Back / Front Depth Safety Tests
    // -------------------------------------------------------------------------
    @Test
    fun c07_all13FrozenMeshesPreserveDepthSafetyAcrossFull360Orbit() {
        val root = resolveWorkspaceRoot()
        val sceneRoot = File(root, "releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1")
        val opt = JSONObject(File(sceneRoot, "02_SUPPLEMENTED_ASSETS/materials/optical_config.json").readText()).getJSONObject("sphere")
        val cx = opt.getJSONArray("center_px").getDouble(0).toFloat()
        val cy = opt.getJSONArray("center_px").getDouble(1).toFloat()
        val innerR = opt.getDouble("inner_radius_px").toFloat()
        val draws = JSONObject(File(sceneRoot, "03_SCENE_GRAPH/draw_bindings.json").readText()).getJSONArray("draws")
        val profile = WaterWorldMotionProfile.defaultProfile()

        var residentCount = 0
        for (i in 0 until draws.length()) {
            val d = draws.getJSONObject(i)
            val passId = d.getString("pass_id")
            if (passId != "02_RESIDENT_BACK" && passId != "10_RESIDENT_FRONT") continue
            residentCount++
            val drawId = d.getString("draw_id")
            val gk = requireNotNull(WaterWorldStaticRenderer.resolveResidentGroupKey(drawId))
            val rp = profile.residents.first { it.groupKey == gk }
            val axis = WaterWorldResidentOrbitalMath.normalizeAxis(rp.orbitalAxisX, rp.orbitalAxisY, rp.orbitalAxisZ)
            val modelZ = d.getJSONArray("model").getDouble(14).toFloat()
            val verts = JSONObject(File(sceneRoot, d.getString("mesh_path")).readText()).getJSONArray("vertices")

            for (deg in 0 until 360 step 15) {
                val mat = WaterWorldResidentOrbitalMath.buildRodriguesMat3(axis, Math.toRadians(deg.toDouble()).toFloat())
                for (v in 0 until verts.length()) {
                    val row = verts.getJSONArray(v)
                    val rot = WaterWorldResidentOrbitalMath.transformCoreLocalPoint(
                        row.getDouble(0).toFloat(),
                        row.getDouble(1).toFloat(),
                        row.getDouble(2).toFloat() + modelZ,
                        cx, cy, 0f, innerR, mat,
                    )
                    if (passId == "02_RESIDENT_BACK") {
                        assertTrue("$drawId back Z=${rot[2]} flipped <= 0 at deg=$deg", rot[2] > 0f)
                        assertTrue("$drawId back Z=${rot[2]} clipped behind background >= 1900 at deg=$deg", rot[2] < 1900f)
                    } else {
                        assertTrue("$drawId front Z=${rot[2]} moved behind near ecology >= 106 at deg=$deg", rot[2] < 106f)
                        assertTrue("$drawId front Z=${rot[2]} clipped near plane <= -2048 at deg=$deg", rot[2] > -2048f)
                    }
                }
            }
        }
        assertEquals(13, residentCount)
    }

    // -------------------------------------------------------------------------
    // Category 8: Shader Compile / Static GLSL Structure Tests
    // -------------------------------------------------------------------------
    @Test
    fun c08_residentRibbonVertexShaderStructureTransformOrderAndT0Bypass() {
        val vert = WaterWorldShaders.RESIDENT_RIBBON_VERTEX
        val frag = WaterWorldShaders.RESIDENT_RIBBON_FRAGMENT
        assertTrue(vert.startsWith("#version 300 es"))
        assertTrue(vert.contains("uniform mat3 uResidentOrbitMat;"))
        assertTrue(vert.contains("uniform vec3 uCoreCenterPx;"))
        assertTrue(vert.contains("uniform float uCoreRadiusPx;"))
        assertTrue(vert.contains("uniform int uResidentOrbitEnabled;"))
        assertTrue(vert.contains("if (uResidentOrbitEnabled == 1)"))
        assertTrue(vert.contains("uResidentOrbitMat * coreLocalNorm"))
        assertTrue(vert.contains("uResidentOrbitMat * worldNormal"))
        assertFalse("Must not re-introduce hardcoded 18.0 px offset", vert.contains("18.0"))

        // Verify exact transform order: V1 deform -> uModel -> Core-local 3D orbit -> gl_Position
        val idxDeform = vert.indexOf("if (uDeformMode == 3)")
        val idxModel = vert.indexOf("vec4 p = uModel * vec4(localPos, 1.0);")
        val idxOrbit = vert.indexOf("if (uResidentOrbitEnabled == 1)")
        val idxProj = vert.indexOf("gl_Position = vec4(")
        assertTrue("Transform order violated: deform=$idxDeform model=$idxModel orbit=$idxOrbit proj=$idxProj",
            idxDeform in 1 until idxModel && idxModel < idxOrbit && idxOrbit < idxProj)

        // V1 Fragment shader remains completely intact
        assertTrue(frag.contains("uResidentFlowParams"))
        assertTrue(frag.contains("vCanonicalS"))
        assertTrue(frag.contains("energyBand"))
    }

    // -------------------------------------------------------------------------
    // Category 9: GPU Uniform Contract Tests
    // -------------------------------------------------------------------------
    @Test
    fun c09_rendererUploadsAllWork32OrbitalUniformsWithoutPerSegmentMatrixAllocation() {
        val root = resolveWorkspaceRoot()
        val rendererSrc = File(root, "app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt").readText()
        assertTrue(rendererSrc.contains("s.residentRibbon.uniform(\"uCoreCenterPx\")"))
        assertTrue(rendererSrc.contains("s.residentRibbon.uniform(\"uCoreRadiusPx\")"))
        assertTrue(rendererSrc.contains("s.residentRibbon.uniform(\"uResidentOrbitEnabled\")"))
        assertTrue(rendererSrc.contains("s.residentRibbon.uniform(\"uResidentOrbitMat\")"))
        assertTrue(rendererSrc.contains("rState.orbitalRotationMat3"))
        assertFalse("Renderer must NOT build Rodrigues matrices inside draw loop", rendererSrc.contains("buildRodriguesMat3"))
        assertFalse("Water uniform hotfix regression guard: uWaterFlowPhase must stay removed", rendererSrc.contains("uWaterFlowPhase"))
    }

    // -------------------------------------------------------------------------
    // Category 10: Timeline Lifecycle Tests
    // -------------------------------------------------------------------------
    @Test
    fun c10_timelinePlayPauseResumeResetSpeedScalingAndActivityLifecycle() {
        val profile = WaterWorldMotionProfile.defaultProfile()
        val rt = WaterWorldMotionRuntime(profile)

        rt.play(1_000_000_000L)
        var snap = rt.stepFrame(1_000_000_000L)
        for (i in 1..40) snap = rt.stepFrame(1_000_000_000L + i * 50_000_000L) // +2.0s at 1.0x
        val g01At2sDeg = Math.toDegrees(snap.residentStates.getValue("G01").orbitalAngleRad.toDouble())
        assertEquals(32.0, g01At2sDeg, 0.05)

        // Pause -> orbital angle completely frozen
        rt.pause()
        val paused = rt.stepFrame(1_000_000_000L + 100 * 50_000_000L)
        assertEquals(32.0, Math.toDegrees(paused.residentStates.getValue("G01").orbitalAngleRad.toDouble()), 1e-4)

        // Resume at 0.5x for 2 wall seconds -> +1.0 sim second (+16 deg -> 48 deg)
        rt.setSpeedScale(0.5f)
        rt.resume(1_000_000_000L + 100 * 50_000_000L)
        for (i in 1..40) snap = rt.stepFrame(1_000_000_000L + (100 + i) * 50_000_000L)
        assertEquals(48.0, Math.toDegrees(snap.residentStates.getValue("G01").orbitalAngleRad.toDouble()), 0.05)

        // Activity pause & resume after 10s gap -> ZERO phase jump!
        rt.onActivityPause()
        rt.onActivityResume()
        val resumedNoJump = rt.stepFrame(1_000_000_000L + 340 * 50_000_000L)
        assertEquals(48.0, Math.toDegrees(resumedNoJump.residentStates.getValue("G01").orbitalAngleRad.toDouble()), 0.05)

        // Speed 1.5x for 2 wall seconds -> +3.0 sim seconds (+48 deg -> 96 deg)
        rt.setSpeedScale(1.5f)
        val baseNs = 1_000_000_000L + 340 * 50_000_000L
        for (i in 1..40) snap = rt.stepFrame(baseNs + i * 50_000_000L)
        assertEquals(96.0, Math.toDegrees(snap.residentStates.getValue("G01").orbitalAngleRad.toDouble()), 0.05)

        // Reset -> exact T=0 neutral
        val resetSnap = rt.reset(autoPlay = false)
        assertEquals(0.0, resetSnap.globalTimeSeconds, 0.0)
        for (rs in resetSnap.residentStates.values) {
            assertFalse(rs.orbitalEnabled)
            assertEquals(0f, rs.orbitalAngleRad, 0f)
            assertArrayEquals(WaterWorldResidentOrbitalMath.identityMat3(), rs.orbitalRotationMat3, 0f)
        }
    }

    // -------------------------------------------------------------------------
    // Category 11: Static T=0 Regression Tests
    // -------------------------------------------------------------------------
    @Test
    fun c11_staticT0RegressionDisablesOrbitAndPreservesExactStaticIdentity() {
        val profile = WaterWorldMotionProfile.defaultProfile()
        val staticSnap = WaterWorldDynamicFrameSnapshot.staticZero(profile)
        assertTrue(staticSnap.staticReferenceMode)
        assertEquals(0.0, staticSnap.globalTimeSeconds, 0.0)
        for (rs in staticSnap.residentStates.values) {
            assertFalse(rs.orbitalEnabled)
            assertEquals(0f, rs.orbitalAngleRad, 0f)
            assertEquals(0f, rs.flowActive, 0f)
            assertEquals(0f, rs.flowOffsetU, 0f)
            assertEquals(0f, rs.lateralWavePx, 0f)
            assertEquals(0f, rs.widthAmplitude, 0f)
            assertEquals(1f, rs.widthPulseScale, 0f)
            assertEquals(1f, rs.brightnessScale, 0f)
            assertArrayEquals(WaterWorldResidentOrbitalMath.identityMat3(), rs.orbitalRotationMat3, 0f)
        }
    }

    // -------------------------------------------------------------------------
    // Category 12: 60s Continuous Motion & P50/P90 Displacement Gate Tests
    // -------------------------------------------------------------------------
    @Test
    fun c12_continuous60SecondMotionAndP50P90DisplacementGatesAtT2AndT5() {
        val root = resolveWorkspaceRoot()
        val sceneRoot = File(root, "releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1")
        val opt = JSONObject(File(sceneRoot, "02_SUPPLEMENTED_ASSETS/materials/optical_config.json").readText()).getJSONObject("sphere")
        val cx = opt.getJSONArray("center_px").getDouble(0).toFloat()
        val cy = opt.getJSONArray("center_px").getDouble(1).toFloat()
        val innerR = opt.getDouble("inner_radius_px").toFloat()
        val draws = JSONObject(File(sceneRoot, "03_SCENE_GRAPH/draw_bindings.json").readText()).getJSONArray("draws")
        val profile = WaterWorldMotionProfile.defaultProfile()

        // Smooth 0..60s progression: finite, monotonic angle per Resident, valid orthonormal matrix
        val prevAngles = HashMap<String, Float>()
        for (step in 0..120) {
            val t = step * 0.5
            val snap = WaterWorldMotionEvaluator.evaluate(profile, t, step.toLong(), staticReferenceMode = (step == 0))
            for (rp in profile.residents) {
                val rs = snap.residentStates.getValue(rp.groupKey)
                assertTrue(rs.orbitalAngleRad.isFinite())
                val mv = WaterWorldResidentOrbitalMath.validateRotationMatrix(rs.orbitalRotationMat3)
                assertTrue("${rp.groupKey} invalid matrix at t=$t", mv.isValid)
                val prev = prevAngles[rp.groupKey]
                if (prev != null) {
                    val delta = rs.orbitalAngleRad - prev
                    if (rp.orbitalAngularVelocityDegPerSec > 0f) assertTrue(delta > 0f) else assertTrue(delta < 0f)
                }
                prevAngles[rp.groupKey] = rs.orbitalAngleRad
            }
        }

        // Pure 3D vertex displacement gates at T=2s (P50 >= 0.08R, P90 >= 0.18R) and T=5s (P50 >= 0.18R, P90 >= 0.35R)
        val snapT2 = WaterWorldMotionEvaluator.evaluate(profile, 2.0, 120L, staticReferenceMode = false)
        val snapT5 = WaterWorldMotionEvaluator.evaluate(profile, 5.0, 300L, staticReferenceMode = false)

        val dispByResT2 = HashMap<String, MutableList<Double>>()
        val dispByResT5 = HashMap<String, MutableList<Double>>()
        for (i in 0 until draws.length()) {
            val d = draws.getJSONObject(i)
            val passId = d.getString("pass_id")
            if (passId != "02_RESIDENT_BACK" && passId != "10_RESIDENT_FRONT") continue
            val gk = requireNotNull(WaterWorldStaticRenderer.resolveResidentGroupKey(d.getString("draw_id")))
            val modelZ = d.getJSONArray("model").getDouble(14).toFloat()
            val verts = JSONObject(File(sceneRoot, d.getString("mesh_path")).readText()).getJSONArray("vertices")
            val mat2 = snapT2.residentStates.getValue(gk).orbitalRotationMat3
            val mat5 = snapT5.residentStates.getValue(gk).orbitalRotationMat3
            val l2 = dispByResT2.getOrPut(gk) { mutableListOf() }
            val l5 = dispByResT5.getOrPut(gk) { mutableListOf() }
            for (v in 0 until verts.length()) {
                val row = verts.getJSONArray(v)
                val x = row.getDouble(0).toFloat()
                val y = row.getDouble(1).toFloat()
                val z = row.getDouble(2).toFloat() + modelZ
                val p2 = WaterWorldResidentOrbitalMath.transformCoreLocalPoint(x, y, z, cx, cy, 0f, innerR, mat2)
                val p5 = WaterWorldResidentOrbitalMath.transformCoreLocalPoint(x, y, z, cx, cy, 0f, innerR, mat5)
                l2 += sqrt(((p2[0] - x) * (p2[0] - x) + (p2[1] - y) * (p2[1] - y) + (p2[2] - z) * (p2[2] - z)).toDouble())
                l5 += sqrt(((p5[0] - x) * (p5[0] - x) + (p5[1] - y) * (p5[1] - y) + (p5[2] - z) * (p5[2] - z)).toDouble())
            }
        }
        fun pct(vals: List<Double>, p: Double): Double {
            val s = vals.sorted()
            return s[((s.size - 1) * p).toInt()]
        }
        for (gk in listOf("G01", "G02", "A01", "A02", "C01")) {
            val p50_2 = pct(dispByResT2.getValue(gk), 0.50) / innerR
            val p90_2 = pct(dispByResT2.getValue(gk), 0.90) / innerR
            val p50_5 = pct(dispByResT5.getValue(gk), 0.50) / innerR
            val p90_5 = pct(dispByResT5.getValue(gk), 0.90) / innerR
            assertTrue("$gk T=2s P50=$p50_2 < 0.08R", p50_2 >= 0.08)
            assertTrue("$gk T=2s P90=$p90_2 < 0.18R", p90_2 >= 0.18)
            assertTrue("$gk T=5s P50=$p50_5 < 0.18R", p50_5 >= 0.18)
            assertTrue("$gk T=5s P90=$p90_5 < 0.35R", p90_5 >= 0.35)
        }
    }

    // -------------------------------------------------------------------------
    // Category 13: Other Ecology & Frozen Asset Regression Tests
    // -------------------------------------------------------------------------
    @Test
    fun c13_otherEcologyCore32Slices11Passes5OpticsAndFrozenArchivesUnchanged() {
        val root = resolveWorkspaceRoot()
        val masterPng = File(root, "releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png")
        val v2Zip = File(root, "releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip")
        assertEquals(WaterWorldResidentContractV1.MASTER_PNG_SHA256, sha256Hex(masterPng))
        assertEquals("6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095", sha256Hex(v2Zip))

        val coreManifest = JSONObject(File(root, "app/src/main/assets/orb_v3/manifest/ORB_V3_ASSET_MANIFEST.json").readText())
        assertEquals(32, coreManifest.getJSONObject("volume").getJSONArray("slices").length())

        val graph = WaterWorldRenderGraph()
        graph.validateGraph()
        assertEquals(11, graph.stages.size)
        assertEquals(5, WaterWorldRenderGraph.OpticalNodeId.entries.size)

        val profile = WaterWorldMotionProfile.defaultProfile().also { it.validate() }
        val snap = WaterWorldMotionEvaluator.evaluate(profile, 3.5, 210L, staticReferenceMode = false)
        assertEquals(47, snap.elementStates.size)
        assertEquals(28, snap.dependencyStates.size)
        assertEquals(4, snap.fishStates.size)
        assertEquals(5, snap.residentStates.size)
        assertEquals(2, snap.plantStates.size)
        assertEquals(3, snap.rockStates.size)
        assertTrue(snap.rockStates.values.all { it.physicallyStationary && it.offsetX == 0f && it.offsetY == 0f })
        assertEquals(9, snap.outerBubbleStates.size)
        assertEquals(14, snap.innerBubbleStates.size)
        assertEquals(WaterWorldMotionContract.CORE_VOLUME_SLICE_COUNT, snap.coreState.sliceCount)
        assertEquals(WaterWorldMotionContract.CORE_VOLUME_SLICE_SEMANTICS, snap.coreState.sliceSemantics)
        assertFalse(snap.coreState.createsDuplicateGlassShell)
    }
}
