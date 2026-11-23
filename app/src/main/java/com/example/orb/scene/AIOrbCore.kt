package com.example.orb.scene

import android.content.res.AssetManager
import com.example.orb.config.AIOrbConfig
import com.example.orb.v3.MasterFidelityV32
import com.example.orb.v3.OrbVolumeTelemetry

/**
 * V3 Core Shell + 3D Liquid Volume entry point.
 * Delegates directly to [OrbFluidVolumeRenderer] so all WebP 3D Volume (`GL_TEXTURE_3D`)
 * raymarching, independent Core Rotation, and double-wall crystal glass shell rendering
 * execute in one unified pass with zero duplicate fluid geometry.
 */
class AIOrbCore {
  private val volumeRenderer = OrbFluidVolumeRenderer()

  val volumeTelemetry: OrbVolumeTelemetry
    get() = volumeRenderer.volumeTelemetry

  val isVolumeReady: Boolean
    get() = volumeRenderer.isVolumeReady

  fun preloadAssetsAsync(assetManager: AssetManager?) {
    volumeRenderer.preloadAssetsAsync(assetManager)
  }

  fun uploadReadyAssetsIfNeeded(assetManager: AssetManager?): Boolean {
    return volumeRenderer.uploadReadyAssetsIfNeeded(assetManager)
  }

  fun init(subdivisions: Int, assetManager: AssetManager? = null) {
    volumeRenderer.init(subdivisions, assetManager)
  }

  fun ensureQuality(subdivisions: Int) {
    volumeRenderer.ensureQuality(subdivisions)
  }

  fun draw(
    outerGlassModel: FloatArray,
    invOuterGlassModel: FloatArray,
    viewMatrix: FloatArray,
    projMatrix: FloatArray,
    cameraPos: FloatArray,
    refractionTexId: Int,
    fboWidth: Int,
    fboHeight: Int,
    motion: AIOrbMotionSnapshot,
    hdrScale: Float,
    lighting: AIOrbLighting,
    config: AIOrbConfig,
    isSoftwareRenderer: Boolean = false,
    fidelity: MasterFidelityV32 = MasterFidelityV32.Default,
    taskInteriorTransmission: Float = 1.0f,
    taskAbsorptionGain: Float = 1.0f,
  ) {
    volumeRenderer.draw(
      outerGlassModel = outerGlassModel,
      invOuterGlassModel = invOuterGlassModel,
      viewMatrix = viewMatrix,
      projMatrix = projMatrix,
      cameraPos = cameraPos,
      refractionTexId = refractionTexId,
      fboWidth = fboWidth,
      fboHeight = fboHeight,
      motion = motion,
      hdrScale = hdrScale,
      lighting = lighting,
      config = config,
      isSoftwareRenderer = isSoftwareRenderer,
      fidelity = fidelity,
      taskInteriorTransmission = taskInteriorTransmission,
      taskAbsorptionGain = taskAbsorptionGain,
    )
  }

  fun release() {
    volumeRenderer.release()
  }
}
