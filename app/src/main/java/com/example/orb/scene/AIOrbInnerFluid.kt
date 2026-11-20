package com.example.orb.scene

import android.opengl.GLES30
import android.opengl.Matrix
import com.example.orb.config.AIOrbConfig
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram

/**
 * Renders the concave inner back-wall liquid bowl (front faces culled) locked strictly
 * to the unified [AIOrbMotionSnapshot] coordinate frame (`outerGlassModelMatrix` + `coreRotationMat3`).
 * Eliminates independent bowl-vs-core rotation drift so back-wall emerald folds, meniscus,
 * and volumetric underwater caustics move in 100% spatial lockstep.
 */
class AIOrbInnerFluid {
  private var program: GlShaderProgram? = null
  private var mesh: GlMesh? = null
  private var currentSubdivisions: Int = -1
  private val bowlModel = FloatArray(16)

  fun init(subdivisions: Int) {
    release()
    program = GlShaderProgram(
      CoreFluidShaders.INNER_FLUID_VERT,
      CoreFluidShaders.INNER_FLUID_FRAG,
      "AIOrbInnerFluid",
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
    viewMatrix: FloatArray,
    projMatrix: FloatArray,
    motion: AIOrbMotionSnapshot,
    hdrScale: Float,
    config: AIOrbConfig,
  ) {
    val prog = program ?: return
    val sphere = mesh ?: return

    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    GLES30.glDepthMask(false)
    GLES30.glEnable(GLES30.GL_CULL_FACE)
    GLES30.glCullFace(GLES30.GL_FRONT)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    // Locked directly to outerGlassModel (no conflicting ad-hoc rotation!)
    System.arraycopy(outerGlassModel, 0, bowlModel, 0, 16)
    Matrix.scaleM(bowlModel, 0, 0.948f, 0.948f, 0.948f)

    prog.use()
    prog.setMat4("uModel", bowlModel)
    prog.setMat4("uView", viewMatrix)
    prog.setMat4("uProj", projMatrix)
    prog.setMat3("uCoreRotMat", motion.coreRotationMat3)
    prog.setFloat("uFluidPhase", motion.fluidPhase)
    prog.setFloat("uMeniscusPhase", motion.meniscusPhase)
    prog.setFloat("uCausticsPhase", motion.causticsPhase)
    prog.setFloat("uFluidIntensity", config.fluidIntensity)
    prog.setFloat("uCoreTransparency", config.coreTransparency)
    prog.setFloat("uOverallIntensity", config.overallIntensity)
    prog.setFloat("uHdrScale", hdrScale)

    sphere.draw()

    GLES30.glCullFace(GLES30.GL_BACK)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glDepthMask(true)
  }

  fun release() {
    program?.release()
    program = null
    mesh?.release()
    mesh = null
    currentSubdivisions = -1
  }
}
