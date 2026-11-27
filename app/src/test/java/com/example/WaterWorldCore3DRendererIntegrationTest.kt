package com.example

import android.view.View
import android.widget.Button
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.scene.OrbFluidVolumeRenderer
import com.example.waterworld.WaterWorldDiagnosticsDrawer
import com.example.waterworld.WaterWorldPreviewActivity
import com.example.waterworld.WaterWorldRenderGraph
import com.example.waterworld.WaterWorldShaders
import com.example.waterworld.WaterWorldStaticRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldCore3DRendererIntegrationTest {

    @Test
    fun t01_coreFluidShadersDeclareProductionCoreVolumeOnlyWithNoDuplicateUniforms() {
        val vert = CoreFluidShaders.CORE_GLASS_VERT
        val frag = CoreFluidShaders.CORE_GLASS_FRAG

        assertTrue(vert.contains("uniform int uCoreRenderMode;"))
        assertTrue(vert.contains("if (uCoreRenderMode == 1)"))
        assertEquals(
            "CORE_GLASS_VERT must declare uCoreRenderMode exactly once",
            1,
            Regex("uniform\\s+int\\s+uCoreRenderMode;").findAll(vert).count(),
        )

        assertTrue(frag.contains("uniform sampler3D uFluidVolume;"))
        assertTrue(frag.contains("uniform mat3 uCoreRotMat;"))
        assertTrue(frag.contains("transpose(uCoreRotMat)"))
        assertTrue(frag.contains("computeVolumeCurl"))
        assertTrue(frag.contains("uniform int uCoreRenderMode;"))
        assertTrue(frag.contains("uniform int uCoreDiagMode;"))
        assertTrue(frag.contains("uniform float uCoreColorShift;"))
        assertTrue(frag.contains("if (uCoreRenderMode == 1)"))

        assertEquals(
            "CORE_GLASS_FRAG must declare uCoreRenderMode exactly once",
            1,
            Regex("uniform\\s+int\\s+uCoreRenderMode;").findAll(frag).count(),
        )
        assertEquals(
            "CORE_GLASS_FRAG must declare uCoreDiagMode exactly once",
            1,
            Regex("uniform\\s+int\\s+uCoreDiagMode;").findAll(frag).count(),
        )
        assertEquals(
            "CORE_GLASS_FRAG must declare uCoreColorShift exactly once",
            1,
            Regex("uniform\\s+float\\s+uCoreColorShift;").findAll(frag).count(),
        )
    }

    @Test
    fun t02_waterFragmentRemoved2DFakeCoreSwirlAnd11Pass5OpticsUnchanged() {
        val waterFrag = WaterWorldShaders.WATER_FRAGMENT
        assertFalse("WATER_FRAGMENT must not contain fake 2D uCoreFluidParams", waterFrag.contains("uCoreFluidParams"))
        assertFalse("WATER_FRAGMENT must not contain fake 2D core swirl", waterFrag.contains("float swirl = sin("))
        assertFalse("WATER_FRAGMENT must not contain fake 2D core filament", waterFrag.contains("float filament = exp("))

        val graph = WaterWorldRenderGraph()
        graph.validateGraph()
        assertEquals(11, graph.stages.size)
        assertEquals(5, WaterWorldRenderGraph.OpticalNodeId.entries.size)

        // All 6 Core 3D GPU isolation modes map cleanly
        assertEquals(
            OrbFluidVolumeRenderer.CoreDiagMode.CORE_VOLUME_ONLY_ISOLATED,
            WaterWorldStaticRenderer.coreDiagModeFor(WaterWorldRenderGraph.DiagnosticMode.CORE_VOLUME_ONLY),
        )
        assertEquals(
            OrbFluidVolumeRenderer.CoreDiagMode.CORE_DENSITY,
            WaterWorldStaticRenderer.coreDiagModeFor(WaterWorldRenderGraph.DiagnosticMode.CORE_DENSITY),
        )
        assertEquals(
            OrbFluidVolumeRenderer.CoreDiagMode.CORE_FLOW,
            WaterWorldStaticRenderer.coreDiagModeFor(WaterWorldRenderGraph.DiagnosticMode.CORE_FLOW),
        )
        assertEquals(
            OrbFluidVolumeRenderer.CoreDiagMode.CORE_CAUSTICS,
            WaterWorldStaticRenderer.coreDiagModeFor(WaterWorldRenderGraph.DiagnosticMode.CORE_CAUSTICS),
        )
        assertEquals(
            OrbFluidVolumeRenderer.CoreDiagMode.CORE_OPTICAL_DEPTH,
            WaterWorldStaticRenderer.coreDiagModeFor(WaterWorldRenderGraph.DiagnosticMode.CORE_OPTICAL_DEPTH),
        )
        assertEquals(
            OrbFluidVolumeRenderer.CoreDiagMode.CORE_ROTATION,
            WaterWorldStaticRenderer.coreDiagModeFor(WaterWorldRenderGraph.DiagnosticMode.CORE_ROTATION),
        )
    }

    @Test
    fun t03_tab3DrawerExposesAll6Core3DIsolationModesCollapsedByDefaultWith48dpTargets() {
        val controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java).setup()
        val activity = controller.get()
        val decor = activity.window.decorView

        // Drawer MUST remain collapsed by default
        assertFalse(activity.isDiagnosticsExpanded)
        val panel = decor.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_DIAGNOSTICS_PANEL)
        assertEquals(View.GONE, panel.visibility)

        // Expand drawer -> verify Section E & all 6 Core 3D GPU isolation buttons
        activity.setDiagnosticsExpanded(true)
        assertEquals(View.VISIBLE, panel.visibility)
        assertNotNull(decor.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_SECTION_CORE_3D))
        assertNotNull(decor.findViewWithTag<View>(WaterWorldDiagnosticsDrawer.TAG_CORE_STATUS))

        val expectedButtons = listOf(
            WaterWorldDiagnosticsDrawer.TAG_CORE_VOLUME_ONLY to WaterWorldRenderGraph.DiagnosticMode.CORE_VOLUME_ONLY,
            WaterWorldDiagnosticsDrawer.TAG_CORE_DENSITY to WaterWorldRenderGraph.DiagnosticMode.CORE_DENSITY,
            WaterWorldDiagnosticsDrawer.TAG_CORE_FLOW to WaterWorldRenderGraph.DiagnosticMode.CORE_FLOW,
            WaterWorldDiagnosticsDrawer.TAG_CORE_CAUSTICS to WaterWorldRenderGraph.DiagnosticMode.CORE_CAUSTICS,
            WaterWorldDiagnosticsDrawer.TAG_CORE_OPTICAL_DEPTH to WaterWorldRenderGraph.DiagnosticMode.CORE_OPTICAL_DEPTH,
            WaterWorldDiagnosticsDrawer.TAG_CORE_ROTATION to WaterWorldRenderGraph.DiagnosticMode.CORE_ROTATION,
        )
        val density = activity.resources.displayMetrics.density
        val minPx = (48f * density).toInt()
        for ((tag, mode) in expectedButtons) {
            val btn = decor.findViewWithTag<Button>(tag)
            assertNotNull("Missing Core 3D diagnostic button: $tag", btn)
            assertTrue("Button $tag must meet >= 48dp touch height", btn.minimumHeight >= minPx)
            btn.performClick()
            assertEquals(mode, activity.activeDiagnosticMode)
        }

        // Restore FINAL_SCENE cleanly
        val restoreBtn = decor.findViewWithTag<Button>(WaterWorldDiagnosticsDrawer.TAG_RESTORE_FINAL_SCENE)
        restoreBtn.performClick()
        assertEquals(WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE, activity.activeDiagnosticMode)
        controller.pause().stop().destroy()
    }

    @Test
    fun t04_coreVolumeErrorSurfacesExplicitResourceFailedNeverReady() {
        val controller = Robolectric.buildActivity(WaterWorldPreviewActivity::class.java).setup()
        val activity = controller.get()
        activity.reportPageState(
            WaterWorldPreviewActivity.PageState.RESOURCE_FAILED,
            "CORE_VOLUME_ERROR: missing orb_v3/volume/fluid_slice_00.webp",
        )
        assertEquals(WaterWorldPreviewActivity.PageState.RESOURCE_FAILED, activity.pageState)
        assertTrue(activity.errorDetail.orEmpty().contains("CORE_VOLUME_ERROR"))
        val errPanel = activity.window.decorView.findViewWithTag<View>(WaterWorldPreviewActivity.TAG_ERROR_PANEL)
        assertEquals(View.VISIBLE, errPanel.visibility)
        controller.pause().stop().destroy()
    }
}
