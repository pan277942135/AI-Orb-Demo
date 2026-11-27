package com.example

import android.opengl.GLSurfaceView
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.example.waterworld.WaterWorldDiagnosticsDrawer
import com.example.waterworld.WaterWorldDynamicEvidenceRecorder
import com.example.waterworld.WaterWorldMotionTimeline
import com.example.waterworld.WaterWorldPreviewActivity
import com.example.waterworld.WaterWorldRenderGraph
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldDynamicPlaybackLifecycleTest {

    @Test
    fun t01_playbackControlsRenderModeSwitchingAndDiagnosticsSectionM() {
        val controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java)
            .create().start().resume()
        val activity = controller.get()
        val root = activity.window.decorView

        // Before READY, surface stays WHEN_DIRTY and diagnostics remain strictly collapsed
        assertEquals(WaterWorldPreviewActivity.PageState.LOADING, activity.pageState)
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, activity.currentRenderMode)
        assertFalse(activity.isDiagnosticsExpanded)

        // Transition to READY and start dynamic playback -> RENDERMODE_CONTINUOUSLY
        activity.reportPageState(WaterWorldPreviewActivity.PageState.READY, telemetry = buildReadyTelemetry())
        activity.playDynamic()
        assertTrue(activity.motionRuntime.isPlaying)
        assertEquals(GLSurfaceView.RENDERMODE_CONTINUOUSLY, activity.currentRenderMode)

        // Expand diagnostics drawer and verify Section M controls exist with >= 48dp touch targets
        root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_DIAGNOSTICS_TOGGLE).performClick()
        assertTrue(activity.isDiagnosticsExpanded)
        assertNotNull(root.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_SECTION_MOTION))
        assertNotNull(root.findViewWithTag<TextView>(WaterWorldDiagnosticsDrawer.TAG_MOTION_STATUS))

        val playPauseBtn = root.findViewWithTag<Button>(WaterWorldDiagnosticsDrawer.TAG_PLAY_PAUSE_BUTTON)
        val resetBtn = root.findViewWithTag<Button>(WaterWorldDiagnosticsDrawer.TAG_RESET_BUTTON)
        val staticRefBtn = root.findViewWithTag<Button>(WaterWorldDiagnosticsDrawer.TAG_STATIC_REF_BUTTON)
        val speedBtn = root.findViewWithTag<Button>(WaterWorldDiagnosticsDrawer.TAG_SPEED_TOGGLE_BUTTON)
        val exportDynBtn = root.findViewWithTag<Button>(WaterWorldDiagnosticsDrawer.TAG_EXPORT_DYNAMIC_BUTTON)
        assertNotNull(playPauseBtn)
        assertNotNull(resetBtn)
        assertNotNull(staticRefBtn)
        assertNotNull(speedBtn)
        assertNotNull(exportDynBtn)

        val minTouchPx = (48 * activity.resources.displayMetrics.density).toInt()
        assertTrue(playPauseBtn.minimumHeight >= minTouchPx)
        assertTrue(resetBtn.minimumHeight >= minTouchPx)
        assertTrue(staticRefBtn.minimumHeight >= minTouchPx)
        assertTrue(speedBtn.minimumHeight >= minTouchPx)
        assertTrue(exportDynBtn.minimumHeight >= minTouchPx)

        // Pause -> RENDERMODE_WHEN_DIRTY
        playPauseBtn.performClick()
        assertFalse(activity.motionRuntime.isPlaying)
        assertEquals(WaterWorldMotionTimeline.PlaybackState.PAUSED, activity.motionRuntime.playbackState)
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, activity.currentRenderMode)

        // Resume -> RENDERMODE_CONTINUOUSLY
        playPauseBtn.performClick()
        assertTrue(activity.motionRuntime.isPlaying)
        assertEquals(GLSurfaceView.RENDERMODE_CONTINUOUSLY, activity.currentRenderMode)

        // Static T=0 reference mode -> RENDERMODE_WHEN_DIRTY
        staticRefBtn.performClick()
        assertTrue(activity.motionRuntime.staticReferenceMode)
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, activity.currentRenderMode)

        // Restore dynamic mode & cycle speed 1.0x -> 1.5x -> 0.5x -> 1.0x
        staticRefBtn.performClick()
        assertFalse(activity.motionRuntime.staticReferenceMode)
        assertEquals(1.0f, activity.motionRuntime.speedScale, 0f)
        speedBtn.performClick()
        assertEquals(1.5f, activity.motionRuntime.speedScale, 0f)
        speedBtn.performClick()
        assertEquals(0.5f, activity.motionRuntime.speedScale, 0f)
        speedBtn.performClick()
        assertEquals(1.0f, activity.motionRuntime.speedScale, 0f)

        // Reset button while playing restarts from T=0 continuously; autoPlay=false pauses at T=0
        activity.motionRuntime.stepByDeltaSeconds(0.05)
        assertTrue(activity.motionRuntime.currentTimeSeconds > 0.0)
        resetBtn.performClick()
        assertEquals(0.0, activity.motionRuntime.currentTimeSeconds, 0.0)
        assertEquals(GLSurfaceView.RENDERMODE_CONTINUOUSLY, activity.currentRenderMode)

        activity.resetDynamic(autoPlay = false)
        assertEquals(0.0, activity.motionRuntime.currentTimeSeconds, 0.0)
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, activity.currentRenderMode)

        controller.pause().stop().destroy()
    }

    @Test
    fun t02_activityPauseStopsContinuousRenderingAndResumeRestoresItWithZeroJump() {
        val controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java)
            .create().start().resume()
        val activity = controller.get()
        activity.reportPageState(WaterWorldPreviewActivity.PageState.READY, telemetry = buildReadyTelemetry())
        activity.playDynamic()
        activity.motionRuntime.stepByDeltaSeconds(0.05)
        val timeBeforePause = activity.motionRuntime.currentTimeSeconds
        assertEquals(GLSurfaceView.RENDERMODE_CONTINUOUSLY, activity.currentRenderMode)

        controller.pause()
        assertFalse("Activity onPause must pause timeline", activity.motionRuntime.isPlaying)
        assertEquals(GLSurfaceView.RENDERMODE_WHEN_DIRTY, activity.currentRenderMode)
        assertEquals(timeBeforePause, activity.motionRuntime.currentTimeSeconds, 1e-6)

        controller.resume()
        assertTrue("Activity onResume must resume timeline", activity.motionRuntime.isPlaying)
        assertEquals(timeBeforePause, activity.motionRuntime.currentTimeSeconds, 1e-6)
        assertEquals(GLSurfaceView.RENDERMODE_CONTINUOUSLY, activity.currentRenderMode)

        controller.pause().stop().destroy()
    }

    @Test
    fun t03_dynamicEvidenceRecorderExportsCompleteDeterministicMetadataAndManifest() {
        val controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java)
            .create().start().resume()
        val activity = controller.get()
        val recorder = WaterWorldDynamicEvidenceRecorder(activity.motionRuntime)
        val tmpDir = Files.createTempDirectory("ww_work3_dyn_meta").toFile()
        try {
            val manifestFile = recorder.exportDeterministicMetadataOnly(tmpDir)
            assertTrue(manifestFile.isFile)
            val timelineJson = JSONObject(File(tmpDir, "motion_timeline.json").readText())
            val elementsJson = JSONObject(File(tmpDir, "motion_element_states.json").readText())
            val depsJson = JSONObject(File(tmpDir, "motion_dependency_execution.json").readText())
            val manifestJson = JSONObject(manifestFile.readText())

            assertEquals(6, timelineJson.getJSONArray("frames").length())
            assertEquals(47, elementsJson.getInt("canonicalElementCount"))
            assertEquals(6, elementsJson.getJSONArray("timestamps").length())
            assertEquals(28, depsJson.getInt("canonicalDependencyCount"))
            assertEquals(6, depsJson.getJSONArray("timestamps").length())
            assertEquals(3, manifestJson.getInt("fileCount"))

            val zipOut = activity.packageEvidenceZip(tmpDir)
            try {
                assertTrue(zipOut.isFile && zipOut.length() > 100L)
            } finally {
                zipOut.delete()
            }
        } finally {
            tmpDir.deleteRecursively()
        }
        controller.pause().stop().destroy()
    }

    private fun buildReadyTelemetry(): JSONObject {
        val passRecords = JSONArray()
        for (stage in WaterWorldRenderGraph.canonical11StageGraph()) {
            passRecords.put(
                JSONObject()
                    .put("passId", stage.passId.wireId)
                    .put("order", stage.passId.order)
                    .put("drawCount", 1)
                    .put("elapsedCpuMs", 1.1)
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
            .put("cpuFrameTimeMs", 11.4)
            .put("rendererClass", "ANDROID_HARDWARE_GPU")
            .put("fboComplete", true)
            .put("passRecords", passRecords)
    }
}
