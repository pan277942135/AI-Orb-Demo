#!/usr/bin/env python3
"""
AI-Orb-Demo V3.1 — Auto-Generator for ReferenceMeniscusProfile.kt (P0-1)

Reads the 32 pixel-extracted master meniscus samples from:
  tools/orb-v3-generator/reference/REFERENCE_VISUAL_REGIONS.json
validates integrity against the sole frozen master PNG:
  public/assets/aistudio/____________910d1531.png
and generates:
  app/src/main/java/com/example/orb/v3/ReferenceMeniscusProfile.kt
"""

from __future__ import annotations

import hashlib
import json
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MASTER_PATH = ROOT / "public" / "assets" / "aistudio" / "____________910d1531.png"
REGIONS_PATH = Path(__file__).resolve().parent / "reference" / "REFERENCE_VISUAL_REGIONS.json"
OUT_KT = ROOT / "app" / "src" / "main" / "java" / "com" / "example" / "orb" / "v3" / "ReferenceMeniscusProfile.kt"

EXPECTED_MASTER_SHA256 = "910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122"
EXPECTED_SAMPLE_COUNT = 32


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    if not MASTER_PATH.exists():
        raise FileNotFoundError(f"Frozen master missing: {MASTER_PATH}")
    disk_sha = sha256_file(MASTER_PATH)
    if disk_sha != EXPECTED_MASTER_SHA256:
        raise ValueError(f"Frozen master SHA-256 mismatch on disk: {disk_sha} != {EXPECTED_MASTER_SHA256}")

    if not REGIONS_PATH.exists():
        raise FileNotFoundError(f"Missing reference regions JSON: {REGIONS_PATH}")
    data = json.loads(REGIONS_PATH.read_text(encoding="utf-8"))

    json_sha = data.get("referenceMaster", {}).get("sha256", "")
    if json_sha != EXPECTED_MASTER_SHA256:
        raise ValueError(f"REFERENCE_VISUAL_REGIONS.json master SHA-256 mismatch: {json_sha}")

    samples = data.get("meniscus", {}).get("profileSamples", [])
    if len(samples) != EXPECTED_SAMPLE_COUNT:
        raise ValueError(f"Expected {EXPECTED_SAMPLE_COUNT} meniscus samples, got {len(samples)}")

    nxs: list[float] = []
    nys: list[float] = []
    prev_nx = -1e9
    for idx, entry in enumerate(samples):
        nx = float(entry["nx"])
        ny = float(entry["ny"])
        if not (math.isfinite(nx) and math.isfinite(ny)):
            raise ValueError(f"Non-finite meniscus sample at index {idx}: ({nx}, {ny})")
        if nx <= prev_nx:
            raise ValueError(f"Non-monotonic nx at index {idx}: {nx} <= {prev_nx}")
        if not (-0.5 <= ny <= 0.9):
            raise ValueError(f"Out-of-range ny at index {idx}: {ny}")
        prev_nx = nx
        nxs.append(nx)
        nys.append(ny)

    def fmt_array(vals: list[float]) -> str:
        rows = []
        for i in range(0, len(vals), 8):
            chunk = ", ".join(f"{v:.4f}f" for v in vals[i:i + 8])
            rows.append(f"    {chunk},")
        return "\n".join(rows)

    kt_source = f"""package com.example.orb.v3

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Auto-generated from `tools/orb-v3-generator/reference/REFERENCE_VISUAL_REGIONS.json`
 * by `tools/orb-v3-generator/generate_reference_meniscus_profile.py`.
 *
 * DO NOT HAND-EDIT: re-run the generator whenever `REFERENCE_VISUAL_REGIONS.json` changes.
 * Provides the exact 32-sample master crystal-cavity meniscus profile (`uReferenceMeniscusY[32]`)
 * bound into `CORE_GLASS_FRAG` for single-source 3D Cavity SDF evaluation (`cavityPhi`).
 */
object ReferenceMeniscusProfile {{
  const val SAMPLE_COUNT: Int = {EXPECTED_SAMPLE_COUNT}
  const val X_MIN: Float = -0.96875f
  const val X_MAX: Float = 0.96875f
  const val MASTER_SHA256: String = "{EXPECTED_MASTER_SHA256}"

  val SAMPLE_X: FloatArray = floatArrayOf(
{fmt_array(nxs)}
  )

  val HEIGHTS: FloatArray = floatArrayOf(
{fmt_array(nys)}
  )

  /**
   * Pure-Kotlin mirror of GLSL `referenceMeniscusHeight(float x)` using smooth clamped
   * piecewise linear interpolation (zero overshoot) across the 32 master profile samples.
   */
  fun sampleHeight(x: Float): Float {{
    val t = ((x - X_MIN) / (X_MAX - X_MIN)).coerceIn(0f, 1f) * (SAMPLE_COUNT - 1)
    val i0 = floor(t).toInt().coerceIn(0, SAMPLE_COUNT - 2)
    val i1 = i0 + 1
    val frac = (t - i0).coerceIn(0f, 1f)
    return HEIGHTS[i0] * (1f - frac) + HEIGHTS[i1] * frac
  }}

  /**
   * Pure-Kotlin mirror of GLSL `cavityPhi(vec3 p, float phase)` in Core Frame.
   * Positive values (`phi > 0`) lie in the upper transparent crystal cavity;
   * negative values (`phi < 0`) lie inside the submerged 3D liquid volume.
   */
  fun cavityPhi(x: Float, y: Float, z: Float, phase: Float): Float {{
    // 3-tap smooth filter over the 32 master samples eliminates jagged V-notch while honoring master profile
    val baseProfileY = 0.25f * sampleHeight(x - 0.08f) +
      0.50f * sampleHeight(x) +
      0.25f * sampleHeight(x + 0.08f)
    val r2 = x * x + z * z
    val domeDepthDip = 0.14f * z * z - 0.035f * z
    val sideWallClimb = 0.048f * r2 * r2
    val wave = 0.012f * sin(x * 2.4f + phase * 0.45f + z * 1.1f) +
      0.008f * cos(z * 2.8f - phase * 0.36f - x * 1.5f)
    val surfaceY = baseProfileY + domeDepthDip + sideWallClimb + wave
    return y - surfaceY
  }}
}}
"""

    OUT_KT.parent.mkdir(parents=True, exist_ok=True)
    OUT_KT.write_text(kt_source, encoding="utf-8")
    print(
        f"[generate_reference_meniscus_profile] Wrote {OUT_KT.relative_to(ROOT)} "
        f"({EXPECTED_SAMPLE_COUNT} samples, y range [{min(nys):.4f}, {max(nys):.4f}], master SHA verified)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
