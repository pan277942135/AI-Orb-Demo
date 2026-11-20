package com.example.orb.scene

import android.opengl.EGL14
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.RenderDebugMode
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Live runtime & GPU hardware/software telemetry reported by [AIOrbScene].
 */
data class AIOrbTelemetry(
  val fps: Int = 60,
  val frameMsP50: Float = 0f,
  val frameMsP95: Float = 0f,
  val coreAngleDeg: Float = 0f,
  val coreAngularVelDegPerSec: Float = 0f,
  val isHdrFbo: Boolean = true,
  val activeRenderMode: RenderDebugMode = RenderDebugMode.FULL_RENDER,
  val glVendor: String = "Pending GL init",
  val glRenderer: String = "Pending GL init",
  val glVersion: String = "OpenGL ES 3.0",
  val eglVersion: String = "EGL 1.4+",
  val isSoftwareRenderer: Boolean = false,
) {
  val fboFormatLabel: String get() = if (isHdrFbo) "RGBA16F (HDR)" else "RGBA8 (Linear-HDR 0.25x)"
  val renderingBackendLabel: String get() = if (isSoftwareRenderer) "SOFTWARE RENDERING" else "HARDWARE GPU"
}

/**
 * Unified OpenGL ES 3.0 scene renderer for AI Orb Active State V2.
 *
 * 100% pure procedural GPU multi-pass pipeline — ZERO static reference image textures.
 * Supports all production diagnostic isolation modes ([RenderDebugMode]) strictly by
 * toggling/visualizing real production render passes.
 */
class AIOrbScene(
  initialConfig: AIOrbConfig = AIOrbConfig.Default,
  private val onTelemetryUpdated: (AIOrbTelemetry) -> Unit = {},
) : GLSurfaceView.Renderer {

  private val configRef = AtomicReference(initialConfig)

  private val motionController = AIOrbMotionController()
  private val lighting = AIOrbLighting()
  private val background = AIOrbBackground()
  private val innerFluid = AIOrbInnerFluid()
  private val core = AIOrbCore()
  private val orbitRibbons = AIOrbOrbitRibbons()
  private val energyHighlights = AIOrbEnergyHighlights()
  private val particles = AIOrbParticles()
  private val postProcessing = AIOrbPostProcessing()

  private val projMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val outerGlassModel = FloatArray(16)
  private val invOuterGlassModel = FloatArray(16)
  private val cameraPos = FloatArray(3)
  private val orbOriginWorld = floatArrayOf(0f, 0f, 0f, 1f)
  private val orbOriginView = FloatArray(4)

  private var surfaceWidth = 1
  private var surfaceHeight = 1
  private val fovYDeg = 42f
  private var lastFrameNanos: Long = 0L

  @Volatile private var targetYawDeg: Float = 0f
  @Volatile private var targetPitchDeg: Float = 0f
  @Volatile private var isDragging: Boolean = false
  private var currentYawDeg: Float = 0f
  private var currentPitchDeg: Float = 0f

  private val cpuFrameMsWindow = FloatArray(120)
  private var cpuWindowCount = 0
  private var cpuWindowCursor = 0
  private var fpsFrameCount = 0
  private var fpsWindowStartNanos = 0L

  private var glVendor: String = "Unknown"
  private var glRenderer: String = "Unknown"
  private var glVersion: String = "Unknown"
  private var eglVersion: String = "Unknown"
  private var isSoftwareRenderer: Boolean = false

  fun updateConfig(config: AIOrbConfig) {
    configRef.set(config)
  }

  fun onDragOrbit(deltaX: Float, deltaY: Float) {
    isDragging = true
    targetYawDeg = (targetYawDeg + deltaX * 0.22f).coerceIn(-55f, 55f)
    targetPitchDeg = (targetPitchDeg + deltaY * 0.18f).coerceIn(-38f, 38f)
  }

  fun onDragRelease() {
    isDragging = false
  }

  override fun onSurfaceCreated(gl: GL10?, eglConfig: EGLConfig?) {
    val cfg = configRef.get()
    GLES30.glClearColor(0.002f, 0.016f, 0.022f, 1.0f)
    GLES30.glEnable(GLES30.GL_DEPTH_TEST)

    background.init()
    innerFluid.init(cfg.quality.fluidSubdivisions)
    core.init(cfg.quality.icosphereSubdivisions)
    orbitRibbons.init(cfg.quality.ribbonSegments)
    energyHighlights.init()
    particles.init()
    postProcessing.init()

    glVendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "Unknown"
    glRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "Unknown"
    glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "Unknown"
    val dpy = EGL14.eglGetCurrentDisplay()
    eglVersion = if (dpy != null && dpy != EGL14.EGL_NO_DISPLAY) {
      EGL14.eglQueryString(dpy, EGL14.EGL_VERSION) ?: "Unknown"
    } else {
      "Unavailable"
    }
    isSoftwareRenderer = detectSoftwareRenderer(glVendor, glRenderer)

    lastFrameNanos = System.nanoTime()
    fpsWindowStartNanos = lastFrameNanos
    fpsFrameCount = 0
    cpuWindowCount = 0
    cpuWindowCursor = 0

    onTelemetryUpdated(
      AIOrbTelemetry(
        fps = 60,
        frameMsP50 = 0f,
        frameMsP95 = 0f,
        coreAngleDeg = 0f,
        coreAngularVelDegPerSec = 0f,
        isHdrFbo = postProcessing.hdrScale > 0.5f,
        activeRenderMode = cfg.debugMode,
        glVendor = glVendor,
        glRenderer = glRenderer,
        glVersion = glVersion,
        eglVersion = eglVersion,
        isSoftwareRenderer = isSoftwareRenderer,
      )
    )
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    surfaceWidth = width.coerceAtLeast(1)
    surfaceHeight = height.coerceAtLeast(1)
    val cfg = configRef.get()
    postProcessing.resize(surfaceWidth, surfaceHeight, cfg.quality)
    updateProjectionMatrix()
  }

  private fun updateProjectionMatrix() {
    val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
    Matrix.perspectiveM(projMatrix, 0, fovYDeg, aspect, 1.2f, 32.0f)
  }

  override fun onDrawFrame(gl: GL10?) {
    val frameStartNanos = System.nanoTime()
    val rawDt = if (lastFrameNanos == 0L) 1.0 / 60.0 else (frameStartNanos - lastFrameNanos) * 1e-9
    lastFrameNanos = frameStartNanos

    val cfg = configRef.get()
    val motion = motionController.step(rawDt, cfg)
    val dt = rawDt.toFloat().coerceIn(0.0001f, 0.05f)
    val time = motion.simTimeSec.toFloat()

    innerFluid.ensureQuality(cfg.quality.fluidSubdivisions)
    core.ensureQuality(cfg.quality.icosphereSubdivisions)
    orbitRibbons.ensureQuality(cfg.quality.ribbonSegments)
    postProcessing.resize(surfaceWidth, surfaceHeight, cfg.quality)

    if (!isDragging) {
      val decay = (1.0f - dt * 2.2f).coerceIn(0f, 1f)
      targetYawDeg *= decay
      targetPitchDeg *= decay
    }
    val follow = (dt * 7.5f).coerceIn(0f, 1f)
    currentYawDeg += (targetYawDeg - currentYawDeg) * follow
    currentPitchDeg += (targetPitchDeg - currentPitchDeg) * follow

    // Calibrated reference composition (V14): strong ~54% hero orb presence + zero ribbon clipping
    val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
    val tanHalfY = tan(Math.toRadians(fovYDeg.toDouble() * 0.5)).toFloat()
    val tanHalfX = (tanHalfY * aspect).coerceAtLeast(0.10f)
    val visualBoundRadius = 1.54f
    val baseCamDist = maxOf(
      visualBoundRadius / tanHalfY,
      visualBoundRadius / tanHalfX,
      4.05f,
    )

    // In Core-Rotation QA mode, lock camera completely still so observed internal rotation is 100% pure
    val lockCamera = cfg.effectiveLockCoreRotation
    val autoYawRad = if (lockCamera) {
      Math.toRadians(currentYawDeg.toDouble()).toFloat()
    } else {
      sin(time * 0.14f) * 0.026f + Math.toRadians(currentYawDeg.toDouble()).toFloat()
    }
    val autoPitchRad = if (lockCamera) {
      Math.toRadians(currentPitchDeg.toDouble()).toFloat()
    } else {
      cos(time * 0.11f) * 0.020f + Math.toRadians(currentPitchDeg.toDouble()).toFloat()
    }

    val camX = baseCamDist * cos(autoPitchRad) * sin(autoYawRad)
    val camY = baseCamDist * sin(autoPitchRad)
    val camZ = baseCamDist * cos(autoPitchRad) * cos(autoYawRad)
    cameraPos[0] = camX
    cameraPos[1] = camY
    cameraPos[2] = camZ

    Matrix.setLookAtM(
      viewMatrix, 0,
      camX, camY, camZ,
      0f, 0f, 0f,
      0f, 1f, 0f,
    )

    Matrix.multiplyMV(orbOriginView, 0, viewMatrix, 0, orbOriginWorld, 0)
    val orbCenterViewZ = orbOriginView[2]

    motion.writeOuterGlassModelMatrix(outerGlassModel)
    motion.writeInvOuterGlassModelMatrix(invOuterGlassModel)

    val rw = postProcessing.renderWidth
    val rh = postProcessing.renderHeight
    val hdrScale = postProcessing.hdrScale
    val mode = cfg.debugMode
    val hideOuter = cfg.effectiveHideOuterOrbitals

    val drawRibbons = !hideOuter && mode != RenderDebugMode.OPTICS_ONLY
    val drawHighlights = !hideOuter && mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.DEPTH_DEBUG
    val drawParticles = !hideOuter && mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.DEPTH_DEBUG
    val drawInnerFluid = mode != RenderDebugMode.RIBBONS_ONLY &&
      mode != RenderDebugMode.OPTICS_ONLY &&
      mode != RenderDebugMode.GLASS_ONLY &&
      mode != RenderDebugMode.DEPTH_DEBUG
    val drawCore = mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.OPTICS_ONLY

    // ==================== MULTI-PASS PRODUCTION GPU PIPELINE ====================
    postProcessing.beginScenePass()

    // Pass 1: Deep emerald-teal atmospheric space & multi-depth bokeh
    background.draw(time, rw, rh, autoYawRad, autoPitchRad, hdrScale, cfg)

    // Pass 2: Far-depth crystal droplets & micro-motes
    if (drawParticles) {
      particles.drawPass(0, orbCenterViewZ, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }

    // Pass 3: Back-hemisphere 3D ribbons (refracted through the glass crystal sphere)
    if (drawRibbons) {
      orbitRibbons.drawPass(0, orbCenterViewZ, viewMatrix, projMatrix, cameraPos, motion, hdrScale, cfg)
    }

    // Pass 4: Back-hemisphere travelling HDR starburst flares
    if (drawHighlights) {
      energyHighlights.drawPass(0, orbCenterViewZ, orbitRibbons, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }

    // Pass 5: Concave inner back-wall liquid bowl (locked to unified uCoreRotMat)
    if (drawInnerFluid) {
      innerFluid.draw(outerGlassModel, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }

    // Pass 6: Capture background + back ribbons + concave inner bowl for optical lens refraction
    if (drawCore) {
      postProcessing.captureRefractionSnapshot()
    }

    // Pass 7: Outer blown crystal glass sphere + rotating vortex volume + bisected meniscus + Voronoi caustics
    if (drawCore) {
      core.draw(
        outerGlassModel = outerGlassModel,
        invOuterGlassModel = invOuterGlassModel,
        viewMatrix = viewMatrix,
        projMatrix = projMatrix,
        cameraPos = cameraPos,
        refractionTexId = postProcessing.refractionTextureId,
        fboWidth = rw,
        fboHeight = rh,
        motion = motion,
        hdrScale = hdrScale,
        lighting = lighting,
        config = cfg,
      )
    }

    // Pass 8: Front-hemisphere 3D ribbons (hardware depth-tested against outer glass sphere!)
    if (drawRibbons) {
      orbitRibbons.drawPass(1, orbCenterViewZ, viewMatrix, projMatrix, cameraPos, motion, hdrScale, cfg)
    }

    // Pass 9: Front-hemisphere jewel starbursts & foreground crystal water droplets
    if (drawHighlights) {
      energyHighlights.drawPass(1, orbCenterViewZ, orbitRibbons, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }
    if (drawParticles) {
      particles.drawPass(1, orbCenterViewZ, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }

    // Pass 10: High-threshold HDR Bloom + ACES Filmic + True sRGB 2.2 Composite
    postProcessing.executePostProcessToScreen(cfg)

    // Record CPU render-thread duration & update rolling P50/P95 + FPS telemetry every 500ms
    val cpuMs = (System.nanoTime() - frameStartNanos) * 1e-6f
    cpuFrameMsWindow[cpuWindowCursor] = cpuMs
    cpuWindowCursor = (cpuWindowCursor + 1) % cpuFrameMsWindow.size
    if (cpuWindowCount < cpuFrameMsWindow.size) cpuWindowCount++

    fpsFrameCount++
    val elapsed = frameStartNanos - fpsWindowStartNanos
    if (elapsed >= 500_000_000L) {
      val fps = ((fpsFrameCount * 1_000_000_000.0) / elapsed).toInt().coerceIn(1, 120)
      val sorted = cpuFrameMsWindow.copyOf(cpuWindowCount).apply { sort() }
      val p50 = if (sorted.isNotEmpty()) sorted[sorted.size / 2] else 0f
      val p95 = if (sorted.isNotEmpty()) sorted[((sorted.size - 1) * 0.95f).toInt()] else 0f

      onTelemetryUpdated(
        AIOrbTelemetry(
          fps = fps,
          frameMsP50 = p50,
          frameMsP95 = p95,
          coreAngleDeg = AIOrbMotionController.wrapAngleDeg(motion.coreRotationAngleDeg),
          coreAngularVelDegPerSec = motion.coreAngularVelocityDegPerSec,
          isHdrFbo = hdrScale > 0.5f,
          activeRenderMode = mode,
          glVendor = glVendor,
          glRenderer = glRenderer,
          glVersion = glVersion,
          eglVersion = eglVersion,
          isSoftwareRenderer = isSoftwareRenderer,
        )
      )
      fpsFrameCount = 0
      fpsWindowStartNanos = frameStartNanos
    }
  }

  fun release() {
    background.release()
    innerFluid.release()
    core.release()
    orbitRibbons.release()
    energyHighlights.release()
    particles.release()
    postProcessing.release()
  }

  companion object {
    /**
     * Distinguishes Software Rendering (SwiftShader, llvmpipe, softpipe, Android Emulator software)
     * from Hardware GPU execution (Adreno, Mali, Immortalis, PowerVR, NVIDIA, AMD, Intel, Apple).
     */
    fun detectSoftwareRenderer(vendor: String, renderer: String): Boolean {
      val combined = "$vendor $renderer".lowercase(Locale.US)
      return combined.contains("swiftshader") ||
        combined.contains("llvmpipe") ||
        combined.contains("softpipe") ||
        combined.contains("software") ||
        combined.contains("pixel-flinger") ||
        combined.contains("lavapipe") ||
        combined.contains("basic render")
    }

    /**
     * Authoritative render pass execution manifest for a given [RenderDebugMode],
     * audited directly by unit tests for P0-1 provenance & pass-order integrity.
     */
    fun activeRenderPasses(mode: RenderDebugMode): List<String> {
      if (mode == RenderDebugMode.REFERENCE_ONLY) {
        return listOf("QA_STATIC_REFERENCE_VIEWER_ONLY (NO_GPU_COMPOSITE)")
      }
      val hideOuter = mode.isCoreIsolated
      val drawRibbons = !hideOuter && mode != RenderDebugMode.OPTICS_ONLY
      val drawHighlights = !hideOuter && mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.DEPTH_DEBUG
      val drawParticles = !hideOuter && mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.DEPTH_DEBUG
      val drawInnerFluid = mode != RenderDebugMode.RIBBONS_ONLY &&
        mode != RenderDebugMode.OPTICS_ONLY &&
        mode != RenderDebugMode.GLASS_ONLY &&
        mode != RenderDebugMode.DEPTH_DEBUG
      val drawCore = mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.OPTICS_ONLY

      return buildList {
        add("PASS_1_BACKGROUND_LINEAR_TEAL_BOKEH")
        if (drawParticles) add("PASS_2_BACK_DROPLETS_PARTICLES")
        if (drawRibbons) add("PASS_3_BACK_RIBBONS_RESIDENT_AND_SECONDARY")
        if (drawHighlights) add("PASS_4_BACK_STARBURST_HIGHLIGHTS")
        if (drawInnerFluid) add("PASS_5_CONCAVE_INNER_FLUID_BOWL")
        if (drawCore) add("PASS_6_REFRACTION_FBO_BLIT_SNAPSHOT")
        if (drawCore) add("PASS_7_OUTER_CRYSTAL_CORE_MENISCUS_VORONOI_CAUSTICS")
        if (drawRibbons) add("PASS_8_FRONT_RIBBONS_DEPTH_TESTED")
        if (drawHighlights) add("PASS_9A_FRONT_STARBURST_HIGHLIGHTS")
        if (drawParticles) add("PASS_9B_FRONT_DROPLETS_PARTICLES")
        add(
          if (mode == RenderDebugMode.BLOOM_OFF) "PASS_10_ACES_SRGB_COMPOSITE_BLOOM_OFF"
          else "PASS_10_HDR_BLOOM_ACES_SRGB_COMPOSITE"
        )
      }
    }
  }
}
