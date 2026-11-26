package com.example.waterworld

/**
 * Motion compatibility contract preserving the 47 elements and 28 dependency edges from
 * motion_element_registry.json / motion_dependency_graph.json without copying AI Orb
 * dynamic speeds or inventing unverified Water World animation rates.
 */
object WaterWorldMotionContract {

    const val STATIC_REFERENCE_TIME_SECONDS = 0.0
    const val CORE_VOLUME_SLICE_COUNT = 32
    const val CORE_VOLUME_SLICE_SEMANTICS = "SPATIAL_Z_SLICE_NOT_TIME_FRAME"

    enum class MotionDomain {
        CORE_INTERNAL,
        RESIDENT_PERIMETER,
        ECOSYSTEM,
        WATER_SYSTEM,
        GLASS_OPTICS,
    }

    data class Pivot2D(val x: Float, val y: Float)

    data class TransformNode(
        val elementId: String,
        val parentElementId: String?,
        val domain: MotionDomain,
        val coordinateSpace: String,
        val pivot: Pivot2D? = null,
        val localModelMatrix: FloatArray = identityMatrix(),
    )

    /**
     * All unverified Water World temporal rates MUST remain null until dynamic reference
     * video validation is completed in Work 2.
     */
    data class MotionBinding(
        val elementId: String,
        val motionModel: String,
        val angularVelocity: Float? = null,
        val phaseOffset: Float? = null,
        val amplitude: Float? = null,
        val frequency: Float? = null,
        val flowVelocity: Float? = null,
        val deformationRate: Float? = null,
        val waterWorldMotionValuesValidated: Boolean = false,
    ) {
        init {
            if (!waterWorldMotionValuesValidated) {
                require(angularVelocity == null) { "Unverified angularVelocity must be null for $elementId" }
                require(phaseOffset == null) { "Unverified phaseOffset must be null for $elementId" }
                require(amplitude == null) { "Unverified amplitude must be null for $elementId" }
                require(frequency == null) { "Unverified frequency must be null for $elementId" }
                require(flowVelocity == null) { "Unverified flowVelocity must be null for $elementId" }
                require(deformationRate == null) { "Unverified deformationRate must be null for $elementId" }
            }
        }
    }

    data class MotionDependencyEdge(
        val source: String,
        val target: String,
        val dependencyType: String,
        val affectedPass: String,
    )

    /** Preserves the 32 WebP spatial Z-slice volume sampling contract (never time frames). */
    interface VolumeSamplingInterface {
        val sliceCount: Int get() = CORE_VOLUME_SLICE_COUNT
        val sliceSemantics: String get() = CORE_VOLUME_SLICE_SEMANTICS
        fun sampleDensity(u: Float, v: Float, zSliceNorm: Float): Float
        fun sampleColorRgb(u: Float, v: Float, zSliceNorm: Float, outRgb: FloatArray)
    }

    interface FlowFieldInterface {
        fun sampleVelocity(u: Float, v: Float, timeSeconds: Double, outVelocity: FloatArray)
    }

    interface DeformationInterface {
        fun evaluateOffset(elementId: String, u: Float, v: Float, timeSeconds: Double, outDelta: FloatArray)
    }

    /** Immutable per-frame snapshot shared across all 11 render passes so every pass sees identical state. */
    data class SharedFrameSnapshot(
        val frameIndex: Long,
        val timeSeconds: Double = STATIC_REFERENCE_TIME_SECONDS,
        val canvasWidth: Int = 1254,
        val canvasHeight: Int = 1254,
        val staticReferenceMode: Boolean = true,
    ) {
        companion object {
            fun staticZero(): SharedFrameSnapshot = SharedFrameSnapshot(
                frameIndex = 0L,
                timeSeconds = STATIC_REFERENCE_TIME_SECONDS,
                canvasWidth = 1254,
                canvasHeight = 1254,
                staticReferenceMode = true
            )
        }
    }

    val CANONICAL_47_ELEMENTS: List<String> = listOf(
        "CORE_CAUSTICS", "CORE_COLOR", "CORE_DENSITY", "CORE_FILAMENT",
        "CORE_FLUID", "CORE_GLASS", "CORE_PARTICLES", "CORE_VOLUME",
        "WW_BACKGROUND",
        "WW_BUBBLE_B01", "WW_BUBBLE_B02", "WW_BUBBLE_B03", "WW_BUBBLE_B04",
        "WW_BUBBLE_B05", "WW_BUBBLE_B06", "WW_BUBBLE_B07", "WW_BUBBLE_B08", "WW_BUBBLE_B09",
        "WW_FISH_01", "WW_FISH_01_FINS", "WW_FISH_01_TAIL",
        "WW_FISH_02", "WW_FISH_02_FINS", "WW_FISH_02_TAIL",
        "WW_FISH_03", "WW_FISH_03_FINS", "WW_FISH_03_TAIL",
        "WW_FISH_04", "WW_FISH_04_FINS", "WW_FISH_04_TAIL",
        "WW_GLASS_BACK", "WW_GLASS_FRONT",
        "WW_PLANT_LEFT", "WW_PLANT_LEFT_LEAVES",
        "WW_PLANT_RIGHT", "WW_PLANT_RIGHT_LEAVES",
        "WW_RESIDENT_A01", "WW_RESIDENT_A02", "WW_RESIDENT_C01",
        "WW_RESIDENT_G01", "WW_RESIDENT_G02",
        "WW_ROCK_01", "WW_ROCK_02", "WW_ROCK_03",
        "WW_SPHERE", "WW_WATERBED", "WW_WATER_SURFACE",
    )

    val CANONICAL_28_DEPENDENCIES: List<MotionDependencyEdge> = listOf(
        MotionDependencyEdge("CORE_VOLUME", "CORE_FLUID", "GEOMETRY_AND_SAMPLING", "CORE_INTERNAL"),
        MotionDependencyEdge("CORE_VOLUME", "CORE_DENSITY", "GEOMETRY_AND_SAMPLING", "CORE_INTERNAL"),
        MotionDependencyEdge("CORE_VOLUME", "CORE_COLOR", "GEOMETRY_AND_SAMPLING", "CORE_INTERNAL"),
        MotionDependencyEdge("CORE_VOLUME", "CORE_CAUSTICS", "GEOMETRY_AND_SAMPLING", "CORE_INTERNAL"),
        MotionDependencyEdge("CORE_VOLUME", "CORE_GLASS", "GEOMETRY_AND_SAMPLING", "CORE_INTERNAL"),
        MotionDependencyEdge("CORE_FLUID", "CORE_DENSITY", "FLOW_AND_DEFORMATION", "CORE_INTERNAL"),
        MotionDependencyEdge("CORE_FLUID", "CORE_COLOR", "FLOW_AND_DEFORMATION", "CORE_INTERNAL"),
        MotionDependencyEdge("CORE_FLUID", "CORE_CAUSTICS", "FLOW_AND_DEFORMATION", "CORE_INTERNAL"),
        MotionDependencyEdge("CORE_FLUID", "CORE_GLASS", "FLOW_AND_DEFORMATION", "CORE_INTERNAL"),
        MotionDependencyEdge("CORE_DENSITY", "CORE_CAUSTICS", "DENSITY_FIELD", "CORE_INTERNAL"),
        MotionDependencyEdge("CORE_DENSITY", "CORE_GLASS", "OCCLUSION_AND_TRANSMISSION", "GLASS_OPTICS"),
        MotionDependencyEdge("CORE_COLOR", "CORE_GLASS", "MATERIAL_SAMPLE", "GLASS_OPTICS"),
        MotionDependencyEdge("CORE_CAUSTICS", "CORE_GLASS", "OPTICAL_RESPONSE", "GLASS_OPTICS"),
        MotionDependencyEdge("CORE_FILAMENT", "CORE_GLASS", "DEPTH_AND_OCCLUSION", "GLASS_OPTICS"),
        MotionDependencyEdge("CORE_PARTICLES", "CORE_GLASS", "DEPTH_AND_OCCLUSION", "GLASS_OPTICS"),
        MotionDependencyEdge("WW_FISH_01", "WW_FISH_01_TAIL", "PARENT_TRANSFORM", "ECOSYSTEM"),
        MotionDependencyEdge("WW_FISH_01", "WW_FISH_01_FINS", "PARENT_TRANSFORM", "ECOSYSTEM"),
        MotionDependencyEdge("WW_FISH_02", "WW_FISH_02_TAIL", "PARENT_TRANSFORM", "ECOSYSTEM"),
        MotionDependencyEdge("WW_FISH_02", "WW_FISH_02_FINS", "PARENT_TRANSFORM", "ECOSYSTEM"),
        MotionDependencyEdge("WW_FISH_03", "WW_FISH_03_TAIL", "PARENT_TRANSFORM", "ECOSYSTEM"),
        MotionDependencyEdge("WW_FISH_03", "WW_FISH_03_FINS", "PARENT_TRANSFORM", "ECOSYSTEM"),
        MotionDependencyEdge("WW_FISH_04", "WW_FISH_04_TAIL", "PARENT_TRANSFORM", "ECOSYSTEM"),
        MotionDependencyEdge("WW_FISH_04", "WW_FISH_04_FINS", "PARENT_TRANSFORM", "ECOSYSTEM"),
        MotionDependencyEdge("WW_PLANT_LEFT", "WW_PLANT_LEFT_LEAVES", "PARENT_DEFORMATION", "ECOSYSTEM"),
        MotionDependencyEdge("WW_PLANT_RIGHT", "WW_PLANT_RIGHT_LEAVES", "PARENT_DEFORMATION", "ECOSYSTEM"),
        MotionDependencyEdge("WW_WATER_SURFACE", "WW_PLANT_LEFT", "ENVIRONMENTAL_FLOW", "WATER_SYSTEM"),
        MotionDependencyEdge("WW_WATER_SURFACE", "WW_PLANT_RIGHT", "ENVIRONMENTAL_FLOW", "WATER_SYSTEM"),
        MotionDependencyEdge("WW_WATER_SURFACE", "WW_WATERBED", "NORMAL_AND_CAUSTIC_RESPONSE", "WATER_SYSTEM"),
    )

    fun defaultStaticBindings(): List<MotionBinding> = CANONICAL_47_ELEMENTS.map { id ->
        MotionBinding(elementId = id, motionModel = if (id == "WW_BACKGROUND" || id == "WW_SPHERE" || id == "WW_WATERBED") "STATIC" else "PREPARED_ONLY")
    }

    fun identityMatrix(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )
}
