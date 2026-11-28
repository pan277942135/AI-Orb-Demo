package com.example.orb.task

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * EXIF-normalized decoded photo owned exclusively by the GL upload queue until consumed & recycled.
 */
data class DecodedPhotoSource(
  val bitmap: Bitmap,
  val widthPx: Int,
  val heightPx: Int,
  val exifOrientation: Int,
)

/**
 * Pure EXIF orientation normalizer (supporting all 8 EXIF orientations including flips & transposes)
 * and IO-thread URI photo decoder.
 */
object PhotoSourceDecoder {

  const val DEFAULT_MAX_DIMENSION_PX = 2048

  /**
   * Applies EXIF [orientation] (1..8) to [source] using [Matrix].
   * When a new Bitmap is created, [source] is recycled immediately so zero temporary bitmap leaks.
   * When orientation is NORMAL (1) or UNDEFINED (0), returns [source] untouched.
   */
  fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
    if (source.isRecycled) return source
    val matrix = Matrix()
    val needsTransform = when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.setScale(-1f, 1f)
        true
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.setRotate(180f)
        true
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.setScale(1f, -1f)
        true
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.setRotate(90f)
        matrix.postScale(-1f, 1f)
        true
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.setRotate(90f)
        true
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.setRotate(270f)
        matrix.postScale(-1f, 1f)
        true
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.setRotate(270f)
        true
      }
      else -> false
    }
    if (!needsTransform) return source
    val transformed = Bitmap.createBitmap(
      source,
      0,
      0,
      source.width,
      source.height,
      matrix,
      true,
    )
    if (transformed !== source && !source.isRecycled) {
      source.recycle()
    }
    return transformed
  }

  /**
   * Decodes [uri] on the calling background thread, reads EXIF `TAG_ORIENTATION`, applies
   * orientation normalization BEFORE returning dimensions so subsequent [PhotoUvTransform] crop
   * matches upright visual orientation.
   */
  fun decodeFromUri(
    context: Context,
    uri: Uri,
    maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
  ): DecodedPhotoSource? = runCatching {
    val resolver = context.contentResolver
    val orientation = runCatching {
      resolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_NORMAL,
        )
      } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
    val rawW = boundsOpts.outWidth
    val rawH = boundsOpts.outHeight
    if (rawW <= 0 || rawH <= 0) return@runCatching null

    val maxDim = maxOf(rawW, rawH)
    var sampleSize = 1
    while (maxDim / sampleSize > maxDimensionPx) sampleSize *= 2

    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inScaled = false
    }
    val rawBmp = resolver.openInputStream(uri)?.use {
      BitmapFactory.decodeStream(it, null, decodeOpts)
    } ?: return@runCatching null

    val normalized = applyExifOrientation(rawBmp, orientation)
    DecodedPhotoSource(
      bitmap = normalized,
      widthPx = normalized.width,
      heightPx = normalized.height,
      exifOrientation = orientation,
    )
  }.getOrNull()
}

/**
 * Single-owner asynchronous photo loader with monotonic generation cancellation:
 * - UI passes ONLY a [Uri?] (never a [Bitmap]!)
 * - Rapid A -> B -> C selection cancels/drops stale decodes and recycles stale bitmaps immediately
 * - Pending decoded bitmap is drained ONCE on the GL thread, uploaded, and recycled in `finally`
 * - Zero double-recycle, zero leak on dispose or rapid switching.
 */
class PhotoSourceLoadCoordinator(
  private val decodeFn: (Uri) -> DecodedPhotoSource?,
  private val onDecodedReady: () -> Unit = {},
  private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
    Thread(r, "Tab2PhotoSourceDecode").apply { isDaemon = true }
  },
  private val ownsExecutor: Boolean = true,
) {
  private data class PendingUpload(
    val generation: Long,
    val source: DecodedPhotoSource,
  )

  private val generation = AtomicLong(0L)
  private val disposed = AtomicBoolean(false)
  private val pending = AtomicReference<PendingUpload?>(null)
  @Volatile private var activeUri: Uri? = null

  val currentGeneration: Long get() = generation.get()
  val currentUri: Uri? get() = activeUri

  fun requestLoad(uri: Uri?) {
    if (disposed.get()) return
    activeUri = uri
    val gen = generation.incrementAndGet()
    if (uri == null) {
      recyclePending()
      return
    }
    executor.execute {
      if (disposed.get() || generation.get() != gen) return@execute
      val decoded = runCatching { decodeFn(uri) }.getOrNull() ?: return@execute
      if (disposed.get() || generation.get() != gen) {
        safeRecycle(decoded.bitmap)
        return@execute
      }
      val previous = pending.getAndSet(PendingUpload(gen, decoded))
      if (previous != null && previous.source.bitmap !== decoded.bitmap) {
        safeRecycle(previous.source.bitmap)
      }
      // Double-check after handoff in case dispose() raced right before getAndSet
      if (disposed.get()) {
        recyclePending()
      } else {
        onDecodedReady()
      }
    }
  }

  /**
   * Drains the latest decoded photo on the GL thread. Caller MUST recycle `source.bitmap`
   * immediately after texture upload (never retains reference across frames).
   */
  fun drainPendingForGlUpload(): DecodedPhotoSource? {
    val item = pending.getAndSet(null) ?: return null
    if (disposed.get() || item.generation != generation.get()) {
      safeRecycle(item.source.bitmap)
      return null
    }
    return item.source
  }

  fun dispose() {
    if (disposed.compareAndSet(false, true)) {
      generation.incrementAndGet()
      recyclePending()
      if (ownsExecutor) {
        executor.shutdownNow()
      }
    }
  }

  private fun recyclePending() {
    val prev = pending.getAndSet(null)
    if (prev != null) safeRecycle(prev.source.bitmap)
  }

  private fun safeRecycle(bmp: Bitmap) {
    if (!bmp.isRecycled) bmp.recycle()
  }
}
