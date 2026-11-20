package com.example.orb.v3

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Decoded raw RGBA8 2D texture ready for OpenGL ES 3.0 `glTexImage2D`.
 */
data class DecodedTexture2D(
  val id: String,
  val width: Int,
  val height: Int,
  val isSrgb: Boolean,
  val rgbaBuffer: ByteBuffer,
) {
  val byteCount: Int get() = width * height * 4
}

/**
 * Complete decoded V3 WebP bundle (256x256x32 3D Fluid Volume + 3 2D Optical/Detail Maps),
 * decoded natively on Android with `inScaled = false`, `inPremultiplied = false`, `ARGB_8888`.
 */
data class DecodedOrbV3Assets(
  val manifest: OrbV3AssetManifest,
  val volumeWidth: Int,
  val volumeHeight: Int,
  val volumeDepth: Int,
  val volumeRgba: ByteBuffer,
  val emeraldDetailMap: DecodedTexture2D,
  val causticsMap: DecodedTexture2D,
  val glassEnvMap: DecodedTexture2D,
  val decodeTimeMs: Float,
) {
  val totalGpuBytes: Long
    get() = (volumeWidth.toLong() * volumeHeight * volumeDepth * 4L) +
      emeraldDetailMap.byteCount.toLong() +
      causticsMap.byteCount.toLong() +
      glassEnvMap.byteCount.toLong()
}

/**
 * Native Android Lossless RGBA WebP decoder for AI-Orb-Demo V3.
 *
 * Strictly enforces:
 * - `Bitmap.Config.ARGB_8888` (byte-order RGBA in native little-endian memory)
 * - `inScaled = false` (zero DPI scaling corruption)
 * - `inPremultiplied = false` (preserves un-premultiplied fluid color RGB and linear density A)
 * - Immediate `Bitmap.recycle()` after copying each slice into a reusable direct ByteBuffer
 */
object OrbWebpAssetLoader {

  fun loadAll(assetManager: AssetManager): DecodedOrbV3Assets {
    val startNanos = System.nanoTime()
    val manifest = OrbV3AssetManifest.loadFromAssets(assetManager)
    val vol = manifest.volume

    require(vol.slices.size == vol.depth) {
      "Volume slice count mismatch: manifest has ${vol.slices.size}, expected depth=${vol.depth}"
    }

    val sliceBytes = vol.width * vol.height * 4
    val totalVolumeBytes = sliceBytes * vol.depth
    val volumeBuffer = ByteBuffer.allocateDirect(totalVolumeBytes).order(ByteOrder.nativeOrder())
    val scratchSlice = ByteBuffer.allocateDirect(sliceBytes).order(ByteOrder.nativeOrder())

    for (slice in vol.slices) {
      val assetPath = "${OrbV3AssetManifest.ASSET_ROOT}/${slice.path}"
      decodeWebpIntoBuffer(
        assetManager = assetManager,
        assetPath = assetPath,
        expectedWidth = vol.width,
        expectedHeight = vol.height,
        target = scratchSlice,
      )
      scratchSlice.rewind()
      volumeBuffer.put(scratchSlice)
    }
    volumeBuffer.rewind()

    val emeraldDesc = manifest.findMap("emerald_distribution")
    val causticsDesc = manifest.findMap("caustics")
    val envDesc = manifest.findMap("glass_environment")

    val emeraldMap = decodeMap2D(assetManager, emeraldDesc)
    val causticsMap = decodeMap2D(assetManager, causticsDesc)
    val envMap = decodeMap2D(assetManager, envDesc)

    val decodeMs = (System.nanoTime() - startNanos) * 1e-6f
    return DecodedOrbV3Assets(
      manifest = manifest,
      volumeWidth = vol.width,
      volumeHeight = vol.height,
      volumeDepth = vol.depth,
      volumeRgba = volumeBuffer,
      emeraldDetailMap = emeraldMap,
      causticsMap = causticsMap,
      glassEnvMap = envMap,
      decodeTimeMs = decodeMs,
    )
  }

  private fun decodeMap2D(
    assetManager: AssetManager,
    desc: OrbV3AssetManifest.MapDescriptor,
  ): DecodedTexture2D {
    val assetPath = "${OrbV3AssetManifest.ASSET_ROOT}/${desc.path}"
    val buf = ByteBuffer.allocateDirect(desc.width * desc.height * 4).order(ByteOrder.nativeOrder())
    decodeWebpIntoBuffer(
      assetManager = assetManager,
      assetPath = assetPath,
      expectedWidth = desc.width,
      expectedHeight = desc.height,
      target = buf,
    )
    buf.rewind()
    return DecodedTexture2D(
      id = desc.id,
      width = desc.width,
      height = desc.height,
      isSrgb = desc.isSrgb,
      rgbaBuffer = buf,
    )
  }

  internal fun decodeWebpIntoBuffer(
    assetManager: AssetManager,
    assetPath: String,
    expectedWidth: Int,
    expectedHeight: Int,
    target: ByteBuffer,
  ) {
    val opts = BitmapFactory.Options().apply {
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inScaled = false
      inPremultiplied = false
      inMutable = false
    }
    val bitmap = assetManager.open(assetPath).use { stream ->
      BitmapFactory.decodeStream(stream, null, opts)
    } ?: error("Failed to decode Lossless WebP asset: $assetPath")

    try {
      require(bitmap.width == expectedWidth && bitmap.height == expectedHeight) {
        "WebP dimensions mismatch for $assetPath: got ${bitmap.width}x${bitmap.height}, expected ${expectedWidth}x${expectedHeight}"
      }
      val rgbaBitmap = if (bitmap.config == Bitmap.Config.ARGB_8888) {
        bitmap
      } else {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
      }
      try {
        target.clear()
        rgbaBitmap.copyPixelsToBuffer(target)
        target.rewind()
      } finally {
        if (rgbaBitmap !== bitmap) {
          rgbaBitmap.recycle()
        }
      }
    } finally {
      bitmap.recycle()
    }
  }

  /** Exact IEC 61966-2-1 sRGB byte -> Linear float helper for validation & CPU/GPU parity checks. */
  fun srgbByteToLinear(u8: Int): Float {
    val s = (u8.coerceIn(0, 255)) / 255.0f
    return if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
  }
}
