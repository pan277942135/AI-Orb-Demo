package com.example

import android.content.Context
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
    val metadata = readExtendedWebpMetadata(bytes)
    assertEquals(384, metadata.width)
    assertEquals(384, metadata.height)
    assertTrue("VP8X source container must declare alpha", metadata.hasAlpha)
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
    assertTrue("Tab 3 draws YuJian Core once between REF_B rear and front passes", AIOrbScene.activeRenderPassesYuJianHybrid(
      com.example.orb.config.RenderDebugMode.FULL_RENDER
    ).count { it == "PASS_5_YUJIAN_CORE_SOURCE_SPHERE_FS" } == 1)
  }

  private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private data class WebpMetadata(val width: Int, val height: Int, val hasAlpha: Boolean)

  private fun readExtendedWebpMetadata(bytes: ByteArray): WebpMetadata {
    assertTrue("RIFF signature", bytes.size >= 30 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
    assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
    var offset = 12
    while (offset + 18 <= bytes.size) {
      val chunkName = String(bytes, offset, 4, Charsets.US_ASCII)
      val chunkLength = littleEndianUInt32(bytes, offset + 4).toInt()
      if (chunkName == "VP8X") {
        assertEquals("VP8X extended header length", 10, chunkLength)
        val flags = bytes[offset + 8].toInt() and 0xff
        val width = 1 + littleEndianUInt24(bytes, offset + 12)
        val height = 1 + littleEndianUInt24(bytes, offset + 15)
        return WebpMetadata(width, height, (flags and 0x10) != 0)
      }
      offset += 8 + chunkLength + (chunkLength and 1)
    }
    throw AssertionError("Expected VP8X chunk in pinned WebP")
  }

  private fun littleEndianUInt24(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or
      ((bytes[offset + 1].toInt() and 0xff) shl 8) or
      ((bytes[offset + 2].toInt() and 0xff) shl 16)

  private fun littleEndianUInt32(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xff) or
      ((bytes[offset + 1].toLong() and 0xff) shl 8) or
      ((bytes[offset + 2].toLong() and 0xff) shl 16) or
      ((bytes[offset + 3].toLong() and 0xff) shl 24)
}
