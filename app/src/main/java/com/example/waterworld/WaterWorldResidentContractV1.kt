package com.example.waterworld

/**
 * Frozen V1 contract & canonical 13-segment arc-length parameterization for the Five Resident Filaments
 * (`WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1`).
 *
 * Scope: STRICTLY G01, G02, A01, A02, C01 and their 13 frozen Render Segments (8 Back + 5 Front).
 * Does NOT modify any frozen mesh geometry, Pass assignments, colors, Core 3D volume, fish, plants,
 * rocks, bubbles, water, glass, caustics, or background.
 */
object WaterWorldResidentContractV1 {

    const val CONTRACT_ID = "WATER_WORLD_FIVE_RESIDENT_FILAMENTS_DESIGN_FREEZE_V1"
    const val PROVENANCE_V1_FROZEN = "V1_DESIGN_TARGET_NOT_GROUND_TRUTH_MEASURED"
    const val STATIC_BASELINE_HEAD = "120aeb2c1f716771351bf951826539350bedb512"
    const val MASTER_PNG_SHA256 = "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589"

    const val TOTAL_RESIDENTS = 5
    const val TOTAL_SEGMENTS = 13
    const val BACK_SEGMENTS = 8
    const val FRONT_SEGMENTS = 5

    data class SegmentMapping(
        val parentResidentId: String,
        val parentResidentGroup: String,
        val segmentId: String,
        val segmentPass: String,
        val segmentArcLengthStart: Float,
        val segmentArcLengthEnd: Float,
        val localUvDirection: Float,
        val localFlowPhaseOffset: Float,
        val continuityStatus: String,
    ) {
        /** Maps a segment-local `u in [0, 1]` to canonical Resident arc-length `s in [0, 1]`. */
        fun mapLocalUToCanonicalS(localU: Float): Float {
            val directedU = if (localUvDirection >= 0f) localU else (1f - localU)
            return segmentArcLengthStart + (segmentArcLengthEnd - segmentArcLengthStart) * directedU + localFlowPhaseOffset
        }
    }

    val CANONICAL_SEGMENT_MAPPINGS: List<SegmentMapping> = listOf(
        // G01 — Lime Green (#9FE226), direction +1, period 16.0s
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_G01",
            parentResidentGroup = "G01",
            segmentId = "G01_back_arch",
            segmentPass = "02_RESIDENT_BACK",
            segmentArcLengthStart = 0.0000f,
            segmentArcLengthEnd = 0.5600f,
            localUvDirection = 1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "HYPOTHESIS_DISJOINT_OCCLUDED_GAP_GROUND_TRUTH_UNKNOWN",
        ),
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_G01",
            parentResidentGroup = "G01",
            segmentId = "G01_back_outer_veil",
            segmentPass = "02_RESIDENT_BACK",
            segmentArcLengthStart = 0.2400f,
            segmentArcLengthEnd = 0.5500f,
            localUvDirection = 1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "PARALLEL_VEIL_CO_PHASED_GROUND_TRUTH_UNKNOWN",
        ),
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_G01",
            parentResidentGroup = "G01",
            segmentId = "G01_front_lower_sweep",
            segmentPass = "10_RESIDENT_FRONT",
            segmentArcLengthStart = 0.6000f,
            segmentArcLengthEnd = 0.9600f,
            localUvDirection = -1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "HYPOTHESIS_DISJOINT_OCCLUDED_GAP_GROUND_TRUTH_UNKNOWN",
        ),

        // G02 — Warm Gold (#FFC247), direction -1, period 18.0s
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_G02",
            parentResidentGroup = "G02",
            segmentId = "G02_back_upper_arc",
            segmentPass = "02_RESIDENT_BACK",
            segmentArcLengthStart = 0.0000f,
            segmentArcLengthEnd = 0.3400f,
            localUvDirection = 1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "HYPOTHESIS_PARTIAL_GAP_GROUND_TRUTH_UNKNOWN",
        ),
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_G02",
            parentResidentGroup = "G02",
            segmentId = "G02_back_upper_hairline",
            segmentPass = "02_RESIDENT_BACK",
            segmentArcLengthStart = 0.0200f,
            segmentArcLengthEnd = 0.3100f,
            localUvDirection = 1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "PARALLEL_HAIRLINE_CO_PHASED_GROUND_TRUTH_UNKNOWN",
        ),
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_G02",
            parentResidentGroup = "G02",
            segmentId = "G02_back_right_rim",
            segmentPass = "02_RESIDENT_BACK",
            segmentArcLengthStart = 0.4300f,
            segmentArcLengthEnd = 0.6400f,
            localUvDirection = 1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "HYPOTHESIS_PARTIAL_GAP_GROUND_TRUTH_UNKNOWN",
        ),
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_G02",
            parentResidentGroup = "G02",
            segmentId = "G02_front_right_embrace",
            segmentPass = "10_RESIDENT_FRONT",
            segmentArcLengthStart = 0.6400f,
            segmentArcLengthEnd = 1.0000f,
            localUvDirection = 1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "HYPOTHESIS_PARTIAL_GAP_GROUND_TRUTH_UNKNOWN",
        ),

        // A01 — Cyan (#37E7EF), direction +1, period 20.0s (exact 0.0px center seam at s = 0.2863)
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_A01",
            parentResidentGroup = "A01",
            segmentId = "A01_back_upper_left",
            segmentPass = "02_RESIDENT_BACK",
            segmentArcLengthStart = 0.0000f,
            segmentArcLengthEnd = 0.2863f,
            localUvDirection = 1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "GEOMETRICALLY_CONTIGUOUS_SEAM_0PX",
        ),
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_A01",
            parentResidentGroup = "A01",
            segmentId = "A01_front_left_embrace",
            segmentPass = "10_RESIDENT_FRONT",
            segmentArcLengthStart = 0.2863f,
            segmentArcLengthEnd = 1.0000f,
            localUvDirection = 1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "GEOMETRICALLY_CONTIGUOUS_SEAM_0PX",
        ),

        // A02 — Cyan Teal (#15D8ED), direction -1, period 14.0s
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_A02",
            parentResidentGroup = "A02",
            segmentId = "A02_front_lower_strand",
            segmentPass = "10_RESIDENT_FRONT",
            segmentArcLengthStart = 0.0000f,
            segmentArcLengthEnd = 0.7200f,
            localUvDirection = 1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "HYPOTHESIS_OVERLAPPING_LOWER_HALO_GROUND_TRUTH_UNKNOWN",
        ),
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_A02",
            parentResidentGroup = "A02",
            segmentId = "A02_back_lower_halo",
            segmentPass = "02_RESIDENT_BACK",
            segmentArcLengthStart = 0.4700f,
            segmentArcLengthEnd = 1.0000f,
            localUvDirection = 1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "HYPOTHESIS_OVERLAPPING_LOWER_HALO_GROUND_TRUTH_UNKNOWN",
        ),

        // C01 — Gold (#F6BB48), direction +1, period 22.0s
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_C01",
            parentResidentGroup = "C01",
            segmentId = "C01_front_lower_right",
            segmentPass = "10_RESIDENT_FRONT",
            segmentArcLengthStart = 0.0000f,
            segmentArcLengthEnd = 0.5388f,
            localUvDirection = 1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "HYPOTHESIS_LOWER_BRIDGE_GAP_260PX_GROUND_TRUTH_UNKNOWN",
        ),
        SegmentMapping(
            parentResidentId = "WW_RESIDENT_C01",
            parentResidentGroup = "C01",
            segmentId = "C01_back_lower_thread",
            segmentPass = "02_RESIDENT_BACK",
            segmentArcLengthStart = 0.5388f,
            segmentArcLengthEnd = 1.0000f,
            localUvDirection = -1f,
            localFlowPhaseOffset = 0.0f,
            continuityStatus = "HYPOTHESIS_LOWER_BRIDGE_GAP_260PX_GROUND_TRUTH_UNKNOWN",
        ),
    )

    private val mappingBySegmentId: Map<String, SegmentMapping> =
        CANONICAL_SEGMENT_MAPPINGS.associateBy { it.segmentId }

    fun mappingForSegment(segmentId: String): SegmentMapping =
        mappingBySegmentId[segmentId]
            ?: error("Unknown Resident segment '$segmentId' (not in frozen 13-segment V1 table)")

    fun mappingForSegmentOrNull(segmentId: String): SegmentMapping? =
        mappingBySegmentId[segmentId]
}
