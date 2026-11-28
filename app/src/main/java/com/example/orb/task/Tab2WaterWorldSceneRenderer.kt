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
  val photoTextureLoaded: Boolean = false,
  val refractionEnabled: Boolean = true,
  val refractionTexId: Int = 0,
  val perimeterShaderLinked: Boolean = true,
)

/**
 * Unified Tab 2 GLES 3.0 Renderer (`TAB2_V22_LAYOUT_PHOTO_PIPELINE_PERIMETER_RUNTIME_CLOSURE_V1`):
 *
 * Strictly reuses Tab 3's production [WaterWorldStaticRenderer] + [WaterWorldMotionRuntime]
 * and layers:
 *   1. Exact frozen underwater PNG background ([Tab2UnderwaterBackgroundRenderer])
 *   2. Lower full dynamic Water World V2.1 ecosystem sphere (11 Render Passes, 5 Optical Nodes,
 *      Core 3D 32 Z-slices, Work 3.2 Five Residents 3D Orbital Motion, 4 Fish, 2 Plants, 3 Rocks)
 *   3. Upper rounded photo ([PhotoTextureRenderer]) with direct sRGB display color mode and
 *      aspect-locked CENTER_CROP UV transform + standalone RGBA8 perimeter refraction snapshot
 *      ([PerimeterWaterLightRenderer] with Tab2 V2.2 slender material profile).
 */
class Tab2WaterWorldSceneRenderer(
  private val appContext: Context,
  private val onStatusChanged: (Tab2WaterWorldRuntimeStatus) -> Unit = {},
  private val requestRenderCallback: () -> Unit = {},
) : GLSurfaceView.Renderer {

  private val backgroundRenderer = Tab2UnderwaterBackgroundRenderer()
  private val photoRenderer = PhotoTextureRenderer()
  private val perimeterRenderer = PerimeterWaterLightRenderer(
    material = PerimeterWaterLightMaterial(
      l1IceAlphaScale = 0.95f,
      l2EmeraldAlphaScale = 0.85f,
      l3GoldAlphaScale = 0.90f,
    ),
  )
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

  // EXIF URI photo loader background coordinator
  private val photoLoader = PhotoSourceLoadCoordinator(
    decodeFn = { uri -> PhotoSourceDecoder.decodeFromUri(appContext, uri) },
    onDecodedReady = { requestRenderCallback() },
  )

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

  // Dedicated standalone RGBA8 refraction snapshot texture for Tab 2 perimeter waterlight
  private var refractionSnapshotTexId: Int = 0
  private var refractionTexWidth: Int = 0
  private var refractionTexHeight: Int = 0

  private var initializedStagingRoot: String? = null

  @Volatile var debugOptions: Tab2DebugOptions = Tab2DebugOptions.Default

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

  fun onPhotoUriSelected(uri: android.net.Uri?) {
    photoLoader.requestLoad(uri)
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
    photoLoader.dispose()
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
    if (refractionSnapshotTexId != 0) {
      GLES30.glDeleteTextures(1, intArrayOf(refractionSnapshotTexId), 0)
      refractionSnapshotTexId = 0
    }
    refractionTexWidth = 0
    refractionTexHeight = 0
    hasPresentedFirstReadyFrame = false
  }

  internal fun currentTelemetry(): JSONObject? = lastTelemetry
  internal fun currentOrbViewport(): Tab2OrbViewportRect? = lastOrbViewport
  internal fun currentPerimeterSnapshot(): Tab2PhotoPerimeterSnapshot = perimeterController.snapshot()

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
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
    ensureRefractionSnapshotTexture(surfaceWidth, surfaceHeight)
  }

  override fun onDrawFrame(gl: GL10?) {
    if (disposed.get()) return
    val sw = surfaceWidth
    val sh = surfaceHeight

    // Drain decoded photo source from background EXIF loader thread and upload on GL thread
    photoLoader.drainPendingForGlUpload()?.let { decoded ->
      try {
        photoRenderer.uploadCustomPhotoBitmap(decoded.bitmap)
      } finally {
        if (!decoded.bitmap.isRecycled) decoded.bitmap.recycle()
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

    // 4. Layer 3: Upper Rounded Photo (Direct sRGB display color mode + CENTER_CROP UV transform)
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glViewport(0, 0, sw, sh)
    photoRenderer.drawPhoto(
      layout = resolvedLayout,
      hdrScale = 1.0f,
      assetManager = appContext.assets,
      colorMode = PhotoDisplayColorMode.DIRECT_DISPLAY_SRGB,
      useCenterCropUv = true,
    )

    // 5. Capture Perimeter Refraction Snapshot (Zero feedback loop: copy default framebuffer 0 -> RGBA8 texture)
    val activeRefractionTexId = if (debugOptions.enableRefraction) {
      ensureRefractionSnapshotTexture(sw, sh)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, refractionSnapshotTexId)
      GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 0, 0, sw, sh)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
      refractionSnapshotTexId
    } else {
      0
    }

    // 6. Layer 4: Independent Perimeter Water-Light (Tab 2 V2.2 Slender Material Profile + Refraction)
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
      refractionTexId = activeRefractionTexId,
      fboWidth = sw,
      fboHeight = sh,
      debugOptions = debugOptions.toPerimeterDebugOptions(),
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

  private fun ensureRefractionSnapshotTexture(w: Int, h: Int) {
    if (refractionSnapshotTexId != 0 && refractionTexWidth == w && refractionTexHeight == h) return
    if (refractionSnapshotTexId != 0) {
      GLES30.glDeleteTextures(1, intArrayOf(refractionSnapshotTexId), 0)
      refractionSnapshotTexId = 0
    }
    val texArr = IntArray(1)
    GLES30.glGenTextures(1, texArr, 0)
    refractionSnapshotTexId = texArr[0]
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, refractionSnapshotTexId)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    refractionTexWidth = w
    refractionTexHeight = h
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
        photoTextureLoaded = photoRenderer.isPhotoTextureLoaded,
        refractionEnabled = debugOptions.enableRefraction,
        refractionTexId = refractionSnapshotTexId,
        perimeterShaderLinked = perimeterRenderer.lastDrawStats.shaderLinked,
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
        vUv = pos * 0.5 + 0.5;
      }
    """.trimIndent()

    /**
     * Seamlessly composites the 1254x1254 Water World final FBO onto Tab 2's sunlit underwater PNG with
     * mathematically exact premultiplied Source-Over alpha (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`):
     * - Inside the crystal glass shell (`distPx <= uSphereOuterRadiusPx`): full opaque alpha = 1.0
     *   so the crystal sphere, Core 3D volume, 4 fish, 2 plants, 3 rocks, and caustics keep 100%
     *   Tab 3 fidelity with zero bright-background washout.
     * - Outside the glass shell (`distPx > uSphereOuterRadiusPx`): soft, non-overbright premultiplied
     *   fusion of the 5 golden Residents and 9 outer bubbles over bright cyan water with zero harsh clipping.
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
        vec2 canvasPx = vec2(vUv.x * uCanvasSize.x, (1.0 - vUv.y) * uCanvasSize.y);
        float distPx = length(canvasPx - uSphereCenterPx);

        float sphereAlpha = 1.0 - smoothstep(uSphereOuterRadiusPx - 3.5, uSphereOuterRadiusPx + 4.5, distPx);
        vec2 edgeDist = min(vUv, 1.0 - vUv);
        float edgeFade = smoothstep(0.0, 0.06, min(edgeDist.x, edgeDist.y));

        float peak = max(max(c.r, c.g), c.b);
        float outerCoverage = smoothstep(0.02, 0.72, peak) * 0.72 * edgeFade;
        float safePeak = max(peak, 1e-4);
        float chromaScale = mix(0.76, 0.96, smoothstep(0.15, 0.95, peak));
        vec3 outerStraight = (c / safePeak) * chromaScale;
        vec3 outerPremul = outerStraight * outerCoverage;

        float outAlpha = mix(outerCoverage, 1.0, sphereAlpha);
        if (outAlpha < 0.002) discard;

        vec3 premulRgb = mix(outerPremul, c, sphereAlpha);
        fragColor = vec4(premulRgb, outAlpha);
      }
    """.trimIndent()
  }
}
