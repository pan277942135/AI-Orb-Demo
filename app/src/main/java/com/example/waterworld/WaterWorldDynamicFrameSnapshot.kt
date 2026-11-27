package com.example.waterworld

import org.json.JSONArray
import org.json.JSONObject

/**
 * Single immutable per-frame dynamic state snapshot shared across all 11 Render Passes
 * and all 5 independent Optical Nodes in Work 3.
 *
 * Guarantees:
 * - Zero hidden mutable state inside passes or shaders.
 * - Covers all 47 canonical motion elements and 28 canonical dependency edges.
 * - At `staticReferenceMode = true` or `globalTimeSeconds == 0.0`, every displacement,
 *   deformation, and phase offset evaluates to exact `0.0` (exact Work 2 T=0 parity).
 */
data class WaterWorldDynamicFrameSnapshot(
    val frameId: Long,
    val globalTimeSeconds: Double,
    val staticReferenceMode: Boolean,
    val randomSeed: Long,
    val provenance: String,
    val fishStates: Map<String, FishMotionState>,
    val residentStates: Map<String, ResidentMotionState>,
    val plantStates: Map<String, PlantMotionState>,
    val rockStates: Map<String, RockMotionState>,
    val outerBubbleStates: Map<String, BubbleMotionState>,
    val innerBubbleStates: Map<String, BubbleMotionState>,
    val waterOptics: WaterOpticsMotionState,
    val coreState: CoreMotionState,
    val elementStates: Map<String, ElementMotionState>,
    val dependencyStates: List<DependencyExecutionState>,
) {
    data class FishMotionState(
        val id: String,
        val drawId: String,
        val pivotX: Float,
        val pivotY: Float,
        val offsetX: Float,
        val offsetY: Float,
        val offsetZ: Float,
        val pitchRad: Float,
        val facingScaleX: Float,
        val depthScale: Float,
        val tailPhaseRad: Float,
        val tailAmpPx: Float,
        val finPhaseRad: Float,
        val finAmpPx: Float,
        val pathProgress: Float,
    )

    data class ResidentMotionState(
        val id: String,
        val groupKey: String,
        val drawIds: List<String>,
        val flowPhaseRad: Float,
        val flowOffsetU: Float,
        val widthPulseScale: Float,
        val brightnessScale: Float,
        val lateralWavePx: Float,
        val wavePhaseRad: Float,
        val occlusionStatus: String,
    )

    data class PlantMotionState(
        val id: String,
        val drawIds: List<String>,
        val swayAmpPx: Float,
        val swayPhaseRad: Float,
        val leafFlutterAmpPx: Float,
        val leafFlutterPhaseRad: Float,
        val waterFlowCoupling: Float,
    )

    data class RockMotionState(
        val id: String,
        val drawIds: List<String>,
        val offsetX: Float,
        val offsetY: Float,
        val rotationRad: Float,
        val physicallyStationary: Boolean,
        val causticsPhaseRad: Float,
        val causticsGain: Float,
    )

    data class BubbleMotionState(
        val id: String,
        val drawId: String,
        val offsetX: Float,
        val offsetY: Float,
        val scaleMul: Float,
        val alphaMul: Float,
        val cycleProgress: Float,
    )

    data class WaterOpticsMotionState(
        val waterNormalOffsetU: Float,
        val waterNormalOffsetV: Float,
        val meniscusWavePhaseRad: Float,
        val meniscusSecondaryPhaseRad: Float,
        val waterFlowPhaseRad: Float,
        val causticsPhaseRad: Float,
        val lightingShimmerScale: Float,
        val glassRefractionScale: Float,
        val glassFresnelScale: Float,
        val glassOpticalPhaseRad: Float,
        val bloomResponseScale: Float,
        val bokehDriftOffsetU: Float,
        val bokehDriftOffsetV: Float,
        val bokehPhaseRad: Float,
    )

    data class CoreMotionState(
        val sliceCount: Int,
        val sliceSemantics: String,
        val rotationAngleDeg: Float,
        val angularVelocityDegPerSec: Float,
        val rotationMat3: FloatArray,
        val fluidPhaseRad: Float,
        val densityScale: Float,
        val colorShift: Float,
        val causticsPhaseRad: Float,
        val glassOpticalResponse: Float,
        val particlesPhaseRad: Float,
        val filamentPhaseRad: Float,
        val filamentStatus: String,
        val createsDuplicateGlassShell: Boolean,
    )

    data class ElementMotionState(
        val elementId: String,
        val active: Boolean,
        val phaseRad: Float,
        val displacementPx: Float,
        val modulationScale: Float,
        val provenance: String,
    )

    data class DependencyExecutionState(
        val index: Int,
        val sourceId: String,
        val targetId: String,
        val relation: String,
        val evaluatedAtTimeSec: Double,
        val signalValue: Float,
    )

    fun toSharedFrameSnapshot(): WaterWorldMotionContract.SharedFrameSnapshot =
        WaterWorldMotionContract.SharedFrameSnapshot(
            frameIndex = frameId,
            timeSeconds = globalTimeSeconds,
            canvasWidth = 1254,
            canvasHeight = 1254,
            staticReferenceMode = staticReferenceMode,
        )

    fun toTimelineJson(): JSONObject = JSONObject()
        .put("frameId", frameId)
        .put("globalTimeSeconds", globalTimeSeconds)
        .put("staticReferenceMode", staticReferenceMode)
        .put("randomSeed", randomSeed)
        .put("provenance", provenance)
        .put("elementCount", elementStates.size)
        .put("dependencyCount", dependencyStates.size)
        .put("coreVolumeSliceCount", coreState.sliceCount)
        .put("coreVolumeSliceSemantics", coreState.sliceSemantics)
        .put("coreRotationAngleDeg", coreState.rotationAngleDeg.toDouble())
        .put("coreFilamentStatus", coreState.filamentStatus)
        .put("waterNormalOffset", JSONArray(listOf(waterOptics.waterNormalOffsetU.toDouble(), waterOptics.waterNormalOffsetV.toDouble())))
        .put("causticsPhaseRad", waterOptics.causticsPhaseRad.toDouble())

    fun toElementStatesJson(): JSONObject {
        val arr = JSONArray()
        for (id in WaterWorldMotionContract.CANONICAL_47_ELEMENTS) {
            val st = elementStates[id] ?: continue
            arr.put(
                JSONObject()
                    .put("elementId", st.elementId)
                    .put("active", st.active)
                    .put("phaseRad", st.phaseRad.toDouble())
                    .put("displacementPx", st.displacementPx.toDouble())
                    .put("modulationScale", st.modulationScale.toDouble())
                    .put("provenance", st.provenance)
            )
        }
        return JSONObject()
            .put("frameId", frameId)
            .put("globalTimeSeconds", globalTimeSeconds)
            .put("count", arr.length())
            .put("elements", arr)
    }

    fun toDependencyExecutionJson(): JSONObject {
        val arr = JSONArray()
        for (dep in dependencyStates) {
            arr.put(
                JSONObject()
                    .put("index", dep.index)
                    .put("sourceId", dep.sourceId)
                    .put("targetId", dep.targetId)
                    .put("relation", dep.relation)
                    .put("evaluatedAtTimeSec", dep.evaluatedAtTimeSec)
                    .put("signalValue", dep.signalValue.toDouble())
            )
        }
        return JSONObject()
            .put("frameId", frameId)
            .put("globalTimeSeconds", globalTimeSeconds)
            .put("count", arr.length())
            .put("dependencies", arr)
    }

    companion object {
        fun staticZero(profile: WaterWorldMotionProfile = WaterWorldMotionProfile.defaultProfile()): WaterWorldDynamicFrameSnapshot =
            WaterWorldMotionEvaluator.evaluate(
                profile = profile,
                timeSeconds = 0.0,
                frameId = 0L,
                randomSeed = profile.deterministicSeed,
                staticReferenceMode = true,
            )
    }
}
