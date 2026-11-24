package com.example.orb.scene

import android.opengl.GLES30
import com.example.orb.config.AIOrbConfig
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import com.example.orb.outer.OuterOpticalBokeh

/**
 * Renders the independent deep emerald-teal space backdrop with atmospheric
 * depth gradients and sparse, soft warm olive-gold & emerald-teal background bokeh.
 */
class AIOrbBackground {
  private var program: GlShaderProgram? = null
  private var quad: GlMesh? = null

  fun init() {
    release()
    program = GlShaderProgram(
      CoreFluidShaders.BACKGROUND_VERT,
      CoreFluidShaders.BACKGROUND_FRAG,
      "AIOrbBackground",
    )
    quad = OrbGeometryBuilder.createFullscreenQuad()
  }

  fun draw(
    time: Float,
    width: Int,
    height: Int,
    parallaxX: Float,
    parallaxY: Float,
    hdrScale: Float,
    config: AIOrbConfig,
    referenceBokeh: List<OuterOpticalBokeh>? = null,
  ) {
    val prog = program ?: return
    val mesh = quad ?: return

    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_BLEND)

    prog.use()
    prog.setFloat("uTime", time)
    prog.setVec2("uResolution", width.toFloat(), height.toFloat())
    prog.setVec2("uParallax", parallaxX, parallaxY)
    prog.setFloat("uOverallIntensity", config.overallIntensity)
    prog.setFloat("uHdrScale", hdrScale)
    prog.setInt("uUseReferenceBokeh", if (referenceBokeh == null) 0 else 1)
    if (!referenceBokeh.isNullOrEmpty()) {
      val params = FloatArray(12 * 4)
      val colors = FloatArray(12 * 3)
      referenceBokeh.take(12).forEachIndexed { index, bokeh ->
        val p = index * 4
        params[p] = bokeh.x
        params[p + 1] = bokeh.y
        params[p + 2] = bokeh.radius
        params[p + 3] = bokeh.intensity
        val c = index * 3
        colors[c] = bokeh.color[0]
        colors[c + 1] = bokeh.color[1]
        colors[c + 2] = bokeh.color[2]
      }
      prog.setVec4Array("uReferenceBokeh[0]", params)
      prog.setVec3Array("uReferenceBokehColor[0]", colors)
    }

    mesh.draw()
    GLES30.glDepthMask(true)
  }

  fun release() {
    program?.release()
    program = null
    quad?.release()
    quad = null
  }
}
