package com.example.orb.task

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import com.example.orb.geometry.OrbGeometryBuilder
import com.example.orb.gl.GlBufferUtils
import com.example.orb.gl.GlMesh
import com.example.orb.gl.GlShaderProgram
import java.util.concurrent.Executors

/**
 * Renders the frozen user-supplied Tab 2 background PNG (with separate animated
 * water-surface caustics beneath the shared Orb) AND the single
 * cropped fishing photo (`assets/task_demo/fishing_photo.webp`) clipped cleanly
 * to the live [PhotoFrameGeometry] rounded rectangle.
 *
 * Decodes source bitmaps away from the GL thread and uploads them on the GL thread.
 * Tab 1 (`AI Orb`) pays ZERO startup or memory overhead.
 */
class PhotoTextureRenderer(
  private val visualLock: TaskVisualFidelityV41? = null,
) {

  private var bgProgram: GlShaderProgram? = null
  private var photoProgram: GlShaderProgram? = null
  private var fullscreenQuad: GlMesh? = null

  private var photoVao: Int = 0
  private var photoVbo: Int = 0
  private var backgroundTextureId: Int = 0
  private var photoTextureId: Int = 0
  private var backgroundLoadAttempted: Boolean = false
  private var photoLoadAttempted: Boolean = false
  @Volatile private var backgroundBitmap: Bitmap? = null
  @Volatile private var photoBitmap: Bitmap? = null
  @Volatile private var backgroundLoadError: String? = null
  @Volatile private var photoLoadError: String? = null
  private val bitmapDecodeExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "orb-task-background-decode").apply { isDaemon = true }
  }

  private var cachedPhotoFrame: PhotoFrameGeometry? = null
  private var cachedSurfaceW: Int = -1
  private var cachedSurfaceH: Int = -1
  private val photoQuadPadPx: Float = 16f

  val isPhotoTextureLoaded: Boolean get() = photoTextureId != 0
  val isBackgroundTextureLoaded: Boolean get() = backgroundTextureId != 0
  val backgroundAssetError: String? get() = backgroundLoadError

  val backgroundLoadStatus: String
    get() = when {
      isBackgroundTextureLoaded -> "LOADED"
      backgroundLoadError != null -> "FAILED: $backgroundLoadError"
      backgroundLoadAttempted -> "DECODING"
      else -> "NOT_REQUESTED"
    }

  fun init() {
    release()
    bgProgram = GlShaderProgram(
      TaskWaterLightShaders.TASK_BG_VERT,
      TaskWaterLightShaders.TASK_BG_FRAG,
      "TaskBackground",
    )
    photoProgram = GlShaderProgram(
      TaskWaterLightShaders.PHOTO_QUAD_VERT,
      TaskWaterLightShaders.PHOTO_QUAD_FRAG,
      "TaskFishingPhoto",
    )
    fullscreenQuad = OrbGeometryBuilder.createFullscreenQuad()

    val vaoArr = IntArray(1)
    val vboArr = IntArray(1)
    GLES30.glGenVertexArrays(1, vaoArr, 0)
    GLES30.glGenBuffers(1, vboArr, 0)
    photoVao = vaoArr[0]
    photoVbo = vboArr[0]
  }

  private fun ensureDecoded(assetManager: AssetManager?, path: String, isBackground: Boolean) {
    if (assetManager == null) return
    val attempted = if (isBackground) backgroundLoadAttempted else photoLoadAttempted
    val ready = if (isBackground) backgroundBitmap else photoBitmap
    if (attempted || ready != null) return
    if (isBackground) backgroundLoadAttempted = true else photoLoadAttempted = true
    bitmapDecodeExecutor.execute {
      try {
        val opts = BitmapFactory.Options().apply {
          inPreferredConfig = Bitmap.Config.ARGB_8888
          inScaled = false
        }
        val bitmap = assetManager.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
          ?: error("Bitmap decoder returned null for $path")
        if (isBackground) backgroundBitmap = bitmap else photoBitmap = bitmap
      } catch (e: Throwable) {
        val message = "${e.javaClass.simpleName}: ${e.message ?: "decode failed"}"
        if (isBackground) backgroundLoadError = message else photoLoadError = message
      }
    }
  }

  /** Must be called on the GL thread. The BitmapFactory work above never blocks rendering. */
  private fun uploadTextureIfReady(bitmap: Bitmap?, existingId: Int, mipmaps: Boolean): Int {
    if (existingId != 0 || bitmap == null) return existingId
    val ids = IntArray(1)
    GLES30.glGenTextures(1, ids, 0)
    val textureId = ids[0]
    if (textureId == 0) return 0
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
      if (mipmaps) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    if (mipmaps) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    return textureId
  }

  fun drawBackground(
    time: Float,
    renderWidth: Int,
    renderHeight: Int,
    layout: TaskSceneLayoutSnapshot,
    hdrScale: Float,
    assetManager: AssetManager?,
  ) {
    val prog = bgProgram ?: return
    val quad = fullscreenQuad ?: return
    ensureDecoded(
      assetManager,
      visualLock?.background?.assetPath ?: DEFAULT_BACKGROUND_ASSET_PATH,
      isBackground = true,
    )
    backgroundTextureId = uploadTextureIfReady(backgroundBitmap, backgroundTextureId, mipmaps = false)
    if (backgroundTextureId == 0) return
    val orbUv = layout.orbTransform.orbCenterGlUv

    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glDisable(GLES30.GL_CULL_FACE)

    prog.use()
    prog.setFloat("uTime", time)
    prog.setVec2("uResolution", renderWidth.toFloat(), renderHeight.toFloat())
    prog.setVec2("uOrbCenterUv", orbUv.x, orbUv.y)
    prog.setFloat("uOrbRadiusNormH", layout.orbTransform.orbRadiusNormH)
    prog.setFloat("uHdrScale", hdrScale)
    prog.setVec2(
      "uBackgroundSizePx",
      backgroundBitmap?.width?.toFloat() ?: 1f,
      backgroundBitmap?.height?.toFloat() ?: 1f,
    )
    prog.setFloat("uCausticsGain", visualLock?.background?.causticsGain ?: 0.35f)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backgroundTextureId)
    prog.setInt("uBackgroundTex", 0)
    quad.draw()
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
  }

  fun drawPhoto(
    layout: TaskSceneLayoutSnapshot,
    hdrScale: Float,
    assetManager: AssetManager?,
  ) {
    ensureDecoded(assetManager, DEMO_PHOTO_ASSET_PATH, isBackground = false)
    photoTextureId = uploadTextureIfReady(photoBitmap, photoTextureId, mipmaps = true)
    if (photoTextureId == 0 || photoVao == 0) return
    val prog = photoProgram ?: return
    val photo = layout.photoFrame
    if (!photo.isValid()) return

    updatePhotoQuadIfNeeded(photo, layout.surfaceWidthPx, layout.surfaceHeightPx)

    GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    GLES30.glDepthMask(false)
    GLES30.glDisable(GLES30.GL_CULL_FACE)
    GLES30.glEnable(GLES30.GL_BLEND)
    GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

    prog.use()
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTextureId)
    prog.setInt("uPhotoTex", 0)
    prog.setVec2("uPhotoSizePx", photo.widthPx, photo.heightPx)
    prog.setFloat("uCornerRadiusPx", photo.clampedCornerRadiusPx)
    prog.setFloat("uPadPx", photoQuadPadPx)
    prog.setFloat("uHdrScale", hdrScale)

    GLES30.glBindVertexArray(photoVao)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    GLES30.glBindVertexArray(0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
  }

  private fun updatePhotoQuadIfNeeded(photo: PhotoFrameGeometry, sw: Int, sh: Int) {
    if (cachedPhotoFrame == photo && cachedSurfaceW == sw && cachedSurfaceH == sh) return
    cachedPhotoFrame = photo
    cachedSurfaceW = sw
    cachedSurfaceH = sh

    val w = sw.coerceAtLeast(1).toFloat()
    val h = sh.coerceAtLeast(1).toFloat()
    val l = ((photo.leftPx - photoQuadPadPx) / w) * 2f - 1f
    val r = ((photo.rightPx + photoQuadPadPx) / w) * 2f - 1f
    val t = 1f - ((photo.topPx - photoQuadPadPx) / h) * 2f
    val b = 1f - ((photo.bottomPx + photoQuadPadPx) / h) * 2f

    // Triangle strip: BL, BR, TL, TR with UV (0,0) bottom-left -> (1,1) top-right
    val verts = floatArrayOf(
      l, b, 0f, 0f,
      r, b, 1f, 0f,
      l, t, 0f, 1f,
      r, t, 1f, 1f,
    )

    GLES30.glBindVertexArray(photoVao)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, photoVbo)
    GLES30.glBufferData(
      GLES30.GL_ARRAY_BUFFER,
      verts.size * 4,
      GlBufferUtils.toFloatBuffer(verts),
      GLES30.GL_DYNAMIC_DRAW,
    )
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
    GLES30.glEnableVertexAttribArray(1)
    GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
    GLES30.glBindVertexArray(0)
  }

  fun release() {
    bgProgram?.release()
    bgProgram = null
    photoProgram?.release()
    photoProgram = null
    fullscreenQuad?.release()
    fullscreenQuad = null
    if (photoVao != 0) {
      GLES30.glDeleteVertexArrays(1, intArrayOf(photoVao), 0)
      photoVao = 0
    }
    if (photoVbo != 0) {
      GLES30.glDeleteBuffers(1, intArrayOf(photoVbo), 0)
      photoVbo = 0
    }
    if (photoTextureId != 0) {
      GLES30.glDeleteTextures(1, intArrayOf(photoTextureId), 0)
      photoTextureId = 0
    }
    if (backgroundTextureId != 0) {
      GLES30.glDeleteTextures(1, intArrayOf(backgroundTextureId), 0)
      backgroundTextureId = 0
    }
    cachedPhotoFrame = null
  }

  companion object {
    const val DEFAULT_BACKGROUND_ASSET_PATH = "task_demo/background_lake_glass_v41.png"
    const val DEMO_PHOTO_ASSET_PATH = "task_demo/fishing_photo.webp"
  }
}
