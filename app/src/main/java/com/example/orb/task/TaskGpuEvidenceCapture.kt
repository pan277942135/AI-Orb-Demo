package com.example.orb.task

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.os.Build
import com.example.orb.scene.AIOrbTelemetry
import com.example.orb.v3.MasterFidelityV32
import com.example.orb.v3.OrbGpuReferenceCapture
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

data class TaskGpuCaptureStep(
  val fileName: String,
  val stage: TaskEnergyStage,
  val debugOptions: TaskRenderDebugOptions,
)

enum class TaskGpuCaptureState {
  IDLE,
  CAPTURING,
  COMPLETED,
  FAILED,
}

data class TaskGpuCaptureProgress(
  val state: TaskGpuCaptureState = TaskGpuCaptureState.IDLE,
  val completedSteps: Int = 0,
  val totalSteps: Int = TaskGpuEvidenceCapture.CAPTURE_STEPS.size,
  val currentFile: String = "",
  val outputDirPath: String = "",
  val statusMessage: String = "",
)

/**
 * Production GL-thread zero-UI GPU evidence capture for Tab 2 (`照片水光`) V4 visual parity.
 *
 * Captures ALL 6 keyframes (`KF01..KF06_FINAL_GPU.png`), G1 visibility diagnostics
 * (`KF06_GEOMETRY_MAGENTA.png`, `KF06_ALPHA_HEATMAP.png`, `KF06_LAYER_L1/L2/L3_ONLY.png`,
 * `KF06_PRE_TONEMAP.png`, `KF06_FINAL_COMPOSITE.png`), and ON/OFF diff proof frames
 * (`KF06_TASK_OFF_GPU.png`, `KF06_TASK_ONLY_GPU.png`, `KF06_PERIMETER_ONLY_GPU.png`),
 * plus `TASK_6KF_V4_RUNTIME_TELEMETRY.json`.
 */
class TaskGpuEvidenceCapture {
  private val lock = Any()
  private val ioExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "orb-task6kf-v4-gpu-evidence-io").apply { isDaemon = true }
  }

  private var active = false
  private var stepIndex = 0
  private var outputDir: File? = null
  private var callback: ((TaskGpuCaptureProgress) -> Unit)? = null

  val isCapturing: Boolean
    get() = synchronized(lock) { active }

  fun requestCapture(
    outputDirectory: File,
    onProgress: (TaskGpuCaptureProgress) -> Unit,
  ): Boolean {
    synchronized(lock) {
      if (active) return false
      active = true
      stepIndex = 0
      outputDir = outputDirectory
      callback = onProgress
    }
    onProgress(
      TaskGpuCaptureProgress(
        state = TaskGpuCaptureState.CAPTURING,
        completedSteps = 0,
        totalSteps = CAPTURE_STEPS.size,
        currentFile = CAPTURE_STEPS.first().fileName,
        outputDirPath = outputDirectory.absolutePath,
        statusMessage = "Capturing 0/${CAPTURE_STEPS.size} V4 6KF GPU frames...",
      )
    )
    return true
  }

  fun currentStepOrNull(): TaskGpuCaptureStep? = synchronized(lock) {
    if (!active || stepIndex !in CAPTURE_STEPS.indices) null else CAPTURE_STEPS[stepIndex]
  }

  fun captureCurrentGlFrameIfActive(
    surfaceWidth: Int,
    surfaceHeight: Int,
    evidence: TaskEnergyEvidence,
    telemetry: AIOrbTelemetry,
  ) {
    val step: TaskGpuCaptureStep
    val dir: File
    val cb: ((TaskGpuCaptureProgress) -> Unit)?
    val idx: Int
    synchronized(lock) {
      if (!active || stepIndex !in CAPTURE_STEPS.indices) return
      step = CAPTURE_STEPS[stepIndex]
      dir = outputDir ?: return
      cb = callback
      idx = stepIndex
      stepIndex++
    }

    val w = surfaceWidth.coerceAtLeast(1)
    val h = surfaceHeight.coerceAtLeast(1)
    val byteCount = w * h * 4
    val buffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
    GLES30.glFinish()
    GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
    GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)

    val rawRgba = ByteArray(byteCount)
    buffer.rewind()
    buffer.get(rawRgba)

    val isLastStep = idx == CAPTURE_STEPS.lastIndex
    val completedCount = idx + 1

    ioExecutor.execute {
      try {
        if (!dir.exists()) dir.mkdirs()
        val flipped = OrbGpuReferenceCapture.flipRgbaVertically(rawRgba, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(flipped))
        FileOutputStream(File(dir, step.fileName)).use { fos ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()

        if (isLastStep) {
          File(dir, FILE_RUNTIME_TELEMETRY).writeText(
            buildTelemetryJson(evidence, telemetry, w, h),
            Charsets.UTF_8,
          )
          synchronized(lock) { active = false }
          cb?.invoke(
            TaskGpuCaptureProgress(
              state = TaskGpuCaptureState.COMPLETED,
              completedSteps = CAPTURE_STEPS.size,
              totalSteps = CAPTURE_STEPS.size,
              currentFile = step.fileName,
              outputDirPath = dir.absolutePath,
              statusMessage = "Exported ${CAPTURE_STEPS.size} GPU PNGs + telemetry JSON to ${dir.name}/",
            )
          )
        } else {
          cb?.invoke(
            TaskGpuCaptureProgress(
              state = TaskGpuCaptureState.CAPTURING,
              completedSteps = completedCount,
              totalSteps = CAPTURE_STEPS.size,
              currentFile = CAPTURE_STEPS[completedCount].fileName,
              outputDirPath = dir.absolutePath,
              statusMessage = "Captured $completedCount/${CAPTURE_STEPS.size} (${step.fileName})",
            )
          )
        }
      } catch (e: Exception) {
        synchronized(lock) { active = false }
        cb?.invoke(
          TaskGpuCaptureProgress(
            state = TaskGpuCaptureState.FAILED,
            completedSteps = idx,
            totalSteps = CAPTURE_STEPS.size,
            currentFile = step.fileName,
            outputDirPath = dir.absolutePath,
            statusMessage = "Capture failed: ${e.message ?: e.javaClass.simpleName}",
          )
        )
      }
    }
  }

  fun release() {
    synchronized(lock) { active = false }
    ioExecutor.shutdownNow()
  }

  companion object {
    const val EVIDENCE_DIR_NAME = "task6kf_v4_evidence"
    const val FILE_RUNTIME_TELEMETRY = "TASK_6KF_V4_RUNTIME_TELEMETRY.json"

    val CAPTURE_STEPS: List<TaskGpuCaptureStep> = listOf(
      TaskGpuCaptureStep("KF01_FINAL_GPU.png", TaskEnergyStage.ORB_RELEASE, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF02_FINAL_GPU.png", TaskEnergyStage.BOTTOM_CONTACT, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF03_FINAL_GPU.png", TaskEnergyStage.BOTTOM_SPLIT, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF04_FINAL_GPU.png", TaskEnergyStage.SIDE_ASCEND, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF05_FINAL_GPU.png", TaskEnergyStage.TOP_JOIN, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF06_FINAL_GPU.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF06_GEOMETRY_MAGENTA.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.GeometryMagenta),
      TaskGpuCaptureStep("KF06_ALPHA_HEATMAP.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.AlphaHeatmap),
      TaskGpuCaptureStep("KF06_LAYER_L1_ONLY.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.L1Only),
      TaskGpuCaptureStep("KF06_LAYER_L2_ONLY.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.L2Only),
      TaskGpuCaptureStep("KF06_LAYER_L3_ONLY.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.L3Only),
      TaskGpuCaptureStep("KF06_PRE_TONEMAP.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.PreTonemap),
      TaskGpuCaptureStep("KF06_FINAL_COMPOSITE.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.FinalComposite),
      TaskGpuCaptureStep("KF06_TASK_OFF_GPU.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.TaskOff),
      TaskGpuCaptureStep("KF06_TASK_ONLY_GPU.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.TaskOnly),
      TaskGpuCaptureStep("KF06_PERIMETER_ONLY_GPU.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.PerimeterOnly),
    )

    fun resolveEvidenceDir(context: Context): File {
      return context.getExternalFilesDir(EVIDENCE_DIR_NAME)
        ?: File(context.filesDir, EVIDENCE_DIR_NAME)
    }

    fun buildTelemetryJson(
      evidence: TaskEnergyEvidence,
      telemetry: AIOrbTelemetry,
      surfaceWidth: Int,
      surfaceHeight: Int,
    ): String {
      fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
      val draw = evidence.drawTelemetry
      val shaderSha = MasterFidelityV32.sha256Hex(
        (TaskWaterLightShaders.TASK_RIBBON_FRAG + "\n" + TaskWaterLightShaders.PERIMETER_RIBBON_FRAG)
          .toByteArray(Charsets.UTF_8)
      )
      return buildString {
        appendLine("{")
        appendLine("  \"schema\": \"AI_ORB_TASK_6KF_V4_RUNTIME_TELEMETRY_1_0\",")
        appendLine("  \"taskId\": \"AI_ORB_TASK_PERIMETER_6KF_FINAL_VISUAL_PARITY_V4\",")
        appendLine("  \"capturedAtUtc\": \"${utcIsoNow()}\",")
        appendLine("  \"surfaceWidth\": $surfaceWidth,")
        appendLine("  \"surfaceHeight\": $surfaceHeight,")
        appendLine("  \"glVendor\": \"${esc(telemetry.glVendor)}\",")
        appendLine("  \"glRenderer\": \"${esc(telemetry.glRenderer)}\",")
        appendLine("  \"glVersion\": \"${esc(telemetry.glVersion)}\",")
        appendLine("  \"isSoftwareRenderer\": ${telemetry.isSoftwareRenderer},")
        appendLine("  \"fboComplete\": ${draw.fboComplete},")
        appendLine("  \"fboWidth\": ${draw.fboWidth},")
        appendLine("  \"fboHeight\": ${draw.fboHeight},")
        appendLine("  \"glErrorCode\": ${draw.glErrorCode},")
        appendLine("  \"taskRibbonVertexCount\": ${draw.taskRibbonVertexCount},")
        appendLine("  \"taskRibbonDrawCalls\": ${draw.taskRibbonDrawCalls},")
        appendLine("  \"perimeterVertexCount\": ${draw.perimeterVertexCount},")
        appendLine("  \"perimeterDrawCalls\": ${draw.perimeterDrawCalls},")
        appendLine("  \"sparkleCount\": ${draw.sparkleCount},")
        appendLine("  \"sparkleUsedQuadFallback\": ${draw.sparkleUsedQuadFallback},")
        appendLine("  \"maxAliasedPointSizePx\": ${String.format(Locale.US, "%.1f", draw.maxAliasedPointSizePx)},")
        appendLine("  \"gpuResourcesReused\": ${evidence.gpuResourcesReused},")
        appendLine("  \"taskWaterLightShaderSha256\": \"$shaderSha\",")
        appendLine("  \"deviceManufacturer\": \"${esc(Build.MANUFACTURER ?: "unknown")}\",")
        appendLine("  \"deviceModel\": \"${esc(Build.MODEL ?: "unknown")}\",")
        appendLine("  \"androidSdkInt\": ${Build.VERSION.SDK_INT},")
        appendLine("  \"capturedFiles\": [")
        CAPTURE_STEPS.forEachIndexed { idx, s ->
          val comma = if (idx < CAPTURE_STEPS.lastIndex) "," else ""
          appendLine("    \"${s.fileName}\"$comma")
        }
        appendLine("  ]")
        append("}")
      }
    }

    private fun utcIsoNow(): String {
      val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
      sdf.timeZone = TimeZone.getTimeZone("UTC")
      return sdf.format(Date())
    }
  }
}
