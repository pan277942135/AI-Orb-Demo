package com.example.waterworld

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, stateless, deterministic motion evaluator for all 47 canonical elements and 28 dependencies.
 *
 * Zero OpenGL dependencies — executes identically in Android runtime, JVM Robolectric tests,
 * and software dynamic verification.
 */
object WaterWorldMotionEvaluator {

    private const val TWO_PI = 2.0 * PI
    private const val FISH_LUT_SAMPLES = 96

    /**
     * Exact inner bubble anchors matching `draw_bindings.json` (`IB01..IB14`) so inner water
     * bubbles rise smoothly within the sphere water volume below the meniscus and above rocks.
     */
    private val INNER_BUBBLE_SPECS: List<Triple<String, Float, Float>> = listOf(
        Triple("IB01", 8.8f, 118f),
        Triple("IB02", 9.7f, 130f),
        Triple("IB03", 10.6f, 142f),
        Triple("IB04", 11.4f, 152f),
        Triple("IB05", 12.1f, 165f),
        Triple("IB06", 13.0f, 178f),
        Triple("IB07", 13.9f, 196f),
        Triple("IB08", 14.6f, 205f),
        Triple("IB09", 11.8f, 188f),
        Triple("IB10", 12.7f, 198f),
        Triple("IB11", 10.3f, 140f),
        Triple("IB12", 11.5f, 158f),
        Triple("IB13", 9.1f, 92f),
        Triple("IB14", 9.9f, 108f),
    )

    fun evaluate(
        profile: WaterWorldMotionProfile,
        timeSeconds: Double,
        frameId: Long = 0L,
        randomSeed: Long = profile.deterministicSeed,
        staticReferenceMode: Boolean = false,
    ): WaterWorldDynamicFrameSnapshot {
        val t = if (staticReferenceMode || !timeSeconds.isFinite() || timeSeconds <= 0.0) 0.0 else timeSeconds
        val isZero = staticReferenceMode || t == 0.0
        val ramp = if (isZero) 0f else smoothstep(0f, 0.35f, t.toFloat())

        // 1. Core state (32 spatial Z-slices contract preserved)
        val coreState = WaterWorldCoreMotionAdapter.evaluate(profile.core, t, isZero)

        // 2. Water, caustics, lighting, glass & bokeh optics
        val wo = profile.waterAndOptics
        val waterOptics = if (isZero) {
            WaterWorldDynamicFrameSnapshot.WaterOpticsMotionState(
                waterNormalOffsetU = 0f,
                waterNormalOffsetV = 0f,
                meniscusWavePhaseRad = 0f,
                meniscusSecondaryPhaseRad = 0f,
                waterFlowPhaseRad = 0f,
                causticsPhaseRad = 0f,
                lightingShimmerScale = 1f,
                glassRefractionScale = 1f,
                glassFresnelScale = 1f,
                glassOpticalPhaseRad = 0f,
                bloomResponseScale = 1f,
                bokehDriftOffsetU = 0f,
                bokehDriftOffsetV = 0f,
                bokehPhaseRad = 0f,
            )
        } else {
            val flowPhase = (t * wo.waterFlowRadPerSec.toDouble()).toFloat()
            val cauPhase = (t * wo.causticsPhaseRadPerSec.toDouble()).toFloat()
            val lightShimmer = 1f + wo.godRayShimmerAmp * sin(TWO_PI * wo.godRayShimmerHz * t).toFloat()
            val glassPhase = flowPhase * 0.72f + coreState.fluidPhaseRad * 0.28f
            val glassRefract = 1f + wo.glassRefractionShimmerAmp * sin(glassPhase) + coreState.glassOpticalResponse * 0.5f
            val glassFresnel = 1f + wo.glassFresnelPulseAmp * sin(glassPhase * 1.18f) + coreState.glassOpticalResponse
            val bloomScale = 1f + wo.bloomResponseCoupling * (lightShimmer - 1f) * 3.0f
            val bokehAngle = (TWO_PI * wo.bokehDriftHz * t).toFloat()
            WaterWorldDynamicFrameSnapshot.WaterOpticsMotionState(
                waterNormalOffsetU = (t * wo.waterNormalUvVelX.toDouble()).toFloat(),
                waterNormalOffsetV = (t * wo.waterNormalUvVelY.toDouble()).toFloat(),
                meniscusWavePhaseRad = (t * wo.waterMeniscusWaveRadPerSec.toDouble()).toFloat(),
                meniscusSecondaryPhaseRad = (t * wo.waterMeniscusSecondaryRadPerSec.toDouble()).toFloat(),
                waterFlowPhaseRad = flowPhase,
                causticsPhaseRad = cauPhase,
                lightingShimmerScale = lightShimmer,
                glassRefractionScale = glassRefract,
                glassFresnelScale = glassFresnel,
                glassOpticalPhaseRad = glassPhase,
                bloomResponseScale = bloomScale,
                bokehDriftOffsetU = wo.bokehDriftUvAmp * sin(bokehAngle),
                bokehDriftOffsetV = wo.bokehDriftUvAmp * (1f - cos(bokehAngle * 0.82f)) * 0.5f,
                bokehPhaseRad = bokehAngle,
            )
        }

        // 3. Four independent fish on closed 3D Catmull-Rom paths + tail/fin deformation
        val fishMap = linkedMapOf<String, WaterWorldDynamicFrameSnapshot.FishMotionState>()
        for (fp in profile.fish) {
            fishMap[fp.id] = evaluateFish(fp, profile, t, isZero, ramp)
        }

        // 4. Five independent residents (all segments of each resident share unified state)
        val residentMap = linkedMapOf<String, WaterWorldDynamicFrameSnapshot.ResidentMotionState>()
        for (rp in profile.residents) {
            residentMap[rp.groupKey] = evaluateResident(rp, t, isZero, ramp)
        }

        // 5. Two aquatic plants (root-pinned sway coupled to water flow)
        val plantMap = linkedMapOf<String, WaterWorldDynamicFrameSnapshot.PlantMotionState>()
        for (pp in profile.plants) {
            plantMap[pp.id] = evaluatePlant(pp, waterOptics.waterFlowPhaseRad, t, isZero, ramp)
        }

        // 6. Three physically stationary rocks (dynamic surface caustics only)
        val rockMap = linkedMapOf<String, WaterWorldDynamicFrameSnapshot.RockMotionState>()
        for (rk in profile.rocks) {
            rockMap[rk.id] = WaterWorldDynamicFrameSnapshot.RockMotionState(
                id = rk.id,
                drawIds = rk.drawIds,
                offsetX = 0f,
                offsetY = 0f,
                rotationRad = 0f,
                physicallyStationary = true,
                causticsPhaseRad = waterOptics.causticsPhaseRad,
                causticsGain = rk.causticsResponseGain,
            )
        }

        // 7. Nine outer bubbles + 14 inner bubbles (continuous rise, drift, fade-out & re-entry)
        val outerBubbles = linkedMapOf<String, WaterWorldDynamicFrameSnapshot.BubbleMotionState>()
        for (bp in profile.bubbles) {
            outerBubbles[bp.drawId] = evaluateBubble(
                id = bp.id,
                drawId = bp.drawId,
                risePeriodSec = bp.risePeriodSec,
                riseSpanPx = bp.riseSpanPx,
                driftAmpPx = bp.driftAmpPx,
                driftHz = bp.driftHz,
                phaseRad = bp.phaseRad,
                scalePulseAmp = bp.scalePulseAmp,
                t = t,
                isZero = isZero,
            )
        }
        val innerBubbles = linkedMapOf<String, WaterWorldDynamicFrameSnapshot.BubbleMotionState>()
        INNER_BUBBLE_SPECS.forEachIndexed { idx, (drawId, period, span) ->
            innerBubbles[drawId] = evaluateBubble(
                id = "WW_INNER_BUBBLE_$drawId",
                drawId = drawId,
                risePeriodSec = period,
                riseSpanPx = span,
                driftAmpPx = 4.2f + (idx % 4) * 1.1f,
                driftHz = 0.28f + (idx % 5) * 0.04f,
                phaseRad = idx * 0.83f,
                scalePulseAmp = 0.05f,
                t = t,
                isZero = isZero,
            )
        }

        // 8. Map all 47 canonical elements explicitly
        val elements = buildCanonical47Elements(
            profile = profile,
            fishMap = fishMap,
            residentMap = residentMap,
            plantMap = plantMap,
            rockMap = rockMap,
            outerBubbles = outerBubbles,
            waterOptics = waterOptics,
            coreState = coreState,
            isZero = isZero,
        )

        // 9. Execute all 28 canonical dependency edges
        val dependencies = WaterWorldMotionContract.CANONICAL_28_DEPENDENCIES.mapIndexed { idx, dep ->
            val src = elements[dep.source]
            val dst = elements[dep.target]
            val signal = ((src?.phaseRad ?: 0f) * 0.5f + (dst?.modulationScale ?: 1f) * 0.5f)
            WaterWorldDynamicFrameSnapshot.DependencyExecutionState(
                index = idx,
                sourceId = dep.source,
                targetId = dep.target,
                relation = dep.dependencyType,
                evaluatedAtTimeSec = t,
                signalValue = signal,
            )
        }

        return WaterWorldDynamicFrameSnapshot(
            frameId = frameId,
            globalTimeSeconds = t,
            staticReferenceMode = isZero,
            randomSeed = randomSeed,
            provenance = profile.motionParameterProvenance,
            fishStates = fishMap,
            residentStates = residentMap,
            plantStates = plantMap,
            rockStates = rockMap,
            outerBubbleStates = outerBubbles,
            innerBubbleStates = innerBubbles,
            waterOptics = waterOptics,
            coreState = coreState,
            elementStates = elements,
            dependencyStates = dependencies,
        )
    }

    private fun evaluateFish(
        fp: WaterWorldMotionProfile.FishProfile,
        profile: WaterWorldMotionProfile,
        t: Double,
        isZero: Boolean,
        ramp: Float,
    ): WaterWorldDynamicFrameSnapshot.FishMotionState {
        if (isZero) {
            return WaterWorldDynamicFrameSnapshot.FishMotionState(
                id = fp.id,
                drawId = fp.drawId,
                pivotX = fp.t0CenterX,
                pivotY = fp.t0CenterY,
                offsetX = 0f,
                offsetY = 0f,
                offsetZ = 0f,
                pitchRad = 0f,
                facingScaleX = 1f,
                depthScale = 1f,
                tailPhaseRad = 0f,
                tailAmpPx = 0f,
                finPhaseRad = 0f,
                finAmpPx = 0f,
                pathProgress = 0f,
            )
        }

        val u = ((t / fp.pathPeriodSec.toDouble()) % 1.0).let { if (it < 0.0) it + 1.0 else it }.toFloat()
        val sample = sampleClosedCatmullRomArcLength(fp.waypointsOffsetPx, u)
        val eps = 0.004f
        val nextSample = sampleClosedCatmullRomArcLength(fp.waypointsOffsetPx, (u + eps) % 1f)

        val bobY = fp.verticalBobPx * sin(TWO_PI * fp.verticalBobHz * t).toFloat() * ramp
        var rawDx = sample.dx
        var rawDy = sample.dy + bobY

        // Enforce strict crystal sphere interior + rock clearance + water surface clearance
        var absX = fp.t0CenterX + rawDx
        var absY = fp.t0CenterY + rawDy
        val minWaterY = 390f + fp.heightPx * 0.42f
        val maxRockSafeY = 710f - fp.heightPx * 0.35f
        absY = absY.coerceIn(minWaterY, maxRockSafeY)

        val maxSphereR = (profile.sphereInnerRadiusPx - fp.widthPx * 0.46f - 18f).coerceAtLeast(120f)
        val relX = absX - profile.sphereCenterX
        val relY = absY - profile.sphereCenterY
        val dist = hypot(relX.toDouble(), relY.toDouble()).toFloat()
        if (dist > maxSphereR && dist > 1e-4f) {
            val scaleBack = maxSphereR / dist
            absX = profile.sphereCenterX + relX * scaleBack
            absY = profile.sphereCenterY + relY * scaleBack
        }
        rawDx = absX - fp.t0CenterX
        rawDy = absY - fp.t0CenterY

        // Smooth velocity tangent for natural pitch & 3D perspective turn (never snaps or flips upside-down)
        val vx = (nextSample.dx - sample.dx) / eps
        val vy = (nextSample.dy - sample.dy) / eps
        val speed = hypot(vx.toDouble(), vy.toDouble()).toFloat().coerceAtLeast(1e-3f)
        val dirX = (vx / speed).coerceIn(-1f, 1f)

        // Smooth depth-driven perspective scale + continuous horizontal turn across back arc
        val depthT = ((sample.dz + 0.55f) / 0.85f).coerceIn(0f, 1f)
        val depthScale = mix(fp.minDepthScale, fp.maxDepthScale, depthT)
        // Smooth perspective foreshortening when turning; strictly >= 0.42 in magnitude so fish never collapses to a thin line
        val targetFacing = if (dirX >= 0f) {
            0.48f + 0.52f * dirX
        } else {
            -(0.48f + 0.52f * (-dirX))
        }
        // Blend smoothly from +1.0 at T=0 so T=0->T>0 has zero snap
        val facingScaleX = mix(1f, targetFacing, ramp)

        val maxPitchRad = Math.toRadians(fp.maxPitchDeg.toDouble()).toFloat()
        val rawPitch = atan2(vy, kotlin.math.abs(vx).coerceAtLeast(25f))
        val pitchRad = (rawPitch.coerceIn(-maxPitchRad, maxPitchRad)) * (if (facingScaleX >= 0f) 1f else -1f) * ramp

        // Tail & fin swimming deformation driven by independent frequencies (sinusoidal head-to-tail wave)
        val tailPhase = (TWO_PI * fp.tailFreqHz * t + fp.phaseOffsetRad).toFloat()
        val finPhase = (TWO_PI * fp.finFreqHz * t + fp.phaseOffsetRad * 1.37f).toFloat()
        val tailAmpPx = (fp.heightPx * 0.145f * (fp.tailAmpDeg / 6.0f)) * ramp
        val finAmpPx = (fp.heightPx * 0.068f * (fp.finAmpDeg / 4.5f)) * ramp

        return WaterWorldDynamicFrameSnapshot.FishMotionState(
            id = fp.id,
            drawId = fp.drawId,
            pivotX = fp.t0CenterX,
            pivotY = fp.t0CenterY,
            offsetX = rawDx,
            offsetY = rawDy,
            offsetZ = sample.dz,
            pitchRad = pitchRad,
            facingScaleX = facingScaleX,
            depthScale = mix(1f, depthScale, ramp),
            tailPhaseRad = tailPhase,
            tailAmpPx = tailAmpPx,
            finPhaseRad = finPhase,
            finAmpPx = finAmpPx,
            pathProgress = u,
        )
    }

    private fun evaluateResident(
        rp: WaterWorldMotionProfile.ResidentProfile,
        t: Double,
        isZero: Boolean,
        ramp: Float,
    ): WaterWorldDynamicFrameSnapshot.ResidentMotionState {
        val flowSpeedPerSec = rp.flowDirection / rp.cyclePeriodSec
        val waveFreqHz = 3.0f / rp.cyclePeriodSec
        val normAxis = WaterWorldResidentOrbitalMath.normalizeAxis(rp.orbitalAxisX, rp.orbitalAxisY, rp.orbitalAxisZ)
        val omegaRadPerSecDouble = WaterWorldResidentOrbitalMath.degreesToRadians(rp.orbitalAngularVelocityDegPerSec.toDouble())
        val omegaRadPerSec = omegaRadPerSecDouble.toFloat()
        if (isZero) {
            return WaterWorldDynamicFrameSnapshot.ResidentMotionState(
                id = rp.id,
                groupKey = rp.groupKey,
                drawIds = rp.drawIds,
                flowPhaseRad = 0f,
                flowOffsetU = 0f,
                widthPulseScale = 1f,
                brightnessScale = 1f,
                lateralWavePx = 0f,
                wavePhaseRad = 0f,
                occlusionStatus = rp.occlusionStatus,
                flowSpeedPerSec = flowSpeedPerSec,
                flowDirection = rp.flowDirection,
                flowActive = 0f,
                widthAmplitude = 0f,
                widthTimePhaseRad = 0f,
                brightnessAmplitude = 0f,
                brightnessTimePhaseRad = 0f,
                waveFrequencyHz = waveFreqHz,
                phaseOffsetRad = rp.phaseOffsetRad,
                orbitalEnabled = false,
                orbitalAngleRad = 0f,
                orbitalAngularVelocityRadPerSec = omegaRadPerSec,
                orbitalAxisX = normAxis[0],
                orbitalAxisY = normAxis[1],
                orbitalAxisZ = normAxis[2],
                orbitalRotationMat3 = WaterWorldResidentOrbitalMath.identityMat3(),
                orbitalProvenance = rp.orbitalProvenance,
            )
        }
        val cycle = (t / rp.cyclePeriodSec.toDouble()).toFloat()
        val flowPhase = (cycle * TWO_PI.toFloat()) * rp.flowDirection
        val flowOffsetU = cycle * rp.flowDirection
        val widthTimePhase = (TWO_PI * rp.widthPulseHz * t + rp.phaseOffsetRad).toFloat()
        val brightnessTimePhase = (TWO_PI * rp.brightnessPulseHz * t + rp.phaseOffsetRad * 0.7).toFloat()
        val widthAmpActive = rp.widthPulseAmp * ramp
        val brightnessAmpActive = rp.brightnessPulseAmp * ramp
        val widthPulse = 1f + widthAmpActive * sin(widthTimePhase.toDouble()).toFloat()
        val brightness = 1f + brightnessAmpActive * sin(brightnessTimePhase.toDouble()).toFloat()
        val wavePhase = (TWO_PI * waveFreqHz.toDouble() * t + rp.phaseOffsetRad).toFloat()

        // Work 3.2 unified timeline rigid orbital angle: theta(t) = omega * globalTimeSeconds (zero per-frame accumulation)
        val orbitalActive = rp.orbitalEnabled
        val orbitalAngleRad = if (orbitalActive) (omegaRadPerSecDouble * t).toFloat() else 0f
        val orbitalMat3 = if (orbitalActive) {
            WaterWorldResidentOrbitalMath.buildRodriguesMat3(normAxis, orbitalAngleRad)
        } else {
            WaterWorldResidentOrbitalMath.identityMat3()
        }

        return WaterWorldDynamicFrameSnapshot.ResidentMotionState(
            id = rp.id,
            groupKey = rp.groupKey,
            drawIds = rp.drawIds,
            flowPhaseRad = flowPhase,
            flowOffsetU = flowOffsetU,
            widthPulseScale = widthPulse,
            brightnessScale = brightness,
            lateralWavePx = rp.lateralWavePx * ramp,
            wavePhaseRad = wavePhase,
            occlusionStatus = rp.occlusionStatus,
            flowSpeedPerSec = flowSpeedPerSec,
            flowDirection = rp.flowDirection,
            flowActive = ramp,
            widthAmplitude = widthAmpActive,
            widthTimePhaseRad = widthTimePhase,
            brightnessAmplitude = brightnessAmpActive,
            brightnessTimePhaseRad = brightnessTimePhase,
            waveFrequencyHz = waveFreqHz,
            phaseOffsetRad = rp.phaseOffsetRad,
            orbitalEnabled = orbitalActive,
            orbitalAngleRad = orbitalAngleRad,
            orbitalAngularVelocityRadPerSec = omegaRadPerSec,
            orbitalAxisX = normAxis[0],
            orbitalAxisY = normAxis[1],
            orbitalAxisZ = normAxis[2],
            orbitalRotationMat3 = orbitalMat3,
            orbitalProvenance = rp.orbitalProvenance,
        )
    }

    private fun evaluatePlant(
        pp: WaterWorldMotionProfile.PlantProfile,
        waterFlowPhaseRad: Float,
        t: Double,
        isZero: Boolean,
        ramp: Float,
    ): WaterWorldDynamicFrameSnapshot.PlantMotionState {
        if (isZero) {
            return WaterWorldDynamicFrameSnapshot.PlantMotionState(
                id = pp.id,
                drawIds = pp.drawIds,
                swayAmpPx = 0f,
                swayPhaseRad = 0f,
                leafFlutterAmpPx = 0f,
                leafFlutterPhaseRad = 0f,
                waterFlowCoupling = pp.waterFlowCoupling,
            )
        }
        // Plant height ~450px -> 1.8% height <= 8.1px max sway
        val swayPhase = (TWO_PI * pp.swayFreqHz * t + pp.phaseOffsetRad + waterFlowPhaseRad * pp.waterFlowCoupling).toFloat()
        val flutterPhase = (TWO_PI * pp.leafFlutterHz * t + pp.phaseOffsetRad * 1.4f).toFloat()
        val maxTipPx = (450f * min(pp.maxSwayHeightRatio, 0.02f)) * ramp
        return WaterWorldDynamicFrameSnapshot.PlantMotionState(
            id = pp.id,
            drawIds = pp.drawIds,
            swayAmpPx = maxTipPx,
            swayPhaseRad = swayPhase,
            leafFlutterAmpPx = maxTipPx * 0.34f,
            leafFlutterPhaseRad = flutterPhase,
            waterFlowCoupling = pp.waterFlowCoupling,
        )
    }

    /**
     * Continuous bubble lifecycle anchored at exact Work 2 T=0 position (`u = 0 -> dy=0, dx=0, alpha=1, scale=1`).
     * Over `u in [0.0, 0.62]`: rises smoothly upward to top and fades `1 -> 0` over `[0.46, 0.62]`.
     * At `u = 0.62` (strictly invisible, `alpha == 0`): wraps cleanly to bottom (`+0.38 * riseSpanPx`).
     * Over `u in [0.62, 1.0]`: fades smoothly back in (`0.62..0.76`) and rises cleanly back through T=0 anchor!
     */
    private fun evaluateBubble(
        id: String,
        drawId: String,
        risePeriodSec: Float,
        riseSpanPx: Float,
        driftAmpPx: Float,
        driftHz: Float,
        phaseRad: Float,
        scalePulseAmp: Float,
        t: Double,
        isZero: Boolean,
    ): WaterWorldDynamicFrameSnapshot.BubbleMotionState {
        if (isZero) {
            return WaterWorldDynamicFrameSnapshot.BubbleMotionState(
                id = id,
                drawId = drawId,
                offsetX = 0f,
                offsetY = 0f,
                scaleMul = 1f,
                alphaMul = 1f,
                cycleProgress = 0f,
            )
        }
        val u = ((t / risePeriodSec.toDouble()) - floor(t / risePeriodSec.toDouble())).toFloat()
        val wrapPoint = 0.62f
        val offsetY: Float
        val alphaMul: Float
        if (u < wrapPoint) {
            offsetY = -u * riseSpanPx
            alphaMul = if (u > 0.46f) {
                1f - smoothstep(0.46f, wrapPoint, u)
            } else {
                1f
            }
        } else {
            offsetY = (1f - u) * riseSpanPx
            alphaMul = if (u < 0.76f) {
                smoothstep(wrapPoint, 0.76f, u)
            } else {
                1f
            }
        }
        // Horizontal drift is strictly 0 at t=0 and bounded <= 1.5% D (~12.6px)
        val angle = (TWO_PI * driftHz * t).toFloat()
        val offsetX = driftAmpPx * (sin(angle + phaseRad) - sin(phaseRad)) * 0.5f
        val scaleMul = 1f + scalePulseAmp * sin(angle * 1.35f)

        return WaterWorldDynamicFrameSnapshot.BubbleMotionState(
            id = id,
            drawId = drawId,
            offsetX = offsetX,
            offsetY = offsetY,
            scaleMul = scaleMul,
            alphaMul = alphaMul.coerceIn(0f, 1f),
            cycleProgress = u,
        )
    }

    /**
     * Evaluates a closed smooth Catmull-Rom spline with uniform arc-length parameterization.
     * Guarantees exact waypoint[0] == (0,0,0) at `u == 0`.
     */
    internal fun sampleClosedCatmullRomArcLength(
        points: List<WaterWorldMotionProfile.Vec3Offset>,
        uNormalized: Float,
    ): WaterWorldMotionProfile.Vec3Offset {
        val n = points.size
        if (n == 0) return WaterWorldMotionProfile.Vec3Offset(0f, 0f, 0f)
        if (n == 1) return points[0]
        val u = ((uNormalized % 1f) + 1f) % 1f
        if (u == 0f) return points[0]

        // Build arc-length LUT (cheap: 96 samples, zero heap churn beyond tiny local arrays)
        val cumLen = FloatArray(FISH_LUT_SAMPLES + 1)
        var prev = evalCatmullRomRaw(points, 0f)
        for (i in 1..FISH_LUT_SAMPLES) {
            val s = i.toFloat() / FISH_LUT_SAMPLES.toFloat()
            val cur = evalCatmullRomRaw(points, s)
            val seg = sqrt(
                (cur.dx - prev.dx) * (cur.dx - prev.dx) +
                    (cur.dy - prev.dy) * (cur.dy - prev.dy)
            )
            cumLen[i] = cumLen[i - 1] + seg
            prev = cur
        }
        val totalLen = cumLen[FISH_LUT_SAMPLES]
        if (totalLen < 1e-5f) return points[0]
        val targetLen = u * totalLen

        // Binary search LUT for uniform arc-length parameter
        var lo = 0
        var hi = FISH_LUT_SAMPLES
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (cumLen[mid] <= targetLen) lo = mid else hi = mid
        }
        val segLen = (cumLen[hi] - cumLen[lo]).coerceAtLeast(1e-6f)
        val frac = ((targetLen - cumLen[lo]) / segLen).coerceIn(0f, 1f)
        val sMapped = (lo + frac) / FISH_LUT_SAMPLES.toFloat()
        return evalCatmullRomRaw(points, sMapped)
    }

    private fun evalCatmullRomRaw(
        pts: List<WaterWorldMotionProfile.Vec3Offset>,
        sNormalized: Float,
    ): WaterWorldMotionProfile.Vec3Offset {
        val n = pts.size
        val scaled = (((sNormalized % 1f) + 1f) % 1f) * n
        val i1 = floor(scaled.toDouble()).toInt() % n
        val t = scaled - floor(scaled.toDouble()).toFloat()
        val p0 = pts[(i1 - 1 + n) % n]
        val p1 = pts[i1]
        val p2 = pts[(i1 + 1) % n]
        val p3 = pts[(i1 + 2) % n]
        return WaterWorldMotionProfile.Vec3Offset(
            dx = catmullScalar(p0.dx, p1.dx, p2.dx, p3.dx, t),
            dy = catmullScalar(p0.dy, p1.dy, p2.dy, p3.dy, t),
            dz = catmullScalar(p0.dz, p1.dz, p2.dz, p3.dz, t),
        )
    }

    private fun catmullScalar(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (
            (2f * p1) +
                (-p0 + p2) * t +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                (-p0 + 3f * p1 - 3f * p2 + p3) * t3
            )
    }

    private fun buildCanonical47Elements(
        profile: WaterWorldMotionProfile,
        fishMap: Map<String, WaterWorldDynamicFrameSnapshot.FishMotionState>,
        residentMap: Map<String, WaterWorldDynamicFrameSnapshot.ResidentMotionState>,
        plantMap: Map<String, WaterWorldDynamicFrameSnapshot.PlantMotionState>,
        rockMap: Map<String, WaterWorldDynamicFrameSnapshot.RockMotionState>,
        outerBubbles: Map<String, WaterWorldDynamicFrameSnapshot.BubbleMotionState>,
        waterOptics: WaterWorldDynamicFrameSnapshot.WaterOpticsMotionState,
        coreState: WaterWorldDynamicFrameSnapshot.CoreMotionState,
        isZero: Boolean,
    ): Map<String, WaterWorldDynamicFrameSnapshot.ElementMotionState> {
        val prov = profile.motionParameterProvenance
        val map = linkedMapOf<String, WaterWorldDynamicFrameSnapshot.ElementMotionState>()
        fun put(id: String, active: Boolean, phase: Float, disp: Float, mod: Float, p: String = prov) {
            map[id] = WaterWorldDynamicFrameSnapshot.ElementMotionState(id, active, phase, disp, mod, p)
        }

        // 8 CORE elements
        put("CORE_VOLUME", !isZero, Math.toRadians(coreState.rotationAngleDeg.toDouble()).toFloat(), 0f, coreState.densityScale)
        put("CORE_FLUID", !isZero, coreState.fluidPhaseRad, 0f, coreState.densityScale)
        put("CORE_DENSITY", !isZero, coreState.fluidPhaseRad, 0f, coreState.densityScale)
        put("CORE_COLOR", !isZero, coreState.fluidPhaseRad, 0f, 1f + coreState.colorShift)
        put("CORE_CAUSTICS", !isZero, coreState.causticsPhaseRad, 0f, 1f)
        put("CORE_GLASS", !isZero, waterOptics.glassOpticalPhaseRad, 0f, 1f + coreState.glassOpticalResponse)
        put("CORE_PARTICLES", !isZero, coreState.particlesPhaseRad, 0f, 1f)
        put("CORE_FILAMENT", !isZero, coreState.filamentPhaseRad, 0f, 1f, coreState.filamentStatus)

        // 5 Water World global/glass/water/lighting/optical elements
        put("WW_GLASS_BACK", !isZero, waterOptics.glassOpticalPhaseRad, 0f, waterOptics.glassRefractionScale)
        put("WW_GLASS_FRONT", !isZero, waterOptics.glassOpticalPhaseRad, 0f, waterOptics.glassFresnelScale)
        put("WW_WATER_BODY", !isZero, waterOptics.waterFlowPhaseRad, 0f, 1f)
        put("WW_WATER_SURFACE", !isZero, waterOptics.meniscusWavePhaseRad, 0f, 1f)
        put("WW_WATER_FLOW", !isZero, waterOptics.waterFlowPhaseRad, hypot(waterOptics.waterNormalOffsetU.toDouble(), waterOptics.waterNormalOffsetV.toDouble()).toFloat(), 1f)

        // 12 Fish elements (4 bodies + 4 tails + 4 fins)
        for (fp in profile.fish) {
            val fs = fishMap.getValue(fp.id)
            val disp = hypot(fs.offsetX.toDouble(), fs.offsetY.toDouble()).toFloat()
            put(fp.id, !isZero, fs.pathProgress * TWO_PI.toFloat(), disp, fs.depthScale)
            put(fp.tailId, !isZero, fs.tailPhaseRad, fs.tailAmpPx, 1f)
            put(fp.finsId, !isZero, fs.finPhaseRad, fs.finAmpPx, 1f)
        }

        // 15 Resident elements (5 bases + 5 flow + 5 pulse)
        for (rp in profile.residents) {
            val rs = residentMap.getValue(rp.groupKey)
            put(rp.id, !isZero, rs.flowPhaseRad, rs.lateralWavePx, rs.widthPulseScale)
            put(rp.flowId, !isZero, rs.flowPhaseRad, rs.flowOffsetU, 1f)
            put(rp.pulseId, !isZero, rs.wavePhaseRad, 0f, rs.brightnessScale)
        }

        // 4 Plant elements (2 plants + 2 leaves)
        for (pp in profile.plants) {
            val ps = plantMap.getValue(pp.id)
            put(pp.id, !isZero, ps.swayPhaseRad, ps.swayAmpPx, 1f)
            put(pp.leavesId, !isZero, ps.leafFlutterPhaseRad, ps.leafFlutterAmpPx, 1f)
        }

        // 3 Rock elements (physically stationary; dynamic surface caustics)
        for (rk in profile.rocks) {
            val rs = rockMap.getValue(rk.id)
            put(rk.id, !isZero, rs.causticsPhaseRad, 0f, rs.causticsGain)
        }

        // 9 Outer bubbles
        for (bp in profile.bubbles) {
            val bs = outerBubbles.getValue(bp.drawId)
            val disp = hypot(bs.offsetX.toDouble(), bs.offsetY.toDouble()).toFloat()
            put(bp.id, !isZero, bs.cycleProgress * TWO_PI.toFloat(), disp, bs.alphaMul)
        }

        // Remaining canonical optical/lighting/background elements (fill any canonical IDs from contract)
        val remaining = mapOf(
            "WW_BACKGROUND" to Triple(waterOptics.bokehPhaseRad, 0f, 1f),
            "WW_BACKGROUND_BOKEH" to Triple(waterOptics.bokehPhaseRad, hypot(waterOptics.bokehDriftOffsetU.toDouble(), waterOptics.bokehDriftOffsetV.toDouble()).toFloat(), 1f),
            "WW_CAUSTICS" to Triple(waterOptics.causticsPhaseRad, 0f, waterOptics.lightingShimmerScale),
            "WW_LIGHTING" to Triple(waterOptics.causticsPhaseRad, 0f, waterOptics.lightingShimmerScale),
            "WW_GOD_RAYS" to Triple(waterOptics.causticsPhaseRad, 0f, waterOptics.lightingShimmerScale),
            "WW_BLOOM" to Triple(waterOptics.glassOpticalPhaseRad, 0f, waterOptics.bloomResponseScale),
            "WW_BOKEH" to Triple(waterOptics.bokehPhaseRad, 0f, 1f),
            "WW_GLASS_REFRACTION" to Triple(waterOptics.glassOpticalPhaseRad, 0f, waterOptics.glassRefractionScale),
            "WW_WATER_DISTORTION" to Triple(waterOptics.waterFlowPhaseRad, 0f, 1f),
        )
        // Ensure exact 1:1 alignment with WaterWorldMotionContract.CANONICAL_47_ELEMENTS
        val ordered = linkedMapOf<String, WaterWorldDynamicFrameSnapshot.ElementMotionState>()
        for (canonicalId in WaterWorldMotionContract.CANONICAL_47_ELEMENTS) {
            val existing = map[canonicalId]
            if (existing != null) {
                ordered[canonicalId] = existing
            } else {
                val fallback = remaining[canonicalId] ?: Triple(waterOptics.waterFlowPhaseRad, 0f, 1f)
                ordered[canonicalId] = WaterWorldDynamicFrameSnapshot.ElementMotionState(
                    elementId = canonicalId,
                    active = !isZero,
                    phaseRad = fallback.first,
                    displacementPx = fallback.second,
                    modulationScale = fallback.third,
                    provenance = prov,
                )
            }
        }
        return ordered
    }

    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
