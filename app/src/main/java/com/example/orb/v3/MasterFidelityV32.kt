package com.example.orb.v3

import android.content.res.AssetManager
import com.example.orb.geometry.Vec3
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strongly typed runtime configuration loaded from
 * `app/src/main/assets/orb_v3/manifest/MASTER_FIDELITY_V32.json`.
 *
 * Connects the offline Stage A–E Reference-to-3D parameter fitting results directly to the
 * production Android OpenGL ES 3.0 renderer (`OrbFluidVolumeRenderer`, `AIOrbOrbitRibbons`,
 * and `AIOrbScene`) with exact 1:1 fallback defaults when AssetManager is unavailable in JVM tests.
 */
data class MasterFidelityV32(
  val schemaVersion: String,
  val masterPath: String,
  val masterSha256: String,
  val masterWidth: Int,
  val masterHeight: Int,
  val configSha256: String,
  val loadedFromAssets: Boolean,
  val camera: CameraFit,
  val outerGlass: OuterGlassFit,
  val innerCavity: InnerCavityFit,
  val fluidVolume: FluidVolumeFit,
  val sapphireValleys: SapphireValleysFit,
  val caustics: CausticsFit,
  val residentC01: ResidentCurveFit,
  val residentA01: ResidentCurveFit,
  val residentA02: ResidentCurveFit,
  val residentG01: ResidentCurveFit,
  val residentG02: ResidentCurveFit,
  val lighting: LightingFit,
  val postProcessing: PostProcessingFit,
  val selectedCavityModel: String,
  val selectedVolumeDepth: Int,
  val offlineVisualLossTotal: Float,
) {

  data class CameraFit(
    val fovYDeg: Float = 42.0f,
    val coreScale: Float = 1.0f,
    val coreShellScale: Float = 0.8f,
    val ribbonHugScale: Float = 0.87f,
    val visualBoundRadius: Float = 1.5661f,
    val cameraDistance: Float = 4.0798f,
    val targetOffsetX: Float = 0.0265f,
    val targetOffsetY: Float = -0.125f,
    val targetSphereDiameterRatio: Float = 0.51994f,
  )

  data class OuterGlassFit(
    val iorGlass: Float = 1.45f,
    val glassWallThickness: Float = 0.115f,
    val fresnelPower: Float = 6.2f,
    val fresnelGain: Float = 0.8f,
    val razorRimGain: Float = 1.35f,
    val innerRingGain: Float = 0.32f,
    val grooveDarkening: Float = 0.54f,
    val envReflectionCap: Float = 0.24f,
    val upperHazeSuppress: Float = 0.8f,
    val crownGoldStrength: Float = 0.4f,
  )

  data class InnerCavityFit(
    val selectedModel: String = "CANDIDATE_B_CRYSTAL_LENS",
    val meniscusYOffset: Float = -0.012f,
    val domeDepthDip: Float = 0.14f,
    val sideWallClimb: Float = 0.048f,
    val lensCenterX: Float = 0.1529f,
    val lensCenterY: Float = 0.336f,
    val lensCenterZ: Float = 0.16f,
    val lensRadiusX: Float = 0.3f,
    val lensRadiusY: Float = 0.1793f,
    val lensRadiusZ: Float = 0.39f,
    val lensWallThickness: Float = 0.047f,
    val iorAir: Float = 1.0003f,
    val iorWater: Float = 1.333f,
    val iorCrystal: Float = 1.45f,
    val cavityTintGain: Float = 0.14f,
    val cavityRimRefraction: Float = 0.752f,
    val upperGoldPenetration: Float = 0.86f,
  )

  data class FluidVolumeFit(
    val width: Int = 256,
    val height: Int = 256,
    val depth: Int = 32,
    val densityScale: Float = 2.3f,
    val baseDensity: Float = 0.032f,
    val foldCount: Int = 3,
    val foldIso: Float = 0.18f,
    val foldThickness: Float = 0.2f,
    val foldNormalPerturbation: Float = 0.34f,
    val foldRefraction: Float = 0.22f,
    val foldSpecularGain: Float = 0.42f,
  )

  data class SapphireValleysFit(
    val sapphireAbsorptionGain: Float = 0.32f,
    val sapphireAnchorWeight: Float = 1.45f,
    val sapphireDepthDarken: Float = 0.74f,
    val beerLambertR: Float = 2.6f,
    val beerLambertG: Float = 0.92f,
    val beerLambertB: Float = 0.42f,
  )

  data class CausticsFit(
    val causticIntensity: Float = 0.56f,
    val causticDecoupleWeight: Float = 0.12f,
    val upperGoldCausticGain: Float = 0.44f,
  )

  data class ResidentCurveFit(
    val code: String,
    val periodMs: Int,
    val closed: Boolean,
    val halfWidth: Float,
    val opacityScale: Float,
    val controlPoints: List<Vec3>,
    val widthProfile: FloatArray? = null,
    val twistProfileDeg: FloatArray? = null,
  )

  data class LightingFit(
    val goldWallsGain: Float = 0.74f,
    val coreStreamGain: Float = 0.66f,
    val secThreadGain: Float = 0.395f,
    val glassBellyGain: Float = 0.16f,
    val goldSpecGain: Float = 0.305f,
    val volumeExposure: Float = 1.06f,
  )

  data class PostProcessingFit(
    val bloomThreshold: Float = 1.32f,
    val bloomStrength: Float = 0.62f,
    val acesExposure: Float = 1.06f,
    val saturationBoost: Float = 1.1f,
  )

  fun toRuntimeSummaryJson(): String {
    fun f(v: Float): String = String.format(Locale.US, "%.4f", v)
    return buildString {
      appendLine("{")
      appendLine("  \"schemaVersion\": \"$schemaVersion\",")
      appendLine("  \"masterSha256\": \"$masterSha256\",")
      appendLine("  \"configSha256\": \"$configSha256\",")
      appendLine("  \"loadedFromAssets\": $loadedFromAssets,")
      appendLine("  \"selectedCavityModel\": \"$selectedCavityModel\",")
      appendLine("  \"selectedVolumeDepth\": $selectedVolumeDepth,")
      appendLine("  \"camera\": {")
      appendLine("    \"fovYDeg\": ${f(camera.fovYDeg)},")
      appendLine("    \"cameraDistance\": ${f(camera.cameraDistance)},")
      appendLine("    \"visualBoundRadius\": ${f(camera.visualBoundRadius)},")
      appendLine("    \"targetOffsetX\": ${f(camera.targetOffsetX)},")
      appendLine("    \"targetOffsetY\": ${f(camera.targetOffsetY)},")
      appendLine("    \"coreShellScale\": ${f(camera.coreShellScale)},")
      appendLine("    \"ribbonHugScale\": ${f(camera.ribbonHugScale)}")
      appendLine("  },")
      appendLine("  \"crystalLens\": {")
      appendLine("    \"center\": [${f(innerCavity.lensCenterX)}, ${f(innerCavity.lensCenterY)}, ${f(innerCavity.lensCenterZ)}],")
      appendLine("    \"radii\": [${f(innerCavity.lensRadiusX)}, ${f(innerCavity.lensRadiusY)}, ${f(innerCavity.lensRadiusZ)}],")
      appendLine("    \"wallThickness\": ${f(innerCavity.lensWallThickness)},")
      appendLine("    \"cavityTintGain\": ${f(innerCavity.cavityTintGain)},")
      appendLine("    \"cavityRimRefraction\": ${f(innerCavity.cavityRimRefraction)},")
      appendLine("    \"upperGoldPenetration\": ${f(innerCavity.upperGoldPenetration)}")
      appendLine("  },")
      appendLine("  \"foldOptics\": {")
      appendLine("    \"foldNormalPerturbation\": ${f(fluidVolume.foldNormalPerturbation)},")
      appendLine("    \"foldRefraction\": ${f(fluidVolume.foldRefraction)},")
      appendLine("    \"foldSpecularGain\": ${f(fluidVolume.foldSpecularGain)},")
      appendLine("    \"beerLambert\": [${f(sapphireValleys.beerLambertR)}, ${f(sapphireValleys.beerLambertG)}, ${f(sapphireValleys.beerLambertB)}]")
      appendLine("  },")
      appendLine("  \"ribbonOptics\": {")
      appendLine("    \"goldWallsGain\": ${f(lighting.goldWallsGain)},")
      appendLine("    \"coreStreamGain\": ${f(lighting.coreStreamGain)},")
      appendLine("    \"secThreadGain\": ${f(lighting.secThreadGain)},")
      appendLine("    \"glassBellyGain\": ${f(lighting.glassBellyGain)},")
      appendLine("    \"goldSpecGain\": ${f(lighting.goldSpecGain)}")
      appendLine("  }")
      append("}")
    }
  }

  companion object {
    const val ASSET_PATH = "orb_v3/manifest/MASTER_FIDELITY_V32.json"
    const val EXPECTED_SCHEMA = "AI_ORB_V3_2_MASTER_FIDELITY_1_0"
    const val EXPECTED_MASTER_SHA256 =
      "910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122"

    val Default: MasterFidelityV32 = MasterFidelityV32(
      schemaVersion = EXPECTED_SCHEMA,
      masterPath = "public/assets/aistudio/____________910d1531.png",
      masterSha256 = EXPECTED_MASTER_SHA256,
      masterWidth = 1254,
      masterHeight = 1254,
      configSha256 = "FALLBACK_EMBEDDED_V32_DEFAULTS",
      loadedFromAssets = false,
      camera = CameraFit(),
      outerGlass = OuterGlassFit(),
      innerCavity = InnerCavityFit(),
      fluidVolume = FluidVolumeFit(),
      sapphireValleys = SapphireValleysFit(),
      caustics = CausticsFit(),
      residentC01 = ResidentCurveFit(
        code = "C01",
        periodMs = 4400,
        closed = false,
        halfWidth = 0.125f,
        opacityScale = 0.96f,
        controlPoints = listOf(
          Vec3(0.76f, 0.58f, -0.44f),
          Vec3(0.28f, 1.02f, -0.18f),
          Vec3(-0.36f, 1.04f, 0.06f),
          Vec3(-0.96f, 0.72f, 0.28f),
          Vec3(-1.26f, 0.22f, 0.52f),
          Vec3(-1.04f, -0.24f, 0.80f),
          Vec3(-0.32f, -0.44f, 0.96f),
          Vec3(0.38f, -0.62f, 0.82f),
          Vec3(0.66f, -0.80f, 0.46f),
          Vec3(0.50f, -0.98f, 0.12f),
          Vec3(-0.02f, -1.04f, -0.14f),
          Vec3(-0.56f, -0.94f, -0.30f),
        ),
      ),
      residentA01 = ResidentCurveFit(
        code = "A01",
        periodMs = 3700,
        closed = false,
        halfWidth = 0.1365f,
        opacityScale = 0.94f,
        controlPoints = listOf(
          Vec3(-0.82f, 0.441f, 0.24f),
          Vec3(-0.852f, 0.497f, 0.26f),
          Vec3(-0.86f, 0.567f, 0.24f),
          Vec3(-0.78f, 0.775f, 0.20f),
          Vec3(-0.52f, 0.893f, 0.14f),
          Vec3(-0.362f, 1.04f, 0.08f),
          Vec3(-0.317f, 1.075f, 0.02f),
          Vec3(-0.297f, 1.02f, -0.06f),
          Vec3(-0.143f, 0.845f, -0.14f),
        ),
        widthProfile = floatArrayOf(0.14f, 0.246f, 1.688f, 0.69f, 1.716f, 0.252f, 0.14f, 0.62f, 0.14f),
        twistProfileDeg = floatArrayOf(-16f, -8f, 2f, 12f, 20f, 26f, 30f, 34f, 38f),
      ),
      residentA02 = ResidentCurveFit(
        code = "A02",
        periodMs = 2900,
        closed = false,
        halfWidth = 0.173f,
        opacityScale = 1.0f,
        controlPoints = listOf(
          Vec3(0.42f, -0.82f, 0.34f),
          Vec3(0.48f, -0.78f, 0.34f),
          Vec3(0.78f, -0.71f, 0.30f),
          Vec3(1.22f, -0.395f, 0.24f),
          Vec3(1.285f, -0.195f, 0.18f),
          Vec3(1.142f, -0.05f, 0.12f),
          Vec3(0.985f, 0.117f, 0.04f),
          Vec3(0.76f, 0.385f, -0.06f),
          Vec3(0.70f, 0.595f, -0.18f),
        ),
        widthProfile = floatArrayOf(0.14f, 0.14f, 1.014f, 0.778f, 1.592f, 1.782f, 1.688f, 0.772f, 0.252f),
        twistProfileDeg = floatArrayOf(22f, 12f, 0f, -12f, -22f, -14f, -4f, 8f, 18f),
      ),
      residentG01 = ResidentCurveFit(
        code = "G01",
        periodMs = 6100,
        closed = false,
        halfWidth = 0.165f,
        opacityScale = 0.92f,
        controlPoints = listOf(
          Vec3(-0.22f, 1.18f, -0.60f),
          Vec3(0.28f, 1.30f, -0.34f),
          Vec3(0.78f, 1.08f, -0.14f),
          Vec3(1.14f, 0.62f, -0.08f),
          Vec3(1.22f, 0.12f, -0.22f),
          Vec3(1.04f, -0.32f, -0.34f),
          Vec3(0.70f, -0.68f, -0.42f),
          Vec3(0.28f, -0.92f, -0.54f),
          Vec3(-0.14f, -1.04f, -0.68f),
        ),
      ),
      residentG02 = ResidentCurveFit(
        code = "G02",
        periodMs = 5300,
        closed = false,
        halfWidth = 0.155f,
        opacityScale = 0.90f,
        controlPoints = listOf(
          Vec3(-0.92f, 0.48f, -0.36f),
          Vec3(-1.12f, 0.02f, 0.12f),
          Vec3(-1.08f, -0.46f, 0.48f),
          Vec3(-0.78f, -0.82f, 0.66f),
          Vec3(-0.48f, -1.00f, 0.50f),
          Vec3(-0.32f, -1.20f, 0.30f),
          Vec3(-0.04f, -1.32f, 0.14f),
          Vec3(0.26f, -1.36f, -0.06f),
          Vec3(0.48f, -1.26f, -0.24f),
        ),
      ),
      lighting = LightingFit(),
      postProcessing = PostProcessingFit(),
      selectedCavityModel = "CANDIDATE_B_CRYSTAL_LENS",
      selectedVolumeDepth = 32,
      offlineVisualLossTotal = 0.21507f,
    )

    fun loadFromAssets(assetManager: AssetManager?): MasterFidelityV32 {
      if (assetManager == null) return Default
      return try {
        val rawBytes = assetManager.open(ASSET_PATH).use { it.readBytes() }
        val sha256 = sha256Hex(rawBytes)
        parseJson(String(rawBytes, Charsets.UTF_8), configSha256 = sha256, loadedFromAssets = true)
      } catch (_: Exception) {
        Default
      }
    }

    fun parseJson(
      jsonText: String,
      configSha256: String = sha256Hex(jsonText.toByteArray(Charsets.UTF_8)),
      loadedFromAssets: Boolean = false,
    ): MasterFidelityV32 {
      val root = JSONObject(jsonText)
      val master = root.getJSONObject("referenceMaster")

      fun fParam(section: JSONObject, key: String, fallback: Float): Float {
        if (!section.has(key)) return fallback
        val obj = section.getJSONObject(key)
        return when {
          obj.has("fittedValue") -> obj.getDouble("fittedValue").toFloat()
          obj.has("default") -> obj.getDouble("default").toFloat()
          else -> fallback
        }
      }

      fun iParam(section: JSONObject, key: String, fallback: Int): Int {
        if (!section.has(key)) return fallback
        val obj = section.getJSONObject(key)
        return when {
          obj.has("fittedValue") -> obj.getInt("fittedValue")
          obj.has("default") -> obj.getInt("default")
          else -> fallback
        }
      }

      fun sParam(section: JSONObject, key: String, fallback: String): String {
        if (!section.has(key)) return fallback
        val obj = section.getJSONObject(key)
        return when {
          obj.has("fittedValue") -> obj.getString("fittedValue")
          obj.has("default") -> obj.getString("default")
          else -> fallback
        }
      }

      fun bParam(section: JSONObject, key: String, fallback: Boolean): Boolean {
        if (!section.has(key)) return fallback
        val obj = section.getJSONObject(key)
        return when {
          obj.has("fittedValue") -> obj.getBoolean("fittedValue")
          obj.has("default") -> obj.getBoolean("default")
          else -> fallback
        }
      }

      fun parseVec3List(arr: JSONArray): List<Vec3> = List(arr.length()) { i ->
        val p = arr.getJSONArray(i)
        Vec3(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat())
      }

      fun parseFloatArray(arr: JSONArray): FloatArray = FloatArray(arr.length()) { i ->
        arr.getDouble(i).toFloat()
      }

      fun parseResident(sectionKey: String, code: String, fallback: ResidentCurveFit): ResidentCurveFit {
        val sec = root.optJSONObject(sectionKey) ?: return fallback
        val ptsArr = sec.optJSONObject("controlPoints")?.optJSONArray("fittedValue")
        val widthArr = sec.optJSONObject("widthProfile")?.optJSONArray("fittedValue")
        val twistArr = sec.optJSONObject("twistProfileDeg")?.optJSONArray("fittedValue")
        return ResidentCurveFit(
          code = code,
          periodMs = iParam(sec, "periodMs", fallback.periodMs),
          closed = bParam(sec, "closed", fallback.closed),
          halfWidth = fParam(sec, "halfWidth", fallback.halfWidth),
          opacityScale = fParam(sec, "opacityScale", fallback.opacityScale),
          controlPoints = if (ptsArr != null && ptsArr.length() >= 4) parseVec3List(ptsArr) else fallback.controlPoints,
          widthProfile = if (widthArr != null && widthArr.length() >= 4) parseFloatArray(widthArr) else fallback.widthProfile,
          twistProfileDeg = if (twistArr != null && twistArr.length() >= 4) parseFloatArray(twistArr) else fallback.twistProfileDeg,
        )
      }

      val cam = root.getJSONObject("camera")
      val glass = root.getJSONObject("outerGlass")
      val cav = root.getJSONObject("innerCavity")
      val vol = root.getJSONObject("fluidVolume")
      val sap = root.getJSONObject("sapphireValleys")
      val cau = root.getJSONObject("caustics")
      val lgt = root.getJSONObject("lighting")
      val post = root.getJSONObject("postProcessing")
      val fitSum = root.optJSONObject("fitSummary")

      val d = Default
      return MasterFidelityV32(
        schemaVersion = root.getString("schemaVersion"),
        masterPath = master.getString("path"),
        masterSha256 = master.getString("sha256"),
        masterWidth = master.getInt("width"),
        masterHeight = master.getInt("height"),
        configSha256 = configSha256,
        loadedFromAssets = loadedFromAssets,
        camera = CameraFit(
          fovYDeg = fParam(cam, "fovYDeg", d.camera.fovYDeg),
          coreScale = fParam(cam, "coreScale", d.camera.coreScale),
          coreShellScale = fParam(cam, "coreShellScale", d.camera.coreShellScale),
          ribbonHugScale = fParam(cam, "ribbonHugScale", d.camera.ribbonHugScale),
          visualBoundRadius = fParam(cam, "visualBoundRadius", d.camera.visualBoundRadius),
          cameraDistance = fParam(cam, "cameraDistance", d.camera.cameraDistance),
          targetOffsetX = fParam(cam, "targetOffsetX", d.camera.targetOffsetX),
          targetOffsetY = fParam(cam, "targetOffsetY", d.camera.targetOffsetY),
          targetSphereDiameterRatio = fParam(cam, "targetSphereDiameterRatio", d.camera.targetSphereDiameterRatio),
        ),
        outerGlass = OuterGlassFit(
          iorGlass = fParam(glass, "iorGlass", d.outerGlass.iorGlass),
          glassWallThickness = fParam(glass, "glassWallThickness", d.outerGlass.glassWallThickness),
          fresnelPower = fParam(glass, "fresnelPower", d.outerGlass.fresnelPower),
          fresnelGain = fParam(glass, "fresnelGain", d.outerGlass.fresnelGain),
          razorRimGain = fParam(glass, "razorRimGain", d.outerGlass.razorRimGain),
          innerRingGain = fParam(glass, "innerRingGain", d.outerGlass.innerRingGain),
          grooveDarkening = fParam(glass, "grooveDarkening", d.outerGlass.grooveDarkening),
          envReflectionCap = fParam(glass, "envReflectionCap", d.outerGlass.envReflectionCap),
          upperHazeSuppress = fParam(glass, "upperHazeSuppress", d.outerGlass.upperHazeSuppress),
          crownGoldStrength = fParam(glass, "crownGoldStrength", d.outerGlass.crownGoldStrength),
        ),
        innerCavity = InnerCavityFit(
          selectedModel = sParam(cav, "selectedModel", d.innerCavity.selectedModel),
          meniscusYOffset = fParam(cav, "meniscusYOffset", d.innerCavity.meniscusYOffset),
          domeDepthDip = fParam(cav, "domeDepthDip", d.innerCavity.domeDepthDip),
          sideWallClimb = fParam(cav, "sideWallClimb", d.innerCavity.sideWallClimb),
          lensCenterX = fParam(cav, "lensCenterX", d.innerCavity.lensCenterX),
          lensCenterY = fParam(cav, "lensCenterY", d.innerCavity.lensCenterY),
          lensCenterZ = fParam(cav, "lensCenterZ", d.innerCavity.lensCenterZ),
          lensRadiusX = fParam(cav, "lensRadiusX", d.innerCavity.lensRadiusX),
          lensRadiusY = fParam(cav, "lensRadiusY", d.innerCavity.lensRadiusY),
          lensRadiusZ = fParam(cav, "lensRadiusZ", d.innerCavity.lensRadiusZ),
          lensWallThickness = fParam(cav, "lensWallThickness", d.innerCavity.lensWallThickness),
          iorAir = fParam(cav, "iorAir", d.innerCavity.iorAir),
          iorWater = fParam(cav, "iorWater", d.innerCavity.iorWater),
          iorCrystal = fParam(cav, "iorCrystal", d.innerCavity.iorCrystal),
          cavityTintGain = fParam(cav, "cavityTintGain", d.innerCavity.cavityTintGain),
          cavityRimRefraction = fParam(cav, "cavityRimRefraction", d.innerCavity.cavityRimRefraction),
          upperGoldPenetration = fParam(cav, "upperGoldPenetration", d.innerCavity.upperGoldPenetration),
        ),
        fluidVolume = FluidVolumeFit(
          width = iParam(vol, "width", d.fluidVolume.width),
          height = iParam(vol, "height", d.fluidVolume.height),
          depth = iParam(vol, "depth", d.fluidVolume.depth),
          densityScale = fParam(vol, "densityScale", d.fluidVolume.densityScale),
          baseDensity = fParam(vol, "baseDensity", d.fluidVolume.baseDensity),
          foldCount = iParam(vol, "foldCount", d.fluidVolume.foldCount),
          foldIso = fParam(vol, "foldIso", d.fluidVolume.foldIso),
          foldThickness = fParam(vol, "foldThickness", d.fluidVolume.foldThickness),
          foldNormalPerturbation = fParam(vol, "foldNormalPerturbation", d.fluidVolume.foldNormalPerturbation),
          foldRefraction = fParam(vol, "foldRefraction", d.fluidVolume.foldRefraction),
          foldSpecularGain = fParam(vol, "foldSpecularGain", d.fluidVolume.foldSpecularGain),
        ),
        sapphireValleys = SapphireValleysFit(
          sapphireAbsorptionGain = fParam(sap, "sapphireAbsorptionGain", d.sapphireValleys.sapphireAbsorptionGain),
          sapphireAnchorWeight = fParam(sap, "sapphireAnchorWeight", d.sapphireValleys.sapphireAnchorWeight),
          sapphireDepthDarken = fParam(sap, "sapphireDepthDarken", d.sapphireValleys.sapphireDepthDarken),
          beerLambertR = fParam(sap, "beerLambertR", d.sapphireValleys.beerLambertR),
          beerLambertG = fParam(sap, "beerLambertG", d.sapphireValleys.beerLambertG),
          beerLambertB = fParam(sap, "beerLambertB", d.sapphireValleys.beerLambertB),
        ),
        caustics = CausticsFit(
          causticIntensity = fParam(cau, "causticIntensity", d.caustics.causticIntensity),
          causticDecoupleWeight = fParam(cau, "causticDecoupleWeight", d.caustics.causticDecoupleWeight),
          upperGoldCausticGain = fParam(cau, "upperGoldCausticGain", d.caustics.upperGoldCausticGain),
        ),
        residentC01 = parseResident("residentC01", "C01", d.residentC01),
        residentA01 = parseResident("residentA01", "A01", d.residentA01),
        residentA02 = parseResident("residentA02", "A02", d.residentA02),
        residentG01 = parseResident("residentG01", "G01", d.residentG01),
        residentG02 = parseResident("residentG02", "G02", d.residentG02),
        lighting = LightingFit(
          goldWallsGain = fParam(lgt, "goldWallsGain", d.lighting.goldWallsGain),
          coreStreamGain = fParam(lgt, "coreStreamGain", d.lighting.coreStreamGain),
          secThreadGain = fParam(lgt, "secThreadGain", d.lighting.secThreadGain),
          glassBellyGain = fParam(lgt, "glassBellyGain", d.lighting.glassBellyGain),
          goldSpecGain = fParam(lgt, "goldSpecGain", d.lighting.goldSpecGain),
          volumeExposure = fParam(lgt, "volumeExposure", d.lighting.volumeExposure),
        ),
        postProcessing = PostProcessingFit(
          bloomThreshold = fParam(post, "bloomThreshold", d.postProcessing.bloomThreshold),
          bloomStrength = fParam(post, "bloomStrength", d.postProcessing.bloomStrength),
          acesExposure = fParam(post, "acesExposure", d.postProcessing.acesExposure),
          saturationBoost = fParam(post, "saturationBoost", d.postProcessing.saturationBoost),
        ),
        selectedCavityModel = fitSum?.optString("selectedCavityModel", d.selectedCavityModel) ?: d.selectedCavityModel,
        selectedVolumeDepth = fitSum?.optInt("selectedVolumeDepth", d.selectedVolumeDepth) ?: d.selectedVolumeDepth,
        offlineVisualLossTotal = fitSum?.optDouble("L_total", d.offlineVisualLossTotal.toDouble())?.toFloat()
          ?: d.offlineVisualLossTotal,
      )
    }

    fun sha256Hex(bytes: ByteArray): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
      val sb = StringBuilder(digest.size * 2)
      for (b in digest) {
        sb.append(String.format(Locale.US, "%02x", b))
      }
      return sb.toString()
    }
  }
}
