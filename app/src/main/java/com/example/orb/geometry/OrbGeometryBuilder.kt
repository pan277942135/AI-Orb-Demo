package com.example.orb.geometry

import android.opengl.GLES30
import com.example.orb.gl.GlBufferUtils
import com.example.orb.gl.GlMesh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Float, val y: Float, val z: Float) {
  operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
  operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
  operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
  fun length(): Float = sqrt(x * x + y * y + z * z)
  fun normalized(): Vec3 {
    val len = length()
    return if (len > 1e-6f) Vec3(x / len, y / len, z / len) else Vec3(0f, 1f, 0f)
  }

  fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
  fun cross(o: Vec3) = Vec3(
    y * o.z - z * o.y,
    z * o.x - x * o.z,
    x * o.y - y * o.x,
  )
}

/**
 * Reusable pure-3D procedural geometry builder:
 * - Seamless geodesic Icosphere (eliminates UV pole artifacts on liquid glass)
 * - 3D Catmull-Rom closed/open spatial curves
 * - Parallel-Transport (Bishop) Frame 3D Ribbon Meshes & Fine Orbital Tubes
 * - Fullscreen screen-space quad
 */
object OrbGeometryBuilder {

  fun createFullscreenQuad(): GlMesh {
    val vertices = floatArrayOf(
      -1f, -1f, 0f, 0f,
      1f, -1f, 1f, 0f,
      -1f, 1f, 0f, 1f,
      1f, 1f, 1f, 1f,
    )
    val vao = IntArray(1)
    val vbo = IntArray(1)
    GLES30.glGenVertexArrays(1, vao, 0)
    GLES30.glGenBuffers(1, vbo, 0)

    GLES30.glBindVertexArray(vao[0])
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
    GLES30.glBufferData(
      GLES30.GL_ARRAY_BUFFER,
      vertices.size * 4,
      GlBufferUtils.toFloatBuffer(vertices),
      GLES30.GL_STATIC_DRAW,
    )
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
    GLES30.glEnableVertexAttribArray(1)
    GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
    GLES30.glBindVertexArray(0)
    return GlMesh(vao[0], vbo[0], 0, 4, GLES30.GL_TRIANGLE_STRIP)
  }

  /**
   * Generates a unit geodesic icosphere with smooth outward normals.
   * Vertex layout: position(3), normal(3) -> stride 24 bytes.
   */
  fun createIcosphere(subdivisions: Int): GlMesh {
    val t = (1.0f + sqrt(5.0f)) / 2.0f
    val vertices = ArrayList<Vec3>(12)
    fun addVertex(x: Float, y: Float, z: Float): Int {
      vertices.add(Vec3(x, y, z).normalized())
      return vertices.size - 1
    }

    addVertex(-1f, t, 0f); addVertex(1f, t, 0f); addVertex(-1f, -t, 0f); addVertex(1f, -t, 0f)
    addVertex(0f, -1f, t); addVertex(0f, 1f, t); addVertex(0f, -1f, -t); addVertex(0f, 1f, -t)
    addVertex(t, 0f, -1f); addVertex(t, 0f, 1f); addVertex(-t, 0f, -1f); addVertex(-t, 0f, 1f)

    var faces = mutableListOf(
      intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
      intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
      intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
      intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
    )

    val midpointCache = HashMap<Long, Int>()
    fun getMidpoint(i1: Int, i2: Int): Int {
      val small = minOf(i1, i2).toLong()
      val large = maxOf(i1, i2).toLong()
      val key = (small shl 32) or large
      return midpointCache.getOrPut(key) {
        val v1 = vertices[i1]
        val v2 = vertices[i2]
        addVertex((v1.x + v2.x) * 0.5f, (v1.y + v2.y) * 0.5f, (v1.z + v2.z) * 0.5f)
      }
    }

    repeat(subdivisions) {
      val nextFaces = ArrayList<IntArray>(faces.size * 4)
      for (f in faces) {
        val a = getMidpoint(f[0], f[1])
        val b = getMidpoint(f[1], f[2])
        val c = getMidpoint(f[2], f[0])
        nextFaces.add(intArrayOf(f[0], a, c))
        nextFaces.add(intArrayOf(f[1], b, a))
        nextFaces.add(intArrayOf(f[2], c, b))
        nextFaces.add(intArrayOf(a, b, c))
      }
      faces = nextFaces
    }

    val interleaved = FloatArray(vertices.size * 6)
    for (i in vertices.indices) {
      val v = vertices[i]
      val base = i * 6
      interleaved[base] = v.x
      interleaved[base + 1] = v.y
      interleaved[base + 2] = v.z
      interleaved[base + 3] = v.x
      interleaved[base + 4] = v.y
      interleaved[base + 5] = v.z
    }

    val indices = IntArray(faces.size * 3)
    for (i in faces.indices) {
      indices[i * 3] = faces[i][0]
      indices[i * 3 + 1] = faces[i][1]
      indices[i * 3 + 2] = faces[i][2]
    }

    val vao = IntArray(1)
    val vbo = IntArray(1)
    val ebo = IntArray(1)
    GLES30.glGenVertexArrays(1, vao, 0)
    GLES30.glGenBuffers(1, vbo, 0)
    GLES30.glGenBuffers(1, ebo, 0)

    GLES30.glBindVertexArray(vao[0])
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
    GLES30.glBufferData(
      GLES30.GL_ARRAY_BUFFER,
      interleaved.size * 4,
      GlBufferUtils.toFloatBuffer(interleaved),
      GLES30.GL_STATIC_DRAW,
    )
    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo[0])
    GLES30.glBufferData(
      GLES30.GL_ELEMENT_ARRAY_BUFFER,
      indices.size * 4,
      GlBufferUtils.toIntBuffer(indices),
      GLES30.GL_STATIC_DRAW,
    )
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
    GLES30.glEnableVertexAttribArray(1)
    GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
    GLES30.glBindVertexArray(0)

    return GlMesh(vao[0], vbo[0], ebo[0], indices.size)
  }

  /**
   * Evaluates a smooth 3D Catmull-Rom spline (closed or open) at parameter t in [0, 1].
   */
  fun sampleCatmullRom(points: List<Vec3>, t: Float, closed: Boolean): Vec3 {
    val n = points.size
    if (n < 2) return points.firstOrNull() ?: Vec3(0f, 0f, 0f)
    val clampedT = if (closed) ((t % 1f) + 1f) % 1f else t.coerceIn(0f, 0.9999f)
    val segmentCount = if (closed) n else (n - 1)
    val scaled = clampedT * segmentCount
    val seg = scaled.toInt().coerceIn(0, segmentCount - 1)
    val u = scaled - seg

    fun idx(i: Int): Vec3 =
      if (closed) points[((i % n) + n) % n]
      else points[i.coerceIn(0, n - 1)]

    val p0 = idx(seg - 1)
    val p1 = idx(seg)
    val p2 = idx(seg + 1)
    val p3 = idx(seg + 2)

    val u2 = u * u
    val u3 = u2 * u

    fun interp(a: Float, b: Float, c: Float, d: Float): Float =
      0.5f * ((2f * b) +
        (-a + c) * u +
        (2f * a - 5f * b + 4f * c - d) * u2 +
        (-a + 3f * b - 3f * c + d) * u3)

    return Vec3(
      interp(p0.x, p1.x, p2.x, p3.x),
      interp(p0.y, p1.y, p2.y, p3.y),
      interp(p0.z, p1.z, p2.z, p3.z),
    )
  }

  /**
   * Continuous, deterministic sculpted ribbon cross-section & twist profile along normalized arc-length s in [0, 1].
   * Enables organic botanical leaf silhouettes, asymmetric edges, local 3D turnovers, and tapered vanishing ends
   * with zero per-frame random jitter.
   */
  data class RibbonSurfaceProfile(
    val widthControlPoints: FloatArray,
    val leftAsymmetryPoints: FloatArray,
    val rightAsymmetryPoints: FloatArray,
    val twistProfileRad: FloatArray,
    val camberFactor: Float = 0.18f,
    val edgeWaveAmp: Float = 0.0f,
    val edgeWaveFreq: Float = 2.0f,
    val edgeWavePhase: Float = 0f,
    val tipFlexWeight: Float = 0.0f,
  ) {
    companion object {
      fun defaultFor(closed: Boolean, twistTurns: Float): RibbonSurfaceProfile =
        if (closed) {
          RibbonSurfaceProfile(
            widthControlPoints = floatArrayOf(0.75f, 1.05f, 0.82f, 1.00f, 0.75f),
            leftAsymmetryPoints = floatArrayOf(1f, 1f, 1f),
            rightAsymmetryPoints = floatArrayOf(1f, 1f, 1f),
            twistProfileRad = floatArrayOf(0f, (twistTurns * PI).toFloat(), (twistTurns * 2.0 * PI).toFloat()),
          )
        } else {
          RibbonSurfaceProfile(
            widthControlPoints = floatArrayOf(0f, 0.78f, 1.0f, 0.84f, 0f),
            leftAsymmetryPoints = floatArrayOf(1f, 1.08f, 1f),
            rightAsymmetryPoints = floatArrayOf(1f, 0.92f, 1f),
            twistProfileRad = floatArrayOf(0f, (twistTurns * PI).toFloat(), (twistTurns * 2.0 * PI).toFloat()),
            tipFlexWeight = 0.75f,
          )
        }
    }
  }

  /**
   * Evaluates a C1-smooth Catmull-Rom scalar profile at normalized arc-length [s] in [0, 1].
   */
  fun sampleScalarProfile(values: FloatArray, s: Float, closed: Boolean = false): Float {
    val n = values.size
    if (n == 0) return 1f
    if (n == 1) return values[0]
    val clamped = if (closed) ((s % 1f) + 1f) % 1f else s.coerceIn(0f, 1f)
    val segCount = if (closed) n else (n - 1)
    val scaled = clamped * segCount
    val seg = scaled.toInt().coerceIn(0, segCount - 1)
    val u = (scaled - seg).coerceIn(0f, 1f)

    fun at(i: Int): Float = if (closed) values[((i % n) + n) % n] else values[i.coerceIn(0, n - 1)]

    val p0 = at(seg - 1)
    val p1 = at(seg)
    val p2 = at(seg + 1)
    val p3 = at(seg + 2)
    val u2 = u * u
    val u3 = u2 * u
    return 0.5f * ((2f * p1) +
      (-p0 + p2) * u +
      (2f * p0 - 5f * p1 + 4f * p2 - p3) * u2 +
      (-p0 + 3f * p1 - 3f * p2 + p3) * u3)
  }

  /**
   * Pure-Kotlin evaluation of a sculpted ribbon cross-section (used by both GPU mesh generation and unit tests).
   */
  data class RibbonSampleMetrics(
    val leftHalfWidth: Float,
    val rightHalfWidth: Float,
    val totalWidth: Float,
    val twistRad: Float,
    val flexWeight: Float,
  )

  fun evaluateRibbonProfileAt(
    u: Float,
    halfWidth: Float,
    closed: Boolean,
    taperEnds: Boolean,
    profile: RibbonSurfaceProfile,
  ): RibbonSampleMetrics {
    val s = u.coerceIn(0f, 1f)
    var baseW = sampleScalarProfile(profile.widthControlPoints, s, closed = closed).coerceAtLeast(0f)
    if (taperEnds) {
      val fadeIn = (s / 0.13f).coerceIn(0f, 1f)
      val fadeOut = ((1f - s) / 0.17f).coerceIn(0f, 1f)
      val sIn = fadeIn * fadeIn * (3f - 2f * fadeIn)
      val sOut = fadeOut * fadeOut * (3f - 2f * fadeOut)
      baseW *= sIn * sOut
    }

    val leftScale = sampleScalarProfile(profile.leftAsymmetryPoints, s, closed = closed).coerceIn(0.35f, 1.65f)
    val rightScale = sampleScalarProfile(profile.rightAsymmetryPoints, s, closed = closed).coerceIn(0.35f, 1.65f)
    val waveL = 1f + profile.edgeWaveAmp * sin(s * (2.0 * PI).toFloat() * profile.edgeWaveFreq + profile.edgeWavePhase)
    val waveR = 1f + profile.edgeWaveAmp * cos(s * (2.0 * PI).toFloat() * (profile.edgeWaveFreq * 0.83f) - profile.edgeWavePhase * 1.2f)

    val leftW = (halfWidth * baseW * leftScale * waveL).coerceAtLeast(0f)
    val rightW = (halfWidth * baseW * rightScale * waveR).coerceAtLeast(0f)
    val twist = sampleScalarProfile(profile.twistProfileRad, s, closed = false)

    // Open kelp blades flex progressively stronger toward free drifting ends; closed water ribbons flex smoothly
    val flex = if (!closed) {
      val edgeDist = kotlin.math.abs(s * 2f - 1f) // 0 at mid-body, 1 at free tips
      val tipBoost = edgeDist * edgeDist
      0.34f + 0.66f * (profile.tipFlexWeight * tipBoost + (1f - profile.tipFlexWeight) * 0.35f)
    } else {
      0.55f + 0.45f * sin(s * (2.0 * PI).toFloat() + profile.edgeWavePhase)
    }

    return RibbonSampleMetrics(
      leftHalfWidth = leftW,
      rightHalfWidth = rightW,
      totalWidth = leftW + rightW,
      twistRad = twist,
      flexWeight = flex.coerceIn(0.15f, 1.15f),
    )
  }

  /**
   * Generates a real sculpted 3D Ribbon Surface Mesh along a Catmull-Rom spline using:
   * - Parallel-transport (Bishop) frames (zero gimbal flip through steep inflection points)
   * - Continuous arc-length WidthProfile(s) & asymmetric left/right botanical contours
   * - Smooth non-uniform local TwistProfile(s)
   * - Per-vertex unit spine tangent & tip flexibility weight for GPU Rodrigues twist & aquatic sway
   *
   * Vertex layout (stride 60 bytes = 15 floats):
   *   loc 0: centerPos   (vec3) - spine point
   *   loc 1: offset      (vec3) - asymmetric sculpted cross-section offset from spine
   *   loc 2: normal      (vec3) - smooth curved surface normal
   *   loc 3: uv          (vec2) - u in [0,1] along arc length, v in [-1,1] across width
   *   loc 4: tangentFlex (vec4) - xyz = unit spine tangent T(s), w = flexibility weight
   */
  fun createRibbonMesh(
    controlPoints: List<Vec3>,
    segments: Int,
    closed: Boolean,
    halfWidth: Float,
    twistTurns: Float,
    taperEnds: Boolean,
    crossSlices: Int = 6,
    surfaceProfile: RibbonSurfaceProfile? = null,
  ): GlMesh {
    val profile = surfaceProfile ?: RibbonSurfaceProfile.defaultFor(closed, twistTurns)
    val spine = ArrayList<Vec3>(segments + 1)
    for (i in 0..segments) {
      val u = i.toFloat() / segments.toFloat()
      spine.add(sampleCatmullRom(controlPoints, u, closed))
    }

    val tangents = ArrayList<Vec3>(segments + 1)
    for (i in 0..segments) {
      val prev = if (i == 0) (if (closed) spine[segments - 1] else spine[0]) else spine[i - 1]
      val next = if (i == segments) (if (closed) spine[1] else spine[segments]) else spine[i + 1]
      tangents.add((next - prev).normalized())
    }

    // Parallel-transport (Bishop) frames prevent gimbal flip along steep 3D curves
    val normals = ArrayList<Vec3>(segments + 1)
    val binormals = ArrayList<Vec3>(segments + 1)
    val t0 = tangents[0]
    val radialRef = spine[0].normalized()
    val initN = (radialRef - t0 * radialRef.dot(t0)).let {
      if (it.length() > 1e-4f) it.normalized() else Vec3(0f, 1f, 0f).cross(t0).normalized()
    }
    normals.add(initN)
    binormals.add(t0.cross(initN).normalized())

    for (i in 1..segments) {
      val prevT = tangents[i - 1]
      val currT = tangents[i]
      val prevN = normals[i - 1]
      val axis = prevT.cross(currT)
      val axisLen = axis.length()
      val nextN = if (axisLen > 1e-5f) {
        val k = axis * (1f / axisLen)
        val cosA = prevT.dot(currT).coerceIn(-1f, 1f)
        val sinA = axisLen.coerceIn(0f, 1f)
        (prevN * cosA + k.cross(prevN) * sinA + k * (k.dot(prevN) * (1f - cosA))).normalized()
      } else {
        prevN
      }
      val orthoN = (nextN - currT * nextN.dot(currT)).normalized()
      normals.add(orthoN)
      binormals.add(currT.cross(orthoN).normalized())
    }

    // Smoothly absorb holonomy frame-closure angle on closed ribbons so there is zero twist seam
    var closureCorrection = 0f
    if (closed) {
      val nStart = normals[0]
      val nEnd = normals[segments]
      val bEnd = binormals[segments]
      val dotN = nStart.dot(nEnd).coerceIn(-1f, 1f)
      val dotB = nStart.dot(bEnd)
      closureCorrection = kotlin.math.atan2(dotB, dotN)
    }

    val vertsPerRing = crossSlices + 1
    val vertexCount = (segments + 1) * vertsPerRing
    val floats = FloatArray(vertexCount * 15)
    var ptr = 0

    for (i in 0..segments) {
      val u = i.toFloat() / segments.toFloat()
      val center = spine[i]
      val tangent = tangents[i]
      val baseN = normals[i]
      val baseB = binormals[i]

      val metrics = evaluateRibbonProfileAt(u, halfWidth, closed, taperEnds, profile)
      val twistAngle = metrics.twistRad + closureCorrection * u
      val ct = cos(twistAngle)
      val st = sin(twistAngle)
      val crossDir = (baseB * ct + baseN * st).normalized()
      val surfNormal = (baseN * ct - baseB * st).normalized()
      val avgHalfW = 0.5f * (metrics.leftHalfWidth + metrics.rightHalfWidth)

      for (j in 0..crossSlices) {
        val vNorm = (j.toFloat() / crossSlices.toFloat()) * 2f - 1f // -1..1
        // Smooth C1 blend between asymmetric left & right half-widths across ribbon center (zero spine kink!)
        val blendT = (vNorm * 0.5f + 0.5f).coerceIn(0f, 1f)
        val smoothBlend = blendT * blendT * (3f - 2f * blendT)
        val signedSpan = -metrics.leftHalfWidth * (1f - smoothBlend) + metrics.rightHalfWidth * smoothBlend
        val camber = (1f - vNorm * vNorm) * avgHalfW * profile.camberFactor
        val offset = crossDir * signedSpan + surfNormal * camber
        val curvedNormal = (surfNormal + crossDir * (vNorm * 0.36f)).normalized()

        floats[ptr++] = center.x
        floats[ptr++] = center.y
        floats[ptr++] = center.z
        floats[ptr++] = offset.x
        floats[ptr++] = offset.y
        floats[ptr++] = offset.z
        floats[ptr++] = curvedNormal.x
        floats[ptr++] = curvedNormal.y
        floats[ptr++] = curvedNormal.z
        floats[ptr++] = u
        floats[ptr++] = vNorm
        floats[ptr++] = tangent.x
        floats[ptr++] = tangent.y
        floats[ptr++] = tangent.z
        floats[ptr++] = metrics.flexWeight
      }
    }

    val indices = IntArray(segments * crossSlices * 6)
    var idxPtr = 0
    for (i in 0 until segments) {
      val row0 = i * vertsPerRing
      val row1 = (i + 1) * vertsPerRing
      for (j in 0 until crossSlices) {
        val a = row0 + j
        val b = row0 + j + 1
        val c = row1 + j
        val d = row1 + j + 1
        indices[idxPtr++] = a
        indices[idxPtr++] = c
        indices[idxPtr++] = b
        indices[idxPtr++] = b
        indices[idxPtr++] = c
        indices[idxPtr++] = d
      }
    }

    val vao = IntArray(1)
    val vbo = IntArray(1)
    val ebo = IntArray(1)
    GLES30.glGenVertexArrays(1, vao, 0)
    GLES30.glGenBuffers(1, vbo, 0)
    GLES30.glGenBuffers(1, ebo, 0)

    GLES30.glBindVertexArray(vao[0])
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
    GLES30.glBufferData(
      GLES30.GL_ARRAY_BUFFER,
      floats.size * 4,
      GlBufferUtils.toFloatBuffer(floats),
      GLES30.GL_STATIC_DRAW,
    )
    GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebo[0])
    GLES30.glBufferData(
      GLES30.GL_ELEMENT_ARRAY_BUFFER,
      indices.size * 4,
      GlBufferUtils.toIntBuffer(indices),
      GLES30.GL_STATIC_DRAW,
    )
    val stride = 15 * 4
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
    GLES30.glEnableVertexAttribArray(1)
    GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
    GLES30.glEnableVertexAttribArray(2)
    GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24)
    GLES30.glEnableVertexAttribArray(3)
    GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, stride, 36)
    GLES30.glEnableVertexAttribArray(4)
    GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, stride, 44)
    GLES30.glBindVertexArray(0)

    return GlMesh(vao[0], vbo[0], ebo[0], indices.size)
  }

  /**
   * Builds a unit quad (-0.5..0.5) for camera-facing HDR energy flares and 3D sphere-impostor glass bubbles.
   */
  fun createCenteredBillboardQuad(): GlMesh {
    val vertices = floatArrayOf(
      -0.5f, -0.5f, 0f, 0f,
      0.5f, -0.5f, 1f, 0f,
      -0.5f, 0.5f, 0f, 1f,
      0.5f, 0.5f, 1f, 1f,
    )
    val vao = IntArray(1)
    val vbo = IntArray(1)
    GLES30.glGenVertexArrays(1, vao, 0)
    GLES30.glGenBuffers(1, vbo, 0)
    GLES30.glBindVertexArray(vao[0])
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
    GLES30.glBufferData(
      GLES30.GL_ARRAY_BUFFER,
      vertices.size * 4,
      GlBufferUtils.toFloatBuffer(vertices),
      GLES30.GL_STATIC_DRAW,
    )
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
    GLES30.glEnableVertexAttribArray(1)
    GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
    GLES30.glBindVertexArray(0)
    return GlMesh(vao[0], vbo[0], 0, 4, GLES30.GL_TRIANGLE_STRIP)
  }
}
