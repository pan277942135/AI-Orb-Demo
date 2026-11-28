package com.example.waterworld

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Work 3 unified motion profile parsed from `04_MOTION/work3_motion_profile.json`
 * (or built-in fallback matching the exact authored JSON).
 *
 * Strictly enforces:
 * - `motionParameterProvenance == "WORK3_AUTHORED_RUNTIME_HYPOTHESIS"`
 * - `waterWorldMotionValuesValidated == false`
 * - `coreVolumeSliceCount == 32`
 * - `coreVolumeSliceSemantics == "SPATIAL_Z_SLICE_NOT_TIME_FRAME"`
 * - Independent non-identical parameters across 4 fish, 5 residents, 2 plants, 3 rocks, 9 bubbles.
 */
data class WaterWorldMotionProfile(
    val schemaVersion: String,
    val motionParameterProvenance: String,
    val waterWorldMotionValuesValidated: Boolean,
    val coreVolumeSliceCount: Int,
    val coreVolumeSliceSemantics: String,
    val canvasSizePx: Float,
    val sphereCenterX: Float,
    val sphereCenterY: Float,
    val sphereOuterRadiusPx: Float,
    val sphereInnerRadiusPx: Float,
    val autoPlayOnEnter: Boolean,
    val defaultSpeedScale: Float,
    val maxFrameDeltaSec: Double,
    val deterministicSeed: Long,
    val fish: List<FishProfile>,
    val residents: List<ResidentProfile>,
    val plants: List<PlantProfile>,
    val rocks: List<RockProfile>,
    val bubbles: List<BubbleProfile>,
    val waterAndOptics: WaterOpticsProfile,
    val core: CoreProfile,
) {
    data class Vec3Offset(val dx: Float, val dy: Float, val dz: Float)

    data class FishProfile(
        val id: String,
        val tailId: String,
        val finsId: String,
        val drawId: String,
        val provenance: String,
        val t0CenterX: Float,
        val t0CenterY: Float,
        val widthPx: Float,
        val heightPx: Float,
        val pathPeriodSec: Float,
        val phaseOffsetRad: Float,
        val verticalBobPx: Float,
        val verticalBobHz: Float,
        val tailFreqHz: Float,
        val tailAmpDeg: Float,
        val finFreqHz: Float,
        val finAmpDeg: Float,
        val maxPitchDeg: Float,
        val minDepthScale: Float,
        val maxDepthScale: Float,
        val waypointsOffsetPx: List<Vec3Offset>,
    )

    data class ResidentProfile(
        val id: String,
        val groupKey: String,
        val flowId: String,
        val pulseId: String,
        val provenance: String,
        val occlusionStatus: String,
        val drawIds: List<String>,
        val cyclePeriodSec: Float,
        val flowDirection: Float,
        val phaseOffsetRad: Float,
        val widthPulseAmp: Float,
        val widthPulseHz: Float,
        val brightnessPulseAmp: Float,
        val brightnessPulseHz: Float,
        val lateralWavePx: Float,
    )

    data class PlantProfile(
        val id: String,
        val leavesId: String,
        val drawIds: List<String>,
        val provenance: String,
        val swayFreqHz: Float,
        val leafFlutterHz: Float,
        val maxSwayHeightRatio: Float,
        val phaseOffsetRad: Float,
        val waterFlowCoupling: Float,
    )

    data class RockProfile(
        val id: String,
        val drawIds: List<String>,
        val physicallyStationary: Boolean,
        val causticsResponseGain: Float,
        val provenance: String,
    )

    data class BubbleProfile(
        val id: String,
        val drawId: String,
        val risePeriodSec: Float,
        val riseSpanPx: Float,
        val driftAmpPx: Float,
        val driftHz: Float,
        val phaseRad: Float,
        val scalePulseAmp: Float,
        val seed: Long,
    )

    data class WaterOpticsProfile(
        val provenance: String,
        val waterNormalUvVelX: Float,
        val waterNormalUvVelY: Float,
        val waterMeniscusWaveRadPerSec: Float,
        val waterMeniscusSecondaryRadPerSec: Float,
        val waterFlowRadPerSec: Float,
        val causticsPhaseRadPerSec: Float,
        val godRayShimmerHz: Float,
        val godRayShimmerAmp: Float,
        val glassRefractionShimmerAmp: Float,
        val glassFresnelPulseAmp: Float,
        val bloomResponseCoupling: Float,
        val bokehDriftUvAmp: Float,
        val bokehDriftHz: Float,
    )

    data class CoreProfile(
        val provenance: String,
        val coreVolumeSliceCount: Int,
        val coreVolumeSliceSemantics: String,
        val rotationAxisX: Float,
        val rotationAxisY: Float,
        val rotationAxisZ: Float,
        val rotationDegPerSec: Float,
        val fluidRadPerSec: Float,
        val densityPulseAmp: Float,
        val densityPulseHz: Float,
        val colorShiftAmp: Float,
        val colorShiftHz: Float,
        val causticsRadPerSec: Float,
        val glassOpticalCoupling: Float,
        val particlesRadPerSec: Float,
        val filamentStatus: String,
        val filamentRadPerSec: Float,
    )

    fun validate() {
        require(motionParameterProvenance == PROVENANCE_WORK3_HYPOTHESIS) {
            "Work 3 profile must declare provenance=$PROVENANCE_WORK3_HYPOTHESIS"
        }
        require(!waterWorldMotionValuesValidated) {
            "Work 3 authored motion parameters must NOT claim waterWorldMotionValuesValidated=true"
        }
        require(coreVolumeSliceCount == WaterWorldMotionContract.CORE_VOLUME_SLICE_COUNT) {
            "coreVolumeSliceCount must remain 32"
        }
        require(coreVolumeSliceSemantics == WaterWorldMotionContract.CORE_VOLUME_SLICE_SEMANTICS) {
            "coreVolumeSliceSemantics must remain SPATIAL_Z_SLICE_NOT_TIME_FRAME"
        }
        require(fish.size == 4) { "Expected 4 fish profiles, got ${fish.size}" }
        require(fish.map { it.pathPeriodSec }.toSet().size == 4) { "All 4 fish must have distinct periods" }
        require(fish.map { it.tailFreqHz }.toSet().size == 4) { "All 4 fish must have distinct tail frequencies" }
        require(residents.size == 5) { "Expected 5 resident profiles, got ${residents.size}" }
        require(residents.map { it.cyclePeriodSec }.toSet().size == 5) { "All 5 residents must have distinct periods" }
        require(residents.map { it.phaseOffsetRad }.toSet().size == 5) { "All 5 residents must have distinct initial phases" }
        require(plants.size == 2) { "Expected 2 plant profiles, got ${plants.size}" }
        require(rocks.size == 3 && rocks.all { it.physicallyStationary }) { "All 3 rocks must be stationary" }
        require(bubbles.size == 9) { "Expected 9 outer bubble profiles, got ${bubbles.size}" }
        require(bubbles.map { it.risePeriodSec }.toSet().size == 9) { "All 9 bubbles must have distinct rise periods" }
        require(core.rotationDegPerSec in 2.0f..6.0f) {
            "Water World core rotation speed must stay in 2..6 deg/s, got ${core.rotationDegPerSec}"
        }
    }

    companion object {
        const val PROVENANCE_WORK3_HYPOTHESIS = "WORK3_AUTHORED_RUNTIME_HYPOTHESIS"
        const val RELATIVE_ASSET_PATH = "04_MOTION/work3_motion_profile.json"

        fun loadFromAssetRoot(assetRoot: File?): WaterWorldMotionProfile {
            val file = assetRoot?.let { File(it, RELATIVE_ASSET_PATH) }
            if (file != null && file.isFile) {
                return parseJson(JSONObject(file.readText())).also { it.validate() }
            }
            return defaultProfile().also { it.validate() }
        }

        fun parseJson(root: JSONObject): WaterWorldMotionProfile {
            val sphere = root.getJSONObject("sphere")
            val center = sphere.getJSONArray("center_px")
            val playback = root.getJSONObject("playback")

            val fishArr = root.getJSONArray("fish")
            val fish = (0 until fishArr.length()).map { i ->
                val o = fishArr.getJSONObject(i)
                val t0 = o.getJSONArray("t0_center_px")
                val sz = o.getJSONArray("size_px")
                val dRange = o.getJSONArray("depth_scale_range")
                val wpArr = o.getJSONArray("waypoints_offset_px")
                val wps = (0 until wpArr.length()).map { j ->
                    val w = wpArr.getJSONArray(j)
                    Vec3Offset(w.getDouble(0).toFloat(), w.getDouble(1).toFloat(), w.getDouble(2).toFloat())
                }
                FishProfile(
                    id = o.getString("id"),
                    tailId = o.getString("tail_id"),
                    finsId = o.getString("fins_id"),
                    drawId = o.getString("draw_id"),
                    provenance = o.getString("provenance"),
                    t0CenterX = t0.getDouble(0).toFloat(),
                    t0CenterY = t0.getDouble(1).toFloat(),
                    widthPx = sz.getDouble(0).toFloat(),
                    heightPx = sz.getDouble(1).toFloat(),
                    pathPeriodSec = o.getDouble("path_period_sec").toFloat(),
                    phaseOffsetRad = o.getDouble("phase_offset_rad").toFloat(),
                    verticalBobPx = o.getDouble("vertical_bob_px").toFloat(),
                    verticalBobHz = o.getDouble("vertical_bob_hz").toFloat(),
                    tailFreqHz = o.getDouble("tail_freq_hz").toFloat(),
                    tailAmpDeg = o.getDouble("tail_amp_deg").toFloat(),
                    finFreqHz = o.getDouble("fin_freq_hz").toFloat(),
                    finAmpDeg = o.getDouble("fin_amp_deg").toFloat(),
                    maxPitchDeg = o.getDouble("max_pitch_deg").toFloat(),
                    minDepthScale = dRange.getDouble(0).toFloat(),
                    maxDepthScale = dRange.getDouble(1).toFloat(),
                    waypointsOffsetPx = wps,
                )
            }

            val resArr = root.getJSONArray("residents")
            val residents = (0 until resArr.length()).map { i ->
                val o = resArr.getJSONObject(i)
                ResidentProfile(
                    id = o.getString("id"),
                    groupKey = o.getString("group_key"),
                    flowId = o.getString("flow_id"),
                    pulseId = o.getString("pulse_id"),
                    provenance = o.getString("provenance"),
                    occlusionStatus = o.getString("occlusion_status"),
                    drawIds = jsonStringList(o.getJSONArray("draw_ids")),
                    cyclePeriodSec = o.getDouble("cycle_period_sec").toFloat(),
                    flowDirection = o.getDouble("flow_direction").toFloat(),
                    phaseOffsetRad = o.getDouble("phase_offset_rad").toFloat(),
                    widthPulseAmp = o.getDouble("width_pulse_amp").toFloat(),
                    widthPulseHz = o.getDouble("width_pulse_hz").toFloat(),
                    brightnessPulseAmp = o.getDouble("brightness_pulse_amp").toFloat(),
                    brightnessPulseHz = o.getDouble("brightness_pulse_hz").toFloat(),
                    lateralWavePx = o.getDouble("lateral_wave_px").toFloat(),
                )
            }

            val plantArr = root.getJSONArray("plants")
            val plants = (0 until plantArr.length()).map { i ->
                val o = plantArr.getJSONObject(i)
                PlantProfile(
                    id = o.getString("id"),
                    leavesId = o.getString("leaves_id"),
                    drawIds = jsonStringList(o.getJSONArray("draw_ids")),
                    provenance = o.getString("provenance"),
                    swayFreqHz = o.getDouble("sway_freq_hz").toFloat(),
                    leafFlutterHz = o.getDouble("leaf_flutter_hz").toFloat(),
                    maxSwayHeightRatio = o.getDouble("max_sway_height_ratio").toFloat(),
                    phaseOffsetRad = o.getDouble("phase_offset_rad").toFloat(),
                    waterFlowCoupling = o.getDouble("water_flow_coupling").toFloat(),
                )
            }

            val rockArr = root.getJSONArray("rocks")
            val rocks = (0 until rockArr.length()).map { i ->
                val o = rockArr.getJSONObject(i)
                RockProfile(
                    id = o.getString("id"),
                    drawIds = jsonStringList(o.getJSONArray("draw_ids")),
                    physicallyStationary = o.getBoolean("physically_stationary"),
                    causticsResponseGain = o.getDouble("caustics_response_gain").toFloat(),
                    provenance = o.getString("provenance"),
                )
            }

            val bubArr = root.getJSONArray("bubbles")
            val bubbles = (0 until bubArr.length()).map { i ->
                val o = bubArr.getJSONObject(i)
                BubbleProfile(
                    id = o.getString("id"),
                    drawId = o.getString("draw_id"),
                    risePeriodSec = o.getDouble("rise_period_sec").toFloat(),
                    riseSpanPx = o.getDouble("rise_span_px").toFloat(),
                    driftAmpPx = o.getDouble("drift_amp_px").toFloat(),
                    driftHz = o.getDouble("drift_hz").toFloat(),
                    phaseRad = o.getDouble("phase_rad").toFloat(),
                    scalePulseAmp = o.getDouble("scale_pulse_amp").toFloat(),
                    seed = o.getLong("seed"),
                )
            }

            val wo = root.getJSONObject("water_and_optics")
            val uvVel = wo.getJSONArray("water_normal_uv_velocity")
            val waterOptics = WaterOpticsProfile(
                provenance = wo.getString("provenance"),
                waterNormalUvVelX = uvVel.getDouble(0).toFloat(),
                waterNormalUvVelY = uvVel.getDouble(1).toFloat(),
                waterMeniscusWaveRadPerSec = wo.getDouble("water_meniscus_wave_rad_per_sec").toFloat(),
                waterMeniscusSecondaryRadPerSec = wo.getDouble("water_meniscus_secondary_rad_per_sec").toFloat(),
                waterFlowRadPerSec = wo.getDouble("water_flow_rad_per_sec").toFloat(),
                causticsPhaseRadPerSec = wo.getDouble("caustics_phase_rad_per_sec").toFloat(),
                godRayShimmerHz = wo.getDouble("god_ray_shimmer_hz").toFloat(),
                godRayShimmerAmp = wo.getDouble("god_ray_shimmer_amp").toFloat(),
                glassRefractionShimmerAmp = wo.getDouble("glass_refraction_shimmer_amp").toFloat(),
                glassFresnelPulseAmp = wo.getDouble("glass_fresnel_pulse_amp").toFloat(),
                bloomResponseCoupling = wo.getDouble("bloom_response_coupling").toFloat(),
                bokehDriftUvAmp = wo.getDouble("bokeh_drift_uv_amp").toFloat(),
                bokehDriftHz = wo.getDouble("bokeh_drift_hz").toFloat(),
            )

            val co = root.getJSONObject("core")
            val axis = co.getJSONArray("rotation_axis")
            val core = CoreProfile(
                provenance = co.getString("provenance"),
                coreVolumeSliceCount = co.getInt("core_volume_slice_count"),
                coreVolumeSliceSemantics = co.getString("core_volume_slice_semantics"),
                rotationAxisX = axis.getDouble(0).toFloat(),
                rotationAxisY = axis.getDouble(1).toFloat(),
                rotationAxisZ = axis.getDouble(2).toFloat(),
                rotationDegPerSec = co.getDouble("rotation_deg_per_sec").toFloat(),
                fluidRadPerSec = co.getDouble("fluid_rad_per_sec").toFloat(),
                densityPulseAmp = co.getDouble("density_pulse_amp").toFloat(),
                densityPulseHz = co.getDouble("density_pulse_hz").toFloat(),
                colorShiftAmp = co.getDouble("color_shift_amp").toFloat(),
                colorShiftHz = co.getDouble("color_shift_hz").toFloat(),
                causticsRadPerSec = co.getDouble("caustics_rad_per_sec").toFloat(),
                glassOpticalCoupling = co.getDouble("glass_optical_coupling").toFloat(),
                particlesRadPerSec = co.getDouble("particles_rad_per_sec").toFloat(),
                filamentStatus = co.getString("filament_status"),
                filamentRadPerSec = co.getDouble("filament_rad_per_sec").toFloat(),
            )

            return WaterWorldMotionProfile(
                schemaVersion = root.getString("schema_version"),
                motionParameterProvenance = root.getString("motion_parameter_provenance"),
                waterWorldMotionValuesValidated = root.getBoolean("water_world_motion_values_validated"),
                coreVolumeSliceCount = root.getInt("core_volume_slice_count"),
                coreVolumeSliceSemantics = root.getString("core_volume_slice_semantics"),
                canvasSizePx = root.getDouble("canvas_size_px").toFloat(),
                sphereCenterX = center.getDouble(0).toFloat(),
                sphereCenterY = center.getDouble(1).toFloat(),
                sphereOuterRadiusPx = sphere.getDouble("outer_radius_px").toFloat(),
                sphereInnerRadiusPx = sphere.getDouble("inner_radius_px").toFloat(),
                autoPlayOnEnter = playback.getBoolean("auto_play_on_enter"),
                defaultSpeedScale = playback.getDouble("default_speed_scale").toFloat(),
                maxFrameDeltaSec = playback.getDouble("max_frame_delta_sec"),
                deterministicSeed = playback.getLong("deterministic_seed"),
                fish = fish,
                residents = residents,
                plants = plants,
                rocks = rocks,
                bubbles = bubbles,
                waterAndOptics = waterOptics,
                core = core,
            )
        }

        private fun jsonStringList(arr: JSONArray): List<String> =
            (0 until arr.length()).map { arr.getString(it) }

        fun defaultProfile(): WaterWorldMotionProfile = WaterWorldMotionProfile(
            schemaVersion = "WATER_WORLD_V21_WORK3_MOTION_PROFILE_V1",
            motionParameterProvenance = PROVENANCE_WORK3_HYPOTHESIS,
            waterWorldMotionValuesValidated = false,
            coreVolumeSliceCount = WaterWorldMotionContract.CORE_VOLUME_SLICE_COUNT,
            coreVolumeSliceSemantics = WaterWorldMotionContract.CORE_VOLUME_SLICE_SEMANTICS,
            canvasSizePx = 1254f,
            sphereCenterX = 626.47575f,
            sphereCenterY = 596.34413f,
            sphereOuterRadiusPx = 422.29866f,
            sphereInnerRadiusPx = 395.75f,
            autoPlayOnEnter = true,
            defaultSpeedScale = 1.0f,
            maxFrameDeltaSec = 0.10,
            deterministicSeed = WaterWorldMotionTimeline.DEFAULT_DETERMINISTIC_SEED,
            fish = listOf(
                FishProfile(
                    id = "WW_FISH_01", tailId = "WW_FISH_01_TAIL", finsId = "WW_FISH_01_FINS", drawId = "WW_FISH_01",
                    provenance = PROVENANCE_WORK3_HYPOTHESIS, t0CenterX = 775.4f, t0CenterY = 497.9f,
                    widthPx = 206.1f, heightPx = 109.1f, pathPeriodSec = 26.0f, phaseOffsetRad = 0.0f,
                    verticalBobPx = 5.5f, verticalBobHz = 0.28f, tailFreqHz = 1.18f, tailAmpDeg = 5.6f,
                    finFreqHz = 0.84f, finAmpDeg = 4.2f, maxPitchDeg = 11.5f, minDepthScale = 0.91f, maxDepthScale = 1.04f,
                    waypointsOffsetPx = listOf(
                        Vec3Offset(0f, 0f, 0f), Vec3Offset(44f, -24f, 0.22f), Vec3Offset(18f, -48f, -0.35f),
                        Vec3Offset(-78f, -28f, -0.52f), Vec3Offset(-132f, 12f, -0.18f), Vec3Offset(-64f, 26f, 0.26f),
                    ),
                ),
                FishProfile(
                    id = "WW_FISH_02", tailId = "WW_FISH_02_TAIL", finsId = "WW_FISH_02_FINS", drawId = "WW_FISH_02",
                    provenance = PROVENANCE_WORK3_HYPOTHESIS, t0CenterX = 569.2f, t0CenterY = 551.4f,
                    widthPx = 164.9f, heightPx = 83.8f, pathPeriodSec = 21.5f, phaseOffsetRad = 1.15f,
                    verticalBobPx = 6.2f, verticalBobHz = 0.34f, tailFreqHz = 1.42f, tailAmpDeg = 6.4f,
                    finFreqHz = 1.05f, finAmpDeg = 4.8f, maxPitchDeg = 12.5f, minDepthScale = 0.90f, maxDepthScale = 1.05f,
                    waypointsOffsetPx = listOf(
                        Vec3Offset(0f, 0f, 0f), Vec3Offset(78f, -22f, 0.25f), Vec3Offset(124f, 14f, -0.15f),
                        Vec3Offset(62f, 42f, -0.45f), Vec3Offset(-42f, 28f, -0.25f), Vec3Offset(-58f, -10f, 0.18f),
                    ),
                ),
                FishProfile(
                    id = "WW_FISH_03", tailId = "WW_FISH_03_TAIL", finsId = "WW_FISH_03_FINS", drawId = "WW_FISH_03",
                    provenance = PROVENANCE_WORK3_HYPOTHESIS, t0CenterX = 679.9f, t0CenterY = 587.2f,
                    widthPx = 150.8f, heightPx = 79.8f, pathPeriodSec = 29.5f, phaseOffsetRad = 2.38f,
                    verticalBobPx = 4.8f, verticalBobHz = 0.25f, tailFreqHz = 1.29f, tailAmpDeg = 6.0f,
                    finFreqHz = 0.92f, finAmpDeg = 4.5f, maxPitchDeg = 10.5f, minDepthScale = 0.89f, maxDepthScale = 1.04f,
                    waypointsOffsetPx = listOf(
                        Vec3Offset(0f, 0f, 0f), Vec3Offset(64f, 18f, 0.24f), Vec3Offset(86f, -24f, -0.22f),
                        Vec3Offset(-12f, -46f, -0.48f), Vec3Offset(-94f, -14f, -0.20f), Vec3Offset(-48f, 22f, 0.20f),
                    ),
                ),
                FishProfile(
                    id = "WW_FISH_04", tailId = "WW_FISH_04_TAIL", finsId = "WW_FISH_04_FINS", drawId = "WW_FISH_04",
                    provenance = PROVENANCE_WORK3_HYPOTHESIS, t0CenterX = 599.1f, t0CenterY = 657.5f,
                    widthPx = 139.5f, heightPx = 74.7f, pathPeriodSec = 24.0f, phaseOffsetRad = 3.72f,
                    verticalBobPx = 4.2f, verticalBobHz = 0.31f, tailFreqHz = 1.56f, tailAmpDeg = 7.1f,
                    finFreqHz = 1.18f, finAmpDeg = 5.1f, maxPitchDeg = 11.0f, minDepthScale = 0.91f, maxDepthScale = 1.04f,
                    waypointsOffsetPx = listOf(
                        Vec3Offset(0f, 0f, 0f), Vec3Offset(68f, -16f, 0.20f), Vec3Offset(102f, -36f, -0.24f),
                        Vec3Offset(24f, -52f, -0.44f), Vec3Offset(-64f, -24f, -0.18f), Vec3Offset(-44f, 8f, 0.16f),
                    ),
                ),
            ),
            residents = listOf(
                ResidentProfile(
                    id = "WW_RESIDENT_G01", groupKey = "G01", flowId = "WW_RESIDENT_G01_FLOW", pulseId = "WW_RESIDENT_G01_PULSE",
                    provenance = WaterWorldResidentContractV1.PROVENANCE_V1_FROZEN, occlusionStatus = "GROUND_TRUTH_UNKNOWN_RENDER_HYPOTHESIS_WORK2",
                    drawIds = listOf("G01_back_arch", "G01_back_outer_veil", "G01_front_lower_sweep"),
                    cyclePeriodSec = 16.0f, flowDirection = 1f, phaseOffsetRad = 0.00f,
                    widthPulseAmp = 0.060f, widthPulseHz = 0.19f, brightnessPulseAmp = 0.090f, brightnessPulseHz = 0.19f, lateralWavePx = 8.0f,
                ),
                ResidentProfile(
                    id = "WW_RESIDENT_G02", groupKey = "G02", flowId = "WW_RESIDENT_G02_FLOW", pulseId = "WW_RESIDENT_G02_PULSE",
                    provenance = WaterWorldResidentContractV1.PROVENANCE_V1_FROZEN, occlusionStatus = "GROUND_TRUTH_UNKNOWN_RENDER_HYPOTHESIS_WORK2",
                    drawIds = listOf("G02_back_upper_arc", "G02_back_upper_hairline", "G02_back_right_rim", "G02_front_right_embrace"),
                    cyclePeriodSec = 18.0f, flowDirection = -1f, phaseOffsetRad = 1.24f,
                    widthPulseAmp = 0.050f, widthPulseHz = 0.18f, brightnessPulseAmp = 0.080f, brightnessPulseHz = 0.16f, lateralWavePx = 7.0f,
                ),
                ResidentProfile(
                    id = "WW_RESIDENT_A01", groupKey = "A01", flowId = "WW_RESIDENT_A01_FLOW", pulseId = "WW_RESIDENT_A01_PULSE",
                    provenance = WaterWorldResidentContractV1.PROVENANCE_V1_FROZEN, occlusionStatus = "GROUND_TRUTH_UNKNOWN_RENDER_HYPOTHESIS_WORK2",
                    drawIds = listOf("A01_back_upper_left", "A01_front_left_embrace"),
                    cyclePeriodSec = 20.0f, flowDirection = 1f, phaseOffsetRad = 2.41f,
                    widthPulseAmp = 0.070f, widthPulseHz = 0.21f, brightnessPulseAmp = 0.100f, brightnessPulseHz = 0.22f, lateralWavePx = 9.0f,
                ),
                ResidentProfile(
                    id = "WW_RESIDENT_A02", groupKey = "A02", flowId = "WW_RESIDENT_A02_FLOW", pulseId = "WW_RESIDENT_A02_PULSE",
                    provenance = WaterWorldResidentContractV1.PROVENANCE_V1_FROZEN, occlusionStatus = "GROUND_TRUTH_UNKNOWN_RENDER_HYPOTHESIS_WORK2",
                    drawIds = listOf("A02_back_lower_halo", "A02_front_lower_strand"),
                    cyclePeriodSec = 14.0f, flowDirection = -1f, phaseOffsetRad = 3.85f,
                    widthPulseAmp = 0.060f, widthPulseHz = 0.25f, brightnessPulseAmp = 0.090f, brightnessPulseHz = 0.21f, lateralWavePx = 8.0f,
                ),
                ResidentProfile(
                    id = "WW_RESIDENT_C01", groupKey = "C01", flowId = "WW_RESIDENT_C01_FLOW", pulseId = "WW_RESIDENT_C01_PULSE",
                    provenance = WaterWorldResidentContractV1.PROVENANCE_V1_FROZEN, occlusionStatus = "GROUND_TRUTH_UNKNOWN_RENDER_HYPOTHESIS_WORK2",
                    drawIds = listOf("C01_back_lower_thread", "C01_front_lower_right"),
                    cyclePeriodSec = 22.0f, flowDirection = 1f, phaseOffsetRad = 5.02f,
                    widthPulseAmp = 0.045f, widthPulseHz = 0.17f, brightnessPulseAmp = 0.070f, brightnessPulseHz = 0.15f, lateralWavePx = 6.5f,
                ),
            ),
            plants = listOf(
                PlantProfile(
                    id = "WW_PLANT_LEFT", leavesId = "WW_PLANT_LEFT_LEAVES",
                    drawIds = listOf("plant_left", "far_plant_left_bg"),
                    provenance = PROVENANCE_WORK3_HYPOTHESIS, swayFreqHz = 0.115f, leafFlutterHz = 0.27f,
                    maxSwayHeightRatio = 0.018f, phaseOffsetRad = 0.0f, waterFlowCoupling = 0.42f,
                ),
                PlantProfile(
                    id = "WW_PLANT_RIGHT", leavesId = "WW_PLANT_RIGHT_LEAVES",
                    drawIds = listOf("plant_right", "plant_mid_sprout", "far_plant_mid_bg", "far_plant_right_bg"),
                    provenance = PROVENANCE_WORK3_HYPOTHESIS, swayFreqHz = 0.138f, leafFlutterHz = 0.31f,
                    maxSwayHeightRatio = 0.017f, phaseOffsetRad = 1.92f, waterFlowCoupling = 0.38f,
                ),
            ),
            rocks = listOf(
                RockProfile("WW_ROCK_01", listOf("rock_01", "far_rock_left_bg"), true, 1.0f, PROVENANCE_WORK3_HYPOTHESIS),
                RockProfile("WW_ROCK_02", listOf("rock_02", "far_rock_right_bg"), true, 0.92f, PROVENANCE_WORK3_HYPOTHESIS),
                RockProfile("WW_ROCK_03", listOf("rock_03"), true, 0.85f, PROVENANCE_WORK3_HYPOTHESIS),
            ),
            bubbles = listOf(
                BubbleProfile("WW_BUBBLE_B01", "B01", 10.2f, 145f, 8.4f, 0.32f, 0.18f, 0.06f, 3101L),
                BubbleProfile("WW_BUBBLE_B02", "B02", 12.6f, 160f, 9.8f, 0.27f, 1.05f, 0.05f, 3102L),
                BubbleProfile("WW_BUBBLE_B03", "B03", 14.4f, 185f, 11.2f, 0.23f, 1.88f, 0.07f, 3103L),
                BubbleProfile("WW_BUBBLE_B04", "B04", 9.4f, 150f, 7.6f, 0.36f, 2.71f, 0.05f, 3104L),
                BubbleProfile("WW_BUBBLE_B05", "B05", 8.6f, 135f, 6.8f, 0.41f, 3.44f, 0.06f, 3105L),
                BubbleProfile("WW_BUBBLE_B06", "B06", 11.8f, 170f, 10.4f, 0.29f, 4.19f, 0.06f, 3106L),
                BubbleProfile("WW_BUBBLE_B07", "B07", 15.2f, 190f, 11.8f, 0.22f, 4.92f, 0.07f, 3107L),
                BubbleProfile("WW_BUBBLE_B08", "B08", 13.1f, 175f, 9.2f, 0.26f, 5.56f, 0.05f, 3108L),
                BubbleProfile("WW_BUBBLE_B09", "B09", 15.8f, 195f, 12.0f, 0.21f, 0.74f, 0.08f, 3109L),
            ),
            waterAndOptics = WaterOpticsProfile(
                provenance = PROVENANCE_WORK3_HYPOTHESIS,
                waterNormalUvVelX = 0.0046f,
                waterNormalUvVelY = -0.0028f,
                waterMeniscusWaveRadPerSec = 0.62f,
                waterMeniscusSecondaryRadPerSec = 0.94f,
                waterFlowRadPerSec = 0.24f,
                causticsPhaseRadPerSec = 0.145f,
                godRayShimmerHz = 0.18f,
                godRayShimmerAmp = 0.065f,
                glassRefractionShimmerAmp = 0.032f,
                glassFresnelPulseAmp = 0.035f,
                bloomResponseCoupling = 0.04f,
                bokehDriftUvAmp = 0.0045f,
                bokehDriftHz = 0.09f,
            ),
            core = CoreProfile(
                provenance = PROVENANCE_WORK3_HYPOTHESIS,
                coreVolumeSliceCount = 32,
                coreVolumeSliceSemantics = WaterWorldMotionContract.CORE_VOLUME_SLICE_SEMANTICS,
                rotationAxisX = 0.14f,
                rotationAxisY = 0.98f,
                rotationAxisZ = -0.14f,
                rotationDegPerSec = 3.6f,
                fluidRadPerSec = 0.24f,
                densityPulseAmp = 0.045f,
                densityPulseHz = 0.14f,
                colorShiftAmp = 0.04f,
                colorShiftHz = 0.11f,
                causticsRadPerSec = 0.16f,
                glassOpticalCoupling = 0.035f,
                particlesRadPerSec = 0.21f,
                filamentStatus = "ASSET_GAP_PROCEDURAL_RUNTIME_HYPOTHESIS",
                filamentRadPerSec = 0.19f,
            ),
        )
    }
}
