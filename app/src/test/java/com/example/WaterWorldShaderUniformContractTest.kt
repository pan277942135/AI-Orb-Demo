package com.example

import com.example.orb.gl.CoreFluidShaders
import com.example.waterworld.WaterWorldCoreMotionAdapter
import com.example.waterworld.WaterWorldMotionEvaluator
import com.example.waterworld.WaterWorldMotionProfile
import com.example.waterworld.WaterWorldProgram
import com.example.waterworld.WaterWorldRenderGraph
import com.example.waterworld.WaterWorldShaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Work 3.1.1 P0 Anti-Regression Shader Uniform Contract Test (`WATER_WORLD_V2_1_WORK3_1_1_WATER_UNIFORM_CONTRACT_HOTFIX_V1`).
 *
 * Guarantees that EVERY uniform strictly queried via `WaterWorldProgram.uniform(...)` across all 12
 * Water World GLSL ES 3.00 shader programs is BOTH declared AND actively read in shader executable code
 * (preventing GLSL compiler dead-uniform elimination failures like `Missing scene uniform uWaterFlowPhase`
 * on strict mobile GPU drivers such as HUAWEI Maleoon 935), while preserving the real 3D Core Volume
 * flow chain (`uFluidPhase -> CoreFluidShaders -> sampleFluidVolume3D -> computeVolumeCurl`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldShaderUniformContractTest {

    private fun resolveWorkspaceRoot(): File {
        val candidates = listOf(File("."), File(".."), File("/app/applet"))
        return candidates.firstOrNull {
            File(it, "app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt").isFile
        } ?: error("Unable to locate workspace root with WaterWorldStaticRenderer.kt")
    }

    private fun stripGlslComments(glsl: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(glsl, "")
        return Regex("//[^\\n]*").replace(noBlock, "")
    }

    private fun parseDeclaredAndReadUniforms(glsl: String): Map<String, Int> {
        val clean = stripGlslComments(glsl)
        val declRegex = Regex("\\buniform\\s+\\w+\\s+(\\w+)(?:\\[\\d+])?\\s*;")
        val declared = declRegex.findAll(clean).map { it.groupValues[1] }.toSet()
        val executableBody = declRegex.replace(clean, "")
        return declared.associateWith { name ->
            Regex("\\b${Regex.escape(name)}\\b").findAll(executableBody).count()
        }
    }

    @Test
    fun t01_waterFragmentDoesNotDeclareOrReferenceUWaterFlowPhaseOr2DFakeCore() {
        val waterFrag = WaterWorldShaders.WATER_FRAGMENT
        assertFalse(
            "WATER_FRAGMENT must NOT declare unused uniform uWaterFlowPhase",
            waterFrag.contains("uWaterFlowPhase"),
        )
        assertFalse(
            "WATER_FRAGMENT must NOT re-introduce fake 2D uCoreFluidParams",
            waterFrag.contains("uCoreFluidParams"),
        )
        assertFalse(
            "WATER_FRAGMENT must NOT re-introduce fake 2D core swirl",
            waterFrag.contains("float swirl = sin("),
        )
        assertFalse(
            "WATER_FRAGMENT must NOT re-introduce fake 2D core filament",
            waterFrag.contains("float filament = exp("),
        )
    }

    @Test
    fun t02_renderWaterPassDoesNotQueryUWaterFlowPhaseAndStrictUniformCheckPreserved() {
        val root = resolveWorkspaceRoot()
        val rendererSrc = File(
            root,
            "app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt",
        ).readText()
        val programSrc = File(
            root,
            "app/src/main/java/com/example/waterworld/WaterWorldProgram.kt",
        ).readText()

        assertFalse(
            "WaterWorldStaticRenderer must NOT query or upload uWaterFlowPhase",
            rendererSrc.contains("uWaterFlowPhase"),
        )
        assertTrue(
            "WaterWorldProgram.uniform() must preserve strict non-negative check",
            programSrc.contains("check(it >= 0) { \"Missing scene uniform \$name\" }"),
        )
        // Verify motion runtime snapshot field remains untouched for downstream consumers
        val snap = WaterWorldMotionEvaluator.evaluate(
            profile = WaterWorldMotionProfile.defaultProfile(),
            timeSeconds = 2.5,
            frameId = 150L,
            randomSeed = 42L,
            staticReferenceMode = false,
        )
        assertTrue("waterFlowPhaseRad remains finite in snapshot", snap.waterOptics.waterFlowPhaseRad.isFinite())
    }

    @Test
    fun t03_allRemainingWaterFragmentUniformsAreActivelyReadInShaderBody() {
        val usage = parseDeclaredAndReadUniforms(WaterWorldShaders.WATER_FRAGMENT)
        assertEquals("WATER_FRAGMENT must declare exactly 21 active uniforms", 21, usage.size)
        val unused = usage.filterValues { it == 0 }.keys
        assertTrue(
            "WATER_FRAGMENT has unused uniform(s) that GLES drivers will strip: $unused",
            unused.isEmpty(),
        )
        // Spot-check critical dynamic water uniforms remain actively read
        assertTrue((usage["uWaterNormalOffset"] ?: 0) >= 2)
        assertTrue((usage["uMeniscusPhases"] ?: 0) >= 2)
        assertTrue((usage["uSceneSnapshot"] ?: 0) >= 1)
    }

    @Test
    fun t04_allTwelveWaterWorldProgramsHaveExactActiveUniformParityWithRenderer() {
        val root = resolveWorkspaceRoot()
        val rendererSrc = File(
            root,
            "app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt",
        ).readText()

        val programs: Map<String, Pair<String, String>> = linkedMapOf(
            "opaque" to (WaterWorldShaders.MESH_VERTEX to WaterWorldShaders.OPAQUE_FRAGMENT),
            "transparent" to (WaterWorldShaders.MESH_VERTEX to WaterWorldShaders.TRANSPARENT_FRAGMENT),
            "residentRibbon" to (WaterWorldShaders.RESIDENT_RIBBON_VERTEX to WaterWorldShaders.RESIDENT_RIBBON_FRAGMENT),
            "water" to (WaterWorldShaders.FULLSCREEN_VERTEX to WaterWorldShaders.WATER_FRAGMENT),
            "glass" to (WaterWorldShaders.FULLSCREEN_VERTEX to WaterWorldShaders.GLASS_FRAGMENT),
            "caustics" to (WaterWorldShaders.FULLSCREEN_VERTEX to WaterWorldShaders.CAUSTICS_FRAGMENT),
            "bloomBright" to (WaterWorldShaders.FULLSCREEN_VERTEX to WaterWorldShaders.BLOOM_BRIGHT_FRAGMENT),
            "bloomBlur" to (WaterWorldShaders.FULLSCREEN_VERTEX to WaterWorldShaders.BLOOM_BLUR_FRAGMENT),
            "bloomComposite" to (WaterWorldShaders.FULLSCREEN_VERTEX to WaterWorldShaders.BLOOM_COMPOSITE_FRAGMENT),
            "particleBokeh" to (WaterWorldShaders.MESH_VERTEX to WaterWorldShaders.PARTICLE_BOKEH_FRAGMENT),
            "bokehOrb" to (WaterWorldShaders.FULLSCREEN_VERTEX to WaterWorldShaders.BOKEH_ORB_FRAGMENT),
            "present" to (WaterWorldShaders.FULLSCREEN_VERTEX to WaterWorldShaders.PRESENT_FRAGMENT),
        )

        val failures = mutableListOf<String>()
        for ((progName, sources) in programs) {
            val vertUsage = parseDeclaredAndReadUniforms(sources.first)
            val fragUsage = parseDeclaredAndReadUniforms(sources.second)
            val combinedUsage = HashMap<String, Int>()
            for ((u, c) in vertUsage) combinedUsage[u] = (combinedUsage[u] ?: 0) + c
            for ((u, c) in fragUsage) combinedUsage[u] = (combinedUsage[u] ?: 0) + c

            // 1) No declared uniform in any program may be dead (0 body reads)
            val deadDeclared = combinedUsage.filterValues { it == 0 }.keys
            if (deadDeclared.isNotEmpty()) {
                failures += "[$progName] dead declared uniform(s) in GLSL: $deadDeclared"
            }

            // 2) Every uniform queried by WaterWorldStaticRenderer via `s.<prog>.uniform("...")`
            //    must exist AND be actively read in GLSL so glGetUniformLocation >= 0 at link time
            val queried = Regex("\\bs\\.${Regex.escape(progName)}\\.uniform\\(\"([^\"]+)\"\\)")
                .findAll(rendererSrc)
                .map { it.groupValues[1] }
                .toSet()
            for (q in queried) {
                val reads = combinedUsage[q]
                if (reads == null) {
                    failures += "[$progName] renderer queries undeclared uniform '$q'"
                } else if (reads <= 0) {
                    failures += "[$progName] renderer queries dead/unused uniform '$q' (will be stripped by GLES compiler!)"
                }
            }
        }
        assertTrue(
            "Shader <-> Renderer active uniform contract violations:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    @Test
    fun t05_core3DRealFluidFlowChainAndUniformsRemainIntact() {
        val root = resolveWorkspaceRoot()
        val rendererSrc = File(
            root,
            "app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt",
        ).readText()
        val coreFragUsage = parseDeclaredAndReadUniforms(CoreFluidShaders.CORE_GLASS_FRAG)
        val coreVertUsage = parseDeclaredAndReadUniforms(CoreFluidShaders.CORE_GLASS_VERT)

        // Core 3D Volume uniforms must all be declared AND actively read in CORE_GLASS_FRAG
        val requiredCoreFragUniforms = listOf(
            "uFluidVolume",
            "uCoreRotMat",
            "uFluidPhase",
            "uCausticsPhase",
            "uDensityScale",
            "uCoreRenderMode",
            "uCoreDiagMode",
            "uCoreColorShift",
        )
        for (u in requiredCoreFragUniforms) {
            val reads = coreFragUsage[u] ?: 0
            assertTrue("CORE_GLASS_FRAG must actively read $u (found $reads reads)", reads > 0)
        }
        assertTrue(
            "CORE_GLASS_VERT must actively read uCoreRenderMode",
            (coreVertUsage["uCoreRenderMode"] ?: 0) > 0,
        )

        // Real 3D volume sampling & curl advection & 3D rotation functions intact
        assertTrue(CoreFluidShaders.CORE_GLASS_FRAG.contains("uniform sampler3D uFluidVolume;"))
        assertTrue(CoreFluidShaders.CORE_GLASS_FRAG.contains("sampleFluidVolume3D"))
        assertTrue(CoreFluidShaders.CORE_GLASS_FRAG.contains("computeVolumeCurl"))
        assertTrue(CoreFluidShaders.CORE_GLASS_FRAG.contains("transpose(uCoreRotMat)"))

        // Single-timeline snapshot -> CoreMotionAdapter -> AIOrbMotionSnapshot -> uFluidPhase
        val snapT0 = WaterWorldMotionEvaluator.evaluate(
            profile = WaterWorldMotionProfile.defaultProfile(),
            timeSeconds = 0.0,
            frameId = 0L,
            randomSeed = 42L,
            staticReferenceMode = true,
        )
        val snapT4 = WaterWorldMotionEvaluator.evaluate(
            profile = WaterWorldMotionProfile.defaultProfile(),
            timeSeconds = 4.0,
            frameId = 240L,
            randomSeed = 42L,
            staticReferenceMode = false,
        )
        val orbT0 = WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(snapT0)
        val orbT4 = WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(snapT4)
        assertTrue("uFluidPhase must advance smoothly from T=0 to T=4s", orbT4.fluidPhase > orbT0.fluidPhase + 0.5f)
        assertTrue("coreRotationAngleDeg must advance smoothly from T=0 to T=4s", orbT4.coreRotationAngleDeg > 10f)

        // Renderer executes drawVolumeOnly() inside PassId.WATER_INTERIOR and preserves 11 passes / 5 optics
        assertTrue(rendererSrc.contains("coreVol.drawVolumeOnly("))
        val graph = WaterWorldRenderGraph()
        graph.validateGraph()
        assertEquals(11, graph.stages.size)
        assertEquals(5, WaterWorldRenderGraph.OpticalNodeId.entries.size)
    }
}
