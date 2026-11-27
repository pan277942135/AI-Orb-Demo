package com.example.waterworld

import android.opengl.GLES30

/**
 * Pooled offscreen RenderTarget manager supporting full-resolution RGBA8+Depth24 targets
 * and downsampled Bloom targets (1/2x and 1/4x). Enforces FBO completeness, prevents
 * FBO texture read/write feedback loops, and guarantees clean single-release lifecycle.
 */
internal class WaterWorldRenderTargetPool(
    val fullSize: Int = 1254
) {
    data class PooledTargetSpec(
        val key: String,
        val width: Int,
        val height: Int,
        val withDepth: Boolean
    )

    private val targets = linkedMapOf<String, WaterWorldRenderTarget>()
    private val specs = linkedMapOf<String, PooledTargetSpec>()
    private val inUse = mutableSetOf<String>()

    val allocatedCount: Int get() = targets.size
    val activeKeys: Set<String> get() = inUse.toSet()

    val scene: WaterWorldRenderTarget by lazy { acquireFullRes("scene", withDepth = true) }
    val snapshot: WaterWorldRenderTarget by lazy { acquireFullRes("snapshot", withDepth = false) }
    val finalTarget: WaterWorldRenderTarget by lazy { acquireFullRes("finalTarget", withDepth = false) }
    val diagnostic: WaterWorldRenderTarget by lazy { acquireFullRes("diagnostic", withDepth = false) }
    val bloomHalfA: WaterWorldRenderTarget by lazy { acquireDownsampled("bloomHalfA", divisor = 2) }
    val bloomHalfB: WaterWorldRenderTarget by lazy { acquireDownsampled("bloomHalfB", divisor = 2) }
    val bloomQuarterA: WaterWorldRenderTarget by lazy { acquireDownsampled("bloomQuarterA", divisor = 4) }
    val bloomQuarterB: WaterWorldRenderTarget by lazy { acquireDownsampled("bloomQuarterB", divisor = 4) }

    fun acquireFullRes(key: String, withDepth: Boolean = true): WaterWorldRenderTarget =
        acquire(key, fullSize, fullSize, withDepth)

    fun acquireDownsampled(key: String, divisor: Int): WaterWorldRenderTarget {
        require(divisor >= 1) { "Invalid downsample divisor: $divisor" }
        val w = (fullSize / divisor).coerceAtLeast(1)
        return acquire(key, w, w, withDepth = false)
    }

    fun acquire(key: String, width: Int, height: Int, withDepth: Boolean): WaterWorldRenderTarget {
        val existing = targets[key]
        if (existing != null) {
            val spec = checkNotNull(specs[key])
            check(spec.width == width && spec.height == height && spec.withDepth == withDepth) {
                "Pooled target '$key' spec mismatch: existing=$spec requested=${width}x${height} depth=$withDepth"
            }
            inUse += key
            return existing
        }
        val created = WaterWorldRenderTarget(size = width, height = height, withDepth = withDepth)
        targets[key] = created
        specs[key] = PooledTargetSpec(key, width, height, withDepth)
        inUse += key
        return created
    }

    fun markIdle(key: String) {
        inUse.remove(key)
    }

    fun releaseTarget(key: String) {
        inUse.remove(key)
        specs.remove(key)
        targets.remove(key)?.release()
    }

    /** Verifies no source texture ID is also bound as the color attachment of the destination FBO. */
    fun assertNoFeedback(sourceTextures: Collection<Int>, destination: WaterWorldRenderTarget, passId: String) {
        assertNoFeedbackIds(sourceTextures, destination.texture, passId)
    }

    fun copyColor(
        source: WaterWorldRenderTarget,
        destination: WaterWorldRenderTarget,
        label: String,
        filter: Int = GLES30.GL_NEAREST
    ) {
        assertNoFeedback(listOf(source.texture), destination, label)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, source.framebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, destination.framebuffer)
        GLES30.glBlitFramebuffer(
            0, 0, source.size, source.height,
            0, 0, destination.size, destination.height,
            GLES30.GL_COLOR_BUFFER_BIT,
            filter
        )
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "WaterWorld blit '$label' GL error 0x${err.toString(16)}" }
    }

    fun copyColorAndDepth(
        source: WaterWorldRenderTarget,
        destination: WaterWorldRenderTarget,
        label: String
    ) {
        assertNoFeedback(listOf(source.texture), destination, label)
        require(source.size == destination.size && source.height == destination.height) {
            "Color+Depth blit requires matching dimensions for '$label'"
        }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, source.framebuffer)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, destination.framebuffer)
        val mask = if (source.withDepth && destination.withDepth) {
            GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT
        } else {
            GLES30.GL_COLOR_BUFFER_BIT
        }
        GLES30.glBlitFramebuffer(
            0, 0, source.size, source.height,
            0, 0, destination.size, destination.height,
            mask,
            GLES30.GL_NEAREST
        )
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "WaterWorld color+depth blit '$label' GL error 0x${err.toString(16)}" }
    }

    /** Estimates total allocated GPU memory in bytes for all active FBO attachments (RGBA8 + Depth24). */
    fun estimatedGpuMemoryBytes(): Long {
        var total = 0L
        for (spec in specs.values) {
            val px = spec.width.toLong() * spec.height.toLong()
            total += px * 4L // GL_RGBA8 color attachment
            if (spec.withDepth) total += px * 4L // GL_DEPTH_COMPONENT24 (32-bit aligned in GPU VRAM)
        }
        return total
    }

    fun releaseAll() {
        for (target in targets.values) {
            target.release()
        }
        targets.clear()
        specs.clear()
        inUse.clear()
    }

    companion object {
        /** Pure JVM-testable feedback detector ensuring input textures never alias output FBO attachment. */
        fun assertNoFeedbackIds(sourceTextureIds: Collection<Int>, destinationTextureId: Int, passId: String) {
            for (srcTex in sourceTextureIds) {
                if (srcTex > 0 && srcTex == destinationTextureId) {
                    throw IllegalStateException(
                        "FBO feedback loop detected in pass '$passId': textureId=$srcTex is both input and output"
                    )
                }
            }
        }

        fun plannedTargetDimensions(fullSize: Int = 1254): Map<String, Pair<Int, Int>> = mapOf(
            "scene" to (fullSize to fullSize),
            "snapshot" to (fullSize to fullSize),
            "finalTarget" to (fullSize to fullSize),
            "diagnostic" to (fullSize to fullSize),
            "bloomHalfA" to (fullSize / 2 to fullSize / 2),
            "bloomHalfB" to (fullSize / 2 to fullSize / 2),
            "bloomQuarterA" to (fullSize / 4 to fullSize / 4),
            "bloomQuarterB" to (fullSize / 4 to fullSize / 4)
        )

        fun plannedFboMemoryBytes(fullSize: Int = 1254): Long {
            var total = 0L
            for ((key, dims) in plannedTargetDimensions(fullSize)) {
                val px = dims.first.toLong() * dims.second.toLong()
                total += px * 4L
                if (key == "scene") total += px * 4L
            }
            return total
        }
    }
}
