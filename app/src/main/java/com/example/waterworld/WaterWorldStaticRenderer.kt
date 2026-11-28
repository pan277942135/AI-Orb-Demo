package com.example.waterworld

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.os.Debug
import com.example.orb.scene.OrbFluidVolumeRenderer
import com.example.orb.v3.CoreVolumeLifecycleState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.sin

/**
 * Production Android OpenGL ES 3.0 renderer for Water World V2.1 (Work 1.6 / Work 2 / Work 3 / Work 3.1 Core 3D Volume).
 *
 * Executes all 11 canonical composition stages and 5 independent optical nodes via
 * [WaterWorldRenderGraph], [WaterWorldRenderTargetPool], [WaterWorldTextureCache], and [WaterWorldShaders],
 * plus AI Orb V3's real 32 Z-slice `GL_TEXTURE_3D` (`256x256x32`) raymarched Core fluid volume inside
 * Pass `04_WATER_INTERIOR` via [OrbFluidVolumeRenderer.drawVolumeOnly] (zero duplicate glass shell).
 */
internal class WaterWorldStaticRenderer(
    private val root: File,
    private val assetManager: AssetManager? = null,
) {

    data class Binding(
        val objectId: String,
        val passId: WaterWorldRenderGraph.PassId = WaterWorldRenderGraph.PassId.NEAR_ECOLOGY,
        val shaderProgram: WaterWorldRenderGraph.ShaderProgramId = WaterWorldRenderGraph.ShaderProgramId.TRANSPARENT,
        val materialName: String = "plant",
        val meshPath: String,
        val model: FloatArray,
        val tint: FloatArray,
        val coreColor: FloatArray = floatArrayOf(1f, 0.95f, 0.85f),
        val texturePath: String?,
        val textureSha256: String?,
        val emission: Float,
        val ambient: Float,
        val lightDirection: FloatArray,
        val lightColor: FloatArray,
        val depthTest: Boolean = true,
        val depthWrite: Boolean = false,
        val blendMode: WaterWorldRenderGraph.BlendMode = WaterWorldRenderGraph.BlendMode.ALPHA,
        val alphaCutoff: Float = 0f,
        val fresnel: Float = 0f,
        val shellSide: Float = 0f,
        val sphereClip: Boolean = false,
        val ribbonStyle: Float = 0f,
        val hypothesisStatus: String = "GROUND_TRUTH_VERIFIED"
    )

    data class PresentViewport(
        val left: Int,
        val bottom: Int,
        val side: Int,
        val surfaceWidth: Int,
        val surfaceHeight: Int
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("left", left)
            .put("bottom", bottom)
            .put("side", side)
            .put("surfaceWidth", surfaceWidth)
            .put("surfaceHeight", surfaceHeight)
            .put("coordinateOrigin", "OPENGL_BOTTOM_LEFT")
            .put("scaleToCanvas", if (side > 0) side.toDouble() / 1254.0 else 0.0)
            .put("letterboxHorizontalPx", (surfaceWidth - side).coerceAtLeast(0))
            .put("letterboxVerticalPx", (surfaceHeight - side).coerceAtLeast(0))
    }

    private data class DrawItem(
        val binding: Binding,
        val mesh: WaterWorldMesh,
        val texture: Int,
        val residentGroupKey: String?,
        val residentSegmentMapping: WaterWorldResidentContractV1.SegmentMapping?,
        val plantElementId: String?,
        val rockElementId: String?,
    )

    private val loader = WaterWorldAssetLoader(root)
    private val renderGraph = WaterWorldRenderGraph()
    private val drawsByPass = LinkedHashMap<WaterWorldRenderGraph.PassId, MutableList<DrawItem>>()
    private val allDraws = mutableListOf<DrawItem>()
    private val scratchModel = FloatArray(16)
    private val scratchBeamBuf = FloatArray(18)
    private var textureCache: WaterWorldTextureCache? = null
    private var shaders: WaterWorldShaders? = null
    private var targetPool: WaterWorldRenderTargetPool? = null
    private var opticalConfig: WaterWorldSceneBindings.OpticalConfig? = null
    private var motionProfile: WaterWorldMotionProfile = WaterWorldMotionProfile.defaultProfile()
    private var whiteTexture = 0
    private var fullscreenVao = 0
    private var waterNormalTex = 0
    private var waterDepthTex = 0
    private var causticsHeightTex = 0
    private var glassShellMaskTex = 0
    private var coreVolumeRenderer: OrbFluidVolumeRenderer? = null
    private var coreVolumeCamera: WaterWorldCoreMotionAdapter.AlignedVolumeCamera? = null
    @Volatile private var lastCoreInspection: OrbFluidVolumeRenderer.CoreGpuInspection? = null
    @Volatile private var lastPresentViewport: PresentViewport? = null
    @Volatile private var presentedFrameId: Long = 0L
    @Volatile private var glErrorCount: Int = 0

    internal fun currentPresentViewport(): PresentViewport? = lastPresentViewport
    internal fun currentPresentedFrameId(): Long = presentedFrameId
    internal fun currentGlErrorCount(): Int = glErrorCount
    internal val requiresCoreVolume: Boolean get() = assetManager != null
    internal val isCoreVolumeReady: Boolean
        get() = coreVolumeRenderer?.isVolumeReady ?: (assetManager == null)
    internal val coreVolumeLifecycleState: CoreVolumeLifecycleState
        get() = coreVolumeRenderer?.lifecycleState ?: CoreVolumeLifecycleState.CORE_VOLUME_LOADING
    internal val coreVolumeStatusMessage: String
        get() = coreVolumeRenderer?.volumeTelemetry?.statusMessage ?: "Pending Core 3D Volume"

    internal fun pollCoreVolumeUpload(): Boolean {
        val coreVol = coreVolumeRenderer ?: return false
        return coreVol.uploadReadyAssetsIfNeeded(assetManager)
    }

    internal fun handleCoreContextLost() {
        coreVolumeRenderer?.handleContextLost()
    }

    fun initialize(bindings: List<Binding>) {
        val loaded = WaterWorldSceneBindings.loadScene(root)
        initialize(loaded.copy(bindings = bindings))
    }

    fun initialize(scene: WaterWorldSceneBindings.LoadedScene) {
        check(shaders == null) { "Renderer already initialized" }
        val bindings = scene.bindings
        require(bindings.isNotEmpty() && bindings.map { it.objectId }.distinct().size == bindings.size) {
            "Bindings must be non-empty with unique objectIds"
        }
        renderGraph.validateGraph()
        try {
            loader.resetUploadedStats()
            motionProfile = WaterWorldMotionProfile.loadFromAssetRoot(root)
            opticalConfig = scene.opticalConfig
            shaders = WaterWorldShaders()
            targetPool = WaterWorldRenderTargetPool(scene.canvasSize)

            val vaoArr = IntArray(1)
            GLES30.glGenVertexArrays(1, vaoArr, 0)
            fullscreenVao = vaoArr[0]

            val texIds = IntArray(1)
            GLES30.glGenTextures(1, texIds, 0)
            whiteTexture = texIds[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, whiteTexture)
            val pixel = ByteBuffer.allocateDirect(4).put(byteArrayOf(-1, -1, -1, -1))
            pixel.position(0)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixel
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)

            val cache = WaterWorldTextureCache(
                uploader = { path, sha -> loader.texture(path, sha) },
                deleter = { id -> GLES30.glDeleteTextures(1, intArrayOf(id), 0) }
            )
            textureCache = cache

            val opt = scene.opticalConfig
            waterNormalTex = cache.acquire(opt.waterNormalTex.path, opt.waterNormalTex.sha256)
            waterDepthTex = cache.acquire(opt.waterDepthTex.path, opt.waterDepthTex.sha256)
            causticsHeightTex = cache.acquire(opt.causticsHeightTex.path, opt.causticsHeightTex.sha256)
            glassShellMaskTex = cache.acquire(opt.glassShellMaskTex.path, opt.glassShellMaskTex.sha256)

            WaterWorldRenderGraph.PassId.entries.forEach { drawsByPass[it] = mutableListOf() }
            for (binding in bindings) {
                require(binding.model.size == 16 && binding.tint.size == 4)
                require(binding.lightDirection.size == 3 && binding.lightColor.size == 3)
                require((binding.model + binding.tint + binding.lightDirection + binding.lightColor).all { it.isFinite() })
                require(binding.emission.isFinite() && binding.ambient.isFinite())
                val texture = binding.texturePath?.let { path ->
                    cache.acquire(path, requireNotNull(binding.textureSha256))
                } ?: whiteTexture
                val isPlantMesh = binding.materialName == "plant"
                val isResidentMesh = binding.passId == WaterWorldRenderGraph.PassId.RESIDENT_BACK ||
                    binding.passId == WaterWorldRenderGraph.PassId.RESIDENT_FRONT
                val mesh = WaterWorldMesh(
                    json = loader.json(binding.meshPath),
                    subdividePlantGrid = isPlantMesh,
                    computeRibbonHalfWidths = isResidentMesh,
                )
                val residentKey = resolveResidentGroupKey(binding.objectId)
                val residentSegMap = if (isResidentMesh) {
                    WaterWorldResidentContractV1.mappingForSegment(binding.objectId)
                } else {
                    null
                }
                val plantId = resolvePlantElementId(binding.objectId, isPlantMesh)
                val rockId = resolveRockElementId(binding.objectId, binding.materialName == "rock")
                val item = DrawItem(binding, mesh, texture, residentKey, residentSegMap, plantId, rockId)
                allDraws += item
                drawsByPass.getValue(binding.passId) += item
            }

            coreVolumeCamera = WaterWorldCoreMotionAdapter.buildAlignedVolumeCamera(
                canvasWidth = scene.canvasSize.toFloat(),
                canvasHeight = scene.canvasSize.toFloat(),
                sphereCenterX = opt.sphereCenterPx[0],
                sphereCenterY = opt.sphereCenterPx[1],
                sphereInnerRadiusPx = opt.sphereInnerRadiusPx,
            )
            if (assetManager != null) {
                val volRenderer = OrbFluidVolumeRenderer()
                volRenderer.init(subdivisions = 4, assetManager = assetManager)
                coreVolumeRenderer = volRenderer
            }
            checkError("initialize")
        } catch (failure: Throwable) {
            release()
            throw failure
        }
    }

    /**
     * Backward-compatible Work 1.6 / Work 2 render entry point.
     * Delegates to [renderDynamic] using exact T=0 static snapshot when `staticReferenceMode` is true
     * or `timeSeconds == 0.0`.
     */
    fun render(
        mode: WaterWorldRenderGraph.DiagnosticMode = WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE,
        snapshot: WaterWorldMotionContract.SharedFrameSnapshot = WaterWorldMotionContract.SharedFrameSnapshot.staticZero(),
        capturePassDir: File? = null
    ): JSONObject {
        require(snapshot.timeSeconds >= 0.0 && snapshot.timeSeconds.isFinite())
        val dynamicSnapshot = WaterWorldMotionEvaluator.evaluate(
            profile = motionProfile,
            timeSeconds = snapshot.timeSeconds,
            frameId = snapshot.frameIndex,
            randomSeed = motionProfile.deterministicSeed,
            staticReferenceMode = snapshot.staticReferenceMode || snapshot.timeSeconds <= 0.0,
        )
        return renderDynamic(dynamicSnapshot, mode, capturePassDir)
    }

    /**
     * Work 3 authoritative dynamic render pass: consumes ONE immutable [WaterWorldDynamicFrameSnapshot]
     * across all 11 composition stages and all 5 independent optical nodes with zero per-frame
     * texture uploads or mesh rebuilds.
     */
    fun renderDynamic(
        snapshot: WaterWorldDynamicFrameSnapshot,
        mode: WaterWorldRenderGraph.DiagnosticMode = WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE,
        capturePassDir: File? = null
    ): JSONObject {
        val cpuStartNs = System.nanoTime()
        val s = checkNotNull(shaders) { "Renderer not initialized" }
        val pool = checkNotNull(targetPool) { "Target pool not initialized" }
        val opt = checkNotNull(opticalConfig) { "Optical config not initialized" }
        val cache = checkNotNull(textureCache) { "Texture cache not initialized" }
        require(snapshot.globalTimeSeconds >= 0.0 && snapshot.globalTimeSeconds.isFinite())

        val passRecords = mutableListOf<WaterWorldRenderGraph.PassExecutionRecord>()
        val passPngShas = JSONObject()
        val opticalPngShas = JSONObject()
        val opticalNodesExecuted = mutableSetOf<String>()

        val canvasW = pool.fullSize.toFloat()
        val canvasH = pool.fullSize.toFloat()

        // Clear main scene FBO
        pool.scene.bind()
        GLES30.glDepthMask(true)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        for (stage in renderGraph.stages) {
            val stageStartNs = System.nanoTime()
            val stageDraws = drawsByPass[stage.passId].orEmpty()
            var drawCount = 0

            when (stage.passId) {
                WaterWorldRenderGraph.PassId.BACKGROUND -> {
                    pool.scene.bind()
                    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
                    GLES30.glDepthMask(true)
                    GLES30.glDisable(GLES30.GL_BLEND)
                    s.opaque.use()
                    GLES30.glUniform2f(s.opaque.uniform("uCanvas"), canvasW, canvasH)
                    GLES30.glUniform1i(s.opaque.uniform("uDeformMode"), 0)
                    GLES30.glUniform4f(s.opaque.uniform("uDeformParams"), 0f, 0f, 0f, 0f)
                    GLES30.glUniform2f(s.opaque.uniform("uSphereCenter"), opt.sphereCenterPx[0], opt.sphereCenterPx[1])
                    GLES30.glUniform1f(s.opaque.uniform("uTopGlareSuppression"), opt.topGlareSuppression)
                    GLES30.glUniform1f(s.opaque.uniform("uVignetteStrength"), opt.vignetteStrength)
                    GLES30.glUniform3fv(s.opaque.uniform("uDeepTint"), 1, opt.bgDeepTint, 0)
                    GLES30.glUniform2fv(s.opaque.uniform("uHaloCenter"), 1, opt.bgHaloCenter, 0)
                    GLES30.glUniform1f(s.opaque.uniform("uHaloRadius"), opt.bgHaloRadius)
                    GLES30.glUniform3fv(s.opaque.uniform("uHaloColor"), 1, opt.bgHaloColor, 0)
                    GLES30.glUniform1f(s.opaque.uniform("uHaloIntensity"), opt.bgHaloIntensity)
                    GLES30.glUniform1i(s.opaque.uniform("uTexture"), 0)
                    for (item in stageDraws) {
                        GLES30.glUniformMatrix4fv(s.opaque.uniform("uModel"), 1, false, item.binding.model, 0)
                        bindTex(0, item.texture)
                        item.mesh.draw()
                        drawCount++
                    }
                    drawCount += renderBokehLayer(s, opt, snapshot, "BG", pool.scene)
                    opticalNodesExecuted += WaterWorldRenderGraph.OpticalNodeId.BOKEH.wireId
                }

                WaterWorldRenderGraph.PassId.RESIDENT_BACK,
                WaterWorldRenderGraph.PassId.RESIDENT_FRONT -> {
                    pool.scene.bind()
                    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
                    GLES30.glDepthMask(false)
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR)
                    s.residentRibbon.use()
                    GLES30.glUniform2f(s.residentRibbon.uniform("uCanvas"), canvasW, canvasH)
                    for (item in stageDraws) {
                        val rState = item.residentGroupKey?.let { snapshot.residentStates[it] }
                        val segMap = item.residentSegmentMapping
                            ?: WaterWorldResidentContractV1.mappingForSegment(item.binding.objectId)
                        val deformMode = if (!snapshot.staticReferenceMode && rState != null) 3 else 0
                        GLES30.glUniform1i(s.residentRibbon.uniform("uDeformMode"), deformMode)
                        GLES30.glUniform4f(
                            s.residentRibbon.uniform("uSegmentArcRange"),
                            segMap.segmentArcLengthStart,
                            segMap.segmentArcLengthEnd,
                            segMap.localUvDirection,
                            segMap.localFlowPhaseOffset,
                        )
                        if (deformMode == 3 && rState != null) {
                            GLES30.glUniform4f(
                                s.residentRibbon.uniform("uDeformParams"),
                                rState.widthAmplitude,
                                rState.lateralWavePx,
                                rState.wavePhaseRad,
                                rState.widthTimePhaseRad,
                            )
                            GLES30.glUniform1f(s.residentRibbon.uniform("uFlowOffsetU"), rState.flowOffsetU)
                            GLES30.glUniform1f(s.residentRibbon.uniform("uBrightnessScale"), rState.brightnessScale)
                            GLES30.glUniform4f(
                                s.residentRibbon.uniform("uResidentFlowParams"),
                                rState.flowActive,
                                rState.brightnessAmplitude,
                                rState.brightnessTimePhaseRad,
                                rState.flowDirection,
                            )
                        } else {
                            GLES30.glUniform4f(s.residentRibbon.uniform("uDeformParams"), 0f, 0f, 0f, 0f)
                            GLES30.glUniform1f(s.residentRibbon.uniform("uFlowOffsetU"), 0f)
                            GLES30.glUniform1f(s.residentRibbon.uniform("uBrightnessScale"), 1f)
                            GLES30.glUniform4f(s.residentRibbon.uniform("uResidentFlowParams"), 0f, 0f, 0f, 1f)
                        }
                        GLES30.glUniformMatrix4fv(s.residentRibbon.uniform("uModel"), 1, false, item.binding.model, 0)
                        GLES30.glUniform4fv(s.residentRibbon.uniform("uTint"), 1, item.binding.tint, 0)
                        GLES30.glUniform3fv(s.residentRibbon.uniform("uCoreColor"), 1, item.binding.coreColor, 0)
                        GLES30.glUniform1f(s.residentRibbon.uniform("uEmission"), item.binding.emission)
                        GLES30.glUniform1f(s.residentRibbon.uniform("uRibbonStyle"), item.binding.ribbonStyle)
                        item.mesh.draw()
                        drawCount++
                    }
                }

                WaterWorldRenderGraph.PassId.SPHERE_BACK -> {
                    pool.copyColor(pool.scene, pool.snapshot, "pass03_pre_glass_back")
                    renderGlassPass(s, opt, snapshot, pool.snapshot, pool.scene, passSide = 0, diagnosticOnly = false)
                    drawCount = 1
                    opticalNodesExecuted += WaterWorldRenderGraph.OpticalNodeId.GLASS_REFRACTION.wireId
                }

                WaterWorldRenderGraph.PassId.WATER_INTERIOR -> {
                    pool.copyColor(pool.scene, pool.snapshot, "pass04_pre_water")
                    val waterbedTex = stageDraws.firstOrNull()?.texture ?: whiteTexture
                    renderWaterPass(s, opt, snapshot, pool.snapshot, waterbedTex, pool.scene, diagnosticOnly = false)
                    drawCount = 1
                    opticalNodesExecuted += WaterWorldRenderGraph.OpticalNodeId.WATER_NORMAL_DISTORTION.wireId

                    // Work 3.1 Real 32 Z-Slice GL_TEXTURE_3D Core Volume Sub-pass inside Pass 04_WATER_INTERIOR.
                    // Strictly zero duplicate glass shell, zero FBO feedback, zero silent 2D fallback on error.
                    val coreVol = coreVolumeRenderer
                    val coreCam = coreVolumeCamera
                    if (coreVol != null && coreCam != null) {
                        coreVol.uploadReadyAssetsIfNeeded(assetManager)
                        if (coreVol.volumeTelemetry.hasError) {
                            throw IllegalStateException("CORE_VOLUME_ERROR: ${coreVol.volumeTelemetry.statusMessage}")
                        }
                        if (coreVol.isVolumeReady) {
                            pool.copyColor(pool.scene, pool.snapshot, "pass04_pre_core_3d_volume")
                            val orbMotion = WaterWorldCoreMotionAdapter.toAIOrbMotionSnapshot(snapshot)
                            pool.scene.bind()
                            lastCoreInspection = coreVol.drawVolumeOnly(
                                modelMatrix = coreCam.modelMatrix,
                                invModelMatrix = coreCam.invModelMatrix,
                                viewMatrix = coreCam.viewMatrix,
                                projMatrix = coreCam.projMatrix,
                                cameraPos = coreCam.cameraPos,
                                refractionTexId = pool.snapshot.texture,
                                fboWidth = pool.fullSize,
                                fboHeight = pool.fullSize,
                                motion = orbMotion,
                                densityScale = snapshot.coreState.densityScale,
                                colorShift = snapshot.coreState.colorShift,
                                diagMode = OrbFluidVolumeRenderer.CoreDiagMode.PRODUCTION_COMPOSITE,
                            )
                            drawCount += 1

                            val requestedCoreDiag = coreDiagModeFor(mode)
                            if (requestedCoreDiag != null) {
                                pool.diagnostic.bind()
                                GLES30.glClearColor(0.008f, 0.036f, 0.058f, 1f)
                                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                                lastCoreInspection = coreVol.drawVolumeOnly(
                                    modelMatrix = coreCam.modelMatrix,
                                    invModelMatrix = coreCam.invModelMatrix,
                                    viewMatrix = coreCam.viewMatrix,
                                    projMatrix = coreCam.projMatrix,
                                    cameraPos = coreCam.cameraPos,
                                    refractionTexId = pool.snapshot.texture,
                                    fboWidth = pool.fullSize,
                                    fboHeight = pool.fullSize,
                                    motion = orbMotion,
                                    densityScale = snapshot.coreState.densityScale,
                                    colorShift = snapshot.coreState.colorShift,
                                    diagMode = requestedCoreDiag,
                                )
                            }
                        }
                    }

                    if (capturePassDir != null || mode == WaterWorldRenderGraph.DiagnosticMode.OPTICAL_WATER_DISTORTION) {
                        renderWaterPass(s, opt, snapshot, pool.snapshot, waterbedTex, pool.diagnostic, diagnosticOnly = true)
                        if (capturePassDir != null) {
                            opticalPngShas.put(
                                "water_normal_distortion",
                                captureTarget(pool.diagnostic, File(capturePassDir, "opt_water_normal_distortion.png"))
                            )
                        }
                    }
                }

                WaterWorldRenderGraph.PassId.FAR_ECOLOGY,
                WaterWorldRenderGraph.PassId.FISH,
                WaterWorldRenderGraph.PassId.NEAR_ECOLOGY -> {
                    pool.scene.bind()
                    GLES30.glEnable(GLES30.GL_DEPTH_TEST)
                    GLES30.glDepthFunc(GLES30.GL_LEQUAL)
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFuncSeparate(
                        GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA,
                        GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA
                    )
                    for (item in stageDraws) {
                        val b = item.binding
                        GLES30.glDepthMask(b.depthWrite)
                        if (b.shaderProgram == WaterWorldRenderGraph.ShaderProgramId.PARTICLE_BOKEH) {
                            drawParticleQuad(s, opt, snapshot, item, canvasW, canvasH, isOuterDroplet = false)
                        } else {
                            drawTransparentEntity(
                                s, opt, snapshot, item, canvasW, canvasH,
                                farHaze = if (stage.passId == WaterWorldRenderGraph.PassId.FAR_ECOLOGY) 0.45f else 0f
                            )
                        }
                        drawCount++
                    }
                }

                WaterWorldRenderGraph.PassId.LIGHTING -> {
                    renderCausticsPass(s, opt, snapshot, pool.scene, diagnosticOnly = false)
                    drawCount = 1
                    opticalNodesExecuted += WaterWorldRenderGraph.OpticalNodeId.CAUSTICS.wireId
                    if (capturePassDir != null || mode == WaterWorldRenderGraph.DiagnosticMode.OPTICAL_CAUSTICS) {
                        pool.diagnostic.bind()
                        GLES30.glClearColor(0f, 0f, 0f, 1f)
                        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                        renderCausticsPass(s, opt, snapshot, pool.diagnostic, diagnosticOnly = true)
                        if (capturePassDir != null) {
                            opticalPngShas.put(
                                "caustics",
                                captureTarget(pool.diagnostic, File(capturePassDir, "opt_caustics.png"))
                            )
                        }
                    }
                }

                WaterWorldRenderGraph.PassId.SPHERE_FRONT -> {
                    pool.copyColor(pool.scene, pool.snapshot, "pass09_pre_glass_front")
                    renderGlassPass(s, opt, snapshot, pool.snapshot, pool.scene, passSide = 1, diagnosticOnly = false)
                    drawCount = 1
                    opticalNodesExecuted += WaterWorldRenderGraph.OpticalNodeId.GLASS_REFRACTION.wireId
                    if (capturePassDir != null || mode == WaterWorldRenderGraph.DiagnosticMode.OPTICAL_GLASS_REFRACTION) {
                        renderGlassPass(s, opt, snapshot, pool.snapshot, pool.diagnostic, passSide = 1, diagnosticOnly = true)
                        if (capturePassDir != null) {
                            opticalPngShas.put(
                                "glass_refraction",
                                captureTarget(pool.diagnostic, File(capturePassDir, "opt_glass_refraction.png"))
                            )
                        }
                    }
                }

                WaterWorldRenderGraph.PassId.FINAL_COMPOSITE -> {
                    runBloomChain(s, opt, snapshot, pool, diagnosticOnly = false)
                    opticalNodesExecuted += WaterWorldRenderGraph.OpticalNodeId.BLOOM.wireId
                    if (capturePassDir != null || mode == WaterWorldRenderGraph.DiagnosticMode.OPTICAL_BLOOM) {
                        runBloomChain(s, opt, snapshot, pool, diagnosticOnly = true)
                        if (capturePassDir != null) {
                            opticalPngShas.put(
                                "bloom",
                                captureTarget(pool.diagnostic, File(capturePassDir, "opt_bloom.png"))
                            )
                        }
                    }
                    drawCount += renderBokehLayer(s, opt, snapshot, "FG", pool.finalTarget)
                    pool.finalTarget.bind()
                    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                    GLES30.glDepthMask(false)
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFuncSeparate(
                        GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA,
                        GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA
                    )
                    for (item in stageDraws) {
                        drawParticleQuad(s, opt, snapshot, item, canvasW, canvasH, isOuterDroplet = true)
                        drawCount++
                    }
                    if (capturePassDir != null || mode == WaterWorldRenderGraph.DiagnosticMode.OPTICAL_BOKEH) {
                        pool.diagnostic.bind()
                        GLES30.glClearColor(0f, 0f, 0f, 1f)
                        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                        renderBokehLayer(s, opt, snapshot, "BG", pool.diagnostic)
                        renderBokehLayer(s, opt, snapshot, "FG", pool.diagnostic)
                        if (capturePassDir != null) {
                            opticalPngShas.put(
                                "bokeh",
                                captureTarget(pool.diagnostic, File(capturePassDir, "opt_bokeh.png"))
                            )
                        }
                    }
                }
            }

            checkError(stage.passId.wireId)
            val stageMs = (System.nanoTime() - stageStartNs) / 1_000_000.0
            passRecords += WaterWorldRenderGraph.PassExecutionRecord(
                passId = stage.passId,
                drawCount = drawCount,
                elapsedCpuMs = stageMs,
                fboComplete = true,
                depthWriteEnabled = stage.depthWrite,
                opticalNodesApplied = stage.opticalNodes.map { it.wireId }
            )

            val currentTarget = if (stage.passId == WaterWorldRenderGraph.PassId.FINAL_COMPOSITE) {
                pool.finalTarget
            } else {
                pool.scene
            }
            if (capturePassDir != null) {
                val pFile = File(capturePassDir, "${stage.passId.wireId.lowercase()}.png")
                passPngShas.put(stage.passId.wireId, captureTarget(currentTarget, pFile))
            }
            if ((mode == WaterWorldRenderGraph.DiagnosticMode.PASS_RESIDENT_BACK &&
                    stage.passId == WaterWorldRenderGraph.PassId.RESIDENT_BACK) ||
                (mode == WaterWorldRenderGraph.DiagnosticMode.PASS_FISH &&
                    stage.passId == WaterWorldRenderGraph.PassId.FISH) ||
                (mode == WaterWorldRenderGraph.DiagnosticMode.PASS_RESIDENT_FRONT &&
                    stage.passId == WaterWorldRenderGraph.PassId.RESIDENT_FRONT)
            ) {
                pool.copyColor(currentTarget, pool.diagnostic, "diag_${stage.passId.wireId}")
            }
        }

        if (mode != WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE) {
            pool.copyColor(pool.diagnostic, pool.finalTarget, "present_diagnostic_${mode.name}")
        }

        GLES30.glDepthMask(true)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        val totalCpuMs = (System.nanoTime() - cpuStartNs) / 1_000_000.0
        val executedPassesArr = JSONArray()
        val passRecordsArr = JSONArray()
        passRecords.forEach {
            executedPassesArr.put(it.passId.wireId)
            passRecordsArr.put(it.toJson())
        }
        val glVendor = GLES30.glGetString(GLES30.GL_VENDOR).orEmpty()
        val glRenderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()
        val deviceLower = glRenderer.lowercase()
        val isSoftware = listOf("swiftshader", "llvmpipe", "softpipe", "software").any { deviceLower.contains(it) }
        val rendererClass = when {
            glRenderer.isBlank() -> "UNKNOWN_GLES_RENDERER"
            isSoftware -> "ANDROID_EMULATOR_SOFTWARE_GLES"
            else -> "ANDROID_HARDWARE_GPU"
        }

        val fboMemBytes = pool.estimatedGpuMemoryBytes()
        val coreTexMemBytes = coreVolumeRenderer?.volumeTelemetry?.gpuMemoryBytes ?: 0L
        val texMemBytes = loader.uploadedTextureBytes + coreTexMemBytes
        val totalMemBytes = fboMemBytes + texMemBytes
        val mbDiv = 1024.0 * 1024.0
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        val hasGpuTimerExt = extensions.contains("GL_EXT_disjoint_timer_query")
        val runtimeMem = Runtime.getRuntime()
        val javaHeapUsedMb = (runtimeMem.totalMemory() - runtimeMem.freeMemory()) / mbDiv
        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / mbDiv
        val coreValidation = WaterWorldCoreMotionAdapter.validateRotationMatrix(snapshot.coreState.rotationMat3)
        val coreInspectionJson = lastCoreInspection?.toJson() ?: JSONObject()
            .put("core3DVolumeEnabled", false)
            .put("coreRenderMode", OrbFluidVolumeRenderer.CoreRenderMode.CORE_VOLUME_ONLY.name)
            .put("coreVolumeLifecycleState", coreVolumeLifecycleState.name)
            .put("statusMessage", coreVolumeStatusMessage)

        return JSONObject()
            .put("sceneVersion", "water-world-v2.1-work3.1-core3d-gpu")
            .put("glVendor", glVendor)
            .put("glRenderer", glRenderer)
            .put("glVersion", GLES30.glGetString(GLES30.GL_VERSION).orEmpty())
            .put("glslVersion", GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION).orEmpty())
            .put("androidApi", android.os.Build.VERSION.SDK_INT)
            .put("deviceModel", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
            .put("renderResolution", JSONArray(listOf(pool.fullSize, pool.fullSize)))
            .put("fboFormat", "GL_RGBA8+GL_DEPTH_COMPONENT24")
            .put("shaderPrograms", JSONArray(WaterWorldShaders.SHADER_PROGRAM_NAMES))
            .put("shaderProgramCount", s.allPrograms().size + if (coreVolumeRenderer != null) 1 else 0)
            .put("shaderCompileLinkOk", true)
            .put("uniqueTextureCount", cache.uniqueGpuTextureCount() + 1 + if (isCoreVolumeReady) 4 else 0)
            .put("logicalTextureAliasCount", cache.logicalAliasCount())
            .put("textureUploadCount", cache.totalUploadCount)
            .put("textureCacheHits", cache.totalCacheHitCount)
            .put("estimatedFboMemoryMb", fboMemBytes / mbDiv)
            .put("estimatedTextureMemoryMb", texMemBytes / mbDiv)
            .put("estimatedCore3dVolumeMemoryMb", coreTexMemBytes / mbDiv)
            .put("estimatedTotalGpuMemoryMb", totalMemBytes / mbDiv)
            .put("javaHeapUsedMb", javaHeapUsedMb)
            .put("nativeHeapAllocatedMb", nativeHeapMb)
            .put("glErrorCount", glErrorCount)
            .put("presentedFrameId", presentedFrameId)
            .put("presentViewport", lastPresentViewport?.toJson() ?: JSONObject.NULL)
            .put("cpuFrameTimeMs", totalCpuMs)
            .put("gpuFrameTimeMs", if (hasGpuTimerExt) "TIMER_QUERY_AVAILABLE_NOT_SAMPLED" else "GPU_TIMER_UNAVAILABLE")
            .put("gpuTimerStatus", if (hasGpuTimerExt) "EXT_DISJOINT_TIMER_QUERY_SUPPORTED" else "GPU_TIMER_UNAVAILABLE")
            .put("rendererClass", rendererClass)
            .put("hardwareGpuVerified", !isSoftware && glRenderer.isNotBlank())
            .put("drawCount", allDraws.size + if (lastCoreInspection != null) 1 else 0)
            .put("passCount", passRecords.size)
            .put("stageCount", 11)
            .put("opticalNodeCount", opticalNodesExecuted.size)
            .put("executedPasses", executedPassesArr)
            .put("passRecords", passRecordsArr)
            .put("opticalNodesExecuted", JSONArray(opticalNodesExecuted.toList()))
            .put("diagnosticMode", mode.name)
            .put("frameId", snapshot.frameId)
            .put("timeSeconds", snapshot.globalTimeSeconds)
            .put("staticReferenceMode", snapshot.staticReferenceMode)
            .put("motionElementCount", snapshot.elementStates.size)
            .put("motionDependencyCount", snapshot.dependencyStates.size)
            .put("coreVolumeSliceCount", snapshot.coreState.sliceCount)
            .put("coreVolumeSliceSemantics", snapshot.coreState.sliceSemantics)
            .put("coreFilamentStatus", snapshot.coreState.filamentStatus)
            .put("core3DVolumeEnabled", lastCoreInspection?.core3DVolumeEnabled ?: false)
            .put("coreVolumeLifecycleState", coreVolumeLifecycleState.name)
            .put("coreRotationMatDet", coreValidation.determinant.toDouble())
            .put("coreRotationMatOrthoError", coreValidation.maxOrthogonalityError.toDouble())
            .put("core3dGpuInspection", coreInspectionJson)
            .put("fboComplete", passRecords.all { it.fboComplete })
            .put("passPngSha256", passPngShas)
            .put("opticalPngSha256", opticalPngShas)
    }

    private fun drawTransparentEntity(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
        snapshot: WaterWorldDynamicFrameSnapshot,
        item: DrawItem,
        canvasW: Float,
        canvasH: Float,
        farHaze: Float
    ) {
        val b = item.binding
        s.transparent.use()
        GLES30.glUniform2f(s.transparent.uniform("uCanvas"), canvasW, canvasH)

        var modelToUse = b.model
        var deformMode = 0
        var p0 = 0f
        var p1 = 0f
        var p2 = 0f
        var p3 = 0f
        var effectiveFarHaze = farHaze

        if (!snapshot.staticReferenceMode) {
            if (b.materialName == "fish") {
                val fishState = snapshot.fishStates[b.objectId]
                if (fishState != null) {
                    writeFishModelMatrix(b.model, fishState, scratchModel)
                    modelToUse = scratchModel
                    deformMode = 1
                    p0 = fishState.tailAmpPx
                    p1 = fishState.tailPhaseRad
                    p2 = fishState.finAmpPx
                    p3 = fishState.finPhaseRad
                    // Subtle depth tint when fish glides through rear arc (z < 0)
                    if (fishState.offsetZ < 0f) {
                        effectiveFarHaze = (effectiveFarHaze + (-fishState.offsetZ) * 0.16f).coerceAtMost(0.32f)
                    }
                }
            } else if (b.materialName == "plant") {
                val plantState = item.plantElementId?.let { snapshot.plantStates[it] }
                if (plantState != null) {
                    deformMode = 2
                    val farScale = if (farHaze > 0.01f) 0.72f else 1.0f
                    p0 = plantState.swayAmpPx * farScale
                    p1 = plantState.swayPhaseRad
                    p2 = plantState.leafFlutterAmpPx * farScale
                    p3 = plantState.leafFlutterPhaseRad
                }
            }
        }

        GLES30.glUniform1i(s.transparent.uniform("uDeformMode"), deformMode)
        GLES30.glUniform4f(s.transparent.uniform("uDeformParams"), p0, p1, p2, p3)
        GLES30.glUniformMatrix4fv(s.transparent.uniform("uModel"), 1, false, modelToUse, 0)
        GLES30.glUniform4fv(s.transparent.uniform("uTint"), 1, b.tint, 0)
        GLES30.glUniform3fv(s.transparent.uniform("uKeyColor"), 1, b.lightColor, 0)
        GLES30.glUniform1f(s.transparent.uniform("uAmbient"), b.ambient)
        GLES30.glUniform1f(s.transparent.uniform("uAlphaCutoff"), b.alphaCutoff)
        GLES30.glUniform1f(s.transparent.uniform("uSphereClip"), if (b.sphereClip) 1f else 0f)
        GLES30.glUniform2f(s.transparent.uniform("uSphereCenter"), opt.sphereCenterPx[0], opt.sphereCenterPx[1])
        GLES30.glUniform1f(s.transparent.uniform("uSphereInnerRadius"), opt.sphereInnerRadiusPx)
        val matMode = when {
            b.materialName == "fish" -> 0
            b.materialName == "plant" -> 1
            b.objectId == "rock_03" -> 3
            else -> 2
        }
        GLES30.glUniform1i(s.transparent.uniform("uMaterialMode"), matMode)
        GLES30.glUniform1f(s.transparent.uniform("uFarHaze"), effectiveFarHaze)
        val wo = snapshot.waterOptics
        val rockGain = if (snapshot.staticReferenceMode) 1f else (item.rockElementId?.let { snapshot.rockStates[it]?.causticsGain } ?: 1f)
        GLES30.glUniform2f(s.transparent.uniform("uWaterNormalOffset"), wo.waterNormalOffsetU, wo.waterNormalOffsetV)
        GLES30.glUniform1f(s.transparent.uniform("uCausticsPhase"), wo.causticsPhaseRad)
        GLES30.glUniform1f(s.transparent.uniform("uCausticsGain"), rockGain)
        bindTex(0, item.texture)
        GLES30.glUniform1i(s.transparent.uniform("uTexture"), 0)
        bindTex(1, causticsHeightTex)
        GLES30.glUniform1i(s.transparent.uniform("uCausticsTex"), 1)
        bindTex(2, waterNormalTex)
        GLES30.glUniform1i(s.transparent.uniform("uWaterNormalTex"), 2)
        item.mesh.draw()
    }

    private fun drawParticleQuad(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
        snapshot: WaterWorldDynamicFrameSnapshot,
        item: DrawItem,
        canvasW: Float,
        canvasH: Float,
        isOuterDroplet: Boolean
    ) {
        val b = item.binding
        val bState = if (isOuterDroplet) {
            snapshot.outerBubbleStates[b.objectId]
        } else {
            snapshot.innerBubbleStates[b.objectId]
        }
        val alphaScale = if (!snapshot.staticReferenceMode && bState != null) bState.alphaMul else 1f
        if (alphaScale < 0.002f) return

        val modelToUse = if (!snapshot.staticReferenceMode && bState != null) {
            writeBubbleModelMatrix(b.model, item.mesh.centerX, item.mesh.centerY, bState, scratchModel)
            scratchModel
        } else {
            b.model
        }

        s.particleBokeh.use()
        GLES30.glUniform2f(s.particleBokeh.uniform("uCanvas"), canvasW, canvasH)
        GLES30.glUniform1i(s.particleBokeh.uniform("uDeformMode"), 0)
        GLES30.glUniform4f(s.particleBokeh.uniform("uDeformParams"), 0f, 0f, 0f, 0f)
        GLES30.glUniformMatrix4fv(s.particleBokeh.uniform("uModel"), 1, false, modelToUse, 0)
        GLES30.glUniform4fv(s.particleBokeh.uniform("uTint"), 1, b.tint, 0)
        GLES30.glUniform1f(s.particleBokeh.uniform("uSphereClip"), if (b.sphereClip) 1f else 0f)
        GLES30.glUniform2f(s.particleBokeh.uniform("uSphereCenter"), opt.sphereCenterPx[0], opt.sphereCenterPx[1])
        GLES30.glUniform1f(s.particleBokeh.uniform("uSphereInnerRadius"), opt.sphereInnerRadiusPx)
        GLES30.glUniform1i(s.particleBokeh.uniform("uIsOuterDroplet"), if (isOuterDroplet) 1 else 0)
        GLES30.glUniform1f(s.particleBokeh.uniform("uAlphaScale"), alphaScale)
        bindTex(0, item.texture)
        GLES30.glUniform1i(s.particleBokeh.uniform("uTexture"), 0)
        item.mesh.draw()
    }

    private fun renderWaterPass(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
        snapshot: WaterWorldDynamicFrameSnapshot,
        snapshotTarget: WaterWorldRenderTarget,
        waterbedTex: Int,
        dst: WaterWorldRenderTarget,
        diagnosticOnly: Boolean
    ) {
        check(snapshotTarget.texture != dst.texture) { "FBO feedback in Water pass" }
        dst.bind()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        if (diagnosticOnly) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_BLEND)
        } else {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
        val wo = snapshot.waterOptics
        s.water.use()
        GLES30.glUniform2f(s.water.uniform("uCanvas"), dst.size.toFloat(), dst.height.toFloat())
        GLES30.glUniform2f(s.water.uniform("uSphereCenter"), opt.sphereCenterPx[0], opt.sphereCenterPx[1])
        GLES30.glUniform1f(s.water.uniform("uSphereInnerRadius"), opt.sphereInnerRadiusPx)
        GLES30.glUniform1f(s.water.uniform("uNormalStrength"), opt.waterNormalStrength)
        GLES30.glUniform1f(s.water.uniform("uMeniscusBaseY"), opt.meniscusBaseYNorm)
        GLES30.glUniform1f(s.water.uniform("uMeniscusAmp"), opt.meniscusWaveAmpNorm)
        GLES30.glUniform1f(s.water.uniform("uMeniscusThick"), opt.meniscusThicknessNorm)
        GLES30.glUniform3fv(s.water.uniform("uAirDomeTop"), 1, opt.airDomeTopColor, 0)
        GLES30.glUniform3fv(s.water.uniform("uAirDomeWarm"), 1, opt.airDomeWarmColor, 0)
        GLES30.glUniform3fv(s.water.uniform("uWaterShallow"), 1, opt.waterShallowColor, 0)
        GLES30.glUniform3fv(s.water.uniform("uWaterMid"), 1, opt.waterMidColor, 0)
        GLES30.glUniform3fv(s.water.uniform("uWaterDeep"), 1, opt.waterDeepColor, 0)
        GLES30.glUniform1f(s.water.uniform("uWaterbedBlend"), opt.waterbedBlend)
        GLES30.glUniform1i(s.water.uniform("uDiagnosticOnly"), if (diagnosticOnly) 1 else 0)
        GLES30.glUniform2f(s.water.uniform("uWaterNormalOffset"), wo.waterNormalOffsetU, wo.waterNormalOffsetV)
        GLES30.glUniform2f(s.water.uniform("uMeniscusPhases"), wo.meniscusWavePhaseRad, wo.meniscusSecondaryPhaseRad)
        bindTex(0, snapshotTarget.texture); GLES30.glUniform1i(s.water.uniform("uSceneSnapshot"), 0)
        bindTex(1, waterbedTex); GLES30.glUniform1i(s.water.uniform("uWaterbedTex"), 1)
        bindTex(2, waterNormalTex); GLES30.glUniform1i(s.water.uniform("uWaterNormalTex"), 2)
        bindTex(3, waterDepthTex); GLES30.glUniform1i(s.water.uniform("uWaterDepthTex"), 3)
        bindTex(4, causticsHeightTex); GLES30.glUniform1i(s.water.uniform("uCausticsTex"), 4)
        drawFullscreenTriangle()
    }

    private fun renderGlassPass(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
        snapshot: WaterWorldDynamicFrameSnapshot,
        snapshotTarget: WaterWorldRenderTarget,
        dst: WaterWorldRenderTarget,
        passSide: Int,
        diagnosticOnly: Boolean
    ) {
        check(snapshotTarget.texture != dst.texture) { "FBO feedback in Glass pass $passSide" }
        dst.bind()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_BLEND)
        val wo = snapshot.waterOptics
        s.glass.use()
        GLES30.glUniform2f(s.glass.uniform("uCanvas"), dst.size.toFloat(), dst.height.toFloat())
        GLES30.glUniform2f(s.glass.uniform("uSphereCenter"), opt.sphereCenterPx[0], opt.sphereCenterPx[1])
        GLES30.glUniform1f(s.glass.uniform("uSphereOuterRadius"), opt.sphereOuterRadiusPx)
        GLES30.glUniform1i(s.glass.uniform("uPassSide"), passSide)
        GLES30.glUniform1f(
            s.glass.uniform("uRefractionStrength"),
            if (passSide == 0) opt.backRefractionStrength else opt.frontRefractionStrength
        )
        GLES30.glUniform1f(s.glass.uniform("uDispersion"), opt.chromaticDispersion)
        GLES30.glUniform1f(s.glass.uniform("uFresnelPower"), opt.fresnelPower)
        GLES30.glUniform3fv(s.glass.uniform("uRimCool"), 1, opt.rimColorCool, 0)
        GLES30.glUniform3fv(s.glass.uniform("uRimWarm"), 1, opt.rimColorWarm, 0)
        GLES30.glUniform3fv(s.glass.uniform("uAbsorption"), 1, opt.glassAbsorptionColor, 0)
        GLES30.glUniform2fv(s.glass.uniform("uTopLeftFlare"), 1, opt.topLeftFlareNorm, 0)
        GLES30.glUniform1f(s.glass.uniform("uTopLeftRadius"), opt.topLeftFlareRadius)
        GLES30.glUniform3fv(s.glass.uniform("uTopLeftColor"), 1, opt.topLeftFlareColor, 0)
        GLES30.glUniform1f(s.glass.uniform("uTopLeftIntensity"), opt.topLeftFlareIntensity)
        GLES30.glUniform2fv(s.glass.uniform("uBottomRightFlare"), 1, opt.bottomRightFlareNorm, 0)
        GLES30.glUniform1f(s.glass.uniform("uBottomRightRadius"), opt.bottomRightFlareRadius)
        GLES30.glUniform3fv(s.glass.uniform("uBottomRightColor"), 1, opt.bottomRightFlareColor, 0)
        GLES30.glUniform1f(s.glass.uniform("uBottomRightIntensity"), opt.bottomRightFlareIntensity)
        GLES30.glUniform2fv(s.glass.uniform("uRightRimArc"), 1, opt.rightRimArcNorm, 0)
        GLES30.glUniform1f(s.glass.uniform("uRightRimIntensity"), opt.rightRimArcIntensity)
        GLES30.glUniform1i(s.glass.uniform("uDiagnosticOnly"), if (diagnosticOnly) 1 else 0)
        GLES30.glUniform1f(s.glass.uniform("uGlassRefractionScale"), wo.glassRefractionScale)
        GLES30.glUniform1f(s.glass.uniform("uGlassFresnelScale"), wo.glassFresnelScale)
        GLES30.glUniform1f(s.glass.uniform("uGlassOpticalPhase"), wo.glassOpticalPhaseRad)
        bindTex(0, snapshotTarget.texture); GLES30.glUniform1i(s.glass.uniform("uSceneSnapshot"), 0)
        bindTex(1, glassShellMaskTex); GLES30.glUniform1i(s.glass.uniform("uGlassShellMask"), 1)
        drawFullscreenTriangle()
    }

    private fun renderCausticsPass(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
        snapshot: WaterWorldDynamicFrameSnapshot,
        dst: WaterWorldRenderTarget,
        diagnosticOnly: Boolean
    ) {
        dst.bind()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        if (diagnosticOnly) {
            GLES30.glDisable(GLES30.GL_BLEND)
        } else {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        }
        val wo = snapshot.waterOptics
        s.caustics.use()
        GLES30.glUniform2f(s.caustics.uniform("uCanvas"), dst.size.toFloat(), dst.height.toFloat())
        GLES30.glUniform2f(s.caustics.uniform("uSphereCenter"), opt.sphereCenterPx[0], opt.sphereCenterPx[1])
        GLES30.glUniform1f(s.caustics.uniform("uSphereInnerRadius"), opt.sphereInnerRadiusPx)
        GLES30.glUniform2fv(s.caustics.uniform("uSunOrigin"), 1, opt.sunOriginNorm, 0)
        GLES30.glUniform1f(s.caustics.uniform("uGodRayIntensity"), opt.godRayIntensity)
        GLES30.glUniform3fv(s.caustics.uniform("uGodRayColor"), 1, opt.godRayColor, 0)
        for (i in 0 until minOf(6, opt.godRayBeams.size)) {
            scratchBeamBuf[i * 3] = opt.godRayBeams[i].angleDeg
            scratchBeamBuf[i * 3 + 1] = opt.godRayBeams[i].widthDeg
            scratchBeamBuf[i * 3 + 2] = opt.godRayBeams[i].strength
        }
        GLES30.glUniform3fv(s.caustics.uniform("uBeams"), 6, scratchBeamBuf, 0)
        GLES30.glUniform1f(s.caustics.uniform("uSurfaceCausticStrength"), opt.surfaceCausticStrength)
        GLES30.glUniform1f(s.caustics.uniform("uBedCausticStrength"), opt.bedCausticStrength)
        GLES30.glUniform3fv(s.caustics.uniform("uBedCausticColor"), 1, opt.bedCausticColor, 0)
        GLES30.glUniform1i(s.caustics.uniform("uDiagnosticOnly"), if (diagnosticOnly) 1 else 0)
        GLES30.glUniform2f(s.caustics.uniform("uWaterNormalOffset"), wo.waterNormalOffsetU, wo.waterNormalOffsetV)
        GLES30.glUniform1f(s.caustics.uniform("uCausticsPhase"), wo.causticsPhaseRad)
        GLES30.glUniform1f(s.caustics.uniform("uLightingShimmer"), wo.lightingShimmerScale)
        bindTex(0, waterNormalTex); GLES30.glUniform1i(s.caustics.uniform("uWaterNormalTex"), 0)
        bindTex(1, causticsHeightTex); GLES30.glUniform1i(s.caustics.uniform("uCausticsTex"), 1)
        drawFullscreenTriangle()
    }

    private fun runBloomChain(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
        snapshot: WaterWorldDynamicFrameSnapshot,
        pool: WaterWorldRenderTargetPool,
        diagnosticOnly: Boolean
    ) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_BLEND)

        pool.bloomHalfA.bind()
        s.bloomBright.use()
        bindTex(0, pool.scene.texture)
        GLES30.glUniform1i(s.bloomBright.uniform("uSceneTex"), 0)
        GLES30.glUniform1f(s.bloomBright.uniform("uThreshold"), opt.bloomThreshold)
        GLES30.glUniform1f(s.bloomBright.uniform("uSoftKnee"), opt.bloomSoftKnee)
        drawFullscreenTriangle()

        blurPass(s, pool.bloomHalfA, pool.bloomHalfB, horizontal = true)
        blurPass(s, pool.bloomHalfB, pool.bloomHalfA, horizontal = false)

        pool.copyColor(pool.bloomHalfA, pool.bloomQuarterA, "bloom_downsample_4x", GLES30.GL_LINEAR)
        blurPass(s, pool.bloomQuarterA, pool.bloomQuarterB, horizontal = true)
        blurPass(s, pool.bloomQuarterB, pool.bloomQuarterA, horizontal = false)

        val outTarget = if (diagnosticOnly) pool.diagnostic else pool.finalTarget
        check(outTarget.texture != pool.scene.texture) { "FBO feedback in Bloom composite" }
        outTarget.bind()
        s.bloomComposite.use()
        bindTex(0, pool.scene.texture); GLES30.glUniform1i(s.bloomComposite.uniform("uSceneTex"), 0)
        bindTex(1, pool.bloomHalfA.texture); GLES30.glUniform1i(s.bloomComposite.uniform("uBloomHalfTex"), 1)
        bindTex(2, pool.bloomQuarterA.texture); GLES30.glUniform1i(s.bloomComposite.uniform("uBloomQuarterTex"), 2)
        GLES30.glUniform1f(s.bloomComposite.uniform("uBloomStrength"), opt.bloomStrength * snapshot.waterOptics.bloomResponseScale)
        GLES30.glUniform1f(s.bloomComposite.uniform("uMaxContribution"), opt.bloomMaxContribution)
        GLES30.glUniform1i(s.bloomComposite.uniform("uDiagnosticOnly"), if (diagnosticOnly) 1 else 0)
        drawFullscreenTriangle()
    }

    private fun blurPass(
        s: WaterWorldShaders,
        src: WaterWorldRenderTarget,
        dst: WaterWorldRenderTarget,
        horizontal: Boolean
    ) {
        check(src.texture != dst.texture) { "FBO feedback in Bloom blur" }
        dst.bind()
        s.bloomBlur.use()
        bindTex(0, src.texture)
        GLES30.glUniform1i(s.bloomBlur.uniform("uInputTex"), 0)
        if (horizontal) {
            GLES30.glUniform2f(s.bloomBlur.uniform("uTexelStep"), 1f / src.size.toFloat(), 0f)
        } else {
            GLES30.glUniform2f(s.bloomBlur.uniform("uTexelStep"), 0f, 1f / src.height.toFloat())
        }
        drawFullscreenTriangle()
    }

    private fun renderBokehLayer(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
        snapshot: WaterWorldDynamicFrameSnapshot,
        layer: String,
        dst: WaterWorldRenderTarget
    ): Int {
        dst.bind()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        s.bokehOrb.use()
        val wo = snapshot.waterOptics
        var count = 0
        for ((idx, orb) in opt.bokehOrbs.withIndex()) {
            if (orb.layer != layer) continue
            val sign = if (idx % 2 == 0) 1f else -1f
            val cx = orb.x + wo.bokehDriftOffsetU * sign
            val cy = orb.y + wo.bokehDriftOffsetV * (if (idx % 3 == 0) -1f else 1f)
            val shimmer = if (snapshot.staticReferenceMode) 1f else (1f + 0.05f * sin(wo.bokehPhaseRad + idx * 1.1f))
            GLES30.glUniform2f(s.bokehOrb.uniform("uCenter"), cx, cy)
            GLES30.glUniform1f(s.bokehOrb.uniform("uRadius"), orb.radius)
            GLES30.glUniform3fv(s.bokehOrb.uniform("uColor"), 1, orb.color, 0)
            GLES30.glUniform1f(s.bokehOrb.uniform("uAlpha"), orb.alpha * shimmer)
            GLES30.glUniform1f(s.bokehOrb.uniform("uSoftness"), orb.softness)
            drawFullscreenTriangle()
            count++
        }
        return count
    }

    private fun bindTex(unit: Int, texId: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
    }

    private fun drawFullscreenTriangle() {
        GLES30.glBindVertexArray(fullscreenVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    /** Present the exact offscreen 1254x1254 scene centered into the Android surface without cropping. */
    fun present(width: Int, height: Int): PresentViewport {
        require(width > 0 && height > 0)
        val fbo = checkNotNull(targetPool).finalTarget
        val side = minOf(width, height)
        val left = (width - side) / 2
        val bottom = (height - side) / 2
        val viewport = PresentViewport(left, bottom, side, width, height)
        lastPresentViewport = viewport
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo.framebuffer)
        GLES30.glBlitFramebuffer(
            0, 0, fbo.size, fbo.height,
            left, bottom, left + side, bottom + side,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR
        )
        checkError("present")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        presentedFrameId += 1L
        return viewport
    }

    /**
     * Inserts an OpenGL ES 3.0 GPU command fence and waits for completion. Returns `true` only when
     * the GL driver actually signaled the fence without timeout or error.
     */
    fun waitForGpuFenceSync(timeoutNs: Long = 100_000_000L): Boolean {
        val sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (sync == 0L) return false
        return try {
            val status = GLES30.glClientWaitSync(sync, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, timeoutNs)
            status == GLES30.GL_ALREADY_SIGNALED || status == GLES30.GL_CONDITION_SATISFIED
        } finally {
            GLES30.glDeleteSync(sync)
        }
    }

    fun capture(file: File): String = captureTarget(checkNotNull(targetPool).finalTarget, file)

    fun capturePasses(
        directory: File,
        snapshot: WaterWorldDynamicFrameSnapshot? = null,
    ): JSONObject {
        require(!directory.exists()) { "Capture evidence directory must be new" }
        check(directory.mkdirs())
        val effectiveSnapshot = snapshot ?: WaterWorldDynamicFrameSnapshot.staticZero(motionProfile)
        val telemetry = renderDynamic(
            snapshot = effectiveSnapshot,
            mode = WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE,
            capturePassDir = directory
        )
        val finalSha = captureTarget(checkNotNull(targetPool).finalTarget, File(directory, "final_fbo.png"))
        telemetry.put("finalFboPngSha256", finalSha)
        val device = telemetry.optString("glRenderer").lowercase()
        val isSoftware = listOf("swiftshader", "llvmpipe", "softpipe", "software").any { device.contains(it) }
        telemetry.put("rendererClass", if (isSoftware) "ANDROID_EMULATOR_SOFTWARE_GLES" else "ANDROID_HARDWARE_GPU")
        telemetry.put("hardwareGpuVerified", !isSoftware && device.isNotBlank())
        File(directory, "ANDROID_GPU_TELEMETRY.json").writeText(telemetry.toString(2))
        return telemetry
    }

    private fun captureTarget(fbo: WaterWorldRenderTarget, file: File): String {
        fbo.bind()
        val bytes = ByteBuffer.allocateDirect(fbo.size * fbo.height * 4)
        GLES30.glReadPixels(0, 0, fbo.size, fbo.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, bytes)
        checkError("capture")
        val pixels = IntArray(fbo.size * fbo.height)
        for (y in 0 until fbo.height) {
            for (x in 0 until fbo.size) {
                val i = (y * fbo.size + x) * 4
                val r = bytes.get(i).toInt() and 255
                val g = bytes.get(i + 1).toInt() and 255
                val b = bytes.get(i + 2).toInt() and 255
                val a = bytes.get(i + 3).toInt() and 255
                pixels[(fbo.height - 1 - y) * fbo.size + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, fbo.size, fbo.height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path))
        try {
            check(decoded.width == fbo.size && decoded.height == fbo.height)
        } finally {
            decoded.recycle()
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun release() {
        for (draw in allDraws) draw.mesh.release()
        allDraws.clear()
        drawsByPass.clear()
        coreVolumeRenderer?.release()
        coreVolumeRenderer = null
        coreVolumeCamera = null
        lastCoreInspection = null
        textureCache?.releaseAll()
        textureCache = null
        targetPool?.releaseAll()
        targetPool = null
        shaders?.release()
        shaders = null
        opticalConfig = null
        if (whiteTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(whiteTexture), 0)
            whiteTexture = 0
        }
        if (fullscreenVao != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(fullscreenVao), 0)
            fullscreenVao = 0
        }
        waterNormalTex = 0
        waterDepthTex = 0
        causticsHeightTex = 0
        glassShellMaskTex = 0
    }

    private fun checkError(pass: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            glErrorCount += 1
            throw IllegalStateException("WaterWorld $pass GL error 0x${error.toString(16)}")
        }
    }

    internal companion object {
        internal fun coreDiagModeFor(mode: WaterWorldRenderGraph.DiagnosticMode): OrbFluidVolumeRenderer.CoreDiagMode? = when (mode) {
            WaterWorldRenderGraph.DiagnosticMode.CORE_VOLUME_ONLY -> OrbFluidVolumeRenderer.CoreDiagMode.CORE_VOLUME_ONLY_ISOLATED
            WaterWorldRenderGraph.DiagnosticMode.CORE_DENSITY -> OrbFluidVolumeRenderer.CoreDiagMode.CORE_DENSITY
            WaterWorldRenderGraph.DiagnosticMode.CORE_FLOW -> OrbFluidVolumeRenderer.CoreDiagMode.CORE_FLOW
            WaterWorldRenderGraph.DiagnosticMode.CORE_CAUSTICS -> OrbFluidVolumeRenderer.CoreDiagMode.CORE_CAUSTICS
            WaterWorldRenderGraph.DiagnosticMode.CORE_OPTICAL_DEPTH -> OrbFluidVolumeRenderer.CoreDiagMode.CORE_OPTICAL_DEPTH
            WaterWorldRenderGraph.DiagnosticMode.CORE_ROTATION -> OrbFluidVolumeRenderer.CoreDiagMode.CORE_ROTATION
            else -> null
        }

        internal fun resolveResidentGroupKey(objectId: String): String? = when {
            objectId.startsWith("G01") -> "G01"
            objectId.startsWith("G02") -> "G02"
            objectId.startsWith("A01") -> "A01"
            objectId.startsWith("A02") -> "A02"
            objectId.startsWith("C01") -> "C01"
            else -> null
        }

        internal fun resolvePlantElementId(objectId: String, isPlant: Boolean): String? {
            if (!isPlant) return null
            return if (objectId.contains("left")) "WW_PLANT_LEFT" else "WW_PLANT_RIGHT"
        }

        internal fun resolveRockElementId(objectId: String, isRock: Boolean): String? {
            if (!isRock) return null
            return when {
                objectId.contains("03") -> "WW_ROCK_03"
                objectId.contains("02") || objectId.contains("right") -> "WW_ROCK_02"
                else -> "WW_ROCK_01"
            }
        }

        /**
         * Builds column-major 4x4 transform: `T(pivot + offset) * Rz(pitch) * S(facingScaleX * depthScale, depthScale, 1) * T(-pivot)`
         * keeping `baseModel` Z translation intact (`baseModel[14]`).
         */
        internal fun writeFishModelMatrix(
            baseModel: FloatArray,
            fish: WaterWorldDynamicFrameSnapshot.FishMotionState,
            out: FloatArray,
        ) {
            val sx = fish.facingScaleX * fish.depthScale
            val sy = fish.depthScale
            val c = cos(fish.pitchRad)
            val s = sin(fish.pitchRad)
            val a = c * sx
            val b = -s * sy
            val d = s * sx
            val e = c * sy
            val tx = fish.pivotX + fish.offsetX - (a * fish.pivotX + b * fish.pivotY)
            val ty = fish.pivotY + fish.offsetY - (d * fish.pivotX + e * fish.pivotY)
            out[0] = a; out[1] = d; out[2] = 0f; out[3] = 0f
            out[4] = b; out[5] = e; out[6] = 0f; out[7] = 0f
            out[8] = 0f; out[9] = 0f; out[10] = 1f; out[11] = 0f
            out[12] = tx; out[13] = ty; out[14] = baseModel[14] + fish.offsetZ * 6f; out[15] = 1f
        }

        /**
         * Builds column-major 4x4 bubble transform around mesh center:
         * `T(center + offset) * S(scaleMul, scaleMul, 1) * T(-center)` preserving `baseModel[14]`.
         */
        internal fun writeBubbleModelMatrix(
            baseModel: FloatArray,
            centerX: Float,
            centerY: Float,
            bubble: WaterWorldDynamicFrameSnapshot.BubbleMotionState,
            out: FloatArray,
        ) {
            val sc = bubble.scaleMul
            val tx = centerX + bubble.offsetX - sc * centerX
            val ty = centerY + bubble.offsetY - sc * centerY
            out[0] = sc; out[1] = 0f; out[2] = 0f; out[3] = 0f
            out[4] = 0f; out[5] = sc; out[6] = 0f; out[7] = 0f
            out[8] = 0f; out[9] = 0f; out[10] = 1f; out[11] = 0f
            out[12] = tx; out[13] = ty; out[14] = baseModel[14]; out[15] = 1f
        }
    }
}
