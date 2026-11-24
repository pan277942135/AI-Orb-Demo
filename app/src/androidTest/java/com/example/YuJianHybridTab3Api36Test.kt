package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.orb.config.OrbCoreMode
import com.example.orb.config.OrbSceneMode
import com.example.orb.config.RenderDebugMode
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** API36 runtime check: the real Tab 3 pass must render in the app's single production GLSurfaceView. */
@RunWith(AndroidJUnit4::class)
class YuJianHybridTab3Api36Test {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun switchesThreeTabsAndExecutesYuJianPassSixWithoutRebuildingTheScene() {
    verifyAndroidWebpDecode()
    composeRule.waitUntil(timeoutMillis = 30_000) {
      composeRule.activity.sceneForInstrumentation()?.latestTelemetry?.glVendor != "Pending GL init"
    }
    val scene = requireNotNull(composeRule.activity.sceneForInstrumentation())
    val contextCount = scene.glContextInitCount

    val evidenceDir = File(composeRule.activity.getExternalFilesDir(null), "tab3_hybrid").apply { mkdirs() }
    composeRule.activity.setSceneForInstrumentation(
      OrbSceneMode.ORB_ONLY, RenderDebugMode.REFERENCE_LOCKED_FRAME, paused = true,
    )
    composeRule.waitUntil(timeoutMillis = 30_000) {
      scene.latestTelemetry.activeRenderMode == RenderDebugMode.REFERENCE_LOCKED_FRAME
    }
    val nativeSnapshot = scene.latestTelemetry
    capture("TAB1_NATIVE_ORB_GPU.png", evidenceDir, nativeSnapshot)
    composeRule.activity.setSceneForInstrumentation(
      OrbSceneMode.ORB_ONLY, RenderDebugMode.RIBBONS_ONLY, paused = true,
    )
    composeRule.waitUntil(timeoutMillis = 30_000) {
      scene.latestTelemetry.activeRenderMode == RenderDebugMode.RIBBONS_ONLY
    }
    capture("TAB1_OUTER_RESIDENTS_GPU.png", evidenceDir, scene.latestTelemetry)
    composeRule.activity.setSceneForInstrumentation(
      OrbSceneMode.ORB_ONLY, RenderDebugMode.REFERENCE_LOCKED_FRAME, paused = true,
    )
    composeRule.waitUntil(timeoutMillis = 30_000) {
      scene.latestTelemetry.activeRenderMode == RenderDebugMode.REFERENCE_LOCKED_FRAME
    }

    composeRule.onNodeWithTag("tab_yujian_ai_orb").performClick()
    composeRule.onNodeWithTag("yujian_hybrid_badge").assertIsDisplayed()
    composeRule.waitUntil(timeoutMillis = 30_000) {
      val telemetry = scene.latestTelemetry
      telemetry.coreRendererMode == OrbCoreMode.YUJIAN_CORE &&
        telemetry.yujianCoreStatus == "RENDERED" &&
        telemetry.yujianShaderProgramId > 0 &&
        telemetry.yujianCoreFrameCount > 0L
    }
    val hybridTelemetry = scene.latestTelemetry
    assertEquals(contextCount, scene.glContextInitCount)
    assertEquals("GL_NO_ERROR", hybridTelemetry.yujianCoreGlError)
    assertEquals("RENDERED", hybridTelemetry.yujianCoreStatus)
    assertTrue(hybridTelemetry.yujianCoreUniformsReadBackValid)
    assertTrue(hybridTelemetry.yujianCoreRefractionTextureId > 0)
    assertEquals(hybridTelemetry.yujianCoreRefractionTextureId, hybridTelemetry.yujianCoreBoundTextureId)
    assertEquals(0, hybridTelemetry.yujianCoreSamplerTextureUnit)
    assertTrue(hybridTelemetry.yujianDrawFramebufferComplete)
    assertTrue(hybridTelemetry.yujianNoFramebufferFeedbackLoop)
    assertTrue(hybridTelemetry.yujianPassStateReadBackValid)
    assertTrue(hybridTelemetry.sceneFramebufferComplete)
    assertEquals(hybridTelemetry.refractionTextureId, hybridTelemetry.yujianCoreRefractionTextureId)
    composeRule.activity.setSceneForInstrumentation(
      OrbSceneMode.YUJIAN_HYBRID, RenderDebugMode.REFERENCE_LOCKED_FRAME, paused = true,
    )
    composeRule.waitUntil(timeoutMillis = 30_000) {
      scene.latestTelemetry.coreRendererMode == OrbCoreMode.YUJIAN_CORE &&
        scene.latestTelemetry.activeRenderMode == RenderDebugMode.REFERENCE_LOCKED_FRAME &&
        scene.latestTelemetry.yujianCoreStatus == "RENDERED"
    }
    val hybridSnapshot = scene.latestTelemetry
    assertEquals(nativeSnapshot.coreAngleDeg, hybridSnapshot.coreAngleDeg, 1e-4f)
    assertEquals(nativeSnapshot.coreAngularVelDegPerSec, hybridSnapshot.coreAngularVelDegPerSec, 1e-4f)
    assertEquals(nativeSnapshot.activeRenderMode, hybridSnapshot.activeRenderMode)
    capture("TAB3_HYBRID_ORB_GPU.png", evidenceDir, hybridSnapshot)

    composeRule.activity.setSceneForInstrumentation(
      OrbSceneMode.YUJIAN_HYBRID, RenderDebugMode.CORE_ONLY_TRANSPARENT, paused = true,
    )
    composeRule.waitUntil(timeoutMillis = 30_000) {
      scene.latestTelemetry.activeRenderMode == RenderDebugMode.CORE_ONLY_TRANSPARENT &&
        scene.latestTelemetry.yujianCoreStatus == "RENDERED"
    }
    SystemClock.sleep(500)
    capture("YUJIAN_CORE_ONLY_SOURCE_SHADER_GPU.png", evidenceDir, scene.latestTelemetry)
    composeRule.activity.setSceneForInstrumentation(
      OrbSceneMode.YUJIAN_HYBRID, RenderDebugMode.RIBBONS_ONLY, paused = true,
    )
    composeRule.waitUntil(timeoutMillis = 30_000) {
      scene.latestTelemetry.activeRenderMode == RenderDebugMode.RIBBONS_ONLY
    }
    capture("TAB3_OUTER_RESIDENTS_GPU.png", evidenceDir, scene.latestTelemetry)
    composeRule.activity.setSceneForInstrumentation(OrbSceneMode.YUJIAN_HYBRID, RenderDebugMode.FULL_RENDER)
    composeRule.waitUntil(timeoutMillis = 30_000) {
      scene.latestTelemetry.activeRenderMode == RenderDebugMode.FULL_RENDER &&
        scene.latestTelemetry.yujianCoreStatus == "RENDERED"
    }

    composeRule.onNodeWithTag("tab_task_perimeter").performClick()
    composeRule.onNodeWithTag("tab_ai_orb").performClick()
    composeRule.onNodeWithTag("tab_yujian_ai_orb").performClick()
    composeRule.waitUntil(timeoutMillis = 30_000) { scene.latestTelemetry.yujianCoreFrameCount > hybridTelemetry.yujianCoreFrameCount }
    assertEquals(contextCount, scene.glContextInitCount)
    writeRuntimeManifest(evidenceDir, scene.latestTelemetry, contextCount)
  }

  @Test
  fun thirdTabKeepsTheSharedCoreClockMovingForTwentySeconds() {
    composeRule.onNodeWithTag("tab_yujian_ai_orb").performClick()
    val scene = requireNotNull(composeRule.activity.sceneForInstrumentation())
    composeRule.waitUntil(timeoutMillis = 30_000) {
      scene.latestTelemetry.coreRendererMode == OrbCoreMode.YUJIAN_CORE &&
        scene.latestTelemetry.yujianCoreStatus == "RENDERED" &&
        scene.latestTelemetry.yujianCoreFrameCount > 0L
    }
    val before = scene.latestTelemetry
    val started = SystemClock.elapsedRealtime()
    val evidenceDir = File(composeRule.activity.getExternalFilesDir(null), "tab3_hybrid").apply { mkdirs() }
    File(evidenceDir, "TAB3_MOTION_STARTED").writeText("${System.currentTimeMillis()}\n")
    SystemClock.sleep(20_500)
    val elapsedMs = SystemClock.elapsedRealtime() - started
    val after = scene.latestTelemetry
    assertTrue("Tab 3 must render while the 20-second motion interval elapses", after.yujianCoreFrameCount > before.yujianCoreFrameCount)
    assertTrue("The shared simulation clock must advance during the motion interval", after.coreAngleDeg != before.coreAngleDeg)
    assertTrue("The motion interval must be at least 20 seconds", elapsedMs >= 20_000L)
    val motion = JSONObject()
      .put("elapsedWallClockMs", elapsedMs)
      .put("frameCountBefore", before.yujianCoreFrameCount)
      .put("frameCountAfter", after.yujianCoreFrameCount)
      .put("coreAngleBeforeDeg", before.coreAngleDeg)
      .put("coreAngleAfterDeg", after.coreAngleDeg)
      .put("simulatedAngularVelocityBeforeDegPerSec", before.coreAngularVelDegPerSec)
      .put("simulatedAngularVelocityAfterDegPerSec", after.coreAngularVelDegPerSec)
      .put("isSoftwareRenderer", after.isSoftwareRenderer)
      .put("glRenderer", after.glRenderer)
      .put("status", "PASS")
    File(evidenceDir, "TAB3_MOTION_20S_RUNTIME.json").writeText(motion.toString(2) + "\n")
  }

  private fun capture(name: String, outputDir: File, telemetry: com.example.orb.scene.AIOrbTelemetry) {
    composeRule.waitForIdle()
    SystemClock.sleep(500)
    val bitmap: Bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    val file = File(outputDir, name)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    require(file.length() > 0L) { "Failed to write GPU screenshot: $name" }
    val sha = sha256(file.readBytes())
    val hashesFile = File(outputDir, "SCREENSHOT_SHA256SUMS.txt")
    hashesFile.appendText("$sha  $name\n")
    val snapshot = JSONObject()
      .put("filename", name)
      .put("buildSha", telemetry.buildSha)
      .put("activeRenderMode", telemetry.activeRenderMode.name)
      .put("coreRendererMode", telemetry.coreRendererMode.name)
      .put("coreAngleDeg", telemetry.coreAngleDeg)
      .put("coreAngularVelocityDegPerSec", telemetry.coreAngularVelDegPerSec)
      .put("isSoftwareRenderer", telemetry.isSoftwareRenderer)
      .put("glRenderer", telemetry.glRenderer)
      .put("renderResolution", telemetry.yujianCoreRenderResolution)
      .put("projectedCenterNdc", telemetry.yujianCoreProjectedCenterNdc)
      .put("projectedRadiiNdc", telemetry.yujianCoreProjectedRadiiNdc)
      .put("drawFramebufferId", telemetry.yujianDrawFramebufferId)
      .put("drawColorAttachmentTextureId", telemetry.yujianDrawColorAttachmentTextureId)
      .put("drawFramebufferComplete", telemetry.yujianDrawFramebufferComplete)
      .put("noFramebufferFeedbackLoop", telemetry.yujianNoFramebufferFeedbackLoop)
      .put("passStateReadBackValid", telemetry.yujianPassStateReadBackValid)
      .put("sceneFramebufferComplete", telemetry.sceneFramebufferComplete)
      .put("refractionTextureIdFromScene", telemetry.refractionTextureId)
      .put("cameraSnapshot", "MASTER_CAMERA_REFERENCE_LOCKED_0_0_YAW_PITCH")
    File(outputDir, name.removeSuffix(".png") + ".json").writeText(snapshot.toString(2) + "\n")
    bitmap.recycle()
  }

  private fun verifyAndroidWebpDecode() {
    val context = composeRule.activity.applicationContext
    val bytes = context.assets.open("yujian_orb_core/processing_water_core_v1.webp").use { it.readBytes() }
    assertEquals(com.example.orb.yujian.core.YuJianCoreRenderer.SOURCE_CORE_WEBP_SHA256, sha256(bytes))
    val bitmap = BitmapFactory.decodeByteArray(
      bytes,
      0,
      bytes.size,
      BitmapFactory.Options().apply { inScaled = false; inPremultiplied = false },
    )
    requireNotNull(bitmap)
    assertEquals(384, bitmap.width)
    assertEquals(384, bitmap.height)
    assertTrue(bitmap.hasAlpha())
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val rgba = ByteArray(pixels.size * 4)
    pixels.forEachIndexed { index, pixel ->
      rgba[index * 4] = ((pixel ushr 16) and 0xff).toByte()
      rgba[index * 4 + 1] = ((pixel ushr 8) and 0xff).toByte()
      rgba[index * 4 + 2] = (pixel and 0xff).toByte()
      rgba[index * 4 + 3] = ((pixel ushr 24) and 0xff).toByte()
    }
    assertEquals(com.example.orb.yujian.core.YuJianCoreRenderer.SOURCE_CORE_WEBP_RGBA_SHA256, sha256(rgba))
    bitmap.recycle()
  }

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

  private fun writeRuntimeManifest(outputDir: File, telemetry: com.example.orb.scene.AIOrbTelemetry, contextCount: Int) {
    val json = JSONObject()
      .put("androidApiLevel", android.os.Build.VERSION.SDK_INT)
      .put("buildSha", telemetry.buildSha)
      .put("apkSha256", telemetry.apkSha256 ?: "UNAVAILABLE")
      .put("glVendor", telemetry.glVendor)
      .put("glRenderer", telemetry.glRenderer)
      .put("glVersion", telemetry.glVersion)
      .put("isSoftwareRenderer", telemetry.isSoftwareRenderer)
      .put("coreRendererMode", telemetry.coreRendererMode.name)
      .put("yujianShaderProgramId", telemetry.yujianShaderProgramId)
      .put("sourceSphereShaderSha256", com.example.orb.yujian.core.YuJianCoreRenderer.SOURCE_SPHERE_SHADER_SHA256)
      .put("adaptedFragmentShaderSha256", telemetry.yujianShaderSourceSha256)
      .put("sourceCoreWebpSha256", com.example.orb.yujian.core.YuJianCoreRenderer.SOURCE_CORE_WEBP_SHA256)
      .put("sourceCoreWebpUsage", "PACKAGED_NOT_SAMPLED_BY_SOURCE_SPHERE_FS")
      .put("yujianCoreStatus", telemetry.yujianCoreStatus)
      .put("yujianCoreResolution", telemetry.yujianCoreRenderResolution)
      .put("yujianCoreFrameCount", telemetry.yujianCoreFrameCount)
      .put("contextRebuildCount", telemetry.yujianCoreContextRebuildCount)
      .put("tabSwitchContextCountBeforeAndAfter", contextCount)
      .put("uniformsReadBackValid", telemetry.yujianCoreUniformsReadBackValid)
      .put("refractionTextureId", telemetry.yujianCoreRefractionTextureId)
      .put("boundTextureId", telemetry.yujianCoreBoundTextureId)
      .put("samplerTextureUnit", telemetry.yujianCoreSamplerTextureUnit)
      .put("uHdrScaleReadBack", telemetry.yujianCoreHdrScale)
      .put("uTimeReadBack", telemetry.yujianCoreElapsedTimeSec)
      .put("projectedCenterNdc", telemetry.yujianCoreProjectedCenterNdc)
      .put("projectedRadiiNdc", telemetry.yujianCoreProjectedRadiiNdc)
      .put("glError", telemetry.yujianCoreGlError)
      .put("glErrorPass", telemetry.yujianCoreGlErrorPass)
      .put("coreAngleDeg", telemetry.coreAngleDeg)
      .put("coreAngularVelocityDegPerSec", telemetry.coreAngularVelDegPerSec)
      .put("activeRenderMode", telemetry.activeRenderMode.name)
      .put("cameraAndMotionFrozen", telemetry.activeRenderMode == RenderDebugMode.REFERENCE_LOCKED_FRAME)
    File(outputDir, "RUNTIME_TELEMETRY.json").writeText(json.toString(2) + "\n")
  }
}
