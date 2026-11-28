package com.example.orb.task

import kotlin.math.PI
import kotlin.math.sin

/**
 * Independent photo perimeter light lifecycle states for Tab 2
 * (`TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`).
 *
 * Strictly decoupled from the lower Water World ecosystem sphere:
 *   PHOTO_SELECTED -> PHOTO_STABLE -> PERIMETER_LIGHT_ACTIVATED
 * ZERO energy beam or rising channel droplets are ever emitted from the Orb to the photo.
 */
enum class Tab2PhotoPerimeterState {
  PHOTO_SELECTED,
  PHOTO_STABLE,
  PERIMETER_LIGHT_ACTIVATED,
}

/**
 * Immutable snapshot of Tab 2's independent photo perimeter light state.
 */
data class Tab2PhotoPerimeterSnapshot(
  val state: Tab2PhotoPerimeterState,
  val elapsedSec: Double,
  val perimeterProgress: Float,
  val shimmerIntensity: Float,
  val flowTimeSec: Float,
  val isPaused: Boolean,
  val orbToPhotoBeamActive: Boolean = false,
) {
  /**
   * Adapts to [TaskEnergySnapshot] for zero-duplication reuse of [PerimeterWaterLightRenderer]
   * while guaranteeing `releaseProgress == 0f` and `contactIntensity == 0f` so NO orb-to-photo
   * energy stream or channel droplets are ever rendered.
   */
  fun toPerimeterRenderSnapshot(): TaskEnergySnapshot {
    val active = state == Tab2PhotoPerimeterState.PERIMETER_LIGHT_ACTIVATED
    val cov = active && perimeterProgress >= 0.999f
    val mappedStage = when {
      !active -> TaskEnergyStage.ORB_RELEASE
      cov -> TaskEnergyStage.PERIMETER_LOOP
      else -> TaskEnergyStage.SIDE_ASCEND
    }
    return TaskEnergySnapshot(
      stage = mappedStage,
      elapsedMs = (elapsedSec * 1000.0).toLong(),
      releaseProgress = 0f, // STRICTLY ZERO: no beam emitted from Water World sphere!
      contactReached = false,
      contactIntensity = 0f, // STRICTLY ZERO: no orb-impact flash on photo bottom!
      leftProgress = if (active) perimeterProgress else 0f,
      rightProgress = if (active) perimeterProgress else 0f,
      joinIntensity = if (active && cov) (0.28f + 0.10f * shimmerIntensity) else 0f,
      coverageComplete = cov,
      flowTimeSec = flowTimeSec,
      isPaused = isPaused,
    )
  }
}

/**
 * Deterministic controller driving Tab 2's independent photo perimeter water-light effect.
 *
 * Timeline after [onPhotoSelected]:
 *   - `[0.00s, 0.25s)`  : `PHOTO_SELECTED` (photo settles cleanly in upper frame, light off)
 *   - `[0.25s, 0.55s)`  : `PHOTO_STABLE` (photo recognized as stable, preparing perimeter light)
 *   - `[0.55s, +inf)`   : `PERIMETER_LIGHT_ACTIVATED` (smooth 1.45s perimeter reveal around all 4
 *                         rounded photo edges, followed by continuous calm water-light shimmer)
 */
class Tab2IndependentPerimeterController {

  private var elapsedSec: Double = 0.0
  private var flowTimeSec: Float = 0f
  private var paused: Boolean = false
  private val stateTransitions = mutableListOf(Tab2PhotoPerimeterState.PHOTO_SELECTED)

  @Volatile
  private var latest: Tab2PhotoPerimeterSnapshot = evaluate(0.0, 0f, false)

  val transitionHistory: List<Tab2PhotoPerimeterState>
    @Synchronized get() = stateTransitions.toList()

  @Synchronized
  fun onPhotoSelected(unpause: Boolean = true): Tab2PhotoPerimeterSnapshot {
    elapsedSec = 0.0
    flowTimeSec = 0f
    if (unpause) paused = false
    stateTransitions.clear()
    stateTransitions += Tab2PhotoPerimeterState.PHOTO_SELECTED
    latest = evaluate(elapsedSec, flowTimeSec, paused)
    return latest
  }

  @Synchronized
  fun setPaused(isPaused: Boolean) {
    paused = isPaused
    latest = latest.copy(isPaused = paused)
  }

  @Synchronized
  fun step(deltaSec: Double): Tab2PhotoPerimeterSnapshot {
    val safeDt = if (deltaSec.isFinite() && deltaSec > 0.0) deltaSec.coerceAtMost(0.05) else 0.0
    if (!paused && safeDt > 0.0) {
      elapsedSec += safeDt
      flowTimeSec += safeDt.toFloat()
    }
    val snap = evaluate(elapsedSec, flowTimeSec, paused)
    if (stateTransitions.lastOrNull() != snap.state) {
      stateTransitions += snap.state
    }
    latest = snap
    return snap
  }

  @Synchronized
  fun snapshot(): Tab2PhotoPerimeterSnapshot = latest

  private fun evaluate(tSec: Double, flow: Float, isPaused: Boolean): Tab2PhotoPerimeterSnapshot {
    return when {
      tSec < PHOTO_STABLE_START_SEC -> Tab2PhotoPerimeterSnapshot(
        state = Tab2PhotoPerimeterState.PHOTO_SELECTED,
        elapsedSec = tSec,
        perimeterProgress = 0f,
        shimmerIntensity = 0f,
        flowTimeSec = flow,
        isPaused = isPaused,
        orbToPhotoBeamActive = false,
      )
      tSec < PERIMETER_ACTIVATE_START_SEC -> Tab2PhotoPerimeterSnapshot(
        state = Tab2PhotoPerimeterState.PHOTO_STABLE,
        elapsedSec = tSec,
        perimeterProgress = 0f,
        shimmerIntensity = 0f,
        flowTimeSec = flow,
        isPaused = isPaused,
        orbToPhotoBeamActive = false,
      )
      else -> {
        val activeElapsed = (tSec - PERIMETER_ACTIVATE_START_SEC).toFloat()
        val u = (activeElapsed / PERIMETER_REVEAL_DURATION_SEC).coerceIn(0f, 1f)
        // Smooth cubic ease-out reveal strictly around photo perimeter
        val eased = 1f - (1f - u) * (1f - u) * (1f - u)
        val shimmer = (0.5f + 0.5f * sin(flow * (2.0 * PI / 3.4)).toFloat()).coerceIn(0f, 1f)
        Tab2PhotoPerimeterSnapshot(
          state = Tab2PhotoPerimeterState.PERIMETER_LIGHT_ACTIVATED,
          elapsedSec = tSec,
          perimeterProgress = eased,
          shimmerIntensity = shimmer,
          flowTimeSec = flow,
          isPaused = isPaused,
          orbToPhotoBeamActive = false,
        )
      }
    }
  }

  companion object {
    const val PHOTO_STABLE_START_SEC = 0.25
    const val PERIMETER_ACTIVATE_START_SEC = 0.55
    const val PERIMETER_REVEAL_DURATION_SEC = 1.45f
  }
}
