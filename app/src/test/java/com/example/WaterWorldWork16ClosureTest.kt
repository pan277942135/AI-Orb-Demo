package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.waterworld.WaterWorldMotionContract
import com.example.waterworld.WaterWorldPreviewActivity
import com.example.waterworld.WaterWorldRenderGraph
import com.example.waterworld.WaterWorldRenderTargetPool
import com.example.waterworld.WaterWorldSceneBindings
import com.example.waterworld.WaterWorldShaders
import com.example.waterworld.WaterWorldTextureCache
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Comprehensive API 36 JVM & Robolectric test suite verifying all 12 Work 1.6 closure contracts:
 *  1. Scene Graph schema (14 objects) & Ground Truth vs Render Hypothesis separation
 *  2. Render Graph DAG dependencies & cycle rejection
 *  3. 11-stage pass order & 5 optical nodes coverage
 *  4. FBO target pool sizing & completeness plan
 *  5. FBO read/write feedback loop rejection
 *  6. Depth/Alpha per-material policy enforcement
 *  7. Texture SHA-256 verification & frozen Master texture rejection
 *  8. Texture Cache deduplication, alias sharing, SHA conflict error & single-delete lifecycle
 *  9. EGL context recreation lifecycle contract
 * 10. WaterWorldPreviewActivity Robolectric launch & debug-only manifest isolation (MainActivity untouched)
 * 11. Capture integrity & telemetry JSON schema validation
 * 12. 6-axis visual scoring calculation & 47-element / 28-edge / 32-Z-slice Motion Contract
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldWork16ClosureTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (dir != null) {
            if (File(dir, "releases/water-world-v2-v21").isDirectory) return dir
            dir = dir.parentFile
        }
        error("Could not locate repository root from ${System.getProperty("user.dir")}")
    }

    private fun scenePackageRoot(): File =
        File(repoRoot(), "releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1")

    private fun work16Root(): File =
        File(repoRoot(), "releases/water-world-v2-v21/work1_6_structure_closure_android_gpu")

    @Test
    fun `01_scene_graph_schema_and_ground_truth_vs_hypothesis_separation`() {
        val sceneGraph = JSONObject(File(work16Root(), "03_SCENE_GRAPH/SCENE_GRAPH_CANONICAL.json").readText())
        assertEquals(14, sceneGraph.getJSONArray("nodes").length())
        assertTrue(sceneGraph.getBoolean("render_order_is_not_ground_truth_occlusion"))

        val occlusion = JSONObject(File(work16Root(), "03_SCENE_GRAPH/OCCLUSION_GRAPH_CANONICAL.json").readText())
        assertEquals(0, occlusion.getJSONArray("front_object_back_object_edges").length())
        assertEquals("BLOCKED_DIRECTION_AND_GROUND_TRUTH", occlusion.getString("status"))
        val hypothesis = occlusion.getJSONObject("render_hypothesis_occlusion")
        assertTrue(hypothesis.getJSONArray("edges").length() >= 9)

        val gtSplit = JSONObject(File(work16Root(), "02_GROUND_TRUTH/GROUND_TRUTH_AND_HYPOTHESIS.json").readText())
        assertTrue(gtSplit.getString("policy").contains("never overwrite Ground Truth"))
        val gtObjects = gtSplit.getJSONArray("objects")
        assertEquals(14, gtObjects.length())
        for (i in 0 until gtObjects.length()) {
            val obj = gtObjects.getJSONObject(i)
            assertTrue(obj.has("ground_truth_metric_status"))
            assertTrue(obj.has("render_hypothesis"))
        }
    }

    @Test
    fun `02_render_graph_dag_validation_and_cycle_rejection`() {
        val graph = WaterWorldRenderGraph(1254)
        graph.validateGraph()
        assertEquals(11, graph.stages.size)

        // Inject a forward/cyclic dependency: Pass 01 depending on Pass 11 must throw
        val brokenStages = graph.stages.toMutableList()
        brokenStages[0] = brokenStages[0].copy(
            inputDependencies = listOf(WaterWorldRenderGraph.PassId.FINAL_COMPOSITE)
        )
        try {
            WaterWorldRenderGraph.validateGraph(brokenStages)
            fail("Expected DAG validation to reject forward/cyclic dependency")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("missing or executes after"))
        }
    }

    @Test
    fun `03_eleven_stage_pass_order_and_five_optical_nodes_and_eight_shaders`() {
        val stages = WaterWorldRenderGraph.canonical11StageGraph(1254)
        val expectedOrder = listOf(
            "01_BACKGROUND",
            "02_RESIDENT_BACK",
            "03_SPHERE_BACK",
            "04_WATER_INTERIOR",
            "05_FAR_ECOLOGY",
            "06_LIGHTING",
            "07_FISH",
            "08_NEAR_ECOLOGY",
            "09_SPHERE_FRONT",
            "10_RESIDENT_FRONT",
            "11_FINAL_COMPOSITE"
        )
        assertEquals(expectedOrder, stages.map { it.passId.wireId })

        val coveredOptics = stages.flatMap { it.opticalNodes }.toSet()
        assertEquals(WaterWorldRenderGraph.OpticalNodeId.entries.toSet(), coveredOptics)
        assertEquals(5, coveredOptics.size)
        assertEquals(8, WaterWorldShaders.SHADER_PROGRAM_NAMES.size)

        // Ensure all 8 shader modules are non-empty GLSL ES 3.0 programs
        val sources = listOf(
            WaterWorldShaders.MESH_VERTEX,
            WaterWorldShaders.FULLSCREEN_VERTEX,
            WaterWorldShaders.OPAQUE_FRAGMENT,
            WaterWorldShaders.TRANSPARENT_FRAGMENT,
            WaterWorldShaders.RESIDENT_RIBBON_FRAGMENT,
            WaterWorldShaders.WATER_FRAGMENT,
            WaterWorldShaders.GLASS_FRAGMENT,
            WaterWorldShaders.CAUSTICS_FRAGMENT,
            WaterWorldShaders.BLOOM_BRIGHT_FRAGMENT,
            WaterWorldShaders.BLOOM_BLUR_FRAGMENT,
            WaterWorldShaders.BLOOM_COMPOSITE_FRAGMENT,
            WaterWorldShaders.PARTICLE_BOKEH_FRAGMENT,
            WaterWorldShaders.BOKEH_ORB_FRAGMENT,
            WaterWorldShaders.PRESENT_FRAGMENT
        )
        for (src in sources) {
            assertTrue(src.startsWith("#version 300 es"))
        }
    }

    @Test
    fun `04_fbo_target_pool_sizing_and_downsample_plan`() {
        val plan = WaterWorldRenderTargetPool.plannedTargetDimensions(1254)
        assertEquals(1254 to 1254, plan["scene"])
        assertEquals(1254 to 1254, plan["snapshot"])
        assertEquals(1254 to 1254, plan["finalTarget"])
        assertEquals(1254 to 1254, plan["diagnostic"])
        assertEquals(627 to 627, plan["bloomHalfA"])
        assertEquals(627 to 627, plan["bloomHalfB"])
        assertEquals(313 to 313, plan["bloomQuarterA"])
        assertEquals(313 to 313, plan["bloomQuarterB"])
    }

    @Test
    fun `05_fbo_feedback_loop_rejection_in_graph_and_target_pool`() {
        // 1. Target pool runtime texture ID feedback rejection
        WaterWorldRenderTargetPool.assertNoFeedbackIds(listOf(10, 11), 12, "09_SPHERE_FRONT")
        try {
            WaterWorldRenderTargetPool.assertNoFeedbackIds(listOf(10, 12), 12, "09_SPHERE_FRONT")
            fail("Expected FBO feedback exception when input texture == output texture")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("FBO feedback loop"))
        }

        // 2. RenderGraph static feedback rejection
        val stages = WaterWorldRenderGraph.canonical11StageGraph(1254).toMutableList()
        stages[2] = stages[2].copy(inputTextures = listOf("scene"), outputTarget = "scene")
        try {
            WaterWorldRenderGraph.validateGraph(stages)
            fail("Expected RenderGraph validation to reject target self-read")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("FBO feedback loop"))
        }
    }

    @Test
    fun `06_depth_and_alpha_per_material_policy`() {
        val loaded = WaterWorldSceneBindings.loadScene(scenePackageRoot())
        assertEquals(1254, loaded.canvasSize)
        assertTrue(loaded.bindings.size >= 50)

        for (b in loaded.bindings) {
            val expectedPolicy = WaterWorldRenderGraph.depthPolicyForMaterial(b.materialName, b.depthWrite)
            when (b.materialName) {
                "background" -> {
                    assertTrue(b.depthWrite)
                    assertEquals(WaterWorldRenderGraph.DepthPolicy.OPAQUE_WRITE, expectedPolicy)
                }
                "resident", "water", "glass", "bubble" -> {
                    assertFalse("Translucent ${b.objectId} must not write depth", b.depthWrite)
                    assertEquals(WaterWorldRenderGraph.DepthPolicy.TRANSLUCENT_READ_ONLY, expectedPolicy)
                }
                "fish", "plant", "rock" -> {
                    assertTrue("Alpha cutout ${b.objectId} should have positive alphaCutoff", b.alphaCutoff > 0f)
                }
            }
        }
    }

    @Test
    fun `07_texture_sha256_verification_and_master_texture_rejection`() {
        val loaded = WaterWorldSceneBindings.loadScene(scenePackageRoot())
        val validSha = "a".repeat(64)
        for (b in loaded.bindings) {
            b.texturePath?.let { path ->
                assertFalse("Master PNG must never be referenced: $path", path.contains("奇幻"))
                assertFalse("Master PNG must never be referenced: $path", path.lowercase().contains("master"))
                assertEquals(64, requireNotNull(b.textureSha256).length)
            }
        }

        val cache = WaterWorldTextureCache(uploader = { _, _ -> 100 }, deleter = {})
        try {
            cache.acquire("01_REUSED_ASSETS/奇幻水晶球中的水下生态世界 (1).png", validSha)
            fail("Expected master PNG path rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Master artwork is forbidden"))
        }
    }

    @Test
    fun `08_texture_cache_dedup_alias_sharing_conflict_and_single_delete`() {
        var nextId = 10
        val deletedIds = mutableListOf<Int>()
        val cache = WaterWorldTextureCache(
            uploader = { _, _ -> nextId++ },
            deleter = { id -> deletedIds += id }
        )
        val shaA = "1".repeat(64)
        val shaB = "2".repeat(64)

        val id1 = cache.acquire("01_REUSED_ASSETS/05_FISH/fish_01.png", shaA)
        val id2 = cache.acquire("01_REUSED_ASSETS/05_FISH/fish_01.png", shaA)
        val idAlias = cache.acquire("01_REUSED_ASSETS/05_FISH/fish_01_alias.png", shaA)
        val idOther = cache.acquire("01_REUSED_ASSETS/05_FISH/fish_02.png", shaB)

        assertEquals(id1, id2)
        assertEquals(id1, idAlias)
        assertNotEquals(id1, idOther)
        assertEquals(2, cache.uniqueGpuTextureCount())
        assertEquals(3, cache.logicalAliasCount())
        assertEquals(2, cache.totalUploadCount)
        assertEquals(2, cache.totalCacheHitCount)

        // Same logical path with conflicting SHA must fail loudly
        try {
            cache.acquire("01_REUSED_ASSETS/05_FISH/fish_01.png", shaB)
            fail("Expected conflicting SHA exception")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("Conflicting SHA-256"))
        }

        // Release deletes every unique texture handle exactly once
        val releasedCount = cache.releaseAll()
        assertEquals(2, releasedCount)
        assertEquals(listOf(id1, idOther), deletedIds)
        assertEquals(0, cache.uniqueGpuTextureCount())
        // Second release is a safe no-op
        assertEquals(0, cache.releaseAll())
        assertEquals(2, deletedIds.size)
    }

    @Test
    fun `09_egl_context_recreation_lifecycle_resets_handles_cleanly`() {
        var generation = 0
        val deleted = mutableListOf<Int>()
        val cache1 = WaterWorldTextureCache(uploader = { _, _ -> ++generation }, deleter = { deleted += it })
        val h1 = cache1.acquire("01_REUSED_ASSETS/01_BACKGROUND/background.png", "a".repeat(64))
        assertEquals(1, h1)
        // Simulated EGL context loss: release old cache and allocate fresh context cache
        cache1.releaseAll()
        val cache2 = WaterWorldTextureCache(uploader = { _, _ -> ++generation }, deleter = { deleted += it })
        val h2 = cache2.acquire("01_REUSED_ASSETS/01_BACKGROUND/background.png", "a".repeat(64))
        assertEquals(2, h2)
        assertNotEquals(h1, h2)
        cache2.releaseAll()
        assertEquals(listOf(1, 2), deleted)
    }

    @Test
    fun `10_preview_activity_robolectric_launch_and_debug_isolation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager

        // WaterWorldPreviewActivity must be resolvable in debug build
        val previewIntent = Intent(context, WaterWorldPreviewActivity::class.java)
        val previewInfo = pm.resolveActivity(previewIntent, 0)
        assertNotNull("WaterWorldPreviewActivity must be registered in debug manifest", previewInfo)

        // MainActivity remains the sole MAIN/LAUNCHER entry
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchers = pm.queryIntentActivities(launcherIntent, 0)
        assertTrue(launchers.any { it.activityInfo.name.endsWith("MainActivity") })
        assertFalse(launchers.any { it.activityInfo.name.endsWith("WaterWorldPreviewActivity") })

        // Launch WaterWorldPreviewActivity under Robolectric API 36 and cycle lifecycle
        val controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java)
            .create()
            .start()
            .resume()
        val activity = controller.get()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }

    @Test
    fun `11_capture_integrity_and_telemetry_schema_validation`() {
        val passDir = File(work16Root(), "05_RENDER_PASS_EVIDENCE")
        val telemetry = JSONObject(File(passDir, "SOFTWARE_REFERENCE_TELEMETRY.json").readText())
        assertEquals(11, telemetry.getInt("stage_count"))
        assertEquals(5, telemetry.getInt("optical_node_count"))
        assertFalse(telemetry.getBoolean("hardware_gpu_verified"))
        assertEquals("SOFTWARE_REFERENCE_PIPELINE_CPU_1TO1", telemetry.getString("classification"))

        val passShas = telemetry.getJSONObject("pass_png_sha256")
        assertEquals(11, passShas.length())
        for (passId in WaterWorldRenderGraph.PassId.entries) {
            val f = File(passDir, "${passId.wireId.lowercase()}.png")
            assertTrue("Missing pass PNG ${f.name}", f.isFile && f.length() > 1000)
            val actualSha = sha256Hex(f)
            assertEquals("SHA mismatch for ${f.name}", passShas.getString(passId.wireId), actualSha)
        }

        val optShas = telemetry.getJSONObject("optical_png_sha256")
        assertEquals(5, optShas.length())
        for (opt in WaterWorldRenderGraph.OpticalNodeId.entries) {
            val f = File(passDir, "opt_${opt.wireId}.png")
            assertTrue("Missing optical PNG ${f.name}", f.isFile && f.length() > 1000)
            assertEquals("SHA mismatch for ${f.name}", optShas.getString(opt.wireId), sha256Hex(f))
        }
    }

    @Test
    fun `12_six_axis_visual_score_and_47_element_motion_contract`() {
        // Verify 6-axis weighted formula & >=80 convergence on software reference evaluation
        val visScore = JSONObject(File(work16Root(), "08_VISUAL_VALIDATION/visual_score.json").readText())
        val dims = visScore.getJSONObject("six_dimension_scores")
        val recomputed = (
            dims.getDouble("composition") +
                dims.getDouble("geometry") +
                dims.getDouble("resident") +
                dims.getDouble("material") +
                dims.getDouble("lighting") +
                dims.getDouble("depth")
            ) / 6.0
        val reported = visScore.getDouble("overall_score")
        assertEquals(recomputed, reported, 0.05)
        assertTrue("Expected converged score >= 80.0, got $reported", reported >= 80.0)
        assertFalse(visScore.getBoolean("master_used_as_texture"))
        assertFalse(visScore.getBoolean("hardware_gpu_verified"))

        // Verify Motion Compatibility Contract: 47 elements, 28 edges, 32 spatial Z-slices, null unverified rates
        assertEquals(47, WaterWorldMotionContract.CANONICAL_47_ELEMENTS.size)
        assertEquals(47, WaterWorldMotionContract.CANONICAL_47_ELEMENTS.distinct().size)
        assertEquals(28, WaterWorldMotionContract.CANONICAL_28_DEPENDENCIES.size)
        assertEquals(32, WaterWorldMotionContract.CORE_VOLUME_SLICE_COUNT)
        assertEquals("SPATIAL_Z_SLICE_NOT_TIME_FRAME", WaterWorldMotionContract.CORE_VOLUME_SLICE_SEMANTICS)

        val bindings = WaterWorldMotionContract.defaultStaticBindings()
        assertEquals(47, bindings.size)
        for (mb in bindings) {
            assertNull(mb.angularVelocity)
            assertNull(mb.phaseOffset)
            assertNull(mb.amplitude)
            assertNull(mb.frequency)
            assertNull(mb.flowVelocity)
            assertNull(mb.deformationRate)
        }

        // Unverified non-null dynamic speed must be rejected
        try {
            WaterWorldMotionContract.MotionBinding(
                elementId = "WW_FISH_01",
                motionModel = "SWIM",
                angularVelocity = 1.5f,
                waterWorldMotionValuesValidated = false
            )
            fail("Expected unverified non-null angularVelocity to throw")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Unverified angularVelocity"))
        }

        val snap = WaterWorldMotionContract.SharedFrameSnapshot.staticZero()
        assertEquals(0.0, snap.timeSeconds, 0.0)
        assertTrue(snap.staticReferenceMode)
    }

    private fun sha256Hex(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
