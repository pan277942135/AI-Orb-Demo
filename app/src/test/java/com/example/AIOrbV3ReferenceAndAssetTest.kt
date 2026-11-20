package com.example

import com.example.orb.v3.OrbV3AssetManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * AI-Orb-Demo V3 Reference Master Lock & WebP Asset Integrity Tests.
 *
 * Verifies:
 * 1. Sole frozen visual master `public/assets/aistudio/____________910d1531.png` is unmodified
 *    (exact SHA-256 and 1254x1254 PNG IHDR dimensions).
 * 2. V3 master lock & breakdown docs exist and cite the locked master SHA-256.
 * 3. All 35 Lossless RGBA WebP assets (32 volume slices + 3 optical/detail maps) exist,
 *    contain valid RIFF/WEBP + VP8L lossless chunks, and match ORB_V3_ASSET_MANIFEST.json + SHA256SUMS.
 * 4. Zero YuJian assets exist anywhere in V3 asset or source directories.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AIOrbV3ReferenceAndAssetTest {

  private fun projectRoot(): File {
    var dir = File(System.getProperty("user.dir") ?: ".").canonicalFile
    while (!File(dir, "settings.gradle.kts").exists()) {
      dir = dir.parentFile ?: break
    }
    assertTrue("Could not locate project root from ${System.getProperty("user.dir")}", File(dir, "settings.gradle.kts").exists())
    return dir
  }

  private fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(65536)
      while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  private fun extractWebpChunks(bytes: ByteArray): Set<String> {
    assertTrue("WebP file too small", bytes.size >= 16)
    assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
    assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
    val chunks = linkedSetOf<String>()
    var pos = 12
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    while (pos + 8 <= bytes.size) {
      val fourcc = String(bytes, pos, 4, Charsets.US_ASCII)
      val size = bb.getInt(pos + 4)
      chunks.add(fourcc)
      pos += 8 + size + (size and 1)
    }
    return chunks
  }

  @Test
  fun frozenVisualMasterIsUnmodifiedAndMatchesExactLockContract() {
    val root = projectRoot()
    val master = File(root, "public/assets/aistudio/____________910d1531.png")
    assertTrue("Sole frozen master PNG must exist at ${master.path}", master.exists())
    assertEquals(OrbV3AssetManifest.EXPECTED_MASTER_SHA256, sha256(master))

    // Verify exact PNG IHDR dimensions (1254 x 1254)
    val header = ByteArray(24)
    master.inputStream().use { it.read(header) }
    val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
    assertEquals(1254, bb.getInt(16))
    assertEquals(1254, bb.getInt(20))

    val lockDoc = File(root, "docs/v3/REFERENCE_MASTER_LOCK.md")
    val breakdownDoc = File(root, "docs/v3/REFERENCE_MASTER_BREAKDOWN.md")
    assertTrue("REFERENCE_MASTER_LOCK.md must exist", lockDoc.exists())
    assertTrue("REFERENCE_MASTER_BREAKDOWN.md must exist", breakdownDoc.exists())
    assertTrue(lockDoc.readText().contains(OrbV3AssetManifest.EXPECTED_MASTER_SHA256))
    assertTrue(breakdownDoc.readText().contains(OrbV3AssetManifest.EXPECTED_MASTER_SHA256))
  }

  @Test
  fun all35LosslessRgbaWebpAssetsMatchManifestAndSha256Sums() {
    val root = projectRoot()
    val assetsRoot = File(root, "app/src/main/assets/orb_v3")
    val manifestFile = File(assetsRoot, "manifest/ORB_V3_ASSET_MANIFEST.json")
    val shaSumsFile = File(assetsRoot, "manifest/SHA256SUMS")

    assertTrue("ORB_V3_ASSET_MANIFEST.json must exist", manifestFile.exists())
    assertTrue("SHA256SUMS must exist", shaSumsFile.exists())

    val manifest = OrbV3AssetManifest.parseJson(manifestFile.readText(Charsets.UTF_8))
    assertEquals(OrbV3AssetManifest.EXPECTED_SCHEMA, manifest.schemaVersion)
    assertEquals(OrbV3AssetManifest.EXPECTED_MASTER_SHA256, manifest.masterSha256)
    assertEquals(256, manifest.volume.width)
    assertEquals(256, manifest.volume.height)
    assertEquals(32, manifest.volume.depth)
    assertEquals(32, manifest.volume.slices.size)
    assertEquals(3, manifest.maps.size)
    assertEquals(35, manifest.totalWebpFiles)
    assertFalse("Volume must store un-premultiplied RGBA", manifest.volume.premultipliedAlpha)
    assertEquals(8L * 1024L * 1024L, manifest.volume.gpuMemoryBytes)

    val sumsByPath = shaSumsFile.readLines(Charsets.UTF_8)
      .filter { it.isNotBlank() }
      .associate { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        parts[1] to parts[0]
      }
    assertEquals(35, sumsByPath.size)

    // Verify all 32 3D volume slices
    for ((idx, slice) in manifest.volume.slices.withIndex()) {
      assertEquals(idx, slice.zIndex)
      val f = File(assetsRoot, slice.path)
      assertTrue("Missing volume slice ${slice.path}", f.exists())
      assertEquals("Byte length mismatch for ${slice.path}", slice.bytes, f.length())
      val actualSha = sha256(f)
      assertEquals("Manifest SHA-256 mismatch for ${slice.path}", slice.sha256, actualSha)
      assertEquals("SHA256SUMS mismatch for ${slice.path}", actualSha, sumsByPath[slice.path])

      val chunks = extractWebpChunks(f.readBytes())
      assertTrue("${slice.path} must be Lossless WebP (VP8L chunk missing, found $chunks)", chunks.contains("VP8L"))
    }

    // Verify all 3 2D optical/detail WebP maps
    for (map in manifest.maps) {
      val f = File(assetsRoot, map.path)
      assertTrue("Missing 2D WebP map ${map.path}", f.exists())
      assertEquals("Byte length mismatch for ${map.path}", map.bytes, f.length())
      val actualSha = sha256(f)
      assertEquals("Manifest SHA-256 mismatch for ${map.path}", map.sha256, actualSha)
      assertEquals("SHA256SUMS mismatch for ${map.path}", actualSha, sumsByPath[map.path])

      val chunks = extractWebpChunks(f.readBytes())
      assertTrue("${map.path} must be Lossless WebP (VP8L chunk missing, found $chunks)", chunks.contains("VP8L"))
    }

    // Confirm zero YuJian references in orb_v3 assets
    val allAssetNames = assetsRoot.walkTopDown().map { it.name.lowercase() }.toList()
    assertTrue(
      "Zero YuJian assets allowed in orb_v3",
      allAssetNames.none { it.contains("yujian") || it.contains("shiyu") || it.contains("fish") },
    )
  }
}
