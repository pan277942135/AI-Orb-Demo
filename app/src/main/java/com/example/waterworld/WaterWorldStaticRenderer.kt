package com.example.waterworld

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Production Android OpenGL ES 3.0 static renderer for Water World V2.1 Work 1.6.
 * Executes all 11 canonical composition stages and 5 independent optical nodes
 * via [WaterWorldRenderGraph], [WaterWorldRenderTargetPool], [WaterWorldTextureCache],
 * and [WaterWorldShaders]. All GL calls run exclusively on the active EGL thread.
 */
internal class WaterWorldStaticRenderer(private val root: File) {

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

    private data class DrawItem(
        val binding: Binding,
        val mesh: WaterWorldMesh,
        val texture: Int
    )

    private val loader = WaterWorldAssetLoader(root)
    private val renderGraph = WaterWorldRenderGraph()
    private val drawsByPass = LinkedHashMap<WaterWorldRenderGraph.PassId, MutableList<DrawItem>>()
    private val allDraws = mutableListOf<DrawItem>()
    private var textureCache: WaterWorldTextureCache? = null
    private var shaders: WaterWorldShaders? = null
    private var targetPool: WaterWorldRenderTargetPool? = null
    private var opticalConfig: WaterWorldSceneBindings.OpticalConfig? = null
    private var whiteTexture = 0
    private var fullscreenVao = 0
    private var waterNormalTex = 0
    private var waterDepthTex = 0
    private var causticsHeightTex = 0
    private var glassShellMaskTex = 0

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
                val item = DrawItem(binding, WaterWorldMesh(loader.json(binding.meshPath)), texture)
                allDraws += item
                drawsByPass.getValue(binding.passId) += item
            }
            checkError("initialize")
        } catch (failure: Throwable) {
            release()
            throw failure
        }
    }

    fun render(
        mode: WaterWorldRenderGraph.DiagnosticMode = WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE,
        snapshot: WaterWorldMotionContract.SharedFrameSnapshot = WaterWorldMotionContract.SharedFrameSnapshot.staticZero(),
        capturePassDir: File? = null
    ): JSONObject {
        val cpuStartNs = System.nanoTime()
        val s = checkNotNull(shaders) { "Renderer not initialized" }
        val pool = checkNotNull(targetPool) { "Target pool not initialized" }
        val opt = checkNotNull(opticalConfig) { "Optical config not initialized" }
        val cache = checkNotNull(textureCache) { "Texture cache not initialized" }
        require(snapshot.timeSeconds >= 0f && snapshot.timeSeconds.isFinite())

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
                    // Background Bokeh orbs (Optical Node 5 - BG layer)
                    drawCount += renderBokehLayer(s, opt, "BG", pool.scene)
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
                    // Snapshot scene -> snapshot FBO before reading (zero FBO feedback loop!)
                    pool.copyColor(pool.scene, pool.snapshot, "pass03_pre_glass_back")
                    renderGlassPass(s, opt, pool.snapshot, pool.scene, passSide = 0, diagnosticOnly = false)
                    drawCount = 1
                    opticalNodesExecuted += WaterWorldRenderGraph.OpticalNodeId.GLASS_REFRACTION.wireId
                }

                WaterWorldRenderGraph.PassId.WATER_INTERIOR -> {
                    pool.copyColor(pool.scene, pool.snapshot, "pass04_pre_water")
                    val waterbedTex = stageDraws.firstOrNull()?.texture ?: whiteTexture
                    renderWaterPass(s, opt, pool.snapshot, waterbedTex, pool.scene, diagnosticOnly = false)
                    drawCount = 1
                    opticalNodesExecuted += WaterWorldRenderGraph.OpticalNodeId.WATER_NORMAL_DISTORTION.wireId
                    if (capturePassDir != null || mode == WaterWorldRenderGraph.DiagnosticMode.OPTICAL_WATER_DISTORTION) {
                        renderWaterPass(s, opt, pool.snapshot, waterbedTex, pool.diagnostic, diagnosticOnly = true)
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
                            drawParticleQuad(s, opt, item, canvasW, canvasH, isOuterDroplet = false)
                        } else {
                            drawTransparentEntity(
                                s, opt, item, canvasW, canvasH,
                                farHaze = if (stage.passId == WaterWorldRenderGraph.PassId.FAR_ECOLOGY) 0.45f else 0f
                            )
                        }
                        drawCount++
                    }
                }

                WaterWorldRenderGraph.PassId.LIGHTING -> {
                    renderCausticsPass(s, opt, pool.scene, diagnosticOnly = false)
                    drawCount = 1
                    opticalNodesExecuted += WaterWorldRenderGraph.OpticalNodeId.CAUSTICS.wireId
                    if (capturePassDir != null || mode == WaterWorldRenderGraph.DiagnosticMode.OPTICAL_CAUSTICS) {
                        pool.diagnostic.bind()
                        GLES30.glClearColor(0f, 0f, 0f, 1f)
                        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                        renderCausticsPass(s, opt, pool.diagnostic, diagnosticOnly = true)
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
                    renderGlassPass(s, opt, pool.snapshot, pool.scene, passSide = 1, diagnosticOnly = false)
                    drawCount = 1
                    opticalNodesExecuted += WaterWorldRenderGraph.OpticalNodeId.GLASS_REFRACTION.wireId
                    if (capturePassDir != null || mode == WaterWorldRenderGraph.DiagnosticMode.OPTICAL_GLASS_REFRACTION) {
                        renderGlassPass(s, opt, pool.snapshot, pool.diagnostic, passSide = 1, diagnosticOnly = true)
                        if (capturePassDir != null) {
                            opticalPngShas.put(
                                "glass_refraction",
                                captureTarget(pool.diagnostic, File(capturePassDir, "opt_glass_refraction.png"))
                            )
                        }
                    }
                }

                WaterWorldRenderGraph.PassId.FINAL_COMPOSITE -> {
                    // Optical Node 4: 5-step Bloom (Bright -> 2x BlurH/V -> 4x BlurH/V -> Composite)
                    runBloomChain(s, opt, pool, diagnosticOnly = false)
                    opticalNodesExecuted += WaterWorldRenderGraph.OpticalNodeId.BLOOM.wireId
                    if (capturePassDir != null || mode == WaterWorldRenderGraph.DiagnosticMode.OPTICAL_BLOOM) {
                        runBloomChain(s, opt, pool, diagnosticOnly = true)
                        if (capturePassDir != null) {
                            opticalPngShas.put(
                                "bloom",
                                captureTarget(pool.diagnostic, File(capturePassDir, "opt_bloom.png"))
                            )
                        }
                    }
                    // Optical Node 5: Foreground Bokeh sparkles + Outer glossy droplets onto finalTarget
                    drawCount += renderBokehLayer(s, opt, "FG", pool.finalTarget)
                    pool.finalTarget.bind()
                    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                    GLES30.glDepthMask(false)
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFuncSeparate(
                        GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA,
                        GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA
                    )
                    for (item in stageDraws) {
                        drawParticleQuad(s, opt, item, canvasW, canvasH, isOuterDroplet = true)
                        drawCount++
                    }
                    if (capturePassDir != null || mode == WaterWorldRenderGraph.DiagnosticMode.OPTICAL_BOKEH) {
                        pool.diagnostic.bind()
                        GLES30.glClearColor(0f, 0f, 0f, 1f)
                        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                        renderBokehLayer(s, opt, "BG", pool.diagnostic)
                        renderBokehLayer(s, opt, "FG", pool.diagnostic)
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

            // Snapshot intermediate pass PNG if requested or matching pass diagnostic mode
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

        // If a non-final diagnostic mode is active, copy diagnostic buffer into finalTarget for presentation
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

        return JSONObject()
            .put("glVendor", glVendor)
            .put("glRenderer", glRenderer)
            .put("glVersion", GLES30.glGetString(GLES30.GL_VERSION).orEmpty())
            .put("glslVersion", GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION).orEmpty())
            .put("androidApi", android.os.Build.VERSION.SDK_INT)
            .put("renderResolution", JSONArray(listOf(pool.fullSize, pool.fullSize)))
            .put("shaderPrograms", JSONArray(WaterWorldShaders.SHADER_PROGRAM_NAMES))
            .put("shaderProgramCount", s.allPrograms().size)
            .put("shaderCompileLinkOk", true)
            .put("uniqueTextureCount", cache.uniqueGpuTextureCount() + 1)
            .put("logicalTextureAliasCount", cache.logicalAliasCount())
            .put("cpuFrameTimeMs", totalCpuMs)
            .put("gpuFrameTimeMs", "NOT_MEASURED")
            .put("rendererClass", rendererClass)
            .put("hardwareGpuVerified", !isSoftware && glRenderer.isNotBlank())
            .put("drawCount", allDraws.size)
            .put("passCount", passRecords.size)
            .put("stageCount", 11)
            .put("opticalNodeCount", opticalNodesExecuted.size)
            .put("executedPasses", executedPassesArr)
            .put("passRecords", passRecordsArr)
            .put("opticalNodesExecuted", JSONArray(opticalNodesExecuted.toList()))
            .put("diagnosticMode", mode.name)
            .put("timeSeconds", snapshot.timeSeconds)
            .put("fboComplete", passRecords.all { it.fboComplete })
            .put("passPngSha256", passPngShas)
            .put("opticalPngSha256", opticalPngShas)
    }

    private fun drawTransparentEntity(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
        item: DrawItem,
        canvasW: Float,
        canvasH: Float,
        farHaze: Float
    ) {
        val b = item.binding
        s.transparent.use()
        GLES30.glUniform2f(s.transparent.uniform("uCanvas"), canvasW, canvasH)
        GLES30.glUniformMatrix4fv(s.transparent.uniform("uModel"), 1, false, b.model, 0)
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
        GLES30.glUniform1f(s.transparent.uniform("uFarHaze"), farHaze)
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
        item: DrawItem,
        canvasW: Float,
        canvasH: Float,
        isOuterDroplet: Boolean
    ) {
        val b = item.binding
        s.particleBokeh.use()
        GLES30.glUniform2f(s.particleBokeh.uniform("uCanvas"), canvasW, canvasH)
        GLES30.glUniformMatrix4fv(s.particleBokeh.uniform("uModel"), 1, false, b.model, 0)
        GLES30.glUniform4fv(s.particleBokeh.uniform("uTint"), 1, b.tint, 0)
        GLES30.glUniform1f(s.particleBokeh.uniform("uSphereClip"), if (b.sphereClip) 1f else 0f)
        GLES30.glUniform2f(s.particleBokeh.uniform("uSphereCenter"), opt.sphereCenterPx[0], opt.sphereCenterPx[1])
        GLES30.glUniform1f(s.particleBokeh.uniform("uSphereInnerRadius"), opt.sphereInnerRadiusPx)
        GLES30.glUniform1i(s.particleBokeh.uniform("uIsOuterDroplet"), if (isOuterDroplet) 1 else 0)
        bindTex(0, item.texture)
        GLES30.glUniform1i(s.particleBokeh.uniform("uTexture"), 0)
        item.mesh.draw()
    }

    private fun renderWaterPass(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
        snapshot: WaterWorldRenderTarget,
        waterbedTex: Int,
        dst: WaterWorldRenderTarget,
        diagnosticOnly: Boolean
    ) {
        check(snapshot.texture != dst.texture) { "FBO feedback in Water pass" }
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
        bindTex(0, snapshot.texture); GLES30.glUniform1i(s.water.uniform("uSceneSnapshot"), 0)
        bindTex(1, waterbedTex); GLES30.glUniform1i(s.water.uniform("uWaterbedTex"), 1)
        bindTex(2, waterNormalTex); GLES30.glUniform1i(s.water.uniform("uWaterNormalTex"), 2)
        bindTex(3, waterDepthTex); GLES30.glUniform1i(s.water.uniform("uWaterDepthTex"), 3)
        bindTex(4, causticsHeightTex); GLES30.glUniform1i(s.water.uniform("uCausticsTex"), 4)
        drawFullscreenTriangle()
    }

    private fun renderGlassPass(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
        snapshot: WaterWorldRenderTarget,
        dst: WaterWorldRenderTarget,
        passSide: Int,
        diagnosticOnly: Boolean
    ) {
        check(snapshot.texture != dst.texture) { "FBO feedback in Glass pass $passSide" }
        dst.bind()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_BLEND)
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
        bindTex(0, snapshot.texture); GLES30.glUniform1i(s.glass.uniform("uSceneSnapshot"), 0)
        bindTex(1, glassShellMaskTex); GLES30.glUniform1i(s.glass.uniform("uGlassShellMask"), 1)
        drawFullscreenTriangle()
    }

    private fun renderCausticsPass(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
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
        s.caustics.use()
        GLES30.glUniform2f(s.caustics.uniform("uCanvas"), dst.size.toFloat(), dst.height.toFloat())
        GLES30.glUniform2f(s.caustics.uniform("uSphereCenter"), opt.sphereCenterPx[0], opt.sphereCenterPx[1])
        GLES30.glUniform1f(s.caustics.uniform("uSphereInnerRadius"), opt.sphereInnerRadiusPx)
        GLES30.glUniform2fv(s.caustics.uniform("uSunOrigin"), 1, opt.sunOriginNorm, 0)
        GLES30.glUniform1f(s.caustics.uniform("uGodRayIntensity"), opt.godRayIntensity)
        GLES30.glUniform3fv(s.caustics.uniform("uGodRayColor"), 1, opt.godRayColor, 0)
        val beamBuf = FloatArray(18)
        for (i in 0 until minOf(6, opt.godRayBeams.size)) {
            beamBuf[i * 3] = opt.godRayBeams[i].angleDeg
            beamBuf[i * 3 + 1] = opt.godRayBeams[i].widthDeg
            beamBuf[i * 3 + 2] = opt.godRayBeams[i].strength
        }
        GLES30.glUniform3fv(s.caustics.uniform("uBeams"), 6, beamBuf, 0)
        GLES30.glUniform1f(s.caustics.uniform("uSurfaceCausticStrength"), opt.surfaceCausticStrength)
        GLES30.glUniform1f(s.caustics.uniform("uBedCausticStrength"), opt.bedCausticStrength)
        GLES30.glUniform3fv(s.caustics.uniform("uBedCausticColor"), 1, opt.bedCausticColor, 0)
        GLES30.glUniform1i(s.caustics.uniform("uDiagnosticOnly"), if (diagnosticOnly) 1 else 0)
        bindTex(0, waterNormalTex); GLES30.glUniform1i(s.caustics.uniform("uWaterNormalTex"), 0)
        bindTex(1, causticsHeightTex); GLES30.glUniform1i(s.caustics.uniform("uCausticsTex"), 1)
        drawFullscreenTriangle()
    }

    private fun runBloomChain(
        s: WaterWorldShaders,
        opt: WaterWorldSceneBindings.OpticalConfig,
        pool: WaterWorldRenderTargetPool,
        diagnosticOnly: Boolean
    ) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_BLEND)

        // 1. Bright pass -> bloomHalfA
        pool.bloomHalfA.bind()
        s.bloomBright.use()
        bindTex(0, pool.scene.texture)
        GLES30.glUniform1i(s.bloomBright.uniform("uSceneTex"), 0)
        GLES30.glUniform1f(s.bloomBright.uniform("uThreshold"), opt.bloomThreshold)
        GLES30.glUniform1f(s.bloomBright.uniform("uSoftKnee"), opt.bloomSoftKnee)
        drawFullscreenTriangle()

        // 2. Half-res BlurH -> bloomHalfB, BlurV -> bloomHalfA
        blurPass(s, pool.bloomHalfA, pool.bloomHalfB, horizontal = true)
        blurPass(s, pool.bloomHalfB, pool.bloomHalfA, horizontal = false)

        // 3. Downsample 2x -> 4x (bloomHalfA -> bloomQuarterA) + BlurH -> bloomQuarterB + BlurV -> bloomQuarterA
        pool.copyColor(pool.bloomHalfA, pool.bloomQuarterA, "bloom_downsample_4x", GLES30.GL_LINEAR)
        blurPass(s, pool.bloomQuarterA, pool.bloomQuarterB, horizontal = true)
        blurPass(s, pool.bloomQuarterB, pool.bloomQuarterA, horizontal = false)

        // 4. Composite Scene + Half Bloom + Quarter Bloom -> finalTarget (or diagnostic)
        val outTarget = if (diagnosticOnly) pool.diagnostic else pool.finalTarget
        check(outTarget.texture != pool.scene.texture) { "FBO feedback in Bloom composite" }
        outTarget.bind()
        s.bloomComposite.use()
        bindTex(0, pool.scene.texture); GLES30.glUniform1i(s.bloomComposite.uniform("uSceneTex"), 0)
        bindTex(1, pool.bloomHalfA.texture); GLES30.glUniform1i(s.bloomComposite.uniform("uBloomHalfTex"), 1)
        bindTex(2, pool.bloomQuarterA.texture); GLES30.glUniform1i(s.bloomComposite.uniform("uBloomQuarterTex"), 2)
        GLES30.glUniform1f(s.bloomComposite.uniform("uBloomStrength"), opt.bloomStrength)
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
        layer: String,
        dst: WaterWorldRenderTarget
    ): Int {
        dst.bind()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        s.bokehOrb.use()
        var count = 0
        for (orb in opt.bokehOrbs) {
            if (orb.layer != layer) continue
            GLES30.glUniform2f(s.bokehOrb.uniform("uCenter"), orb.x, orb.y)
            GLES30.glUniform1f(s.bokehOrb.uniform("uRadius"), orb.radius)
            GLES30.glUniform3fv(s.bokehOrb.uniform("uColor"), 1, orb.color, 0)
            GLES30.glUniform1f(s.bokehOrb.uniform("uAlpha"), orb.alpha)
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
    fun present(width: Int, height: Int) {
        require(width > 0 && height > 0)
        val fbo = checkNotNull(targetPool).finalTarget
        val side = minOf(width, height)
        val left = (width - side) / 2
        val bottom = (height - side) / 2
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
    }

    fun capture(file: File): String = captureTarget(checkNotNull(targetPool).finalTarget, file)

    fun capturePasses(directory: File): JSONObject {
        require(!directory.exists()) { "Capture evidence directory must be new" }
        check(directory.mkdirs())
        val telemetry = render(
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
        check(error == GLES30.GL_NO_ERROR) { "WaterWorld $pass GL error 0x${error.toString(16)}" }
    }
}
