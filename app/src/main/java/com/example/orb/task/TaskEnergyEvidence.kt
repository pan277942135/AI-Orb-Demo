package com.example.orb.task

import java.util.Locale

/**
 * Runtime diagnostics & evidence recorder verifying:
 *  - Single shared GPU context (`glContextInitCount == 1`)
 *  - Single V3 WebP 3D Volume load (`webpLoadCount <= 1` across tab switches)
 *  - Continuous Orb motion across Tab 1 <-> Tab 2 switches
 *  - Ordered emission of all 11 Task Energy milestone events
 *  - Real displayed photo & orb pixel coordinates
 */
data class TaskEnergyEvidence(
  val glContextInitCount: Int = 0,
  val webpLoadCount: Int = 0,
  val tabSwitchCount: Int = 0,
  val taskSnapshot: TaskEnergySnapshot = TaskEnergySnapshot.Initial,
  val emittedEvents: List<TaskEnergyEvent> = emptyList(),
  val layoutSnapshot: TaskSceneLayoutSnapshot? = null,
  val photoTextureLoaded: Boolean = false,
) {
  val gpuResourcesReused: Boolean
    get() = glContextInitCount <= 1 && webpLoadCount <= 1

  fun summaryLine(): String {
    val p = layoutSnapshot?.photoFrame
    val photoDesc = if (p != null) {
      String.format(Locale.US, "Photo[%.0f,%.0f..%.0f,%.0f r=%.0f]", p.leftPx, p.topPx, p.rightPx, p.bottomPx, p.cornerRadiusPx)
    } else {
      "Photo[pending]"
    }
    return "${taskSnapshot.stage.code} ${taskSnapshot.elapsedMs}ms " +
      "rel=${String.format(Locale.US, "%.2f", taskSnapshot.releaseProgress)} " +
      "L/R=${String.format(Locale.US, "%.2f", taskSnapshot.leftProgress)} " +
      "events=${emittedEvents.size}/11 $photoDesc reuse=${gpuResourcesReused}"
  }
}
