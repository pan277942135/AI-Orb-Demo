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

/**
 * Renders the Tab 2 (`TASK_PERIMETER_DEMO`) light-cyan aquatic studio background
 * (with animated water-surface caustics beneath the shared Orb) AND the single
 * cropped fishing photo (`assets/task_demo/fishing_photo.webp`) clipped cleanly
 * to the live [PhotoFrameGeometry] rounded rectangle.
 *
 * Lazily decodes & uploads `fishing_photo.webp` ONCE on first Tab 2 frame so
 * Tab 1 (`AI Orb`) pays ZERO startup or memory overhead.
 */
class PhotoTextureRenderer {

  private var bgProgram: GlShaderProgram? = null
  private var photoProgram: GlShaderProgram? = null
  private var fullscreenQuad: GlMesh? = null

  private var photoVao: Int = 0
  private var photoVbo: Int = 0
  private var photoTextureId: Int = 0
  private var photoLoadAttempted: Boolean = false
  private var photoLoaded: Boolean = false
  private var photoSourceWidthPx: Int = 0
  private var photoSourceHeightPx: Int = 0

  private var cachedPhotoFrame: PhotoFrameGeometry? = null
  private var cachedSurfaceW: Int = -1
  private var cachedSurfaceH: Int = -1
  private val photoQuadPadPx: Float = 16f

  val isPhotoTextureLoaded: Boolean get() = photoLoaded
  val sourceWidthPx: Int get() = photoSourceWidthPx
  val sourceHeightPx: Int get() = photoSourceHeightPx

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

  fun ensurePhotoLoaded(assetManager: AssetManager?) {
    if (photoLoadAttempted || assetManager == null) return
    photoLoadAttempted = true
    try {
      val opts = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
      }
      val bmp = assetManager.open(DEMO_PHOTO_ASSET_PATH).use { stream ->
        BitmapFactory.decodeStream(stream, null, opts)
      } ?: return

      try {
        val texArr = IntArray(1)
        GLES30.glGenTextures(1, texArr, 0)
        photoTextureId = texArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTextureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        photoSourceWidthPx = bmp.width
        photoSourceHeightPx = bmp.height
        photoLoaded = true
      } finally {
        bmp.recycle()
      }
    } catch (_: Throwable) {
      photoLoaded = false
    }
  }

  /**
   * Replaces the photo texture on the GL thread with a caller-supplied EXIF-normalized bitmap.
   * Does not recycle [bitmap] (caller owns lifecycle).
   */
  fun uploadCustomPhotoBitmap(bitmap: Bitmap) {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
    if (photoTextureId == 0) {
      val texArr = IntArray(1)
      GLES30.glGenTextures(1, texArr, 0)
      photoTextureId = texArr[0]
    }
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTextureId)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    photoSourceWidthPx = bitmap.width
    photoSourceHeightPx = bitmap.height
    photoLoadAttempted = true
    photoLoaded = true
  }

  fun resetToDefaultDemoPhoto() {
    photoLoadAttempted = false
    photoLoaded = false
    photoSourceWidthPx = 0
    photoSourceHeightPx = 0
  }

  fun drawBackground(
    time: Float,
    renderWidth: Int,
    renderHeight: Int,
    layout: TaskSceneLayoutSnapshot,
    hdrScale: Float,
  ) {
    val prog = bgProgram ?: return
    val quad = fullscreenQuad ?: return
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
    quad.draw()
  }

  fun drawPhoto(
    layout: TaskSceneLayoutSnapshot,
    hdrScale: Float,
    assetManager: AssetManager?,
    colorMode: PhotoDisplayColorMode = PhotoDisplayColorMode.LEGACY_HDR_POST_COMP,
    useCenterCropUv: Boolean = false,
  ) {
    ensurePhotoLoaded(assetManager)
    if (!photoLoaded || photoTextureId == 0 || photoVao == 0) return
    val prog = photoProgram ?: return
    val photo = layout.photoFrame
    if (!photo.isValid()) return

    updatePhotoQuadIfNeeded(photo, layout.surfaceWidthPx, layout.surfaceHeightPx)

    val uvRect = if (useCenterCropUv && photoSourceWidthPx > 0 && photoSourceHeightPx > 0) {
      PhotoUvTransform.compute(
        srcWidthPx = photoSourceWidthPx,
        srcHeightPx = photoSourceHeightPx,
        frameWidthPx = photo.widthPx,
        frameHeightPx = photo.heightPx,
      )
    } else {
      PhotoUvRect.IDENTITY
    }

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
    prog.setInt("uDirectDisplaySrgb", colorMode.shaderFlag)
    prog.setVec4("uPhotoUvRect", uvRect.uMin, uvRect.vMin, uvRect.uMax, uvRect.vMax)

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
    photoLoadAttempted = false
    photoLoaded = false
    photoSourceWidthPx = 0
    photoSourceHeightPx = 0
    cachedPhotoFrame = null
  }

  companion object {
    const val DEMO_PHOTO_ASSET_PATH = "task_demo/fishing_photo.webp"
  }
}
