package com.example.waterworld

import android.opengl.GLES30
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Indexed position/normal/UV geometry shared by sphere and immutable SVG ribbons. */
internal class WaterWorldMesh(json: JSONObject) {
    private val vertices = json.getJSONArray("vertices")
    private val indices = json.getJSONArray("indices")
    private val handles = IntArray(3)
    private val count = indices.length()

    init {
        require(count > 0 && count % 3 == 0)
        val vertexData = ByteBuffer.allocateDirect(vertices.length() * 8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until vertices.length()) {
            val row = vertices.getJSONArray(i)
            require(row.length() == 8)
            for (j in 0 until 8) {
                val value = row.getDouble(j).toFloat()
                require(value.isFinite())
                vertexData.put(value)
            }
        }
        vertexData.position(0)
        val indexData = ByteBuffer.allocateDirect(count * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()
        for (i in 0 until count) {
            val index = indices.getInt(i)
            require(index in 0 until vertices.length())
            indexData.put(index)
        }
        indexData.position(0)
        GLES30.glGenVertexArrays(1, handles, 0)
        GLES30.glGenBuffers(2, handles, 1)
        GLES30.glBindVertexArray(handles[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, handles[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexData.capacity() * 4, vertexData, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, handles[2])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, count * 4, indexData, GLES30.GL_STATIC_DRAW)
        for ((location, size, offset) in listOf(Triple(0, 3, 0), Triple(1, 3, 12), Triple(2, 2, 24))) {
            GLES30.glEnableVertexAttribArray(location)
            GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, 32, offset)
        }
        GLES30.glBindVertexArray(0)
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "WaterWorld geometry upload failed" }
    }

    fun draw() {
        GLES30.glBindVertexArray(handles[0])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    /** Call only while the owning EGL context is current. Recreate on context loss. */
    fun release() {
        GLES30.glDeleteBuffers(2, handles, 1)
        GLES30.glDeleteVertexArrays(1, handles, 0)
        handles.fill(0)
    }
}
