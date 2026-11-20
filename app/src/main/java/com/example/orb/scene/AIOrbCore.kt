package com.example.orb.scene

import android.opengl.GLES30
import com.example.orb.config.AIOrbConfig
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram

/**
 * Renders the outer blown liquid-glass crystal sphere using the unified [AIOrbMotionSnapshot]:
 * - Exact analytical inverse outer-glass matrix (`invModelMatrix`)
 * - Unified tilted-axis 3D core rotation (`uCoreRotMat`) driving internal vortex advection & caustics
 * - Quality-adaptive bisected meniscus raymarch (`uRaymarchSteps`) with early ray termination
 * - Real FBO refraction, Cauchy RGB chromatic dispersion & double-wall blown glass Fresnel rim
 */
class AIOrbCore {
  private var program: GlShaderProgram? = null
  private var mesh: GlMesh? = null
  private var currentSubdivisions: Int = -1

  fun init(subdivisions: Int) {
    release()
    program = GlShaderProgram(
      CoreFluidShaders.CORE_GLASS_VERT,
      CoreFluidShaders.CORE_GLASS_FRAG,
      "AIOrbCore",
    )
    rebuildMesh(subdivisions)
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
  ) {
    val prog = program ?: return
    val sphere = mesh ?: return

    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    GLES30.glDepthMask(true)
    GLES30.glEnable(GLES30.GL_CULL_FACE)
    GLES30.glCullFace(GLES30.GL_BACK)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    prog.use()
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, refractionTexId)
    prog.setInt("uRefractionTex", 0)
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
    prog.setInt("uRaymarchSteps", config.quality.raymarchSteps)
    prog.setInt("uDebugMode", config.debugMode.shaderModeId)

    lighting.applyTo(prog)
    sphere.draw()

    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
  }

  fun release() {
    program?.release()
    program = null
    mesh?.release()
    mesh = null
    currentSubdivisions = -1
  }
}
