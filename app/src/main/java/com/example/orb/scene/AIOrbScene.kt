package com.example.orb.scene

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.orb.config.AIOrbConfig
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Unified OpenGL ES 3.0 scene renderer for AI Orb Active State (V2).
 *
 * V2 Upgrades:
 * - Horizontal + vertical frustum-adaptive camera distance (guarantees 100% full framing of the orb,
 *   all 3D ribbons, energy flares, and water droplets on narrow portrait phones and tablets alike)
 * - Unified linear HDR scaling across all passes (zero 8-bit clipping on RGBA8 fallback GPUs)
 * - Concave inner back-wall bowl + refracted-ray 3D meniscus & volumetric caustics inside AIOrbCore
 */
class AIOrbScene(
  initialConfig: AIOrbConfig = AIOrbConfig.Default,
  private val onFpsUpdated: (Int) -> Unit = {},
) : GLSurfaceView.Renderer {

  private val configRef = AtomicReference(initialConfig)

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
  private val orbModelMatrix = FloatArray(16)
  private val cameraPos = FloatArray(3)
  private val orbOriginWorld = floatArrayOf(0f, 0f, 0f, 1f)
  private val orbOriginView = FloatArray(4)

  private var surfaceWidth = 1
  private var surfaceHeight = 1
  private val fovYDeg = 42f

  private var simTimeSec: Double = 0.0
  private var lastFrameNanos: Long = 0L

  @Volatile private var targetYawDeg: Float = 0f
  @Volatile private var targetPitchDeg: Float = 0f
  @Volatile private var isDragging: Boolean = false
  private var currentYawDeg: Float = 0f
  private var currentPitchDeg: Float = 0f

  private var fpsFrameCount = 0
  private var fpsWindowStartNanos = 0L

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
    GLES30.glClearColor(0.005f, 0.024f, 0.032f, 1.0f)
    GLES30.glEnable(GLES30.GL_DEPTH_TEST)

    background.init()
    innerFluid.init(cfg.quality.fluidSubdivisions)
    core.init(cfg.quality.icosphereSubdivisions)
    orbitRibbons.init(cfg.quality.ribbonSegments)
    energyHighlights.init()
    particles.init()
    postProcessing.init()

    lastFrameNanos = System.nanoTime()
    fpsWindowStartNanos = lastFrameNanos
    fpsFrameCount = 0
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
    val nowNanos = System.nanoTime()
    val rawDt = if (lastFrameNanos == 0L) 1f / 60f else (nowNanos - lastFrameNanos) * 1e-9f
    lastFrameNanos = nowNanos
    val dt = rawDt.coerceIn(0.0001f, 0.05f)

    val cfg = configRef.get()
    if (!cfg.isPaused) {
      simTimeSec += dt.toDouble()
    }
    val time = simTimeSec.toFloat()

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

    // Frustum-adaptive camera distance: guarantees complete orb + ribbons + droplets framing
    // across BOTH narrow portrait phones (e.g. 9:19.5) and wide landscape tablets.
    val aspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
    val tanHalfY = tan(Math.toRadians(fovYDeg.toDouble() * 0.5)).toFloat()
    val tanHalfX = (tanHalfY * aspect).coerceAtLeast(0.10f)
    val visualBoundRadius = 1.68f
    val baseCamDist = maxOf(
      visualBoundRadius / tanHalfY,
      visualBoundRadius / tanHalfX,
      4.35f,
    )

    val autoYawRad = sin(time * 0.14f) * 0.05f + Math.toRadians(currentYawDeg.toDouble()).toFloat()
    val autoPitchRad = cos(time * 0.11f) * 0.038f + Math.toRadians(currentPitchDeg.toDouble()).toFloat()

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

    // Continuous non-mechanical multi-frequency breathing + gentle 3D spatial rotation
    val breatheScale = 1.0f +
      0.015f * sin(time * 0.80f) +
      0.008f * sin(time * 1.33f + 1.15f) +
      0.004f * cos(time * 0.52f + 2.4f)
    val sX = cfg.coreScale * breatheScale * (1.0f + 0.007f * sin(time * 0.65f))
    val sY = cfg.coreScale * breatheScale * (1.0f + 0.007f * cos(time * 0.72f + 0.8f))
    val sZ = cfg.coreScale * breatheScale * (1.0f - 0.005f * sin(time * 0.58f + 1.6f))

    Matrix.setIdentityM(orbModelMatrix, 0)
    Matrix.rotateM(orbModelMatrix, 0, (time * 4.2f) % 360f, 0f, 1f, 0f)
    Matrix.rotateM(orbModelMatrix, 0, sin(time * 0.22f) * 7.5f, 1f, 0f, 0f)
    Matrix.rotateM(orbModelMatrix, 0, cos(time * 0.18f) * 6.0f, 0f, 0f, 1f)
    Matrix.scaleM(orbModelMatrix, 0, sX, sY, sZ)

    val rw = postProcessing.renderWidth
    val rh = postProcessing.renderHeight
    val hdrScale = postProcessing.hdrScale

    // ==================== MULTI-PASS OPTICAL PIPELINE ====================
    postProcessing.beginScenePass()

    // Pass 1: Deep emerald-teal atmospheric space & warm olive-gold / teal bokeh
    background.draw(time, rw, rh, autoYawRad, autoPitchRad, hdrScale, cfg)

    // Pass 2: Far-depth glass droplets & micro-motes behind orb
    particles.drawPass(0, orbCenterViewZ, viewMatrix, projMatrix, time, hdrScale, cfg)

    // Pass 3: Back-hemisphere 3D ribbons (will refract through the blown crystal sphere)
    orbitRibbons.drawPass(0, orbCenterViewZ, viewMatrix, projMatrix, cameraPos, time, hdrScale, cfg)

    // Pass 4: Back-hemisphere travelling energy starburst flares
    energyHighlights.drawPass(0, orbCenterViewZ, orbitRibbons, viewMatrix, projMatrix, time, hdrScale, cfg)

    // Pass 5: Concave inner back-wall liquid bowl (deep sapphire/emerald interior shadow + back caustics)
    innerFluid.draw(orbModelMatrix, viewMatrix, projMatrix, cameraPos, time, hdrScale, cfg)

    // Pass 6: Capture background + back ribbons + concave inner bowl for optical lens refraction
    postProcessing.captureRefractionSnapshot()

    // Pass 7: Outer blown crystal sphere with refracted-ray 3D meniscus, volumetric caustics & double rim
    core.draw(
      modelMatrix = orbModelMatrix,
      viewMatrix = viewMatrix,
      projMatrix = projMatrix,
      cameraPos = cameraPos,
      refractionTexId = postProcessing.refractionTextureId,
      fboWidth = rw,
      fboHeight = rh,
      time = time,
      hdrScale = hdrScale,
      lighting = lighting,
      config = cfg,
    )

    // Pass 8: Front-hemisphere 3D ribbons (hardware depth-tested against outer crystal glass depth)
    orbitRibbons.drawPass(1, orbCenterViewZ, viewMatrix, projMatrix, cameraPos, time, hdrScale, cfg)

    // Pass 9: Front-hemisphere travelling HDR starbursts & foreground 3D water droplets
    energyHighlights.drawPass(1, orbCenterViewZ, orbitRibbons, viewMatrix, projMatrix, time, hdrScale, cfg)
    particles.drawPass(1, orbCenterViewZ, viewMatrix, projMatrix, time, hdrScale, cfg)

    // Pass 10: High-threshold HDR Bloom + Saturation-preserving ACES Filmic Composite + Vignette
    postProcessing.executePostProcessToScreen(cfg)

    // Live FPS telemetry every 500ms
    fpsFrameCount++
    val elapsed = nowNanos - fpsWindowStartNanos
    if (elapsed >= 500_000_000L) {
      val fps = ((fpsFrameCount * 1_000_000_000.0) / elapsed).toInt().coerceIn(1, 120)
      onFpsUpdated(fps)
      fpsFrameCount = 0
      fpsWindowStartNanos = nowNanos
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
}
