package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.orb.scene.AIOrbScene
import com.example.orb.config.RenderDebugMode
import com.example.orb.gl.CoreFluidShaders
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OuterOpticalFieldContractTest {
  @Test
  fun packagedRuntimeFieldMatchesTheFrozenRefBExtraction() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bytes = context.assets.open("outer_optical_field/OUTER_OPTICAL_FIELD_RUNTIME_V1.json").use { it.readBytes() }
    assertEquals("f6cec2833160e9343d4c60e0eb10d677b9f8f67c458dceccbe5ed51d8a22b45d", sha256(bytes))
    val root = JSONObject(String(bytes, Charsets.UTF_8))
    assertEquals("AI_ORB_TAB3_OUTER_OPTICAL_RUNTIME_V1", root.getString("schema"))
    assertEquals("910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122", root.getJSONObject("source").getString("refBSha256"))
    assertEquals(7, root.getJSONArray("traces").length())
    assertEquals(12, root.getJSONArray("bokeh").length())
    assertEquals(51, root.getJSONArray("highlightNodes").length())
    assertEquals(5, root.getJSONArray("droplets").length())
    assertEquals(10, root.getJSONArray("particles").length())
    for (i in 0 until root.getJSONArray("traces").length()) {
      val trace = root.getJSONArray("traces").getJSONObject(i)
      assertTrue(trace.getJSONArray("pointsPx").length() >= 8)
      assertTrue(trace.getDouble("signalWidthPx") > 0.0)
      assertTrue(trace.getDouble("signalWidthPx") < trace.getDouble("annotationWidthPx"))
      assertTrue(trace.getString("confidence").contains("PIXEL_TRACE_MEASURED"))
    }
    assertTrue(root.getJSONObject("runtimeParameters").getString("note").contains("hypotheses"))
  }

  @Test
  fun tabThreeUsesItsOwnOpticalPassesWhileTabOneManifestIsUnchanged() {
    val tab1 = AIOrbScene.activeRenderPassesV3(RenderDebugMode.FULL_RENDER)
    val tab3 = AIOrbScene.activeRenderPassesYuJianHybrid(RenderDebugMode.FULL_RENDER)
    assertTrue(tab1.contains("PASS_3_BACK_RIBBONS_RESIDENT_AND_SECONDARY"))
    assertTrue(tab1.contains("PASS_6_V3_WEBP_3D_VOLUME_RAYMARCH_AND_CRYSTAL_GLASS_SHELL"))
    assertTrue(tab3.indexOf("PASS_2_REF_B_BACK_OPTICAL_SURFACES_AND_FILAMENTS") < tab3.indexOf("PASS_5_YUJIAN_CORE_SOURCE_SPHERE_FS"))
    assertTrue(tab3.indexOf("PASS_5_YUJIAN_CORE_SOURCE_SPHERE_FS") < tab3.indexOf("PASS_7_REF_B_FRONT_OPTICAL_SURFACES_AND_FILAMENTS"))
    assertTrue(tab3.last().contains("PASS_9_HDR_BLOOM"))
    assertTrue(CoreFluidShaders.BACKGROUND_FRAG.contains("uniform int uUseReferenceBokeh"))
    assertTrue(CoreFluidShaders.BACKGROUND_FRAG.contains("else {\n        for (int i = 0; i < 12; i++)"))
  }

  private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
