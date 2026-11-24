package com.example.orb.task

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.os.Build
import com.example.BuildConfig
import com.example.orb.scene.AIOrbTelemetry
import com.example.orb.v3.MasterFidelityV32
import com.example.orb.v3.OrbGpuReferenceCapture
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class TaskGpuCaptureStep(
  val fileName: String,
  val stage: TaskEnergyStage,
  val debugOptions: TaskRenderDebugOptions,
)

data class TaskGpuCaptureSnapshot(
  val snapshotId: String,
  val cameraConfigSha256: String,
  val modelTransformSha256: String,
  val motionSha256: String,
  val visualLockSha256: String,
  val viewportWidth: Int,
  val viewportHeight: Int,
) {
  companion object {
    fun create(
      viewAndProjection: FloatArray,
      modelTransform: FloatArray,
      coreRotationDeg: Float,
      motionTimeSec: Double,
      visualLockSha256: String,
      viewportWidth: Int,
      viewportHeight: Int,
    ): TaskGpuCaptureSnapshot {
      val cameraHash = hashFloats(viewAndProjection)
      val modelHash = hashFloats(modelTransform)
      val motionHash = sha256("${coreRotationDeg.toBits()}:$motionTimeSec".toByteArray(Charsets.UTF_8))
      val id = sha256(
        "$cameraHash|$modelHash|$motionHash|$visualLockSha256|$viewportWidth|$viewportHeight"
          .toByteArray(Charsets.UTF_8)
      )
      return TaskGpuCaptureSnapshot(
        snapshotId = id,
        cameraConfigSha256 = cameraHash,
        modelTransformSha256 = modelHash,
        motionSha256 = motionHash,
        visualLockSha256 = visualLockSha256,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
      )
    }

    private fun hashFloats(values: FloatArray): String {
      val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
      values.forEach { bytes.putInt(it.toBits()) }
      return sha256(bytes.array())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
      .digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }
  }
}

data class TaskGpuCaptureFrameRecord(
  val gpuFile: String,
  val gpuSha256: String,
  val gpuBytes: Long,
  val gpuWidth: Int,
  val gpuHeight: Int,
  val windowFile: String?,
  val windowSha256: String?,
  val windowWidth: Int?,
  val windowHeight: Int?,
  val stage: String,
  val debugMode: String,
  val taskDrawCalls: Int,
  val perimeterDrawCalls: Int,
  val glErrorCode: Int,
  val fboComplete: Boolean,
)

fun interface TaskWindowFrameCapture {
  fun capture(gpuFileName: String, callback: (Bitmap?, String?) -> Unit)
}

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
  private var windowFrameCapture: TaskWindowFrameCapture? = null
  private var writePending: Boolean = false
  private var frozenSnapshot: TaskGpuCaptureSnapshot? = null
  private val frameRecords = ArrayList<TaskGpuCaptureFrameRecord>(CAPTURE_STEPS.size)

  val isCapturing: Boolean
    get() = synchronized(lock) { active }

  fun requestCapture(
    outputDirectory: File,
    onProgress: (TaskGpuCaptureProgress) -> Unit,
    windowCapture: TaskWindowFrameCapture?,
    hardwareGpuAvailable: Boolean = true,
  ): Boolean {
    if (!hardwareGpuAvailable) {
      onProgress(
        TaskGpuCaptureProgress(
          state = TaskGpuCaptureState.FAILED,
          totalSteps = CAPTURE_STEPS.size,
          outputDirPath = outputDirectory.absolutePath,
          statusMessage = "PENDING_REAL_GPU_CAPTURE: software renderer detected; no evidence was written.",
        )
      )
      return false
    }
    if (windowCapture == null) {
      onProgress(
        TaskGpuCaptureProgress(
          state = TaskGpuCaptureState.FAILED,
          totalSteps = CAPTURE_STEPS.size,
          outputDirPath = outputDirectory.absolutePath,
          statusMessage = "Window capture provider is unavailable; paired GPU/Window evidence was not started.",
        )
      )
      return false
    }
    synchronized(lock) {
      if (active) return false
      active = true
      stepIndex = 0
      writePending = false
      frozenSnapshot = null
      frameRecords.clear()
      outputDir = outputDirectory
      callback = onProgress
      windowFrameCapture = windowCapture
    }
    onProgress(
      TaskGpuCaptureProgress(
        state = TaskGpuCaptureState.CAPTURING,
        completedSteps = 0,
        totalSteps = CAPTURE_STEPS.size,
        currentFile = CAPTURE_STEPS.first().fileName,
        outputDirPath = outputDirectory.absolutePath,
        statusMessage = "Capturing verified GLES frames 0/${CAPTURE_STEPS.size}...",
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
    snapshot: TaskGpuCaptureSnapshot,
  ) {
    val step: TaskGpuCaptureStep
    val dir: File
    val cb: ((TaskGpuCaptureProgress) -> Unit)?
    val idx: Int
    var snapshotMismatch = false
    synchronized(lock) {
      if (!active || writePending || stepIndex !in CAPTURE_STEPS.indices) return
      step = CAPTURE_STEPS[stepIndex]
      dir = outputDir ?: return
      cb = callback
      idx = stepIndex
      val expectedSnapshot = frozenSnapshot
      if (expectedSnapshot == null) frozenSnapshot = snapshot
      else snapshotMismatch = expectedSnapshot.snapshotId != snapshot.snapshotId
      if (snapshotMismatch) {
        // Keep the current step unchanged; failCapture reports the frozen/live hash difference.
      } else {
      writePending = true
      }
    }

    if (snapshotMismatch) {
      failCapture(
        step, dir, cb, idx,
        "frozen capture snapshot changed (expected ${frozenSnapshot?.snapshotId}, got ${snapshot.snapshotId})",
      )
      return
    }

    val w = surfaceWidth.coerceAtLeast(1)
    val h = surfaceHeight.coerceAtLeast(1)
    val fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
    val preReadError = GLES30.glGetError()
    if (fboStatus != GLES30.GL_FRAMEBUFFER_COMPLETE || preReadError != GLES30.GL_NO_ERROR) {
      failCapture(step, dir, cb, idx, "FBO=0x${Integer.toHexString(fboStatus)}, GL=0x${Integer.toHexString(preReadError)}")
      return
    }
    val byteCount = w * h * 4
    val buffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
    GLES30.glFinish()
    GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
    GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
    val readError = GLES30.glGetError()
    if (readError != GLES30.GL_NO_ERROR) {
      failCapture(step, dir, cb, idx, "glReadPixels GL error 0x${Integer.toHexString(readError)}")
      return
    }

    val rawRgba = ByteArray(byteCount)
    buffer.rewind()
    buffer.get(rawRgba)

    ioExecutor.execute {
      var bitmap: Bitmap? = null
      try {
        if (!dir.exists()) dir.mkdirs()
        val flipped = OrbGpuReferenceCapture.flipRgbaVertically(rawRgba, w, h)
        val frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap = frameBitmap
        frameBitmap.copyPixelsFromBuffer(ByteBuffer.wrap(flipped))
        val destination = File(dir, step.fileName)
        val temp = File(dir, "${step.fileName}.tmp")
        FileOutputStream(temp).use { fos ->
          check(frameBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) { "PNG encoder failed" }
          fos.fd.sync()
        }
        frameBitmap.recycle()
        bitmap = null
        val decoded = BitmapFactory.decodeFile(temp.absolutePath)
          ?: error("PNG re-decode failed for ${step.fileName}")
        val decodedWidth = decoded.width
        val decodedHeight = decoded.height
        decoded.recycle()
        check(decodedWidth == w && decodedHeight == h) {
          "PNG dimensions ${decodedWidth}x${decodedHeight} do not match captured ${w}x${h}"
        }
        if (destination.exists()) check(destination.delete()) { "Unable to replace old ${step.fileName}" }
        check(temp.renameTo(destination)) { "PNG atomic rename failed for ${step.fileName}" }
        val pngSha256 = sha256File(destination)

        var windowDimensions: Pair<Int, Int>? = null
        if (idx < KEYFRAME_COUNT) {
          val provider = synchronized(lock) { windowFrameCapture }
            ?: error("Window capture provider disappeared")
          val latch = CountDownLatch(1)
          var windowBitmap: Bitmap? = null
          var windowError: String? = null
          provider.capture(step.fileName) { image, error ->
            windowBitmap = image
            windowError = error
            latch.countDown()
          }
          check(latch.await(WINDOW_CAPTURE_TIMEOUT_SEC, TimeUnit.SECONDS)) { "PixelCopy timed out for ${step.fileName}" }
          check(windowError == null && windowBitmap != null) {
            "Window capture failed for ${step.fileName}: ${windowError ?: "empty PixelCopy bitmap"}"
          }
          val pairedBitmap = windowBitmap!!
          try {
            val windowName = windowFileName(step.fileName)
            windowDimensions = writeVerifiedPng(pairedBitmap, File(dir, windowName))
          } finally {
            pairedBitmap.recycle()
          }
        }

        val pairedWindowName = if (idx < KEYFRAME_COUNT) windowFileName(step.fileName) else null
        synchronized(lock) {
          frameRecords += TaskGpuCaptureFrameRecord(
            gpuFile = step.fileName,
            gpuSha256 = pngSha256,
            gpuBytes = destination.length(),
            gpuWidth = w,
            gpuHeight = h,
            windowFile = pairedWindowName,
            windowSha256 = pairedWindowName?.let { sha256File(File(dir, it)) },
            windowWidth = windowDimensions?.first,
            windowHeight = windowDimensions?.second,
            stage = step.stage.code,
            debugMode = step.debugOptions.mode.name,
            taskDrawCalls = evidence.drawTelemetry.taskRibbonDrawCalls,
            perimeterDrawCalls = evidence.drawTelemetry.perimeterDrawCalls,
            glErrorCode = evidence.drawTelemetry.glErrorCode,
            fboComplete = evidence.drawTelemetry.fboComplete,
          )
        }

        val completedCount: Int
        val isLastStep: Boolean
        synchronized(lock) {
          check(active && stepIndex == idx) { "Capture step changed before PNG verification" }
          stepIndex++
          writePending = false
          completedCount = stepIndex
          isLastStep = stepIndex == CAPTURE_STEPS.size
        }

        if (isLastStep) {
          val frozen = synchronized(lock) { frozenSnapshot }
            ?: error("Frozen capture snapshot disappeared before finalization")
          val records = synchronized(lock) { frameRecords.toList() }
          File(dir, FILE_RUNTIME_TELEMETRY).writeText(
            buildTelemetryJson(evidence, telemetry, w, h, fboStatus, pngSha256, frozen, records),
            Charsets.UTF_8,
          )
          writeFinalEvidenceFiles(dir, evidence, telemetry, w, h, frozen, records)
          synchronized(lock) { active = false; frozenSnapshot = null }
          cb?.invoke(
            TaskGpuCaptureProgress(
              state = TaskGpuCaptureState.COMPLETED,
              completedSteps = CAPTURE_STEPS.size,
              totalSteps = CAPTURE_STEPS.size,
              currentFile = step.fileName,
              outputDirPath = dir.absolutePath,
              statusMessage = "Exported ${CAPTURE_STEPS.size} verified GPU PNGs, manifests and ZIP to ${dir.name}/",
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
        bitmap?.recycle()
        File(dir, "${step.fileName}.tmp").delete()
        synchronized(lock) { active = false; writePending = false; frozenSnapshot = null }
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
    synchronized(lock) { active = false; writePending = false; frozenSnapshot = null }
    ioExecutor.shutdownNow()
  }

  private fun failCapture(
    step: TaskGpuCaptureStep,
    dir: File,
    cb: ((TaskGpuCaptureProgress) -> Unit)?,
    completedSteps: Int,
    reason: String,
  ) {
    synchronized(lock) { active = false; writePending = false }
    cb?.invoke(
      TaskGpuCaptureProgress(
        state = TaskGpuCaptureState.FAILED,
        completedSteps = completedSteps,
        totalSteps = CAPTURE_STEPS.size,
        currentFile = step.fileName,
        outputDirPath = dir.absolutePath,
        statusMessage = "Capture validation failed: $reason",
      )
    )
  }

  companion object {
    const val EVIDENCE_DIR_NAME = "AI_ORB_DEMO_V41_FINAL_EVIDENCE"
    const val FILE_RUNTIME_TELEMETRY = "TASK_6KF_V41_RUNTIME_TELEMETRY.json"
    private const val FILE_CAPTURE_MANIFEST = "TASK_6KF_V41_CAPTURE_MANIFEST.json"
    private const val KEYFRAME_COUNT = 6
    private const val WINDOW_CAPTURE_TIMEOUT_SEC = 15L

    val CAPTURE_STEPS: List<TaskGpuCaptureStep> = listOf(
      TaskGpuCaptureStep("KF01_FINAL_GPU.png", TaskEnergyStage.ORB_RELEASE, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF02_FINAL_GPU.png", TaskEnergyStage.BOTTOM_CONTACT, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF03_FINAL_GPU.png", TaskEnergyStage.BOTTOM_SPLIT, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF04_FINAL_GPU.png", TaskEnergyStage.SIDE_ASCEND, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF05_FINAL_GPU.png", TaskEnergyStage.TOP_JOIN, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF06_FINAL_GPU.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.Default),
      TaskGpuCaptureStep("KF06_TASK_GEOMETRY_MAGENTA.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.TaskGeometryMagenta),
      TaskGpuCaptureStep("KF06_TASK_ALPHA_HEATMAP.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.TaskAlphaHeatmap),
      TaskGpuCaptureStep("KF06_PERIMETER_GEOMETRY_MAGENTA.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.PerimeterGeometryMagenta),
      TaskGpuCaptureStep("KF06_PERIMETER_ALPHA_HEATMAP.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.PerimeterAlphaHeatmap),
      TaskGpuCaptureStep("KF06_LAYER_L1_ONLY.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.L1Only),
      TaskGpuCaptureStep("KF06_LAYER_L2_ONLY.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.L2Only),
      TaskGpuCaptureStep("KF06_LAYER_L3_ONLY.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.L3Only),
      TaskGpuCaptureStep("KF06_PERIMETER_PRE_TONEMAP.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.PerimeterOnlyPreTonemap),
      TaskGpuCaptureStep("KF06_PERIMETER_FINAL_COMPOSITE.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.PerimeterOnlyFinalComposite),
      TaskGpuCaptureStep("KF06_TASK_OFF.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.TaskOff),
      TaskGpuCaptureStep("KF06_TASK_ONLY.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.TaskOnly),
      TaskGpuCaptureStep("KF06_PERIMETER_ONLY.png", TaskEnergyStage.PERIMETER_LOOP, TaskRenderDebugOptions.PerimeterOnly),
    )

    fun windowFileName(gpuFileName: String): String = gpuFileName.replace("_FINAL_GPU.png", "_FULL_WINDOW.png")

    private fun writeVerifiedPng(bitmap: Bitmap, destination: File): Pair<Int, Int> {
      val temp = File(destination.parentFile, "${destination.name}.tmp")
      FileOutputStream(temp).use { fos ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) { "Window PNG encoder failed" }
        fos.fd.sync()
      }
      val decoded = BitmapFactory.decodeFile(temp.absolutePath) ?: error("Window PNG re-decode failed")
      val width = decoded.width
      val height = decoded.height
      decoded.recycle()
      check(width > 0 && height > 0) { "Window PNG dimensions are invalid" }
      if (destination.exists()) check(destination.delete()) { "Unable to replace ${destination.name}" }
      check(temp.renameTo(destination)) { "Window PNG atomic rename failed" }
      return width to height
    }

    fun resolveEvidenceDir(context: Context): File {
      return context.getExternalFilesDir(EVIDENCE_DIR_NAME)
        ?: File(context.filesDir, EVIDENCE_DIR_NAME)
    }

    fun buildTelemetryJson(
      evidence: TaskEnergyEvidence,
      telemetry: AIOrbTelemetry,
      surfaceWidth: Int,
      surfaceHeight: Int,
      fboStatus: Int = GLES30.GL_FRAMEBUFFER_COMPLETE,
      capturedPngSha256: String = "UNAVAILABLE",
      snapshot: TaskGpuCaptureSnapshot? = null,
      records: List<TaskGpuCaptureFrameRecord> = emptyList(),
    ): String {
      fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
      val draw = evidence.drawTelemetry
      val shaderSha = MasterFidelityV32.sha256Hex(
        (TaskWaterLightShaders.TASK_RIBBON_FRAG + "\n" + TaskWaterLightShaders.PERIMETER_RIBBON_FRAG)
          .toByteArray(Charsets.UTF_8)
      )
      return buildString {
        appendLine("{")
        appendLine("  \"schema\": \"AI_ORB_TASK_6KF_V41_RUNTIME_TELEMETRY_1_0\",")
        appendLine("  \"taskId\": \"AI_ORB_TASK_V41_FINAL_HIGH_FIDELITY\",")
        appendLine("  \"captureSnapshotId\": \"${snapshot?.snapshotId ?: "UNAVAILABLE"}\",")
        appendLine("  \"cameraConfigSha256\": \"${snapshot?.cameraConfigSha256 ?: "UNAVAILABLE"}\",")
        appendLine("  \"modelTransformSha256\": \"${snapshot?.modelTransformSha256 ?: "UNAVAILABLE"}\",")
        appendLine("  \"motionSha256\": \"${snapshot?.motionSha256 ?: "UNAVAILABLE"}\",")
        appendLine("  \"visualLockSha256\": \"${snapshot?.visualLockSha256 ?: "UNAVAILABLE"}\",")
        appendLine("  \"capturedAtUtc\": \"${utcIsoNow()}\",")
        appendLine("  \"surfaceWidth\": $surfaceWidth,")
        appendLine("  \"surfaceHeight\": $surfaceHeight,")
        appendLine("  \"glVendor\": \"${esc(telemetry.glVendor)}\",")
        appendLine("  \"glRenderer\": \"${esc(telemetry.glRenderer)}\",")
        appendLine("  \"glVersion\": \"${esc(telemetry.glVersion)}\",")
        appendLine("  \"isSoftwareRenderer\": ${telemetry.isSoftwareRenderer},")
        appendLine("  \"fboComplete\": ${draw.fboComplete},")
        appendLine("  \"captureFboStatus\": \"0x${Integer.toHexString(fboStatus)}\",")
        appendLine("  \"capturedPngSha256\": \"$capturedPngSha256\",")
        appendLine("  \"fboWidth\": ${draw.fboWidth},")
        appendLine("  \"fboHeight\": ${draw.fboHeight},")
        appendLine("  \"glErrorCode\": ${draw.glErrorCode},")
        appendLine("  \"taskRibbonVertexCount\": ${draw.taskRibbonVertexCount},")
        appendLine("  \"taskRibbonDrawCalls\": ${draw.taskRibbonDrawCalls},")
        appendLine("  \"taskRibbonDrawCallsByStream\": ${intMapJson(draw.taskRibbonDrawCallsByStream)},")
        appendLine("  \"perimeterVertexCount\": ${draw.perimeterVertexCount},")
        appendLine("  \"perimeterDrawCalls\": ${draw.perimeterDrawCalls},")
        appendLine("  \"perimeterDrawCallsByLayer\": ${intMapJson(draw.perimeterDrawCallsByLayer)},")
        appendLine("  \"sparkleCount\": ${draw.sparkleCount},")
        appendLine("  \"sparkleUsedQuadFallback\": ${draw.sparkleUsedQuadFallback},")
        appendLine("  \"maxAliasedPointSizePx\": ${String.format(Locale.US, "%.1f", draw.maxAliasedPointSizePx)},")
        appendLine("  \"gpuResourcesReused\": ${evidence.gpuResourcesReused},")
        appendLine("  \"taskWaterLightShaderSha256\": \"$shaderSha\",")
        appendLine("  \"deviceManufacturer\": \"${esc(Build.MANUFACTURER ?: "unknown")}\",")
        appendLine("  \"deviceModel\": \"${esc(Build.MODEL ?: "unknown")}\",")
        appendLine("  \"androidSdkInt\": ${Build.VERSION.SDK_INT},")
        appendLine("  \"capturedFiles\": [")
        val capturedNames = CAPTURE_STEPS.flatMapIndexed { idx, s ->
          if (idx < KEYFRAME_COUNT) listOf(s.fileName, windowFileName(s.fileName)) else listOf(s.fileName)
        }
        capturedNames.forEachIndexed { idx, name ->
          val comma = if (idx < capturedNames.lastIndex) "," else ""
          appendLine("    \"$name\"$comma")
        }
        appendLine("  ],")
        appendLine("  \"captureFrames\": [")
        records.forEachIndexed { idx, item ->
          val comma = if (idx < records.lastIndex) "," else ""
          val windowFile = item.windowFile?.let { "\"$it\"" } ?: "null"
          val windowSha = item.windowSha256?.let { "\"$it\"" } ?: "null"
          val windowDimensions = item.windowWidth?.let { "\"${it}x${item.windowHeight}\"" } ?: "null"
          appendLine("    {\"gpuFile\":\"${item.gpuFile}\",\"gpuSha256\":\"${item.gpuSha256}\",\"gpuBytes\":${item.gpuBytes},\"gpuDimensions\":\"${item.gpuWidth}x${item.gpuHeight}\",\"windowFile\":$windowFile,\"windowSha256\":$windowSha,\"windowDimensions\":$windowDimensions,\"stage\":\"${item.stage}\",\"debugMode\":\"${item.debugMode}\",\"taskDrawCalls\":${item.taskDrawCalls},\"perimeterDrawCalls\":${item.perimeterDrawCalls},\"glErrorCode\":${item.glErrorCode},\"fboComplete\":${item.fboComplete}}$comma")
        }
        appendLine("  ]")
        append("}")
      }
    }

    private fun sha256File(file: File): String = MessageDigest.getInstance("SHA-256")
      .digest(FileInputStream(file).use { it.readBytes() })
      .joinToString("") { "%02x".format(Locale.US, it) }

    private fun intMapJson(values: Map<String, Int>): String = values.entries.joinToString(
      prefix = "{",
      postfix = "}",
    ) { (key, value) -> "\"$key\":$value" }

    private fun writeFinalEvidenceFiles(
      dir: File,
      evidence: TaskEnergyEvidence,
      telemetry: AIOrbTelemetry,
      surfaceWidth: Int,
      surfaceHeight: Int,
      snapshot: TaskGpuCaptureSnapshot,
      records: List<TaskGpuCaptureFrameRecord>,
    ) {
      val layout = evidence.layoutSnapshot
      File(dir, "RUNTIME_RENDER_CONFIG.json").writeText(
        """{
          |  "schema": "AI_ORB_TASK_V41_RUNTIME_RENDER_CONFIG_1_0",
          |  "gitHead": "${BuildConfig.GIT_SHA}",
          |  "visualLockSha256": "${layout?.visualLockSha256 ?: "UNAVAILABLE"}",
          |  "backgroundAssetPath": "${TaskVisualFidelityV41.BACKGROUND_ASSET_PATH}",
          |  "backgroundLoadStatus": "${evidence.backgroundLoadStatus}",
          |  "surfaceWidth": $surfaceWidth,
          |  "surfaceHeight": $surfaceHeight,
          |  "photoFrame": {"left": ${layout?.photoFrame?.leftPx ?: 0f}, "top": ${layout?.photoFrame?.topPx ?: 0f}, "right": ${layout?.photoFrame?.rightPx ?: 0f}, "bottom": ${layout?.photoFrame?.bottomPx ?: 0f}},
          |  "orb": {"centerX": ${layout?.orbTransform?.orbCenterPx?.x ?: 0f}, "centerY": ${layout?.orbTransform?.orbCenterPx?.y ?: 0f}, "diameter": ${layout?.orbTransform?.orbDiameterPx ?: 0f}},
          |  "captureSnapshotId": "${snapshot.snapshotId}",
          |  "cameraConfigSha256": "${snapshot.cameraConfigSha256}",
          |  "modelTransformSha256": "${snapshot.modelTransformSha256}",
          |  "motionSha256": "${snapshot.motionSha256}",
          |  "captureMode": "REAL_GLES_3_0_GL_READ_PIXELS",
          |  "gpuVendor": "${telemetry.glVendor}",
          |  "gpuRenderer": "${telemetry.glRenderer}"
          |}""".trimMargin(),
        Charsets.UTF_8,
      )
      File(dir, "TASK_6KF_V41_BUILD_FINGERPRINT.json").writeText(
        """{
          |  "gitHead": "${BuildConfig.GIT_SHA}",
          |  "applicationId": "${BuildConfig.APPLICATION_ID}",
          |  "versionName": "${BuildConfig.VERSION_NAME}",
          |  "deviceFingerprint": "${Build.FINGERPRINT}",
          |  "manufacturer": "${Build.MANUFACTURER}",
          |  "model": "${Build.MODEL}",
          |  "androidRelease": "${Build.VERSION.RELEASE}",
          |  "sdkInt": ${Build.VERSION.SDK_INT},
          |  "glVendor": "${telemetry.glVendor}",
          |  "glRenderer": "${telemetry.glRenderer}",
          |  "glVersion": "${telemetry.glVersion}",
          |  "isSoftwareRenderer": ${telemetry.isSoftwareRenderer},
          |  "acceptance": "${if (telemetry.isSoftwareRenderer) "PENDING_REAL_GPU_CAPTURE" else "HARDWARE_GPU_CAPTURED"}"
          |}""".trimMargin(),
        Charsets.UTF_8,
      )
      val imageFiles = CAPTURE_STEPS.flatMapIndexed { index, step ->
        val gpu = File(dir, step.fileName)
        if (index < KEYFRAME_COUNT) listOf(gpu, File(dir, windowFileName(step.fileName))) else listOf(gpu)
      }
      check(imageFiles.all { it.isFile }) { "Cannot write manifest: one or more PNGs are missing" }
      File(dir, FILE_CAPTURE_MANIFEST).writeText(
        """{
          |  "schema": "AI_ORB_TASK_V41_CAPTURE_MANIFEST_1_0",
          |  "gitHead": "${BuildConfig.GIT_SHA}",
          |  "captureSnapshotId": "${snapshot.snapshotId}",
          |  "cameraConfigSha256": "${snapshot.cameraConfigSha256}",
          |  "modelTransformSha256": "${snapshot.modelTransformSha256}",
          |  "motionSha256": "${snapshot.motionSha256}",
          |  "visualLockSha256": "${snapshot.visualLockSha256}",
          |  "viewport": {"width": $surfaceWidth, "height": $surfaceHeight},
          |  "pngCount": ${imageFiles.size},
          |  "captureFrames": [${records.joinToString(",") { record ->
            val windowName = record.windowFile?.let { "\"$it\"" } ?: "null"
            val windowSha = record.windowSha256?.let { "\"$it\"" } ?: "null"
            val windowDimensions = record.windowWidth?.let { "\"${it}x${record.windowHeight}\"" } ?: "null"
            "{\"gpuFile\":\"${record.gpuFile}\",\"gpuSha256\":\"${record.gpuSha256}\",\"gpuBytes\":${record.gpuBytes},\"gpuDimensions\":\"${record.gpuWidth}x${record.gpuHeight}\",\"windowFile\":$windowName,\"windowSha256\":$windowSha,\"windowDimensions\":$windowDimensions,\"stage\":\"${record.stage}\",\"debugMode\":\"${record.debugMode}\",\"taskDrawCalls\":${record.taskDrawCalls},\"perimeterDrawCalls\":${record.perimeterDrawCalls},\"glErrorCode\":${record.glErrorCode},\"fboComplete\":${record.fboComplete}}"
          }}],
          |  "files": [${imageFiles.joinToString(",") { file ->
            val record = records.firstOrNull { it.gpuFile == file.name || it.windowFile == file.name }
            val dimensions = if (record?.gpuFile == file.name) "${record.gpuWidth}x${record.gpuHeight}"
              else "${record?.windowWidth ?: 0}x${record?.windowHeight ?: 0}"
            "{\"path\":\"${file.name}\",\"sha256\":\"${sha256File(file)}\",\"bytes\":${file.length()},\"dimensions\":\"$dimensions\"}"
          }}]
          |}""".trimMargin(),
        Charsets.UTF_8,
      )
      val signedFiles = imageFiles + listOf(
        File(dir, FILE_RUNTIME_TELEMETRY),
        File(dir, FILE_CAPTURE_MANIFEST),
        File(dir, "TASK_6KF_V41_BUILD_FINGERPRINT.json"),
        File(dir, "RUNTIME_RENDER_CONFIG.json"),
      )
      val sums = signedFiles.joinToString("\n", postfix = "\n") { "${sha256File(it)}  ${it.name}" }
      File(dir, "SHA256SUMS").writeText(sums, Charsets.UTF_8)
      val zip = File(dir, "AI_ORB_DEMO_V41_FINAL_EVIDENCE.zip")
      ZipOutputStream(FileOutputStream(zip).buffered()).use { out ->
        dir.listFiles()?.filter { it.isFile && it != zip }?.sortedBy { it.name }?.forEach { file ->
          out.putNextEntry(ZipEntry(file.name))
          FileInputStream(file).use { it.copyTo(out) }
          out.closeEntry()
        }
      }
      java.util.zip.ZipFile(zip).use { zipped ->
        val expected = imageFiles.map { it.name }.toSet() + setOf(
          FILE_RUNTIME_TELEMETRY, FILE_CAPTURE_MANIFEST,
          "TASK_6KF_V41_BUILD_FINGERPRINT.json", "RUNTIME_RENDER_CONFIG.json", "SHA256SUMS",
        )
        val actual = zipped.entries().asSequence().map { it.name }.toSet()
        check(actual == expected) { "Evidence ZIP entries differ from the verified evidence files" }
      }
    }

    private fun utcIsoNow(): String {
      val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
      sdf.timeZone = TimeZone.getTimeZone("UTC")
      return sdf.format(Date())
    }
  }
}
