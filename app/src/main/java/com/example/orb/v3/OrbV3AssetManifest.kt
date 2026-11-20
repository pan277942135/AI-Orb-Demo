package com.example.orb.v3

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * Typed representation of `app/src/main/assets/orb_v3/manifest/ORB_V3_ASSET_MANIFEST.json`.
 * Serves as the runtime contract connecting pre-generated Lossless RGBA WebP assets to
 * OpenGL ES 3.0 3D Volume and 2D Optical textures.
 */
data class OrbV3AssetManifest(
  val schemaVersion: String,
  val architecture: String,
  val generatorSeed: Long,
  val masterPath: String,
  val masterSha256: String,
  val masterWidth: Int,
  val masterHeight: Int,
  val volume: VolumeDescriptor,
  val maps: List<MapDescriptor>,
  val totalWebpFiles: Int,
  val totalDiskBytes: Long,
  val totalGpuMemoryBytes: Long,
) {
  data class VolumeSliceEntry(
    val zIndex: Int,
    val path: String,
    val bytes: Long,
    val sha256: String,
  )

  data class VolumeDescriptor(
    val id: String,
    val uniformName: String,
    val textureUnit: Int,
    val width: Int,
    val height: Int,
    val depth: Int,
    val format: String,
    val colorSpace: String,
    val premultipliedAlpha: Boolean,
    val yAxisConvention: String,
    val gpuTarget: String,
    val gpuInternalFormat: String,
    val gpuMemoryBytes: Long,
    val slices: List<VolumeSliceEntry>,
  )

  data class MapDescriptor(
    val id: String,
    val path: String,
    val width: Int,
    val height: Int,
    val colorSpace: String,
    val gpuInternalFormat: String,
    val channels: String,
    val uniformName: String,
    val textureUnit: Int,
    val bytes: Long,
    val sha256: String,
  ) {
    val isSrgb: Boolean get() = colorSpace.equals("SRGB", ignoreCase = true)
  }

  fun findMap(id: String): MapDescriptor =
    maps.firstOrNull { it.id == id }
      ?: error("Required V3 WebP map '$id' missing from ORB_V3_ASSET_MANIFEST.json")

  companion object {
    const val ASSET_ROOT = "orb_v3"
    const val MANIFEST_ASSET_PATH = "$ASSET_ROOT/manifest/ORB_V3_ASSET_MANIFEST.json"
    const val EXPECTED_MASTER_SHA256 =
      "910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122"
    const val EXPECTED_SCHEMA = "AI_ORB_V3_WEBP_VOLUME_MANIFEST_1_0"

    fun loadFromAssets(assetManager: AssetManager): OrbV3AssetManifest {
      val jsonText = assetManager.open(MANIFEST_ASSET_PATH).bufferedReader(Charsets.UTF_8).use {
        it.readText()
      }
      return parseJson(jsonText)
    }

    fun parseJson(jsonText: String): OrbV3AssetManifest {
      val root = JSONObject(jsonText)
      val master = root.getJSONObject("referenceMaster")
      val vol = root.getJSONObject("volume")
      val slicesArr = vol.getJSONArray("slices")
      val slices = ArrayList<VolumeSliceEntry>(slicesArr.length())
      for (i in 0 until slicesArr.length()) {
        val s = slicesArr.getJSONObject(i)
        slices.add(
          VolumeSliceEntry(
            zIndex = s.getInt("zIndex"),
            path = s.getString("path"),
            bytes = s.getLong("bytes"),
            sha256 = s.getString("sha256"),
          )
        )
      }
      slices.sortBy { it.zIndex }

      val mapsArr = root.getJSONArray("maps")
      val maps = ArrayList<MapDescriptor>(mapsArr.length())
      for (i in 0 until mapsArr.length()) {
        val m = mapsArr.getJSONObject(i)
        maps.add(
          MapDescriptor(
            id = m.getString("id"),
            path = m.getString("path"),
            width = m.getInt("width"),
            height = m.getInt("height"),
            colorSpace = m.getString("colorSpace"),
            gpuInternalFormat = m.getString("gpuInternalFormat"),
            channels = m.getString("channels"),
            uniformName = m.getString("uniformName"),
            textureUnit = m.getInt("textureUnit"),
            bytes = m.getLong("bytes"),
            sha256 = m.getString("sha256"),
          )
        )
      }

      val summary = root.getJSONObject("summary")
      return OrbV3AssetManifest(
        schemaVersion = root.getString("schemaVersion"),
        architecture = root.getString("architecture"),
        generatorSeed = root.getLong("generatorSeed"),
        masterPath = master.getString("path"),
        masterSha256 = master.getString("sha256"),
        masterWidth = master.getInt("width"),
        masterHeight = master.getInt("height"),
        volume = VolumeDescriptor(
          id = vol.getString("id"),
          uniformName = vol.getString("uniformName"),
          textureUnit = vol.getInt("textureUnit"),
          width = vol.getInt("width"),
          height = vol.getInt("height"),
          depth = vol.getInt("depth"),
          format = vol.getString("format"),
          colorSpace = vol.getString("colorSpace"),
          premultipliedAlpha = vol.getBoolean("premultipliedAlpha"),
          yAxisConvention = vol.getString("yAxisConvention"),
          gpuTarget = vol.getString("gpuTarget"),
          gpuInternalFormat = vol.getString("gpuInternalFormat"),
          gpuMemoryBytes = vol.getLong("gpuMemoryBytes"),
          slices = slices,
        ),
        maps = maps,
        totalWebpFiles = summary.getInt("totalWebpFiles"),
        totalDiskBytes = summary.getLong("totalDiskBytes"),
        totalGpuMemoryBytes = summary.getLong("totalGpuMemoryBytes"),
      )
    }
  }
}
