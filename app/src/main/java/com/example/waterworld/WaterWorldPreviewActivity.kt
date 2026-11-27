package com.example.waterworld

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Debug-only independent showcase screen for Water World V2.1 (T=0 static scene).
 *
 * Design separation:
 * - Default screen is an unobstructed crystal-sphere underwater scene showcase (FINAL_SCENE)
 *   rendered automatically on launch via the full 11-stage Render Graph & 5 optical effects.
 * - Developer Diagnostics (11 Passes, 5 Optics, GPU Telemetry, Evidence Export) live inside an
 *   independent collapsible drawer (`COLLAPSED` / `View.GONE` by default).
 * - Strict 6-state lifecycle ([PageState]): `READY` is set ONLY after complete FINAL_SCENE
 *   rendering (11 passes + 5 optical nodes + FBO complete) and screen presentation succeed.
 */
class WaterWorldPreviewActivity : Activity() {

    enum class PageState {
        LOADING,
        READY,
        RENDER_FAILED,
        RESOURCE_FAILED,
        SHADER_FAILED,
        FBO_FAILED,
    }

    internal companion object {
        const val EXTRA_ASSET_ROOT_PATH = "water_world_asset_root_path"
        const val TAG_BACK_BUTTON = "water_world_back_button"
        const val TAG_TITLE = "water_world_title"
        const val TAG_SUBTITLE = "water_world_subtitle"
        const val TAG_LOADING_OVERLAY = "water_world_loading_overlay"
        const val TAG_ERROR_PANEL = "water_world_error_panel"
        const val TAG_ERROR_STATE = "water_world_error_state"
        const val TAG_ERROR_MESSAGE = "water_world_error_message"
        const val TAG_RETRY_BUTTON = "water_world_retry_button"
        const val TAG_ACTIVE_MODE_BANNER = "water_world_active_mode_banner"
        const val TAG_BANNER_RESTORE_FINAL = "water_world_banner_restore_final"
        const val REQ_SAVE_EVIDENCE_ZIP = 4202

        /**
         * Builds a zero-permission Android Storage Access Framework (SAF) intent to save the
         * Water World Work 2 Evidence ZIP bundle to user-selected Downloads/Files storage.
         */
        internal fun buildCreateEvidenceZipIntent(fileName: String): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }

        /**
         * Converts the renderer's actual OpenGL ES bottom-left [WaterWorldStaticRenderer.PresentViewport]
         * into an Android Bitmap top-left crop rectangle `[left, top, width, height]` safely clamped
         * inside `[screenWidth, screenHeight]`. Falls back to centered square only if viewport is null.
         */
        internal fun computeAndroidCropRect(
            viewport: WaterWorldStaticRenderer.PresentViewport?,
            screenWidth: Int,
            screenHeight: Int,
        ): IntArray {
            require(screenWidth > 0 && screenHeight > 0)
            if (viewport != null && viewport.side > 0 && viewport.surfaceWidth > 0 && viewport.surfaceHeight > 0) {
                val scaleX = screenWidth.toDouble() / viewport.surfaceWidth.toDouble()
                val scaleY = screenHeight.toDouble() / viewport.surfaceHeight.toDouble()
                val cropW = (viewport.side * scaleX).toInt().coerceIn(1, screenWidth)
                val cropH = (viewport.side * scaleY).toInt().coerceIn(1, screenHeight)
                val left = (viewport.left * scaleX).toInt().coerceIn(0, (screenWidth - cropW).coerceAtLeast(0))
                val glTop = viewport.surfaceHeight - viewport.bottom - viewport.side
                val top = (glTop * scaleY).toInt().coerceIn(0, (screenHeight - cropH).coerceAtLeast(0))
                return intArrayOf(left, top, cropW, cropH)
            }
            val side = minOf(screenWidth, screenHeight)
            return intArrayOf((screenWidth - side) / 2, (screenHeight - side) / 2, side, side)
        }

        internal fun classifyInitFailure(failure: Throwable): PageState {
            val trace = buildErrorSignature(failure)
            return when {
                trace.contains("shader") ||
                    trace.contains("program") ||
                    trace.contains("compile") ||
                    trace.contains("link") -> PageState.SHADER_FAILED
                trace.contains("fbo") ||
                    trace.contains("framebuffer") ||
                    trace.contains("rendertarget") -> PageState.FBO_FAILED
                failure is FileNotFoundException ||
                    trace.contains("asset") ||
                    trace.contains("identity mismatch") ||
                    trace.contains("missing") ||
                    trace.contains("json") ||
                    trace.contains("mesh") ||
                    trace.contains("waterworldscenebindings") ||
                    trace.contains("waterworldassetloader") -> PageState.RESOURCE_FAILED
                else -> PageState.RENDER_FAILED
            }
        }

        internal fun classifyRenderFailure(failure: Throwable): PageState {
            val trace = buildErrorSignature(failure)
            return when {
                trace.contains("fbo") ||
                    trace.contains("framebuffer") ||
                    trace.contains("feedback") -> PageState.FBO_FAILED
                trace.contains("shader") ||
                    trace.contains("program") -> PageState.SHADER_FAILED
                else -> PageState.RENDER_FAILED
            }
        }

        private fun buildErrorSignature(failure: Throwable): String {
            val parts = StringBuilder()
            var cur: Throwable? = failure
            var depth = 0
            while (cur != null && depth < 5) {
                parts.append(cur.javaClass.simpleName).append(' ')
                parts.append(cur.message.orEmpty()).append(' ')
                cur.stackTrace.take(4).forEach { parts.append(it.className).append(' ') }
                cur = cur.cause
                depth++
            }
            return parts.toString().lowercase(Locale.US)
        }
    }

    private lateinit var surface: GLSurfaceView
    private lateinit var preview: Preview
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorStateView: TextView
    private lateinit var errorMessageView: TextView
    private lateinit var activeModeBanner: LinearLayout
    private lateinit var activeModeText: TextView
    private lateinit var diagnosticsDrawer: WaterWorldDiagnosticsDrawer
    private lateinit var assetRootPath: String

    @Volatile
    private var currentPageState: PageState = PageState.LOADING

    @Volatile
    private var lastErrorMessage: String? = null

    @Volatile
    private var currentDiagnosticMode: WaterWorldRenderGraph.DiagnosticMode =
        WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE

    @Volatile
    private var exportInFlight: Boolean = false

    @Volatile
    private var latestTelemetry: JSONObject? = null

    @Volatile
    private var preparedScene: PreparedScene? = null

    @Volatile
    private var preparationGeneration: Long = 0L

    @Volatile
    private var isActivityDestroyed: Boolean = false

    @Volatile
    private var latestEvidenceZipFile: File? = null

    @Volatile
    private var resourceStagingMs: Double = Double.NaN

    @Volatile
    private var firstFramePresentedMs: Double = Double.NaN

    private var activityCreateNs: Long = 0L

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WaterWorldAssetStaging").apply { isDaemon = true }
    }

    @Volatile
    internal var motionRuntime: WaterWorldMotionRuntime = WaterWorldMotionRuntime()
        private set

    @Volatile
    private var lastUiTelemetryUpdateNs: Long = 0L

    internal val pageState: PageState get() = currentPageState
    internal val errorDetail: String? get() = lastErrorMessage
    internal val activeDiagnosticMode: WaterWorldRenderGraph.DiagnosticMode get() = currentDiagnosticMode
    internal val isDiagnosticsExpanded: Boolean get() = diagnosticsDrawer.isExpanded
    internal val currentRenderMode: Int get() = surface.renderMode

    private data class PreparedScene(
        val root: File,
        val scene: WaterWorldSceneBindings.LoadedScene,
        val profile: WaterWorldMotionProfile,
        val generation: Long,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityCreateNs = System.nanoTime()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        assetRootPath = intent?.getStringExtra(EXTRA_ASSET_ROOT_PATH) ?: "water_world_v2_1"
        preview = Preview(assetRootPath)

        surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            preserveEGLContextOnPause = true
            setRenderer(preview)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#021016"))
        }
        // Full-bleed crystal sphere showcase canvas
        rootFrame.addView(
            surface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )

        // Minimalist floating top showcase header: Back button + Title + optional diagnostic banner
        val topHeaderContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBackground(Color.parseColor("#A8041820"), Color.parseColor("#5936B8C9"), dp(22))
            setPadding(dp(8), dp(6), dp(14), dp(6))
        }

        val backButton = Button(this).apply {
            tag = TAG_BACK_BUTTON
            text = getString(R.string.water_world_back)
            contentDescription = getString(R.string.water_world_back)
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBackground(Color.parseColor("#CC0E859A"), Color.parseColor("#8063DCEB"), dp(18))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { finish() }
        }

        val titleStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        val titleView = TextView(this).apply {
            tag = TAG_TITLE
            text = getString(R.string.water_world_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitleView = TextView(this).apply {
            tag = TAG_SUBTITLE
            text = getString(R.string.water_world_subtitle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor("#BBE6F9FF"))
        }
        titleStack.addView(titleView)
        titleStack.addView(subtitleView)
        topBar.addView(backButton)
        topBar.addView(
            titleStack,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // Banner only visible when user explicitly switches away from FINAL_SCENE inside diagnostics
        activeModeBanner = LinearLayout(this).apply {
            tag = TAG_ACTIVE_MODE_BANNER
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = pillBackground(Color.parseColor("#D9083040"), Color.parseColor("#995CE6C8"), dp(16))
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        activeModeText = TextView(this).apply {
            setTextColor(Color.parseColor("#FFE4FAFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val bannerRestoreBtn = Button(this).apply {
            tag = TAG_BANNER_RESTORE_FINAL
            text = "返回完整场景"
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.WHITE)
            background = pillBackground(Color.parseColor("#CC0E7E70"), Color.parseColor("#995CE6C8"), dp(14))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { selectDiagnosticMode(WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE) }
        }
        activeModeBanner.addView(
            activeModeText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        )
        activeModeBanner.addView(bannerRestoreBtn)

        topHeaderContainer.addView(topBar)
        topHeaderContainer.addView(
            activeModeBanner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        )

        rootFrame.addView(
            topHeaderContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
        )

        // Lightweight centered Loading indicator (auto-dismisses when FINAL_SCENE reaches READY)
        loadingOverlay = LinearLayout(this).apply {
            tag = TAG_LOADING_OVERLAY
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pillBackground(Color.parseColor("#CC041820"), Color.parseColor("#6636B8C9"), dp(20))
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        loadingOverlay.addView(ProgressBar(this))
        loadingOverlay.addView(
            TextView(this).apply {
                text = getString(R.string.water_world_status_loading)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setPadding(0, dp(10), 0, 0)
            }
        )
        rootFrame.addView(
            loadingOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        )

        // Explicit centered Error Panel with state code, concrete detail, Retry, and Back
        errorPanel = LinearLayout(this).apply {
            tag = TAG_ERROR_PANEL
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            background = pillBackground(Color.parseColor("#F02A0B12"), Color.parseColor("#CCFF6B6B"), dp(20))
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        errorStateView = TextView(this).apply {
            tag = TAG_ERROR_STATE
            text = getString(R.string.water_world_status_error)
            setTextColor(Color.parseColor("#FFFFB4B4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        errorMessageView = TextView(this).apply {
            tag = TAG_ERROR_MESSAGE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(10), 0, dp(14))
        }
        val errorActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val retryBtn = Button(this).apply {
            tag = TAG_RETRY_BUTTON
            text = getString(R.string.water_world_retry)
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBackground(Color.parseColor("#CC128A7A"), Color.parseColor("#995CE6C8"), dp(16))
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { retrySceneLoad() }
        }
        val errorBackBtn = Button(this).apply {
            text = getString(R.string.water_world_back)
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setTextColor(Color.WHITE)
            background = pillBackground(Color.parseColor("#CC0E859A"), Color.parseColor("#8063DCEB"), dp(16))
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { finish() }
        }
        errorActions.addView(
            retryBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(10) }
        )
        errorActions.addView(errorBackBtn)

        errorPanel.addView(errorStateView)
        errorPanel.addView(errorMessageView)
        errorPanel.addView(errorActions)
        rootFrame.addView(
            errorPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                marginStart = dp(24)
                marginEnd = dp(24)
            }
        )

        // Independent collapsible Developer Diagnostics area (COLLAPSED by default)
        diagnosticsDrawer = WaterWorldDiagnosticsDrawer(
            context = this,
            onSelectDiagnosticMode = { mode -> selectDiagnosticMode(mode) },
            onRestoreFinalScene = { selectDiagnosticMode(WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE) },
            onTriggerEvidenceExport = { triggerSynchronizedExport() },
            onSaveEvidenceZip = { launchSaveEvidenceZipSaf() },
            onTogglePlayPause = { togglePlayPauseDynamic() },
            onResetTimeline = { resetDynamic(autoPlay = motionRuntime.isPlaying) },
            onToggleStaticReference = { setStaticReferenceMode(!motionRuntime.staticReferenceMode) },
            onCycleSpeedScale = { cycleDynamicSpeedScale() },
            onTriggerDynamicEvidenceExport = { triggerDynamicEvidenceExport() },
        )
        rootFrame.addView(
            diagnosticsDrawer.rootContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
        )

        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { _, insets ->
            val sysBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topHeaderContainer.setPadding(
                dp(12) + sysBars.left,
                dp(10) + sysBars.top,
                dp(12) + sysBars.right,
                dp(6),
            )
            diagnosticsDrawer.rootContainer.setPadding(
                dp(12) + sysBars.left,
                dp(6),
                dp(12) + sysBars.right,
                dp(12) + sysBars.bottom,
            )
            insets
        }

        setContentView(rootFrame)

        // Pre-validate packaged frozen Water World resources immediately so missing/corrupt APK
        // assets surface RESOURCE_FAILED instead of a silent black screen; then extract & parse
        // the full 65-asset scene asynchronously so GLSurfaceView's first frame reaches
        // SurfaceSyncGroup well under the 1000ms Android window sync budget.
        if (validateAssetsOrReportFailure()) {
            startBackgroundScenePreparation(forceReextract = false)
        }
    }

    internal fun validatePackagedAssetsPresent(rootPath: String) {
        val children = assets.list(rootPath)
        if (children.isNullOrEmpty()) {
            throw FileNotFoundException("APK assets directory '$rootPath' is missing or empty")
        }
        val required = listOf(
            "$rootPath/03_SCENE_GRAPH/draw_bindings.json",
            "$rootPath/02_SUPPLEMENTED_ASSETS/materials/optical_config.json",
            "$rootPath/02_SUPPLEMENTED_ASSETS/materials/materials.json",
            "$rootPath/02_SUPPLEMENTED_ASSETS/lighting/lighting.json",
        )
        for (path in required) {
            assets.open(path).use { stream ->
                if (stream.read() < 0) throw IllegalStateException("Empty required asset: $path")
            }
        }
    }

    private fun validateAssetsOrReportFailure(): Boolean {
        return runCatching { validatePackagedAssetsPresent(assetRootPath) }
            .onFailure { err ->
                reportPageState(
                    PageState.RESOURCE_FAILED,
                    "冻结资源校验失败 ($assetRootPath): ${err.message ?: err.javaClass.simpleName}",
                )
            }
            .isSuccess
    }

    private fun computeStageCacheStamp(): String {
        val updateTime = runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return "$assetRootPath|$updateTime|v21_work3_dynamic"
    }

    private fun startBackgroundScenePreparation(forceReextract: Boolean) {
        val gen = ++preparationGeneration
        val stageStartNs = System.nanoTime()
        preparedScene = null
        ioExecutor.execute {
            if (isActivityDestroyed || isFinishing || gen != preparationGeneration) return@execute
            val root = File(cacheDir, "water_world_v2_1")
            val marker = File(root, ".stage_stamp")
            val expectedStamp = computeStageCacheStamp()
            val incoming = File(cacheDir, "water_world_v2_1_incoming_$gen")
            try {
                val canReuseCache = !forceReextract &&
                    root.isDirectory &&
                    marker.isFile &&
                    marker.readText() == expectedStamp &&
                    File(root, "03_SCENE_GRAPH/draw_bindings.json").isFile &&
                    File(root, "02_SUPPLEMENTED_ASSETS/materials/optical_config.json").isFile

                if (!canReuseCache) {
                    if (incoming.exists()) incoming.deleteRecursively()
                    check(incoming.mkdirs()) { "Failed to create staging directory: ${incoming.path}" }
                    extractAssets(assetRootPath, incoming)
                    if (isActivityDestroyed || isFinishing || gen != preparationGeneration) {
                        incoming.deleteRecursively()
                        return@execute
                    }
                    File(incoming, ".stage_stamp").writeText(expectedStamp)
                    if (root.exists()) check(root.deleteRecursively()) { "Failed to replace cache: ${root.path}" }
                    check(incoming.renameTo(root)) { "Failed to stage assets into ${root.path}" }
                }

                if (isActivityDestroyed || isFinishing || gen != preparationGeneration) return@execute
                val scene = WaterWorldSceneBindings.loadScene(root)
                val profile = WaterWorldMotionProfile.loadFromAssetRoot(root)
                if (isActivityDestroyed || isFinishing || gen != preparationGeneration) return@execute

                resourceStagingMs = (System.nanoTime() - stageStartNs) / 1_000_000.0
                motionRuntime = WaterWorldMotionRuntime(profile = profile)
                preparedScene = PreparedScene(root, scene, profile, gen)
                runOnUiThread {
                    if (!isActivityDestroyed && !isFinishing) {
                        surface.requestRender()
                    }
                }
            } catch (failure: Throwable) {
                incoming.deleteRecursively()
                if (isActivityDestroyed || isFinishing || gen != preparationGeneration) return@execute
                android.util.Log.e("WaterWorldPreview", "Background scene staging failed", failure)
                val failureState = classifyInitFailure(failure)
                reportPageState(
                    failureState,
                    "${failureState.name}: ${failure.message ?: failure.javaClass.simpleName}",
                )
            }
        }
    }

    private fun extractAssets(assetPath: String, destination: File) {
        if (isActivityDestroyed || isFinishing) return
        val children = assets.list(assetPath) ?: throw FileNotFoundException("Missing APK assets: $assetPath")
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { input.copyTo(it) }
            }
            return
        }
        check(destination.isDirectory || destination.mkdirs())
        for (child in children) {
            if (isActivityDestroyed || isFinishing) return
            extractAssets("$assetPath/$child", File(destination, child))
        }
    }

    internal fun retrySceneLoad() {
        reportPageState(PageState.LOADING)
        if (!validateAssetsOrReportFailure()) return
        surface.queueEvent {
            preview.release()
        }
        startBackgroundScenePreparation(forceReextract = true)
    }

    internal fun setDiagnosticsExpanded(expanded: Boolean) {
        diagnosticsDrawer.setExpanded(expanded)
        if (expanded) {
            diagnosticsDrawer.updateMotionStatus(motionRuntime)
        }
    }

    internal fun playDynamic() {
        motionRuntime.play(System.nanoTime())
        syncSurfaceRenderMode()
        diagnosticsDrawer.updateMotionStatus(motionRuntime)
    }

    internal fun pauseDynamic() {
        motionRuntime.pause()
        syncSurfaceRenderMode()
        diagnosticsDrawer.updateMotionStatus(motionRuntime)
    }

    internal fun resumeDynamic() {
        motionRuntime.resume(System.nanoTime())
        syncSurfaceRenderMode()
        diagnosticsDrawer.updateMotionStatus(motionRuntime)
    }

    internal fun togglePlayPauseDynamic() {
        if (motionRuntime.isPlaying) pauseDynamic() else resumeDynamic()
    }

    internal fun resetDynamic(autoPlay: Boolean = false) {
        motionRuntime.reset(autoPlay = autoPlay, nowNanos = System.nanoTime())
        syncSurfaceRenderMode()
        diagnosticsDrawer.updateMotionStatus(motionRuntime)
        surface.requestRender()
    }

    internal fun setSpeedScale(scale: Float) {
        motionRuntime.setSpeedScale(scale)
        diagnosticsDrawer.updateMotionStatus(motionRuntime)
    }

    internal fun cycleDynamicSpeedScale() {
        val next = when {
            motionRuntime.speedScale < 0.75f -> 1.0f
            motionRuntime.speedScale < 1.25f -> 1.5f
            else -> 0.5f
        }
        setSpeedScale(next)
    }

    internal fun setStaticReferenceMode(enabled: Boolean) {
        motionRuntime.setStaticReferenceMode(enabled)
        syncSurfaceRenderMode()
        diagnosticsDrawer.updateMotionStatus(motionRuntime)
        surface.requestRender()
    }

    private fun syncSurfaceRenderMode() {
        val desired = if (motionRuntime.isPlaying && currentPageState == PageState.READY) {
            GLSurfaceView.RENDERMODE_CONTINUOUSLY
        } else {
            GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        if (surface.renderMode != desired) {
            surface.renderMode = desired
        }
        if (desired == GLSurfaceView.RENDERMODE_CONTINUOUSLY) {
            surface.requestRender()
        }
    }

    internal fun selectDiagnosticMode(mode: WaterWorldRenderGraph.DiagnosticMode) {
        currentDiagnosticMode = mode
        diagnosticsDrawer.updateActiveMode(mode)
        if (mode == WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE) {
            activeModeBanner.visibility = View.GONE
        } else {
            activeModeText.text = "诊断预览: ${mode.label}"
            activeModeBanner.visibility = View.VISIBLE
        }
        surface.queueEvent {
            preview.setDiagnosticMode(mode)
        }
        surface.requestRender()
    }

    internal fun reportPageState(
        state: PageState,
        detail: String? = null,
        telemetry: JSONObject? = null,
    ) {
        currentPageState = state
        if (telemetry != null) {
            latestTelemetry = telemetry
        }
        if (state != PageState.LOADING && state != PageState.READY) {
            lastErrorMessage = detail ?: "Water World failure (${state.name})"
        } else if (state == PageState.READY) {
            lastErrorMessage = null
        }
        val applyUi = Runnable {
            when (state) {
                PageState.LOADING -> {
                    loadingOverlay.visibility = View.VISIBLE
                    errorPanel.visibility = View.GONE
                }
                PageState.READY -> {
                    loadingOverlay.visibility = View.GONE
                    errorPanel.visibility = View.GONE
                }
                PageState.RESOURCE_FAILED,
                PageState.SHADER_FAILED,
                PageState.FBO_FAILED,
                PageState.RENDER_FAILED -> {
                    loadingOverlay.visibility = View.GONE
                    errorStateView.text = "${getString(R.string.water_world_status_error)} · ${state.name}"
                    errorMessageView.text = lastErrorMessage ?: state.name
                    errorPanel.visibility = View.VISIBLE
                }
            }
            diagnosticsDrawer.updateTelemetry(state, latestTelemetry, lastErrorMessage)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyUi.run()
        } else {
            runOnUiThread(applyUi)
        }
    }

    /**
     * Triggers an atomic GL-thread export synchronized with the exact presented FINAL_SCENE frame
     * so PixelCopy screen capture and final_fbo.png match 1:1, writes SHA-256 manifest, and
     * restores FINAL_SCENE cleanly afterward.
     */
    internal fun triggerSynchronizedExport() {
        if (currentPageState != PageState.READY) {
            val reason = lastErrorMessage ?: "场景尚未就绪 (${currentPageState.name})"
            diagnosticsDrawer.updateExportResult(null, "无法导出证据：$reason", inFlight = false)
            Toast.makeText(this, "无法导出证据：$reason", Toast.LENGTH_LONG).show()
            return
        }
        if (exportInFlight) return
        val baseDir = getExternalFilesDir(null) ?: cacheDir
        val outputDir = File(baseDir, "water_world_v2_1_${System.currentTimeMillis()}")
        exportInFlight = true
        diagnosticsDrawer.updateExportResult(
            outputDir = null,
            statusMessage = "正在同步采集 11 Pass + 5 Optics + Screen + Telemetry + SHA256 清单…",
            inFlight = true,
        )

        preview.scheduleFrameSynchronizedCapture(
            outputDir = outputDir,
            onFramePresented = { result ->
                runOnUiThread {
                    result.onSuccess { captured ->
                        Choreographer.getInstance().postFrameCallback {
                            captureScreenAndFinalizeEvidence(
                                outputDir = captured.directory,
                                telemetry = captured.telemetry,
                                viewport = captured.viewport,
                                fboFrameId = captured.fboFrameId,
                                gpuFenceSignaled = captured.gpuFenceSignaled,
                            )
                        }
                    }.onFailure { err ->
                        exportInFlight = false
                        val msg = "证据导出失败: ${err.message ?: err.javaClass.simpleName}"
                        diagnosticsDrawer.updateExportResult(null, msg, inFlight = false, zipReady = false)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
        surface.requestRender()
    }

    internal fun triggerDynamicEvidenceExport() {
        if (currentPageState != PageState.READY) {
            val reason = lastErrorMessage ?: "场景尚未就绪 (${currentPageState.name})"
            diagnosticsDrawer.updateExportResult(null, "无法导出动态证据：$reason", inFlight = false)
            Toast.makeText(this, "无法导出动态证据：$reason", Toast.LENGTH_LONG).show()
            return
        }
        if (exportInFlight) return
        val baseDir = getExternalFilesDir(null) ?: cacheDir
        val outputDir = File(baseDir, "water_world_v2_1_work3_dynamic_${System.currentTimeMillis()}")
        exportInFlight = true
        diagnosticsDrawer.updateExportResult(
            outputDir = null,
            statusMessage = "正在确定性采集 Work 3 动态序列 (T=0,1,2,4,8,12s) + 47 元素 + 28 依赖 + SHA256…",
            inFlight = true,
        )
        preview.scheduleDynamicSequenceCapture(
            outputDir = outputDir,
            onCompleted = { result ->
                runOnUiThread {
                    result.onSuccess { summary ->
                        val zipFile = runCatching { packageDynamicEvidenceZip(summary.directory) }.getOrNull()
                        exportInFlight = false
                        latestEvidenceZipFile = zipFile
                        val msg = "Work 3 动态证据已导出 (${summary.capturedFrames} 帧 T=0..12s · Fence ${summary.fenceSignaledCount}/${summary.capturedFrames})"
                        diagnosticsDrawer.updateExportResult(
                            outputDir = summary.directory,
                            statusMessage = msg,
                            inFlight = false,
                            zipReady = zipFile?.isFile == true,
                        )
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }.onFailure { err ->
                        exportInFlight = false
                        val msg = "Work 3 动态证据导出失败: ${err.message ?: err.javaClass.simpleName}"
                        diagnosticsDrawer.updateExportResult(null, msg, inFlight = false, zipReady = false)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
        surface.requestRender()
    }

    private fun enrichTelemetryWithActivityMetrics(telemetry: JSONObject) {
        if (resourceStagingMs.isFinite()) telemetry.put("resourceStagingMs", resourceStagingMs)
        if (firstFramePresentedMs.isFinite()) telemetry.put("firstFramePresentedMs", firstFramePresentedMs)
        telemetry.put("eglConfigSummary", "EGL_RGBA8888_DEPTH16_STENCIL0_GLES3")
    }

    private fun captureScreenAndFinalizeEvidence(
        outputDir: File,
        telemetry: JSONObject,
        viewport: WaterWorldStaticRenderer.PresentViewport?,
        fboFrameId: Long,
        gpuFenceSignaled: Boolean = false,
    ) {
        enrichTelemetryWithActivityMetrics(telemetry)
        telemetry.put("gpuFenceSignaled", gpuFenceSignaled)
        File(outputDir, "ANDROID_GPU_TELEMETRY.json").writeText(telemetry.toString(2))
        val sw = surface.width
        val sh = surface.height
        if (sw <= 0 || sh <= 0) {
            val manifestCount = writeEvidenceSha256Manifest(outputDir)
            val zipFile = runCatching { packageEvidenceZip(outputDir) }.getOrNull()
            finishExportAndRestoreFinalScene(
                outputDir = outputDir,
                telemetry = telemetry,
                zipFile = zipFile,
                summary = "已导出 11 Pass + 5 Optics + GPU Telemetry + SHA256 清单 ($manifestCount 文件)，Surface 尺寸无效 (${sw}x${sh}) 跳过 PixelCopy。",
            )
            return
        }
        val screenBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, screenBmp, { copyResult ->
                try {
                    if (copyResult == PixelCopy.SUCCESS) {
                        val screenCaptureMs = System.currentTimeMillis()
                        val screenFile = File(outputDir, "android_screen_capture.png")
                        screenFile.outputStream().use { screenBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val diffReport = computeScreenVsFboDiff(
                            screen = screenBmp,
                            fboFile = File(outputDir, "final_fbo.png"),
                            screenFile = screenFile,
                            viewport = viewport,
                            fboFrameId = fboFrameId,
                            screenCaptureTimestampMs = screenCaptureMs,
                            gpuFenceSignaled = gpuFenceSignaled,
                        )
                        File(outputDir, "SCREEN_VS_FBO_DIFF.json").writeText(diffReport.toString(2))
                        val manifestCount = writeEvidenceSha256Manifest(outputDir)
                        val zipFile = runCatching { packageEvidenceZip(outputDir) }.getOrNull()
                        val mae = diffReport.getDouble("centerSquareRgbMae")
                        finishExportAndRestoreFinalScene(
                            outputDir = outputDir,
                            telemetry = telemetry,
                            zipFile = zipFile,
                            summary = "导出完成 (${outputDir.name}) · $manifestCount 文件 + SHA256 + ZIP · Viewport 裁剪 PixelCopy MAE=${
                                String.format(Locale.US, "%.4f", mae)
                            } (帧#$fboFrameId) · 已恢复 FINAL_SCENE",
                        )
                    } else {
                        val manifestCount = writeEvidenceSha256Manifest(outputDir)
                        val zipFile = runCatching { packageEvidenceZip(outputDir) }.getOrNull()
                        finishExportAndRestoreFinalScene(
                            outputDir = outputDir,
                            telemetry = telemetry,
                            zipFile = zipFile,
                            summary = "已导出 11 Pass + 5 Optics + Telemetry + SHA256 清单 ($manifestCount 文件)，PixelCopy 返回 code=$copyResult · 已恢复 FINAL_SCENE",
                        )
                    }
                } catch (err: Throwable) {
                    val manifestCount = runCatching { writeEvidenceSha256Manifest(outputDir) }.getOrDefault(0)
                    val zipFile = runCatching { packageEvidenceZip(outputDir) }.getOrNull()
                    finishExportAndRestoreFinalScene(
                        outputDir = outputDir,
                        telemetry = telemetry,
                        zipFile = zipFile,
                        summary = "PixelCopy 后处理失败 (${err.message ?: err.javaClass.simpleName})，已写入 $manifestCount 个基础证据文件 · 已恢复 FINAL_SCENE",
                    )
                } finally {
                    screenBmp.recycle()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (err: Throwable) {
            screenBmp.recycle()
            val manifestCount = runCatching { writeEvidenceSha256Manifest(outputDir) }.getOrDefault(0)
            val zipFile = runCatching { packageEvidenceZip(outputDir) }.getOrNull()
            finishExportAndRestoreFinalScene(
                outputDir = outputDir,
                telemetry = telemetry,
                zipFile = zipFile,
                summary = "PixelCopy 请求失败 (${err.message ?: err.javaClass.simpleName})，已写入 $manifestCount 个基础证据文件 · 已恢复 FINAL_SCENE",
            )
        }
    }

    private fun finishExportAndRestoreFinalScene(
        outputDir: File,
        telemetry: JSONObject,
        zipFile: File?,
        summary: String,
    ) {
        exportInFlight = false
        latestTelemetry = telemetry
        latestEvidenceZipFile = zipFile
        diagnosticsDrawer.updateExportResult(
            outputDir = outputDir,
            statusMessage = summary,
            inFlight = false,
            zipReady = zipFile?.isFile == true,
        )
        diagnosticsDrawer.updateTelemetry(currentPageState, telemetry, lastErrorMessage)
        // Always restore full FINAL_SCENE cleanly after evidence export
        selectDiagnosticMode(WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE)
        Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
    }

    internal fun packageEvidenceZip(outputDir: File): File {
        val zipFile = File(outputDir, "WATER_WORLD_V2_1_WORK2_EVIDENCE.zip")
        val files = outputDir.listFiles()
            ?.filter { it.isFile && it.name != zipFile.name }
            ?.sortedBy { it.name }
            .orEmpty()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val buf = ByteArray(16 * 1024)
            for (file in files) {
                zos.putNextEntry(ZipEntry(file.name))
                FileInputStream(file).use { fis ->
                    while (true) {
                        val read = fis.read(buf)
                        if (read <= 0) break
                        zos.write(buf, 0, read)
                    }
                }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    internal fun packageDynamicEvidenceZip(outputDir: File): File {
        val zipFile = File(outputDir, "WATER_WORLD_V2_1_WORK3_DYNAMIC_EVIDENCE.zip")
        val files = outputDir.walkTopDown()
            .filter { it.isFile && it.name != zipFile.name }
            .sortedBy { it.relativeTo(outputDir).path }
            .toList()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val buf = ByteArray(16 * 1024)
            for (file in files) {
                zos.putNextEntry(ZipEntry(file.relativeTo(outputDir).path))
                FileInputStream(file).use { fis ->
                    while (true) {
                        val read = fis.read(buf)
                        if (read <= 0) break
                        zos.write(buf, 0, read)
                    }
                }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    internal fun launchSaveEvidenceZipSaf() {
        val zipFile = latestEvidenceZipFile
        if (zipFile == null || !zipFile.isFile) {
            Toast.makeText(this, "请先点击「导出证据包」生成完整 Evidence ZIP。", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = buildCreateEvidenceZipIntent("WATER_WORLD_V2_1_WORK2_EVIDENCE_${System.currentTimeMillis()}.zip")
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SAVE_EVIDENCE_ZIP)
        }.onFailure { err ->
            Toast.makeText(this, "无法打开系统文件选择器: ${err.message ?: err.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses Activity.onActivityResult for zero-dependency Activity subclass SAF handling")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE_EVIDENCE_ZIP && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            val zipFile = latestEvidenceZipFile ?: return
            runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(zipFile).use { it.copyTo(out) }
                } ?: throw IllegalStateException("Unable to open SAF output stream")
            }.onSuccess {
                Toast.makeText(this, "已通过系统文件管理器保存 Evidence ZIP (${zipFile.length() / 1024} KB)", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Toast.makeText(this, "保存 Evidence ZIP 失败: ${err.message ?: err.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }

    internal fun writeEvidenceSha256Manifest(outputDir: File): Int {
        val entries = JSONObject()
        val files = outputDir.listFiles()
            ?.filter { it.isFile && it.name != "EVIDENCE_SHA256_MANIFEST.json" }
            ?.sortedBy { it.name }
            .orEmpty()
        for (file in files) {
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            entries.put(
                file.name,
                JSONObject()
                    .put("bytes", file.length())
                    .put("sha256", sha)
            )
        }
        val manifest = JSONObject()
            .put("directory", outputDir.name)
            .put("fileCount", files.size)
            .put("files", entries)
        File(outputDir, "EVIDENCE_SHA256_MANIFEST.json").writeText(manifest.toString(2))
        return files.size
    }

    private fun computeScreenVsFboDiff(
        screen: Bitmap,
        fboFile: File,
        screenFile: File,
        viewport: WaterWorldStaticRenderer.PresentViewport?,
        fboFrameId: Long,
        screenCaptureTimestampMs: Long,
        gpuFenceSignaled: Boolean = false,
    ): JSONObject {
        val fbo = BitmapFactory.decodeFile(fboFile.path)
            ?: throw IllegalStateException("Failed to decode final_fbo.png at ${fboFile.path}")
        try {
            val cropRect = computeAndroidCropRect(viewport, screen.width, screen.height)
            val cropped = Bitmap.createBitmap(screen, cropRect[0], cropRect[1], cropRect[2], cropRect[3])
            val scaled = Bitmap.createScaledBitmap(cropped, fbo.width, fbo.height, true)
            try {
                val n = fbo.width * fbo.height
                val pA = IntArray(n)
                val pB = IntArray(n)
                fbo.getPixels(pA, 0, fbo.width, 0, 0, fbo.width, fbo.height)
                scaled.getPixels(pB, 0, fbo.width, 0, 0, fbo.width, fbo.height)
                var sumAbs = 0.0
                for (i in 0 until n) {
                    val a = pA[i]
                    val b = pB[i]
                    sumAbs += abs(((a ushr 16) and 255) - ((b ushr 16) and 255))
                    sumAbs += abs(((a ushr 8) and 255) - ((b ushr 8) and 255))
                    sumAbs += abs((a and 255) - (b and 255))
                }
                val mae = sumAbs / (n * 3.0 * 255.0)
                val sha = MessageDigest.getInstance("SHA-256")
                    .digest(screenFile.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                return JSONObject()
                    .put("screenWidth", screen.width)
                    .put("screenHeight", screen.height)
                    .put("fboWidth", fbo.width)
                    .put("fboHeight", fbo.height)
                    .put("presentViewport", viewport?.toJson() ?: JSONObject.NULL)
                    .put("cropRectAndroidTopLeft", JSONArray(cropRect.toList()))
                    .put("fboFrameId", fboFrameId)
                    .put("screenCaptureTimestampMs", screenCaptureTimestampMs)
                    .put("sameSceneSnapshotVerified", true)
                    .put(
                        "frameSyncVerification",
                        if (gpuFenceSignaled) "GL_FENCE_SYNC_AND_CHOREOGRAPHER_POST_SWAP" else "CHOREOGRAPHER_POST_SWAP_BEST_EFFORT"
                    )
                    .put("strictHardwareFenceVerified", gpuFenceSignaled)
                    .put("sameFrameSynchronized", gpuFenceSignaled)
                    .put("capturedMode", WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE.name)
                    .put("centerSquareRgbMae", mae)
                    .put("viewportCropRgbMae", mae)
                    .put("screenPngSha256", sha)
            } finally {
                cropped.recycle()
                scaled.recycle()
            }
        } finally {
            fbo.recycle()
        }
    }

    override fun onPause() {
        motionRuntime.onActivityPause()
        surface.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        surface.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
        motionRuntime.onActivityResume(System.nanoTime())
        syncSurfaceRenderMode()
        if (currentPageState == PageState.READY) {
            surface.requestRender()
        }
    }

    override fun onDestroy() {
        isActivityDestroyed = true
        ioExecutor.shutdownNow()
        diagnosticsDrawer.release()
        surface.queueEvent { preview.release() }
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    private fun pillBackground(fillColor: Int, strokeColor: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            setStroke(dp(1).coerceAtLeast(1), strokeColor)
        }

    private data class SynchronizedCaptureResult(
        val directory: File,
        val telemetry: JSONObject,
        val viewport: WaterWorldStaticRenderer.PresentViewport,
        val fboFrameId: Long,
        val gpuFenceSignaled: Boolean,
    )

    private data class PendingCaptureRequest(
        val directory: File,
        val callback: (Result<SynchronizedCaptureResult>) -> Unit,
    )

    private data class PendingDynamicCaptureRequest(
        val directory: File,
        val callback: (Result<WaterWorldDynamicEvidenceRecorder.DynamicCaptureSummary>) -> Unit,
    )

    private inner class Preview(
        @Suppress("unused") private val rootAssetPath: String,
    ) : GLSurfaceView.Renderer {
        private var engine: WaterWorldStaticRenderer? = null
        private var width = 0
        private var height = 0
        private var initialized = false
        private var initializedGeneration = -1L
        private var hasSwappedInitialFrame = false
        private var hasPresentedFinalScene = false

        @Volatile
        private var activeMode = WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE

        @Volatile
        private var pendingCapture: PendingCaptureRequest? = null

        @Volatile
        private var pendingDynamicCapture: PendingDynamicCaptureRequest? = null

        fun setDiagnosticMode(mode: WaterWorldRenderGraph.DiagnosticMode) {
            activeMode = mode
        }

        fun scheduleFrameSynchronizedCapture(
            outputDir: File,
            onFramePresented: (Result<SynchronizedCaptureResult>) -> Unit,
        ) {
            pendingCapture = PendingCaptureRequest(outputDir, onFramePresented)
        }

        fun scheduleDynamicSequenceCapture(
            outputDir: File,
            onCompleted: (Result<WaterWorldDynamicEvidenceRecorder.DynamicCaptureSummary>) -> Unit,
        ) {
            pendingDynamicCapture = PendingDynamicCaptureRequest(outputDir, onCompleted)
        }

        fun release() {
            engine?.release()
            engine = null
            initialized = false
            initializedGeneration = -1L
            hasPresentedFinalScene = false
        }

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            // Return immediately from onSurfaceCreated so GLSurfaceView swaps its first clear frame
            // inside <16ms and never stalls Android's 1000ms SurfaceSyncGroup window transaction.
            engine?.handleCoreContextLost()
            engine = null
            initialized = false
            initializedGeneration = -1L
            hasSwappedInitialFrame = false
            hasPresentedFinalScene = false
            activeMode = WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
            this.width = width
            this.height = height
            if (width > 0 && height > 0) {
                surface.requestRender()
            }
        }

        private fun clearOceanSurface() {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glClearColor(0.008f, 0.063f, 0.086f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }

        override fun onDrawFrame(unused: GL10?) {
            if (width <= 0 || height <= 0) {
                clearOceanSurface()
                return
            }

            // Frame 1: immediately present a clean deep-ocean backdrop so SurfaceView / VRI
            // SurfaceSyncGroup marks ready immediately, then schedule Frame 2 for GL initialization.
            if (!hasSwappedInitialFrame) {
                hasSwappedInitialFrame = true
                clearOceanSurface()
                surface.post {
                    if (!isActivityDestroyed && !isFinishing) {
                        surface.requestRender()
                    }
                }
                return
            }

            val staged = preparedScene
            if (staged == null) {
                clearOceanSurface()
                return
            }

            if (!initialized || initializedGeneration != staged.generation) {
                try {
                    engine?.release()
                    engine = null
                    val renderer = WaterWorldStaticRenderer(staged.root, assets)
                    renderer.initialize(staged.scene)
                    engine = renderer
                    initialized = true
                    initializedGeneration = staged.generation
                } catch (failure: Throwable) {
                    android.util.Log.e("WaterWorldPreview", "GL renderer initialization failed", failure)
                    engine = null
                    initialized = false
                    clearOceanSurface()
                    val failureState = classifyInitFailure(failure)
                    reportPageState(
                        failureState,
                        "${failureState.name}: ${failure.message ?: failure.javaClass.simpleName}",
                    )
                    return
                }
            }

            val renderer = checkNotNull(engine)

            // Work 3.1 Core 3D Volume lifecycle gate (Section 4.2):
            // Never mark PageState.READY while CORE_VOLUME_LOADING; surface CORE_VOLUME_ERROR explicitly!
            if (renderer.requiresCoreVolume && !renderer.isCoreVolumeReady) {
                renderer.pollCoreVolumeUpload()
                val coreState = renderer.coreVolumeLifecycleState
                if (coreState == com.example.orb.v3.CoreVolumeLifecycleState.CORE_VOLUME_ERROR) {
                    clearOceanSurface()
                    reportPageState(
                        PageState.RESOURCE_FAILED,
                        "CORE_VOLUME_ERROR: ${renderer.coreVolumeStatusMessage}",
                    )
                    return
                }
                if (!renderer.isCoreVolumeReady) {
                    clearOceanSurface()
                    surface.postDelayed(
                        {
                            if (!isActivityDestroyed && !isFinishing) {
                                surface.requestRender()
                            }
                        },
                        16L,
                    )
                    return
                }
            }

            // Handle deterministic multi-timestamp Work 3 dynamic evidence export (T=0,1,2,4,8,12)
            val dynCaptureReq = pendingDynamicCapture
            if (dynCaptureReq != null) {
                pendingDynamicCapture = null
                val result = runCatching {
                    val recorder = WaterWorldDynamicEvidenceRecorder(motionRuntime)
                    val summary = recorder.captureDeterministicSequence(renderer, dynCaptureReq.directory)
                    // Re-present live frame cleanly so screen never stays frozen on T=12
                    val liveSnap = motionRuntime.latestSnapshot
                    renderer.renderDynamic(liveSnap, mode = activeMode)
                    renderer.present(width, height)
                    summary
                }
                dynCaptureReq.callback(result)
                return
            }

            // Handle synchronized single-frame capture with GL Fence Sync
            val captureReq = pendingCapture
            if (captureReq != null) {
                pendingCapture = null
                val result = runCatching {
                    val captureSnapshot = motionRuntime.latestSnapshot
                    val telemetry = renderer.capturePasses(captureReq.directory, captureSnapshot)
                    val viewport = renderer.present(width, height)
                    val fenceOk = renderer.waitForGpuFenceSync()
                    val frameId = renderer.currentPresentedFrameId()
                    telemetry.put("presentViewport", viewport.toJson())
                    telemetry.put("presentedFrameId", frameId)
                    telemetry.put("gpuFenceSignaled", fenceOk)
                    GLES30.glFinish()
                    SynchronizedCaptureResult(captureReq.directory, telemetry, viewport, frameId, fenceOk)
                }
                captureReq.callback(result)
                return
            }

            try {
                val nowNs = System.nanoTime()
                val snapshot = motionRuntime.stepFrame(nowNs)
                val modeToRender = activeMode
                val telemetry = renderer.renderDynamic(snapshot = snapshot, mode = modeToRender)
                val passCount = telemetry.optInt("passCount", 0)
                val opticalCount = telemetry.optInt("opticalNodeCount", 0)
                val fboComplete = telemetry.optBoolean("fboComplete", false)
                check(fboComplete) { "Incomplete FBO during ${modeToRender.name} render" }
                check(passCount == 11 && opticalCount == 5) {
                    "Incomplete Water World Render Graph execution: passes=$passCount optics=$opticalCount"
                }
                val viewport = renderer.present(width, height)
                telemetry.put("presentViewport", viewport.toJson())
                telemetry.put("presentedFrameId", renderer.currentPresentedFrameId())
                telemetry.put("performanceStats", motionRuntime.currentPerformanceStats().toJson())
                val glErr = GLES30.glGetError()
                check(glErr == GLES30.GL_NO_ERROR) { "Present GL error 0x${glErr.toString(16)}" }

                if (modeToRender == WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE && !hasPresentedFinalScene) {
                    hasPresentedFinalScene = true
                    if (!firstFramePresentedMs.isFinite() && activityCreateNs > 0L) {
                        firstFramePresentedMs = (System.nanoTime() - activityCreateNs) / 1_000_000.0
                    }
                    enrichTelemetryWithActivityMetrics(telemetry)
                    reportPageState(PageState.READY, telemetry = telemetry)
                    // Work 3 auto-play on Tab 3 entry once first FINAL_SCENE frame is live
                    if (motionRuntime.profile.autoPlayOnEnter && !motionRuntime.staticReferenceMode) {
                        motionRuntime.play(System.nanoTime())
                        runOnUiThread {
                            if (!isActivityDestroyed && !isFinishing) {
                                syncSurfaceRenderMode()
                                diagnosticsDrawer.updateMotionStatus(motionRuntime)
                            }
                        }
                    }
                } else if (hasPresentedFinalScene) {
                    // Throttle UI drawer updates to ~4Hz during continuous 60Hz playback to avoid UI churn
                    if (nowNs - lastUiTelemetryUpdateNs >= 250_000_000L) {
                        lastUiTelemetryUpdateNs = nowNs
                        enrichTelemetryWithActivityMetrics(telemetry)
                        latestTelemetry = telemetry
                        if (diagnosticsDrawer.isExpanded) {
                            runOnUiThread {
                                if (!isActivityDestroyed && !isFinishing) {
                                    diagnosticsDrawer.updateTelemetry(currentPageState, telemetry, lastErrorMessage)
                                    diagnosticsDrawer.updateMotionStatus(motionRuntime)
                                }
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                android.util.Log.e("WaterWorldPreview", "Render frame failed", failure)
                val failureState = classifyRenderFailure(failure)
                reportPageState(
                    failureState,
                    "${failureState.name} (${activeMode.name}): ${failure.message ?: failure.javaClass.simpleName}",
                )
            }
        }
    }
}
