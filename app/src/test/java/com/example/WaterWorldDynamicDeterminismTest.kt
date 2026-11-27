package com.example

import com.example.waterworld.WaterWorldDynamicEvidenceRecorder
import com.example.waterworld.WaterWorldDynamicFrameSnapshot
import com.example.waterworld.WaterWorldMotionRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WaterWorldDynamicDeterminismTest {

    @Test
    fun t01_repeatedEvaluationsAtCanonicalTimestampsAreBitIdenticalRegardlessOfOrder() {
        val runtimeA = WaterWorldMotionRuntime()
        val runtimeB = WaterWorldMotionRuntime()
        val timestamps = WaterWorldDynamicEvidenceRecorder.CANONICAL_TIMESTAMPS_SEC

        val forward = timestamps.mapIndexed { idx, t ->
            runtimeA.evaluateDeterministic(t, idx.toLong(), staticReferenceMode = (t <= 0.0))
        }
        // Advance runtimeB arbitrarily first, then evaluate in REVERSE order
        runtimeB.play()
        repeat(90) { runtimeB.stepByDeltaSeconds(1.0 / 30.0) }
        val reverse = timestamps.reversed().map { t ->
            val idx = timestamps.indexOf(t)
            runtimeB.evaluateDeterministic(t, idx.toLong(), staticReferenceMode = (t <= 0.0))
        }.reversed()

        for (i in timestamps.indices) {
            assertEquals(
                "Timeline JSON must be bit-identical at T=${timestamps[i]}",
                forward[i].toTimelineJson().toString(),
                reverse[i].toTimelineJson().toString(),
            )
            assertEquals(
                "47-element JSON must be bit-identical at T=${timestamps[i]}",
                forward[i].toElementStatesJson().toString(),
                reverse[i].toElementStatesJson().toString(),
            )
            assertEquals(
                "28-dependency JSON must be bit-identical at T=${timestamps[i]}",
                forward[i].toDependencyExecutionJson().toString(),
                reverse[i].toDependencyExecutionJson().toString(),
            )
        }

        // Verify consecutive timestamps genuinely differ (real continuous motion, not frozen)
        assertNotEquals(
            forward[0].toElementStatesJson().toString(),
            forward[1].toElementStatesJson().toString(),
        )
        assertNotEquals(
            forward[1].toElementStatesJson().toString(),
            forward[4].toElementStatesJson().toString(),
        )
    }

    @Test
    fun t02_resetRestoresBitIdenticalT0StaticZeroState() {
        val runtime = WaterWorldMotionRuntime()
        val initialZero = WaterWorldDynamicFrameSnapshot.staticZero(runtime.profile)

        runtime.play()
        repeat(240) { runtime.stepByDeltaSeconds(1.0 / 60.0) }
        assertNotEquals(0.0, runtime.currentTimeSeconds, 0.0)

        val afterReset = runtime.reset(autoPlay = false)
        assertEquals(initialZero.toTimelineJson().toString(), afterReset.toTimelineJson().toString())
        assertEquals(initialZero.toElementStatesJson().toString(), afterReset.toElementStatesJson().toString())
        assertEquals(initialZero.toDependencyExecutionJson().toString(), afterReset.toDependencyExecutionJson().toString())
    }

    @Test
    fun t03_metadataExportProducesIdenticalFileSha256AcrossIndependentRuns() {
        val dirA = Files.createTempDirectory("ww_det_a").toFile()
        val dirB = Files.createTempDirectory("ww_det_b").toFile()
        try {
            WaterWorldDynamicEvidenceRecorder(WaterWorldMotionRuntime()).exportDeterministicMetadataOnly(dirA)
            WaterWorldDynamicEvidenceRecorder(WaterWorldMotionRuntime()).exportDeterministicMetadataOnly(dirB)

            for (name in listOf("motion_timeline.json", "motion_element_states.json", "motion_dependency_execution.json")) {
                val shaA = WaterWorldDynamicEvidenceRecorder.sha256(File(dirA, name))
                val shaB = WaterWorldDynamicEvidenceRecorder.sha256(File(dirB, name))
                assertEquals("SHA-256 of $name must be deterministic across runs", shaA, shaB)
            }
        } finally {
            dirA.deleteRecursively()
            dirB.deleteRecursively()
        }
    }
}
