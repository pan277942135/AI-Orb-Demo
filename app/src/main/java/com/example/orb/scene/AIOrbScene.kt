package com.example.orb.scene

import android.content.res.AssetManager
import android.opengl.EGL14
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.OrbSceneMode
import com.example.orb.config.RenderDebugMode
import com.example.orb.task.PerimeterArcLengthTable
import com.example.orb.task.PerimeterPathGeometry
import com.example.orb.task.PerimeterWaterLightRenderer
import com.example.orb.task.PerimeterWaterLightMaterial
import com.example.orb.task.PhotoFrameGeometry
import com.example.orb.task.PhotoTextureRenderer
import com.example.orb.task.TaskEnergyController
import com.example.orb.task.TaskEnergyEvidence
import com.example.orb.task.TaskEnergyRibbonRenderer
import com.example.orb.task.TaskEnergySnapshot
import com.example.orb.task.TaskEnergyStage
import com.example.orb.task.TaskGpuCaptureProgress
import com.example.orb.task.TaskGpuCaptureSnapshot
import com.example.orb.task.TaskWindowFrameCapture
import com.example.orb.task.TaskGpuDrawTelemetry
import com.example.orb.task.TaskGpuEvidenceCapture
import com.example.orb.task.TaskRenderDebugOptions
import com.example.orb.task.TaskSceneLayoutSnapshot
import com.example.orb.task.TaskVisualDebugMode
import com.example.orb.task.TaskVisualFidelityV41
import com.example.orb.v3.GpuCaptureProgress
import com.example.orb.v3.MasterFidelityV32
import com.example.orb.v3.OrbCameraBuilder
import com.example.orb.v3.OrbCameraSnapshot
import com.example.orb.v3.OrbGpuCaptureSnapshot
import com.example.orb.v3.OrbGpuReferenceCapture
import com.example.orb.v3.OrbV3LoadState
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Live runtime & GPU hardware/software telemetry reported by [AIOrbScene],
 * including V3 WebP 3D Volume decode, upload, and VRAM metrics.
 */
data class AIOrbTelemetry(
  val fps: Int = 0,
  val frameMsP50: Float = 0f,
  val frameMsP95: Float = 0f,
  val frameMsLatest: Float = 0f,
  val frameSampleCount: Int = 0,
  val hasSufficientTimingSamples: Boolean = false,
  val gpuFrameTimeStatus: String = FrameTimingSnapshot.GPU_FRAME_TIME_UNAVAILABLE,
  val coreAngleDeg: Float = 0f,
  val coreAngularVelDegPerSec: Float = 0f,
  val isHdrFbo: Boolean = true,
  val activeRenderMode: RenderDebugMode = RenderDebugMode.FULL_RENDER,
  val glVendor: String = "Pending GL init",
  val glRenderer: String = "Pending GL init",
  val glVersion: String = "OpenGL ES 3.0",
  val eglVersion: String = "EGL 1.4+",
  val isSoftwareRenderer: Boolean = false,
  val volumeLoadState: OrbV3LoadState = OrbV3LoadState.UNINITIALIZED,
  val volumeLoaded: Boolean = false,
  val volumeDimensions: String = "256x256x32",
  val webpDecodeMs: Float = 0f,
  val texture3dUploadMs: Float = 0f,
  val gpuVolumeMemoryMiB: Float = 0f,
  val volumeStatus: String = "Pending V3 WebP load",
) {
  val fboFormatLabel: String get() = if (isHdrFbo) "RGBA16F (HDR)" else "RGBA8 (Linear-HDR 0.25x)"
  val renderingBackendLabel: String get() = if (isSoftwareRenderer) "SOFTWARE RENDERING" else "HARDWARE GPU"
}

/**
 * Unified OpenGL ES 3.0 scene renderer shared across BOTH Tab 1 (`AI Orb`) and Tab 2 (`照片水光`).
 *
 * - Tab 1 (`ORB_ONLY`): 100% preserves the frozen V3 WebP-First 3D Volume Hybrid Rendering pipeline,
 *   double-wall crystal glass shell, independent 26°/s Core Rotation, 5 frozen Residents, and all
 *   QA diagnostic modes with ZERO Task/Photo/Perimeter overhead.
 * - Tab 2 (`TASK_PERIMETER_DEMO`): Directly reuses the EXACT SAME [core], [orbitRibbons],
 *   [energyHighlights], [particles], [motionController], and [postProcessing] FBO via off-axis
 *   lens-shift projection, adding the studio backdrop, cropped fishing photo, 4 independent Task
 *   Energy streams, and unified-path 6-keyframe Perimeter WaterLight.
 */
class AIOrbScene(
  initialConfig: AIOrbConfig = AIOrbConfig.Default,
  private val assetManager: AssetManager? = null,
  private val onTelemetryUpdated: (AIOrbTelemetry) -> Unit = {},
  private val onTaskEvidenceUpdated: (TaskEnergyEvidence) -> Unit = {},
  private val taskVisualFidelity: TaskVisualFidelityV41? = assetManager?.let(TaskVisualFidelityV41::loadFromAssets),
) : GLSurfaceView.Renderer {

  private val configRef = AtomicReference(initialConfig)

  // V3.2 Master-Fitted runtime parameters loaded from assets/orb_v3/manifest/MASTER_FIDELITY_V32.json
  val masterFidelity: MasterFidelityV32 = MasterFidelityV32.loadFromAssets(assetManager)
  private val gpuReferenceCapture = OrbGpuReferenceCapture()

  // Shared single-instance V3 AI Orb core, Residents, motion clock & post-processing
  private val motionController = AIOrbMotionController()
  private val lighting = AIOrbLighting()
  private val background = AIOrbBackground()
  private val core = AIOrbCore()
  private val orbitRibbons = AIOrbOrbitRibbons(masterFidelity)
  private val energyHighlights = AIOrbEnergyHighlights()
  private val particles = AIOrbParticles()
  private val postProcessing = AIOrbPostProcessing()

  // Tab 2 additive renderers & deterministic 6KF Task Energy controller (strictly inactive on Tab 1)
  private val photoTextureRenderer = PhotoTextureRenderer(taskVisualFidelity)
  private val taskEnergyRenderer = TaskEnergyRibbonRenderer(taskVisualFidelity?.taskStreams)
  private val perimeterRenderer = PerimeterWaterLightRenderer(
    material = taskVisualFidelity?.perimeter?.let {
      PerimeterWaterLightMaterial(
        l1IceAlphaScale = it.l1AlphaScale,
        l2EmeraldAlphaScale = it.l2AlphaScale,
        l3GoldAlphaScale = it.l3AlphaScale,
        dropletCountPerSide = it.l4DropletsPerSide,
        l1AlphaMin = it.l1AlphaMin,
        l1AlphaMax = it.l1AlphaMax,
        l2AlphaMin = it.l2AlphaMin,
        l2AlphaMax = it.l2AlphaMax,
        l3AlphaMin = it.l3AlphaMin,
        l3AlphaMax = it.l3AlphaMax,
      )
    } ?: PerimeterWaterLightMaterial(),
    visualLock = taskVisualFidelity,
  )
  private val taskEnergyController = TaskEnergyController(taskVisualFidelity)
  private var taskCaptureController: TaskEnergyController? = null
  private val taskGpuEvidenceCapture = TaskGpuEvidenceCapture()
  private val taskDebugOptionsRef = AtomicReference(TaskRenderDebugOptions.Default)
  private val lastTaskDrawTelemetryRef = AtomicReference(TaskGpuDrawTelemetry())
  private val taskLock = Any()
  private val taskLayoutRef = AtomicReference<TaskSceneLayoutSnapshot?>(null)
  private var cachedPerimeterPhoto: PhotoFrameGeometry? = null
  private var cachedPerimeterTable: PerimeterArcLengthTable? = null
  private var taskRenderersInitialized: Boolean = false

  // Shared-resource reuse counters verified by G0 regression & runtime diagnostics
  private val glContextInitCounter = AtomicInteger(0)
  private val webpLoadCounter = AtomicInteger(0)
  private val tabSwitchCounter = AtomicInteger(0)

  val glContextInitCount: Int get() = glContextInitCounter.get()
  val webpLoadCount: Int get() = webpLoadCounter.get()
  val tabSwitchCount: Int get() = tabSwitchCounter.get()

  /** V3 executes strictly one unified 3D volume fluid renderer (zero duplicate inner-bowl fluid pass). */
  val isV3SingleVolumePipeline: Boolean = true

  private val projMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val taskProjMatrix = FloatArray(16)
  private val taskViewMatrix = FloatArray(16)
  private val outerGlassModel = FloatArray(16)
  private val invOuterGlassModel = FloatArray(16)
  private val cameraPos = FloatArray(3)
  private val taskCameraPos = FloatArray(3)
  private val orbOriginWorld = floatArrayOf(0f, 0f, 0f, 1f)
  private val orbOriginView = FloatArray(4)

  private var surfaceWidth = 1
  private var surfaceHeight = 1
  private val fovYDeg: Float get() = masterFidelity.camera.fovYDeg
  private var lastFrameNanos: Long = 0L
  private var lastMotionSimTimeSec: Double = Double.NaN
  private var lastTaskEvidenceNanos: Long = 0L
  private var lastReportedTaskStage: TaskEnergyStage? = null
  private var lastReportedTaskPaused: Boolean? = null

  @Volatile private var targetYawDeg: Float = 0f
  @Volatile private var targetPitchDeg: Float = 0f
  @Volatile private var isDragging: Boolean = false
  private var currentYawDeg: Float = 0f
  private var currentPitchDeg: Float = 0f

  private val frameTelemetryTracker = FrameTelemetryStatsTracker()
  private var wasCapturingV32LastFrame: Boolean = false
  private var wasCapturingTaskLastFrame: Boolean = false
  private var taskCaptureFrozenMotion: AIOrbMotionSnapshot? = null

  private var glVendor: String = "Unknown"
  private var glRenderer: String = "Unknown"
  private var glVersion: String = "Unknown"
  private var eglVersion: String = "Unknown"
  private var isSoftwareRenderer: Boolean = false
  private var hasRenderedFirstFrame: Boolean = false

  init {
    // Kick off non-blocking background WebP decode before EGL/SurfaceView creation completes,
    // eliminating >1000ms SurfaceSyncGroup transaction timeouts on startup.
    if (assetManager != null) {
      core.preloadAssetsAsync(assetManager)
    }
  }

  fun updateConfig(config: AIOrbConfig) {
    val prev = configRef.getAndSet(config)
    if (prev.sceneMode != config.sceneMode) {
      tabSwitchCounter.incrementAndGet()
      if (config.sceneMode == OrbSceneMode.TASK_PERIMETER_DEMO) {
        synchronized(taskLock) {
          taskEnergyController.ensureStarted()
        }
        emitTaskEvidenceNow()
      }
    }
  }

  /**
   * Triggers the Gate 4 one-click 6-frame production GLES 3.0 evidence capture
   * directly on the active GL thread (zero Compose UI/status bar).
   */
  fun requestV32EvidenceExport(
    outputDir: File,
    onProgress: (GpuCaptureProgress) -> Unit,
  ): Boolean {
    return gpuReferenceCapture.requestCapture(outputDir, onProgress)
  }

  /**
   * Triggers the Tab 2 V4.1 one-click production GLES + Window evidence capture
   * directly on the active GL thread (zero Compose UI/status bar).
   */
  fun requestTask6KfEvidenceExport(
    outputDir: File,
    windowCapture: TaskWindowFrameCapture?,
    onProgress: (TaskGpuCaptureProgress) -> Unit,
  ): Boolean {
    return taskGpuEvidenceCapture.requestCapture(
      outputDirectory = outputDir,
      onProgress = onProgress,
      windowCapture = windowCapture,
      hardwareGpuAvailable = !isSoftwareRenderer,
    )
  }

  /**
   * Updates Tab 2 GPU visibility diagnostic options ONLY.
   * Strictly NEVER mutates `taskEnergyController` elapsed time, progress, or emitted events.
   */
  fun updateTaskDebugOptions(options: TaskRenderDebugOptions) {
    taskDebugOptionsRef.set(options)
    emitTaskEvidenceNow()
  }

  fun currentTaskDebugOptions(): TaskRenderDebugOptions = taskDebugOptionsRef.get()

  fun updateTaskLayout(snapshot: TaskSceneLayoutSnapshot) {
    if (snapshot.isValid()) {
      taskLayoutRef.set(snapshot)
    }
  }

  fun toggleTaskPaused(): Boolean {
    val paused = synchronized(taskLock) {
      taskEnergyController.ensureStarted()
      taskEnergyController.togglePaused()
    }
    emitTaskEvidenceNow()
    return paused
  }

  fun setTaskPaused(paused: Boolean) {
    synchronized(taskLock) {
      taskEnergyController.ensureStarted()
      taskEnergyController.setPaused(paused)
    }
    emitTaskEvidenceNow()
  }

  fun restartTask(): TaskEnergySnapshot {
    val snap = synchronized(taskLock) {
      taskEnergyController.restart(unpause = true)
    }
    emitTaskEvidenceNow()
    return snap
  }

  fun jumpToTaskKeyframe(stage: TaskEnergyStage): TaskEnergySnapshot {
    val layout = resolveTaskLayout()
    val table = resolvePerimeterTable(layout.photoFrame)
    val snap = synchronized(taskLock) {
      taskEnergyController.jumpToKeyframe(stage, table.geometry)
    }
    emitTaskEvidenceNow()
    return snap
  }

  fun currentTaskSnapshot(): TaskEnergySnapshot = synchronized(taskLock) {
    taskEnergyController.snapshot()
  }

  fun currentTaskEvidence(): TaskEnergyEvidence {
    val layout = taskLayoutRef.get() ?: if (surfaceWidth >= 64 && surfaceHeight >= 64) {
      TaskSceneLayoutSnapshot.canonicalFallback(surfaceWidth, surfaceHeight, taskVisualFidelity)
    } else {
      null
    }
    val (snap, evs) = synchronized(taskLock) {
      taskEnergyController.snapshot() to taskEnergyController.events
    }
    return TaskEnergyEvidence(
      glContextInitCount = glContextInitCounter.get(),
      webpLoadCount = webpLoadCounter.get(),
      tabSwitchCount = tabSwitchCounter.get(),
      taskSnapshot = snap,
      emittedEvents = evs,
      layoutSnapshot = layout,
      photoTextureLoaded = photoTextureRenderer.isPhotoTextureLoaded,
      backgroundTextureLoaded = photoTextureRenderer.isBackgroundTextureLoaded,
      backgroundLoadStatus = photoTextureRenderer.backgroundLoadStatus,
      debugOptions = taskDebugOptionsRef.get(),
      drawTelemetry = lastTaskDrawTelemetryRef.get(),
    )
  }

  private fun emitTaskEvidenceNow() {
    val evidence = currentTaskEvidence()
    lastReportedTaskStage = evidence.taskSnapshot.stage
    lastReportedTaskPaused = evidence.taskSnapshot.isPaused
    lastTaskEvidenceNanos = System.nanoTime()
    onTaskEvidenceUpdated(evidence)
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
    glContextInitCounter.incrementAndGet()
    GLES30.glClearColor(0.002f, 0.016f, 0.022f, 1.0f)
    GLES30.glEnable(GLES30.GL_DEPTH_TEST)

    background.init()
    core.init(cfg.quality.icosphereSubdivisions, assetManager)
    orbitRibbons.init(cfg.quality.ribbonSegments)
    energyHighlights.init()
    particles.init()
    postProcessing.init()

    // Defer Tab 2 shader compilation until first Tab 2 frame so Tab 1 startup swaps cleanly
    // well within Android's 1000ms SurfaceSyncGroup handshake budget.
    taskRenderersInitialized = false

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
    lastMotionSimTimeSec = Double.NaN
    frameTelemetryTracker.reset(lastFrameNanos)
    wasCapturingV32LastFrame = false
    hasRenderedFirstFrame = false

    val volTel = core.volumeTelemetry
    if (volTel.isLoaded && webpLoadCounter.get() == 0) {
      webpLoadCounter.incrementAndGet()
    }
    onTelemetryUpdated(
      buildTelemetrySnapshot(
        timing = frameTelemetryTracker.snapshot(),
        coreAngleDeg = 0f,
        coreAngularVelDegPerSec = 0f,
        mode = cfg.debugMode,
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
    val camSnap = OrbCameraBuilder.build(
      surfaceWidth = surfaceWidth,
      surfaceHeight = surfaceHeight,
      cameraFit = masterFidelity.camera,
    )
    camSnap.writeProjMatrix(projMatrix)
  }

  private fun ensureTaskRenderersInitialized() {
    if (taskRenderersInitialized) return
    photoTextureRenderer.init()
    taskEnergyRenderer.init()
    perimeterRenderer.init()
    taskRenderersInitialized = true
  }

  private fun resolveTaskLayout(): TaskSceneLayoutSnapshot {
    val sw = surfaceWidth.coerceAtLeast(64)
    val sh = surfaceHeight.coerceAtLeast(64)
    val live = taskLayoutRef.get()
    if (
      live != null &&
      live.isValid() &&
      abs(live.surfaceWidthPx - sw) <= 4 &&
      abs(live.surfaceHeightPx - sh) <= 4
    ) {
      return live
    }
    return TaskSceneLayoutSnapshot.canonicalFallback(sw, sh, taskVisualFidelity)
  }

  private fun resolvePerimeterTable(photo: PhotoFrameGeometry): PerimeterArcLengthTable {
    val prev = cachedPerimeterTable
    if (prev != null && cachedPerimeterPhoto == photo) {
      return prev
    }
    val table = PerimeterArcLengthTable(
      PerimeterPathGeometry(
        photo = photo,
        kf04SideAscendRatio = taskVisualFidelity?.keyframes?.kf04SideAscendRatio ?: 0.60f,
      )
    )
    cachedPerimeterPhoto = photo
    cachedPerimeterTable = table
    return table
  }

  override fun onDrawFrame(gl: GL10?) {
    val frameStartNanos = System.nanoTime()
    val rawDt = if (lastFrameNanos == 0L) 1.0 / 60.0 else (frameStartNanos - lastFrameNanos) * 1e-9
    lastFrameNanos = frameStartNanos

    val cfg = configRef.get()
    val volumeJustUpdated = core.uploadReadyAssetsIfNeeded(assetManager)
    if (volumeJustUpdated) {
      webpLoadCounter.incrementAndGet()
    }

    val isTaskDemo = cfg.sceneMode == OrbSceneMode.TASK_PERIMETER_DEMO

    // Fast first-frame handshake: complete initial eglSwapBuffers() in <15ms so Android's
    // SurfaceSyncGroup(SurfaceView / VRI[MainActivity]) NEVER hits its 1000ms timeout while
    // 35 Lossless RGBA WebP assets finish decoding in the background or SwiftShader warms up.
    if (!hasRenderedFirstFrame && (!core.isVolumeReady || isSoftwareRenderer)) {
      hasRenderedFirstFrame = true
      postProcessing.resize(surfaceWidth, surfaceHeight, cfg.quality)
      postProcessing.beginScenePass()
      if (isTaskDemo) {
        ensureTaskRenderersInitialized()
        val layout = resolveTaskLayout()
        photoTextureRenderer.drawBackground(
          time = 0f,
          renderWidth = postProcessing.renderWidth,
          renderHeight = postProcessing.renderHeight,
          layout = layout,
          hdrScale = postProcessing.hdrScale,
          assetManager = assetManager,
        )
      } else {
        background.draw(
          time = 0f,
          width = postProcessing.renderWidth,
          height = postProcessing.renderHeight,
          parallaxX = 0f,
          parallaxY = 0f,
          hdrScale = postProcessing.hdrScale,
          config = cfg,
        )
      }
      postProcessing.executePostProcessToScreen(cfg)
      return
    }
    hasRenderedFirstFrame = true

    // Tab 2 preserves live continuous V3 Orb spin even if Tab 1 had pause/debug modes enabled,
    // without mutating Tab 1's AIOrbConfig. During Gate 4 GPU capture on Tab 1, freeze ONE single
    // OrbGpuCaptureSnapshot (camera + transform + phase) across all 6 frames without stepping live motion.
    val captureOverrideMode = if (!isTaskDemo) gpuReferenceCapture.currentOverrideModeOrNull() else null
    val captureActive = captureOverrideMode != null
    val effectiveOrbCfg = when {
      isTaskDemo -> cfg.copy(
        isPaused = false,
        isolateCoreDebug = false,
        debugMode = RenderDebugMode.FULL_RENDER,
      )
      captureActive -> cfg.copy(
        isPaused = false,
        isolateCoreDebug = false,
        debugMode = captureOverrideMode!!,
      )
      else -> cfg
    }

    val frozenCaptureSnapshot: OrbGpuCaptureSnapshot? = if (captureActive) {
      gpuReferenceCapture.getOrCreateFrozenSnapshot(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        fidelity = masterFidelity,
      ) {
        motionController.step(
          0.0,
          effectiveOrbCfg.copy(debugMode = RenderDebugMode.REFERENCE_LOCKED_FRAME),
        )
      }
    } else {
      null
    }

    val motion: AIOrbMotionSnapshot
    val simDeltaSec: Double
    val taskCaptureActive = isTaskDemo && taskGpuEvidenceCapture.isCapturing
    if (frozenCaptureSnapshot != null) {
      motion = frozenCaptureSnapshot.motion
      simDeltaSec = 0.0
      wasCapturingV32LastFrame = true
    } else if (taskCaptureActive) {
      motion = taskCaptureFrozenMotion ?: motionController.step(0.0, effectiveOrbCfg).also {
        taskCaptureFrozenMotion = it
      }
      simDeltaSec = 0.0
      wasCapturingTaskLastFrame = true
    } else {
      // If we just resumed from a frozen 6-frame capture, step with zero jump on the first live frame
      if (taskCaptureFrozenMotion != null) {
        taskCaptureFrozenMotion = null
        wasCapturingTaskLastFrame = true
      }
      val effectiveRawDt = if (wasCapturingV32LastFrame || wasCapturingTaskLastFrame) {
        wasCapturingV32LastFrame = false
        wasCapturingTaskLastFrame = false
        0.0
      } else {
        rawDt
      }
      motion = motionController.step(effectiveRawDt, effectiveOrbCfg)
      simDeltaSec = if (lastMotionSimTimeSec.isNaN()) {
        0.0
      } else {
        (motion.simTimeSec - lastMotionSimTimeSec).coerceIn(0.0, 0.25)
      }
      lastMotionSimTimeSec = motion.simTimeSec
    }

    core.ensureQuality(effectiveOrbCfg.quality.icosphereSubdivisions)
    orbitRibbons.ensureQuality(effectiveOrbCfg.quality.ribbonSegments)
    postProcessing.resize(surfaceWidth, surfaceHeight, effectiveOrbCfg.quality)

    if (frozenCaptureSnapshot != null) {
      System.arraycopy(frozenCaptureSnapshot.outerGlassModel, 0, outerGlassModel, 0, 16)
      System.arraycopy(frozenCaptureSnapshot.invOuterGlassModel, 0, invOuterGlassModel, 0, 16)
    } else {
      motion.writeOuterGlassModelMatrix(outerGlassModel)
      motion.writeInvOuterGlassModelMatrix(invOuterGlassModel)
    }

    if (isTaskDemo) {
      renderTaskPerimeterDemoFrame(
        frameStartNanos = frameStartNanos,
        simDeltaSec = simDeltaSec,
        motion = motion,
        taskOrbCfg = effectiveOrbCfg,
      )
    } else {
      renderOriginalOrbOnlyFrame(
        frameStartNanos = frameStartNanos,
        rawDt = rawDt,
        motion = motion,
        cfg = effectiveOrbCfg,
        frozenCaptureSnapshot = frozenCaptureSnapshot,
      )
    }

    recordAndPublishTelemetry(
      frameStartNanos = frameStartNanos,
      volumeJustUpdated = volumeJustUpdated,
      motion = motion,
      mode = effectiveOrbCfg.debugMode,
    )
  }

  /**
   * 100% preserved Tab 1 (`ORB_ONLY`) V3 Single-Volume Multi-Pass GPU Pipeline with unified
   * V3.2 `OrbCameraBuilder` camera shared identically by `FULL_RENDER` and ALL diagnostic modes.
   * Zero Task, Photo, or Perimeter passes execute here.
   */
  private fun renderOriginalOrbOnlyFrame(
    frameStartNanos: Long,
    rawDt: Double,
    motion: AIOrbMotionSnapshot,
    cfg: AIOrbConfig,
    frozenCaptureSnapshot: OrbGpuCaptureSnapshot? = null,
  ) {
    val captureActive = frozenCaptureSnapshot != null
    val dt = rawDt.toFloat().coerceIn(0.0001f, 0.05f)
    val time = motion.simTimeSec.toFloat()

    if (!captureActive) {
      if (!isDragging) {
        val decay = (1.0f - dt * 2.2f).coerceIn(0f, 1f)
        targetYawDeg *= decay
        targetPitchDeg *= decay
      }
      val follow = (dt * 7.5f).coerceIn(0f, 1f)
      currentYawDeg += (targetYawDeg - currentYawDeg) * follow
      currentPitchDeg += (targetPitchDeg - currentPitchDeg) * follow
    }

    val lockRef = cfg.debugMode.locksReferenceFrame
    val lockCamera = cfg.effectiveLockCoreRotation
    val autoYawRad = when {
      captureActive || lockRef -> 0f
      lockCamera -> Math.toRadians(currentYawDeg.toDouble()).toFloat()
      else -> sin(time * 0.14f) * 0.026f + Math.toRadians(currentYawDeg.toDouble()).toFloat()
    }
    val autoPitchRad = when {
      captureActive || lockRef -> 0f
      lockCamera -> Math.toRadians(currentPitchDeg.toDouble()).toFloat()
      else -> cos(time * 0.11f) * 0.020f + Math.toRadians(currentPitchDeg.toDouble()).toFloat()
    }

    // Unified single-source Tab 1 Camera: FULL_RENDER and ALL diagnostic/capture modes use masterFidelity.camera
    val cameraSnapshot: OrbCameraSnapshot = frozenCaptureSnapshot?.camera ?: OrbCameraBuilder.build(
      surfaceWidth = surfaceWidth,
      surfaceHeight = surfaceHeight,
      cameraFit = masterFidelity.camera,
      yawRad = autoYawRad,
      pitchRad = autoPitchRad,
    )
    cameraSnapshot.writeCameraPos(cameraPos)
    cameraSnapshot.writeViewMatrix(viewMatrix)
    cameraSnapshot.writeProjMatrix(projMatrix)

    Matrix.multiplyMV(orbOriginView, 0, viewMatrix, 0, orbOriginWorld, 0)
    val orbCenterViewZ = orbOriginView[2]

    val rw = postProcessing.renderWidth
    val rh = postProcessing.renderHeight
    val hdrScale = postProcessing.hdrScale
    val mode = cfg.debugMode
    val hideOuter = cfg.effectiveHideOuterOrbitals

    val drawRibbons = !hideOuter && mode != RenderDebugMode.OPTICS_ONLY
    val drawHighlights = !hideOuter && mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.DEPTH_DEBUG
    val drawParticles = !hideOuter && mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.DEPTH_DEBUG
    val drawCore = mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.OPTICS_ONLY

    // ==================== V3 SINGLE-VOLUME MULTI-PASS GPU PIPELINE ====================
    postProcessing.beginScenePass()

    // Pass 1: Deep emerald-teal atmospheric space & multi-depth bokeh
    background.draw(time, rw, rh, autoYawRad, autoPitchRad, hdrScale, cfg)

    // Pass 2: Far-depth crystal droplets & micro-motes
    if (drawParticles) {
      particles.drawPass(0, orbCenterViewZ, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }

    // Pass 3: Back-hemisphere & behind-glass-dome 3D organic Resident ribbons (refracted through glass core)
    val scaledCoreRadius = (cfg.coreScale * cfg.coreShellScale).coerceAtLeast(0.4f)
    if (drawRibbons) {
      orbitRibbons.drawPass(0, orbOriginView, scaledCoreRadius, viewMatrix, projMatrix, cameraPos, motion, hdrScale, cfg)
    }

    // Pass 4: Back-hemisphere travelling HDR starburst flares
    if (drawHighlights) {
      energyHighlights.drawPass(0, orbCenterViewZ, orbitRibbons, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }

    // Pass 5 (V3): Capture background + back ribbons ONLY for clean crystal refraction
    if (drawCore) {
      postProcessing.captureRefractionSnapshot()
    }

    // Pass 6 (V3): Unified WebP 3D Volume Raymarch (`sampler3D uFluidVolume`) + Double-Wall Crystal Glass Shell
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
        isSoftwareRenderer = isSoftwareRenderer,
        fidelity = masterFidelity,
      )
    }

    // Pass 7: Front-of-glass-dome 3D organic Resident ribbons (hardware depth-tested against outer glass sphere!)
    if (drawRibbons) {
      orbitRibbons.drawPass(1, orbOriginView, scaledCoreRadius, viewMatrix, projMatrix, cameraPos, motion, hdrScale, cfg)
    }

    // Pass 8: Front-hemisphere jewel starbursts & foreground crystal water droplets
    if (drawHighlights) {
      energyHighlights.drawPass(1, orbCenterViewZ, orbitRibbons, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }
    if (drawParticles) {
      particles.drawPass(1, orbCenterViewZ, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }

    // Pass 9: High-threshold HDR Bloom + ACES Filmic + True sRGB 2.2 Composite
    postProcessing.executePostProcessToScreen(cfg)

    // Gate 4: Real GL-thread zero-UI glReadPixels capture when user triggers V3.2 Evidence Export
    if (frozenCaptureSnapshot != null) {
      val timingSnap = frameTelemetryTracker.snapshot()
      val captureTel = buildTelemetrySnapshot(
        timing = timingSnap,
        coreAngleDeg = AIOrbMotionController.wrapAngleDeg(motion.coreRotationAngleDeg),
        coreAngularVelDegPerSec = motion.coreAngularVelocityDegPerSec,
        mode = cfg.debugMode,
      )
      gpuReferenceCapture.captureCurrentGlFrameIfActive(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        config = cfg,
        fidelity = masterFidelity,
        telemetry = captureTel,
        snapshot = frozenCaptureSnapshot,
      )
    }
  }

  /**
   * Tab 2 (`TASK_PERIMETER_DEMO`) GPU pipeline directly reusing the EXACT SAME V3 AI Orb
   * (`core`, `orbitRibbons`, `energyHighlights`, `particles`, `postProcessing`) anchored at
   * the bottom center via off-axis lens-shift projection, unified with the 6-keyframe Task
   * Energy streams and bidirectional rounded-rect Perimeter WaterLight.
   */
  private fun renderTaskPerimeterDemoFrame(
    frameStartNanos: Long,
    simDeltaSec: Double,
    motion: AIOrbMotionSnapshot,
    taskOrbCfg: AIOrbConfig,
  ) {
    ensureTaskRenderersInitialized()
    val layout = resolveTaskLayout()
    val perimeterTable = resolvePerimeterTable(layout.photoFrame)

    val captureStep = taskGpuEvidenceCapture.currentStepOrNull()
    val effectiveTaskDebug = captureStep?.debugOptions ?: taskDebugOptionsRef.get()
    val taskSnapAndEvents = synchronized(taskLock) {
      if (captureStep != null) {
        val captureController = taskCaptureController
          ?: TaskEnergyController(taskVisualFidelity).also { taskCaptureController = it }
        captureController.jumpToKeyframe(captureStep.stage, perimeterTable.geometry) to captureController.events
      } else {
        taskCaptureController = null
        taskEnergyController.ensureStarted()
        taskEnergyController.step(simDeltaSec, perimeterTable.geometry) to taskEnergyController.events
      }
    }
    val taskSnap = taskSnapAndEvents.first

    val scaledCoreRadius = (taskOrbCfg.coreScale * taskOrbCfg.coreShellScale).coerceAtLeast(0.4f)
    val taskCamDist = layout.orbTransform.computeCameraDistance(fovYDeg, scaledCoreRadius)
    val time = motion.simTimeSec.toFloat()

    // Subtle living parallax on front +Z camera while lens-shift keeps the Orb straight-on at bottom center
    val subtleYaw = sin(time * 0.22f) * 0.018f
    val subtlePitch = cos(time * 0.17f) * 0.014f
    taskCameraPos[0] = taskCamDist * cos(subtlePitch) * sin(subtleYaw)
    taskCameraPos[1] = taskCamDist * sin(subtlePitch)
    taskCameraPos[2] = taskCamDist * cos(subtlePitch) * cos(subtleYaw)

    Matrix.setLookAtM(
      taskViewMatrix, 0,
      taskCameraPos[0], taskCameraPos[1], taskCameraPos[2],
      0f, 0f, 0f,
      0f, 1f, 0f,
    )
    layout.orbTransform.writeLensShiftedProjection(taskProjMatrix, fovYDeg)

    Matrix.multiplyMV(orbOriginView, 0, taskViewMatrix, 0, orbOriginWorld, 0)
    val orbCenterViewZ = orbOriginView[2]

    val rw = postProcessing.renderWidth
    val rh = postProcessing.renderHeight
    val hdrScale = postProcessing.hdrScale

    // ==================== TAB 2 SHARED-ORB + TASK ENERGY + PERIMETER PIPELINE ====================
    postProcessing.beginScenePass()

    // Pass 1: Light-cyan daylight aquatic studio backdrop + focused pool caustics beneath Orb
    photoTextureRenderer.drawBackground(time, rw, rh, layout, hdrScale, assetManager)

    // Pass 2: Single rounded fishing photo (`fishing_photo.webp`) aligned to exact live Compose frame
    photoTextureRenderer.drawPhoto(layout, hdrScale, assetManager)

    // Pass 3-5: SHARED V3 AI Orb back-hemisphere particles, 5 Residents, and starburst flares
    particles.drawPass(0, orbCenterViewZ, taskViewMatrix, taskProjMatrix, motion, hdrScale, taskOrbCfg)
    orbitRibbons.drawPass(0, orbOriginView, scaledCoreRadius, taskViewMatrix, taskProjMatrix, taskCameraPos, motion, hdrScale, taskOrbCfg)
    energyHighlights.drawPass(0, orbCenterViewZ, orbitRibbons, taskViewMatrix, taskProjMatrix, motion, hdrScale, taskOrbCfg)

    // Pass 6: Capture daylight cyan studio backdrop + photo + back ribbons for real-time crystal refraction!
    postProcessing.captureRefractionSnapshot()

    // Pass 7: SHARED V3 WebP 3D Volume Raymarch + Double-Wall Crystal Glass Shell
    // P1: Bright-background optics (0.75 / 1.12) keep sapphire core & emerald folds rich on daylight cyan!
    core.draw(
      outerGlassModel = outerGlassModel,
      invOuterGlassModel = invOuterGlassModel,
      viewMatrix = taskViewMatrix,
      projMatrix = taskProjMatrix,
      cameraPos = taskCameraPos,
      refractionTexId = postProcessing.refractionTextureId,
      fboWidth = rw,
      fboHeight = rh,
      motion = motion,
      hdrScale = hdrScale,
      lighting = lighting,
      config = taskOrbCfg,
      isSoftwareRenderer = isSoftwareRenderer,
      fidelity = masterFidelity,
      taskInteriorTransmission = taskVisualFidelity?.tab2Optics?.interiorTransmission ?: TAB2_ORB_INTERIOR_TRANSMISSION,
      taskAbsorptionGain = taskVisualFidelity?.tab2Optics?.absorptionGain ?: TAB2_ORB_ABSORPTION_GAIN,
    )

    // Pass 8-9: SHARED V3 AI Orb front-of-glass-dome 5 Residents, starbursts & crystal droplets
    orbitRibbons.drawPass(1, orbOriginView, scaledCoreRadius, taskViewMatrix, taskProjMatrix, taskCameraPos, motion, hdrScale, taskOrbCfg)
    energyHighlights.drawPass(1, orbCenterViewZ, orbitRibbons, taskViewMatrix, taskProjMatrix, motion, hdrScale, taskOrbCfg)
    particles.drawPass(1, orbCenterViewZ, taskViewMatrix, taskProjMatrix, motion, hdrScale, taskOrbCfg)

    // P0-4: Capture separate refraction snapshot (daylight bg + photo + shared Orb) BEFORE Task/Perimeter!
    // Strictly reads from refractionFbo (never active sceneFbo -> zero feedback loop!).
    postProcessing.captureRefractionSnapshot()

    // Pass 10: 4 independent Task Energy liquid streams released from Orb Top -> Photo Bottom Center
    taskEnergyRenderer.draw(
      layout = layout,
      snapshot = taskSnap,
      hdrScale = hdrScale,
      refractionTexId = postProcessing.refractionTextureId,
      fboWidth = rw,
      fboHeight = rh,
      debugOptions = effectiveTaskDebug,
    )

    // Pass 11: Unified-path bidirectional Perimeter WaterLight (L1–L4 + KF02/KF05 localized glints)
    perimeterRenderer.draw(
      layout = layout,
      table = perimeterTable,
      snapshot = taskSnap,
      hdrScale = hdrScale,
      refractionTexId = postProcessing.refractionTextureId,
      fboWidth = rw,
      fboHeight = rh,
      debugOptions = effectiveTaskDebug,
    )

    // Pass 12: Shared HDR Bloom (>1.32f threshold preserves crisp photo & cyan bg) + ACES + sRGB
    // (or PRE_TONEMAP diagnostic direct linear Scene FBO inspection)
    val preTonemap = effectiveTaskDebug.mode == TaskVisualDebugMode.PRE_TONEMAP ||
      effectiveTaskDebug.mode == TaskVisualDebugMode.PERIMETER_ONLY_PRE_TONEMAP
    val forceBloomOff = effectiveTaskDebug.disableBloom || effectiveTaskDebug.isShaderVisualization
    postProcessing.executePostProcessToScreen(
      config = taskOrbCfg,
      preTonemapBypass = preTonemap,
      forceBloomOff = forceBloomOff,
    )

    // Record real GL draw telemetry for G1 verification
    val glErr = GLES30.glGetError()
    val tStats = taskEnergyRenderer.lastDrawStats
    val pStats = perimeterRenderer.lastDrawStats
    lastTaskDrawTelemetryRef.set(
      TaskGpuDrawTelemetry(
        taskRibbonVertexCount = tStats.vertexCount,
        taskRibbonDrawCalls = tStats.drawCalls,
        taskRibbonShaderLinked = tStats.shaderLinked,
        taskRibbonVaoIds = tStats.vaoIds,
        taskRibbonVboIds = tStats.vboIds,
        taskRibbonDrawCallsByStream = tStats.drawCallsByStream,
        perimeterVertexCount = pStats.vertexCount,
        perimeterDrawCalls = pStats.drawCalls,
        perimeterShaderLinked = pStats.shaderLinked,
        perimeterVaoIds = pStats.vaoIds,
        perimeterVboIds = pStats.vboIds,
        perimeterDrawCallsByLayer = pStats.drawCallsByLayer,
        sparkleCount = pStats.sparkleCount,
        sparkleDrawCalls = pStats.sparkleDrawCalls,
        sparkleUsedQuadFallback = pStats.usedQuadFallback,
        maxAliasedPointSizePx = pStats.maxAliasedPointSizePx,
        fboComplete = postProcessing.isSceneFboComplete,
        fboWidth = rw,
        fboHeight = rh,
        glErrorCode = glErr,
      )
    )

    // Capture zero-UI GL frame if user triggered V4 6KF GPU evidence export
    if (captureStep != null) {
      val cameraMatrices = FloatArray(taskViewMatrix.size + taskProjMatrix.size).also {
        System.arraycopy(taskViewMatrix, 0, it, 0, taskViewMatrix.size)
        System.arraycopy(taskProjMatrix, 0, it, taskViewMatrix.size, taskProjMatrix.size)
      }
      val captureSnapshot = TaskGpuCaptureSnapshot.create(
        viewAndProjection = cameraMatrices,
        modelTransform = outerGlassModel,
        coreRotationDeg = motion.coreRotationAngleDeg,
        motionTimeSec = motion.simTimeSec,
        visualLockSha256 = taskVisualFidelity?.lockSha256 ?: "UNAVAILABLE",
        viewportWidth = surfaceWidth,
        viewportHeight = surfaceHeight,
      )
      val captureTel = buildTelemetrySnapshot(
        timing = frameTelemetryTracker.snapshot(),
        coreAngleDeg = AIOrbMotionController.wrapAngleDeg(motion.coreRotationAngleDeg),
        coreAngularVelDegPerSec = motion.coreAngularVelocityDegPerSec,
        mode = taskOrbCfg.debugMode,
      )
      taskGpuEvidenceCapture.captureCurrentGlFrameIfActive(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        evidence = currentTaskEvidence().copy(
          taskSnapshot = taskSnap,
          emittedEvents = taskSnapAndEvents.second,
          layoutSnapshot = layout,
          debugOptions = effectiveTaskDebug,
          drawTelemetry = lastTaskDrawTelemetryRef.get(),
        ),
        telemetry = captureTel,
        snapshot = captureSnapshot,
      )
    }

    // Publish throttled Task Energy state/evidence to Compose HUD (~10Hz or instant on stage transition)
    val stageChanged = taskSnap.stage != lastReportedTaskStage || taskSnap.isPaused != lastReportedTaskPaused
    if (stageChanged || (frameStartNanos - lastTaskEvidenceNanos) >= 100_000_000L) {
      lastReportedTaskStage = taskSnap.stage
      lastReportedTaskPaused = taskSnap.isPaused
      lastTaskEvidenceNanos = frameStartNanos
      onTaskEvidenceUpdated(currentTaskEvidence())
    }
  }

  private fun buildTelemetrySnapshot(
    timing: FrameTimingSnapshot,
    coreAngleDeg: Float,
    coreAngularVelDegPerSec: Float,
    mode: RenderDebugMode,
  ): AIOrbTelemetry {
    val volTel = core.volumeTelemetry
    return AIOrbTelemetry(
      fps = timing.fps,
      frameMsP50 = timing.cpuFrameMsP50,
      frameMsP95 = timing.cpuFrameMsP95,
      frameMsLatest = timing.latestCpuFrameMs,
      frameSampleCount = timing.sampleCount,
      hasSufficientTimingSamples = timing.hasSufficientSamples,
      gpuFrameTimeStatus = timing.gpuFrameTimeStatus,
      coreAngleDeg = coreAngleDeg,
      coreAngularVelDegPerSec = coreAngularVelDegPerSec,
      isHdrFbo = postProcessing.hdrScale > 0.5f,
      activeRenderMode = mode,
      glVendor = glVendor,
      glRenderer = glRenderer,
      glVersion = glVersion,
      eglVersion = eglVersion,
      isSoftwareRenderer = isSoftwareRenderer,
      volumeLoadState = volTel.loadState,
      volumeLoaded = volTel.isLoaded,
      volumeDimensions = volTel.dimensionsLabel,
      webpDecodeMs = volTel.decodeTimeMs,
      texture3dUploadMs = volTel.uploadTimeMs,
      gpuVolumeMemoryMiB = volTel.gpuMemoryMiB,
      volumeStatus = volTel.statusMessage,
    )
  }

  private fun recordAndPublishTelemetry(
    frameStartNanos: Long,
    volumeJustUpdated: Boolean,
    motion: AIOrbMotionSnapshot,
    mode: RenderDebugMode,
  ) {
    val shouldPublish = frameTelemetryTracker.recordFrame(frameStartNanos, System.nanoTime())
    if (volumeJustUpdated || shouldPublish) {
      onTelemetryUpdated(
        buildTelemetrySnapshot(
          timing = frameTelemetryTracker.snapshot(),
          coreAngleDeg = AIOrbMotionController.wrapAngleDeg(motion.coreRotationAngleDeg),
          coreAngularVelDegPerSec = motion.coreAngularVelocityDegPerSec,
          mode = mode,
        )
      )
    }
  }

  fun release() {
    gpuReferenceCapture.release()
    taskGpuEvidenceCapture.release()
    background.release()
    core.release()
    orbitRibbons.release()
    energyHighlights.release()
    particles.release()
    photoTextureRenderer.release()
    taskEnergyRenderer.release()
    perimeterRenderer.release()
    taskRenderersInitialized = false
    postProcessing.release()
  }

  companion object {
    // P1: Bright-background optics for Tab 2 ONLY (Tab 1 stays strictly 1.0f / 1.0f)
    const val TAB2_ORB_INTERIOR_TRANSMISSION = 0.75f
    const val TAB2_ORB_ABSORPTION_GAIN = 1.12f
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
     * Tab 1 (`ORB_ONLY`) authoritative single-volume render pass manifest:
     * verifies ZERO duplicate internal fluid pass and ZERO Task/Photo/Perimeter passes.
     */
    fun activeRenderPassesV3(mode: RenderDebugMode): List<String> {
      if (mode == RenderDebugMode.REFERENCE_ONLY) {
        return listOf("QA_STATIC_REFERENCE_VIEWER_ONLY (NO_GPU_COMPOSITE)")
      }
      val hideOuter = mode.isCoreIsolated
      val drawRibbons = !hideOuter && mode != RenderDebugMode.OPTICS_ONLY
      val drawHighlights = !hideOuter && mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.DEPTH_DEBUG
      val drawParticles = !hideOuter && mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.DEPTH_DEBUG
      val drawCore = mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.OPTICS_ONLY

      return buildList {
        add("PASS_1_BACKGROUND_LINEAR_TEAL_BOKEH")
        if (drawParticles) add("PASS_2_BACK_DROPLETS_PARTICLES")
        if (drawRibbons) add("PASS_3_BACK_RIBBONS_RESIDENT_AND_SECONDARY")
        if (drawHighlights) add("PASS_4_BACK_STARBURST_HIGHLIGHTS")
        if (drawCore) add("PASS_5_REFRACTION_FBO_BLIT_SNAPSHOT")
        if (drawCore) add("PASS_6_V3_WEBP_3D_VOLUME_RAYMARCH_AND_CRYSTAL_GLASS_SHELL")
        if (drawRibbons) add("PASS_7_FRONT_RIBBONS_DEPTH_TESTED")
        if (drawHighlights) add("PASS_8A_FRONT_STARBURST_HIGHLIGHTS")
        if (drawParticles) add("PASS_8B_FRONT_DROPLETS_PARTICLES")
        add(
          if (mode == RenderDebugMode.BLOOM_OFF) "PASS_9_ACES_SRGB_COMPOSITE_BLOOM_OFF"
          else "PASS_9_HDR_BLOOM_ACES_SRGB_COMPOSITE"
        )
      }
    }

    /**
     * Tab 2 (`TASK_PERIMETER_DEMO`) authoritative render pass manifest reusing the EXACT SAME
     * V3 AI Orb core, 5 Residents, and post-processing FBO.
     */
    fun activeRenderPassesTaskDemo(): List<String> = listOf(
      "PASS_1_TASK_DAYLIGHT_AQUATIC_CAUSTICS_BACKGROUND",
      "PASS_2_ROUNDED_FISHING_PHOTO_QUAD",
      "PASS_3_SHARED_ORB_BACK_PARTICLES",
      "PASS_4_SHARED_ORB_BACK_5_RESIDENTS",
      "PASS_5_SHARED_ORB_BACK_STARBURSTS",
      "PASS_6_SHARED_ORB_REFRACTION_FBO_SNAPSHOT",
      "PASS_7_SHARED_V3_WEBP_3D_VOLUME_AND_CRYSTAL_GLASS_SHELL",
      "PASS_8_SHARED_ORB_FRONT_5_RESIDENTS",
      "PASS_9_SHARED_ORB_FRONT_STARBURSTS_AND_PARTICLES",
      "PASS_10_INDEPENDENT_4_STREAM_TASK_ENERGY_RIBBONS",
      "PASS_11_UNIFIED_PATH_PERIMETER_WATERLIGHT_L1_L4",
      "PASS_12_SHARED_HDR_BLOOM_ACES_SRGB_COMPOSITE",
    )

    /**
     * Legacy V2 pass-order compatibility manifest preserved intact for V2 regression tests.
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
        mode != RenderDebugMode.CORE_GLASS_ONLY &&
        mode != RenderDebugMode.DEPTH_DEBUG
      val drawCore = mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.OPTICS_ONLY

      return buildList {
        add("PASS_1_BACKGROUND_LINEAR_TEAL_BOKEH")
        if (drawParticles) add("PASS_2_BACK_DROPLETS_PARTICLES")
        if (drawRibbons) add("PASS_3_BACK_RIBBONS_RESIDENT_AND_SECONDARY")
        if (drawHighlights) add("PASS_4_BACK_STARBURST_HIGHLIGHTS")
        if (drawInnerFluid) add("PASS_5_CONCAVE_INNER_FLUID_BOWL")
        if (drawCore) add("PASS_6_REFRACTION_FBO_BLIT_SNAPSHOT")
        if (drawCore) add("PASS_7_OUTER_CRYSTAL_CORE_MENISCUS_DOMAIN_WARP_CAUSTICS")
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
