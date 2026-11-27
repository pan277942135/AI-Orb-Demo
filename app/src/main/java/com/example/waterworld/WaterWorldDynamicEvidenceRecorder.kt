package com.example.waterworld

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Deterministic multi-timestamp dynamic evidence recorder for Water World V2.1 Work 3.
 *
 * Captures reproducible frame PNGs at `T = 0, 1, 2, 4, 8, 12` seconds along with:
 * - `motion_timeline.json`
 * - `motion_element_states.json` (all 47 canonical elements per timestamp)
 * - `motion_dependency_execution.json` (all 28 canonical dependencies per timestamp)
 * - `dynamic_render_telemetry.json`
 * - `dynamic_evidence_manifest.json` (SHA-256 of every output file)
 *
 * Strictly honest about hardware GPU vs software verification and GL fence synchronization.
 */
internal class WaterWorldDynamicEvidenceRecorder(
    private val runtime: WaterWorldMotionRuntime,
) {
    companion object {
        val CANONICAL_TIMESTAMPS_SEC: List<Double> = listOf(0.0, 1.0, 2.0, 4.0, 8.0, 12.0)

        fun frameFileName(timeSec: Double): String =
            String.format(Locale.US, "frame_t%03d.png", timeSec.toInt())

        internal fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    data class DynamicCaptureSummary(
        val directory: File,
        val capturedFrames: Int,
        val fenceSignaledCount: Int,
        val telemetry: JSONObject,
        val manifestFile: File,
    )

    /**
     * Captures a complete deterministic dynamic sequence (`T=0, 1, 2, 4, 8, 12`) directly on the
     * active GL thread using [WaterWorldStaticRenderer.renderDynamic] without mutating live playback.
     */
    fun captureDeterministicSequence(
        renderer: WaterWorldStaticRenderer,
        outputDir: File,
        timestampsSec: List<Double> = CANONICAL_TIMESTAMPS_SEC,
    ): DynamicCaptureSummary {
        check(outputDir.isDirectory || outputDir.mkdirs()) {
            "Failed to create dynamic evidence directory: ${outputDir.path}"
        }
        val timelineArr = JSONArray()
        val elementsArr = JSONArray()
        val depsArr = JSONArray()
        val frameTelemArr = JSONArray()
        var fenceSignaled = 0

        timestampsSec.forEachIndexed { idx, tSec ->
            val snapshot = runtime.evaluateDeterministic(
                timeSeconds = tSec,
                frameId = idx.toLong(),
                staticReferenceMode = (tSec <= 0.0),
            )
            val passDir = if (tSec == 0.0) File(outputDir, "passes_t000").also { it.mkdirs() } else null
            val renderTel = renderer.renderDynamic(
                snapshot = snapshot,
                mode = WaterWorldRenderGraph.DiagnosticMode.FINAL_SCENE,
                capturePassDir = passDir,
            )
            val fenceOk = renderer.waitForGpuFenceSync()
            if (fenceOk) fenceSignaled++

            val pngFile = File(outputDir, frameFileName(tSec))
            val pngSha = renderer.capture(pngFile)

            timelineArr.put(
                snapshot.toTimelineJson()
                    .put("fileName", pngFile.name)
                    .put("pngSha256", pngSha)
                    .put("gpuFenceSignaled", fenceOk)
            )
            elementsArr.put(snapshot.toElementStatesJson())
            depsArr.put(snapshot.toDependencyExecutionJson())
            frameTelemArr.put(
                renderTel
                    .put("capturedFile", pngFile.name)
                    .put("capturedSha256", pngSha)
                    .put("gpuFenceSignaled", fenceOk)
            )
        }

        val perfStats = runtime.currentPerformanceStats()
        val dynamicTelemetry = JSONObject()
            .put("schemaVersion", "WATER_WORLD_V21_WORK3_DYNAMIC_EVIDENCE_V1")
            .put("provenance", runtime.profile.motionParameterProvenance)
            .put("waterWorldMotionValuesValidated", runtime.profile.waterWorldMotionValuesValidated)
            .put("coreVolumeSliceCount", runtime.profile.coreVolumeSliceCount)
            .put("coreVolumeSliceSemantics", runtime.profile.coreVolumeSliceSemantics)
            .put("capturedTimestampCount", timestampsSec.size)
            .put("timestampsSeconds", JSONArray(timestampsSec))
            .put("gpuFenceSignaledFrames", fenceSignaled)
            .put("strictHardwareFenceVerified", fenceSignaled == timestampsSec.size)
            .put("glErrorCount", renderer.currentGlErrorCount())
            .put("performanceStats", perfStats.toJson())
            .put("frames", frameTelemArr)

        File(outputDir, "motion_timeline.json").writeText(
            JSONObject()
                .put("schemaVersion", "WATER_WORLD_V21_WORK3_TIMELINE_V1")
                .put("seed", runtime.timeline.deterministicSeed)
                .put("frames", timelineArr)
                .toString(2)
        )
        File(outputDir, "motion_element_states.json").writeText(
            JSONObject()
                .put("schemaVersion", "WATER_WORLD_V21_WORK3_ELEMENTS_47_V1")
                .put("canonicalElementCount", WaterWorldMotionContract.CANONICAL_47_ELEMENTS.size)
                .put("timestamps", elementsArr)
                .toString(2)
        )
        File(outputDir, "motion_dependency_execution.json").writeText(
            JSONObject()
                .put("schemaVersion", "WATER_WORLD_V21_WORK3_DEPENDENCIES_28_V1")
                .put("canonicalDependencyCount", WaterWorldMotionContract.CANONICAL_28_DEPENDENCIES.size)
                .put("timestamps", depsArr)
                .toString(2)
        )
        File(outputDir, "dynamic_render_telemetry.json").writeText(dynamicTelemetry.toString(2))

        val manifestFile = writeDynamicEvidenceManifest(outputDir)
        return DynamicCaptureSummary(
            directory = outputDir,
            capturedFrames = timestampsSec.size,
            fenceSignaledCount = fenceSignaled,
            telemetry = dynamicTelemetry,
            manifestFile = manifestFile,
        )
    }

    /**
     * Generates deterministic metadata JSONs (timeline, 47 element states, 28 dependencies)
     * without requiring an active GL context — used for JVM/Robolectric verification.
     */
    fun exportDeterministicMetadataOnly(
        outputDir: File,
        timestampsSec: List<Double> = CANONICAL_TIMESTAMPS_SEC,
    ): File {
        check(outputDir.isDirectory || outputDir.mkdirs())
        val timelineArr = JSONArray()
        val elementsArr = JSONArray()
        val depsArr = JSONArray()
        timestampsSec.forEachIndexed { idx, tSec ->
            val snap = runtime.evaluateDeterministic(tSec, idx.toLong(), staticReferenceMode = (tSec <= 0.0))
            timelineArr.put(snap.toTimelineJson())
            elementsArr.put(snap.toElementStatesJson())
            depsArr.put(snap.toDependencyExecutionJson())
        }
        File(outputDir, "motion_timeline.json").writeText(
            JSONObject().put("seed", runtime.timeline.deterministicSeed).put("frames", timelineArr).toString(2)
        )
        File(outputDir, "motion_element_states.json").writeText(
            JSONObject().put("canonicalElementCount", 47).put("timestamps", elementsArr).toString(2)
        )
        File(outputDir, "motion_dependency_execution.json").writeText(
            JSONObject().put("canonicalDependencyCount", 28).put("timestamps", depsArr).toString(2)
        )
        return writeDynamicEvidenceManifest(outputDir)
    }

    fun writeDynamicEvidenceManifest(outputDir: File): File {
        val manifestFile = File(outputDir, "dynamic_evidence_manifest.json")
        val entries = JSONObject()
        val allFiles = outputDir.walkTopDown()
            .filter { it.isFile && it.name != manifestFile.name }
            .sortedBy { it.relativeTo(outputDir).path }
            .toList()
        for (f in allFiles) {
            val rel = f.relativeTo(outputDir).path
            entries.put(
                rel,
                JSONObject()
                    .put("bytes", f.length())
                    .put("sha256", sha256(f))
            )
        }
        manifestFile.writeText(
            JSONObject()
                .put("schemaVersion", "WATER_WORLD_V21_WORK3_DYNAMIC_MANIFEST_V1")
                .put("directory", outputDir.name)
                .put("fileCount", allFiles.size)
                .put("files", entries)
                .toString(2)
        )
        return manifestFile
    }
}
