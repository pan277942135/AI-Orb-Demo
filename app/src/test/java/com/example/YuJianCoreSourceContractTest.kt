package com.example

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.orb.scene.AIOrbScene
import com.example.orb.yujian.core.YuJianCoreRenderer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YuJianCoreSourceContractTest {
  @Test
  fun pinnedWebpIsPackagedByteIdenticallyAndRetainsAlpha() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val bytes = context.assets.open("yujian_orb_core/processing_water_core_v1.webp").use { it.readBytes() }
    assertEquals(YuJianCoreRenderer.SOURCE_CORE_WEBP_SHA256, sha256(bytes))
    val bitmap = BitmapFactory.decodeByteArray(
      bytes,
      0,
      bytes.size,
      BitmapFactory.Options().apply {
        inScaled = false
        inPremultiplied = false
      },
    )
    assertTrue("Pinned YuJian core WebP must decode", bitmap != null)
    requireNotNull(bitmap)
    assertEquals(384, bitmap.width)
    assertEquals(384, bitmap.height)
    assertTrue("Source core must retain its alpha channel", bitmap.hasAlpha())
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val rgba = ByteArray(pixels.size * 4)
    pixels.forEachIndexed { index, pixel ->
      rgba[index * 4] = ((pixel ushr 16) and 0xff).toByte()
      rgba[index * 4 + 1] = ((pixel ushr 8) and 0xff).toByte()
      rgba[index * 4 + 2] = (pixel and 0xff).toByte()
      rgba[index * 4 + 3] = ((pixel ushr 24) and 0xff).toByte()
    }
    assertEquals(YuJianCoreRenderer.SOURCE_CORE_WEBP_RGBA_SHA256, sha256(rgba))
    bitmap.recycle()
  }

  @Test
  fun translatedProductionSphereShaderKeepsTheCoreOpticalPathAndUsesGles3() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val source = context.assets.open("yujian_orb_core/YuJianCoreFragment.glsl").bufferedReader().use { it.readText() }
    assertTrue(source.startsWith("#version 300 es"))
    assertTrue(source.contains("uniform sampler2D uBackTex"))
    assertTrue(source.contains("float causticField("))
    assertTrue(source.contains("float meniscusY ="))
    assertTrue(source.contains("float fresnel = pow(1.0 - nz, 3.6)"))
    assertTrue(source.contains("texture(uBackTex, uvR)"))
    assertTrue(source.contains("uSphereCenter"))
    assertTrue(source.contains("uSphereRadii"))
    assertTrue("Tab 3 uses only Pass 6 for YuJian Core", AIOrbScene.activeRenderPassesYuJianHybrid(
      com.example.orb.config.RenderDebugMode.FULL_RENDER
    ).count { it.startsWith("PASS_6_") } == 1)
  }

  private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
