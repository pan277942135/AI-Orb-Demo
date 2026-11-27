package com.example

import com.example.waterworld.WaterWorldDynamicFrameSnapshot
import com.example.waterworld.WaterWorldMesh
import com.example.waterworld.WaterWorldMotionEvaluator
import com.example.waterworld.WaterWorldMotionProfile
import com.example.waterworld.WaterWorldShaders
import com.example.waterworld.WaterWorldStaticRenderer
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldDynamicRendererTest {

    @Test
    fun t01_subdividedPlantQuadPreservesExactCornersAndProduces6x12Grid() {
        val rawVerts = JSONArray().apply {
            put(JSONArray(listOf(100.0, 200.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)))
            put(JSONArray(listOf(300.0, 200.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0)))
            put(JSONArray(listOf(300.0, 650.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
            put(JSONArray(listOf(100.0, 650.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0)))
        }
        val built = WaterWorldMesh.buildSubdividedQuadGrid(rawVerts, cols = 6, rows = 12)

        assertEquals(7 * 13, built.vertexCount)
        assertEquals(6 * 12 * 6, built.indexCount)
        assertEquals(200f, built.centerX, 1e-4f)
        assertEquals(425f, built.centerY, 1e-4f)
        assertEquals(200f, built.widthPx, 1e-4f)
        assertEquals(450f, built.heightPx, 1e-4f)

        // Top-left corner (r=0,c=0) -> (100, 200), UV=(0,0)
        assertEquals(100f, built.vertexFloats[0], 1e-4f)
        assertEquals(200f, built.vertexFloats[1], 1e-4f)
        assertEquals(0f, built.vertexFloats[6], 1e-4f)
        assertEquals(0f, built.vertexFloats[7], 1e-4f)

        // Bottom-right corner (last vertex) -> (300, 650), UV=(1,1)
        val lastBase = (built.vertexCount - 1) * 8
        assertEquals(300f, built.vertexFloats[lastBase], 1e-4f)
        assertEquals(650f, built.vertexFloats[lastBase + 1], 1e-4f)
        assertEquals(1f, built.vertexFloats[lastBase + 6], 1e-4f)
        assertEquals(1f, built.vertexFloats[lastBase + 7], 1e-4f)
        assertTrue("All indices must be in bounds", built.indices.all { it in 0 until built.vertexCount })
    }

    @Test
    fun t02_fishAndBubbleModelMatricesAreExactIdentityAtT0AndSmoothAtTGreaterThan0() {
        val profile = WaterWorldMotionProfile.defaultProfile()
        val t0 = WaterWorldDynamicFrameSnapshot.staticZero(profile)
        val t4 = WaterWorldMotionEvaluator.evaluate(profile, 4.0)
        val baseModel = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, -0.35f, 1f,
        )
        val out = FloatArray(16)

        // Fish at T=0 -> exact identity with preserved Z
        WaterWorldStaticRenderer.writeFishModelMatrix(baseModel, t0.fishStates.getValue("WW_FISH_01"), out)
        assertEquals(1f, out[0], 1e-5f)
        assertEquals(0f, out[1], 1e-5f)
        assertEquals(0f, out[4], 1e-5f)
        assertEquals(1f, out[5], 1e-5f)
        assertEquals(0f, out[12], 1e-4f)
        assertEquals(0f, out[13], 1e-4f)
        assertEquals(-0.35f, out[14], 1e-5f)

        // Fish at T=4 -> non-zero translation/scale, finite
        WaterWorldStaticRenderer.writeFishModelMatrix(baseModel, t4.fishStates.getValue("WW_FISH_01"), out)
        assertTrue(out.all { it.isFinite() })
        assertTrue("Fish matrix at T=4s must move from origin", out[12] != 0f || out[13] != 0f)

        // Bubble at T=0 -> exact identity
        WaterWorldStaticRenderer.writeBubbleModelMatrix(baseModel, 520f, 640f, t0.outerBubbleStates.getValue("B01"), out)
        assertEquals(1f, out[0], 1e-5f)
        assertEquals(1f, out[5], 1e-5f)
        assertEquals(0f, out[12], 1e-4f)
        assertEquals(0f, out[13], 1e-4f)

        // Bubble at T=4 -> rises upward (negative Y translation)
        WaterWorldStaticRenderer.writeBubbleModelMatrix(baseModel, 520f, 640f, t4.outerBubbleStates.getValue("B01"), out)
        assertTrue(out.all { it.isFinite() })
        assertNotEquals(0f, out[13], 1e-3f)
    }

    @Test
    fun t03_residentPlantRockDrawIdResolutionCoversAllSceneBindings() {
        val profile = WaterWorldMotionProfile.defaultProfile()
        for (rp in profile.residents) {
            for (drawId in rp.drawIds) {
                assertEquals(rp.groupKey, WaterWorldStaticRenderer.resolveResidentGroupKey(drawId))
            }
        }
        assertEquals("WW_PLANT_LEFT", WaterWorldStaticRenderer.resolvePlantElementId("plant_left", true))
        assertEquals("WW_PLANT_LEFT", WaterWorldStaticRenderer.resolvePlantElementId("far_plant_left_bg", true))
        assertEquals("WW_PLANT_RIGHT", WaterWorldStaticRenderer.resolvePlantElementId("plant_right", true))
        assertEquals("WW_PLANT_RIGHT", WaterWorldStaticRenderer.resolvePlantElementId("plant_mid_sprout", true))
        assertEquals("WW_ROCK_01", WaterWorldStaticRenderer.resolveRockElementId("rock_01", true))
        assertEquals("WW_ROCK_02", WaterWorldStaticRenderer.resolveRockElementId("rock_02", true))
        assertEquals("WW_ROCK_03", WaterWorldStaticRenderer.resolveRockElementId("rock_03", true))
    }

    @Test
    fun t04_shadersDeclareAllWork3DynamicUniformsAndKeepT0CompatibleMath() {
        assertTrue(WaterWorldShaders.MESH_VERTEX.contains("uDeformMode"))
        assertTrue(WaterWorldShaders.MESH_VERTEX.contains("uDeformParams"))
        assertTrue(WaterWorldShaders.RESIDENT_RIBBON_FRAGMENT.contains("uFlowOffsetU"))
        assertTrue(WaterWorldShaders.RESIDENT_RIBBON_FRAGMENT.contains("uBrightnessScale"))
        assertTrue(WaterWorldShaders.TRANSPARENT_FRAGMENT.contains("uWaterNormalOffset"))
        assertTrue(WaterWorldShaders.TRANSPARENT_FRAGMENT.contains("uCausticsPhase"))
        assertTrue(WaterWorldShaders.TRANSPARENT_FRAGMENT.contains("uCausticsGain"))
        assertTrue(WaterWorldShaders.WATER_FRAGMENT.contains("uWaterNormalOffset"))
        assertTrue(WaterWorldShaders.WATER_FRAGMENT.contains("uMeniscusPhases"))
        assertTrue(WaterWorldShaders.WATER_FRAGMENT.contains("uCoreFluidParams"))
        assertTrue(WaterWorldShaders.GLASS_FRAGMENT.contains("uGlassRefractionScale"))
        assertTrue(WaterWorldShaders.GLASS_FRAGMENT.contains("uGlassFresnelScale"))
        assertTrue(WaterWorldShaders.GLASS_FRAGMENT.contains("uGlassOpticalPhase"))
        assertTrue(WaterWorldShaders.CAUSTICS_FRAGMENT.contains("uCausticsPhase"))
        assertTrue(WaterWorldShaders.CAUSTICS_FRAGMENT.contains("uLightingShimmer"))
        assertTrue(WaterWorldShaders.PARTICLE_BOKEH_FRAGMENT.contains("uAlphaScale"))
        assertTrue(WaterWorldShaders.BOKEH_ORB_FRAGMENT.contains("uCenter"))
    }
}
