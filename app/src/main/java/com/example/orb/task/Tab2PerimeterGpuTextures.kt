package com.example.orb.task

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest

internal data class PerimeterLayerTextureIds(
  val rgba: Int,
  val normal: Int,
  val flow: Int,
)

/** V2-only GLES textures and motion parameters, created and released on the GL thread. */
internal class Tab2PerimeterGpuTextures private constructor(
  val layers: Map<PerimeterRibbonLayer, PerimeterLayerTextureIds>,
  val dropletAtlas: Int,
  val relativeSpeeds: Map<PerimeterRibbonLayer, Float>,
  val dropletRelativeSpeed: Float,
  private val ownedIds: IntArray,
) {
  val allTextureIds: List<Int> = ownedIds.filter { it != 0 }

  fun releaseOnGlThread() {
    val ids = ownedIds.filter { it != 0 }.toIntArray()
    if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids, 0)
  }

  companion object {
    private const val TAG = "Tab2PerimeterAssets"
    private const val MANIFEST_PATH = "tab2_perimeter/manifest.json"
    private const val EXPECTED_ARCHIVE_SHA256 = "f724393fa8254b9d18543747ec356ded8a6e91dd61311d72a9a5028d174b0004"
    private const val EXPECTED_MASTER_SHA256 = "a37819bba6b144ca7fec27ace55e5b319c41e67b89a11e70d51200bce8749d67"

    private data class TextureFile(
      val entry: String,
      val width: Int,
      val height: Int,
      val repeatU: Boolean,
    )

    private val textureFiles = listOf(
      TextureFile("textures/L1_RGBA.png", 2048, 128, true),
      TextureFile("textures/L1_NORMAL_RGB.png", 2048, 128, true),
      TextureFile("textures/L1_FLOW_RG.png", 2048, 128, true),
      TextureFile("textures/L2_RGBA.png", 2048, 128, true),
      TextureFile("textures/L2_NORMAL_RGB.png", 2048, 128, true),
      TextureFile("textures/L2_FLOW_RG.png", 2048, 128, true),
      TextureFile("textures/L3_RGBA.png", 2048, 128, true),
      TextureFile("textures/L3_NORMAL_RGB.png", 2048, 128, true),
      TextureFile("textures/L3_FLOW_RG.png", 2048, 128, true),
      TextureFile("textures/L4_DROPLET_ATLAS_RGBA.png", 512, 512, false),
    )

    fun load(assetManager: AssetManager): Tab2PerimeterGpuTextures {
      val uploaded = mutableListOf<Int>()
      var currentAsset = MANIFEST_PATH
      try {
        val manifestBytes = assetManager.open(MANIFEST_PATH).use { it.readBytes() }
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        check(manifest.getString("archive_sha256") == EXPECTED_ARCHIVE_SHA256) {
          "ARCHIVE_SHA_MISMATCH expected=$EXPECTED_ARCHIVE_SHA256 actual=${manifest.optString("archive_sha256")}"
        }
        check(manifest.getString("master_sha256") == EXPECTED_MASTER_SHA256) {
          "MASTER_SHA_MISMATCH expected=$EXPECTED_MASTER_SHA256 actual=${manifest.optString("master_sha256")}"
        }
        val files = manifest.getJSONObject("files")
        val idsByEntry = mutableMapOf<String, Int>()
        for (file in textureFiles) {
          currentAsset = file.entry
          val descriptor = files.getJSONObject(file.entry)
          val assetPath = descriptor.getString("asset_path")
          val expectedSha = descriptor.getString("sha256")
          val bytes = assetManager.open(assetPath).use { it.readBytes() }
          val actualSha = sha256(bytes)
          check(actualSha == expectedSha) {
            "SHA256_MISMATCH expected=$expectedSha actual=$actualSha"
          }
          val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inMutable = false
          }
          val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("BITMAP_DECODE_NULL")
          try {
            check(bitmap.width == file.width && bitmap.height == file.height) {
              "DIMENSIONS_INVALID actual=${bitmap.width}x${bitmap.height} expected=${file.width}x${file.height}"
            }
            val textureId = upload(bitmap, file.repeatU)
            uploaded += textureId
            idsByEntry[file.entry] = textureId
          } finally {
            bitmap.recycle()
          }
        }

        currentAsset = "motion/KF01_KF06.json"
        val motionDesc = files.getJSONObject(currentAsset)
        val motionBytes = assetManager.open(motionDesc.getString("asset_path")).use { it.readBytes() }
        val motionSha = sha256(motionBytes)
        check(motionSha == motionDesc.getString("sha256")) {
          "SHA256_MISMATCH expected=${motionDesc.getString("sha256")} actual=$motionSha"
        }
        val speedJson = manifest.getJSONObject("relative_speed")
        val speeds = mapOf(
          PerimeterRibbonLayer.L1_ICE_BLUE_BODY to speedJson.getDouble("L1").toFloat(),
          PerimeterRibbonLayer.L2_EMERALD_VEIL to speedJson.getDouble("L2").toFloat(),
          PerimeterRibbonLayer.L3_GOLD_FILAMENT to speedJson.getDouble("L3").toFloat(),
        )
        val dropletSpeed = speedJson.getDouble("L4").toFloat()
        check(speeds.values.all { it.isFinite() && it > 0f } && dropletSpeed.isFinite() && dropletSpeed > 0f) {
          "MOTION_SPEEDS_INVALID layers=$speeds L4=$dropletSpeed"
        }

        val layerTextures = mapOf(
          PerimeterRibbonLayer.L1_ICE_BLUE_BODY to PerimeterLayerTextureIds(
            idsByEntry.getValue("textures/L1_RGBA.png"),
            idsByEntry.getValue("textures/L1_NORMAL_RGB.png"),
            idsByEntry.getValue("textures/L1_FLOW_RG.png"),
          ),
          PerimeterRibbonLayer.L2_EMERALD_VEIL to PerimeterLayerTextureIds(
            idsByEntry.getValue("textures/L2_RGBA.png"),
            idsByEntry.getValue("textures/L2_NORMAL_RGB.png"),
            idsByEntry.getValue("textures/L2_FLOW_RG.png"),
          ),
          PerimeterRibbonLayer.L3_GOLD_FILAMENT to PerimeterLayerTextureIds(
            idsByEntry.getValue("textures/L3_RGBA.png"),
            idsByEntry.getValue("textures/L3_NORMAL_RGB.png"),
            idsByEntry.getValue("textures/L3_FLOW_RG.png"),
          ),
        )
        val atlas = idsByEntry.getValue("textures/L4_DROPLET_ATLAS_RGBA.png")
        return Tab2PerimeterGpuTextures(layerTextures, atlas, speeds, dropletSpeed, uploaded.toIntArray())
      } catch (t: Throwable) {
        val errorCode = (t.message ?: t.javaClass.simpleName).replace('\n', ' ')
        Log.e(TAG, "TAB2_RESOURCE_LOAD_FAILED resource=$currentAsset error=$errorCode downgrade=V2_PERIMETER_DISABLED_NO_LEGACY_FALLBACK", t)
        val ids = uploaded.filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) GLES30.glDeleteTextures(ids.size, ids, 0)
        throw IllegalStateException(
          "TAB2_RESOURCE_LOAD_FAILED resource=$currentAsset error=$errorCode downgrade=V2_PERIMETER_DISABLED_NO_LEGACY_FALLBACK",
          t,
        )
      }
    }

    private fun upload(bitmap: Bitmap, repeatU: Boolean): Int {
      while (GLES30.glGetError() != GLES30.GL_NO_ERROR) Unit
      val ids = IntArray(1)
      GLES30.glGenTextures(1, ids, 0)
      val id = ids[0]
      check(id != 0) { "GL_TEXTURE_ALLOCATION_FAILED" }
      try {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
          GLES30.GL_TEXTURE_2D,
          GLES30.GL_TEXTURE_WRAP_S,
          if (repeatU) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "GL_UPLOAD_ERROR_0x${error.toString(16)}" }
        return id
      } catch (t: Throwable) {
        GLES30.glDeleteTextures(1, intArrayOf(id), 0)
        throw t
      } finally {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
      }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }
  }
}
