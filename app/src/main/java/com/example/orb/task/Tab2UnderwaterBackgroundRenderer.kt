package com.example.orb.task

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import com.example.orb.gl.GlShaderProgram
import java.security.MessageDigest
import kotlin.math.max

/**
 * Renders Tab 2's frozen original underwater background PNG (`assets/tab2_background/tab2_underwater_bg.png`)
 * with exact pixel fidelity (zero color grading, zero distortion, aspect-preserving cover fit that
 * keeps the top water surface, top-right sunlight rays, and bottom aquatic plants balanced across
 * portrait phones, foldables, and tablets).
 *
 * Strictly scoped to Tab 2 (`TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`).
 * Tab 1 and Tab 3 never invoke this renderer.
 */
class Tab2UnderwaterBackgroundRenderer {

  data class CoverUvRect(
    val uMin: Float,
    val vMin: Float,
    val uMax: Float,
    val vMax: Float,
  ) {
    val uSpan: Float get() = uMax - uMin
    val vSpan: Float get() = vMax - vMin
  }

  private var program: GlShaderProgram? = null
  private var vao: Int = 0
  private var textureId: Int = 0
  var textureWidthPx: Int = 0
    private set
  var textureHeightPx: Int = 0
    private set
  var loadedByteSize: Int = 0
    private set
  var loadedSha256: String = ""
    private set
  var isReady: Boolean = false
    private set

  fun init(assetManager: AssetManager) {
    release()
    program = GlShaderProgram(VERT_SHADER, FRAG_SHADER, "Tab2UnderwaterBg")
    val vaos = IntArray(1)
    GLES30.glGenVertexArrays(1, vaos, 0)
    vao = vaos[0]

    val bytes = assetManager.open(BACKGROUND_ASSET_PATH).use { it.readBytes() }
    loadedByteSize = bytes.size
    loadedSha256 = sha256Hex(bytes)

    val opts = BitmapFactory.Options().apply {
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inScaled = false
    }
    val bmp = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)) {
      "Failed to decode frozen Tab 2 underwater background: $BACKGROUND_ASSET_PATH"
    }
    try {
      textureWidthPx = bmp.width
      textureHeightPx = bmp.height
      val tex = IntArray(1)
      GLES30.glGenTextures(1, tex, 0)
      textureId = tex[0]
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
      GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
      isReady = true
    } finally {
      bmp.recycle()
    }
  }

  /**
   * Draws the exact frozen underwater background full-screen into the currently bound framebuffer
   * (typically the default screen framebuffer `0`), preserving aspect ratio with zero stretching.
   */
  fun draw(surfaceWidthPx: Int, surfaceHeightPx: Int) {
    val prog = program ?: return
    if (textureId == 0 || vao == 0 || textureWidthPx <= 0 || textureHeightPx <= 0) return

    val uvRect = computeCoverUvRect(
      imageWidth = textureWidthPx,
      imageHeight = textureHeightPx,
      surfaceWidth = surfaceWidthPx,
      surfaceHeight = surfaceHeightPx,
    )

    GLES30.glViewport(0, 0, surfaceWidthPx, surfaceHeightPx)
    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glDisable(GLES30.GL_BLEND)

    prog.use()
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
    prog.setInt("uBgTex", 0)
    prog.setVec4("uUvRect", uvRect.uMin, uvRect.vMin, uvRect.uMax, uvRect.vMax)

    GLES30.glBindVertexArray(vao)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    GLES30.glBindVertexArray(0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
  }

  fun release() {
    program?.release()
    program = null
    if (textureId != 0) {
      GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
      textureId = 0
    }
    if (vao != 0) {
      GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
      vao = 0
    }
    isReady = false
  }

  companion object {
    const val BACKGROUND_ASSET_PATH = "tab2_background/tab2_underwater_bg.png"
    const val EXPECTED_WIDTH_PX = 852
    const val EXPECTED_HEIGHT_PX = 1846
    const val EXPECTED_SIZE_BYTES = 1_557_320
    const val EXPECTED_SHA256 = "f3dc1549919f48f939df5a6cf5baf18fb6846164c6f857df8e20cfe247b495ba"
    const val EXPECTED_GIT_BLOB_SHA1 = "92562ff0a60bc63f3064c98af1922f817fee22c9"

    /**
     * Aspect-preserving center-cover UV mapping (`u` in `[0,1]` left->right, `v` in `[0,1]` top->bottom).
     * Never stretches or distorts the source PNG. When the surface is wider than the 852x1846 portrait
     * image, gently biases vertical framing (`0.46`) so BOTH the top sunlit water surface AND bottom
     * aquatic plants remain visible.
     */
    fun computeCoverUvRect(
      imageWidth: Int,
      imageHeight: Int,
      surfaceWidth: Int,
      surfaceHeight: Int,
    ): CoverUvRect {
      val imgAspect = max(1, imageWidth).toFloat() / max(1, imageHeight).toFloat()
      val surfAspect = max(1, surfaceWidth).toFloat() / max(1, surfaceHeight).toFloat()
      return if (surfAspect > imgAspect) {
        // Surface is wider than image: full horizontal span, balanced vertical crop
        val visibleV = (imgAspect / surfAspect).coerceIn(0.1f, 1f)
        val extraV = 1f - visibleV
        val vMin = extraV * 0.46f
        CoverUvRect(0f, vMin, 1f, vMin + visibleV)
      } else {
        // Surface is taller/narrower than image: full vertical span (preserves 100% top surface & bottom plants), centered horizontal crop
        val visibleU = (surfAspect / imgAspect).coerceIn(0.1f, 1f)
        val uMin = (1f - visibleU) * 0.5f
        CoverUvRect(uMin, 0f, uMin + visibleU, 1f)
      }
    }

    fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun gitBlobSha1Hex(bytes: ByteArray): String {
      val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8)
      val md = MessageDigest.getInstance("SHA-1")
      md.update(header)
      md.update(bytes)
      return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    val VERT_SHADER = """#version 300 es
      precision highp float;
      precision highp int;
      out vec2 vScreenUv;
      void main() {
        vec2 pos = vec2((gl_VertexID == 1) ? 3.0 : -1.0, (gl_VertexID == 2) ? 3.0 : -1.0);
        gl_Position = vec4(pos, 0.0, 1.0);
        // Top-left (0,0) -> Bottom-right (1,1)
        vScreenUv = vec2(pos.x * 0.5 + 0.5, 0.5 - pos.y * 0.5);
      }
    """.trimIndent()

    val FRAG_SHADER = """#version 300 es
      precision highp float;
      precision highp int;
      in vec2 vScreenUv;
      uniform sampler2D uBgTex;
      uniform vec4 uUvRect;
      out vec4 fragColor;
      void main() {
        vec2 uv = mix(uUvRect.xy, uUvRect.zw, clamp(vScreenUv, 0.0, 1.0));
        vec4 col = texture(uBgTex, uv);
        fragColor = vec4(col.rgb, 1.0);
      }
    """.trimIndent()
  }
}
