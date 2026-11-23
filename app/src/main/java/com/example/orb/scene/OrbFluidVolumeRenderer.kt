package com.example.orb.scene

import android.content.res.AssetManager
import android.opengl.GLES30
import com.example.orb.config.AIOrbConfig
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import com.example.orb.v3.MasterFidelityV32
import com.example.orb.v3.OrbVolumeTelemetry
import com.example.orb.v3.OrbVolumeTexture3D
import com.example.orb.v3.ReferenceMeniscusProfile

/**
 * AI-Orb-Demo V3 Unified 3D Fluid Volume + Crystal Glass Shell Renderer.
 *
 * Implements the WebP-First 3D Volume Hybrid Rendering architecture:
 * - Decodes Lossless RGBA WebP 3D volume slices (256x256x32) + optical/detail maps via [OrbVolumeTexture3D]
 * - Executes single-pass Front-to-Back Beer-Lambert Volume Raymarching (`sampler3D uFluidVolume`)
 * - Rotates the internal fluid volume independently in true 3D via `transpose(uCoreRotMat) * localPos`
 *   plus bounded 3D curl advection while the outer blown crystal glass shell stays optically steady
 * - Replaces V2's duplicate inner-bowl fluid pass so only ONE unified internal liquid volume exists
 */
class OrbFluidVolumeRenderer {
  private var program: GlShaderProgram? = null
  private var mesh: GlMesh? = null
  private var currentSubdivisions: Int = -1
  private val volumeTextures = OrbVolumeTexture3D()

  val volumeTelemetry: OrbVolumeTelemetry
    get() = volumeTextures.telemetry

  val isVolumeReady: Boolean
    get() = volumeTextures.isReady

  fun preloadAssetsAsync(assetManager: AssetManager?) {
    volumeTextures.preloadAsync(assetManager)
  }

  fun uploadReadyAssetsIfNeeded(assetManager: AssetManager?): Boolean {
    return volumeTextures.uploadIfReady(assetManager)
  }

  fun init(subdivisions: Int, assetManager: AssetManager? = null) {
    release()
    program = GlShaderProgram(
      CoreFluidShaders.CORE_GLASS_VERT,
      CoreFluidShaders.CORE_GLASS_FRAG,
      "OrbFluidVolumeRenderer",
    )
    rebuildMesh(subdivisions)
    // Non-blocking: kicks off background decode if not already started and uploads immediately if ready.
    volumeTextures.preloadAsync(assetManager)
    volumeTextures.uploadIfReady(assetManager)
  }

  fun ensureQuality(subdivisions: Int) {
    if (subdivisions != currentSubdivisions || mesh == null) {
      rebuildMesh(subdivisions)
    }
  }

  private fun rebuildMesh(subdivisions: Int) {
    mesh?.release()
    mesh = OrbGeometryBuilder.createIcosphere(subdivisions)
    currentSubdivisions = subdivisions
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
    val prog = program ?: return
    val sphere = mesh ?: return

    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    GLES30.glDepthMask(true)
    GLES30.glEnable(GLES30.GL_CULL_FACE)
    GLES30.glCullFace(GLES30.GL_BACK)
    // P0-3: CORE_GLASS_FRAG owns complete background refraction & edge coverage compositing
    // (`mix(originalBg, finalGlassCol, edgeAlpha)` with alpha = 1.0); disable duplicate GL_BLEND!
    GLES30.glDisable(GLES30.GL_BLEND)

    prog.use()
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, refractionTexId)
    prog.setInt("uRefractionTex", 0)

    volumeTextures.bindTo(prog)

    val effectiveSteps = if (isSoftwareRenderer) {
      minOf(config.quality.raymarchSteps, 20)
    } else {
      config.quality.raymarchSteps
    }

    prog.setVec2("uViewportSize", fboWidth.toFloat(), fboHeight.toFloat())
    prog.setMat4("uModel", outerGlassModel)
    prog.setMat4("uInvModel", invOuterGlassModel)
    prog.setMat3("uCoreRotMat", motion.coreRotationMat3)
    prog.setMat4("uView", viewMatrix)
    prog.setMat4("uProj", projMatrix)
    prog.setVec3("uCameraPos", cameraPos)
    prog.setFloat("uFluidPhase", motion.fluidPhase)
    prog.setFloat("uMeniscusPhase", motion.meniscusPhase)
    prog.setFloat("uCausticsPhase", motion.causticsPhase)
    prog.setFloat("uFluidIntensity", config.fluidIntensity)
    prog.setFloat("uDensityScale", config.densityScale)
    prog.setFloat("uCoreTransparency", config.coreTransparency)
    prog.setFloat("uRefraction", config.refraction)
    prog.setFloat("uOverallIntensity", config.overallIntensity)
    prog.setFloat("uHdrScale", hdrScale)
    prog.setInt("uRaymarchSteps", effectiveSteps)
    prog.setInt("uDebugMode", config.debugMode.shaderModeId)
    prog.setFloatArray("uReferenceMeniscusY", ReferenceMeniscusProfile.HEIGHTS)

    // V3.2 Master-Fitted Candidate B Crystal Lens + Active-Surface Fold Relief + Beer-Lambert Uniforms
    val cav = fidelity.innerCavity
    val vol = fidelity.fluidVolume
    val glass = fidelity.outerGlass
    val sap = fidelity.sapphireValleys
    val lgt = fidelity.lighting
    val cau = fidelity.caustics
    prog.setVec3("uLensCenter", cav.lensCenterX, cav.lensCenterY, cav.lensCenterZ)
    prog.setVec3("uLensRadii", cav.lensRadiusX, cav.lensRadiusY, cav.lensRadiusZ)
    prog.setVec4(
      "uLensParams",
      cav.lensWallThickness,
      cav.cavityTintGain,
      cav.cavityRimRefraction,
      cav.upperGoldPenetration,
    )
    prog.setVec4(
      "uFoldOptics",
      vol.foldNormalPerturbation,
      vol.foldSpecularGain,
      vol.foldRefraction,
      lgt.volumeExposure,
    )
    prog.setVec4(
      "uGlassOptics",
      glass.iorGlass,
      glass.crownGoldStrength,
      glass.upperHazeSuppress,
      cau.causticIntensity,
    )
    prog.setVec3("uBeerLambert", sap.beerLambertR, sap.beerLambertG, sap.beerLambertB)
    prog.setFloat("uTaskInteriorTransmission", taskInteriorTransmission)
    prog.setFloat("uTaskAbsorptionGain", taskAbsorptionGain)

    lighting.applyTo(prog)
    sphere.draw()

    volumeTextures.unbind()
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
  }

  fun release() {
    volumeTextures.release()
    program?.release()
    program = null
    mesh?.release()
    mesh = null
    currentSubdivisions = -1
  }
}
