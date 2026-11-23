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

data class OrbCoreRuntimeShaderState(
  val programId: Int = 0,
  val shaderSourceSha256: String = "UNAVAILABLE",
  val qualityTier: String = "UNKNOWN",
  val isSoftwareRenderer: Boolean = false,
  val configuredRaymarchSteps: Int = -1,
  val effectiveRaymarchSteps: Int = -1,
  val currentSubdivisions: Int = -1,
  val geometryStateValid: Boolean = false,
  val uVolumeLoaded: Int = -1,
  val uDebugMode: Int = -1,
  val gpuTextureObjectsValid: Boolean = false,
  val volumeTextureId: Int = 0,
  val detailTextureId: Int = 0,
  val causticsTextureId: Int = 0,
  val glassEnvTextureId: Int = 0,
  val volumeInternalFormat: String = "UNAVAILABLE",
  val volumeIsSrgb: Boolean = false,
  val raymarchSteps: Int = -1,
  val densityScale: Float = Float.NaN,
  val coreTransparency: Float = Float.NaN,
  val uniformStateValid: Boolean = false,
  val foldActiveRatio: Float? = null,
  val foldActiveRatioStatus: String = "NOT_MEASURED",
  val renderResolution: String = "0x0",
  val geometrySubdivisions: Int = -1,
  val geometryVertexCount: Int = 0,
  val geometryTriangleCount: Int = 0,
  val geometryFingerprint: String = "UNAVAILABLE",
  val glError: String = "GL_NO_ERROR",
  val glErrorPass: String = "NONE",
)

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
  @Volatile private var lastRuntimeShaderState: OrbCoreRuntimeShaderState = OrbCoreRuntimeShaderState()

  val runtimeShaderState: OrbCoreRuntimeShaderState get() = lastRuntimeShaderState

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
    while (GLES30.glGetError() != GLES30.GL_NO_ERROR) { /* isolate errors to the core pass */ }

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
    val effectiveDensity = (vol.densityScale * config.densityScaleMultiplier.coerceIn(
      AIOrbConfig.MIN_DENSITY_SCALE_MULTIPLIER,
      AIOrbConfig.MAX_DENSITY_SCALE_MULTIPLIER,
    )).coerceIn(vol.densityScaleMin, vol.densityScaleMax)
    prog.setFloat("uDensityScale", effectiveDensity)
    prog.setVec2("uDensityRange", vol.densityScaleMin, vol.densityScaleMax)
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
    prog.setVec4(
      "uGlassStructure",
      glass.glassWallThickness,
      glass.fresnelPower,
      glass.fresnelGain,
      glass.razorRimGain,
    )
    prog.setVec3("uGlassRim", glass.innerRingGain, glass.grooveDarkening, glass.envReflectionCap)
    prog.setVec3("uInterfaceIor", cav.iorAir, cav.iorWater, cav.iorCrystal)
    prog.setVec3("uBeerLambert", sap.beerLambertR, sap.beerLambertG, sap.beerLambertB)
    prog.setFloat("uTaskInteriorTransmission", taskInteriorTransmission)
    prog.setFloat("uTaskAbsorptionGain", taskAbsorptionGain)

    lighting.applyTo(prog)
    sphere.draw()
    volumeTextures.unbind()
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glDisable(GLES30.GL_CULL_FACE)

    val actualVolumeLoaded = prog.getIntUniform("uVolumeLoaded") ?: -1
    val actualSteps = prog.getIntUniform("uRaymarchSteps") ?: -1
    val actualDebugMode = prog.getIntUniform("uDebugMode") ?: -1
    val actualDensity = prog.getFloatUniform("uDensityScale") ?: Float.NaN
    val actualTransparency = prog.getFloatUniform("uCoreTransparency") ?: Float.NaN
    val expectedVolumeLoaded = if (volumeTextures.isReady) 1 else 0
    val geometryStateValid = sphere.subdivisionLevel == currentSubdivisions &&
      sphere.vertexCount > 0 && sphere.triangleCount > 0 && sphere.geometryFingerprint.matches(Regex("[0-9a-f]{64}"))
    val uniformStateValid = actualVolumeLoaded == expectedVolumeLoaded &&
      actualSteps == effectiveSteps &&
      actualDebugMode == config.debugMode.shaderModeId &&
      actualDensity.isFinite() && kotlin.math.abs(actualDensity - effectiveDensity) <= 1e-5f &&
      actualTransparency.isFinite() && kotlin.math.abs(actualTransparency - config.coreTransparency) <= 1e-5f
    val errors = ArrayList<Int>(2)
    var glError = GLES30.glGetError()
    while (glError != GLES30.GL_NO_ERROR) {
      errors += glError
      glError = GLES30.glGetError()
    }

    lastRuntimeShaderState = OrbCoreRuntimeShaderState(
      programId = prog.programId,
      shaderSourceSha256 = CORE_SHADER_SOURCE_SHA256,
      qualityTier = config.quality.name,
      isSoftwareRenderer = isSoftwareRenderer,
      configuredRaymarchSteps = config.quality.raymarchSteps,
      effectiveRaymarchSteps = actualSteps,
      currentSubdivisions = currentSubdivisions,
      geometryStateValid = geometryStateValid,
      uVolumeLoaded = actualVolumeLoaded,
      uDebugMode = actualDebugMode,
      gpuTextureObjectsValid = volumeTextures.telemetry.gpuTextureObjectsValid && volumeTextures.isReady,
      volumeTextureId = volumeTextures.volumeTexId,
      detailTextureId = volumeTextures.fluidDetailTexId,
      causticsTextureId = volumeTextures.causticsTexId,
      glassEnvTextureId = volumeTextures.glassEnvTexId,
      volumeInternalFormat = if (!volumeTextures.isReady) "UNAVAILABLE" else if (volumeTextures.telemetry.isSrgbFluidVolume) "GL_SRGB8_ALPHA8" else "GL_RGBA8",
      volumeIsSrgb = volumeTextures.isReady && volumeTextures.telemetry.isSrgbFluidVolume,
      raymarchSteps = actualSteps,
      densityScale = actualDensity,
      coreTransparency = actualTransparency,
      uniformStateValid = uniformStateValid,
      renderResolution = "${fboWidth}x${fboHeight}",
      geometrySubdivisions = sphere.subdivisionLevel,
      geometryVertexCount = sphere.vertexCount,
      geometryTriangleCount = sphere.triangleCount,
      geometryFingerprint = sphere.geometryFingerprint,
      glError = if (errors.isEmpty()) "GL_NO_ERROR" else errors.joinToString(",") { "0x${it.toString(16)}" },
      glErrorPass = if (errors.isEmpty()) "NONE" else "PASS_6_CORE_VOLUME_GLASS",
    )

  }

  fun release() {
    volumeTextures.release()
    program?.release()
    program = null
    mesh?.release()
    mesh = null
    currentSubdivisions = -1
    lastRuntimeShaderState = OrbCoreRuntimeShaderState()
  }

  fun markGlContextLost() {
    volumeTextures.markGlContextLost()
  }

  companion object {
    private val CORE_SHADER_SOURCE_SHA256 = MasterFidelityV32.sha256Hex(
      (CoreFluidShaders.CORE_GLASS_VERT + "\n" + CoreFluidShaders.CORE_GLASS_FRAG).toByteArray(Charsets.UTF_8),
    )
  }
}
