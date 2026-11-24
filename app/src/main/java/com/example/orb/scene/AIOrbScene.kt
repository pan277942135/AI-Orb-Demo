package com.example.orb.scene

import android.content.res.AssetManager
import android.opengl.EGL14
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.BuildConfig
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.OrbCoreMode
import com.example.orb.config.OrbSceneMode
import com.example.orb.config.RenderDebugMode
import com.example.orb.task.PerimeterArcLengthTable
import com.example.orb.task.PerimeterPathGeometry
import com.example.orb.task.PerimeterWaterLightRenderer
import com.example.orb.task.PhotoFrameGeometry
import com.example.orb.task.PhotoTextureRenderer
import com.example.orb.task.TaskEnergyController
import com.example.orb.task.TaskEnergyEvidence
import com.example.orb.task.TaskEnergyRibbonRenderer
import com.example.orb.task.TaskEnergySnapshot
import com.example.orb.task.TaskEnergyStage
import com.example.orb.task.TaskGpuCaptureProgress
import com.example.orb.task.TaskGpuDrawTelemetry
import com.example.orb.task.TaskGpuEvidenceCapture
import com.example.orb.task.TaskRenderDebugOptions
import com.example.orb.task.TaskSceneLayoutSnapshot
import com.example.orb.task.TaskVisualDebugMode
import com.example.orb.v3.GpuCaptureProgress
import com.example.orb.v3.MasterFidelityV32
import com.example.orb.v3.OrbCameraBuilder
import com.example.orb.v3.OrbCameraSnapshot
import com.example.orb.v3.OrbGpuCaptureSnapshot
import com.example.orb.v3.OrbGpuReferenceCapture
import com.example.orb.v3.OrbV3LoadState
import com.example.orb.outer.OuterOpticalFieldRenderer
import com.example.orb.yujian.core.YuJianCoreFrameInput
import com.example.orb.yujian.core.YuJianCoreRenderer
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
  val buildSha: String = BuildConfig.GIT_SHA,
  val apkSha256: String? = null,
  val shaderProgramId: Int = 0,
  val shaderSourceSha256: String = "UNAVAILABLE",
  val qualityTier: String = "UNKNOWN",
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
  val webpDecodeMs: Float = 0f,
  val texture3dUploadMs: Float = 0f,
  val gpuVolumeMemoryMiB: Float = 0f,
  val volumeStatus: String = "Pending V3 WebP load",
  val coreRendererMode: OrbCoreMode = OrbCoreMode.NATIVE_V32,
  val yujianShaderProgramId: Int = 0,
  val yujianShaderSourceSha256: String = "UNAVAILABLE",
  val yujianCoreStatus: String = "NOT_INITIALIZED",
  val yujianCoreRenderResolution: String = "0x0",
  val yujianCoreFrameCount: Long = 0L,
  val yujianCoreContextRebuildCount: Int = 0,
  val yujianCoreGlError: String = "GL_NO_ERROR",
  val yujianCoreGlErrorPass: String = "NONE",
  val yujianCoreUniformsReadBackValid: Boolean = false,
  val yujianCoreRefractionTextureId: Int = 0,
  val yujianCoreBoundTextureId: Int = 0,
  val yujianCoreSamplerTextureUnit: Int = -1,
  val yujianCoreHdrScale: Float = Float.NaN,
  val yujianCoreElapsedTimeSec: Float = 0f,
  val yujianCoreProjectedCenterNdc: String = "UNAVAILABLE",
  val yujianCoreProjectedRadiiNdc: String = "UNAVAILABLE",
  val yujianDrawFramebufferId: Int = 0,
  val yujianDrawColorAttachmentTextureId: Int = 0,
  val yujianDrawFramebufferComplete: Boolean = false,
  val yujianNoFramebufferFeedbackLoop: Boolean = false,
  val yujianPassStateReadBackValid: Boolean = false,
  val sceneFramebufferComplete: Boolean = false,
  val refractionTextureId: Int = 0,
  val outerOpticalStatus: String = "NOT_INITIALIZED",
  val outerOpticalManifestSha256: String = "UNAVAILABLE",
  val outerOpticalShaderSourceSha256: String = "UNAVAILABLE",
  val outerOpticalRibbonProgramId: Int = 0,
  val outerOpticalPointProgramId: Int = 0,
  val outerOpticalTraceCount: Int = 0,
  val outerOpticalSurfaceCount: Int = 0,
  val outerOpticalFilamentCount: Int = 0,
  val outerOpticalHighlightCount: Int = 0,
  val outerOpticalDropletCount: Int = 0,
  val outerOpticalParticleCount: Int = 0,
  val outerOpticalBackVertexCount: Int = 0,
  val outerOpticalFrontVertexCount: Int = 0,
  val outerOpticalFramebufferId: Int = 0,
  val outerOpticalRefractionTextureId: Int = 0,
  val outerOpticalGlError: String = "GL_NO_ERROR",
  val outerOpticalGlErrorPass: String = "NONE",
  val outerOpticalUniformReadbackValid: Boolean = false,
) {
  val fboFormatLabel: String get() = if (isHdrFbo) "RGBA16F (HDR)" else "RGBA8 (Linear-HDR 0.25x)"
  val renderingBackendLabel: String get() = if (isSoftwareRenderer) "SOFTWARE RENDERING" else "HARDWARE GPU"
}

/**
 * Unified OpenGL ES 3.0 scene renderer shared across Tab 1, Tab 2, and Tab 3.
 *
 * - Tab 1 (`ORB_ONLY`): 100% preserves the frozen V3 WebP-First 3D Volume Hybrid Rendering pipeline,
 *   double-wall crystal glass shell, independent 26°/s Core Rotation, 5 frozen Residents, and all
 *   QA diagnostic modes with ZERO Task/Photo/Perimeter overhead.
 * - Tab 2 (`TASK_PERIMETER_DEMO`): Directly reuses the EXACT SAME [core], [orbitRibbons],
 *   [energyHighlights], [particles], [motionController], and [postProcessing] FBO via off-axis
 *   lens-shift projection, adding the studio backdrop, cropped fishing photo, 4 independent Task
 *   Energy streams, and unified-path 6-keyframe Perimeter WaterLight.
 * - Tab 3 (`YUJIAN_HYBRID`): uses a REF_B-driven outer optical field around the source-extracted
 *   YuJian Core shader in this same GL context; Tab 1 and Tab 2 retain their own pass paths.
 */
class AIOrbScene(
  initialConfig: AIOrbConfig = AIOrbConfig.Default,
  private val assetManager: AssetManager? = null,
  private val onTelemetryUpdated: (AIOrbTelemetry) -> Unit = {},
  private val onTaskEvidenceUpdated: (TaskEnergyEvidence) -> Unit = {},
) : GLSurfaceView.Renderer {

  private val configRef = AtomicReference(initialConfig)
  private val telemetryPublishRequested = AtomicBoolean(false)

  // V3.2 Master-Fitted runtime parameters loaded from assets/orb_v3/manifest/MASTER_FIDELITY_V32.json
  val masterFidelity: MasterFidelityV32 = MasterFidelityV32.loadFromAssets(assetManager)
  private val gpuReferenceCapture = OrbGpuReferenceCapture()

  // Shared single-instance V3 AI Orb core, Residents, motion clock & post-processing
  private val motionController = AIOrbMotionController()
  private val lighting = AIOrbLighting()
  private val background = AIOrbBackground()
  private val core = AIOrbCore()
  private val yujianCoreRenderer = YuJianCoreRenderer()
  private val outerOpticalFieldRenderer = OuterOpticalFieldRenderer()
  private val orbitRibbons = AIOrbOrbitRibbons(masterFidelity)
  private val energyHighlights = AIOrbEnergyHighlights()
  private val particles = AIOrbParticles()
  private val postProcessing = AIOrbPostProcessing()

  // Tab 2 additive renderers & deterministic 6KF Task Energy controller (strictly inactive on Tab 1)
  private val photoTextureRenderer = PhotoTextureRenderer()
  private val taskEnergyRenderer = TaskEnergyRibbonRenderer()
  private val perimeterRenderer = PerimeterWaterLightRenderer()
  private val taskEnergyController = TaskEnergyController()
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
  private val apkSha256Ref = AtomicReference<String?>(null)
  private val latestTelemetryRef = AtomicReference(AIOrbTelemetry())

  val glContextInitCount: Int get() = glContextInitCounter.get()
  val webpLoadCount: Int get() = webpLoadCounter.get()
  val tabSwitchCount: Int get() = tabSwitchCounter.get()
  val latestTelemetry: AIOrbTelemetry get() = latestTelemetryRef.get()

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
    if (prev != config) telemetryPublishRequested.set(true)
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
   * Triggers the one-click production GLES 3.0 evidence capture for all required layers
   * directly on the active GL thread (zero Compose UI/status bar).
   */
  fun requestV32EvidenceExport(
    outputDir: File,
    onProgress: (GpuCaptureProgress) -> Unit,
  ): Boolean {
    if (configRef.get().sceneMode != OrbSceneMode.ORB_ONLY) {
      onProgress(GpuCaptureProgress(state = com.example.orb.v3.GpuCaptureState.FAILED, statusMessage = "V4.2 core capture is available only on Tab 1."))
      return false
    }
    if (!core.isVolumeReady) {
      val reason = "GPU_CAPTURE_BLOCKED_${core.volumeTelemetry.loadState.name}: ${core.volumeTelemetry.statusMessage}"
      onProgress(GpuCaptureProgress(state = com.example.orb.v3.GpuCaptureState.FAILED, statusMessage = reason))
      return false
    }
    if (apkSha256Ref.get().isNullOrBlank()) {
      onProgress(GpuCaptureProgress(
        state = com.example.orb.v3.GpuCaptureState.FAILED,
        statusMessage = "GPU_CAPTURE_BLOCKED_APK_FINGERPRINT_PENDING: wait for installed APK SHA-256 telemetry, then retry.",
      ))
      return false
    }
    return gpuReferenceCapture.requestCapture(outputDir) { progress ->
      onProgress(
        if (isSoftwareRenderer && progress.state == com.example.orb.v3.GpuCaptureState.CAPTURING) {
          progress.copy(statusMessage = "SOFTWARE GLES DIAGNOSTIC ONLY — not a hardware GPU fidelity result. ${progress.statusMessage}")
        } else {
          progress
        }
      )
    }
  }

  fun updateApkSha256(value: String) {
    apkSha256Ref.set(value)
  }

  /**
   * Triggers the Tab 2 V4 one-click 16-frame production GLES 3.0 evidence capture
   * directly on the active GL thread (zero Compose UI/status bar).
   */
  fun requestTask6KfEvidenceExport(
    outputDir: File,
    onProgress: (TaskGpuCaptureProgress) -> Unit,
  ): Boolean {
    return taskGpuEvidenceCapture.requestCapture(outputDir, onProgress)
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
      TaskSceneLayoutSnapshot.canonicalFallback(surfaceWidth, surfaceHeight)
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
    val previousContextCount = glContextInitCounter.getAndIncrement()
    if (previousContextCount > 0) {
      // EGL names are context-local. Invalidate the old renderer resources before any
      // renderer allocates new GL names, otherwise a later init/release can delete a
      // newly-created object whose integer happens to match an old-context name.
      gpuReferenceCapture.cancelForContextLoss()
      taskGpuEvidenceCapture.cancelForContextLoss()
      yujianCoreRenderer.onContextLost()
      outerOpticalFieldRenderer.onContextLost()
      background.release()
      core.release()
      core.markGlContextLost()
      orbitRibbons.release()
      energyHighlights.release()
      particles.release()
      photoTextureRenderer.release()
      taskEnergyRenderer.release()
      perimeterRenderer.release()
      postProcessing.release()
    }
    GLES30.glClearColor(0.002f, 0.016f, 0.022f, 1.0f)
    GLES30.glEnable(GLES30.GL_DEPTH_TEST)

    background.init()
    core.init(cfg.quality.icosphereSubdivisions, assetManager)
    orbitRibbons.init(cfg.quality.ribbonSegments)
    energyHighlights.init()
    particles.init()
    postProcessing.init()
    assetManager?.let(yujianCoreRenderer::onSurfaceCreated)

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
    yujianCoreRenderer.onSurfaceChanged(postProcessing.renderWidth, postProcessing.renderHeight)
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
    return TaskSceneLayoutSnapshot.canonicalFallback(sw, sh)
  }

  private fun resolvePerimeterTable(photo: PhotoFrameGeometry): PerimeterArcLengthTable {
    val prev = cachedPerimeterTable
    if (prev != null && cachedPerimeterPhoto == photo) {
      return prev
    }
    val table = PerimeterArcLengthTable(PerimeterPathGeometry(photo))
    cachedPerimeterPhoto = photo
    cachedPerimeterTable = table
    return table
  }

  override fun onDrawFrame(gl: GL10?) {
    val frameStartNanos = System.nanoTime()
    val rawDt = if (lastFrameNanos == 0L) 1.0 / 60.0 else (frameStartNanos - lastFrameNanos) * 1e-9
    lastFrameNanos = frameStartNanos

    val cfg = configRef.get()
    val isYuJianTab3 = cfg.sceneMode == OrbSceneMode.YUJIAN_HYBRID && cfg.coreMode == OrbCoreMode.YUJIAN_CORE
    if (isYuJianTab3 && outerOpticalFieldRenderer.runtimeState.status in setOf("NOT_INITIALIZED", "CONTEXT_LOST", "RELEASED")) {
      // Keep Tab 1 and Tab 2 startup costs and GL resource allocation unchanged. Build the
      // REF_B field on the shared GL thread only when Tab 3 is first rendered or recovers.
      outerOpticalFieldRenderer.onSurfaceCreated(assetManager)
    }
    val volumeJustUpdated = core.uploadReadyAssetsIfNeeded(assetManager)
    if (volumeJustUpdated && core.isVolumeReady) {
      webpLoadCounter.incrementAndGet()
    }

    val isTaskDemo = cfg.sceneMode == OrbSceneMode.TASK_PERIMETER_DEMO

    // Fast first-frame handshake: complete initial eglSwapBuffers() in <15ms so Android's
    // SurfaceSyncGroup(SurfaceView / VRI[MainActivity]) NEVER hits its 1000ms timeout while
    // 35 Lossless RGBA WebP assets finish decoding in the background or SwiftShader warms up.
    // Tab 2 still renders the native V3 core and must preserve its existing asset readiness gate.
    // Only Tab 3's independent YuJian Pass 6 can start without waiting for the Demo 3D volume.
    val needsNativeV32Volume = cfg.coreMode == OrbCoreMode.NATIVE_V32
    if (!hasRenderedFirstFrame && ((needsNativeV32Volume && !core.isVolumeReady) || isSoftwareRenderer)) {
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
      onTelemetryUpdated(buildTelemetrySnapshot(
        timing = frameTelemetryTracker.snapshot(),
        coreAngleDeg = 0f,
        coreAngularVelDegPerSec = 0f,
        mode = cfg.debugMode,
      ))
      return
    }
    hasRenderedFirstFrame = true

    val productionFrameMode = cfg.debugMode == RenderDebugMode.FULL_RENDER ||
      cfg.debugMode == RenderDebugMode.REFERENCE_LOCKED_FRAME ||
      cfg.debugMode == RenderDebugMode.PRE_TONEMAP ||
      cfg.debugMode == RenderDebugMode.POST_TONEMAP ||
      cfg.debugMode == RenderDebugMode.CORE_ONLY_TRANSPARENT
    if (needsNativeV32Volume && productionFrameMode && !core.isVolumeReady) {
      // Production fidelity frames never fall through to the analytic shader fallback.
      // Keep the backdrop responsive and publish LOADING / V3_ASSET_ERROR telemetry.
      postProcessing.resize(surfaceWidth, surfaceHeight, cfg.quality)
      val transparentCore = cfg.debugMode == RenderDebugMode.CORE_ONLY_TRANSPARENT
      postProcessing.beginScenePass(transparentClear = transparentCore)
      if (!transparentCore) {
        background.draw(0f, postProcessing.renderWidth, postProcessing.renderHeight, 0f, 0f, postProcessing.hdrScale, cfg)
      }
      postProcessing.executePostProcessToScreen(cfg, preserveSceneAlpha = transparentCore)
      val pausedMotion = motionController.step(0.0, cfg.copy(isPaused = true))
      recordAndPublishTelemetry(frameStartNanos, volumeJustUpdated, pausedMotion, cfg.debugMode)
      return
    }

    // Tab 2 preserves live continuous V3 Orb spin even if Tab 1 had pause/debug modes enabled,
    // without mutating Tab 1's AIOrbConfig. During the Tab 1 GPU capture, freeze ONE single
    // OrbGpuCaptureSnapshot (camera + transform + phase) across all layers without stepping live motion.
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
    if (frozenCaptureSnapshot != null) {
      motion = frozenCaptureSnapshot.motion
      simDeltaSec = 0.0
      wasCapturingV32LastFrame = true
    } else {
      // If we just resumed from a frozen multi-layer capture, step with zero jump on the first live frame
      val effectiveRawDt = if (wasCapturingV32LastFrame) {
        wasCapturingV32LastFrame = false
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
   * Tab 1 keeps its native V3 single-volume pass path. Tab 3 selects its independent REF_B outer
   * field in this same camera/FBO path. No Tab 2 Task, Photo, or Perimeter pass executes here.
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
    val drawHighlights = !hideOuter && mode != RenderDebugMode.RIBBONS_ONLY &&
      mode != RenderDebugMode.RESIDENTS_ONLY && mode != RenderDebugMode.DEPTH_DEBUG
    val drawParticles = !hideOuter && mode != RenderDebugMode.RIBBONS_ONLY &&
      mode != RenderDebugMode.RESIDENTS_ONLY && mode != RenderDebugMode.DEPTH_DEBUG
    val drawCore = mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.RESIDENTS_ONLY &&
      mode != RenderDebugMode.OPTICS_ONLY
    val isYuJianTab3 = cfg.sceneMode == OrbSceneMode.YUJIAN_HYBRID && cfg.coreMode == OrbCoreMode.YUJIAN_CORE

    // ==================== V3 SINGLE-VOLUME MULTI-PASS GPU PIPELINE ====================
    val transparentCoreCapture = mode == RenderDebugMode.CORE_ONLY_TRANSPARENT
    postProcessing.beginScenePass(transparentClear = transparentCoreCapture)

    // Pass 1: Deep emerald-teal atmospheric space & multi-depth bokeh
    if (mode == RenderDebugMode.FOLD_NORMAL || transparentCoreCapture) {
      GLES30.glClearColor(0f, 0f, 0f, 1f)
      if (transparentCoreCapture) GLES30.glClearColor(0f, 0f, 0f, 0f)
      GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
    } else {
      background.draw(
        time, rw, rh, autoYawRad, autoPitchRad, hdrScale, cfg,
        referenceBokeh = if (isYuJianTab3) outerOpticalFieldRenderer.referenceBokeh else null,
      )
    }

    // Pass 2: Far-depth crystal droplets & micro-motes
    if (drawParticles && !isYuJianTab3) {
      particles.drawPass(0, orbCenterViewZ, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }

    // Pass 3: Back-hemisphere & behind-glass-dome 3D organic Resident ribbons (refracted through glass core)
    val scaledCoreRadius = (cfg.coreScale * cfg.coreShellScale).coerceAtLeast(0.4f)
    if (drawRibbons && !isYuJianTab3) {
      orbitRibbons.drawPass(0, orbOriginView, scaledCoreRadius, viewMatrix, projMatrix, cameraPos, motion, hdrScale, cfg)
    }

    // Pass 4: Back-hemisphere travelling HDR starburst flares
    if (drawHighlights && !isYuJianTab3) {
      energyHighlights.drawPass(0, orbCenterViewZ, orbitRibbons, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }
    if (isYuJianTab3) {
      outerOpticalFieldRenderer.drawBack(
        model = outerGlassModel,
        view = viewMatrix,
        projection = projMatrix,
        width = rw,
        height = rh,
        time = time,
        hdrScale = hdrScale,
        drawSurfaces = drawRibbons,
        drawDetails = drawHighlights || drawParticles,
      )
    }

    // Pass 5 (V3): Capture background + back ribbons ONLY for clean crystal refraction
    if (drawCore) {
      postProcessing.captureRefractionSnapshot()
    }

    // Pass 6: Tab 1 keeps the native V3.2 renderer; Tab 3 substitutes only the YuJian Core-only shader.
    if (drawCore) {
      if (cfg.coreMode == OrbCoreMode.YUJIAN_CORE) {
        yujianCoreRenderer.render(
          YuJianCoreFrameInput(
            modelMatrix = outerGlassModel,
            viewMatrix = viewMatrix,
            projectionMatrix = projMatrix,
            cameraPosition = cameraPos,
            elapsedTimeSec = time,
            deltaTimeSec = dt,
            refractionTextureId = postProcessing.refractionTextureId,
            viewportWidth = rw,
            viewportHeight = rh,
            hdrScale = hdrScale,
            motion = motion,
          )
        )
        if (isYuJianTab3) postProcessing.captureRefractionSnapshot()
      } else {
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
    }

    // Pass 7: Front-of-glass-dome 3D organic Resident ribbons (hardware depth-tested against outer glass sphere!)
    if (drawRibbons && !isYuJianTab3) {
      orbitRibbons.drawPass(1, orbOriginView, scaledCoreRadius, viewMatrix, projMatrix, cameraPos, motion, hdrScale, cfg)
    }

    // Pass 8: Front-hemisphere jewel starbursts & foreground crystal water droplets
    if (drawHighlights && !isYuJianTab3) {
      energyHighlights.drawPass(1, orbCenterViewZ, orbitRibbons, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }
    if (drawParticles && !isYuJianTab3) {
      particles.drawPass(1, orbCenterViewZ, viewMatrix, projMatrix, motion, hdrScale, cfg)
    }
    if (isYuJianTab3) {
      outerOpticalFieldRenderer.drawFront(
        model = outerGlassModel,
        view = viewMatrix,
        projection = projMatrix,
        width = rw,
        height = rh,
        time = time,
        hdrScale = hdrScale,
        refractionTextureId = if (drawCore) postProcessing.refractionTextureId else 0,
        drawSurfaces = drawRibbons,
        drawDetails = drawHighlights || drawParticles,
      )
    }

    // Pass 9: High-threshold HDR Bloom + ACES Filmic + True sRGB 2.2 Composite
    postProcessing.executePostProcessToScreen(
      config = cfg,
      preTonemapBypass = mode == RenderDebugMode.PRE_TONEMAP || mode == RenderDebugMode.FOLD_NORMAL,
      preserveSceneAlpha = transparentCoreCapture,
    )

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
    val taskSnap = synchronized(taskLock) {
      taskEnergyController.ensureStarted()
      if (captureStep != null) {
        taskEnergyController.jumpToKeyframe(captureStep.stage, perimeterTable.geometry)
      } else {
        taskEnergyController.step(simDeltaSec, perimeterTable.geometry)
      }
    }

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
    photoTextureRenderer.drawBackground(time, rw, rh, layout, hdrScale)

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
      taskInteriorTransmission = TAB2_ORB_INTERIOR_TRANSMISSION,
      taskAbsorptionGain = TAB2_ORB_ABSORPTION_GAIN,
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
    val preTonemap = effectiveTaskDebug.mode == TaskVisualDebugMode.PRE_TONEMAP
    val forceBloomOff = effectiveTaskDebug.disableBloom ||
      effectiveTaskDebug.mode == TaskVisualDebugMode.GEOMETRY_MAGENTA ||
      effectiveTaskDebug.mode == TaskVisualDebugMode.ALPHA_HEATMAP
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
        perimeterVertexCount = pStats.vertexCount,
        perimeterDrawCalls = pStats.drawCalls,
        perimeterShaderLinked = pStats.shaderLinked,
        perimeterVaoIds = pStats.vaoIds,
        perimeterVboIds = pStats.vboIds,
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
      val captureTel = buildTelemetrySnapshot(
        timing = frameTelemetryTracker.snapshot(),
        coreAngleDeg = AIOrbMotionController.wrapAngleDeg(motion.coreRotationAngleDeg),
        coreAngularVelDegPerSec = motion.coreAngularVelocityDegPerSec,
        mode = taskOrbCfg.debugMode,
      )
      taskGpuEvidenceCapture.captureCurrentGlFrameIfActive(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        evidence = currentTaskEvidence(),
        telemetry = captureTel,
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
    val yujian = yujianCoreRenderer.runtimeState
    val snapshot = AIOrbTelemetry(
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
      buildSha = BuildConfig.GIT_SHA,
      apkSha256 = apkSha256Ref.get(),
      shaderProgramId = core.runtimeShaderState.programId,
      shaderSourceSha256 = core.runtimeShaderState.shaderSourceSha256,
      qualityTier = core.runtimeShaderState.qualityTier,
      configuredRaymarchSteps = core.runtimeShaderState.configuredRaymarchSteps,
      effectiveRaymarchSteps = core.runtimeShaderState.effectiveRaymarchSteps,
      currentSubdivisions = core.runtimeShaderState.currentSubdivisions,
      geometryStateValid = core.runtimeShaderState.geometryStateValid,
      uVolumeLoaded = core.runtimeShaderState.uVolumeLoaded,
      uDebugMode = core.runtimeShaderState.uDebugMode,
      gpuTextureObjectsValid = core.runtimeShaderState.gpuTextureObjectsValid,
      volumeTextureId = core.runtimeShaderState.volumeTextureId,
      detailTextureId = core.runtimeShaderState.detailTextureId,
      causticsTextureId = core.runtimeShaderState.causticsTextureId,
      glassEnvTextureId = core.runtimeShaderState.glassEnvTextureId,
      volumeInternalFormat = core.runtimeShaderState.volumeInternalFormat,
      volumeIsSrgb = core.runtimeShaderState.volumeIsSrgb,
      raymarchSteps = core.runtimeShaderState.raymarchSteps,
      densityScale = core.runtimeShaderState.densityScale,
      coreTransparency = core.runtimeShaderState.coreTransparency,
      uniformStateValid = core.runtimeShaderState.uniformStateValid,
      foldActiveRatio = core.runtimeShaderState.foldActiveRatio,
      foldActiveRatioStatus = core.runtimeShaderState.foldActiveRatioStatus,
      renderResolution = core.runtimeShaderState.renderResolution,
      geometrySubdivisions = core.runtimeShaderState.geometrySubdivisions,
      geometryVertexCount = core.runtimeShaderState.geometryVertexCount,
      geometryTriangleCount = core.runtimeShaderState.geometryTriangleCount,
      geometryFingerprint = core.runtimeShaderState.geometryFingerprint,
      glError = core.runtimeShaderState.glError,
      glErrorPass = core.runtimeShaderState.glErrorPass,
      webpDecodeMs = volTel.decodeTimeMs,
      texture3dUploadMs = volTel.uploadTimeMs,
      gpuVolumeMemoryMiB = volTel.gpuMemoryMiB,
      volumeStatus = volTel.statusMessage,
      coreRendererMode = configRef.get().coreMode,
      yujianShaderProgramId = yujian.shaderProgramId,
      yujianShaderSourceSha256 = yujian.shaderSourceSha256,
      yujianCoreStatus = yujian.status,
      yujianCoreRenderResolution = yujian.renderResolution,
      yujianCoreFrameCount = yujian.frameCount,
      yujianCoreContextRebuildCount = yujian.contextRebuildCount,
      yujianCoreGlError = yujian.glError,
      yujianCoreGlErrorPass = yujian.glErrorPass,
      yujianCoreUniformsReadBackValid = yujian.uniformsReadBackValid,
      yujianCoreRefractionTextureId = yujian.refractionTextureId,
      yujianCoreBoundTextureId = yujian.boundRefractionTextureId,
      yujianCoreSamplerTextureUnit = yujian.samplerTextureUnit,
      yujianCoreHdrScale = yujian.hdrScale,
      yujianCoreElapsedTimeSec = yujian.elapsedTimeSec,
      yujianCoreProjectedCenterNdc = yujian.projectedCenterNdc,
      yujianCoreProjectedRadiiNdc = yujian.projectedRadiiNdc,
      yujianDrawFramebufferId = yujian.drawFramebufferId,
      yujianDrawColorAttachmentTextureId = yujian.drawColorAttachmentTextureId,
      yujianDrawFramebufferComplete = yujian.drawFramebufferComplete,
      yujianNoFramebufferFeedbackLoop = yujian.noFramebufferFeedbackLoop,
      yujianPassStateReadBackValid = yujian.passStateReadBackValid,
      sceneFramebufferComplete = postProcessing.isSceneFboComplete,
      refractionTextureId = postProcessing.refractionTextureId,
      outerOpticalStatus = outerOpticalFieldRenderer.runtimeState.status,
      outerOpticalManifestSha256 = outerOpticalFieldRenderer.runtimeState.manifestSha256,
      outerOpticalShaderSourceSha256 = outerOpticalFieldRenderer.runtimeState.shaderSourceSha256,
      outerOpticalRibbonProgramId = outerOpticalFieldRenderer.runtimeState.ribbonProgramId,
      outerOpticalPointProgramId = outerOpticalFieldRenderer.runtimeState.pointProgramId,
      outerOpticalTraceCount = outerOpticalFieldRenderer.runtimeState.traceCount,
      outerOpticalSurfaceCount = outerOpticalFieldRenderer.runtimeState.surfaceCount,
      outerOpticalFilamentCount = outerOpticalFieldRenderer.runtimeState.filamentCount,
      outerOpticalHighlightCount = outerOpticalFieldRenderer.runtimeState.highlightCount,
      outerOpticalDropletCount = outerOpticalFieldRenderer.runtimeState.dropletCount,
      outerOpticalParticleCount = outerOpticalFieldRenderer.runtimeState.particleCount,
      outerOpticalBackVertexCount = outerOpticalFieldRenderer.runtimeState.drawnBackVertices,
      outerOpticalFrontVertexCount = outerOpticalFieldRenderer.runtimeState.drawnFrontVertices,
      outerOpticalFramebufferId = outerOpticalFieldRenderer.runtimeState.framebufferId,
      outerOpticalRefractionTextureId = outerOpticalFieldRenderer.runtimeState.refractionTextureId,
      outerOpticalGlError = outerOpticalFieldRenderer.runtimeState.glError,
      outerOpticalGlErrorPass = outerOpticalFieldRenderer.runtimeState.glErrorPass,
      outerOpticalUniformReadbackValid = outerOpticalFieldRenderer.runtimeState.uniformReadbackValid,
    )
    latestTelemetryRef.set(snapshot)
    return snapshot
  }

  private fun recordAndPublishTelemetry(
    frameStartNanos: Long,
    volumeJustUpdated: Boolean,
    motion: AIOrbMotionSnapshot,
    mode: RenderDebugMode,
  ) {
    val shouldPublish = frameTelemetryTracker.recordFrame(frameStartNanos, System.nanoTime())
    if (volumeJustUpdated || shouldPublish || telemetryPublishRequested.getAndSet(false)) {
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
    yujianCoreRenderer.release()
    outerOpticalFieldRenderer.release()
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

    /** Tab 3 uses a REF_B-driven optical field around the source-extracted YuJian Core. */
    fun activeRenderPassesYuJianHybrid(mode: RenderDebugMode): List<String> {
      val hideOuter = mode.isCoreIsolated
      val drawSurfaces = !hideOuter && mode != RenderDebugMode.OPTICS_ONLY
      val drawDetails = !hideOuter && mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.RESIDENTS_ONLY && mode != RenderDebugMode.DEPTH_DEBUG
      val drawCore = mode != RenderDebugMode.RIBBONS_ONLY && mode != RenderDebugMode.RESIDENTS_ONLY && mode != RenderDebugMode.OPTICS_ONLY
      return buildList {
        add("PASS_1_TAB1_BACKGROUND_WITH_REF_B_BOKEH")
        if (drawSurfaces) add("PASS_2_REF_B_BACK_OPTICAL_SURFACES_AND_FILAMENTS")
        if (drawDetails) add("PASS_3_REF_B_BACK_HIGHLIGHTS_PARTICLES_AND_DROPLETS")
        if (drawCore) add("PASS_4_REFRACTION_SNAPSHOT_BEFORE_CORE")
        if (drawCore) add("PASS_5_YUJIAN_CORE_SOURCE_SPHERE_FS")
        if (drawCore) add("PASS_6_REFRACTION_SNAPSHOT_AFTER_CORE")
        if (drawSurfaces) add("PASS_7_REF_B_FRONT_OPTICAL_SURFACES_AND_FILAMENTS")
        if (drawDetails) add("PASS_8_REF_B_FRONT_HIGHLIGHTS_AND_DROPLETS")
        add(if (mode == RenderDebugMode.BLOOM_OFF) "PASS_9_FINAL_COMPOSITE_BLOOM_OFF" else "PASS_9_HDR_BLOOM_ACES_SRGB_COMPOSITE")
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
