package com.example

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.orb.v3.OrbGpuReferenceCapture
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** API 36 AVD preflight: one app restart and one complete production capture per invocation. */
@RunWith(AndroidJUnit4::class)
class P0_3Api36EvidenceTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun capturesOneP0_3BaselineAfterAppRestart() {
    val runIndex = InstrumentationRegistry.getArguments().getString("p0_3Run")?.toIntOrNull()
      ?: error("Pass instrumentation argument -e p0_3Run 1|2|3")
    require(runIndex in 1..3) { "Pass instrumentation argument -e p0_3Run 1|2|3" }
    val context = InstrumentationRegistryProvider.targetContext(composeRule.activity)
    val evidenceDir = OrbGpuReferenceCapture.resolveEvidenceDir(context)
    val repeatRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, "p0_3_repeatability")
    repeatRoot.mkdirs()

    composeRule.onNodeWithTag("toggle_debug_controls_button").performClick()
    composeRule.onNodeWithTag("volume_load_status").performScrollTo()
    composeRule.waitUntil(timeoutMillis = 180_000) {
      val volumeStatus = runCatching {
        composeRule.onNodeWithTag("volume_load_status").fetchSemanticsNode()
          .config[SemanticsProperties.Text].joinToString(" ") { it.text }
      }.getOrDefault("")
      volumeStatus.contains("WEBP3D_OK") || volumeStatus.contains("V3_ASSET_ERROR")
    }
    val volumeStatus = composeRule.onNodeWithTag("volume_load_status").fetchSemanticsNode()
      .config[SemanticsProperties.Text].joinToString(" ") { it.text }
    assertTrue("Production 3D WebP volume did not load: $volumeStatus", volumeStatus.contains("WEBP3D_OK"))
    composeRule.onNodeWithTag("export_v32_evidence_button").performScrollTo().assertIsDisplayed()

    evidenceDir.walkBottomUp().forEach { file ->
      if (file.isFile) file.delete() else if (file != evidenceDir) file.delete()
    }
    evidenceDir.mkdirs()
    composeRule.onNodeWithTag("export_v32_evidence_button").performClick()
    composeRule.waitUntil(timeoutMillis = 600_000) {
      val status = runCatching {
        composeRule.onNodeWithTag("export_v32_evidence_status").fetchSemanticsNode()
          .config[SemanticsProperties.Text].joinToString(" ") { it.text }
      }.getOrDefault("")
      status.contains("Exported") || status.contains("Capture failed")
    }
    val status = composeRule.onNodeWithTag("export_v32_evidence_status").fetchSemanticsNode()
      .config[SemanticsProperties.Text].joinToString(" ") { it.text }
    assertTrue("GPU evidence capture failed: $status", status.contains("Exported"))

    val requiredFiles = listOf(
      "CORE_ONLY_TRANSPARENT.png", "CAVITY_ONLY.png", "FLUID_VOLUME_ONLY.png", "GLASS_ONLY.png",
      "RESIDENTS_ONLY.png", "FULL_RENDER.png", "BLOOM_OFF.png", "PRE_TONEMAP.png", "POST_TONEMAP.png",
      "RUNTIME_TELEMETRY.json", "CAPTURE_MANIFEST.json", OrbGpuReferenceCapture.EVIDENCE_ZIP_NAME,
    )
    requiredFiles.forEach { name ->
      val file = File(evidenceDir, name)
      assertTrue("Missing GPU evidence file: ${file.absolutePath}", file.isFile && file.length() > 0L)
    }
    val telemetry = JSONObject(File(evidenceDir, "RUNTIME_TELEMETRY.json").readText())
    assertTrue("Runtime telemetry must name the actual GL renderer", telemetry.getString("glRenderer").isNotBlank())
    assertTrue("Runtime telemetry must contain actual GL geometry", telemetry.getInt("geometrySubdivisions") == 4)
    assertTrue("HIGH quality must be recorded", telemetry.getString("qualityTier") == "HIGH")
    assertTrue("Configured HIGH raymarch steps must be 48", telemetry.getInt("configuredRaymarchSteps") == 48)

    val manifest = JSONObject(File(evidenceDir, "CAPTURE_MANIFEST.json").readText())
    assertTrue("Capture manifest must be complete", manifest.getString("status") == "COMPLETE")
    assertTrue(manifest.getJSONObject("captureValidation").getBoolean("allRequiredModesPresent"))
    assertTrue(manifest.getJSONObject("captureValidation").getBoolean("allRequiredModeEvidencePresent"))
    assertTrue(manifest.getJSONObject("volume").getBoolean("all3DTexturesValid"))
    assertTrue(manifest.getJSONObject("volume").getInt("uVolumeLoaded") == 1)
    assertTrue(manifest.getInt("effectiveRaymarchSteps") in 1..48)
    assertTrue(manifest.getJSONObject("geometry").getInt("vertexCount") == 2562)
    assertTrue(manifest.getJSONObject("geometry").getInt("triangleCount") == 5120)
    assertTrue(manifest.getJSONObject("geometry").getString("geometryFingerprintSha256").length == 64)

    val requiredModes = listOf(
      "CORE_ONLY_TRANSPARENT", "CAVITY_ONLY", "FLUID_VOLUME_ONLY", "GLASS_ONLY", "RESIDENTS_ONLY",
      "FULL_RENDER", "BLOOM_OFF", "PRE_TONEMAP", "POST_TONEMAP",
    )
    val frames = manifest.getJSONArray("frames")
    assertTrue(frames.length() >= requiredModes.size)
    requiredModes.forEachIndexed { index, mode ->
      val frame = frames.getJSONObject(index)
      assertTrue("Contract mode order mismatch at $index", frame.getString("mode") == mode)
      val config = frame.getJSONObject("modeConfig")
      assertTrue(config.getString("renderDebugMode") == mode)
      assertTrue(config.getString("quality") == "HIGH")
      val runtime = frame.getJSONObject("runtime")
      assertTrue("Framebuffer incomplete for $mode", runtime.getBoolean("framebufferComplete"))
      assertTrue("GL error for $mode", runtime.getInt("readbackGlErrorCode") == 0)
      assertTrue("GL runtime error for $mode", runtime.getString("glError") == "GL_NO_ERROR")
      assertTrue("Wrong shader quality for $mode", runtime.getString("qualityTier") == "HIGH")
      val png = File(evidenceDir, frame.getString("file"))
      val sha = MessageDigest.getInstance("SHA-256").digest(png.readBytes())
        .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
      assertTrue("PNG hash mismatch for $mode", sha == frame.getString("sha256"))
      assertTrue("PNG dimensions mismatch for $mode", frame.getInt("width") == 1254 && frame.getInt("height") == 1254)
      if (frame.getBoolean("coreShaderExecuted")) {
        val uniforms = frame.getJSONObject("actualUniformSnapshot")
        val software = runtime.getBoolean("isSoftwareRenderer")
        val expectedSteps = if (software) minOf(48, 20) else 48
        assertTrue("Shader uRaymarchSteps mismatch for $mode", uniforms.getInt("uRaymarchSteps") == expectedSteps)
        assertTrue("Shader mode uniform mismatch for $mode", uniforms.getInt("uDebugMode") == config.getInt("shaderDebugModeId"))
        assertTrue("Uniform validation failed for $mode", uniforms.getBoolean("uniformStateValid"))
      }
    }

    val runDir = File(repeatRoot, "baseline_$runIndex")
    runDir.mkdirs()
    evidenceDir.listFiles().orEmpty().filter { it.isFile }.forEach { it.copyTo(File(runDir, it.name), overwrite = true) }
    JSONObject()
      .put("runIndex", runIndex)
      .put("startedAtUtc", Instant.now().toString())
      .put("packageName", context.packageName)
      .put("buildSha", manifest.getString("buildSha"))
      .put("apkSha256", manifest.getString("apkSha256"))
      .put("androidApiLevel", android.os.Build.VERSION.SDK_INT)
      .put("instrumentationProcessId", android.os.Process.myPid())
      .writeTo(File(runDir, "run_info.json"))
    requiredModes.forEachIndexed { index, mode ->
      val modeDir = File(runDir, "captures/mode_${(index + 1).toString().padStart(2, '0')}")
      modeDir.mkdirs()
      val frame = frames.getJSONObject(index)
      File(evidenceDir, frame.getString("file")).copyTo(File(modeDir, frame.getString("file")), overwrite = true)
      File(modeDir, "mode.json").writeText(frame.toString(2))
    }
    composeRule.onNodeWithTag("close_debug_controls_button").performScrollTo().performClick()
  }
}

private fun JSONObject.writeTo(file: File) {
  file.writeText(toString(2))
}

/** Avoid leaking an instrumentation context through a static test singleton. */
private object InstrumentationRegistryProvider {
  fun targetContext(activity: MainActivity) = activity.applicationContext
}
