package com.example

import com.example.orb.v3.CoreVolumeLifecycleState
import com.example.orb.v3.OrbV3AssetManifest
import com.example.orb.v3.OrbVolumeTexture3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldCore3DVolumeAssetsTest {

    private fun resolveWorkspaceRoot(): File {
        val candidates = listOf(File("."), File(".."), File("/app/applet"))
        return candidates.firstOrNull { File(it, "app/src/main/assets/orb_v3").isDirectory }
            ?: error("Unable to locate workspace root with app/src/main/assets/orb_v3")
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test
    fun t01_all32SpatialZSlicesAndManifestExistInAppAssets() {
        val root = resolveWorkspaceRoot()
        val assetsRoot = File(root, "app/src/main/assets/${OrbV3AssetManifest.ASSET_ROOT}")
        val manifestFile = File(root, "app/src/main/assets/${OrbV3AssetManifest.MANIFEST_ASSET_PATH}")
        assertTrue("Missing ${OrbV3AssetManifest.MANIFEST_ASSET_PATH}", manifestFile.isFile)

        val manifest = OrbV3AssetManifest.parseJson(manifestFile.readText())
        assertEquals(256, manifest.volume.width)
        assertEquals(256, manifest.volume.height)
        assertEquals(32, manifest.volume.depth)
        assertEquals(32, manifest.volume.slices.size)
        assertEquals("GL_TEXTURE_3D", manifest.volume.gpuTarget)

        for (i in 0 until 32) {
            val slice = manifest.volume.slices[i]
            assertEquals("Slice zIndex order mismatch", i, slice.zIndex)
            val f = File(assetsRoot, slice.path)
            assertTrue("Missing Z-slice $i at ${slice.path}", f.isFile && f.length() > 100L)
            assertEquals("SHA-256 mismatch for Z-slice $i", slice.sha256, sha256(f))
        }
        val caustics = manifest.findMap("caustics")
        val glassEnv = manifest.findMap("glass_environment")
        val emerald = manifest.findMap("emerald_distribution")
        assertTrue(File(assetsRoot, caustics.path).isFile)
        assertTrue(File(assetsRoot, glassEnv.path).isFile)
        assertTrue(File(assetsRoot, emerald.path).isFile)
    }

    @Test
    fun t02_frozenWaterWorldMasterAndZipsRetainExactSha256() {
        val root = resolveWorkspaceRoot()
        val masterPng = File(root, "releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png")
        val v2Zip = File(root, "releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip")
        val v21Zip = File(root, "releases/water-world-v2-v21/delivery/WATER_WORLD_V2_1_ASSETS.zip")
        val v21ShaFile = File(root, "releases/water-world-v2-v21/delivery/WATER_WORLD_V2_1_ASSETS.zip.sha256")

        assertTrue("Frozen master PNG must exist", masterPng.isFile)
        assertEquals(
            "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589",
            sha256(masterPng),
        )
        assertTrue("Frozen V2 ZIP must exist at releases/water-world-v2/", v2Zip.isFile)
        assertEquals(
            "6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095",
            sha256(v2Zip),
        )
        if (v21Zip.isFile) {
            assertEquals(
                "28f8918c39f6e875602d3a7e90775e9d455aa6c4e42783247d1104c3b4509679",
                sha256(v21Zip),
            )
        } else {
            assertTrue("Frozen V2.1 SHA-256 lockfile must exist", v21ShaFile.isFile)
            assertTrue(
                "Frozen V2.1 SHA-256 lockfile must preserve exact digest",
                v21ShaFile.readText().contains("28f8918c39f6e875602d3a7e90775e9d455aa6c4e42783247d1104c3b4509679"),
            )
        }
    }

    @Test
    fun t03_orbVolumeTextureFailureTransitionsToCoreVolumeErrorAndContextLossState() {
        val vol = OrbVolumeTexture3D()
        assertEquals(CoreVolumeLifecycleState.CORE_VOLUME_LOADING, vol.lifecycleState)
        assertFalse(vol.isReady)

        // Null AssetManager must explicitly report CORE_VOLUME_ERROR (never silently mark ready!)
        vol.preloadAsync(null)
        assertEquals(CoreVolumeLifecycleState.CORE_VOLUME_ERROR, vol.lifecycleState)
        assertTrue(vol.telemetry.hasError)
        assertFalse(vol.isReady)

        // EGL context loss transitions cleanly to CORE_VOLUME_CONTEXT_LOST
        vol.markContextLost()
        assertEquals(CoreVolumeLifecycleState.CORE_VOLUME_CONTEXT_LOST, vol.lifecycleState)
        assertFalse(vol.isReady)
    }
}
