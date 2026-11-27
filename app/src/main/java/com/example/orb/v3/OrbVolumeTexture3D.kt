package com.example.orb.v3

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log
import com.example.orb.gl.GlShaderProgram

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit V3 WebP 3D Volume asset lifecycle state (P0-6).
 * Strictly distinguishes production load success, background loading, explicit production asset failure
 * ([V3_ASSET_ERROR] — never silently masked), and headless/test fallback without AssetManager.
 */
enum class OrbV3LoadState(val label: String) {
  UNINITIALIZED("PENDING"),
  LOADING("LOADING_WEBP"),
  LOADED("WEBP3D_OK"),
  V3_ASSET_ERROR("V3_ASSET_ERROR"),
  DIAGNOSTIC_PROCEDURAL_FALLBACK("DIAGNOSTIC_FALLBACK"),
}

/**
 * Explicit Work 3.1 GPU 3D Volume lifecycle state machine (Section 4.2).
 */
enum class CoreVolumeLifecycleState {
  CORE_VOLUME_LOADING,
  CORE_VOLUME_READY,
  CORE_VOLUME_ERROR,
  CORE_VOLUME_CONTEXT_LOST,
  CORE_VOLUME_REUPLOADING,
}

/**
 * Runtime diagnostics for V3 WebP decoding & OpenGL ES 3.0 3D Volume texture upload.
 * Tracks per-texture sRGB/linear upload formats independently (P0-6).
 */
data class OrbVolumeTelemetry(
  val loadState: OrbV3LoadState = OrbV3LoadState.UNINITIALIZED,
  val isLoaded: Boolean = false,
  val volumeWidth: Int = 256,
  val volumeHeight: Int = 256,
  val volumeDepth: Int = 32,
  val decodeTimeMs: Float = 0f,
  val uploadTimeMs: Float = 0f,
  val gpuMemoryBytes: Long = 0L,
  val max3dTextureSize: Int = 0,
  val isSrgbVolume: Boolean = true,
  val isSrgbFluidVolume: Boolean = true,
  val isSrgbGlassEnv: Boolean = true,
  val isSrgbCaustics: Boolean = false,
  val isSrgbFluidDetail: Boolean = false,
  val statusMessage: String = "Pending V3 WebP load",
  val lifecycleState: CoreVolumeLifecycleState = CoreVolumeLifecycleState.CORE_VOLUME_LOADING,
  val uploadGlError: Int = GLES30.GL_NO_ERROR,
  val lastGlError: Int = GLES30.GL_NO_ERROR,
  val failedAssetPath: String? = null,
  val uploadGlStatus: String = "PENDING",
  val uploadAttemptCount: Int = 0,
) {
  val gpuMemoryMiB: Float get() = gpuMemoryBytes / (1024f * 1024f)
  val dimensionsLabel: String get() = "${volumeWidth}x${volumeHeight}x${volumeDepth}"
  val hasError: Boolean get() = loadState == OrbV3LoadState.V3_ASSET_ERROR || lifecycleState == CoreVolumeLifecycleState.CORE_VOLUME_ERROR
}

/**
 * Manages the OpenGL ES 3.0 `GL_TEXTURE_3D` fluid volume and associated 2D optical/detail
 * textures uploaded from Lossless RGBA WebP production assets.
 *
 * Texture Units & Independent Color-Space Contract (P0-6):
 * - Unit 0: `uRefractionTex` (FBO scene refraction snapshot, linear HDR)
 * - Unit 1: `uFluidVolume` (`GL_TEXTURE_3D`, 256x256x32 `GL_SRGB8_ALPHA8`, uniform `uFluidVolumeIsSrgb`)
 * - Unit 2: `uFluidDetailMap` (`GL_TEXTURE_2D`, 256x256 `GL_RGBA8` linear data, uniform `uFluidDetailIsSrgb`)
 * - Unit 3: `uCausticsMap` (`GL_TEXTURE_2D`, 256x256 `GL_RGBA8` linear data, uniform `uCausticsIsSrgb`)
 * - Unit 4: `uGlassEnvMap` (`GL_TEXTURE_2D`, 512x256 `GL_SRGB8_ALPHA8`, uniform `uGlassEnvIsSrgb`)
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

  @Volatile
  var telemetry: OrbVolumeTelemetry = OrbVolumeTelemetry()
    private set

  private val preloadStarted = AtomicBoolean(false)
  private val decodedAssetsRef = AtomicReference<DecodedOrbV3Assets?>(null)
  private val decodeFailureRef = AtomicReference<Throwable?>(null)
  @Volatile private var contextLostFlag: Boolean = false
  @Volatile private var uploadAttempts: Int = 0

  val lifecycleState: CoreVolumeLifecycleState
    get() = telemetry.lifecycleState

  val isReady: Boolean
    get() = telemetry.loadState == OrbV3LoadState.LOADED &&
      telemetry.lifecycleState == CoreVolumeLifecycleState.CORE_VOLUME_READY &&
      volumeTexId != 0 &&
      fluidDetailTexId != 0 &&
      causticsTexId != 0 &&
      glassEnvTexId != 0

  /**
   * Called when EGL context is lost or recreated: drops stale GL texture handles without invoking
   * `glDeleteTextures` on an invalid context, while preserving decoded RGBA buffers in memory so
   * subsequent [uploadIfReady] re-uploads immediately via `CORE_VOLUME_CONTEXT_LOST -> CORE_VOLUME_REUPLOADING -> CORE_VOLUME_READY`.
   */
  fun markContextLost() {
    volumeTexId = 0
    fluidDetailTexId = 0
    causticsTexId = 0
    glassEnvTexId = 0
    contextLostFlag = true
    telemetry = telemetry.copy(
      isLoaded = false,
      lifecycleState = CoreVolumeLifecycleState.CORE_VOLUME_CONTEXT_LOST,
      statusMessage = "EGL context lost; awaiting GPU 3D volume re-upload",
    )
  }

  /**
   * Starts decoding all 35 Lossless RGBA WebP assets on a background daemon thread so
   * `GLSurfaceView.Renderer.onSurfaceCreated()` never blocks the GLThread or triggers Android's
   * 1000ms `SurfaceSyncGroup` transaction timeout.
   */
  fun preloadAsync(assetManager: AssetManager?) {
    if (assetManager == null) {
      telemetry = OrbVolumeTelemetry(
        loadState = OrbV3LoadState.DIAGNOSTIC_PROCEDURAL_FALLBACK,
        isLoaded = false,
        lifecycleState = CoreVolumeLifecycleState.CORE_VOLUME_ERROR,
        uploadGlStatus = "NO_ASSET_MANAGER",
        statusMessage = "Diagnostic procedural fallback (no AssetManager)",
      )
      return
    }
    if (decodedAssetsRef.get() != null || decodeFailureRef.get() != null) {
      return
    }
    if (!preloadStarted.compareAndSet(false, true)) {
      return
    }
    telemetry = telemetry.copy(
      loadState = OrbV3LoadState.LOADING,
      isLoaded = false,
      lifecycleState = CoreVolumeLifecycleState.CORE_VOLUME_LOADING,
      uploadGlStatus = "DECODING",
      statusMessage = "Decoding V3 WebP 3D Volume (background)...",
    )
    Thread(
      {
        try {
          val decoded = OrbWebpAssetLoader.loadAll(assetManager)
          decodedAssetsRef.set(decoded)
        } catch (t: Throwable) {
          Log.e(TAG, "V3_ASSET_ERROR: Background WebP decode failed", t)
          decodeFailureRef.set(t)
        }
      },
      "OrbV3WebpDecoder",
    ).apply {
      isDaemon = true
      priority = Thread.NORM_PRIORITY
      start()
    }
  }

  /**
   * Non-blocking GL-thread check: if background WebP decoding has completed (or failed),
   * uploads the 3D/2D textures immediately (or transitions to [OrbV3LoadState.V3_ASSET_ERROR]).
   * Returns `true` when telemetry/load state changed on this call.
   */
  fun uploadIfReady(assetManager: AssetManager? = null): Boolean {
    if (isReady) return false

    if (assetManager == null && !preloadStarted.get() && decodedAssetsRef.get() == null && decodeFailureRef.get() == null) {
      if (telemetry.loadState != OrbV3LoadState.DIAGNOSTIC_PROCEDURAL_FALLBACK) {
        telemetry = OrbVolumeTelemetry(
          loadState = OrbV3LoadState.DIAGNOSTIC_PROCEDURAL_FALLBACK,
          isLoaded = false,
          lifecycleState = CoreVolumeLifecycleState.CORE_VOLUME_ERROR,
          uploadGlStatus = "NO_ASSET_MANAGER",
          statusMessage = "Diagnostic procedural fallback (no AssetManager)",
        )
        return true
      }
      return false
    }

    val failure = decodeFailureRef.get()
    if (failure != null) {
      if (telemetry.loadState != OrbV3LoadState.V3_ASSET_ERROR) {
        release()
        telemetry = OrbVolumeTelemetry(
          loadState = OrbV3LoadState.V3_ASSET_ERROR,
          isLoaded = false,
          lifecycleState = CoreVolumeLifecycleState.CORE_VOLUME_ERROR,
          uploadGlStatus = "DECODE_FAILED",
          statusMessage = "V3_ASSET_ERROR: ${failure.message ?: failure.javaClass.simpleName}",
        )
        return true
      }
      return false
    }

    val decoded = decodedAssetsRef.get() ?: run {
      if (assetManager != null) preloadAsync(assetManager)
      return false
    }

    return try {
      if (contextLostFlag) {
        telemetry = telemetry.copy(
          lifecycleState = CoreVolumeLifecycleState.CORE_VOLUME_REUPLOADING,
          statusMessage = "Re-uploading 32 Z-slice GL_TEXTURE_3D after EGL context recreation...",
        )
      }
      release()
      uploadDecoded(decoded)
      contextLostFlag = false
      true
    } catch (t: Throwable) {
      Log.e(TAG, "V3_ASSET_ERROR: Failed to upload V3 WebP 3D Volume textures to GLES 3.0", t)
      release()
      decodeFailureRef.set(t)
      telemetry = OrbVolumeTelemetry(
        loadState = OrbV3LoadState.V3_ASSET_ERROR,
        isLoaded = false,
        lifecycleState = CoreVolumeLifecycleState.CORE_VOLUME_ERROR,
        uploadGlStatus = "UPLOAD_FAILED",
        uploadAttemptCount = uploadAttempts,
        statusMessage = "V3_ASSET_ERROR: ${t.message ?: t.javaClass.simpleName}",
      )
      true
    }
  }

  /**
   * Synchronous decode + GL upload entry point (preserved for deterministic tests and direct callers).
   *
   * When [assetManager] is provided (production runtime), any missing file, dimension mismatch,
   * decode failure, or GL upload failure transitions explicitly to [OrbV3LoadState.V3_ASSET_ERROR].
   */
  fun loadAndUpload(assetManager: AssetManager?): OrbVolumeTelemetry {
    release()

    if (assetManager == null) {
      telemetry = OrbVolumeTelemetry(
        loadState = OrbV3LoadState.DIAGNOSTIC_PROCEDURAL_FALLBACK,
        isLoaded = false,
        statusMessage = "Diagnostic procedural fallback (no AssetManager)",
      )
      return telemetry
    }

    return try {
      val decoded = decodedAssetsRef.get() ?: OrbWebpAssetLoader.loadAll(assetManager).also {
        decodedAssetsRef.set(it)
      }
      uploadDecoded(decoded)
    } catch (t: Throwable) {
      Log.e(TAG, "V3_ASSET_ERROR: Failed to load/upload V3 WebP 3D Volume textures", t)
      release()
      val glErr = GLES30.glGetError()
      telemetry = OrbVolumeTelemetry(
        loadState = OrbV3LoadState.V3_ASSET_ERROR,
        isLoaded = false,
        lifecycleState = CoreVolumeLifecycleState.CORE_VOLUME_ERROR,
        uploadGlStatus = if (glErr != GLES30.GL_NO_ERROR) "GL_ERROR_0x${glErr.toString(16)}" else "LOAD_OR_UPLOAD_FAILED",
        lastGlError = glErr,
        failedAssetPath = extractAssetPathHint(t.message),
        statusMessage = "V3_ASSET_ERROR: ${t.message ?: t.javaClass.simpleName}",
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

    val handles = IntArray(4)
    GLES30.glGenTextures(4, handles, 0)
    volumeTexId = handles[0]
    fluidDetailTexId = handles[1]
    causticsTexId = handles[2]
    glassEnvTexId = handles[3]

    // 1. Upload 256x256x32 GL_TEXTURE_3D (`GL_SRGB8_ALPHA8`, linear fallback tracked independently)
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

    // 2. Upload 2D Linear Data maps (`GL_RGBA8`) and Equirectangular Env (`GL_SRGB8_ALPHA8`),
    // tracking each texture's actual uploaded color space independently!
    val usedSrgbDetail = uploadTexture2D(
      texId = fluidDetailTexId,
      tex = decoded.emeraldDetailMap,
      wrapS = GLES30.GL_REPEAT,
      wrapT = GLES30.GL_REPEAT,
    )
    val usedSrgbCaustics = uploadTexture2D(
      texId = causticsTexId,
      tex = decoded.causticsMap,
      wrapS = GLES30.GL_REPEAT,
      wrapT = GLES30.GL_REPEAT,
    )
    val usedSrgbGlassEnv = uploadTexture2D(
      texId = glassEnvTexId,
      tex = decoded.glassEnvMap,
      wrapS = GLES30.GL_REPEAT,
      wrapT = GLES30.GL_CLAMP_TO_EDGE,
    )

    uploadAttempts += 1
    val uploadMs = (System.nanoTime() - uploadStart) * 1e-6f
    val postUploadErr = GLES30.glGetError()
    telemetry = OrbVolumeTelemetry(
      loadState = OrbV3LoadState.LOADED,
      isLoaded = true,
      volumeWidth = decoded.volumeWidth,
      volumeHeight = decoded.volumeHeight,
      volumeDepth = decoded.volumeDepth,
      decodeTimeMs = decoded.decodeTimeMs,
      uploadTimeMs = uploadMs,
      gpuMemoryBytes = decoded.totalGpuBytes,
      max3dTextureSize = max3d,
      isSrgbVolume = usedSrgbVolume,
      isSrgbFluidVolume = usedSrgbVolume,
      isSrgbGlassEnv = usedSrgbGlassEnv,
      isSrgbCaustics = usedSrgbCaustics,
      isSrgbFluidDetail = usedSrgbDetail,
      statusMessage = "WebP 3D Volume OK (${decoded.volumeWidth}x${decoded.volumeHeight}x${decoded.volumeDepth})",
      lifecycleState = CoreVolumeLifecycleState.CORE_VOLUME_READY,
      uploadGlError = postUploadErr,
      uploadGlStatus = if (postUploadErr == GLES30.GL_NO_ERROR) "GL_NO_ERROR" else "GL_ERROR_0x${postUploadErr.toString(16)}",
      uploadAttemptCount = uploadAttempts,
    )
    Log.i(
      TAG,
      "Uploaded V3 WebP Volume ${telemetry.dimensionsLabel} (${String.format("%.2f", telemetry.gpuMemoryMiB)} MiB) " +
        "sRGB=[vol=$usedSrgbVolume,env=$usedSrgbGlassEnv,cau=$usedSrgbCaustics,det=$usedSrgbDetail] " +
        "decode=${String.format("%.1f", telemetry.decodeTimeMs)}ms upload=${String.format("%.1f", telemetry.uploadTimeMs)}ms",
    )
    return telemetry
  }

  private fun uploadTexture2D(
    texId: Int,
    tex: DecodedTexture2D,
    wrapS: Int,
    wrapT: Int,
  ): Boolean {
    var usedSrgb = tex.isSrgb
    val requestedFormat = if (tex.isSrgb) GLES30.GL_SRGB8_ALPHA8 else GLES30.GL_RGBA8
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrapS)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrapT)
    tex.rgbaBuffer.rewind()
    GLES30.glTexImage2D(
      GLES30.GL_TEXTURE_2D,
      0,
      requestedFormat,
      tex.width,
      tex.height,
      0,
      GLES30.GL_RGBA,
      GLES30.GL_UNSIGNED_BYTE,
      tex.rgbaBuffer,
    )
    var err = GLES30.glGetError()
    if (err != GLES30.GL_NO_ERROR && tex.isSrgb) {
      Log.w(TAG, "GL_SRGB8_ALPHA8 upload failed for ${tex.id} (0x${err.toString(16)}); retrying with GL_RGBA8")
      usedSrgb = false
      tex.rgbaBuffer.rewind()
      GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D,
        0,
        GLES30.GL_RGBA8,
        tex.width,
        tex.height,
        0,
        GLES30.GL_RGBA,
        GLES30.GL_UNSIGNED_BYTE,
        tex.rgbaBuffer,
      )
      err = GLES30.glGetError()
    }
    check(err == GLES30.GL_NO_ERROR) {
      "glTexImage2D failed for ${tex.id} with GL error 0x${err.toString(16)}"
    }
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    return usedSrgb
  }

  fun bindTo(program: GlShaderProgram) {
    if (!isReady) {
      program.setInt("uVolumeLoaded", 0)
      program.setInt("uFluidVolumeIsSrgb", 1)
      program.setInt("uGlassEnvIsSrgb", 1)
      program.setInt("uCausticsIsSrgb", 0)
      program.setInt("uFluidDetailIsSrgb", 0)
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
    program.setInt("uFluidVolumeIsSrgb", if (telemetry.isSrgbFluidVolume) 1 else 0)
    program.setInt("uGlassEnvIsSrgb", if (telemetry.isSrgbGlassEnv) 1 else 0)
    program.setInt("uCausticsIsSrgb", if (telemetry.isSrgbCaustics) 1 else 0)
    program.setInt("uFluidDetailIsSrgb", if (telemetry.isSrgbFluidDetail) 1 else 0)
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

  private fun extractAssetPathHint(message: String?): String? {
    if (message.isNullOrBlank()) return null
    val match = Regex("""orb_v3/[A-Za-z0-9_./-]+""").find(message)
    return match?.value
  }

  companion object {
    private const val TAG = "OrbVolumeTexture3D"
  }
}
