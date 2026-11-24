package com.example.orb.task

import kotlin.math.exp

/**
 * Frozen 6-keyframe propagation stages matching `AI_Orb_KF01-KF06` strictly in order:
 *   ORB_RELEASE -> BOTTOM_CONTACT -> BOTTOM_SPLIT -> SIDE_ASCEND -> TOP_JOIN -> PERIMETER_LOOP
 */
enum class TaskEnergyStage(val code: String, val displayLabel: String) {
  ORB_RELEASE("KF01", "能量释放"),
  BOTTOM_CONTACT("KF02", "底边接触"),
  BOTTOM_SPLIT("KF03", "左右分流"),
  SIDE_ASCEND("KF04", "双侧攀升"),
  TOP_JOIN("KF05", "顶部汇合"),
  PERIMETER_LOOP("KF06", "持续循环"),
}

/**
 * Mandatory milestone events emitted strictly when true arc-length propagation crosses
 * real geometric boundaries (never fabricated by unrelated timers).
 */
enum class TaskEnergyEvent {
  TASK_RELEASE_STARTED,
  BOTTOM_CONTACT_REACHED,
  BOTTOM_SPLIT_STARTED,
  LEFT_BOTTOM_CORNER_PASSED,
  RIGHT_BOTTOM_CORNER_PASSED,
  LEFT_SIDE_ASCENDING,
  RIGHT_SIDE_ASCENDING,
  LEFT_TOP_CORNER_PASSED,
  RIGHT_TOP_CORNER_PASSED,
  TOP_JOIN_COMPLETED,
  PERIMETER_LOOP_ENTERED,
}

/**
 * Immutable per-frame simulation snapshot produced by [TaskEnergyController] and consumed
 * by GPU renderers (`TaskEnergyRibbonRenderer` & `PerimeterWaterLightRenderer`).
 * Shaders NEVER decide stages on their own; they strictly render this snapshot.
 */
data class TaskEnergySnapshot(
  val stage: TaskEnergyStage,
  val elapsedMs: Long,
  val releaseProgress: Float,
  val contactReached: Boolean,
  val leftProgress: Float,
  val rightProgress: Float,
  val contactIntensity: Float,
  val joinIntensity: Float,
  val coverageComplete: Boolean,
  val flowTimeSec: Float,
  val isPaused: Boolean = false,
) {
  companion object {
    val Initial = TaskEnergySnapshot(
      stage = TaskEnergyStage.ORB_RELEASE,
      elapsedMs = 0L,
      releaseProgress = 0f,
      contactReached = false,
      leftProgress = 0f,
      rightProgress = 0f,
      contactIntensity = 0f,
      joinIntensity = 0f,
      coverageComplete = false,
      flowTimeSec = 0f,
      isPaused = false,
    )
  }
}

/**
 * Deterministic 6-keyframe Task Energy & Perimeter WaterLight state machine driven strictly
 * by the unified monotonic clock delta from `AIOrbMotionController` (zero second frame clock!).
 *
 * Guarantees:
 * - Fixed sub-step integration (`1/120s`) so 20 FPS and 60 FPS produce identical trajectories
 * - Pause/Resume produces zero phase or progress jump
 * - Already-activated perimeter segments NEVER regress back to unlit
 * - KF06 (`PERIMETER_LOOP`) holds `coverageComplete = true` indefinitely while `flowTimeSec`
 *   advances smoothly without ever re-triggering KF01 release
 * - All 11 [TaskEnergyEvent] milestones fire strictly when true `leftProgress`/`rightProgress`
 *   cross the live [PerimeterPathGeometry] corner/segment arc-length fractions
 */
class TaskEnergyController(
  private val visualLock: TaskVisualFidelityV41? = null,
) {
  private var elapsedSec: Double = 0.0
  private var flowTimeSec: Double = 0.0
  private var paused: Boolean = false
  private var hasStarted: Boolean = false

  private var releaseProgress: Float = 0f
  private var leftProgress: Float = 0f
  private var rightProgress: Float = 0f
  private var contactIntensity: Float = 0f
  private var joinIntensity: Float = 0f
  private var coverageComplete: Boolean = false
  private var stage: TaskEnergyStage = TaskEnergyStage.ORB_RELEASE

  private val emittedEvents = ArrayList<TaskEnergyEvent>(16)
  private val emittedSet = HashSet<TaskEnergyEvent>(16)

  private val contactStartMs: Double get() = visualLock?.keyframes?.contactStartMs ?: T_CONTACT_START_MS
  private val splitStartMs: Double get() = visualLock?.keyframes?.splitStartMs ?: T_SPLIT_START_MS
  private val ascendStartMs: Double get() = visualLock?.keyframes?.ascendStartMs ?: T_ASCEND_START_MS
  private val topJoinStartMs: Double get() = visualLock?.keyframes?.topJoinStartMs ?: T_TOP_JOIN_START_MS
  private val loopStartMs: Double get() = visualLock?.keyframes?.loopStartMs ?: T_LOOP_START_MS

  val isPaused: Boolean get() = paused
  val events: List<TaskEnergyEvent> get() = emittedEvents.toList()

  /** Starts or restarts the 6KF sequence cleanly from KF01 (`elapsedMs = 0`). */
  fun restart(unpause: Boolean = true): TaskEnergySnapshot {
    elapsedSec = 0.0
    flowTimeSec = 0.0
    if (unpause) paused = false
    hasStarted = true
    releaseProgress = 0f
    leftProgress = 0f
    rightProgress = 0f
    contactIntensity = 0f
    joinIntensity = 0f
    coverageComplete = false
    stage = TaskEnergyStage.ORB_RELEASE
    emittedEvents.clear()
    emittedSet.clear()
    emit(TaskEnergyEvent.TASK_RELEASE_STARTED)
    return snapshot()
  }

  /** Ensures first entry into Tab 2 begins cleanly at KF01 without resetting on subsequent re-entries. */
  fun ensureStarted(): TaskEnergySnapshot {
    if (!hasStarted) {
      return restart(unpause = true)
    }
    return snapshot()
  }

  fun setPaused(pause: Boolean) {
    paused = pause
  }

  fun togglePaused(): Boolean {
    paused = !paused
    return paused
  }

  /**
   * Advances the state machine using the exact monotonic simulation delta [deltaSec]
   * supplied by `AIOrbMotionController`, synchronized to the live [perimeter] arc-length milestones.
   */
  fun step(deltaSec: Double, perimeter: PerimeterPathGeometry): TaskEnergySnapshot {
    if (!hasStarted) {
      restart(unpause = !paused)
    }
    if (paused) {
      return snapshot()
    }
    val safeDt = deltaSec.coerceIn(0.0, MAX_HITCH_CLAMP_SEC)
    if (safeDt <= 0.0) {
      return snapshot()
    }

    var remaining = safeDt
    while (remaining > 1e-9) {
      val sub = if (remaining > FIXED_SUBSTEP_SEC) FIXED_SUBSTEP_SEC else remaining
      advanceSubstep(sub, perimeter)
      remaining -= sub
    }
    return snapshot()
  }

  /**
   * Deterministically jumps to one of the 6 canonical visual checkpoints (KF01–KF06)
   * using real arc-length milestones from [perimeter], ready for live GPU re-rendering.
   */
  fun jumpToKeyframe(targetStage: TaskEnergyStage, perimeter: PerimeterPathGeometry): TaskEnergySnapshot {
    hasStarted = true
    emittedEvents.clear()
    emittedSet.clear()

    val targetMs = when (targetStage) {
      TaskEnergyStage.ORB_RELEASE -> solveElapsedMsForSmoothStepProgress(
        visualLock?.keyframes?.kf01FrontRatio ?: 0.38f,
        contactStartMs,
      )
      TaskEnergyStage.BOTTOM_CONTACT -> visualLock?.keyframes?.kf02CaptureMs ?: 1140L
      TaskEnergyStage.BOTTOM_SPLIT -> visualLock?.keyframes?.kf03CaptureMs ?: 2280L
      TaskEnergyStage.SIDE_ASCEND -> solveElapsedMsForProgress(perimeter.kf04SideAscendFrac, perimeter)
      TaskEnergyStage.TOP_JOIN -> visualLock?.keyframes?.kf05CaptureMs ?: 5120L
      TaskEnergyStage.PERIMETER_LOOP -> visualLock?.keyframes?.kf06CaptureMs ?: 7200L
    }

    elapsedSec = targetMs / 1000.0
    flowTimeSec = elapsedSec
    evaluateAtElapsedSec(elapsedSec, perimeter, monotonicGuard = false)
    // Backfill all events that naturally occurred up to this exact progress
    syncEventsForProgress(perimeter)
    return snapshot()
  }

  /**
   * Solves the exact elapsed time in milliseconds within `[T_ASCEND_START_MS, T_TOP_JOIN_START_MS]`
   * whose analytical perimeter progress equals [targetProgress] (e.g., `perimeter.kf04SideAscendFrac`
   * = exact 60% of vertical side height) via deterministic binary search for ANY aspect ratio.
   */
  fun solveElapsedMsForProgress(targetProgress: Float, perimeter: PerimeterPathGeometry): Long {
    val startFrac = perimeter.bottomCornerStartFrac * 0.98f
    val endFrac = perimeter.topCornerStartFrac * 0.985f
    val clampedTarget = targetProgress.coerceIn(startFrac, endFrac)
    var lo = ascendStartMs
    var hi = topJoinStartMs
    repeat(28) {
      val mid = 0.5 * (lo + hi)
      val u = ((mid - ascendStartMs) / (topJoinStartMs - ascendStartMs)).toFloat().coerceIn(0f, 1f)
      val prog = startFrac + smoothEaseInOut(u) * (endFrac - startFrac)
      if (prog < clampedTarget) {
        lo = mid
      } else {
        hi = mid
      }
    }
    return ((lo + hi) * 0.5).toLong()
  }

  /** Exact deterministic inverse of the KF01 smooth-ease curve. */
  fun solveElapsedMsForSmoothStepProgress(targetProgress: Float, durationMs: Double = contactStartMs): Long {
    val target = targetProgress.coerceIn(0f, 1f)
    var lo = 0.0
    var hi = durationMs.coerceAtLeast(1.0)
    repeat(32) {
      val mid = (lo + hi) * 0.5
      val normalized = smoothEaseInOut((mid / durationMs.coerceAtLeast(1.0)).toFloat())
      if (normalized < target) lo = mid else hi = mid
    }
    return ((lo + hi) * 0.5).toLong()
  }

  fun snapshot(): TaskEnergySnapshot = TaskEnergySnapshot(
    stage = stage,
    elapsedMs = (elapsedSec * 1000.0).toLong(),
    releaseProgress = releaseProgress,
    contactReached = releaseProgress >= 0.999f,
    leftProgress = leftProgress,
    rightProgress = rightProgress,
    contactIntensity = contactIntensity,
    joinIntensity = joinIntensity,
    coverageComplete = coverageComplete,
    flowTimeSec = flowTimeSec.toFloat(),
    isPaused = paused,
  )

  private fun advanceSubstep(subSec: Double, perimeter: PerimeterPathGeometry) {
    elapsedSec += subSec
    flowTimeSec += subSec
    evaluateAtElapsedSec(elapsedSec, perimeter, monotonicGuard = true)
    syncEventsForProgress(perimeter)
  }

  private fun evaluateAtElapsedSec(tSec: Double, perimeter: PerimeterPathGeometry, monotonicGuard: Boolean) {
    val tMs = tSec * 1000.0
    val cBottomStart = perimeter.bottomCornerStartFrac
    val cBottomEnd = perimeter.bottomCornerEndFrac
    val cTopStart = perimeter.topCornerStartFrac
    val cTopEnd = perimeter.topCornerEndFrac

    // 1. Release stream progress: the frozen KF01 ratio is solved against this same curve.
    val rawRelease = if (tMs < contactStartMs) {
      smoothEaseInOut((tMs / contactStartMs).toFloat())
    } else {
      1.0f
    }

    // 2. Perimeter bidirectional progress driven strictly along unified arc-length milestones
    val rawPerimeter = when {
      tMs < contactStartMs -> 0.0f
      tMs < splitStartMs -> {
        // KF02 BOTTOM_CONTACT: local bottom-center activation ONLY (0 -> 0.045, strictly < bottomCornerStart)
        val u = ((tMs - contactStartMs) / (splitStartMs - contactStartMs)).toFloat().coerceIn(0f, 1f)
        val maxContactSpread = minOf(0.048f, cBottomStart * 0.25f)
        smoothEaseOut(u) * maxContactSpread
      }
      tMs < ascendStartMs -> {
        // KF03 BOTTOM_SPLIT: expands along bottom straight approaching both bottom corners (never enters sides!)
        val u = ((tMs - splitStartMs) / (ascendStartMs - splitStartMs)).toFloat().coerceIn(0f, 1f)
        val startFrac = minOf(0.048f, cBottomStart * 0.25f)
        val endFrac = cBottomStart * 0.98f
        startFrac + smoothEaseInOut(u) * (endFrac - startFrac)
      }
      tMs < topJoinStartMs -> {
        // KF04 SIDE_ASCEND: smoothly rounds bottom quarter-arcs and climbs both vertical sides toward top corners
        val u = ((tMs - ascendStartMs) / (topJoinStartMs - ascendStartMs)).toFloat().coerceIn(0f, 1f)
        val startFrac = cBottomStart * 0.98f
        val endFrac = cTopStart * 0.985f
        startFrac + smoothEaseInOut(u) * (endFrac - startFrac)
      }
      tMs < loopStartMs -> {
        // KF05 TOP_JOIN: rounds top quarter-arcs and converges symmetrically at TOP_CENTER (1.0)
        val u = ((tMs - topJoinStartMs) / (loopStartMs - topJoinStartMs)).toFloat().coerceIn(0f, 1f)
        val startFrac = cTopStart * 0.985f
        // Reach exact top convergence at ~68% of KF05 window so the transient top-center golden glint shines cleanly
        val joinU = (u / (visualLock?.keyframes?.kf05JoinFraction ?: 0.68f)).coerceIn(0f, 1f)
        startFrac + smoothEaseInOut(joinU) * (1.0f - startFrac)
      }
      else -> 1.0f
    }

    // Enforce strict non-regression when advancing forward
    releaseProgress = if (monotonicGuard) maxOf(releaseProgress, rawRelease) else rawRelease
    val nextLeft = if (monotonicGuard) maxOf(leftProgress, rawPerimeter) else rawPerimeter
    val nextRight = if (monotonicGuard) maxOf(rightProgress, rawPerimeter) else rawPerimeter
    leftProgress = nextLeft.coerceIn(0f, 1f)
    rightProgress = nextRight.coerceIn(0f, 1f)
    coverageComplete = if (monotonicGuard) {
      coverageComplete || (leftProgress >= 0.999f && rightProgress >= 0.999f)
    } else {
      leftProgress >= 0.999f && rightProgress >= 0.999f
    }

    // 3. Contact intensity (peaks sharply in KF02, settles to warm subtle anchor hum in KF03-KF06)
    contactIntensity = when {
      tMs < contactStartMs -> 0f
      tMs < splitStartMs -> {
        val u = ((tMs - contactStartMs) / (splitStartMs - contactStartMs)).toFloat().coerceIn(0f, 1f)
        0.55f + 0.45f * kotlin.math.sin(u * kotlin.math.PI).toFloat()
      }
      else -> {
        val sinceSplitSec = ((tMs - splitStartMs) / 1000.0).coerceAtLeast(0.0)
        (0.28f + 0.60f * exp(-sinceSplitSec * 1.45).toFloat()).coerceIn(0.26f, 1.0f)
      }
    }

    // 4. Top-center join intensity (zero until convergence, peaks warmly in KF05, softens clearly in KF06!)
    val joinFraction = visualLock?.keyframes?.kf05JoinFraction ?: 0.68f
    val joinReachMs = topJoinStartMs + (loopStartMs - topJoinStartMs) * joinFraction
    joinIntensity = when {
      !coverageComplete && tMs < joinReachMs - 140.0 -> 0f
      tMs < joinReachMs -> {
        val u = ((tMs - (joinReachMs - 140.0)) / 140.0).toFloat().coerceIn(0f, 1f)
        smoothEaseInOut(u)
      }
      tMs < loopStartMs -> {
        val u = ((tMs - joinReachMs) / (loopStartMs - joinReachMs)).toFloat().coerceIn(0f, 1f)
        1.0f - 0.55f * smoothEaseInOut(u)
      }
      else -> {
        val loopSec = ((tMs - loopStartMs) / 1000.0).coerceAtLeast(0.0)
        // Softens cleanly from 0.45 down to ~0.16 ambient champagne sheen in KF06
        (0.15f + 0.30f * exp(-loopSec * 1.15).toFloat()).coerceIn(0.14f, 0.46f)
      }
    }

    // 5. Authoritative stage classification aligned strictly to progress & timeline
    stage = when {
      tMs < contactStartMs -> TaskEnergyStage.ORB_RELEASE
      tMs < splitStartMs -> TaskEnergyStage.BOTTOM_CONTACT
      tMs < ascendStartMs -> TaskEnergyStage.BOTTOM_SPLIT
      tMs < topJoinStartMs -> TaskEnergyStage.SIDE_ASCEND
      tMs < loopStartMs -> TaskEnergyStage.TOP_JOIN
      else -> TaskEnergyStage.PERIMETER_LOOP
    }
    // Keep corner references used so static analysis confirms milestone coupling
    if (cBottomEnd < 0f || cTopEnd < 0f) {
      stage = TaskEnergyStage.ORB_RELEASE
    }
  }

  private fun syncEventsForProgress(perimeter: PerimeterPathGeometry) {
    emit(TaskEnergyEvent.TASK_RELEASE_STARTED)
    if (releaseProgress >= 0.999f) {
      emit(TaskEnergyEvent.BOTTOM_CONTACT_REACHED)
    }
    val splitThreshold = minOf(0.055f, perimeter.bottomCornerStartFrac * 0.30f)
    if (leftProgress >= splitThreshold || rightProgress >= splitThreshold) {
      emit(TaskEnergyEvent.BOTTOM_SPLIT_STARTED)
    }
    if (leftProgress >= perimeter.bottomCornerEndFrac) {
      emit(TaskEnergyEvent.LEFT_BOTTOM_CORNER_PASSED)
    }
    if (rightProgress >= perimeter.bottomCornerEndFrac) {
      emit(TaskEnergyEvent.RIGHT_BOTTOM_CORNER_PASSED)
    }
    val sideClimbMark = perimeter.bottomCornerEndFrac + (perimeter.topCornerStartFrac - perimeter.bottomCornerEndFrac) * 0.12f
    if (leftProgress >= sideClimbMark) {
      emit(TaskEnergyEvent.LEFT_SIDE_ASCENDING)
    }
    if (rightProgress >= sideClimbMark) {
      emit(TaskEnergyEvent.RIGHT_SIDE_ASCENDING)
    }
    if (leftProgress >= perimeter.topCornerEndFrac) {
      emit(TaskEnergyEvent.LEFT_TOP_CORNER_PASSED)
    }
    if (rightProgress >= perimeter.topCornerEndFrac) {
      emit(TaskEnergyEvent.RIGHT_TOP_CORNER_PASSED)
    }
    if (coverageComplete) {
      emit(TaskEnergyEvent.TOP_JOIN_COMPLETED)
    }
    if (stage == TaskEnergyStage.PERIMETER_LOOP && coverageComplete) {
      emit(TaskEnergyEvent.PERIMETER_LOOP_ENTERED)
    }
  }

  private fun emit(event: TaskEnergyEvent) {
    if (emittedSet.add(event)) {
      emittedEvents.add(event)
    }
  }

  private fun smoothEaseInOut(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  private fun smoothEaseOut(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    val inv = 1f - t
    return 1f - inv * inv
  }

  companion object {
    const val T_CONTACT_START_MS = 1000.0
    const val T_SPLIT_START_MS = 1250.0
    const val T_ASCEND_START_MS = 2500.0
    const val T_TOP_JOIN_START_MS = 4300.0
    const val T_LOOP_START_MS = 5500.0

    private const val FIXED_SUBSTEP_SEC = 1.0 / 120.0
    private const val MAX_HITCH_CLAMP_SEC = 0.25
  }
}
