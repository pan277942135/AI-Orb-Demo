package com.example

import com.example.orb.task.PerimeterArcLengthTable
import com.example.orb.task.PerimeterPathGeometry
import com.example.orb.task.PerimeterRibbonLayer
import com.example.orb.task.PerimeterRibbonMeshBuilder
import com.example.orb.task.PerimeterSide
import com.example.orb.task.PhotoFrameGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * G2 Unified Rounded-Rectangle Perimeter Geometry Verification Suite:
 * - Both LEFT & RIGHT paths begin identically at BOTTOM_CENTER and converge at TOP_CENTER
 * - Exact half-perimeter arc length `w + h - (4 - PI) * r`
 * - C1 tangent continuity across all 4 rounded quarter-arc transitions (zero corner kinks!)
 * - Strictly unit outward normals pointing away from photo interior everywhere
 * - PerimeterRibbonMeshBuilder vertices stay strictly outside photo interior with zero NaN
 */
class PerimeterPathGeometryTest {

  private val samplePhoto = PhotoFrameGeometry(
    leftPx = 132f,
    topPx = 200f,
    rightPx = 948f,
    bottomPx = 1200f,
    cornerRadiusPx = 64f,
  )

  @Test
  fun perimeterStartsAtBottomCenterConvergesAtTopCenterAndMatchesExactArcLength() {
    val geo = PerimeterPathGeometry(samplePhoto)
    val expectedHalfLen = samplePhoto.widthPx + samplePhoto.heightPx - (4f - PI.toFloat()) * samplePhoto.clampedCornerRadiusPx
    assertEquals("Exact analytical half-perimeter length", expectedHalfLen, geo.totalHalfLengthPx, 0.05f)

    for (side in PerimeterSide.entries) {
      val s0 = geo.sampleByFraction(side, 0f)
      val s1 = geo.sampleByFraction(side, 1f)

      assertEquals("u=0 must start at Photo Bottom Center X", samplePhoto.bottomCenterPx.x, s0.positionPx.x, 1e-3f)
      assertEquals("u=0 must start at Photo Bottom Center Y", samplePhoto.bottomCenterPx.y, s0.positionPx.y, 1e-3f)
      assertEquals("u=1 must arrive at Photo Top Center X", samplePhoto.topCenterPx.x, s1.positionPx.x, 1e-3f)
      assertEquals("u=1 must arrive at Photo Top Center Y", samplePhoto.topCenterPx.y, s1.positionPx.y, 1e-3f)
      assertEquals("Bottom center outward normal must point +Y", 0f, s0.outwardNormal.x, 1e-4f)
      assertEquals("Bottom center outward normal must point +Y", 1f, s0.outwardNormal.y, 1e-4f)
      assertEquals("Top center outward normal must point -Y", 0f, s1.outwardNormal.x, 1e-4f)
      assertEquals("Top center outward normal must point -Y", -1f, s1.outwardNormal.y, 1e-4f)
    }

    // LEFT and RIGHT must be exact horizontal mirrors around photo.centerX
    for (i in 0..40) {
      val u = i / 40f
      val l = geo.sampleByFraction(PerimeterSide.LEFT, u)
      val r = geo.sampleByFraction(PerimeterSide.RIGHT, u)
      assertEquals("Symmetric Y at u=$u", l.positionPx.y, r.positionPx.y, 1e-2f)
      assertEquals("Mirrored X around centerX at u=$u", 2f * samplePhoto.centerX, l.positionPx.x + r.positionPx.x, 1e-2f)
    }
  }

  @Test
  fun roundedCornersMaintainC1TangentContinuityAndStrictlyOutwardUnitNormals() {
    val geo = PerimeterPathGeometry(samplePhoto)
    val transitions = listOf(
      geo.bottomCornerStartFrac,
      geo.bottomCornerEndFrac,
      geo.topCornerStartFrac,
      geo.topCornerEndFrac,
    )
    val eps = 1e-4f

    for (side in PerimeterSide.entries) {
      // C1 continuity check right before and after every quarter-arc boundary
      for (uBoundary in transitions) {
        val before = geo.sampleByFraction(side, (uBoundary - eps).coerceAtLeast(0f))
        val after = geo.sampleByFraction(side, (uBoundary + eps).coerceAtMost(1f))
        val posGap = (after.positionPx - before.positionPx).length()
        assertTrue("Position must be continuous at $uBoundary ($posGap)", posGap < 2.0f)

        val tangentDot = before.tangent.x * after.tangent.x + before.tangent.y * after.tangent.y
        assertTrue("Tangent must be C1 continuous across corner $uBoundary (dot=$tangentDot)", tangentDot > 0.998f)
      }

      // Dense sweep verifying unit tangent, unit outward normal, perpendicularity, and signed distance
      val samples = 240
      for (i in 0..samples) {
        val u = i.toFloat() / samples
        val s = geo.sampleByFraction(side, u)
        assertTrue("Finite X/Y", s.positionPx.x.isFinite() && s.positionPx.y.isFinite())
        assertEquals("Unit tangent at u=$u", 1f, s.tangent.length(), 1e-3f)
        assertEquals("Unit outward normal at u=$u", 1f, s.outwardNormal.length(), 1e-3f)
        val ortho = abs(s.tangent.x * s.outwardNormal.x + s.tangent.y * s.outwardNormal.y)
        assertTrue("Normal must be orthogonal to tangent at u=$u ($ortho)", ortho < 1e-3f)

        val sdBoundary = samplePhoto.signedDistancePx(s.positionPx)
        assertTrue("Sample must lie on photo boundary (sd=$sdBoundary)", abs(sdBoundary) < 0.75f)
        val sdOut = samplePhoto.signedDistancePx(s.positionPx + s.outwardNormal * 3f)
        val sdIn = samplePhoto.signedDistancePx(s.positionPx - s.outwardNormal * 3f)
        assertTrue("Outward step must strictly leave photo interior (sdOut=$sdOut)", sdOut > 2.0f)
        assertTrue("Inward step must enter photo interior (sdIn=$sdIn)", sdIn < -2.0f)
      }
    }
  }

  @Test
  fun arcLengthLookupTableAndRibbonStripVerticesStayStrictlyOutsidePhotoInterior() {
    val geo = PerimeterPathGeometry(samplePhoto)
    val table = PerimeterArcLengthTable(geo, sampleCountPerSide = 220)
    val sw = 1080
    val sh = 2340

    // Lookup table matches analytical geometry within < 0.5px everywhere
    for (side in PerimeterSide.entries) {
      for (i in 0..100) {
        val u = i / 100f
        val exact = geo.sampleByFraction(side, u)
        val lut = table.sample(side, u)
        val errPx = (exact.positionPx - lut.positionPx).length()
        assertTrue("LUT position error must be < 0.6px at u=$u (err=$errPx)", errPx < 0.6f)
      }
    }

    // Verify all 3 ribbon layers (L1, L2, L3) produce valid finite triangle strips strictly outside photo!
    for (side in PerimeterSide.entries) {
      for (layer in PerimeterRibbonLayer.entries) {
        val verts = PerimeterRibbonMeshBuilder.buildLayerStripNdc(table, side, layer, sw, sh)
        assertTrue("Strip must emit >= 400 vertices", verts.size / PerimeterRibbonMeshBuilder.FLOATS_PER_VERTEX >= 400)
        var i = 0
        while (i < verts.size) {
          val ndcX = verts[i]
          val ndcY = verts[i + 1]
          assertTrue("Finite NDC vertex", ndcX.isFinite() && ndcY.isFinite())
          val px = (ndcX + 1f) * 0.5f * sw
          val py = (1f - ndcY) * 0.5f * sh
          val sd = samplePhoto.signedDistancePx(com.example.orb.task.Vec2(px, py))
          assertTrue(
            "Perimeter vertex ($layer/$side) must NEVER cross inside photo interior (sd=$sd)",
            sd >= -0.6f,
          )
          i += PerimeterRibbonMeshBuilder.FLOATS_PER_VERTEX
        }
      }
    }
  }
}
