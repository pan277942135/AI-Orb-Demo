package com.example.waterworld

import android.opengl.GLES30

/** Color/depth FBO owned by the independent static renderer's EGL context. */
internal class WaterWorldRenderTarget(val size: Int = 1254) {
    val framebuffer: Int
    val texture: Int
    private val depth: Int
    init {
        require(size > 0)
        val ids = IntArray(3)
        GLES30.glGenFramebuffers(1, ids, 0)
        GLES30.glGenTextures(1, ids, 1)
        GLES30.glGenRenderbuffers(1, ids, 2)
        framebuffer = ids[0]; texture = ids[1]; depth = ids[2]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, size, size)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depth)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, size, size)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }
    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, size, size)
    }
    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        GLES30.glDeleteRenderbuffers(1, intArrayOf(depth), 0)
    }
}
