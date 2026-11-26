package com.example.waterworld

import android.opengl.GLES30

/**
 * Content-SHA-256 deduplicated texture cache with strict path-conflict detection and
 * single-delete GPU lifecycle tracking. Pure registry logic is separated so JVM tests
 * can verify caching, alias sharing, conflict errors, and single release without GL.
 */
internal class WaterWorldTextureCache(
    private val uploader: (relativePath: String, expectedSha256: String) -> Int,
    private val deleter: (textureId: Int) -> Unit = { id ->
        GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }
) {
    private val pathToSha = mutableMapOf<String, String>()
    private val shaToTextureId = mutableMapOf<String, Int>()
    private var uploadCount = 0
    private var cacheHitCount = 0

    val totalUploadCount: Int get() = uploadCount
    val totalCacheHitCount: Int get() = cacheHitCount

    fun uniqueGpuTextureCount(): Int = shaToTextureId.size
    fun logicalAliasCount(): Int = pathToSha.size

    fun acquire(relativePath: String, expectedSha256: String): Int {
        require(relativePath.isNotBlank()) { "Blank texture path" }
        require(!relativePath.contains("master", ignoreCase = true) && !relativePath.contains("奇幻")) {
            "Master artwork is forbidden as a scene texture: $relativePath"
        }
        require(expectedSha256.length == 64 && expectedSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid SHA-256 for $relativePath: $expectedSha256"
        }
        val existingSha = pathToSha[relativePath]
        if (existingSha != null && existingSha != expectedSha256) {
            throw IllegalStateException(
                "Conflicting SHA-256 for logical texture path '$relativePath': existing=$existingSha vs requested=$expectedSha256"
            )
        }
        pathToSha[relativePath] = expectedSha256

        // Share GPU texture ID across identical content SHAs (even under path aliases)
        val cached = shaToTextureId[expectedSha256]
        if (cached != null) {
            cacheHitCount++
            return cached
        }
        val uploadedId = uploader(relativePath, expectedSha256)
        check(uploadedId > 0) { "Invalid GL texture ID $uploadedId for $relativePath" }
        shaToTextureId[expectedSha256] = uploadedId
        uploadCount++
        return uploadedId
    }

    /** Deletes every unique GPU texture handle exactly once. */
    fun releaseAll(): Int {
        val uniqueIds = shaToTextureId.values.toSet()
        for (id in uniqueIds) {
            if (id > 0) deleter(id)
        }
        shaToTextureId.clear()
        pathToSha.clear()
        return uniqueIds.size
    }
}
