package com.example.waterworld

import org.json.JSONArray
import org.json.JSONObject

/**
 * Executable 11-stage Render Graph with explicit 5-optical-node tracking, dependency DAG
 * validation, per-material Depth/Alpha/Blend policy enforcement, and per-pass telemetry.
 */
internal class WaterWorldRenderGraph(
    val canvasSize: Int = 1254,
    val stages: List<PassDescriptor> = canonical11StageGraph(canvasSize)
) {
    enum class PassId(val wireId: String, val order: Int) {
        BACKGROUND("01_BACKGROUND", 1),
        RESIDENT_BACK("02_RESIDENT_BACK", 2),
        SPHERE_BACK("03_SPHERE_BACK", 3),
        WATER_INTERIOR("04_WATER_INTERIOR", 4),
        FAR_ECOLOGY("05_FAR_ECOLOGY", 5),
        LIGHTING("06_LIGHTING", 6),
        FISH("07_FISH", 7),
        NEAR_ECOLOGY("08_NEAR_ECOLOGY", 8),
        SPHERE_FRONT("09_SPHERE_FRONT", 9),
        RESIDENT_FRONT("10_RESIDENT_FRONT", 10),
        FINAL_COMPOSITE("11_FINAL_COMPOSITE", 11);

        companion object {
            fun fromWireId(wireId: String): PassId =
                entries.firstOrNull { it.wireId == wireId }
                    ?: throw IllegalArgumentException("Unknown Water World PassId: $wireId")
        }
    }

    enum class OpticalNodeId(val wireId: String) {
        GLASS_REFRACTION("glass_refraction"),
        WATER_NORMAL_DISTORTION("water_normal_distortion"),
        CAUSTICS("caustics"),
        BLOOM("bloom"),
        BOKEH("bokeh");

        companion object {
            fun fromWireId(wireId: String): OpticalNodeId =
                entries.firstOrNull { it.wireId == wireId }
                    ?: throw IllegalArgumentException("Unknown OpticalNodeId: $wireId")
        }
    }

    enum class ShaderProgramId {
        OPAQUE,
        TRANSPARENT,
        RESIDENT_RIBBON,
        WATER,
        GLASS,
        CAUSTICS,
        BLOOM,
        PARTICLE_BOKEH
    }

    enum class DepthPolicy(val depthTest: Boolean, val depthWrite: Boolean) {
        OPAQUE_WRITE(depthTest = true, depthWrite = true),
        ALPHA_CUTOUT_WRITE(depthTest = true, depthWrite = true),
        TRANSLUCENT_READ_ONLY(depthTest = true, depthWrite = false),
        POST_PROCESS_NO_DEPTH(depthTest = false, depthWrite = false)
    }

    enum class BlendMode {
        NONE,
        ALPHA,
        ADDITIVE,
        SCREEN
    }

    enum class ClearPolicy {
        CLEAR_COLOR_AND_DEPTH,
        CLEAR_COLOR_ONLY,
        PRESERVE_PREVIOUS
    }

    enum class DiagnosticMode(val label: String) {
        FINAL_SCENE("Full 11-Stage Scene"),
        PASS_RESIDENT_BACK("Pass 02: Resident Back"),
        PASS_FISH("Pass 07: Fish"),
        PASS_RESIDENT_FRONT("Pass 10: Resident Front"),
        OPTICAL_GLASS_REFRACTION("Optical: Glass Refraction"),
        OPTICAL_WATER_DISTORTION("Optical: Water Normal Distortion"),
        OPTICAL_CAUSTICS("Optical: Caustics & God-Rays"),
        OPTICAL_BLOOM("Optical: Bloom Pass"),
        OPTICAL_BOKEH("Optical: Bokeh & Droplets")
    }

    data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

    data class PassDescriptor(
        val passId: PassId,
        val responsibility: String,
        val inputTextures: List<String>,
        val outputTarget: String,
        val inputDependencies: List<PassId>,
        val materialPolicy: String,
        val depthPolicy: DepthPolicy,
        val blendMode: BlendMode,
        val viewport: Viewport,
        val clearPolicy: ClearPolicy,
        val primaryShader: ShaderProgramId,
        val opticalNodes: List<OpticalNodeId> = emptyList()
    ) {
        val depthTest: Boolean get() = depthPolicy.depthTest
        val depthWrite: Boolean get() = depthPolicy.depthWrite
    }

    data class PassExecutionRecord(
        val passId: PassId,
        val drawCount: Int,
        val elapsedCpuMs: Double,
        val fboComplete: Boolean,
        val depthWriteEnabled: Boolean,
        val opticalNodesApplied: List<String>
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("passId", passId.wireId)
            .put("order", passId.order)
            .put("drawCount", drawCount)
            .put("elapsedCpuMs", elapsedCpuMs)
            .put("fboComplete", fboComplete)
            .put("depthWriteEnabled", depthWriteEnabled)
            .put("opticalNodesApplied", JSONArray(opticalNodesApplied))
    }

    fun validateGraph() {
        Companion.validateGraph(stages)
    }

    companion object {
        fun canonical11StageGraph(canvasSize: Int = 1254): List<PassDescriptor> {
            val vp = Viewport(0, 0, canvasSize, canvasSize)
            return listOf(
                PassDescriptor(
                    passId = PassId.BACKGROUND,
                    responsibility = "Background environment & deep bokeh",
                    inputTextures = listOf("01_BACKGROUND/background.png"),
                    outputTarget = "scene",
                    inputDependencies = emptyList(),
                    materialPolicy = "OPAQUE_BACKGROUND",
                    depthPolicy = DepthPolicy.OPAQUE_WRITE,
                    blendMode = BlendMode.NONE,
                    viewport = vp,
                    clearPolicy = ClearPolicy.CLEAR_COLOR_AND_DEPTH,
                    primaryShader = ShaderProgramId.OPAQUE,
                    opticalNodes = listOf(OpticalNodeId.BOKEH)
                ),
                PassDescriptor(
                    passId = PassId.RESIDENT_BACK,
                    responsibility = "Back-hemisphere translucent Resident ribbons",
                    inputTextures = emptyList(),
                    outputTarget = "scene",
                    inputDependencies = listOf(PassId.BACKGROUND),
                    materialPolicy = "TRANSLUCENT_RESIDENT",
                    depthPolicy = DepthPolicy.TRANSLUCENT_READ_ONLY,
                    blendMode = BlendMode.SCREEN,
                    viewport = vp,
                    clearPolicy = ClearPolicy.PRESERVE_PREVIOUS,
                    primaryShader = ShaderProgramId.RESIDENT_RIBBON
                ),
                PassDescriptor(
                    passId = PassId.SPHERE_BACK,
                    responsibility = "Back crystal glass shell & screen-space refraction",
                    inputTextures = listOf("snapshot", "03_SPHERE/glass_shell_mask.png"),
                    outputTarget = "scene",
                    inputDependencies = listOf(PassId.RESIDENT_BACK),
                    materialPolicy = "GLASS_BACK",
                    depthPolicy = DepthPolicy.TRANSLUCENT_READ_ONLY,
                    blendMode = BlendMode.ALPHA,
                    viewport = vp,
                    clearPolicy = ClearPolicy.PRESERVE_PREVIOUS,
                    primaryShader = ShaderProgramId.GLASS,
                    opticalNodes = listOf(OpticalNodeId.GLASS_REFRACTION)
                ),
                PassDescriptor(
                    passId = PassId.WATER_INTERIOR,
                    responsibility = "Water interior, wavy meniscus & tangent-normal distortion",
                    inputTextures = listOf(
                        "snapshot",
                        "04_WATER/waterbed.png",
                        "04_WATER/water_normal_tangent.png",
                        "04_WATER/water_relative_depth_16bit.png",
                        "04_WATER/caustics/caustics_height.png"
                    ),
                    outputTarget = "scene",
                    inputDependencies = listOf(PassId.SPHERE_BACK),
                    materialPolicy = "WATER_VOLUME",
                    depthPolicy = DepthPolicy.TRANSLUCENT_READ_ONLY,
                    blendMode = BlendMode.ALPHA,
                    viewport = vp,
                    clearPolicy = ClearPolicy.PRESERVE_PREVIOUS,
                    primaryShader = ShaderProgramId.WATER,
                    opticalNodes = listOf(OpticalNodeId.WATER_NORMAL_DISTORTION)
                ),
                PassDescriptor(
                    passId = PassId.FAR_ECOLOGY,
                    responsibility = "Far underwater flora & background stones",
                    inputTextures = listOf("06_PLANTS/*", "07_ROCKS/*"),
                    outputTarget = "scene",
                    inputDependencies = listOf(PassId.WATER_INTERIOR),
                    materialPolicy = "ALPHA_CUTOUT_FAR_ECOLOGY",
                    depthPolicy = DepthPolicy.ALPHA_CUTOUT_WRITE,
                    blendMode = BlendMode.ALPHA,
                    viewport = vp,
                    clearPolicy = ClearPolicy.PRESERVE_PREVIOUS,
                    primaryShader = ShaderProgramId.TRANSPARENT
                ),
                PassDescriptor(
                    passId = PassId.LIGHTING,
                    responsibility = "Volumetric sunbeams & spatial caustics lighting field",
                    inputTextures = listOf("04_WATER/caustics/caustics_height.png", "04_WATER/water_normal_tangent.png"),
                    outputTarget = "scene",
                    inputDependencies = listOf(PassId.FAR_ECOLOGY),
                    materialPolicy = "ADDITIVE_CAUSTICS_LIGHTING",
                    depthPolicy = DepthPolicy.TRANSLUCENT_READ_ONLY,
                    blendMode = BlendMode.ADDITIVE,
                    viewport = vp,
                    clearPolicy = ClearPolicy.PRESERVE_PREVIOUS,
                    primaryShader = ShaderProgramId.CAUSTICS,
                    opticalNodes = listOf(OpticalNodeId.CAUSTICS)
                ),
                PassDescriptor(
                    passId = PassId.FISH,
                    responsibility = "Four calibrated fish with underwater directional shading",
                    inputTextures = listOf("05_FISH/fish_01..04.png"),
                    outputTarget = "scene",
                    inputDependencies = listOf(PassId.LIGHTING),
                    materialPolicy = "ALPHA_CUTOUT_FISH",
                    depthPolicy = DepthPolicy.ALPHA_CUTOUT_WRITE,
                    blendMode = BlendMode.ALPHA,
                    viewport = vp,
                    clearPolicy = ClearPolicy.PRESERVE_PREVIOUS,
                    primaryShader = ShaderProgramId.TRANSPARENT
                ),
                PassDescriptor(
                    passId = PassId.NEAR_ECOLOGY,
                    responsibility = "Foreground plants, caustic stones & inner rising micro-bubbles",
                    inputTextures = listOf(
                        "06_PLANTS/*",
                        "07_ROCKS/*",
                        "09_BUBBLES/bubble_sprite.png",
                        "04_WATER/caustics/caustics_height.png"
                    ),
                    outputTarget = "scene",
                    inputDependencies = listOf(PassId.FISH),
                    materialPolicy = "ALPHA_CUTOUT_AND_BUBBLES",
                    depthPolicy = DepthPolicy.ALPHA_CUTOUT_WRITE,
                    blendMode = BlendMode.ALPHA,
                    viewport = vp,
                    clearPolicy = ClearPolicy.PRESERVE_PREVIOUS,
                    primaryShader = ShaderProgramId.TRANSPARENT,
                    opticalNodes = listOf(OpticalNodeId.CAUSTICS)
                ),
                PassDescriptor(
                    passId = PassId.SPHERE_FRONT,
                    responsibility = "Front crystal glass lens refraction, multi-ring Fresnel & flares",
                    inputTextures = listOf("snapshot", "03_SPHERE/glass_shell_mask.png"),
                    outputTarget = "scene",
                    inputDependencies = listOf(PassId.NEAR_ECOLOGY),
                    materialPolicy = "GLASS_FRONT",
                    depthPolicy = DepthPolicy.TRANSLUCENT_READ_ONLY,
                    blendMode = BlendMode.ALPHA,
                    viewport = vp,
                    clearPolicy = ClearPolicy.PRESERVE_PREVIOUS,
                    primaryShader = ShaderProgramId.GLASS,
                    opticalNodes = listOf(OpticalNodeId.GLASS_REFRACTION)
                ),
                PassDescriptor(
                    passId = PassId.RESIDENT_FRONT,
                    responsibility = "Foreground weaving Resident ribbons in front of glass sphere",
                    inputTextures = emptyList(),
                    outputTarget = "scene",
                    inputDependencies = listOf(PassId.SPHERE_FRONT),
                    materialPolicy = "TRANSLUCENT_RESIDENT",
                    depthPolicy = DepthPolicy.TRANSLUCENT_READ_ONLY,
                    blendMode = BlendMode.SCREEN,
                    viewport = vp,
                    clearPolicy = ClearPolicy.PRESERVE_PREVIOUS,
                    primaryShader = ShaderProgramId.RESIDENT_RIBBON
                ),
                PassDescriptor(
                    passId = PassId.FINAL_COMPOSITE,
                    responsibility = "5-stage Bloom composite, foreground Bokeh/droplets & luminance tone rolloff",
                    inputTextures = listOf("scene", "bloomHalfA", "bloomQuarterA", "09_BUBBLES/bubble_sprite.png"),
                    outputTarget = "finalTarget",
                    inputDependencies = listOf(PassId.RESIDENT_FRONT),
                    materialPolicy = "FINAL_OPTICAL_COMPOSITE",
                    depthPolicy = DepthPolicy.POST_PROCESS_NO_DEPTH,
                    blendMode = BlendMode.NONE,
                    viewport = vp,
                    clearPolicy = ClearPolicy.CLEAR_COLOR_ONLY,
                    primaryShader = ShaderProgramId.BLOOM,
                    opticalNodes = listOf(OpticalNodeId.BLOOM, OpticalNodeId.BOKEH)
                )
            )
        }

        /**
         * Validates that all 11 passes are unique, ordered topologically, free of cycles,
         * cover all 5 optical nodes, and free of target read/write feedback loops.
         */
        fun validateGraph(passes: List<PassDescriptor>) {
            require(passes.size == 11) { "Expected 11 composition stages, got ${passes.size}" }
            val seen = linkedSetOf<PassId>()
            for ((index, pass) in passes.withIndex()) {
                require(pass.passId.order == index + 1) {
                    "Pass ${pass.passId.wireId} order mismatch: ${pass.passId.order} != ${index + 1}"
                }
                require(pass.passId !in seen) { "Duplicate passId: ${pass.passId.wireId}" }
                for (dep in pass.inputDependencies) {
                    require(dep in seen) {
                        "Dependency '${dep.wireId}' for pass '${pass.passId.wireId}' is missing or executes after it"
                    }
                }
                require(pass.outputTarget !in pass.inputTextures) {
                    "FBO feedback loop in pass '${pass.passId.wireId}': target '${pass.outputTarget}' is also an input texture"
                }
                seen += pass.passId
            }
            val opticalCoverage = passes.flatMap { it.opticalNodes }.toSet()
            for (opt in OpticalNodeId.entries) {
                require(opt in opticalCoverage) { "Required optical node '${opt.wireId}' is not scheduled in Render Graph" }
            }
        }

        /** Resolves expected DepthPolicy for a given draw material category. */
        fun depthPolicyForMaterial(material: String, depthWriteFlag: Boolean): DepthPolicy = when (material) {
            "background" -> DepthPolicy.OPAQUE_WRITE
            "fish", "plant", "rock" -> if (depthWriteFlag) DepthPolicy.ALPHA_CUTOUT_WRITE else DepthPolicy.TRANSLUCENT_READ_ONLY
            "resident", "water", "glass", "bubble" -> DepthPolicy.TRANSLUCENT_READ_ONLY
            else -> DepthPolicy.TRANSLUCENT_READ_ONLY
        }
    }
}
