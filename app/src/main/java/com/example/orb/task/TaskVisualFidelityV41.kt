package com.example.orb.task

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The single V4.1 visual contract shared by Compose, the GL layout, Task Energy,
 * Perimeter WaterLight, diagnostics and evidence. Values are loaded from the packaged
 * JSON; a malformed/missing lock is a hard error and is never silently replaced.
 */
data class TaskVisualFidelityV41(
  val background: BackgroundLock,
  val referenceMasterSha256: String,
  val v32MasterConfigSha256: String,
  val referenceAspect: Float,
  val topBar: HeaderLock,
  val photo: PhotoLock,
  val statusText: NormalizedPoint,
  val statusTextColorHex: String,
  val orb: OrbLock,
  val keyframes: KeyframeLock,
  val taskStreams: List<TaskStreamSpec>,
  val perimeter: PerimeterLock,
  val tab2Optics: Tab2OpticsLock,
  val output: OutputLock,
  val lockSha256: String,
) {
  data class BackgroundLock(
    val assetPath: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val fit: String,
    val causticsGain: Float,
  )

  data class NormalizedPoint(val x: Float, val y: Float)

  data class HeaderLock(val backX: Float, val titleY: Float, val textColorHex: String)

  data class PhotoLock(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val width: Float,
    val height: Float,
    val aspectRatio: Float,
    val cornerRadiusOfWidth: Float,
  )

  data class OrbLock(
    val centerX: Float,
    val centerY: Float,
    val diameterOfWidth: Float,
    val topAnchorY: Float,
    val gapToPhotoBottom: Float,
  )

  data class KeyframeLock(
    val kf01FrontRatio: Float,
    val kf04SideAscendRatio: Float,
    val kf02CaptureMs: Long,
    val kf03CaptureMs: Long,
    val kf05CaptureMs: Long,
    val kf06CaptureMs: Long,
    val contactStartMs: Double,
    val splitStartMs: Double,
    val ascendStartMs: Double,
    val topJoinStartMs: Double,
    val loopStartMs: Double,
    val kf05JoinFraction: Float,
  )

  data class PerimeterLock(
    val l1MinWidth: Float,
    val l1MaxWidth: Float,
    val localFlareMin: Float,
    val localFlareMax: Float,
    val l1AlphaMin: Float,
    val l1AlphaMax: Float,
    val l2AlphaMin: Float,
    val l2AlphaMax: Float,
    val l3Width: Float,
    val l3AlphaMin: Float,
    val l3AlphaMax: Float,
    val l1AlphaScale: Float,
    val l2AlphaScale: Float,
    val l3AlphaScale: Float,
    val l4DropletsPerSide: Int,
  )

  data class Tab2OpticsLock(
    val cameraFovYDeg: Float,
    val interiorTransmission: Float,
    val absorptionGain: Float,
    val hdrScale: Float,
    val bloomThreshold: Float,
    val acesExposure: Float,
  )

  data class OutputLock(
    val captureWidth: Int,
    val captureHeight: Int,
    val requireHardwareGpu: Boolean,
  )

  /** Maps the frozen 9:16 reference canvas into the actual surface without nonuniform scaling. */
  data class AspectFit(
    val scalePx: Float,
    val offsetXPx: Float,
    val offsetYPx: Float,
    val referenceHeightUnits: Float,
  ) {
    fun x(xNorm: Float): Float = offsetXPx + xNorm * scalePx
    fun y(yNorm: Float): Float = offsetYPx + yNorm * referenceHeightUnits * scalePx
    fun width(widthNorm: Float): Float = widthNorm * scalePx
    fun height(heightNorm: Float): Float = heightNorm * referenceHeightUnits * scalePx
  }

  fun aspectFit(surfaceWidth: Int, surfaceHeight: Int): AspectFit {
    val w = surfaceWidth.coerceAtLeast(1).toFloat()
    val h = surfaceHeight.coerceAtLeast(1).toFloat()
    val refH = 1f / referenceAspect
    val scale = minOf(w, h / refH)
    return AspectFit(
      scalePx = scale,
      offsetXPx = (w - scale) * 0.5f,
      offsetYPx = (h - refH * scale) * 0.5f,
      referenceHeightUnits = refH,
    )
  }

  fun canonicalPhoto(surfaceWidth: Int, surfaceHeight: Int): PhotoFrameGeometry {
    val fit = aspectFit(surfaceWidth, surfaceHeight)
    return PhotoFrameGeometry(
      leftPx = fit.x(photo.left),
      topPx = fit.y(photo.top),
      rightPx = fit.x(photo.right),
      bottomPx = fit.y(photo.bottom),
      cornerRadiusPx = fit.width(photo.width) * photo.cornerRadiusOfWidth,
    )
  }

  fun canonicalOrb(surfaceWidth: Int, surfaceHeight: Int): OrbViewportTransform {
    val fit = aspectFit(surfaceWidth, surfaceHeight)
    return OrbViewportTransform(
      orbCenterPx = Vec2(fit.x(orb.centerX), fit.y(orb.centerY)),
      orbDiameterPx = fit.width(orb.diameterOfWidth),
      surfaceWidthPx = surfaceWidth,
      surfaceHeightPx = surfaceHeight,
    )
  }

  companion object {
    const val ASSET_PATH = "task_demo/TASK_6KF_VISUAL_LOCK_V41.json"
    const val BACKGROUND_ASSET_PATH = "task_demo/background_lake_glass_v41.png"
    const val BACKGROUND_SHA256 = "f3dc1549919f48f939df5a6cf5baf18fb6846164c6f857df8e20cfe247b495ba"
    const val REFERENCE_MASTER_SHA256 = "01802259dc2ad6da30b7df23abc01597041342e171cfb34c29aee376b9055d0c"
    const val V32_MASTER_CONFIG_SHA256 = "e1b00b49565a3bfb4932b1b970e98da92e10028fadcbe0de9dbf74695c00bb7a"

    fun loadFromAssets(assetManager: AssetManager?): TaskVisualFidelityV41 {
      requireNotNull(assetManager) { "V4.1 visual lock cannot load without AssetManager" }
      val bytes = assetManager.open(ASSET_PATH).use { it.readBytes() }
      val json = JSONObject(bytes.toString(Charsets.UTF_8))
      require(json.getString("schemaVersion") == "AI_ORB_TASK_6KF_VISUAL_LOCK_V41_1_0") {
        "Unsupported V4.1 visual lock schema"
      }
      val hash = sha256(bytes)
      val backgroundJson = json.getJSONObject("background")
      val referenceJson = json.getJSONObject("reference")
      val layoutJson = json.getJSONObject("layout")
      val topBarJson = layoutJson.getJSONObject("topBar")
      val photoJson = layoutJson.getJSONObject("photo")
      val statusJson = layoutJson.getJSONObject("statusText")
      val orbJson = layoutJson.getJSONObject("orb")
      val kfJson = json.getJSONObject("keyframes")
      val perimeterJson = json.getJSONObject("perimeter")
      val opticsJson = json.getJSONObject("tab2Optics")
      val outputJson = json.getJSONObject("capture")

      val streamsArray = json.getJSONArray("taskStreams")
      val streams = (0 until streamsArray.length()).map { index ->
        val item = streamsArray.getJSONObject(index)
        val points = item.getJSONArray("controlPointsNorm").toVec2List()
        val widths = item.getJSONArray("widthProfile").toFloatArray()
        TaskStreamSpec(
          id = item.getString("id"),
          kind = TaskStreamKind.valueOf(item.getString("kind")),
          controlPointsNorm = points,
          baseHalfWidthPxRatio = item.getDouble("baseHalfWidthOfOrbDiameter").toFloat(),
          widthProfile = widths,
          twistCycles = item.getDouble("twistCycles").toFloat(),
          flowSpeed = item.getDouble("flowSpeed").toFloat(),
          alphaScale = item.getDouble("alphaScale").toFloat(),
          depthOffset = item.getDouble("depthOffset").toFloat(),
          reachFraction = item.getDouble("reachFraction").toFloat(),
        )
      }
      require(streams.size == 4 && streams.map { it.id }.distinct().size == 4) {
        "V4.1 lock must contain four unique Task streams"
      }
      require(referenceJson.getString("masterSha256") == REFERENCE_MASTER_SHA256) {
        "Frozen KF master SHA in V4.1 lock does not match"
      }
      require(referenceJson.getString("v32MasterConfigSha256") == V32_MASTER_CONFIG_SHA256) {
        "V3.2 Master config SHA in V4.1 lock does not match"
      }
      require(backgroundJson.getString("assetPath").isNotBlank() &&
        backgroundJson.getInt("width") > 0 && backgroundJson.getInt("height") > 0) {
        "V4.1 user-supplied background path and source dimensions are required"
      }

      val lock = TaskVisualFidelityV41(
        background = BackgroundLock(
          assetPath = backgroundJson.getString("assetPath"),
          sha256 = backgroundJson.getString("sha256"),
          width = backgroundJson.getInt("width"),
          height = backgroundJson.getInt("height"),
          fit = backgroundJson.getString("fit"),
          causticsGain = backgroundJson.getDouble("causticsGain").toFloat(),
        ),
        referenceMasterSha256 = referenceJson.getString("masterSha256"),
        v32MasterConfigSha256 = referenceJson.getString("v32MasterConfigSha256"),
        referenceAspect = layoutJson.getDouble("referenceAspect").toFloat(),
        topBar = HeaderLock(
          backX = topBarJson.getDouble("backX").toFloat(),
          titleY = topBarJson.getDouble("titleY").toFloat(),
          textColorHex = topBarJson.getString("textColor"),
        ),
        photo = PhotoLock(
          left = photoJson.getDouble("left").toFloat(),
          top = photoJson.getDouble("top").toFloat(),
          right = photoJson.getDouble("right").toFloat(),
          bottom = photoJson.getDouble("bottom").toFloat(),
          width = photoJson.getDouble("width").toFloat(),
          height = photoJson.getDouble("height").toFloat(),
          aspectRatio = photoJson.getDouble("aspectRatio").toFloat(),
          cornerRadiusOfWidth = photoJson.getDouble("cornerRadiusOfPhotoWidth").toFloat(),
        ),
        statusText = NormalizedPoint(
          x = statusJson.getDouble("centerX").toFloat(),
          y = statusJson.getDouble("centerY").toFloat(),
        ),
        statusTextColorHex = statusJson.getString("textColor"),
        orb = OrbLock(
          centerX = orbJson.getDouble("centerX").toFloat(),
          centerY = orbJson.getDouble("centerY").toFloat(),
          diameterOfWidth = orbJson.getDouble("diameterOfWidth").toFloat(),
          topAnchorY = orbJson.getDouble("topAnchorY").toFloat(),
          gapToPhotoBottom = orbJson.getDouble("gapToPhotoBottom").toFloat(),
        ),
        keyframes = KeyframeLock(
          kf01FrontRatio = kfJson.getDouble("kf01FrontRatio").toFloat(),
          kf04SideAscendRatio = kfJson.getDouble("kf04SideAscendRatio").toFloat(),
          kf02CaptureMs = kfJson.getLong("kf02CaptureMs"),
          kf03CaptureMs = kfJson.getLong("kf03CaptureMs"),
          kf05CaptureMs = kfJson.getLong("kf05CaptureMs"),
          kf06CaptureMs = kfJson.getLong("kf06CaptureMs"),
          contactStartMs = kfJson.getDouble("contactStartMs"),
          splitStartMs = kfJson.getDouble("splitStartMs"),
          ascendStartMs = kfJson.getDouble("ascendStartMs"),
          topJoinStartMs = kfJson.getDouble("topJoinStartMs"),
          loopStartMs = kfJson.getDouble("loopStartMs"),
          kf05JoinFraction = kfJson.getDouble("kf05JoinFraction").toFloat(),
        ),
        taskStreams = streams,
        perimeter = PerimeterLock(
          l1MinWidth = perimeterJson.getDouble("l1MinWidthPhotoRatio").toFloat(),
          l1MaxWidth = perimeterJson.getDouble("l1MaxWidthPhotoRatio").toFloat(),
          localFlareMin = perimeterJson.getDouble("localFlareMinPhotoRatio").toFloat(),
          localFlareMax = perimeterJson.getDouble("localFlareMaxPhotoRatio").toFloat(),
          l1AlphaMin = perimeterJson.getDouble("l1AlphaMin").toFloat(),
          l1AlphaMax = perimeterJson.getDouble("l1AlphaMax").toFloat(),
          l2AlphaMin = perimeterJson.getDouble("l2AlphaMin").toFloat(),
          l2AlphaMax = perimeterJson.getDouble("l2AlphaMax").toFloat(),
          l3Width = perimeterJson.getDouble("l3WidthPhotoRatio").toFloat(),
          l3AlphaMin = perimeterJson.getDouble("l3AlphaMin").toFloat(),
          l3AlphaMax = perimeterJson.getDouble("l3AlphaMax").toFloat(),
          l1AlphaScale = perimeterJson.getDouble("l1AlphaScale").toFloat(),
          l2AlphaScale = perimeterJson.getDouble("l2AlphaScale").toFloat(),
          l3AlphaScale = perimeterJson.getDouble("l3AlphaScale").toFloat(),
          l4DropletsPerSide = perimeterJson.getInt("l4DropletsPerSide"),
        ),
        tab2Optics = Tab2OpticsLock(
          cameraFovYDeg = opticsJson.getDouble("cameraFovYDeg").toFloat(),
          interiorTransmission = opticsJson.getDouble("interiorTransmission").toFloat(),
          absorptionGain = opticsJson.getDouble("absorptionGain").toFloat(),
          hdrScale = opticsJson.getDouble("hdrScale").toFloat(),
          bloomThreshold = opticsJson.getDouble("bloomThreshold").toFloat(),
          acesExposure = opticsJson.getDouble("acesExposure").toFloat(),
        ),
        output = OutputLock(
          captureWidth = outputJson.getInt("width"),
          captureHeight = outputJson.getInt("height"),
          requireHardwareGpu = outputJson.getBoolean("requireHardwareGpu"),
        ),
        lockSha256 = hash,
      )
      require(lock.photo.width > 0f && lock.photo.height > 0f && lock.photo.aspectRatio > 0f)
      require(lock.orb.diameterOfWidth > 0f && lock.orb.centerX in 0f..1f && lock.orb.centerY in 0f..1f)
      require(lock.perimeter.l1MinWidth <= lock.perimeter.l1MaxWidth)
      return lock
    }

    private fun JSONArray.toVec2List(): List<Vec2> = (0 until length()).map { idx ->
      val point = getJSONArray(idx)
      Vec2(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
    }

    private fun JSONArray.toFloatArray(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
      .digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
