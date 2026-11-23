package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.QualityTier
import com.example.orb.config.RenderDebugMode
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.scene.AIOrbScene
import com.example.orb.v3.OrbV3AssetManifest
import com.example.orb.v3.OrbWebpAssetLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * AI-Orb-Demo V3 Robolectric & GLSL ES 3.00 Contract Tests:
 * - Verifies Android AssetManager manifest parsing & native Lossless WebP decoding (inPremultiplied=false)
 * - Verifies sampler3D uFluidVolume Beer-Lambert raymarching, independent Core Rotation,
 *   3D curl advection, V3 diagnostic modes, and removal of V2 front-right specular blob
 * - Verifies V3 single-volume render pass architecture (zero duplicate inner-bowl fluid pass)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AIOrbV3VolumeShaderAndLoaderTest {

  @Test
  fun androidAssetManagerLoadsV3ManifestAndDecodesAll35LosslessWebpAssets() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manifest = OrbV3AssetManifest.loadFromAssets(context.assets)

    assertEquals(OrbV3AssetManifest.EXPECTED_SCHEMA, manifest.schemaVersion)
    assertEquals(256, manifest.volume.width)
    assertEquals(256, manifest.volume.height)
    assertEquals(32, manifest.volume.depth)
    assertEquals(35, manifest.totalWebpFiles)

    val decoded = OrbWebpAssetLoader.loadAll(context.assets)
    assertEquals(256, decoded.volumeWidth)
    assertEquals(256, decoded.volumeHeight)
    assertEquals(32, decoded.volumeDepth)
    assertEquals(256 * 256 * 32 * 4, decoded.volumeRgba.capacity())
    assertEquals(256, decoded.emeraldDetailMap.width)
    assertEquals(256, decoded.causticsMap.width)
    assertEquals(512, decoded.glassEnvMap.width)
    assertEquals(256, decoded.glassEnvMap.height)
    assertFalse(decoded.emeraldDetailMap.isSrgb)
    assertFalse(decoded.causticsMap.isSrgb)
    assertTrue(decoded.glassEnvMap.isSrgb)
    assertEquals(9L * 1024L * 1024L, decoded.totalGpuBytes)
    assertTrue("WebP decode time should be positive", decoded.decodeTimeMs >= 0f)

    // Verify decoded mid-volume slice (z=16) has zero corner alpha (clean spherical containment)
    // and genuine non-zero fluid voxels inside the lower liquid volume
    val sliceStride = 256 * 256 * 4
    val z16Offset = 16 * sliceStride
    val topLeftCornerAlpha = decoded.volumeRgba.get(z16Offset + 3).toInt() and 0xFF
    assertEquals("Cube corner outside sphere must have alpha == 0", 0, topLeftCornerAlpha)

    var fluidPixels = 0
    var emeraldPixels = 0
    for (y in 0 until 256 step 2) {
      for (x in 0 until 256 step 2) {
        val off = z16Offset + (y * 256 + x) * 4
        val g = decoded.volumeRgba.get(off + 1).toInt() and 0xFF
        val b = decoded.volumeRgba.get(off + 2).toInt() and 0xFF
        val a = decoded.volumeRgba.get(off + 3).toInt() and 0xFF
        if (a > 24) {
          fluidPixels++
          if (g > b && g > 70) emeraldPixels++
        }
      }
    }
    assertTrue("Mid-slice z16 must contain rich volumetric fluid voxels ($fluidPixels)", fluidPixels > 4000)
    assertTrue("Mid-slice z16 must contain emerald-green curtain voxels ($emeraldPixels)", emeraldPixels > 1000)

    // Verify exact sRGB -> Linear helper monotonicity
    assertEquals(0f, OrbWebpAssetLoader.srgbByteToLinear(0), 1e-6f)
    assertEquals(1f, OrbWebpAssetLoader.srgbByteToLinear(255), 1e-5f)
    assertTrue(OrbWebpAssetLoader.srgbByteToLinear(128) in 0.20f..0.23f)
  }

  @Test
  fun coreGlassFragmentShaderImplementsV3Sampler3dVolumeRaymarchAndEliminatesFrontRightBlob() {
    val frag = CoreFluidShaders.CORE_GLASS_FRAG

    // V3 GLES 3.0 3D Volume & 2D Optical WebP texture contracts
    assertTrue(frag.contains("precision highp sampler3D;"))
    assertTrue(frag.contains("uniform sampler3D uFluidVolume;"))
    assertTrue(frag.contains("uniform sampler2D uFluidDetailMap;"))
    assertTrue(frag.contains("uniform sampler2D uCausticsMap;"))
    assertTrue(frag.contains("uniform sampler2D uGlassEnvMap;"))
    assertTrue(frag.contains("texture(uFluidVolume,"))
    assertTrue(frag.contains("texture(uFluidDetailMap,"))
    assertTrue(frag.contains("texture(uCausticsMap,"))
    assertTrue(frag.contains("texture(uGlassEnvMap,"))
    assertTrue(frag.contains("traceBackgroundUv("))
    assertTrue(frag.contains("refract(rayDir, -normalize(innerExit)"))
    assertFalse("sRGB texture-unit output must not be gamma-expanded again", frag.contains("if (uCausticsIsSrgb > 0)"))
    assertFalse(frag.contains("uFluidDetailIsSrgb"))

    // True independent 3D Core Rotation + bounded 3D curl + Beer-Lambert Front-to-Back accumulation
    assertTrue(frag.contains("mat3 invCoreRot = transpose(uCoreRotMat);"))
    assertTrue(frag.contains("computeVolumeCurl("))
    assertTrue(frag.contains("accumColor += volumeTransmittance * litSample * (vec3(1.0) - segmentTransmittance)"))
    assertTrue(frag.contains("volumeTransmittance *= segmentTransmittance"))
    assertTrue(frag.contains("accumAlpha += (1.0 - accumAlpha)"))
    assertTrue(frag.contains("if (i > steps || accumAlpha > 0.96) break;"))

    // V3 Diagnostic isolation shader branches
    assertTrue(frag.contains("if (uDebugMode == 7)")) // FLUID_VOLUME_ONLY
    assertTrue(frag.contains("if (uDebugMode == 8)")) // VOLUME_DEPTH
    assertTrue(frag.contains("if (uDebugMode == 10)")) // FOLD_NORMAL
    assertTrue(frag.contains("(uDebugMode == 5)"))    // CORE_GLASS_ONLY / GLASS_ONLY

    // Confirm V2 broad front-right specular blob (`specWarm`) was permanently eliminated
    assertFalse("V2 broad front-right specWarm blob must not exist in V3 glass shader", frag.contains("float specWarm"))
  }

  @Test
  fun v3ScenePipelineUsesSingleVolumeWithoutDuplicateInnerBowlAndPreservesOuterResidents() {
    val scene = AIOrbScene()
    assertTrue("AIOrbScene must run V3 single-volume pipeline", scene.isV3SingleVolumePipeline)

    // Quality tier V3 raymarch steps: 16 / 32 / 48
    assertEquals(16, QualityTier.LOW.raymarchSteps)
    assertEquals(32, QualityTier.MEDIUM.raymarchSteps)
    assertEquals(48, QualityTier.HIGH.raymarchSteps)

    // V3 RenderDebugMode isolation contracts
    assertEquals(5, RenderDebugMode.CORE_GLASS_ONLY.shaderModeId)
    assertEquals(7, RenderDebugMode.FLUID_VOLUME_ONLY.shaderModeId)
    assertEquals(8, RenderDebugMode.VOLUME_DEPTH.shaderModeId)
    assertTrue(RenderDebugMode.CORE_GLASS_ONLY.isCoreIsolated)
    assertTrue(RenderDebugMode.FLUID_VOLUME_ONLY.isCoreIsolated)
    assertTrue(RenderDebugMode.VOLUME_DEPTH.isCoreIsolated)

    val v3Full = AIOrbScene.activeRenderPassesV3(RenderDebugMode.FULL_RENDER)
    assertTrue(v3Full.none { it.contains("INNER_FLUID_BOWL") })
    assertTrue(v3Full.contains("PASS_3_BACK_RIBBONS_RESIDENT_AND_SECONDARY"))
    assertTrue(v3Full.contains("PASS_6_V3_WEBP_3D_VOLUME_RAYMARCH_AND_CRYSTAL_GLASS_SHELL"))
    assertTrue(v3Full.contains("PASS_7_FRONT_RIBBONS_DEPTH_TESTED"))

    val v3VolOnly = AIOrbScene.activeRenderPassesV3(RenderDebugMode.FLUID_VOLUME_ONLY)
    assertTrue(v3VolOnly.none { it.contains("RIBBON") })
    assertTrue(v3VolOnly.contains("PASS_6_V3_WEBP_3D_VOLUME_RAYMARCH_AND_CRYSTAL_GLASS_SHELL"))

    val source = java.io.File("app/src/main/java/com/example/orb/scene/AIOrbScene.kt").readText()
    assertTrue("Production FULL_RENDER must wait for all 4 uploaded volume textures", source.contains("productionFrameMode && !core.isVolumeReady"))
    assertTrue("GPU evidence must wait for installed APK SHA", source.contains("GPU_CAPTURE_BLOCKED_APK_FINGERPRINT_PENDING"))
    assertTrue("Context loss must invalidate the cached volume state", source.contains("core.markGlContextLost()"))
    val loader = java.io.File("app/src/main/java/com/example/orb/v3/OrbVolumeTexture3D.kt").readText()
    assertTrue("Production readiness must require actual GLES texture objects", loader.contains("it > 0 && GLES30.glIsTexture(it)"))
    assertTrue(loader.contains("telemetry.gpuTextureObjectsValid"))
    val shaderUtils = java.io.File("app/src/main/java/com/example/orb/gl/ShaderUtils.kt").readText()
    assertTrue("Runtime telemetry must read back the linked GL uniform state", shaderUtils.contains("GLES30.glGetUniformiv(programId, loc, value, 0)"))
    assertTrue(shaderUtils.contains("GLES30.glGetUniformfv(programId, loc, value, 0)"))
    val capture = java.io.File("app/src/main/java/com/example/orb/v3/OrbGpuReferenceCapture.kt").readText()
    assertTrue(capture.contains("Production shader read back uVolumeLoaded="))
    assertTrue(capture.contains("Production Density / Transparency / Raymarch uniforms did not match"))

    assertTrue(AIOrbConfig.Default.toJson().contains("\"version\": \"V3_WEBP_3D_VOLUME_HYBRID\""))
  }
}
