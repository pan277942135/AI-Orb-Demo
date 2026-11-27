package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.waterworld.WaterWorldPreviewActivity
import com.example.waterworld.WaterWorldRenderGraph
import com.example.waterworld.WaterWorldRenderTargetPool
import com.example.waterworld.WaterWorldSceneBindings
import com.example.waterworld.WaterWorldStaticRenderer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * Robolectric API 36 test suite verifying Work 2 static fidelity convergence contracts:
 *  1. Source Lock integrity (Master PNG, V2 ZIP, V2.1 ZIP SHAs & honest GPU flags)
 *  2. 11-Pass Render Graph, 5 Optical Nodes, 4 Fish & 5 Split Residents with GT/Hypothesis separation
 *  3. FBO GPU memory accounting (plannedFboMemoryBytes)
 *  4. Viewport-accurate GL bottom-left -> Android Bitmap top-left crop mapping for PixelCopy
 *  5. Zero-permission SAF Evidence ZIP creation intent & manifest storage-permission compliance
 *  6. Evidence ZIP packaging integrity
 *  7. 5-Round Work 2 visual convergence progression (>= 80.0 unmodified 6-axis & improvement over baseline)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldWork2StaticFidelityTest {

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

    private fun work2Root(): File =
        File(repoRoot(), "releases/water-world-v2-v21/work2_gpu_static_fidelity")

    @Test
    fun `01_work2_source_lock_and_honest_gpu_status`() {
        val lock = JSONObject(File(work2Root(), "00_SOURCE_LOCK/WORK2_SOURCE_LOCK.json").readText())
        assertEquals("WATER_WORLD_V21_WORK2_GPU_STATIC_FIDELITY_CONVERGENCE_V1", lock.getString("task_id"))
        assertEquals("WORK2_ACTIVE_WITH_RENDER_HYPOTHESES", lock.getString("status"))
        val frozen = lock.getJSONObject("frozen_sources")
        val master = frozen.getJSONObject("master_png")
        assertEquals(
            "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589",
            master.getString("sha256")
        )
        assertFalse(master.getBoolean("used_as_production_texture"))
        assertEquals(
            "6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095",
            frozen.getJSONObject("v2_assets_zip").getString("sha256")
        )
        assertEquals(
            "28f8918c39f6e875602d3a7e90775e9d455aa6c4e42783247d1104c3b4509679",
            frozen.getJSONObject("v2_1_assets_zip").getString("sha256")
        )
        assertFalse(lock.getJSONObject("environment_truth").getBoolean("hardware_gpu_verified"))
    }

    @Test
    fun `02_work2_scene_bindings_four_fish_five_split_residents_and_gt_separation`() {
        val loaded = WaterWorldSceneBindings.loadScene(scenePackageRoot())
        assertEquals(1254, loaded.canvasSize)

        val fish = loaded.bindings.filter { it.passId == WaterWorldRenderGraph.PassId.FISH }
        assertEquals(4, fish.size)
        assertEquals(listOf("WW_FISH_01", "WW_FISH_02", "WW_FISH_03", "WW_FISH_04"), fish.map { it.objectId })
        // WW_FISH_03 and WW_FISH_04 must retain UNKNOWN ground truth designation in their hypothesis status
        assertTrue(fish[2].hypothesisStatus.contains("UNKNOWN"))
        assertTrue(fish[3].hypothesisStatus.contains("UNKNOWN"))

        val backRes = loaded.bindings.filter { it.passId == WaterWorldRenderGraph.PassId.RESIDENT_BACK }
        val frontRes = loaded.bindings.filter { it.passId == WaterWorldRenderGraph.PassId.RESIDENT_FRONT }
        assertTrue(backRes.isNotEmpty())
        assertTrue(frontRes.isNotEmpty())
        for (residentId in listOf("G01", "G02", "A01", "A02", "C01")) {
            assertTrue("Missing back segment for $residentId", backRes.any { it.objectId.startsWith("${residentId}_") })
            assertTrue("Missing front segment for $residentId", frontRes.any { it.objectId.startsWith("${residentId}_") })
        }
        // Master PNG must never appear as any texture path
        for (b in loaded.bindings) {
            b.texturePath?.let { path ->
                assertFalse(path.contains("奇幻"))
                assertFalse(path.lowercase().contains("master"))
            }
        }
    }

    @Test
    fun `03_fbo_pool_gpu_memory_accounting_is_accurate`() {
        val bytes = WaterWorldRenderTargetPool.plannedFboMemoryBytes(1254)
        // 4 full RGBA8 (1254*1254*4*4) + 1 32-bit aligned Depth24 (1254*1254*4) + 2 half (627*627*4*2) + 2 quarter (313*313*4*2)
        val expected = (1254L * 1254L * 4L * 4L) +
            (1254L * 1254L * 4L) +
            (627L * 627L * 4L * 2L) +
            (313L * 313L * 4L * 2L)
        assertEquals(expected, bytes)
        assertTrue("Expected ~35MB FBO footprint", bytes in 30_000_000L..40_000_000L)
    }

    @Test
    fun `04_viewport_crop_rect_converts_gl_bottom_left_to_android_top_left`() {
        // Portrait 1080x2400 -> centered 1080x1080 at left=0, bottom=660 -> Android top = 2400 - 660 - 1080 = 660
        val portraitVp = WaterWorldStaticRenderer.PresentViewport(0, 660, 1080, 1080, 2400)
        assertArrayEquals(
            intArrayOf(0, 660, 1080, 1080),
            WaterWorldPreviewActivity.computeAndroidCropRect(portraitVp, 1080, 2400)
        )

        // Asymmetric non-centered GL viewport: bottom=200 on height=1000 with side=600 -> Android top = 1000 - 200 - 600 = 200
        val offsetVp = WaterWorldStaticRenderer.PresentViewport(120, 150, 600, 1000, 1000)
        assertArrayEquals(
            intArrayOf(120, 250, 600, 600),
            WaterWorldPreviewActivity.computeAndroidCropRect(offsetVp, 1000, 1000)
        )

        // Scaled PixelCopy buffer (half resolution 500x500 for 1000x1000 surface)
        assertArrayEquals(
            intArrayOf(60, 125, 300, 300),
            WaterWorldPreviewActivity.computeAndroidCropRect(offsetVp, 500, 500)
        )

        // Null viewport fallback uses centered square
        assertArrayEquals(
            intArrayOf(460, 0, 1080, 1080),
            WaterWorldPreviewActivity.computeAndroidCropRect(null, 2000, 1080)
        )
    }

    @Test
    fun `05_saf_evidence_zip_intent_and_zero_storage_permissions`() {
        val intent = WaterWorldPreviewActivity.buildCreateEvidenceZipIntent("work2_evidence.zip")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("application/zip", intent.type)
        assertEquals("work2_evidence.zip", intent.getStringExtra(Intent.EXTRA_TITLE))

        // Verify Play Policy compliance: zero broad external storage permissions requested
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertFalse(perms.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertFalse(perms.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
        assertFalse(perms.contains("android.permission.READ_MEDIA_IMAGES"))
        assertFalse(perms.contains("android.permission.READ_MEDIA_VIDEO"))
    }

    @Test
    fun `06_evidence_zip_packaging_bundles_manifest_and_files`() {
        val controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java).create()
        val activity = controller.get()
        val tmpDir = File(activity.cacheDir, "work2_zip_test_${System.nanoTime()}").apply { mkdirs() }
        try {
            File(tmpDir, "final_fbo.png").writeBytes(byteArrayOf(1, 2, 3, 4))
            File(tmpDir, "ANDROID_GPU_TELEMETRY.json").writeText("{\"passCount\":11}")
            val count = activity.writeEvidenceSha256Manifest(tmpDir)
            assertEquals(2, count)
            val zip = activity.packageEvidenceZip(tmpDir)
            assertTrue(zip.isFile && zip.length() > 0)
            ZipFile(zip).use { zf ->
                val names = zf.entries().toList().map { it.name }.toSet()
                assertTrue(names.contains("final_fbo.png"))
                assertTrue(names.contains("ANDROID_GPU_TELEMETRY.json"))
                assertTrue(names.contains("EVIDENCE_SHA256_MANIFEST.json"))
            }
        } finally {
            tmpDir.deleteRecursively()
            controller.destroy()
        }
    }

    @Test
    fun `07_five_round_visual_convergence_progression_and_threshold`() {
        val prog = JSONObject(File(work2Root(), "08_VISUAL_ACCEPTANCE/WORK2_FIVE_ROUND_PROGRESSION.json").readText())
        assertFalse(prog.getBoolean("hardware_gpu_verified"))
        assertFalse(prog.getBoolean("master_used_as_texture"))
        assertEquals("UNMODIFIED_WORK16_SIX_AXIS_EQUAL_WEIGHT", prog.getString("scoring_algorithm"))
        val baseline = prog.getDouble("baseline_overall_score")
        val rounds = prog.getJSONArray("rounds")
        assertEquals(5, rounds.length())
        var finalScore = 0.0
        for (i in 0 until rounds.length()) {
            val r = rounds.getJSONObject(i)
            val score = r.getDouble("overall_score")
            assertTrue("Round ${i + 1} score must be >= 80.0, got $score", score >= 80.0)
            if (i == rounds.length() - 1) finalScore = score
        }
        assertTrue("Final Round 5 score ($finalScore) must improve over baseline ($baseline)", finalScore > baseline)
    }
}
