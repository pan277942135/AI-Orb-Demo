package com.example.orb.scene

import android.opengl.GLES30
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.QualityTier
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.GlBufferUtils
import com.example.orb.gl.GlFramebuffer
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import com.example.orb.gl.RibbonOpticsShaders

/**
 * Multi-pass HDR optical post-processing pipeline with automatic RGBA8 linear HDR packing:
 * - On RGBA16F GPUs: hdrScale = 1.0f (native float HDR)
 * - On RGBA8 fallback GPUs: hdrScale = 0.25f (packs 0.0..4.0 linear HDR cleanly with exact linear blending!)
 * - High-threshold Bloom (threshold = 1.12 linear HDR) so ONLY starbursts, caustic peaks & bright speculars bloom,
 *   keeping the crystal glass shell and ribbon edges razor-sharp.
 */
class AIOrbPostProcessing {
  private var extractProgram: GlShaderProgram? = null
  private var blurProgram: GlShaderProgram? = null
  private var compositeProgram: GlShaderProgram? = null
  private var quad: GlMesh? = null

  private var sceneFbo: GlFramebuffer? = null
  private var refractionFbo: GlFramebuffer? = null
  private var bloomPingFbo: GlFramebuffer? = null
  private var bloomPongFbo: GlFramebuffer? = null

  private var surfaceWidth: Int = 0
  private var surfaceHeight: Int = 0
  private var currentQuality: QualityTier = QualityTier.HIGH

  val renderWidth: Int get() = sceneFbo?.width ?: surfaceWidth.coerceAtLeast(2)
  val renderHeight: Int get() = sceneFbo?.height ?: surfaceHeight.coerceAtLeast(2)
  val refractionTextureId: Int get() = refractionFbo?.colorTexId ?: 0
  var isSceneFboComplete: Boolean = false
    private set

  /**
   * Linear HDR encoding scale applied by all scene fragment shaders.
   * Guarantees zero 8-bit clipping on RGBA8 fallback GPUs while preserving exact GL_BLEND linearity.
   */
  val hdrScale: Float
    get() = if (sceneFbo?.isHdr == true) 1.0f else 0.25f

  fun init() {
    release()
    extractProgram = GlShaderProgram(
      RibbonOpticsShaders.POST_QUAD_VERT,
      RibbonOpticsShaders.BLOOM_EXTRACT_FRAG,
      "BloomExtract",
    )
    blurProgram = GlShaderProgram(
      RibbonOpticsShaders.POST_QUAD_VERT,
      RibbonOpticsShaders.BLOOM_BLUR_FRAG,
      "BloomBlur",
    )
    compositeProgram = GlShaderProgram(
      RibbonOpticsShaders.POST_QUAD_VERT,
      RibbonOpticsShaders.COMPOSITE_FRAG,
      "OpticalComposite",
    )
    quad = OrbGeometryBuilder.createFullscreenQuad()
  }

  fun resize(width: Int, height: Int, quality: QualityTier) {
    if (width <= 0 || height <= 0) return
    if (width == surfaceWidth && height == surfaceHeight && quality == currentQuality && sceneFbo != null) {
      return
    }
    surfaceWidth = width
    surfaceHeight = height
    currentQuality = quality
    allocateFramebuffers()
  }

  private fun allocateFramebuffers() {
    releaseFramebuffers()
    val rw = (surfaceWidth * currentQuality.renderScale).toInt().coerceAtLeast(64)
    val rh = (surfaceHeight * currentQuality.renderScale).toInt().coerceAtLeast(64)
    val bw = (rw / 2).coerceAtLeast(32)
    val bh = (rh / 2).coerceAtLeast(32)

    val mainScene = GlBufferUtils.createFramebuffer(rw, rh, withDepth = true, preferHdr = true)
    val refract = GlBufferUtils.createFramebuffer(rw, rh, withDepth = false, preferHdr = mainScene.isHdr)
    val ping = GlBufferUtils.createFramebuffer(bw, bh, withDepth = false, preferHdr = mainScene.isHdr)
    val pong = GlBufferUtils.createFramebuffer(bw, bh, withDepth = false, preferHdr = mainScene.isHdr)

    sceneFbo = mainScene
    refractionFbo = refract
    bloomPingFbo = ping
    bloomPongFbo = pong

    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mainScene.fboId)
    isSceneFboComplete =
      GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
  }

  fun beginScenePass() {
    val fbo = sceneFbo ?: return
    val s = hdrScale
    fbo.bind()
    GLES30.glClearColor(0.005f * s, 0.024f * s, 0.032f * s, 1.0f)
    GLES30.glClearDepthf(1.0f)
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
  }

  fun captureRefractionSnapshot() {
    val src = sceneFbo ?: return
    val dst = refractionFbo ?: return
    GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, src.fboId)
    GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, dst.fboId)
    GLES30.glBlitFramebuffer(
      0, 0, src.width, src.height,
      0, 0, dst.width, dst.height,
      GLES30.GL_COLOR_BUFFER_BIT,
      GLES30.GL_LINEAR,
    )
    src.bind()
  }

  fun executePostProcessToScreen(
    config: AIOrbConfig,
    preTonemapBypass: Boolean = false,
    forceBloomOff: Boolean = false,
  ) {
    val src = sceneFbo ?: return
    val ping = bloomPingFbo ?: return
    val pong = bloomPongFbo ?: return
    val extractProg = extractProgram ?: return
    val blurProg = blurProgram ?: return
    val compProg = compositeProgram ?: return
    val mesh = quad ?: return
    val scale = hdrScale

    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glDisable(GLES30.GL_CULL_FACE)

    val bloomDisabled = forceBloomOff || preTonemapBypass ||
      config.debugMode == com.example.orb.config.RenderDebugMode.BLOOM_OFF ||
      config.debugMode == com.example.orb.config.RenderDebugMode.DEPTH_DEBUG ||
      config.debugMode == com.example.orb.config.RenderDebugMode.FLUID_FLOW ||
      config.debugMode == com.example.orb.config.RenderDebugMode.MENISCUS_DEBUG

    // 1. Strict linear HDR bright-pass extraction (> 1.32 linear HDR only — zero diffuse bloom wash!)
    ping.bind()
    extractProg.use()
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.colorTexId)
    extractProg.setInt("uSceneTex", 0)
    extractProg.setFloat("uThreshold", 1.32f)
    extractProg.setFloat("uKnee", 0.24f)
    extractProg.setFloat("uHdrScale", scale)
    mesh.draw()

    // 2. Separable 9-tap Gaussian Blur iterations (ping <-> pong)
    val iterations = if (bloomDisabled) 1 else config.quality.bloomIterations.coerceIn(1, 5)
    blurProg.use()
    blurProg.setInt("uSourceTex", 0)
    val invW = 1.0f / ping.width.toFloat()
    val invH = 1.0f / ping.height.toFloat()

    for (i in 0 until iterations) {
      val spread = 1.0f + i * 0.65f
      pong.bind()
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ping.colorTexId)
      blurProg.setVec2("uTexelStep", invW * spread, 0f)
      mesh.draw()

      ping.bind()
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, pong.colorTexId)
      blurProg.setVec2("uTexelStep", 0f, invH * spread)
      mesh.draw()
    }

    // 3. Final ACES Filmic + sRGB 2.2 Composite to screen framebuffer (0)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
    compProg.use()
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.colorTexId)
    compProg.setInt("uSceneTex", 0)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ping.colorTexId)
    compProg.setInt("uBloomTex", 1)
    compProg.setFloat("uBloomStrength", if (bloomDisabled) 0f else config.bloom)
    compProg.setFloat("uOverallIntensity", config.overallIntensity)
    compProg.setFloat("uChromaticAmount", if (bloomDisabled) 0f else 0.0038f * (0.45f + 0.55f * config.refraction))
    compProg.setFloat("uHdrScale", scale)
    compProg.setInt("uPreTonemapBypass", if (preTonemapBypass) 1 else 0)
    mesh.draw()

    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glDepthMask(true)
  }

  private fun releaseFramebuffers() {
    sceneFbo?.release(); sceneFbo = null
    refractionFbo?.release(); refractionFbo = null
    bloomPingFbo?.release(); bloomPingFbo = null
    bloomPongFbo?.release(); bloomPongFbo = null
    isSceneFboComplete = false
  }

  fun release() {
    releaseFramebuffers()
    extractProgram?.release(); extractProgram = null
    blurProgram?.release(); blurProgram = null
    compositeProgram?.release(); compositeProgram = null
    quad?.release(); quad = null
  }
}
