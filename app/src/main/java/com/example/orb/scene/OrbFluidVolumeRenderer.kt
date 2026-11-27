package com.example.orb.scene

import android.content.res.AssetManager
import android.opengl.GLES30
import com.example.orb.config.AIOrbConfig
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import com.example.orb.v3.CoreVolumeLifecycleState
import com.example.orb.v3.MasterFidelityV32
import com.example.orb.v3.OrbVolumeTelemetry
import com.example.orb.v3.OrbVolumeTexture3D
import com.example.orb.v3.ReferenceMeniscusProfile
import org.json.JSONObject
import kotlin.math.abs

/**
 * AI-Orb-Demo V3 Unified 3D Fluid Volume + Crystal Glass Shell Renderer.
 *
 * Implements the WebP-First 3D Volume Hybrid Rendering architecture:
 * - Decodes Lossless RGBA WebP 3D volume slices (256x256x32) + optical/detail maps via [OrbVolumeTexture3D]
 * - Executes single-pass Front-to-Back Beer-Lambert Volume Raymarching (`sampler3D uFluidVolume`)
 * - Rotates the internal fluid volume independently in true 3D via `transpose(uCoreRotMat) * localPos`
 *   plus bounded 3D curl advection while the outer blown crystal glass shell stays optically steady
 * - Work 3.1: Supports explicit production `uCoreRenderMode = 1` (`CORE_VOLUME_ONLY`) via
 *   [drawVolumeOnly] so Water World Tab 3 renders ONLY the real 3D Volume inside `04_WATER_INTERIOR`
 *   with ZERO duplicate glass shell, ZERO duplicate Bloom, and ZERO Tab 1/Tab 2 regression.
 */
class OrbFluidVolumeRenderer {

  enum class CoreRenderMode(val shaderId: Int) {
    FULL_ORB(0),
    CORE_VOLUME_ONLY(1),
  }

  enum class CoreDiagMode(val shaderId: Int) {
    PRODUCTION_COMPOSITE(0),
    CORE_VOLUME_ONLY_ISOLATED(1),
    CORE_DENSITY(2),
    CORE_FLOW(3),
    CORE_CAUSTICS(4),
    CORE_OPTICAL_DEPTH(5),
    CORE_ROTATION(6),
  }

  data class CoreGpuInspection(
    val core3DVolumeEnabled: Boolean,
    val coreRenderMode: String,
    val coreDiagMode: String,
    val lifecycleState: CoreVolumeLifecycleState,
    val programId: Int,
    val programLinkedOk: Boolean,
    val volumeTextureId: Int,
    val volumeSamplerUnit: Int,
    val sampler3DUniformLocation: Int,
    val coreRotMatLocation: Int,
    val fluidPhaseLocation: Int,
    val causticsPhaseLocation: Int,
    val densityScaleLocation: Int,
    val coreRenderModeLocation: Int,
    val coreColorShiftLocation: Int,
    val volumeWidth: Int,
    val volumeHeight: Int,
    val volumeDepth: Int,
    val max3dTextureSize: Int,
    val decodeTimeMs: Float,
    val uploadTimeMs: Float,
    val gpuMemoryBytes: Long,
    val uploadGlStatus: String,
    val raymarchSteps: Int,
    val rotationAngleDeg: Float,
    val rotationMatDet: Float,
    val rotationMatOrthoError: Float,
    val fluidPhase: Float,
    val causticsPhase: Float,
    val densityScale: Float,
    val colorShift: Float,
    val statusMessage: String,
  ) {
    fun toJson(): JSONObject = JSONObject()
      .put("core3DVolumeEnabled", core3DVolumeEnabled)
      .put("coreRenderMode", coreRenderMode)
      .put("coreDiagMode", coreDiagMode)
      .put("coreVolumeLifecycleState", lifecycleState.name)
      .put("programId", programId)
      .put("programLinkedOk", programLinkedOk)
      .put("volumeTextureId", volumeTextureId)
      .put("volumeSamplerUnit", volumeSamplerUnit)
      .put("sampler3DUniformLocation", sampler3DUniformLocation)
      .put("coreRotMatLocation", coreRotMatLocation)
      .put("fluidPhaseLocation", fluidPhaseLocation)
      .put("causticsPhaseLocation", causticsPhaseLocation)
      .put("densityScaleLocation", densityScaleLocation)
      .put("coreRenderModeLocation", coreRenderModeLocation)
      .put("coreColorShiftLocation", coreColorShiftLocation)
      .put("volumeWidth", volumeWidth)
      .put("volumeHeight", volumeHeight)
      .put("volumeDepth", volumeDepth)
      .put("max3dTextureSize", max3dTextureSize)
      .put("decodeTimeMs", decodeTimeMs.toDouble())
      .put("uploadTimeMs", uploadTimeMs.toDouble())
      .put("gpuMemoryBytes", gpuMemoryBytes)
      .put("gpuMemoryMb", gpuMemoryBytes / (1024.0 * 1024.0))
      .put("uploadGlStatus", uploadGlStatus)
      .put("raymarchSteps", raymarchSteps)
      .put("rotationAngleDeg", rotationAngleDeg.toDouble())
      .put("rotationMatDet", rotationMatDet.toDouble())
      .put("rotationMatOrthoError", rotationMatOrthoError.toDouble())
      .put("fluidPhase", fluidPhase.toDouble())
      .put("causticsPhase", causticsPhase.toDouble())
      .put("densityScale", densityScale.toDouble())
      .put("colorShift", colorShift.toDouble())
      .put("statusMessage", statusMessage)
  }

  private var program: GlShaderProgram? = null
  private var mesh: GlMesh? = null
  private var currentSubdivisions: Int = -1
  private val volumeTextures = OrbVolumeTexture3D()
  @Volatile private var lastInspection: CoreGpuInspection? = null

  val volumeTelemetry: OrbVolumeTelemetry
    get() = volumeTextures.telemetry

  val lifecycleState: CoreVolumeLifecycleState
    get() = volumeTextures.lifecycleState

  val isVolumeReady: Boolean
    get() = volumeTextures.isReady

  val lastGpuInspection: CoreGpuInspection?
    get() = lastInspection

  fun preloadAssetsAsync(assetManager: AssetManager?) {
    volumeTextures.preloadAsync(assetManager)
  }

  fun uploadReadyAssetsIfNeeded(assetManager: AssetManager?): Boolean {
    return volumeTextures.uploadIfReady(assetManager)
  }

  fun handleContextLost() {
    volumeTextures.markContextLost()
    program = null
    mesh = null
    currentSubdivisions = -1
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

  /**
   * Tab 1 / Tab 2 production entry point (`uCoreRenderMode = 0` FULL_ORB).
   * Preserved 100% behaviorally identical for zero Tab 1 / Tab 2 regression.
   */
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

    prog.setInt("uCoreRenderMode", CoreRenderMode.FULL_ORB.shaderId)
    prog.setInt("uCoreDiagMode", CoreDiagMode.PRODUCTION_COMPOSITE.shaderId)
    prog.setFloat("uCoreColorShift", 0f)
    prog.setFloat("uDensityScale", config.densityScale)
    applyCommonUniforms(
      prog = prog,
      outerGlassModel = outerGlassModel,
      invOuterGlassModel = invOuterGlassModel,
      viewMatrix = viewMatrix,
      projMatrix = projMatrix,
      cameraPos = cameraPos,
      fboWidth = fboWidth,
      fboHeight = fboHeight,
      motion = motion,
      hdrScale = hdrScale,
      lighting = lighting,
      fluidIntensity = config.fluidIntensity,
      densityScale = config.densityScale,
      coreTransparency = config.coreTransparency,
      refraction = config.refraction,
      overallIntensity = config.overallIntensity,
      raymarchSteps = effectiveSteps,
      debugModeId = config.debugMode.shaderModeId,
      fidelity = fidelity,
      taskInteriorTransmission = taskInteriorTransmission,
      taskAbsorptionGain = taskAbsorptionGain,
    )

    sphere.draw()

    volumeTextures.unbind()
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
  }

  /**
   * Work 3.1 production entry point for Water World Tab 3 (`uCoreRenderMode = 1` CORE_VOLUME_ONLY).
   *
   * Renders ONLY the real 32 Z-slice `GL_TEXTURE_3D` internal fluid volume + 3D curl advection +
   * 3D rotation (`transpose(uCoreRotMat) * localPos`) + subtle 3D caustics directly into the active
   * Water World FBO (`04_WATER_INTERIOR` or `pool.diagnostic`), with ZERO outer glass shell,
   * ZERO background overwrite, and ZERO duplicate Bloom.
   *
   * Strictly refuses to mask `CORE_VOLUME_ERROR` with fake 2D fallback: throws if volume asset load failed.
   */
  fun drawVolumeOnly(
    modelMatrix: FloatArray,
    invModelMatrix: FloatArray,
    viewMatrix: FloatArray,
    projMatrix: FloatArray,
    cameraPos: FloatArray,
    refractionTexId: Int,
    fboWidth: Int,
    fboHeight: Int,
    motion: AIOrbMotionSnapshot,
    densityScale: Float,
    colorShift: Float,
    diagMode: CoreDiagMode = CoreDiagMode.PRODUCTION_COMPOSITE,
    lighting: AIOrbLighting = AIOrbLighting(),
    fidelity: MasterFidelityV32 = MasterFidelityV32.Default,
    raymarchSteps: Int = 28,
    fluidIntensity: Float = 0.88f,
    coreTransparency: Float = 0.42f,
    overallIntensity: Float = 0.92f,
    hdrScale: Float = 1.0f,
  ): CoreGpuInspection {
    val prog = checkNotNull(program) { "CORE_VOLUME_ERROR: OrbFluidVolumeRenderer shader program not initialized" }
    val sphere = checkNotNull(mesh) { "CORE_VOLUME_ERROR: OrbFluidVolumeRenderer sphere mesh not initialized" }
    val volTel = volumeTextures.telemetry
    if (volTel.hasError) {
      throw IllegalStateException("CORE_VOLUME_ERROR: ${volTel.statusMessage}")
    }
    check(volumeTextures.isReady) {
      "CORE_VOLUME_ERROR: 32 Z-slice GL_TEXTURE_3D not ready (state=${volTel.lifecycleState})"
    }

    // Preserve Water World's depth buffer untouched; ray-sphere entry is driven by front faces.
    // CORE_GLASS_FRAG composites premultiplied volume radiance directly over `originalBg` (`uRefractionTex`),
    // keeping `GL_BLEND` disabled for zero duplicate blending and exact opaque FBO alpha = 1.0.
    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glEnable(GLES30.GL_CULL_FACE)
    GLES30.glCullFace(GLES30.GL_BACK)
    GLES30.glDisable(GLES30.GL_BLEND)

    prog.use()
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, refractionTexId)
    prog.setInt("uRefractionTex", 0)

    volumeTextures.bindTo(prog)

    prog.setInt("uCoreRenderMode", CoreRenderMode.CORE_VOLUME_ONLY.shaderId)
    prog.setInt("uCoreDiagMode", diagMode.shaderId)
    prog.setFloat("uCoreColorShift", colorShift)

    applyCommonUniforms(
      prog = prog,
      outerGlassModel = modelMatrix,
      invOuterGlassModel = invModelMatrix,
      viewMatrix = viewMatrix,
      projMatrix = projMatrix,
      cameraPos = cameraPos,
      fboWidth = fboWidth,
      fboHeight = fboHeight,
      motion = motion,
      hdrScale = hdrScale,
      lighting = lighting,
      fluidIntensity = fluidIntensity,
      densityScale = densityScale,
      coreTransparency = coreTransparency,
      refraction = 0.08f,
      overallIntensity = overallIntensity,
      raymarchSteps = raymarchSteps.coerceIn(16, 40),
      debugModeId = 0,
      fidelity = fidelity,
      taskInteriorTransmission = 1.0f,
      taskAbsorptionGain = 1.0f,
    )

    sphere.draw()

    volumeTextures.unbind()
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

    val inspection = buildInspection(
      prog = prog,
      volTel = volTel,
      diagMode = diagMode,
      raymarchSteps = raymarchSteps.coerceIn(16, 40),
      motion = motion,
      densityScale = densityScale,
      colorShift = colorShift,
    )
    lastInspection = inspection
    return inspection
  }

  private fun applyCommonUniforms(
    prog: GlShaderProgram,
    outerGlassModel: FloatArray,
    invOuterGlassModel: FloatArray,
    viewMatrix: FloatArray,
    projMatrix: FloatArray,
    cameraPos: FloatArray,
    fboWidth: Int,
    fboHeight: Int,
    motion: AIOrbMotionSnapshot,
    hdrScale: Float,
    lighting: AIOrbLighting,
    fluidIntensity: Float,
    densityScale: Float,
    coreTransparency: Float,
    refraction: Float,
    overallIntensity: Float,
    raymarchSteps: Int,
    debugModeId: Int,
    fidelity: MasterFidelityV32,
    taskInteriorTransmission: Float,
    taskAbsorptionGain: Float,
  ) {
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
    prog.setFloat("uFluidIntensity", fluidIntensity)
    prog.setFloat("uDensityScale", densityScale)
    prog.setFloat("uCoreTransparency", coreTransparency)
    prog.setFloat("uRefraction", refraction)
    prog.setFloat("uOverallIntensity", overallIntensity)
    prog.setFloat("uHdrScale", hdrScale)
    prog.setInt("uRaymarchSteps", raymarchSteps)
    prog.setInt("uDebugMode", debugModeId)
    prog.setFloatArray("uReferenceMeniscusY", ReferenceMeniscusProfile.HEIGHTS)

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
  }

  private fun buildInspection(
    prog: GlShaderProgram,
    volTel: OrbVolumeTelemetry,
    diagMode: CoreDiagMode,
    raymarchSteps: Int,
    motion: AIOrbMotionSnapshot,
    densityScale: Float,
    colorShift: Float,
  ): CoreGpuInspection {
    val m = motion.coreRotationMat3
    val det = computeMat3Det(m)
    val orthoErr = computeMat3OrthoError(m)
    return CoreGpuInspection(
      core3DVolumeEnabled = true,
      coreRenderMode = CoreRenderMode.CORE_VOLUME_ONLY.name,
      coreDiagMode = diagMode.name,
      lifecycleState = volTel.lifecycleState,
      programId = prog.programId,
      programLinkedOk = prog.programId != 0,
      volumeTextureId = volumeTextures.volumeTexId,
      volumeSamplerUnit = 1,
      sampler3DUniformLocation = prog.uniformLocation("uFluidVolume"),
      coreRotMatLocation = prog.uniformLocation("uCoreRotMat"),
      fluidPhaseLocation = prog.uniformLocation("uFluidPhase"),
      causticsPhaseLocation = prog.uniformLocation("uCausticsPhase"),
      densityScaleLocation = prog.uniformLocation("uDensityScale"),
      coreRenderModeLocation = prog.uniformLocation("uCoreRenderMode"),
      coreColorShiftLocation = prog.uniformLocation("uCoreColorShift"),
      volumeWidth = volTel.volumeWidth,
      volumeHeight = volTel.volumeHeight,
      volumeDepth = volTel.volumeDepth,
      max3dTextureSize = volTel.max3dTextureSize,
      decodeTimeMs = volTel.decodeTimeMs,
      uploadTimeMs = volTel.uploadTimeMs,
      gpuMemoryBytes = volTel.gpuMemoryBytes,
      uploadGlStatus = volTel.uploadGlStatus,
      raymarchSteps = raymarchSteps,
      rotationAngleDeg = motion.coreRotationAngleDeg,
      rotationMatDet = det,
      rotationMatOrthoError = orthoErr,
      fluidPhase = motion.fluidPhase,
      causticsPhase = motion.causticsPhase,
      densityScale = densityScale,
      colorShift = colorShift,
      statusMessage = volTel.statusMessage,
    )
  }

  fun release() {
    volumeTextures.release()
    program?.release()
    program = null
    mesh?.release()
    mesh = null
    currentSubdivisions = -1
    lastInspection = null
  }

  companion object {
    fun computeMat3Det(m: FloatArray): Float {
      if (m.size < 9) return 0f
      // Column-major: col0=(m0,m1,m2), col1=(m3,m4,m5), col2=(m6,m7,m8)
      return m[0] * (m[4] * m[8] - m[5] * m[7]) -
        m[3] * (m[1] * m[8] - m[2] * m[7]) +
        m[6] * (m[1] * m[5] - m[2] * m[4])
    }

    fun computeMat3OrthoError(m: FloatArray): Float {
      if (m.size < 9) return Float.POSITIVE_INFINITY
      var maxErr = 0f
      for (r in 0..2) {
        for (c in 0..2) {
          var dot = 0f
          for (k in 0..2) {
            dot += m[r * 3 + k] * m[c * 3 + k]
          }
          val expected = if (r == c) 1f else 0f
          val err = abs(dot - expected)
          if (err > maxErr) maxErr = err
        }
      }
      return maxErr
    }
  }
}

