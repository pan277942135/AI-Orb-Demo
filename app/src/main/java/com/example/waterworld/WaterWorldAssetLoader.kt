package com.example.waterworld

import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Reads an extracted, hash-verified scene package. No master image may be bound as albedo. */
internal class WaterWorldAssetLoader(private val root: File) {
    private fun path(relative: String): File {
        val file = File(root, relative).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator))
        return file
    }
    fun json(relative: String): JSONObject = JSONObject(path(relative).readText())
    var uploadedTextureBytes: Long = 0L
        private set

    fun resetUploadedStats() {
        uploadedTextureBytes = 0L
    }

    fun texture(relative: String, expectedSha: String): Int {
        require(!relative.contains("master", ignoreCase = true))
        val bytes = path(relative).readBytes()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual == expectedSha) { "Scene texture identity mismatch: $relative" }
        val options = BitmapFactory.Options().apply { inScaled = false; inPremultiplied = false }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        val texBytes = bitmap.width.toLong() * bitmap.height.toLong() * 4L
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Scene texture upload failed: $relative" }
            uploadedTextureBytes += texBytes
            return ids[0]
        } catch (failure: Throwable) {
            GLES30.glDeleteTextures(1, ids, 0)
            throw failure
        } finally { bitmap.recycle() }
    }
}
