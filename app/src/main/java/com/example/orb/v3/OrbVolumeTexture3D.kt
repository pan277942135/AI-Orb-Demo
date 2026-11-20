package com.example.orb.v3

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log
import com.example.orb.gl.GlShaderProgram
import java.nio.ByteBuffer

/**
 * Runtime diagnostics for V3 WebP decoding & OpenGL ES 3.0 3D Volume texture upload.
 */
data class OrbVolumeTelemetry(
  val isLoaded: Boolean = false,
  val volumeWidth: Int = 256,
  val volumeHeight: Int = 256,
  val volumeDepth: Int = 32,
  val decodeTimeMs: Float = 0f,
  val uploadTimeMs: Float = 0f,
  val gpuMemoryBytes: Long = 0L,
  val max3dTextureSize: Int = 0,
  val isSrgbVolume: Boolean = true,
  val statusMessage: String = "Pending V3 WebP load",
) {
  val gpuMemoryMiB: Float get() = gpuMemoryBytes / (1024f * 1024f)
  val dimensionsLabel: String get() = "${volumeWidth}x${volumeHeight}x${volumeDepth}"
}

/**
 * Manages the OpenGL ES 3.0 `GL_TEXTURE_3D` fluid volume and associated 2D optical/detail
 * textures uploaded from Lossless RGBA WebP production assets.
 *
 * Texture Units Contract:
 * - Unit 0: `uRefractionTex` (FBO scene refraction snapshot, bound by caller)
 * - Unit 1: `uFluidVolume` (`GL_TEXTURE_3D`, 256x256x32 `GL_SRGB8_ALPHA8`, linear + clamp)
 * - Unit 2: `uFluidDetailMap` (`GL_TEXTURE_2D`, 256x256 `GL_RGBA8`, linear + repeat)
 * - Unit 3: `uCausticsMap` (`GL_TEXTURE_2D`, 256x256 `GL_RGBA8`, linear + repeat)
 * - Unit 4: `uGlassEnvMap` (`GL_TEXTURE_2D`, 512x256 `GL_SRGB8_ALPHA8`, linear + repeat-S/clamp-T)
 */
class OrbVolumeTexture3D {

  var volumeTexId: Int = 0
    private set
  var fluidDetailTexId: Int = 0
    private set
  var causticsTexId: Int = 0
    private set
  var glassEnvTexId: Int = 0
    private set

  var telemetry: OrbVolumeTelemetry = OrbVolumeTelemetry()
    private set

  val isReady: Boolean
    get() = volumeTexId != 0 && fluidDetailTexId != 0 && causticsTexId != 0 && glassEnvTexId != 0

  /**
   * Decodes Lossless RGBA WebP assets from [assetManager] and uploads them to OpenGL ES 3.0.
   * Must be invoked on the active EGL/GL thread (e.g., `onSurfaceCreated`).
   */
  fun loadAndUpload(assetManager: AssetManager?): OrbVolumeTelemetry {
    release()

    if (assetManager == null) {
      telemetry = OrbVolumeTelemetry(
        isLoaded = false,
        statusMessage = "No AssetManager (procedural fallback)",
      )
      return telemetry
    }

    return try {
      val decoded = OrbWebpAssetLoader.loadAll(assetManager)
      uploadDecoded(decoded)
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to load/upload V3 WebP 3D Volume textures", t)
      release()
      telemetry = OrbVolumeTelemetry(
        isLoaded = false,
        statusMessage = "Load error: ${t.message ?: t.javaClass.simpleName}",
      )
      telemetry
    }
  }

  private fun uploadDecoded(decoded: DecodedOrbV3Assets): OrbVolumeTelemetry {
    val uploadStart = System.nanoTime()

    val max3dArr = IntArray(1)
    GLES30.glGetIntegerv(GLES30.GL_MAX_3D_TEXTURE_SIZE, max3dArr, 0)
    val max3d = max3dArr[0]
    require(max3d >= decoded.volumeWidth && max3d >= decoded.volumeDepth) {
      "Device GL_MAX_3D_TEXTURE_SIZE=$max3d too small for ${decoded.volumeWidth}x${decoded.volumeHeight}x${decoded.volumeDepth}"
    }

    // Drain any pre-existing GL error flags before upload diagnostics
    while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* clear */ }

    GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)

    // 1. Upload 256x256x32 GL_TEXTURE_3D (GL_SRGB8_ALPHA8 -> hardware exact sRGB RGB + linear Alpha)
    val handles = IntArray(4)
    GLES30.glGenTextures(4, handles, 0)
    volumeTexId = handles[0]
    fluidDetailTexId = handles[1]
    causticsTexId = handles[2]
    glassEnvTexId = handles[3]

    var usedSrgbVolume = true
    GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, volumeTexId)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

    decoded.volumeRgba.rewind()
    GLES30.glTexImage3D(
      GLES30.GL_TEXTURE_3D,
      0,
      GLES30.GL_SRGB8_ALPHA8,
      decoded.volumeWidth,
      decoded.volumeHeight,
      decoded.volumeDepth,
      0,
      GLES30.GL_RGBA,
      GLES30.GL_UNSIGNED_BYTE,
      decoded.volumeRgba,
    )
    var err = GLES30.glGetError()
    if (err != GLES30.GL_NO_ERROR) {
      Log.w(TAG, "GL_SRGB8_ALPHA8 3D texture upload returned GL error 0x${err.toString(16)}; retrying with GL_RGBA8")
      usedSrgbVolume = false
      decoded.volumeRgba.rewind()
      GLES30.glTexImage3D(
        GLES30.GL_TEXTURE_3D,
        0,
        GLES30.GL_RGBA8,
        decoded.volumeWidth,
        decoded.volumeHeight,
        decoded.volumeDepth,
        0,
        GLES30.GL_RGBA,
        GLES30.GL_UNSIGNED_BYTE,
        decoded.volumeRgba,
      )
      err = GLES30.glGetError()
      check(err == GLES30.GL_NO_ERROR) { "glTexImage3D failed with GL error 0x${err.toString(16)}" }
    }
    GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)

    // 2. Upload 2D Linear Data maps (`GL_RGBA8`, NO sRGB!) and Equirectangular Env (`GL_SRGB8_ALPHA8`)
    uploadTexture2D(
      texId = fluidDetailTexId,
      tex = decoded.emeraldDetailMap,
      wrapS = GLES30.GL_REPEAT,
      wrapT = GLES30.GL_REPEAT,
    )
    uploadTexture2D(
      texId = causticsTexId,
      tex = decoded.causticsMap,
      wrapS = GLES30.GL_REPEAT,
      wrapT = GLES30.GL_REPEAT,
    )
    uploadTexture2D(
      texId = glassEnvTexId,
      tex = decoded.glassEnvMap,
      wrapS = GLES30.GL_REPEAT,
      wrapT = GLES30.GL_CLAMP_TO_EDGE,
    )

    val uploadMs = (System.nanoTime() - uploadStart) * 1e-6f
    telemetry = OrbVolumeTelemetry(
      isLoaded = true,
      volumeWidth = decoded.volumeWidth,
      volumeHeight = decoded.volumeHeight,
      volumeDepth = decoded.volumeDepth,
      decodeTimeMs = decoded.decodeTimeMs,
      uploadTimeMs = uploadMs,
      gpuMemoryBytes = decoded.totalGpuBytes,
      max3dTextureSize = max3d,
      isSrgbVolume = usedSrgbVolume,
      statusMessage = "WebP 3D Volume OK (${decoded.volumeWidth}x${decoded.volumeHeight}x${decoded.volumeDepth})",
    )
    Log.i(
      TAG,
      "Uploaded V3 WebP Volume ${telemetry.dimensionsLabel} (${String.format("%.2f", telemetry.gpuMemoryMiB)} MiB) " +
        "decode=${String.format("%.1f", telemetry.decodeTimeMs)}ms upload=${String.format("%.1f", telemetry.uploadTimeMs)}ms",
    )
    return telemetry
  }

  private fun uploadTexture2D(
    texId: Int,
    tex: DecodedTexture2D,
    wrapS: Int,
    wrapT: Int,
  ) {
    val internalFormat = if (tex.isSrgb) GLES30.GL_SRGB8_ALPHA8 else GLES30.GL_RGBA8
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrapS)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrapT)
    tex.rgbaBuffer.rewind()
    GLES30.glTexImage2D(
      GLES30.GL_TEXTURE_2D,
      0,
      internalFormat,
      tex.width,
      tex.height,
      0,
      GLES30.GL_RGBA,
      GLES30.GL_UNSIGNED_BYTE,
      tex.rgbaBuffer,
    )
    val err = GLES30.glGetError()
    check(err == GLES30.GL_NO_ERROR) {
      "glTexImage2D failed for ${tex.id} with GL error 0x${err.toString(16)}"
    }
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
  }

  fun bindTo(program: GlShaderProgram) {
    if (!isReady) {
      program.setInt("uVolumeLoaded", 0)
      program.setInt("uVolumeIsSrgb", 1)
      return
    }
    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, volumeTexId)
    program.setInt("uFluidVolume", 1)

    GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fluidDetailTexId)
    program.setInt("uFluidDetailMap", 2)

    GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, causticsTexId)
    program.setInt("uCausticsMap", 3)

    GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glassEnvTexId)
    program.setInt("uGlassEnvMap", 4)

    program.setInt("uVolumeLoaded", 1)
    program.setInt("uVolumeIsSrgb", if (telemetry.isSrgbVolume) 1 else 0)
  }

  fun unbind() {
    GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, 0)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
  }

  fun release() {
    val toDelete = intArrayOf(volumeTexId, fluidDetailTexId, causticsTexId, glassEnvTexId)
      .filter { it != 0 }
      .toIntArray()
    if (toDelete.isNotEmpty()) {
      GLES30.glDeleteTextures(toDelete.size, toDelete, 0)
    }
    volumeTexId = 0
    fluidDetailTexId = 0
    causticsTexId = 0
    glassEnvTexId = 0
  }

  companion object {
    private const val TAG = "OrbVolumeTexture3D"
  }
}
