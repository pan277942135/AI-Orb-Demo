package com.example.orb.v3

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.os.Build
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.RenderDebugMode
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.gl.RibbonOpticsShaders
import com.example.orb.scene.AIOrbTelemetry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

data class GpuCaptureStep(
  val fileName: String,
  val mode: RenderDebugMode,
)

enum class GpuCaptureState {
  IDLE,
  CAPTURING,
  COMPLETED,
  FAILED,
}

data class GpuCaptureProgress(
  val state: GpuCaptureState = GpuCaptureState.IDLE,
  val completedSteps: Int = 0,
  val totalSteps: Int = OrbGpuReferenceCapture.CAPTURE_STEPS.size,
  val currentFile: String = "",
  val outputDirPath: String = "",
  val statusMessage: String = "",
)

/**
 * Gate 4 Production GLES 3.0 GPU Frame & Evidence Exporter.
 *
 * Driven directly from `AIOrbScene.onDrawFrame()` on the real GL thread:
 * - Captures 6 zero-UI production frames via `GLES30.glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf)`
 * - Performs exact row-wise vertical Y-flip into lossless `Bitmap.Config.ARGB_8888` PNGs on a background IO thread
 * - Writes `RUNTIME_RENDER_CONFIG.json`, `GPU_TELEMETRY.json`, and `BUILD_FINGERPRINT.json`
 *   to `context.getExternalFilesDir("v32_fidelity_evidence") ?: File(context.filesDir, "v32_fidelity_evidence")`.
 */
class OrbGpuReferenceCapture {
  private val lock = Any()
  private val ioExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "orb-v32-gpu-evidence-io").apply { isDaemon = true }
  }

  private var active = false
  private var stepIndex = 0
  private var outputDir: File? = null
  private var callback: ((GpuCaptureProgress) -> Unit)? = null
  private var capturedMetaFiles = ArrayList<String>(10)

  val isCapturing: Boolean
    get() = synchronized(lock) { active }

  fun requestCapture(
    outputDirectory: File,
    onProgress: (GpuCaptureProgress) -> Unit,
  ): Boolean {
    synchronized(lock) {
      if (active) return false
      active = true
      stepIndex = 0
      outputDir = outputDirectory
      callback = onProgress
      capturedMetaFiles.clear()
    }
    onProgress(
      GpuCaptureProgress(
        state = GpuCaptureState.CAPTURING,
        completedSteps = 0,
        totalSteps = CAPTURE_STEPS.size,
        currentFile = CAPTURE_STEPS.first().fileName,
        outputDirPath = outputDirectory.absolutePath,
        statusMessage = "Starting 6-frame GPU capture...",
      )
    )
    return true
  }

  /**
   * Called on the GL thread BEFORE rendering a frame. If a capture sequence is active,
   * returns the [RenderDebugMode] required for the current capture step; otherwise returns null.
   */
  fun currentOverrideModeOrNull(): RenderDebugMode? = synchronized(lock) {
    if (!active || stepIndex !in CAPTURE_STEPS.indices) null else CAPTURE_STEPS[stepIndex].mode
  }

  /**
   * Called on the GL thread IMMEDIATELY AFTER `postProcessing.executePostProcessToScreen(...)`
   * while the default framebuffer holds the final zero-UI rendered frame.
   */
  fun captureCurrentGlFrameIfActive(
    surfaceWidth: Int,
    surfaceHeight: Int,
    config: AIOrbConfig,
    fidelity: MasterFidelityV32,
    telemetry: AIOrbTelemetry,
  ) {
    val step: GpuCaptureStep
    val dir: File
    val cb: ((GpuCaptureProgress) -> Unit)?
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
        val flipped = flipRgbaVertically(rawRgba, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(flipped))
        val outFile = File(dir, step.fileName)
        FileOutputStream(outFile).use { fos ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()

        if (isLastStep) {
          File(dir, FILE_RUNTIME_RENDER_CONFIG).writeText(
            buildRuntimeRenderConfigJson(config, fidelity, w, h),
            Charsets.UTF_8,
          )
          File(dir, FILE_GPU_TELEMETRY).writeText(
            buildGpuTelemetryJson(telemetry, w, h),
            Charsets.UTF_8,
          )
          File(dir, FILE_BUILD_FINGERPRINT).writeText(
            buildFingerprintJson(fidelity, telemetry),
            Charsets.UTF_8,
          )
          synchronized(lock) { active = false }
          cb?.invoke(
            GpuCaptureProgress(
              state = GpuCaptureState.COMPLETED,
              completedSteps = CAPTURE_STEPS.size,
              totalSteps = CAPTURE_STEPS.size,
              currentFile = step.fileName,
              outputDirPath = dir.absolutePath,
              statusMessage = "Exported 6 GPU PNGs + 3 JSONs to ${dir.name}/",
            )
          )
        } else {
          cb?.invoke(
            GpuCaptureProgress(
              state = GpuCaptureState.CAPTURING,
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
          GpuCaptureProgress(
            state = GpuCaptureState.FAILED,
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
    const val EVIDENCE_DIR_NAME = "v32_fidelity_evidence"
    const val FILE_RUNTIME_RENDER_CONFIG = "RUNTIME_RENDER_CONFIG.json"
    const val FILE_GPU_TELEMETRY = "GPU_TELEMETRY.json"
    const val FILE_BUILD_FINGERPRINT = "BUILD_FINGERPRINT.json"

    val CAPTURE_STEPS: List<GpuCaptureStep> = listOf(
      GpuCaptureStep("REFERENCE_LOCKED_FRAME_GPU.png", RenderDebugMode.REFERENCE_LOCKED_FRAME),
      GpuCaptureStep("CAVITY_ONLY_GPU.png", RenderDebugMode.CAVITY_ONLY),
      GpuCaptureStep("FLUID_VOLUME_ONLY_GPU.png", RenderDebugMode.FLUID_VOLUME_ONLY),
      GpuCaptureStep("RIBBONS_ONLY_GPU.png", RenderDebugMode.RIBBONS_ONLY),
      GpuCaptureStep("GLASS_ONLY_GPU.png", RenderDebugMode.GLASS_ONLY),
      GpuCaptureStep("FULL_RENDER_GPU.png", RenderDebugMode.FULL_RENDER),
    )

    val EXPECTED_JSON_FILES: List<String> = listOf(
      FILE_RUNTIME_RENDER_CONFIG,
      FILE_GPU_TELEMETRY,
      FILE_BUILD_FINGERPRINT,
    )

    fun resolveEvidenceDir(context: Context): File {
      return context.getExternalFilesDir(EVIDENCE_DIR_NAME)
        ?: File(context.filesDir, EVIDENCE_DIR_NAME)
    }

    /**
     * Exact row-wise vertical Y-flip from OpenGL bottom-left origin to PNG/Bitmap top-left origin.
     * Pure function for deterministic JVM unit verification.
     */
    fun flipRgbaVertically(rgba: ByteArray, width: Int, height: Int): ByteArray {
      val rowBytes = width * 4
      require(rgba.size >= rowBytes * height) {
        "RGBA buffer too small: ${rgba.size} < ${rowBytes * height}"
      }
      val out = ByteArray(rowBytes * height)
      for (y in 0 until height) {
        val srcOffset = (height - 1 - y) * rowBytes
        val dstOffset = y * rowBytes
        System.arraycopy(rgba, srcOffset, out, dstOffset, rowBytes)
      }
      return out
    }

    fun buildRuntimeRenderConfigJson(
      config: AIOrbConfig,
      fidelity: MasterFidelityV32,
      surfaceWidth: Int,
      surfaceHeight: Int,
    ): String {
      return buildString {
        appendLine("{")
        appendLine("  \"schema\": \"AI_ORB_V3_2_RUNTIME_RENDER_CONFIG_1_0\",")
        appendLine("  \"capturedAtUtc\": \"${utcIsoNow()}\",")
        appendLine("  \"surfaceWidth\": $surfaceWidth,")
        appendLine("  \"surfaceHeight\": $surfaceHeight,")
        appendLine("  \"aiOrbConfig\": ${config.toJson().prependIndent("  ").trimStart()},")
        appendLine("  \"masterFidelityV32\": ${fidelity.toRuntimeSummaryJson().prependIndent("  ").trimStart()}")
        append("}")
      }
    }

    fun buildGpuTelemetryJson(
      telemetry: AIOrbTelemetry,
      surfaceWidth: Int,
      surfaceHeight: Int,
    ): String {
      fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
      fun f(v: Float): String = String.format(Locale.US, "%.3f", v)
      return buildString {
        appendLine("{")
        appendLine("  \"schema\": \"AI_ORB_V3_2_GPU_TELEMETRY_1_0\",")
        appendLine("  \"capturedAtUtc\": \"${utcIsoNow()}\",")
        appendLine("  \"surfaceWidth\": $surfaceWidth,")
        appendLine("  \"surfaceHeight\": $surfaceHeight,")
        appendLine("  \"fps\": ${telemetry.fps},")
        appendLine("  \"frameMsP50\": ${f(telemetry.frameMsP50)},")
        appendLine("  \"frameMsP95\": ${f(telemetry.frameMsP95)},")
        appendLine("  \"coreAngleDeg\": ${f(telemetry.coreAngleDeg)},")
        appendLine("  \"coreAngularVelDegPerSec\": ${f(telemetry.coreAngularVelDegPerSec)},")
        appendLine("  \"isHdrFbo\": ${telemetry.isHdrFbo},")
        appendLine("  \"fboFormat\": \"${esc(telemetry.fboFormatLabel)}\",")
        appendLine("  \"renderingBackend\": \"${esc(telemetry.renderingBackendLabel)}\",")
        appendLine("  \"isSoftwareRenderer\": ${telemetry.isSoftwareRenderer},")
        appendLine("  \"glVendor\": \"${esc(telemetry.glVendor)}\",")
        appendLine("  \"glRenderer\": \"${esc(telemetry.glRenderer)}\",")
        appendLine("  \"glVersion\": \"${esc(telemetry.glVersion)}\",")
        appendLine("  \"eglVersion\": \"${esc(telemetry.eglVersion)}\",")
        appendLine("  \"volumeLoadState\": \"${telemetry.volumeLoadState.name}\",")
        appendLine("  \"volumeLoaded\": ${telemetry.volumeLoaded},")
        appendLine("  \"volumeDimensions\": \"${esc(telemetry.volumeDimensions)}\",")
        appendLine("  \"webpDecodeMs\": ${f(telemetry.webpDecodeMs)},")
        appendLine("  \"texture3dUploadMs\": ${f(telemetry.texture3dUploadMs)},")
        appendLine("  \"gpuVolumeMemoryMiB\": ${f(telemetry.gpuVolumeMemoryMiB)},")
        appendLine("  \"deviceManufacturer\": \"${esc(Build.MANUFACTURER ?: "unknown")}\",")
        appendLine("  \"deviceModel\": \"${esc(Build.MODEL ?: "unknown")}\",")
        appendLine("  \"androidSdkInt\": ${Build.VERSION.SDK_INT}")
        append("}")
      }
    }

    fun buildFingerprintJson(
      fidelity: MasterFidelityV32,
      telemetry: AIOrbTelemetry,
    ): String {
      fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
      val coreShaderSha = MasterFidelityV32.sha256Hex(
        (CoreFluidShaders.CORE_GLASS_VERT + "\n" + CoreFluidShaders.CORE_GLASS_FRAG).toByteArray(Charsets.UTF_8)
      )
      val ribbonShaderSha = MasterFidelityV32.sha256Hex(
        (RibbonOpticsShaders.RIBBON_VERT + "\n" + RibbonOpticsShaders.RIBBON_FRAG).toByteArray(Charsets.UTF_8)
      )
      return buildString {
        appendLine("{")
        appendLine("  \"schema\": \"AI_ORB_V3_2_BUILD_FINGERPRINT_1_0\",")
        appendLine("  \"taskId\": \"AI_ORB_V3_2_REFERENCE_TO_3D_FINAL_FIDELITY_P0\",")
        appendLine("  \"capturedAtUtc\": \"${utcIsoNow()}\",")
        appendLine("  \"referenceMasterSha256\": \"${esc(fidelity.masterSha256)}\",")
        appendLine("  \"masterFidelityV32Sha256\": \"${esc(fidelity.configSha256)}\",")
        appendLine("  \"loadedFromAssets\": ${fidelity.loadedFromAssets},")
        appendLine("  \"coreShaderSha256\": \"$coreShaderSha\",")
        appendLine("  \"ribbonShaderSha256\": \"$ribbonShaderSha\",")
        appendLine("  \"selectedCavityModel\": \"${esc(fidelity.selectedCavityModel)}\",")
        appendLine("  \"selectedVolumeDepth\": ${fidelity.selectedVolumeDepth},")
        appendLine("  \"glVendor\": \"${esc(telemetry.glVendor)}\",")
        appendLine("  \"glRenderer\": \"${esc(telemetry.glRenderer)}\",")
        appendLine("  \"capturedPngFiles\": [")
        CAPTURE_STEPS.forEachIndexed { i, step ->
          val comma = if (i < CAPTURE_STEPS.lastIndex) "," else ""
          appendLine("    \"${step.fileName}\"$comma")
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
