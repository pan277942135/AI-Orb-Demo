package com.example.orb.v3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.os.Build
import com.example.orb.config.AIOrbConfig
import com.example.orb.config.RenderDebugMode
import com.example.orb.gl.CoreFluidShaders
import com.example.orb.gl.RibbonOpticsShaders
import com.example.orb.scene.AIOrbMotionSnapshot
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.pow

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
 * Frozen, immutable capture state shared identically across all GPU evidence frames,
 * guaranteeing pixel-exact camera, viewport, object
 * transform, and rotation-phase alignment with zero inter-frame drift.
 */
data class OrbGpuCaptureSnapshot(
  val snapshotId: String,
  val camera: OrbCameraSnapshot,
  val motion: AIOrbMotionSnapshot,
  val outerGlassModel: FloatArray,
  val invOuterGlassModel: FloatArray,
  val cameraConfigHash: String,
  val modelTransformHash: String,
) {
  companion object {
    fun create(
      surfaceWidth: Int,
      surfaceHeight: Int,
      fidelity: MasterFidelityV32,
      motion: AIOrbMotionSnapshot,
      snapshotId: String = defaultSnapshotId(),
    ): OrbGpuCaptureSnapshot {
      val camera = OrbCameraBuilder.build(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        cameraFit = fidelity.camera,
        yawRad = 0f,
        pitchRad = 0f,
      )
      val model = FloatArray(16)
      val invModel = FloatArray(16)
      motion.writeOuterGlassModelMatrix(model)
      motion.writeInvOuterGlassModelMatrix(invModel)

      val modelCanonical = buildString {
        append("sim=").append(String.format(Locale.US, "%.6f", motion.simTimeSec))
        append("|angle=").append(String.format(Locale.US, "%.6f", motion.coreRotationAngleDeg))
        append("|fluid=").append(String.format(Locale.US, "%.6f", motion.fluidPhase))
        append("|meniscus=").append(String.format(Locale.US, "%.6f", motion.meniscusPhase))
        append("|caustics=").append(String.format(Locale.US, "%.6f", motion.causticsPhase))
        append("|ribbon=").append(String.format(Locale.US, "%.6f", motion.ribbonTimeSec))
        append("|M=")
        for (i in model.indices) {
          if (i > 0) append(',')
          append(String.format(Locale.US, "%.5f", model[i]))
        }
        append("|R=")
        for (i in motion.coreRotationMat3.indices) {
          if (i > 0) append(',')
          append(String.format(Locale.US, "%.5f", motion.coreRotationMat3[i]))
        }
      }
      val modelHash = MasterFidelityV32.sha256Hex(modelCanonical.toByteArray(Charsets.UTF_8))
      return OrbGpuCaptureSnapshot(
        snapshotId = snapshotId,
        camera = camera,
        motion = motion,
        outerGlassModel = model,
        invOuterGlassModel = invModel,
        cameraConfigHash = camera.configHash,
        modelTransformHash = modelHash,
      )
    }

    private fun defaultSnapshotId(): String {
      val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmssSSS'Z'", Locale.US)
      sdf.timeZone = TimeZone.getTimeZone("UTC")
      return "v32_snap_${sdf.format(Date())}"
    }
  }
}

/**
 * Gate 4 Production GLES 3.0 GPU Frame & Evidence Exporter.
 *
 * Driven directly from `AIOrbScene.onDrawFrame()` on the real GL thread:
 * - Locks ONE [OrbGpuCaptureSnapshot] across all 6 frames (identical camera, viewport, transform, phase)
 * - Verifies framebuffer completeness (`glCheckFramebufferStatus`) and `glGetError()` around `glReadPixels`
 * - Performs exact row-wise vertical Y-flip into lossless `Bitmap.Config.ARGB_8888` PNGs on an IO thread
 * - Re-decodes every written PNG to verify valid dimensions & computes exact per-PNG SHA-256 digests
 * - Writes `RUNTIME_RENDER_CONFIG.json`, `GPU_TELEMETRY.json`, and `BUILD_FINGERPRINT.json`
 *   including `snapshotId`, `cameraConfigHash`, `modelTransformHash`, `capturedPngSha256`, and real telemetry.
 */
class OrbGpuReferenceCapture {
  private val lock = Any()
  private val ioExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "orb-v32-gpu-evidence-io").apply { isDaemon = true }
  }

  private var active = false
  private var capturePending = false
  private var stepIndex = 0
  private var outputDir: File? = null
  private var callback: ((GpuCaptureProgress) -> Unit)? = null
  private var frozenSnapshot: OrbGpuCaptureSnapshot? = null
  private val capturedPngSha256 = LinkedHashMap<String, String>(8)
  private var measuredFoldActiveRatio: Float? = null

  val isCapturing: Boolean
    get() = synchronized(lock) { active }

  fun requestCapture(
    outputDirectory: File,
    onProgress: (GpuCaptureProgress) -> Unit,
  ): Boolean {
    synchronized(lock) {
      if (active) return false
      active = true
      capturePending = false
      stepIndex = 0
      outputDir = outputDirectory
      callback = onProgress
      frozenSnapshot = null
      capturedPngSha256.clear()
      measuredFoldActiveRatio = null
    }
    onProgress(
      GpuCaptureProgress(
        state = GpuCaptureState.CAPTURING,
        completedSteps = 0,
        totalSteps = CAPTURE_STEPS.size,
        currentFile = CAPTURE_STEPS.first().fileName,
        outputDirPath = outputDirectory.absolutePath,
        statusMessage = "Starting ${CAPTURE_STEPS.size}-frame frozen-snapshot GLES capture...",
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
 * Locks ONE [OrbGpuCaptureSnapshot] on step 0 and reuses the EXACT SAME snapshot
 * across all capture frames so every layer shares identical camera, transform, and phase.
   */
  fun getOrCreateFrozenSnapshot(
    surfaceWidth: Int,
    surfaceHeight: Int,
    fidelity: MasterFidelityV32,
    motionSupplier: () -> AIOrbMotionSnapshot,
  ): OrbGpuCaptureSnapshot = synchronized(lock) {
    val existing = frozenSnapshot
    if (existing != null) {
      existing
    } else {
      val created = OrbGpuCaptureSnapshot.create(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        fidelity = fidelity,
        motion = motionSupplier(),
      )
      frozenSnapshot = created
      created
    }
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
    snapshot: OrbGpuCaptureSnapshot? = null,
  ) {
    val step: GpuCaptureStep
    val dir: File
    val cb: ((GpuCaptureProgress) -> Unit)?
    val idx: Int
    val activeSnap: OrbGpuCaptureSnapshot?
    synchronized(lock) {
      if (!active || capturePending || stepIndex !in CAPTURE_STEPS.indices) return
      step = CAPTURE_STEPS[stepIndex]
      dir = outputDir ?: return
      cb = callback
      idx = stepIndex
      activeSnap = snapshot ?: frozenSnapshot
      capturePending = true
    }

    val w = surfaceWidth.coerceAtLeast(1)
    val h = surfaceHeight.coerceAtLeast(1)
    val byteCount = w * h * 4

    // Drain any prior unrelated error, verify default FBO completeness, and read back pixels cleanly
    while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
      // clear prior error queue before capture
    }
    val fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
    val fboComplete = fboStatus == GLES30.GL_FRAMEBUFFER_COMPLETE

    val buffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
    GLES30.glFinish()
    GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
    GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
    val readError = GLES30.glGetError()

    val rawRgba = ByteArray(byteCount)
    buffer.rewind()
    buffer.get(rawRgba)

    val isLastStep = idx == CAPTURE_STEPS.lastIndex
    val completedCount = idx + 1

    ioExecutor.execute {
      try {
        check(fboComplete) { "Framebuffer incomplete before glReadPixels (status=0x${fboStatus.toString(16)})" }
        check(readError == GLES30.GL_NO_ERROR) { "glReadPixels failed with GL error 0x${readError.toString(16)}" }
        check(telemetry.glError == "GL_NO_ERROR") { "Core rendering reported ${telemetry.glError} in ${telemetry.glErrorPass}" }
        check(rawRgba.size == byteCount) { "Unexpected pixel buffer size ${rawRgba.size} != $byteCount" }

        if (!dir.exists()) dir.mkdirs()
        val flipped = flipRgbaVertically(rawRgba, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(rgbaBytesToArgb(flipped), 0, w, 0, 0, w, h)
        val outFile = File(dir, step.fileName)
        FileOutputStream(outFile).use { fos ->
          val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
          check(ok) { "Bitmap.compress(PNG) returned false for ${step.fileName}" }
        }
        bitmap.recycle()

        // Re-decode written PNG to verify valid header, non-empty size, exact dimensions & SHA-256
        val pngSha = verifyWrittenPngAndSha256(outFile, expectedWidth = w, expectedHeight = h)
        val foldRatio = if (step.fileName == FOLD_NORMAL_FILE) measureFoldActiveRatio(outFile) else null
        val shaMapSnapshot: Map<String, String> = synchronized(lock) {
          capturedPngSha256[step.fileName] = pngSha
          if (foldRatio != null) measuredFoldActiveRatio = foldRatio
          check(stepIndex == idx) { "Capture sequence changed while ${step.fileName} was being validated" }
          stepIndex++
          capturePending = false
          LinkedHashMap(capturedPngSha256)
        }

        if (isLastStep) {
          val finalTelemetry = telemetry.copy(
            foldActiveRatio = measuredFoldActiveRatio,
            foldActiveRatioStatus = if (measuredFoldActiveRatio == null) "NOT_MEASURED" else "MEAN_PER_VISIBLE_LIQUID_RAY_FROM_FOLD_NORMAL_GPU_PNG",
          )
          check(finalTelemetry.uVolumeLoaded == 1) { "Production shader read back uVolumeLoaded=${finalTelemetry.uVolumeLoaded}, expected 1" }
          check(finalTelemetry.gpuTextureObjectsValid) { "One or more production GPU texture objects failed glIsTexture validation" }
          check(finalTelemetry.uniformStateValid) { "Production Density / Transparency / Raymarch uniforms did not match the configured values" }
          File(dir, FILE_RUNTIME_RENDER_CONFIG).writeText(
            buildRuntimeRenderConfigJson(config, fidelity, w, h, activeSnap),
            Charsets.UTF_8,
          )
          File(dir, FILE_GPU_TELEMETRY).writeText(
            buildGpuTelemetryJson(
              telemetry = finalTelemetry,
              surfaceWidth = w,
              surfaceHeight = h,
              snapshot = activeSnap,
              framebufferComplete = fboComplete,
              glErrorCode = readError,
            ),
            Charsets.UTF_8,
          )
          File(dir, FILE_BUILD_FINGERPRINT).writeText(
            buildFingerprintJson(
              fidelity = fidelity,
              telemetry = finalTelemetry,
              snapshot = activeSnap,
              capturedPngSha256 = shaMapSnapshot,
            ),
            Charsets.UTF_8,
          )
          val sumsFile = writeEvidenceShaManifest(dir, shaMapSnapshot)
          val zipFile = createEvidenceZip(dir, sumsFile)
          synchronized(lock) {
            active = false
            capturePending = false
            frozenSnapshot = null
          }
          cb?.invoke(
            GpuCaptureProgress(
              state = GpuCaptureState.COMPLETED,
              completedSteps = CAPTURE_STEPS.size,
              totalSteps = CAPTURE_STEPS.size,
              currentFile = step.fileName,
              outputDirPath = dir.absolutePath,
              statusMessage = "Exported ${CAPTURE_STEPS.size} verified GLES PNGs + 3 JSONs + ${zipFile.name} (${zipFile.length()} bytes)",
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
        synchronized(lock) {
          active = false
          capturePending = false
          frozenSnapshot = null
        }
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
    synchronized(lock) {
      active = false
      capturePending = false
      frozenSnapshot = null
    }
    ioExecutor.shutdownNow()
  }

  fun cancelForContextLoss() {
    val cb = synchronized(lock) {
      active = false
      capturePending = false
      frozenSnapshot = null
      callback
    }
    cb?.invoke(GpuCaptureProgress(
      state = GpuCaptureState.FAILED,
      totalSteps = CAPTURE_STEPS.size,
      statusMessage = "Capture cancelled: EGL context was recreated; all texture IDs and frozen GL state were invalidated.",
    ))
  }

  companion object {
    const val EVIDENCE_DIR_NAME = "v42_fidelity_evidence"
    const val EVIDENCE_ZIP_NAME = "AI_ORB_V42_GPU_FIDELITY_EVIDENCE.zip"
    const val FOLD_NORMAL_FILE = "FOLD_NORMAL_GPU.png"
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
      GpuCaptureStep("CAUSTICS_ONLY_GPU.png", RenderDebugMode.CAUSTICS_ONLY),
      GpuCaptureStep("BLOOM_OFF_GPU.png", RenderDebugMode.BLOOM_OFF),
      GpuCaptureStep(FOLD_NORMAL_FILE, RenderDebugMode.FOLD_NORMAL),
      GpuCaptureStep("VOLUME_DEPTH_GPU.png", RenderDebugMode.VOLUME_DEPTH),
      GpuCaptureStep("PRE_TONEMAP_GPU.png", RenderDebugMode.PRE_TONEMAP),
      GpuCaptureStep("POST_TONEMAP_GPU.png", RenderDebugMode.POST_TONEMAP),
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

    /** Convert GL's byte-ordered RGBA pixels to Android's explicit ARGB integer contract. */
    fun rgbaBytesToArgb(rgba: ByteArray): IntArray {
      require(rgba.size % 4 == 0) { "RGBA byte array length must be divisible by 4" }
      return IntArray(rgba.size / 4) { pixelIndex ->
        val offset = pixelIndex * 4
        val r = rgba[offset].toInt() and 0xff
        val g = rgba[offset + 1].toInt() and 0xff
        val b = rgba[offset + 2].toInt() and 0xff
        val a = rgba[offset + 3].toInt() and 0xff
        (a shl 24) or (r shl 16) or (g shl 8) or b
      }
    }

    /**
     * Verifies a written PNG file by re-decoding its bounds via [BitmapFactory], confirming
     * non-empty file size and exact [expectedWidth] x [expectedHeight], and returning its SHA-256.
     */
    fun verifyWrittenPngAndSha256(
      file: File,
      expectedWidth: Int,
      expectedHeight: Int,
    ): String {
      require(file.exists() && file.length() > 8L) {
        "Written PNG missing or empty: ${file.name}"
      }
      val decoded = BitmapFactory.decodeFile(file.absolutePath)
        ?: error("Written PNG could not be decoded: ${file.name}")
      try {
        require(decoded.width == expectedWidth && decoded.height == expectedHeight) {
          "Decoded PNG dimensions mismatch for ${file.name}: ${decoded.width}x${decoded.height} != ${expectedWidth}x${expectedHeight}"
        }
      } finally {
        decoded.recycle()
      }
      return MasterFidelityV32.sha256Hex(file.readBytes())
    }

    /** Mean per-ray fold activation decoded from the red sRGB channel of the real diagnostic frame. */
    fun measureFoldActiveRatio(file: File): Float? {
      val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
      try {
        val row = IntArray(bitmap.width)
        var activationSum = 0.0
        var rayCount = 0L
        for (y in 0 until bitmap.height) {
          bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
          for (pixel in row) {
            val red = (pixel ushr 16) and 0xff
            val green = (pixel ushr 8) and 0xff
            val blue = pixel and 0xff
            // The isolated diagnostic clears the background to black. A non-black RGB
            // value marks a visible ray with at least one density-bearing volume sample.
            if (red + green + blue > 24) {
              activationSum += (red / 255.0).pow(2.2)
              rayCount++
            }
          }
        }
        return if (rayCount > 0L) (activationSum / rayCount).toFloat() else null
      } finally {
        bitmap.recycle()
      }
    }

    private fun writeEvidenceShaManifest(dir: File, pngSha: Map<String, String>): File {
      val lines = buildList {
        pngSha.toSortedMap().forEach { (name, sha) -> add("$sha  $name") }
        EXPECTED_JSON_FILES.sorted().forEach { name ->
          val file = File(dir, name)
          add("${MasterFidelityV32.sha256Hex(file.readBytes())}  $name")
        }
      }
      return File(dir, "SHA256SUMS.txt").apply { writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8) }
    }

    private fun createEvidenceZip(dir: File, shaManifest: File): File {
      val zipFile = File(dir, EVIDENCE_ZIP_NAME)
      val include = (CAPTURE_STEPS.map { it.fileName } + EXPECTED_JSON_FILES + shaManifest.name)
        .map { File(dir, it) }
      check(include.all { it.isFile }) { "Evidence ZIP is missing files: ${include.filterNot { it.isFile }.joinToString { it.name }}" }
      ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
        val buffer = ByteArray(64 * 1024)
        include.forEach { file ->
          zip.putNextEntry(ZipEntry(file.name))
          file.inputStream().buffered().use { input ->
            while (true) {
              val n = input.read(buffer)
              if (n < 0) break
              zip.write(buffer, 0, n)
            }
          }
          zip.closeEntry()
        }
      }
      check(zipFile.length() > 0L) { "Evidence ZIP was not written" }
      return zipFile
    }

    fun buildRuntimeRenderConfigJson(
      config: AIOrbConfig,
      fidelity: MasterFidelityV32,
      surfaceWidth: Int,
      surfaceHeight: Int,
      snapshot: OrbGpuCaptureSnapshot? = null,
    ): String {
      fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
      val camSnap = snapshot?.camera ?: OrbCameraBuilder.build(
        surfaceWidth = surfaceWidth,
        surfaceHeight = surfaceHeight,
        cameraFit = fidelity.camera,
      )
      return buildString {
        appendLine("{")
      appendLine("  \"schema\": \"AI_ORB_V4_2_RUNTIME_RENDER_CONFIG_1_0\",")
        appendLine("  \"capturedAtUtc\": \"${utcIsoNow()}\",")
        appendLine("  \"snapshotId\": \"${esc(snapshot?.snapshotId ?: "UNBOUND")}\",")
        appendLine("  \"cameraConfigHash\": \"${esc(snapshot?.cameraConfigHash ?: camSnap.configHash)}\",")
        appendLine("  \"modelTransformHash\": \"${esc(snapshot?.modelTransformHash ?: "UNBOUND")}\",")
        appendLine("  \"surfaceWidth\": $surfaceWidth,")
        appendLine("  \"surfaceHeight\": $surfaceHeight,")
        appendLine("  \"captureStepModes\": {")
        CAPTURE_STEPS.forEachIndexed { index, step ->
          val comma = if (index < CAPTURE_STEPS.lastIndex) "," else ""
          appendLine("    \"${esc(step.fileName)}\": \"${step.mode.name}\"$comma")
        }
        appendLine("  },")
        appendLine("  \"cameraSnapshot\": ${camSnap.toJson().prependIndent("  ").trimStart()},")
        appendLine("  \"aiOrbConfig\": ${config.toJson().prependIndent("  ").trimStart()},")
        appendLine("  \"masterFidelityV32\": ${fidelity.toRuntimeSummaryJson().prependIndent("  ").trimStart()}")
        append("}")
      }
    }

    fun buildGpuTelemetryJson(
      telemetry: AIOrbTelemetry,
      surfaceWidth: Int,
      surfaceHeight: Int,
      snapshot: OrbGpuCaptureSnapshot? = null,
      framebufferComplete: Boolean = true,
      glErrorCode: Int = 0,
    ): String {
      fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
      fun f(v: Float): String = String.format(Locale.US, "%.3f", v)
      return buildString {
        appendLine("{")
        appendLine("  \"schema\": \"AI_ORB_V4_2_GPU_TELEMETRY_1_0\",")
        appendLine("  \"capturedAtUtc\": \"${utcIsoNow()}\",")
        appendLine("  \"snapshotId\": \"${esc(snapshot?.snapshotId ?: "UNBOUND")}\",")
        appendLine("  \"cameraConfigHash\": \"${esc(snapshot?.cameraConfigHash ?: "UNBOUND")}\",")
        appendLine("  \"modelTransformHash\": \"${esc(snapshot?.modelTransformHash ?: "UNBOUND")}\",")
        appendLine("  \"surfaceWidth\": $surfaceWidth,")
        appendLine("  \"surfaceHeight\": $surfaceHeight,")
        appendLine("  \"fps\": ${telemetry.fps},")
        appendLine("  \"latestCpuFrameMs\": ${f(telemetry.frameMsLatest)},")
        appendLine("  \"frameMsP50\": ${f(telemetry.frameMsP50)},")
        appendLine("  \"frameMsP95\": ${f(telemetry.frameMsP95)},")
        appendLine("  \"sampleCount\": ${telemetry.frameSampleCount},")
        appendLine("  \"hasSufficientSamples\": ${telemetry.hasSufficientTimingSamples},")
        appendLine("  \"gpuFrameTimeMs\": null,")
        appendLine("  \"gpuFrameTimeStatus\": \"${esc(telemetry.gpuFrameTimeStatus)}\",")
        appendLine("  \"framebufferComplete\": $framebufferComplete,")
        appendLine("  \"glErrorCode\": $glErrorCode,")
        appendLine("  \"coreAngleDeg\": ${f(telemetry.coreAngleDeg)},")
        appendLine("  \"coreAngularVelDegPerSec\": ${f(telemetry.coreAngularVelDegPerSec)},")
        appendLine("  \"isHdrFbo\": ${telemetry.isHdrFbo},")
        appendLine("  \"fboFormat\": \"${esc(telemetry.fboFormatLabel)}\",")
        appendLine("  \"renderingBackend\": \"${esc(telemetry.renderingBackendLabel)}\",")
        appendLine("  \"isSoftwareRenderer\": ${telemetry.isSoftwareRenderer},")
        appendLine("  \"glVendor\": \"${esc(telemetry.glVendor)}\",")
        appendLine("  \"glRenderer\": \"${esc(telemetry.glRenderer)}\",")
        appendLine("  \"glVersion\": \"${esc(telemetry.glVersion)}\",")
        appendLine("  \"buildSha\": \"${esc(telemetry.buildSha)}\",")
        appendLine("  \"apkSha256\": ${telemetry.apkSha256?.let { "\"${esc(it)}\"" } ?: "null"},")
        appendLine("  \"shaderProgramId\": ${telemetry.shaderProgramId},")
        appendLine("  \"shaderSourceSha256\": \"${esc(telemetry.shaderSourceSha256)}\",")
        appendLine("  \"eglVersion\": \"${esc(telemetry.eglVersion)}\",")
        appendLine("  \"volumeLoadState\": \"${telemetry.volumeLoadState.name}\",")
        appendLine("  \"volumeLoaded\": ${telemetry.volumeLoaded},")
        appendLine("  \"uVolumeLoaded\": ${telemetry.uVolumeLoaded},")
        appendLine("  \"gpuTextureObjectsValid\": ${telemetry.gpuTextureObjectsValid},")
        appendLine("  \"volumeDimensions\": \"${esc(telemetry.volumeDimensions)}\",")
        appendLine("  \"volumeTextureId\": ${telemetry.volumeTextureId},")
        appendLine("  \"detailTextureId\": ${telemetry.detailTextureId},")
        appendLine("  \"causticsTextureId\": ${telemetry.causticsTextureId},")
        appendLine("  \"glassEnvTextureId\": ${telemetry.glassEnvTextureId},")
        appendLine("  \"volumeInternalFormat\": \"${esc(telemetry.volumeInternalFormat)}\",")
        appendLine("  \"volumeIsSrgb\": ${telemetry.volumeIsSrgb},")
        appendLine("  \"webpDecodeMs\": ${f(telemetry.webpDecodeMs)},")
        appendLine("  \"texture3dUploadMs\": ${f(telemetry.texture3dUploadMs)},")
        appendLine("  \"gpuVolumeMemoryMiB\": ${f(telemetry.gpuVolumeMemoryMiB)},")
        appendLine("  \"raymarchSteps\": ${telemetry.raymarchSteps},")
        appendLine("  \"densityScale\": ${telemetry.densityScale.takeIf { it.isFinite() }?.let(::f) ?: "null"},")
        appendLine("  \"coreTransparency\": ${telemetry.coreTransparency.takeIf { it.isFinite() }?.let(::f) ?: "null"},")
        appendLine("  \"uniformStateValid\": ${telemetry.uniformStateValid},")
        appendLine("  \"foldActiveRatio\": ${telemetry.foldActiveRatio?.let(::f) ?: "null"},")
        appendLine("  \"foldActiveRatioStatus\": \"${esc(telemetry.foldActiveRatioStatus)}\",")
        appendLine("  \"renderResolution\": \"${esc(telemetry.renderResolution)}\",")
        appendLine("  \"glError\": \"${esc(telemetry.glError)}\",")
        appendLine("  \"glErrorPass\": \"${esc(telemetry.glErrorPass)}\",")
        appendLine("  \"deviceManufacturer\": \"${esc(Build.MANUFACTURER ?: "unknown")}\",")
        appendLine("  \"deviceModel\": \"${esc(Build.MODEL ?: "unknown")}\",")
        appendLine("  \"androidSdkInt\": ${Build.VERSION.SDK_INT}")
        append("}")
      }
    }

    fun buildFingerprintJson(
      fidelity: MasterFidelityV32,
      telemetry: AIOrbTelemetry,
      snapshot: OrbGpuCaptureSnapshot? = null,
      capturedPngSha256: Map<String, String> = emptyMap(),
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
        appendLine("  \"schema\": \"AI_ORB_V4_2_BUILD_FINGERPRINT_1_0\",")
        appendLine("  \"taskId\": \"AI_ORB_DEMO_V42_CORE_REAL_GPU_FIDELITY_RECOVERY_P0\",")
        appendLine("  \"buildSha\": \"${esc(telemetry.buildSha)}\",")
        appendLine("  \"apkSha256\": ${telemetry.apkSha256?.let { "\"${esc(it)}\"" } ?: "null"},")
        appendLine("  \"capturedAtUtc\": \"${utcIsoNow()}\",")
        appendLine("  \"snapshotId\": \"${esc(snapshot?.snapshotId ?: "UNBOUND")}\",")
        appendLine("  \"cameraConfigHash\": \"${esc(snapshot?.cameraConfigHash ?: "UNBOUND")}\",")
        appendLine("  \"modelTransformHash\": \"${esc(snapshot?.modelTransformHash ?: "UNBOUND")}\",")
        appendLine("  \"referenceMasterSha256\": \"${esc(fidelity.masterSha256)}\",")
        appendLine("  \"masterFidelityV32Sha256\": \"${esc(fidelity.configSha256)}\",")
        appendLine("  \"loadedFromAssets\": ${fidelity.loadedFromAssets},")
        appendLine("  \"coreShaderSha256\": \"$coreShaderSha\",")
        appendLine("  \"ribbonShaderSha256\": \"$ribbonShaderSha\",")
        appendLine("  \"selectedCavityModel\": \"${esc(fidelity.selectedCavityModel)}\",")
        appendLine("  \"selectedVolumeDepth\": ${fidelity.selectedVolumeDepth},")
        appendLine("  \"glVendor\": \"${esc(telemetry.glVendor)}\",")
        appendLine("  \"glRenderer\": \"${esc(telemetry.glRenderer)}\",")
        appendLine("  \"shaderProgramId\": ${telemetry.shaderProgramId},")
        appendLine("  \"shaderSourceSha256\": \"${esc(telemetry.shaderSourceSha256)}\",")
        appendLine("  \"uVolumeLoaded\": ${telemetry.uVolumeLoaded},")
        appendLine("  \"gpuTextureObjectsValid\": ${telemetry.gpuTextureObjectsValid},")
        appendLine("  \"uniformStateValid\": ${telemetry.uniformStateValid},")
        appendLine("  \"volumeTextureId\": ${telemetry.volumeTextureId},")
        appendLine("  \"volumeInternalFormat\": \"${esc(telemetry.volumeInternalFormat)}\",")
        appendLine("  \"capturedPngFiles\": [")
        CAPTURE_STEPS.forEachIndexed { i, step ->
          val comma = if (i < CAPTURE_STEPS.lastIndex) "," else ""
          appendLine("    \"${step.fileName}\"$comma")
        }
        appendLine("  ],")
        appendLine("  \"capturedPngSha256\": {")
        val entries = capturedPngSha256.entries.toList()
        entries.forEachIndexed { i, (name, sha) ->
          val comma = if (i < entries.lastIndex) "," else ""
          appendLine("    \"${esc(name)}\": \"${esc(sha)}\"$comma")
        }
        appendLine("  }")
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
