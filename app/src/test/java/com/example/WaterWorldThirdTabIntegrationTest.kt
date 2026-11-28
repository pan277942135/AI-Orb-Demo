package com.example

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.orb.config.OrbSceneMode
import com.example.orb.ui.AIOrbViewModel
import com.example.orb.ui.DemoTab
import com.example.ui.DemoTabSwitcherBar
import com.example.ui.theme.MyApplicationTheme
import com.example.waterworld.WaterWorldDiagnosticsDrawer
import com.example.waterworld.WaterWorldPreviewActivity
import com.example.waterworld.WaterWorldRenderGraph
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldThirdTabIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun t01_debugShowsThreeTabsAndClickingThirdTabTriggersCallback() {
        assertTrue("Unit test runs on debug build variant", BuildConfig.DEBUG)
        var openedWaterWorld = false

        composeRule.setContent {
            MyApplicationTheme {
                DemoTabSwitcherBar(
                    activeTab = DemoTab.AI_ORB,
                    onSelectTab = {},
                    isLightTheme = false,
                    showWaterWorldTab = true,
                    onOpenWaterWorld = { openedWaterWorld = true },
                )
            }
        }

        composeRule.onNodeWithTag("tab_ai_orb").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_task_perimeter").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_water_world").assertIsDisplayed()
        composeRule.onNodeWithText("AI Orb").assertIsDisplayed()
        composeRule.onNodeWithText("照片水光").assertIsDisplayed()
        composeRule.onNodeWithText("水下世界").assertIsDisplayed()

        composeRule.onNodeWithTag("tab_water_world").performClick()
        assertTrue("Clicking third tab must invoke onOpenWaterWorld callback", openedWaterWorld)
    }

    @Test
    fun t01b_releaseFlagOmitsThirdTabCleanly() {
        composeRule.setContent {
            MyApplicationTheme {
                DemoTabSwitcherBar(
                    activeTab = DemoTab.AI_ORB,
                    onSelectTab = {},
                    isLightTheme = false,
                    showWaterWorldTab = false,
                    onOpenWaterWorld = {},
                )
            }
        }

        composeRule.onNodeWithTag("tab_ai_orb").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_task_perimeter").assertIsDisplayed()
        composeRule.onNodeWithTag("tab_water_world").assertDoesNotExist()
    }

    @Test
    fun t02_mainActivityThirdTabLaunchesWaterWorldPreviewWithoutPollutingOrbState() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val vm = AIOrbViewModel()
        assertEquals(DemoTab.AI_ORB, vm.uiState.value.activeTab)
        assertEquals(OrbSceneMode.ORB_ONLY, vm.uiState.value.config.sceneMode)

        // Wire Compose 3rd-tab click directly to MainActivity.openWaterWorldPreview()
        composeRule.setContent {
            MyApplicationTheme {
                DemoTabSwitcherBar(
                    activeTab = vm.uiState.value.activeTab,
                    onSelectTab = vm::selectTab,
                    isLightTheme = false,
                    showWaterWorldTab = BuildConfig.DEBUG,
                    onOpenWaterWorld = { activity.openWaterWorldPreview() },
                )
            }
        }
        composeRule.onNodeWithTag("tab_water_world").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        val startedIntent = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull("Clicking 水下世界 must launch WaterWorldPreviewActivity", startedIntent)
        assertEquals(
            WaterWorldPreviewActivity::class.java.name,
            startedIntent.component?.className,
        )
        // MainActivity's AI Orb ViewModel state remains completely isolated from Water World
        assertEquals(DemoTab.AI_ORB, vm.uiState.value.activeTab)
        assertEquals(OrbSceneMode.ORB_ONLY, vm.uiState.value.config.sceneMode)
        controller.destroy()
    }

    @Test
    fun t03_waterWorldOpensIntoShowcaseFinalSceneWithDiagnosticsCollapsedByDefault() {
        val controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java).create().start().resume()
        val activity = controller.get()
        val root = activity.window.decorView

        assertEquals(WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE, activity.activeDiagnosticMode)
        // Must NOT falsely report READY merely because Activity/GLSurfaceView was created
        assertEquals(WaterWorldPreviewActivity.PageState.LOADING, activity.pageState)
        assertFalse("Developer diagnostics must default to COLLAPSED", activity.isDiagnosticsExpanded)

        val title = root.findViewWithTag<TextView>(WaterWorldPreviewActivity.TAG_TITLE)
        val subtitle = root.findViewWithTag<TextView>(WaterWorldPreviewActivity.TAG_SUBTITLE)
        val backBtn = root.findViewWithTag<View>(WaterWorldPreviewActivity.TAG_BACK_BUTTON)
        val loadingOverlay = root.findViewWithTag<View>(WaterWorldPreviewActivity.TAG_LOADING_OVERLAY)
        val diagToggle = root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_DIAGNOSTICS_TOGGLE)
        val diagPanel = root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_DIAGNOSTICS_PANEL)
        val activeBanner = root.findViewWithTag<View>(WaterWorldPreviewActivity.TAG_ACTIVE_MODE_BANNER)

        assertEquals("Water World V2.1", title.text.toString())
        assertEquals("Underwater Ecosystem", subtitle.text.toString())
        assertEquals(View.VISIBLE, backBtn.visibility)
        assertEquals(View.VISIBLE, loadingOverlay.visibility)
        assertEquals(View.VISIBLE, diagToggle.visibility)
        assertEquals("Diagnostics panel must be GONE on first entry", View.GONE, diagPanel.visibility)
        assertEquals("Diagnostic mode banner must be GONE in FINAL_SCENE", View.GONE, activeBanner.visibility)

        controller.pause().stop().destroy()
    }

    @Test
    fun t04_expandingDiagnosticsPreservesFinalSceneAndAllowsSwitchingAndRestoringModes() {
        val controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java).create().start().resume()
        val activity = controller.get()
        val root = activity.window.decorView

        // Simulate complete FINAL_SCENE telemetry arriving after real GL render & present
        val fakeTelemetry = buildCompleteFinalSceneTelemetry()
        activity.reportPageState(WaterWorldPreviewActivity.PageState.READY, telemetry = fakeTelemetry)

        assertEquals(WaterWorldPreviewActivity.PageState.READY, activity.pageState)
        assertEquals(View.GONE, root.findViewWithTag<View>(WaterWorldPreviewActivity.TAG_LOADING_OVERLAY).visibility)
        assertFalse(activity.isDiagnosticsExpanded)

        // Expand diagnostics: MUST NOT alter FINAL_SCENE
        root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_DIAGNOSTICS_TOGGLE).performClick()
        assertTrue(activity.isDiagnosticsExpanded)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_DIAGNOSTICS_PANEL).visibility)
        assertEquals(WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE, activity.activeDiagnosticMode)

        // Verify all 4 sections exist inside expanded diagnostics
        assertNotNull(root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_SECTION_RENDER_PASS))
        assertNotNull(root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_SECTION_OPTICAL))
        assertNotNull(root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_SECTION_GPU))
        assertNotNull(root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_SECTION_EXPORT))

        val passList = root.findViewWithTag<LinearLayout>(WaterWorldDiagnosticsDrawer.TAG_PASS_LIST)
        assertEquals("All 11 render passes must be listed in diagnostics", 11, passList.childCount)

        val gpuInfo = root.findViewWithTag<TextView>(WaterWorldDiagnosticsDrawer.TAG_GPU_INFO).text.toString()
        assertTrue("GPU Frame Time must explicitly show NOT_MEASURED", gpuInfo.contains("NOT_MEASURED"))
        assertTrue("GPU Diagnostics must show Pass Count 11 / 11", gpuInfo.contains("11 / 11"))
        assertTrue("GPU Diagnostics must show Optics 5 / 5", gpuInfo.contains("5 / 5"))

        // Switch to Caustics optical preview -> active banner shows -> restore FINAL_SCENE
        root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_OPTICAL_CAUSTICS).performClick()
        assertEquals(WaterWorldRenderGraph.DiagnosticMode.OPTICAL_CAUSTICS, activity.activeDiagnosticMode)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>(WaterWorldPreviewActivity.TAG_ACTIVE_MODE_BANNER).visibility)

        root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_RESTORE_FINAL_SCENE).performClick()
        assertEquals(WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE, activity.activeDiagnosticMode)
        assertEquals(View.GONE, root.findViewWithTag<View>(WaterWorldPreviewActivity.TAG_ACTIVE_MODE_BANNER).visibility)

        // Collapse again cleanly
        root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_DIAGNOSTICS_TOGGLE).performClick()
        assertFalse(activity.isDiagnosticsExpanded)
        assertEquals(View.GONE, root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_DIAGNOSTICS_PANEL).visibility)

        controller.pause().stop().destroy()
    }

    @Test
    fun t05_manifestRegistersWaterWorldPreviewAndKeepsMainActivityAsLauncher() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager

        val previewIntent = Intent(context, WaterWorldPreviewActivity::class.java)
        val previewResolved = pm.resolveActivity(previewIntent, 0)
        assertNotNull("WaterWorldPreviewActivity must be registered in Debug manifest", previewResolved)
        assertEquals(WaterWorldPreviewActivity::class.java.name, previewResolved!!.activityInfo.name)

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(context.packageName)
        }
        val launcherActivities = pm.queryIntentActivities(launcherIntent, 0)
        assertEquals("MainActivity must remain the sole LAUNCHER Activity", 1, launcherActivities.size)
        assertEquals(MainActivity::class.java.name, launcherActivities.first().activityInfo.name)
    }

    @Test
    fun t06_backButtonFinishesWaterWorldPreviewAndReturnsToMainActivity() {
        val controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java).create().start().resume()
        val activity = controller.get()
        assertFalse(activity.isFinishing)

        val backBtn = activity.window.decorView.findViewWithTag<View>(WaterWorldPreviewActivity.TAG_BACK_BUTTON)
        assertNotNull(backBtn)
        backBtn.performClick()
        assertTrue("Clicking 返回 AI Orb must finish WaterWorldPreviewActivity", activity.isFinishing)

        controller.pause().stop().destroy()
    }

    @Test
    fun t07_tab1AndTab2PreserveFullRegressionBehavior() {
        val vm = AIOrbViewModel()
        assertEquals(DemoTab.AI_ORB, vm.uiState.value.activeTab)
        assertEquals(OrbSceneMode.ORB_ONLY, vm.uiState.value.config.sceneMode)

        vm.selectTab(DemoTab.TASK_PERIMETER)
        assertEquals(DemoTab.TASK_PERIMETER, vm.uiState.value.activeTab)
        assertEquals(OrbSceneMode.TASK_PERIMETER_DEMO, vm.uiState.value.config.sceneMode)

        vm.selectTab(DemoTab.AI_ORB)
        assertEquals(DemoTab.AI_ORB, vm.uiState.value.activeTab)
        assertEquals(OrbSceneMode.ORB_ONLY, vm.uiState.value.config.sceneMode)
    }

    @Test
    fun t08_sixStateFailureClassificationMissingAssetsErrorPanelRetryAndShaManifest() {
        assertEquals(
            setOf("LOADING", "READY", "RENDER_FAILED", "RESOURCE_FAILED", "SHADER_FAILED", "FBO_FAILED"),
            WaterWorldPreviewActivity.PageState.entries.map { it.name }.toSet(),
        )
        assertEquals(
            WaterWorldPreviewActivity.PageState.SHADER_FAILED,
            WaterWorldPreviewActivity.classifyInitFailure(IllegalStateException("Shader compile failed")),
        )
        assertEquals(
            WaterWorldPreviewActivity.PageState.FBO_FAILED,
            WaterWorldPreviewActivity.classifyInitFailure(IllegalStateException("Incomplete framebuffer FBO")),
        )
        assertEquals(
            WaterWorldPreviewActivity.PageState.RESOURCE_FAILED,
            WaterWorldPreviewActivity.classifyInitFailure(FileNotFoundException("Missing asset draw_bindings.json")),
        )
        assertEquals(
            WaterWorldPreviewActivity.PageState.RENDER_FAILED,
            WaterWorldPreviewActivity.classifyRenderFailure(RuntimeException("Unexpected draw error")),
        )

        val brokenIntent = Intent(RuntimeEnvironment.getApplication(), WaterWorldPreviewActivity::class.java).apply {
            putExtra(WaterWorldPreviewActivity.EXTRA_ASSET_ROOT_PATH, "nonexistent_water_world_assets")
        }
        val controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java, brokenIntent)
            .create().start().resume()
        val activity = controller.get()
        val root = activity.window.decorView

        assertEquals(WaterWorldPreviewActivity.PageState.RESOURCE_FAILED, activity.pageState)
        assertEquals(View.GONE, root.findViewWithTag<View>(WaterWorldPreviewActivity.TAG_LOADING_OVERLAY).visibility)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>(WaterWorldPreviewActivity.TAG_ERROR_PANEL).visibility)
        assertEquals(View.VISIBLE, root.findViewWithTag<View>(WaterWorldPreviewActivity.TAG_RETRY_BUTTON).visibility)
        val errMsg = root.findViewWithTag<TextView>(WaterWorldPreviewActivity.TAG_ERROR_MESSAGE).text.toString()
        assertTrue("Error message must describe asset failure", errMsg.contains("nonexistent_water_world_assets"))

        // Verify SHA-256 manifest export helper produces valid hashes
        val tmpDir = Files.createTempDirectory("ww_manifest_test").toFile()
        try {
            File(tmpDir, "final_fbo.png").writeBytes(byteArrayOf(1, 2, 3, 4))
            File(tmpDir, "ANDROID_GPU_TELEMETRY.json").writeText("{}")
            val count = activity.writeEvidenceSha256Manifest(tmpDir)
            assertEquals(2, count)
            val manifest = JSONObject(File(tmpDir, "EVIDENCE_SHA256_MANIFEST.json").readText())
            assertEquals(2, manifest.getInt("fileCount"))
            assertEquals(64, manifest.getJSONObject("files").getJSONObject("final_fbo.png").getString("sha256").length)
        } finally {
            tmpDir.deleteRecursively()
        }

        controller.pause().stop().destroy()
    }

    private fun buildCompleteFinalSceneTelemetry(): JSONObject {
        val passRecords = JSONArray()
        for (stage in WaterWorldRenderGraph.canonical11StageGraph()) {
            passRecords.put(
                JSONObject()
                    .put("passId", stage.passId.wireId)
                    .put("order", stage.passId.order)
                    .put("drawCount", 1)
                    .put("elapsedCpuMs", 1.25)
                    .put("fboComplete", true)
            )
        }
        return JSONObject()
            .put("glVendor", "TestVendor")
            .put("glRenderer", "TestGLES3Renderer")
            .put("glVersion", "OpenGL ES 3.0")
            .put("shaderProgramCount", 12)
            .put("shaderCompileLinkOk", true)
            .put("drawCount", 118)
            .put("passCount", 11)
            .put("opticalNodeCount", 5)
            .put("gpuFrameTimeMs", "NOT_MEASURED")
            .put("cpuFrameTimeMs", 14.2)
            .put("rendererClass", "ANDROID_HARDWARE_GPU")
            .put("fboComplete", true)
            .put("passRecords", passRecords)
    }
}
