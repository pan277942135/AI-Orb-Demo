package com.example.waterworld

import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Single authoritative runtime orchestrator for Water World V2.1 Work 3 dynamic playback.
 *
 * Connects [WaterWorldMotionTimeline], [WaterWorldMotionProfile], [WaterWorldMotionEvaluator],
 * and [WaterWorldCoreMotionAdapter], manages Android lifecycle pause/resume without phase jump,
 * and collects honest rolling frame-interval statistics (P50 / P95 / P99, FPS, clamped spikes).
 */
class WaterWorldMotionRuntime(
    val profile: WaterWorldMotionProfile = WaterWorldMotionProfile.defaultProfile(),
    val timeline: WaterWorldMotionTimeline = WaterWorldMotionTimeline(
        maxFrameDeltaSec = profile.maxFrameDeltaSec,
        deterministicSeed = profile.deterministicSeed,
    ),
) {
    data class PerformanceStats(
        val sampleCount: Int,
        val meanFrameIntervalMs: Double,
        val p50FrameIntervalMs: Double,
        val p95FrameIntervalMs: Double,
        val p99FrameIntervalMs: Double,
        val estimatedFps: Double,
        val clampedDeltaCount: Long,
        val totalFramesRendered: Long,
        val gpuFrameTimeMs: String = "NOT_MEASURED",
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("sampleCount", sampleCount)
            .put("meanFrameIntervalMs", meanFrameIntervalMs)
            .put("p50FrameIntervalMs", p50FrameIntervalMs)
            .put("p95FrameIntervalMs", p95FrameIntervalMs)
            .put("p99FrameIntervalMs", p99FrameIntervalMs)
            .put("estimatedFps", estimatedFps)
            .put("clampedDeltaCount", clampedDeltaCount)
            .put("totalFramesRendered", totalFramesRendered)
            .put("gpuFrameTimeMs", gpuFrameTimeMs)
    }

    companion object {
        private const val PERF_RING_CAPACITY = 240

        fun fromAssetRoot(assetRoot: File?): WaterWorldMotionRuntime {
            val loaded = WaterWorldMotionProfile.loadFromAssetRoot(assetRoot)
            return WaterWorldMotionRuntime(profile = loaded)
        }
    }

    private val intervalRingMs = DoubleArray(PERF_RING_CAPACITY)
    private var intervalRingIndex = 0
    private var intervalRingCount = 0
    private var clampedDeltaCount = 0L
    private var totalFramesRendered = 0L
    private var resumeAfterActivityPause = false

    @Volatile
    private var _latestSnapshot: WaterWorldDynamicFrameSnapshot = WaterWorldDynamicFrameSnapshot.staticZero(profile)

    val latestSnapshot: WaterWorldDynamicFrameSnapshot get() = _latestSnapshot
    val isPlaying: Boolean get() = timeline.isPlaying
    val playbackState: WaterWorldMotionTimeline.PlaybackState get() = timeline.state
    val currentTimeSeconds: Double get() = timeline.globalTimeSeconds
    val speedScale: Float get() = timeline.speedScale
    val staticReferenceMode: Boolean get() = timeline.staticReferenceMode

    init {
        profile.validate()
    }

    @Synchronized
    fun play(nowNanos: Long = -1L) {
        timeline.play(nowNanos)
    }

    @Synchronized
    fun pause() {
        timeline.pause()
    }

    @Synchronized
    fun resume(nowNanos: Long = -1L) {
        timeline.resume(nowNanos)
    }

    @Synchronized
    fun reset(autoPlay: Boolean = false, nowNanos: Long = -1L): WaterWorldDynamicFrameSnapshot {
        timeline.reset(autoPlay = autoPlay, nowNanos = nowNanos)
        val snap = WaterWorldMotionEvaluator.evaluate(
            profile = profile,
            timeSeconds = 0.0,
            frameId = 0L,
            randomSeed = timeline.deterministicSeed,
            staticReferenceMode = !autoPlay,
        )
        _latestSnapshot = snap
        return snap
    }

    @Synchronized
    fun setSpeedScale(scale: Float) {
        timeline.setSpeedScale(scale)
    }

    @Synchronized
    fun setStaticReferenceMode(enabled: Boolean): WaterWorldDynamicFrameSnapshot {
        timeline.setStaticReferenceMode(enabled)
        val snap = WaterWorldMotionEvaluator.evaluate(
            profile = profile,
            timeSeconds = if (enabled) 0.0 else timeline.globalTimeSeconds,
            frameId = timeline.frameId,
            randomSeed = timeline.deterministicSeed,
            staticReferenceMode = enabled,
        )
        _latestSnapshot = snap
        return snap
    }

    @Synchronized
    fun onActivityPause() {
        resumeAfterActivityPause = timeline.isPlaying
        timeline.pause()
    }

    @Synchronized
    fun onActivityResume(nowNanos: Long = -1L) {
        if (resumeAfterActivityPause) {
            resumeAfterActivityPause = false
            timeline.resume(nowNanos)
        }
    }

    @Synchronized
    fun stepFrame(nowNanos: Long): WaterWorldDynamicFrameSnapshot {
        val tick = timeline.advanceToNanos(nowNanos)
        return recordAndEvaluate(tick)
    }

    @Synchronized
    fun stepByDeltaSeconds(deltaSec: Double): WaterWorldDynamicFrameSnapshot {
        val tick = timeline.advanceByDeltaSeconds(deltaSec)
        return recordAndEvaluate(tick)
    }

    /**
     * Pure deterministic snapshot evaluation at an arbitrary absolute timestamp (e.g. T=0,1,2,4,8,12)
     * without mutating the live playback clock or ring buffer.
     */
    fun evaluateDeterministic(
        timeSeconds: Double,
        frameId: Long = 0L,
        staticReferenceMode: Boolean = (timeSeconds <= 0.0),
    ): WaterWorldDynamicFrameSnapshot = WaterWorldMotionEvaluator.evaluate(
        profile = profile,
        timeSeconds = timeSeconds,
        frameId = frameId,
        randomSeed = timeline.deterministicSeed,
        staticReferenceMode = staticReferenceMode,
    )

    @Synchronized
    fun currentPerformanceStats(): PerformanceStats {
        if (intervalRingCount == 0) {
            return PerformanceStats(
                sampleCount = 0,
                meanFrameIntervalMs = 0.0,
                p50FrameIntervalMs = 0.0,
                p95FrameIntervalMs = 0.0,
                p99FrameIntervalMs = 0.0,
                estimatedFps = 0.0,
                clampedDeltaCount = clampedDeltaCount,
                totalFramesRendered = totalFramesRendered,
            )
        }
        val sorted = DoubleArray(intervalRingCount)
        var sum = 0.0
        for (i in 0 until intervalRingCount) {
            val v = intervalRingMs[i]
            sorted[i] = v
            sum += v
        }
        sorted.sort()
        val mean = sum / intervalRingCount
        val p50 = percentile(sorted, 0.50)
        val p95 = percentile(sorted, 0.95)
        val p99 = percentile(sorted, 0.99)
        val fps = if (mean > 1e-4) 1000.0 / mean else 0.0
        return PerformanceStats(
            sampleCount = intervalRingCount,
            meanFrameIntervalMs = mean,
            p50FrameIntervalMs = p50,
            p95FrameIntervalMs = p95,
            p99FrameIntervalMs = p99,
            estimatedFps = fps,
            clampedDeltaCount = clampedDeltaCount,
            totalFramesRendered = totalFramesRendered,
        )
    }

    fun statusSummaryText(): String {
        val stats = currentPerformanceStats()
        val fpsStr = if (stats.sampleCount > 0) String.format(Locale.US, "%.1f FPS", stats.estimatedFps) else "-- FPS"
        val p95Str = if (stats.sampleCount > 0) String.format(Locale.US, "P95 %.1fms", stats.p95FrameIntervalMs) else "P95 --"
        val stateLabel = if (staticReferenceMode) "STATIC_T0" else playbackState.name
        return String.format(
            Locale.US,
            "%s · T=%.2fs · %.2fx · %s · %s",
            stateLabel,
            currentTimeSeconds,
            speedScale,
            fpsStr,
            p95Str,
        )
    }

    private fun recordAndEvaluate(tick: WaterWorldMotionTimeline.TickResult): WaterWorldDynamicFrameSnapshot {
        totalFramesRendered += 1L
        if (tick.clamped) clampedDeltaCount += 1L
        if (tick.rawDeltaSeconds > 0.0) {
            intervalRingMs[intervalRingIndex] = tick.rawDeltaSeconds * 1000.0
            intervalRingIndex = (intervalRingIndex + 1) % PERF_RING_CAPACITY
            if (intervalRingCount < PERF_RING_CAPACITY) intervalRingCount++
        }
        val snap = WaterWorldMotionEvaluator.evaluate(
            profile = profile,
            timeSeconds = tick.globalTimeSeconds,
            frameId = tick.frameId,
            randomSeed = timeline.deterministicSeed,
            staticReferenceMode = tick.staticReferenceMode,
        )
        _latestSnapshot = snap
        return snap
    }

    private fun percentile(sorted: DoubleArray, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
