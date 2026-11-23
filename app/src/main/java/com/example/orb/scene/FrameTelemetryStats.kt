package com.example.orb.scene

import kotlin.math.roundToInt

/**
 * Honest, measured frame-timing telemetry snapshot.
 *
 * Guarantees:
 * - Never fabricates `fps = 60` or reuses `cpuFrameMsWindow[0]` as P50/P95.
 * - Explicitly marks `gpuFrameTimeStatus = "UNAVAILABLE"` when hardware GL timer
 *   queries (`GL_TIME_ELAPSED`) are not enabled/available.
 */
data class FrameTimingSnapshot(
  val fps: Int = 0,
  val latestCpuFrameMs: Float = 0f,
  val cpuFrameMsP50: Float = 0f,
  val cpuFrameMsP95: Float = 0f,
  val sampleCount: Int = 0,
  val hasSufficientSamples: Boolean = false,
  val gpuFrameTimeMs: Float? = null,
  val gpuFrameTimeStatus: String = GPU_FRAME_TIME_UNAVAILABLE,
) {
  companion object {
    const val GPU_FRAME_TIME_UNAVAILABLE = "UNAVAILABLE"
    const val MIN_SUFFICIENT_SAMPLES = 10
  }
}

/**
 * Rolling frame-timing accumulator computing real measured FPS, latest CPU frame duration,
 * and exact sorted P50/P95 percentiles over up to [windowCapacity] recent frames.
 */
class FrameTelemetryStatsTracker(
  private val windowCapacity: Int = 120,
  private val publishIntervalNanos: Long = 500_000_000L,
) {
  private val cpuFrameMsWindow = FloatArray(windowCapacity.coerceAtLeast(8))
  private var sampleCount = 0
  private var cursor = 0
  private var latestCpuMs = 0f

  private var fpsWindowStartNanos = 0L
  private var fpsWindowFrames = 0
  private var lastMeasuredFps = 0
  private var lastFrameStartNanos = 0L

  @Synchronized
  fun reset(nowNanos: Long = 0L) {
    sampleCount = 0
    cursor = 0
    latestCpuMs = 0f
    fpsWindowStartNanos = nowNanos
    fpsWindowFrames = 0
    lastMeasuredFps = 0
    lastFrameStartNanos = 0L
  }

  /**
   * Records a completed frame's CPU duration (`frameEndNanos - frameStartNanos`) and
   * updates the real frame-rate measurement window.
   *
   * @return true when the periodic telemetry publish interval has elapsed.
   */
  @Synchronized
  fun recordFrame(frameStartNanos: Long, frameEndNanos: Long): Boolean {
    val cpuMs = ((frameEndNanos - frameStartNanos).coerceAtLeast(0L)) * 1e-6f
    latestCpuMs = cpuMs
    cpuFrameMsWindow[cursor] = cpuMs
    cursor = (cursor + 1) % cpuFrameMsWindow.size
    if (sampleCount < cpuFrameMsWindow.size) sampleCount++

    if (fpsWindowStartNanos <= 0L) {
      fpsWindowStartNanos = frameStartNanos
    }
    fpsWindowFrames++

    // Maintain an honest instantaneous FPS estimate even before the first 500ms window closes
    if (lastFrameStartNanos > 0L) {
      val interFrameNs = frameStartNanos - lastFrameStartNanos
      if (interFrameNs > 0L && lastMeasuredFps == 0) {
        lastMeasuredFps = (1_000_000_000.0 / interFrameNs).roundToInt().coerceIn(1, 240)
      }
    }
    lastFrameStartNanos = frameStartNanos

    val elapsedNs = frameStartNanos - fpsWindowStartNanos
    if (elapsedNs >= publishIntervalNanos && fpsWindowFrames > 0) {
      lastMeasuredFps = ((fpsWindowFrames * 1_000_000_000.0) / elapsedNs)
        .roundToInt()
        .coerceIn(1, 240)
      fpsWindowFrames = 0
      fpsWindowStartNanos = frameStartNanos
      return true
    }
    return false
  }

  @Synchronized
  fun snapshot(): FrameTimingSnapshot {
    if (sampleCount == 0) {
      return FrameTimingSnapshot(
        fps = lastMeasuredFps,
        latestCpuFrameMs = 0f,
        cpuFrameMsP50 = 0f,
        cpuFrameMsP95 = 0f,
        sampleCount = 0,
        hasSufficientSamples = false,
        gpuFrameTimeMs = null,
        gpuFrameTimeStatus = FrameTimingSnapshot.GPU_FRAME_TIME_UNAVAILABLE,
      )
    }
    val sorted = cpuFrameMsWindow.copyOf(sampleCount).apply { sort() }
    val p50 = percentile(sorted, 0.50)
    val p95 = percentile(sorted, 0.95)
    return FrameTimingSnapshot(
      fps = lastMeasuredFps,
      latestCpuFrameMs = latestCpuMs,
      cpuFrameMsP50 = p50,
      cpuFrameMsP95 = p95,
      sampleCount = sampleCount,
      hasSufficientSamples = sampleCount >= FrameTimingSnapshot.MIN_SUFFICIENT_SAMPLES,
      gpuFrameTimeMs = null,
      gpuFrameTimeStatus = FrameTimingSnapshot.GPU_FRAME_TIME_UNAVAILABLE,
    )
  }

  companion object {
    /**
     * Exact linear-interpolated percentile over a non-empty sorted ascending array.
     */
    fun percentile(sorted: FloatArray, q: Double): Float {
      if (sorted.isEmpty()) return 0f
      if (sorted.size == 1) return sorted[0]
      val clamped = q.coerceIn(0.0, 1.0)
      val pos = clamped * (sorted.size - 1)
      val lo = pos.toInt().coerceIn(0, sorted.size - 1)
      val hi = (lo + 1).coerceIn(0, sorted.size - 1)
      val frac = (pos - lo).toFloat()
      return sorted[lo] * (1f - frac) + sorted[hi] * frac
    }
  }
}
