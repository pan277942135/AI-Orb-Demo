package com.example.orb.scene

import android.opengl.GLES30
import android.opengl.Matrix
import com.example.orb.config.AIOrbConfig
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import kotlin.math.sin

/**
 * Renders the concave back inner liquid bowl of the crystal sphere (front faces culled!).
 * This establishes deep dark sapphire-teal optical depth, swirling emerald-jade interior
 * wall curtains, and backlit lower underwater caustics captured into the refraction FBO
 * without washing out the front hemisphere.
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
    baseModel: FloatArray,
    viewMatrix: FloatArray,
    projMatrix: FloatArray,
    cameraPos: FloatArray,
    time: Float,
    hdrScale: Float,
    config: AIOrbConfig,
  ) {
    val prog = program ?: return
    val sphere = mesh ?: return

    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
    GLES30.glDepthMask(false)
    // Cull FRONT faces so only the concave inner back wall of the crystal liquid bowl renders
    GLES30.glEnable(GLES30.GL_CULL_FACE)
    GLES30.glCullFace(GLES30.GL_FRONT)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    val fluidT = time * config.fluidSpeed
    System.arraycopy(baseModel, 0, bowlModel, 0, 16)
    Matrix.rotateM(bowlModel, 0, fluidT * 5.4f, 0f, 1f, 0f)
    Matrix.rotateM(bowlModel, 0, sin(fluidT * 0.24f) * 7.5f, 1f, 0f, 0f)
    Matrix.scaleM(bowlModel, 0, 0.945f, 0.945f, 0.945f)

    prog.use()
    prog.setMat4("uModel", bowlModel)
    prog.setMat4("uView", viewMatrix)
    prog.setMat4("uProj", projMatrix)
    prog.setVec3("uCameraPos", cameraPos)
    prog.setFloat("uTime", time)
    prog.setFloat("uFluidSpeed", config.fluidSpeed)
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
