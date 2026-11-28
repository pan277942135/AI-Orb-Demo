package com.example.waterworld

import android.opengl.GLES30
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Indexed position/normal/UV geometry shared by sphere, fish grids, ribbons, plants, rocks & bubbles.
 *
 * Work 3 enhancement: supports deterministic load-time grid subdivision (`subdividePlantGrid = true`)
 * for 4-vertex plant quads so GPU vertex-shader root-pinned sway bends organically without modifying
 * frozen assets or altering T=0 static rasterization.
 */
internal class WaterWorldMesh(
    json: JSONObject,
    subdividePlantGrid: Boolean = false,
    computeRibbonHalfWidths: Boolean = false,
) {
    private val handles = IntArray(3)
    private val count: Int
    val vertexCount: Int
    val centerX: Float
    val centerY: Float
    val widthPx: Float
    val heightPx: Float

    init {
        val rawVertices = json.getJSONArray("vertices")
        val rawIndices = json.getJSONArray("indices")
        require(rawIndices.length() > 0 && rawIndices.length() % 3 == 0)

        val built = when {
            computeRibbonHalfWidths -> buildRibbonBuffersWithHalfWidths(rawVertices, rawIndices)
            subdividePlantGrid && rawVertices.length() == 4 && rawIndices.length() == 6 ->
                buildSubdividedQuadGrid(rawVertices, cols = 6, rows = 12)
            else -> buildDirectBuffers(rawVertices, rawIndices)
        }

        vertexCount = built.vertexCount
        count = built.indexCount
        centerX = built.centerX
        centerY = built.centerY
        widthPx = built.widthPx
        heightPx = built.heightPx

        val vertexData = ByteBuffer.allocateDirect(built.vertexFloats.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexData.put(built.vertexFloats)
        vertexData.position(0)

        val indexData = ByteBuffer.allocateDirect(built.indices.size * 4)
            .order(ByteOrder.nativeOrder()).asIntBuffer()
        indexData.put(built.indices)
        indexData.position(0)

        GLES30.glGenVertexArrays(1, handles, 0)
        GLES30.glGenBuffers(2, handles, 1)
        GLES30.glBindVertexArray(handles[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, handles[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexData.capacity() * 4, vertexData, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, handles[2])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, count * 4, indexData, GLES30.GL_STATIC_DRAW)
        val strideBytes = built.floatsPerVertex * 4
        for ((location, size, offset) in listOf(Triple(0, 3, 0), Triple(1, 3, 12), Triple(2, 2, 24))) {
            GLES30.glEnableVertexAttribArray(location)
            GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, strideBytes, offset)
        }
        if (built.floatsPerVertex >= 9) {
            GLES30.glEnableVertexAttribArray(3)
            GLES30.glVertexAttribPointer(3, 1, GLES30.GL_FLOAT, false, strideBytes, 32)
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

    internal data class BuiltMeshData(
        val vertexFloats: FloatArray,
        val indices: IntArray,
        val vertexCount: Int,
        val indexCount: Int,
        val centerX: Float,
        val centerY: Float,
        val widthPx: Float,
        val heightPx: Float,
        val floatsPerVertex: Int = 8,
    )

    companion object {
        internal fun buildDirectBuffers(
            rawVertices: org.json.JSONArray,
            rawIndices: org.json.JSONArray,
        ): BuiltMeshData {
            val vCount = rawVertices.length()
            val iCount = rawIndices.length()
            val floats = FloatArray(vCount * 8)
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (i in 0 until vCount) {
                val row = rawVertices.getJSONArray(i)
                require(row.length() == 8)
                for (j in 0 until 8) {
                    val value = row.getDouble(j).toFloat()
                    require(value.isFinite())
                    floats[i * 8 + j] = value
                }
                val x = floats[i * 8]
                val y = floats[i * 8 + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            val idxs = IntArray(iCount)
            for (i in 0 until iCount) {
                val idx = rawIndices.getInt(i)
                require(idx in 0 until vCount)
                idxs[i] = idx
            }
            return BuiltMeshData(
                vertexFloats = floats,
                indices = idxs,
                vertexCount = vCount,
                indexCount = iCount,
                centerX = (minX + maxX) * 0.5f,
                centerY = (minY + maxY) * 0.5f,
                widthPx = (maxX - minX).coerceAtLeast(0f),
                heightPx = (maxY - minY).coerceAtLeast(0f),
                floatsPerVertex = 8,
            )
        }

        /**
         * Five Resident Filaments V1 load-time vertex builder: reads the exact frozen paired ribbon
         * strip vertices without modifying any frozen asset files, computes each cross-section's exact
         * local half-width `0.5 * hypot(v0.x - v1.x, v0.y - v1.y)`, and appends it as vertex attribute
         * `location = 3` (`aLocalHalfWidthPx`) so GPU width breathing scales strictly relative to
         * local ribbon thickness rather than a hardcoded pixel offset.
         */
        internal fun buildRibbonBuffersWithHalfWidths(
            rawVertices: org.json.JSONArray,
            rawIndices: org.json.JSONArray,
        ): BuiltMeshData {
            val base = buildDirectBuffers(rawVertices, rawIndices)
            val vCount = base.vertexCount
            val outFloats = FloatArray(vCount * 9)
            var i = 0
            while (i < vCount) {
                val hasPair = (i + 1) < vCount
                val halfWidthPx = if (hasPair) {
                    val x0 = base.vertexFloats[i * 8]
                    val y0 = base.vertexFloats[i * 8 + 1]
                    val x1 = base.vertexFloats[(i + 1) * 8]
                    val y1 = base.vertexFloats[(i + 1) * 8 + 1]
                    0.5f * kotlin.math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
                } else {
                    0f
                }
                for (j in 0 until 8) {
                    outFloats[i * 9 + j] = base.vertexFloats[i * 8 + j]
                }
                outFloats[i * 9 + 8] = halfWidthPx
                if (hasPair) {
                    for (j in 0 until 8) {
                        outFloats[(i + 1) * 9 + j] = base.vertexFloats[(i + 1) * 8 + j]
                    }
                    outFloats[(i + 1) * 9 + 8] = halfWidthPx
                    i += 2
                } else {
                    i += 1
                }
            }
            return BuiltMeshData(
                vertexFloats = outFloats,
                indices = base.indices,
                vertexCount = base.vertexCount,
                indexCount = base.indexCount,
                centerX = base.centerX,
                centerY = base.centerY,
                widthPx = base.widthPx,
                heightPx = base.heightPx,
                floatsPerVertex = 9,
            )
        }

        /**
         * Deterministically subdivides a 4-vertex quad mesh into a `(cols+1) x (rows+1)` bilinear grid,
         * preserving exact boundary coordinates and UVs so T=0 rasterization is pixel-identical while
         * enabling smooth GPU vertex-shader plant sway at T>0.
         */
        internal fun buildSubdividedQuadGrid(
            rawVertices: org.json.JSONArray,
            cols: Int,
            rows: Int,
        ): BuiltMeshData {
            require(rawVertices.length() == 4)
            val corners = Array(4) { i ->
                val row = rawVertices.getJSONArray(i)
                require(row.length() == 8)
                FloatArray(8) { j -> row.getDouble(j).toFloat().also { require(it.isFinite()) } }
            }
            // Identify corners by UV (u, v) so bilinear interpolation always maps cleanly
            val c00 = corners.minBy { it[6] + it[7] }
            val c10 = corners.maxBy { it[6] - it[7] }
            val c01 = corners.maxBy { it[7] - it[6] }
            val c11 = corners.maxBy { it[6] + it[7] }

            val vxCount = (cols + 1) * (rows + 1)
            val floats = FloatArray(vxCount * 8)
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var p = 0
            for (r in 0..rows) {
                val tv = r.toFloat() / rows.toFloat()
                for (c in 0..cols) {
                    val tu = c.toFloat() / cols.toFloat()
                    for (j in 0 until 8) {
                        val top = c00[j] + (c10[j] - c00[j]) * tu
                        val bot = c01[j] + (c11[j] - c01[j]) * tu
                        floats[p + j] = top + (bot - top) * tv
                    }
                    val x = floats[p]
                    val y = floats[p + 1]
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    p += 8
                }
            }
            val idxs = IntArray(cols * rows * 6)
            var k = 0
            val stride = cols + 1
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val i00 = r * stride + c
                    val i10 = i00 + 1
                    val i01 = i00 + stride
                    val i11 = i01 + 1
                    idxs[k++] = i00
                    idxs[k++] = i10
                    idxs[k++] = i11
                    idxs[k++] = i00
                    idxs[k++] = i11
                    idxs[k++] = i01
                }
            }
            return BuiltMeshData(
                vertexFloats = floats,
                indices = idxs,
                vertexCount = vxCount,
                indexCount = idxs.size,
                centerX = (minX + maxX) * 0.5f,
                centerY = (minY + maxY) * 0.5f,
                widthPx = (maxX - minX).coerceAtLeast(0f),
                heightPx = (maxY - minY).coerceAtLeast(0f),
            )
        }
    }
}
