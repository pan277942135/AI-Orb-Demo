package com.example.waterworld

/**
 * Single authoritative monotonic simulation timeline for Water World V2.1 Work 3.
 *
 * Architectural guarantees:
 * - Single monotonic simulation clock (`globalTimeSeconds`) shared across all 11 passes & 5 optics.
 * - Frame-rate invariant: stepping at 60Hz, 30Hz, or variable deltas reaches the exact same time.
 * - Pause freezes time cleanly and clears wall-clock anchor so Resume produces zero jump.
 * - Reset deterministically restores `globalTimeSeconds = 0.0` and `frameId = 0L`.
 * - Deterministic absolute-time evaluation (`evaluateAt`) never mutates live playback state.
 */
class WaterWorldMotionTimeline(
    private val maxFrameDeltaSec: Double = DEFAULT_MAX_FRAME_DELTA_SEC,
    val deterministicSeed: Long = DEFAULT_DETERMINISTIC_SEED,
) {
    enum class PlaybackState {
        PLAYING,
        PAUSED,
        RESUMED,
        RESET,
    }

    data class TickResult(
        val frameId: Long,
        val globalTimeSeconds: Double,
        val deltaSeconds: Double,
        val rawDeltaSeconds: Double,
        val clamped: Boolean,
        val playbackState: PlaybackState,
        val speedScale: Float,
        val staticReferenceMode: Boolean,
    )

    companion object {
        const val DEFAULT_MAX_FRAME_DELTA_SEC: Double = 0.10
        const val MIN_SPEED_SCALE: Float = 0.25f
        const val MAX_SPEED_SCALE: Float = 2.0f
        const val DEFAULT_DETERMINISTIC_SEED: Long = 20261126L
    }

    private var _state: PlaybackState = PlaybackState.PAUSED
    private var _globalTimeSeconds: Double = 0.0
    private var _frameId: Long = 0L
    private var _speedScale: Float = 1.0f
    private var _staticReferenceMode: Boolean = false
    private var lastWallClockNanos: Long = -1L

    val state: PlaybackState get() = _state
    val globalTimeSeconds: Double get() = _globalTimeSeconds
    val frameId: Long get() = _frameId
    val speedScale: Float get() = _speedScale
    val staticReferenceMode: Boolean get() = _staticReferenceMode

    val isPlaying: Boolean
        get() = (_state == PlaybackState.PLAYING || _state == PlaybackState.RESUMED) && !_staticReferenceMode

    @Synchronized
    fun play(nowNanos: Long = -1L) {
        _staticReferenceMode = false
        _state = if (_globalTimeSeconds > 0.0) PlaybackState.RESUMED else PlaybackState.PLAYING
        lastWallClockNanos = if (nowNanos >= 0L) nowNanos else -1L
    }

    @Synchronized
    fun pause() {
        _state = PlaybackState.PAUSED
        lastWallClockNanos = -1L
    }

    @Synchronized
    fun resume(nowNanos: Long = -1L) {
        _staticReferenceMode = false
        _state = PlaybackState.RESUMED
        lastWallClockNanos = if (nowNanos >= 0L) nowNanos else -1L
    }

    @Synchronized
    fun reset(autoPlay: Boolean = false, nowNanos: Long = -1L) {
        _globalTimeSeconds = 0.0
        _frameId = 0L
        lastWallClockNanos = if (autoPlay && nowNanos >= 0L) nowNanos else -1L
        _state = if (autoPlay) PlaybackState.PLAYING else PlaybackState.RESET
    }

    @Synchronized
    fun setSpeedScale(scale: Float) {
        require(scale.isFinite() && scale > 0f) { "speedScale must be finite and positive, got $scale" }
        _speedScale = scale.coerceIn(MIN_SPEED_SCALE, MAX_SPEED_SCALE)
    }

    @Synchronized
    fun setStaticReferenceMode(enabled: Boolean) {
        _staticReferenceMode = enabled
        if (enabled) {
            lastWallClockNanos = -1L
        }
    }

    /**
     * Advances the timeline using a monotonic wall-clock timestamp in nanoseconds (e.g. System.nanoTime
     * or Choreographer frameTimeNanos). First tick after play/resume anchors the clock with dt=0
     * so there is never an initial jump.
     */
    @Synchronized
    fun advanceToNanos(nowNanos: Long): TickResult {
        if (_staticReferenceMode) {
            lastWallClockNanos = -1L
            return TickResult(
                frameId = _frameId,
                globalTimeSeconds = 0.0,
                deltaSeconds = 0.0,
                rawDeltaSeconds = 0.0,
                clamped = false,
                playbackState = _state,
                speedScale = _speedScale,
                staticReferenceMode = true,
            )
        }
        if (!isPlaying) {
            lastWallClockNanos = -1L
            return TickResult(
                frameId = _frameId,
                globalTimeSeconds = _globalTimeSeconds,
                deltaSeconds = 0.0,
                rawDeltaSeconds = 0.0,
                clamped = false,
                playbackState = _state,
                speedScale = _speedScale,
                staticReferenceMode = false,
            )
        }
        if (lastWallClockNanos < 0L) {
            lastWallClockNanos = nowNanos
            return TickResult(
                frameId = _frameId,
                globalTimeSeconds = _globalTimeSeconds,
                deltaSeconds = 0.0,
                rawDeltaSeconds = 0.0,
                clamped = false,
                playbackState = _state,
                speedScale = _speedScale,
                staticReferenceMode = false,
            )
        }
        val rawNs = (nowNanos - lastWallClockNanos).coerceAtLeast(0L)
        lastWallClockNanos = nowNanos
        return advanceByDeltaSeconds(rawNs / 1_000_000_000.0)
    }

    /**
     * Advances the timeline by an explicit delta in seconds (clamped cleanly to [0, maxFrameDeltaSec]).
     */
    @Synchronized
    fun advanceByDeltaSeconds(rawDeltaSec: Double): TickResult {
        if (_staticReferenceMode) {
            return TickResult(
                frameId = _frameId,
                globalTimeSeconds = 0.0,
                deltaSeconds = 0.0,
                rawDeltaSeconds = rawDeltaSec,
                clamped = false,
                playbackState = _state,
                speedScale = _speedScale,
                staticReferenceMode = true,
            )
        }
        if (!isPlaying) {
            return TickResult(
                frameId = _frameId,
                globalTimeSeconds = _globalTimeSeconds,
                deltaSeconds = 0.0,
                rawDeltaSeconds = rawDeltaSec,
                clamped = false,
                playbackState = _state,
                speedScale = _speedScale,
                staticReferenceMode = false,
            )
        }
        val safeRaw = if (rawDeltaSec.isFinite() && rawDeltaSec > 0.0) rawDeltaSec else 0.0
        val clamped = safeRaw > maxFrameDeltaSec
        val boundedDt = if (clamped) maxFrameDeltaSec else safeRaw
        val scaledDt = boundedDt * _speedScale.toDouble()
        _globalTimeSeconds += scaledDt
        _frameId += 1L
        if (_state == PlaybackState.RESUMED) {
            _state = PlaybackState.PLAYING
        }
        return TickResult(
            frameId = _frameId,
            globalTimeSeconds = _globalTimeSeconds,
            deltaSeconds = scaledDt,
            rawDeltaSeconds = safeRaw,
            clamped = clamped,
            playbackState = _state,
            speedScale = _speedScale,
            staticReferenceMode = false,
        )
    }
}
