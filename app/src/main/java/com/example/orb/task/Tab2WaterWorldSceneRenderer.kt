package com.example.orb.task

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.example.orb.gl.GlShaderProgram
import com.example.orb.v3.CoreVolumeLifecycleState
import com.example.waterworld.WaterWorldAssetStaging
import com.example.waterworld.WaterWorldMotionRuntime
import com.example.waterworld.WaterWorldRenderGraph
import com.example.waterworld.WaterWorldStaticRenderer
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Readiness & health snapshot published from Tab 2's GLES 3.0 Water World runtime to Compose UI.
 */
data class Tab2WaterWorldRuntimeStatus(
  val isReady: Boolean = false,
  val isCoreVolumeReady: Boolean = false,
  val isPlaying: Boolean = false,
  val passCount: Int = 0,
  val opticalNodeCount: Int = 0,
  val errorMessage: String? = null,
  val perimeterState: Tab2PhotoPerimeterState = Tab2PhotoPerimeterState.PHOTO_SELECTED,
  val dynamicTimeSeconds: Double = 0.0,
  val orbViewportSidePx: Int = 0,
)

/**
 * Pure geometry helper computing Tab 2's lower Water World ecosystem square viewport so:
 * - Upper region holds the rounded photo + status caption with generous breathing room
 * - Lower region centers the 1:1 circular Water World ecosystem sphere without distortion,
 *   without clipping at the bottom edge, and without overlapping the photo frame.
 */
data class Tab2OrbViewportRect(
  val leftPx: Int,
  val bottomPx: Int, // OpenGL bottom-left origin
  val sidePx: Int,
  val surfaceWidthPx: Int,
  val surfaceHeightPx: Int,
) {
  val topPxFromTopOrigin: Int get() = surfaceHeightPx - (bottomPx + sidePx)
  val bottomPxFromTopOrigin: Int get() = surfaceHeightPx - bottomPx

  companion object {
    /**
     * Computes the centered lower square viewport for the 1254x1254 Water World ecosystem canvas
     * (whose crystal sphere occupies diameter ~62.7% of the 1254px canvas, leaving room for the
     * 5 orbiting Residents and 9 outer bubbles around the sphere!).
     */
    fun compute(
      surfaceWidthPx: Int,
      surfaceHeightPx: Int,
      photoFrame: PhotoFrameGeometry,
      bottomSafeInsetPx: Float = 0f,
    ): Tab2OrbViewportRect {
      val sw = max(1, surfaceWidthPx)
      val sh = max(1, surfaceHeightPx)
      val captionReservePx = max(44f, sh * 0.042f)
      val topLimit = (photoFrame.bottomPx + captionReservePx).coerceIn(sh * 0.36f, sh * 0.68f)
      val bottomLimit = (sh - bottomSafeInsetPx - sh * 0.012f).coerceAtLeast(topLimit + 120f)
      val availH = (bottomLimit - topLimit).coerceAtLeast(120f)
      // Scale square canvas generously so the crystal sphere (~62.7% of canvas) is rich and prominent,
      // while all 5 orbiting Residents & outer bubbles remain cleanly visible in the lower region.
      val maxSideW = sw * 0.94f
      val maxSideH = availH * 1.12f
      val side = min(maxSideW, maxSideH).roundToInt().coerceIn(96, sw)
      val left = ((sw - side) / 2f).roundToInt().coerceAtLeast(0)
      val centerYFromTop = topLimit + availH * 0.50f
      val topFromTop = (centerYFromTop - side * 0.5f)
        .coerceIn(photoFrame.bottomPx + 4f, (sh - side).toFloat().coerceAtLeast(0f))
      val bottomGl = (sh - (topFromTop + side)).roundToInt().coerceIn(0, (sh - side).coerceAtLeast(0))
      return Tab2OrbViewportRect(
        leftPx = left,
        bottomPx = bottomGl,
        sidePx = side,
        surfaceWidthPx = sw,
        surfaceHeightPx = sh,
      )
    }
  }
}

/**
 * Unified Tab 2 GLES 3.0 Renderer (`TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`):
 *
 * Strictly reuses Tab 3's production [WaterWorldStaticRenderer] + [WaterWorldMotionRuntime]
 * (ZERO duplicated Water World shaders, physics, or assets!) and layers:
 *   1. Exact frozen underwater PNG background ([Tab2UnderwaterBackgroundRenderer])
 *   2. Lower full dynamic Water World V2.1 ecosystem sphere (11 Render Passes, 5 Optical Nodes,
 *      Core 3D 32 Z-slices, Work 3.2 Five Residents 3D Orbital Motion, 4 Fish, 2 Plants, 3 Rocks,
 *      9 Outer + 14 Inner Bubbles) composited seamlessly with zero dark square borders
 *   3. Upper rounded photo ([PhotoTextureRenderer]) + independent perimeter water-light
 *      ([PerimeterWaterLightRenderer] + [Tab2IndependentPerimeterController], strictly ZERO
 *      Orb-to-photo energy beam!)
 */
class Tab2WaterWorldSceneRenderer(
  private val appContext: Context,
  private val onStatusChanged: (Tab2WaterWorldRuntimeStatus) -> Unit = {},
  private val requestRenderCallback: () -> Unit = {},
) : GLSurfaceView.Renderer {

  private val backgroundRenderer = Tab2UnderwaterBackgroundRenderer()
  private val photoRenderer = PhotoTextureRenderer()
  private val perimeterRenderer = PerimeterWaterLightRenderer()
  private val perimeterController = Tab2IndependentPerimeterController()

  private var compositeProgram: GlShaderProgram? = null
  private var compositeVao: Int = 0

  private var waterWorldRenderer: WaterWorldStaticRenderer? = null
  @Volatile
  internal var motionRuntime: WaterWorldMotionRuntime = WaterWorldMotionRuntime()
    private set

  @Volatile
  private var stagedScene: WaterWorldAssetStaging.StagedScene? = null
  @Volatile
  private var stagingError: String? = null
  private val stagingStarted = AtomicBoolean(false)
  private val disposed = AtomicBoolean(false)

  private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "Tab2WaterWorldStaging").apply { isDaemon = true }
  }

  private val pendingCustomPhoto = AtomicReference<Bitmap?>(null)
  private val liveLayout = AtomicReference<TaskSceneLayoutSnapshot?>(null)
  @Volatile private var surfaceWidth: Int = 1080
  @Volatile private var surfaceHeight: Int = 2340
  @Volatile private var bottomSafeInsetPx: Float = 0f
  @Volatile private var hasPresentedFirstReadyFrame: Boolean = false
  @Volatile private var lastPerimeterFrameNs: Long = 0L
  @Volatile private var lastUiReportNs: Long = 0L
  @Volatile private var lastOrbViewport: Tab2OrbViewportRect? = null
  @Volatile private var lastTelemetry: JSONObject? = null
  @Volatile private var cachedPerimeterPhoto: PhotoFrameGeometry? = null
  @Volatile private var cachedPerimeterTable: PerimeterArcLengthTable? = null

  private var initializedStagingRoot: String? = null

  fun startStagingIfNeeded() {
    if (!stagingStarted.compareAndSet(false, true)) return
    ioExecutor.execute {
      try {
        val staged = WaterWorldAssetStaging.stageAndLoad(
          context = appContext,
          isCancelled = { disposed.get() },
        )
        if (disposed.get()) return@execute
        motionRuntime = WaterWorldMotionRuntime(profile = staged.profile)
        stagedScene = staged
        stagingError = null
        requestRenderCallback()
      } catch (t: Throwable) {
        if (disposed.get()) return@execute
        stagingError = "RESOURCE_FAILED: ${t.message ?: t.javaClass.simpleName}"
        publishStatus(isReady = false, errorMessage = stagingError)
      }
    }
  }

  fun updateLiveLayout(snapshot: TaskSceneLayoutSnapshot, bottomInsetPx: Float = 0f) {
    liveLayout.set(snapshot)
    bottomSafeInsetPx = bottomInsetPx.coerceAtLeast(0f)
  }

  fun onCustomPhotoSelected(bitmap: Bitmap) {
    val previous = pendingCustomPhoto.getAndSet(bitmap)
    if (previous != null && previous !== bitmap && !previous.isRecycled) {
      previous.recycle()
    }
    perimeterController.onPhotoSelected(unpause = true)
    requestRenderCallback()
  }

  fun triggerPhotoRecognitionRestart() {
    perimeterController.onPhotoSelected(unpause = true)
    requestRenderCallback()
  }

  fun togglePlayPause() {
    if (motionRuntime.isPlaying) {
      motionRuntime.pause()
      perimeterController.setPaused(true)
    } else {
      motionRuntime.resume(System.nanoTime())
      perimeterController.setPaused(false)
      lastPerimeterFrameNs = System.nanoTime()
    }
    publishStatus(isReady = hasPresentedFirstReadyFrame)
    requestRenderCallback()
  }

  fun onHostPause() {
    motionRuntime.onActivityPause()
    perimeterController.setPaused(true)
    lastPerimeterFrameNs = 0L
  }

  fun onHostResume() {
    motionRuntime.onActivityResume(System.nanoTime())
    perimeterController.setPaused(false)
    lastPerimeterFrameNs = System.nanoTime()
    requestRenderCallback()
  }

  fun dispose() {
    disposed.set(true)
    motionRuntime.onActivityPause()
    perimeterController.setPaused(true)
    pendingCustomPhoto.getAndSet(null)?.let { bmp ->
      if (!bmp.isRecycled) bmp.recycle()
    }
    ioExecutor.shutdownNow()
  }

  fun releaseGlResourcesOnGlThread() {
    waterWorldRenderer?.release()
    waterWorldRenderer = null
    initializedStagingRoot = null
    backgroundRenderer.release()
    photoRenderer.release()
    perimeterRenderer.release()
    compositeProgram?.release()
    compositeProgram = null
    if (compositeVao != 0) {
      GLES30.glDeleteVertexArrays(1, intArrayOf(compositeVao), 0)
      compositeVao = 0
    }
    hasPresentedFirstReadyFrame = false
  }

  internal fun currentTelemetry(): JSONObject? = lastTelemetry
  internal fun currentOrbViewport(): Tab2OrbViewportRect? = lastOrbViewport
  internal fun currentPerimeterSnapshot(): Tab2PhotoPerimeterSnapshot = perimeterController.snapshot()

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    // Handles fresh init AND EGL context loss recovery cleanly without leaking old handles
    waterWorldRenderer?.handleCoreContextLost()
    releaseGlResourcesOnGlThread()

    GLES30.glClearColor(0.02f, 0.20f, 0.30f, 1f)
    backgroundRenderer.init(appContext.assets)
    photoRenderer.init()
    perimeterRenderer.init()

    compositeProgram = GlShaderProgram(
      SPHERE_COMPOSITE_VERT,
      SPHERE_COMPOSITE_FRAG,
      "Tab2WaterWorldSphereComposite",
    )
    val vaoArr = IntArray(1)
    GLES30.glGenVertexArrays(1, vaoArr, 0)
    compositeVao = vaoArr[0]

    startStagingIfNeeded()
    lastPerimeterFrameNs = System.nanoTime()
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
    surfaceWidth = max(1, width)
    surfaceHeight = max(1, height)
    GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
  }

  override fun onDrawFrame(gl: GL10?) {
    if (disposed.get()) return
    val sw = surfaceWidth
    val sh = surfaceHeight

    // Handle any user-selected custom photo bitmap uploaded on the GL thread
    pendingCustomPhoto.getAndSet(null)?.let { customBmp ->
      try {
        photoRenderer.uploadCustomPhotoBitmap(customBmp)
      } finally {
        if (!customBmp.isRecycled) customBmp.recycle()
      }
    }

    // 1. Layer 1: Exact frozen underwater PNG background (full-screen, aspect-preserving)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    backgroundRenderer.draw(sw, sh)

    // 2. Resolve unified layout for upper photo frame & lower 1:1 Water World sphere viewport
    val resolvedLayout = resolveEffectiveLayout(sw, sh)
    val orbViewport = Tab2OrbViewportRect.compute(
      surfaceWidthPx = sw,
      surfaceHeightPx = sh,
      photoFrame = resolvedLayout.photoFrame,
      bottomSafeInsetPx = bottomSafeInsetPx,
    )
    lastOrbViewport = orbViewport

    // 3. Layer 2: Shared Tab 3 Water World V2.1 Full Dynamic Ecosystem
    val err = stagingError
    if (err == null) {
      val staged = stagedScene
      if (staged != null) {
        ensureWaterWorldInitialized(staged)
        val ww = waterWorldRenderer
        if (ww != null) {
          val coreReady = checkCoreVolumeReady(ww)
          if (coreReady) {
            renderAndCompositeWaterWorld(ww, orbViewport, sw, sh)
          }
        }
      }
    }

    // 4. Layer 3: Upper Rounded Photo + Independent Perimeter Light (ZERO Orb->Photo beam!)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glViewport(0, 0, sw, sh)
    photoRenderer.drawPhoto(resolvedLayout, hdrScale = 1.0f, assetManager = appContext.assets)

    val nowNs = System.nanoTime()
    val dtSec = if (lastPerimeterFrameNs > 0L) {
      ((nowNs - lastPerimeterFrameNs) / 1_000_000_000.0).coerceIn(0.0, 0.05)
    } else {
      0.0
    }
    lastPerimeterFrameNs = nowNs
    val perimeterSnap = perimeterController.step(dtSec)
    val table = getOrBuildPerimeterTable(resolvedLayout.photoFrame)
    perimeterRenderer.draw(
      layout = resolvedLayout,
      table = table,
      snapshot = perimeterSnap.toPerimeterRenderSnapshot(),
      hdrScale = 1.0f,
      refractionTexId = 0,
      fboWidth = sw,
      fboHeight = sh,
    )

    if (nowNs - lastUiReportNs >= 250_000_000L) {
      lastUiReportNs = nowNs
      publishStatus(
        isReady = hasPresentedFirstReadyFrame,
        errorMessage = stagingError,
        orbViewport = orbViewport,
        perimeterState = perimeterSnap.state,
      )
    }
  }

  private fun ensureWaterWorldInitialized(staged: WaterWorldAssetStaging.StagedScene) {
    if (waterWorldRenderer != null && initializedStagingRoot == staged.rootDir.absolutePath) return
    try {
      waterWorldRenderer?.release()
      val renderer = WaterWorldStaticRenderer(
        root = staged.rootDir,
        assetManager = appContext.assets,
        backdropMode = WaterWorldStaticRenderer.BackdropMode.TRANSPARENT_COMPOSITE,
      )
      renderer.initialize(staged.scene)
      waterWorldRenderer = renderer
      initializedStagingRoot = staged.rootDir.absolutePath
      stagingError = null
    } catch (t: Throwable) {
      waterWorldRenderer?.release()
      waterWorldRenderer = null
      stagingError = "WATER_WORLD_INIT_FAILED: ${t.message ?: t.javaClass.simpleName}"
      publishStatus(isReady = false, errorMessage = stagingError)
    }
  }

  private fun checkCoreVolumeReady(ww: WaterWorldStaticRenderer): Boolean {
    if (!ww.requiresCoreVolume || ww.isCoreVolumeReady) return true
    ww.pollCoreVolumeUpload()
    if (ww.coreVolumeLifecycleState == CoreVolumeLifecycleState.CORE_VOLUME_ERROR) {
      stagingError = "CORE_VOLUME_ERROR: ${ww.coreVolumeStatusMessage}"
      publishStatus(isReady = false, errorMessage = stagingError)
      return false
    }
    return ww.isCoreVolumeReady
  }

  private fun renderAndCompositeWaterWorld(
    ww: WaterWorldStaticRenderer,
    viewport: Tab2OrbViewportRect,
    surfaceW: Int,
    surfaceH: Int,
  ) {
    try {
      val nowNs = System.nanoTime()
      val snapshot = motionRuntime.stepFrame(nowNs)
      val telemetry = ww.renderDynamic(
        snapshot = snapshot,
        mode = WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE,
      )
      val passCount = telemetry.optInt("passCount", 0)
      val opticalCount = telemetry.optInt("opticalNodeCount", 0)
      val fboComplete = telemetry.optBoolean("fboComplete", false)
      check(fboComplete && passCount == 11 && opticalCount == 5) {
        "Incomplete Water World Render Graph in Tab 2: fbo=$fboComplete passes=$passCount optics=$opticalCount"
      }
      lastTelemetry = telemetry

      compositeSphereIntoScreen(ww, viewport, surfaceW, surfaceH)

      if (!hasPresentedFirstReadyFrame) {
        hasPresentedFirstReadyFrame = true
        if (motionRuntime.profile.autoPlayOnEnter && !motionRuntime.staticReferenceMode) {
          motionRuntime.play(System.nanoTime())
        }
        publishStatus(
          isReady = true,
          orbViewport = viewport,
          perimeterState = perimeterController.snapshot().state,
        )
      }
    } catch (t: Throwable) {
      stagingError = "WATER_WORLD_RENDER_FAILED: ${t.message ?: t.javaClass.simpleName}"
      publishStatus(isReady = false, errorMessage = stagingError)
    }
  }

  private fun compositeSphereIntoScreen(
    ww: WaterWorldStaticRenderer,
    viewport: Tab2OrbViewportRect,
    surfaceW: Int,
    surfaceH: Int,
  ) {
    val prog = compositeProgram ?: return
    val texId = ww.finalTargetTextureId
    val opt = ww.activeOpticalConfig ?: return
    if (texId == 0 || compositeVao == 0) return

    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glViewport(viewport.leftPx, viewport.bottomPx, viewport.sidePx, viewport.sidePx)
    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glEnable(GLES30.GL_BLEND)
    // Premultiplied alpha composite: opaque crystal sphere interior + luminous outer Residents/bubbles/bloom
    GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    prog.use()
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
    prog.setInt("uWaterWorldTex", 0)
    prog.setVec2("uCanvasSize", ww.finalTargetSize.toFloat(), ww.finalTargetSize.toFloat())
    prog.setVec2("uSphereCenterPx", opt.sphereCenterPx[0], opt.sphereCenterPx[1])
    prog.setFloat("uSphereOuterRadiusPx", opt.sphereOuterRadiusPx)

    GLES30.glBindVertexArray(compositeVao)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    GLES30.glBindVertexArray(0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    GLES30.glViewport(0, 0, surfaceW, surfaceH)
  }

  private fun resolveEffectiveLayout(sw: Int, sh: Int): TaskSceneLayoutSnapshot {
    val current = liveLayout.get()
    return if (current != null && current.isValid() && current.surfaceWidthPx == sw && current.surfaceHeightPx == sh) {
      current
    } else {
      TaskSceneLayoutSnapshot.canonicalFallback(sw, sh)
    }
  }

  private fun getOrBuildPerimeterTable(photo: PhotoFrameGeometry): PerimeterArcLengthTable {
    val existing = cachedPerimeterTable
    if (existing != null && cachedPerimeterPhoto == photo) return existing
    val built = PerimeterArcLengthTable(PerimeterPathGeometry(photo))
    cachedPerimeterPhoto = photo
    cachedPerimeterTable = built
    return built
  }

  private fun publishStatus(
    isReady: Boolean,
    errorMessage: String? = null,
    orbViewport: Tab2OrbViewportRect? = lastOrbViewport,
    perimeterState: Tab2PhotoPerimeterState = perimeterController.snapshot().state,
  ) {
    val tel = lastTelemetry
    onStatusChanged(
      Tab2WaterWorldRuntimeStatus(
        isReady = isReady,
        isCoreVolumeReady = waterWorldRenderer?.isCoreVolumeReady == true,
        isPlaying = motionRuntime.isPlaying,
        passCount = tel?.optInt("passCount", 0) ?: 0,
        opticalNodeCount = tel?.optInt("opticalNodeCount", 0) ?: 0,
        errorMessage = errorMessage,
        perimeterState = perimeterState,
        dynamicTimeSeconds = motionRuntime.currentTimeSeconds,
        orbViewportSidePx = orbViewport?.sidePx ?: 0,
      ),
    )
  }

  companion object {
    val SPHERE_COMPOSITE_VERT = """#version 300 es
      precision highp float;
      precision highp int;
      out vec2 vUv;
      void main() {
        vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);
        gl_Position = vec4(pos, 0.0, 1.0);
        // GL texture UV (0,0 bottom-left -> 1,1 top-right) matching Water World FBO orientation
        vUv = pos * 0.5 + 0.5;
      }
    """.trimIndent()

    /**
     * Seamlessly composites the 1254x1254 Water World final FBO onto Tab 2's sunlit underwater PNG:
     * - Inside the crystal glass shell (`distPx <= uSphereOuterRadiusPx`): full opaque alpha = 1.0
     *   so the crystal sphere, Core 3D volume, 4 fish, 2 plants, 3 rocks, and caustics keep 100%
     *   Tab 3 fidelity with zero bright-background washout.
     * - Outside the glass shell (`distPx > uSphereOuterRadiusPx`): preserves the 5 orbiting Resident
     *   filaments, 9 rising outer bubbles, and soft golden/cyan bloom as luminous translucent energy
     *   with smooth canvas-edge fade and zero dark square box!
     */
    val SPHERE_COMPOSITE_FRAG = """#version 300 es
      precision highp float;
      precision highp int;
      in vec2 vUv;
      uniform sampler2D uWaterWorldTex;
      uniform vec2 uCanvasSize;
      uniform vec2 uSphereCenterPx;
      uniform float uSphereOuterRadiusPx;
      out vec4 fragColor;
      void main() {
        vec3 c = texture(uWaterWorldTex, vUv).rgb;
        // Convert bottom-left GL UV to top-left Water World canvas pixel space
        vec2 canvasPx = vec2(vUv.x * uCanvasSize.x, (1.0 - vUv.y) * uCanvasSize.y);
        float distPx = length(canvasPx - uSphereCenterPx);

        // Crisp anti-aliased sphere interior mask
        float sphereAlpha = 1.0 - smoothstep(uSphereOuterRadiusPx - 3.5, uSphereOuterRadiusPx + 4.5, distPx);

        // Smooth edge feather near the 1254x1254 square canvas boundary so no square seam ever appears
        vec2 edgeDist = min(vUv, 1.0 - vUv);
        float edgeFade = smoothstep(0.0, 0.06, min(edgeDist.x, edgeDist.y));

        // Luminous alpha for outer 5 Residents, 9 outer droplets, and bloom halo
        float peak = max(max(c.r, c.g), c.b);
        float outerLumaAlpha = smoothstep(0.035, 0.58, peak) * 0.86 * edgeFade;

        float outAlpha = max(sphereAlpha, outerLumaAlpha);
        if (outAlpha < 0.002) discard;

        vec3 premulRgb = mix(c * edgeFade, c, sphereAlpha);
        fragColor = vec4(premulRgb, outAlpha);
      }
    """.trimIndent()
  }
}
