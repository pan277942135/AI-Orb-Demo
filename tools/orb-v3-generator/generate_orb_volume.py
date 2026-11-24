#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Canonical 3D Fluid Volume Synthesizer (Lossless RGBA WebP)

Single-Source-of-Truth Architecture:
  - Imports `render_canonical_slice_rgba` directly from `canonical_fluid_volume.py`,
    guaranteeing 1:1 bit-for-bit algorithmic parity with Stage C optimization
    (`fitting/volume_structure_fitter.py`) and independent verification (`fitting/validate_fit.py`).
  - Reads `MASTER_FIDELITY_V32.json` (`referenceMaster.path` & fitted parameters) and
    `tools/orb-v3-generator/reference/REFERENCE_VISUAL_REGIONS.json`.
  - Contains ZERO baked meniscus density clipping (full spherical containment only,
    leaving GPU `cavityPhi()` + `intersectCrystalLens()` as the sole authoritative liquid boundary).

Outputs 32 Lossless RGBA WebP Z-slices into:
  app/src/main/assets/orb_v3/volume/fluid_color_z00.webp .. fluid_color_z31.webp
"""

from __future__ import annotations

import hashlib
import json
import multiprocessing as mp
import os
import sys
import time
from pathlib import Path

from PIL import Image

GEN_DIR = Path(__file__).resolve().parent
if str(GEN_DIR) not in sys.path:
    sys.path.insert(0, str(GEN_DIR))

from canonical_fluid_volume import (
    DEPTH,
    HEIGHT,
    SEED,
    WIDTH,
    evaluate_canonical_voxel,
    extract_volume_params,
    render_canonical_slice_rgba,
)

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "orb_v3" / "volume"
REGIONS_PATH = GEN_DIR / "reference" / "REFERENCE_VISUAL_REGIONS.json"
V32_CONFIG_PATH = ROOT / "app" / "src" / "main" / "assets" / "orb_v3" / "manifest" / "MASTER_FIDELITY_V32.json"


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def load_generation_inputs() -> tuple[dict, dict[str, list[float]], dict[str, list[float]], dict[str, float], int]:
    if not V32_CONFIG_PATH.exists():
        raise FileNotFoundError(
            f"Missing MASTER_FIDELITY_V32.json: {V32_CONFIG_PATH}. Run optimize_reference_scene.py first."
        )
    if not REGIONS_PATH.exists():
        raise FileNotFoundError(
            f"Missing REFERENCE_VISUAL_REGIONS.json: {REGIONS_PATH}. Run reference_measurements.py first."
        )

    cfg = json.loads(V32_CONFIG_PATH.read_text(encoding="utf-8"))
    master_rel = cfg.get("referenceMaster", {}).get("path", "")
    expected_sha = cfg.get("referenceMaster", {}).get("sha256", "")
    master_path = ROOT / master_rel
    if not master_rel or not master_path.exists():
        raise FileNotFoundError(f"Frozen master path from MASTER_FIDELITY_V32.json missing: {master_path}")
    actual_sha = _sha256_file(master_path)
    if actual_sha != expected_sha:
        raise ValueError(f"Frozen master SHA-256 mismatch: got {actual_sha}, expected {expected_sha}")

    regions = json.loads(REGIONS_PATH.read_text(encoding="utf-8"))
    if regions.get("schemaVersion") != "AI_ORB_V3_REFERENCE_VISUAL_REGIONS_1_0":
        raise ValueError(f"Unexpected REFERENCE_VISUAL_REGIONS schema: {regions.get('schemaVersion')}")
    if regions.get("referenceMaster", {}).get("sha256") != expected_sha:
        raise ValueError("REFERENCE_VISUAL_REGIONS.json master SHA-256 does not match MASTER_FIDELITY_V32.json")

    ref_grids = {
        k: regions["interiorRegions"][k]["spatialGrid32x32"]
        for k in ("E05_emerald", "E06_sapphire", "E07_aqua", "E08_caustics")
    }
    master_rgb = {
        k: regions["interiorRegions"][k]["meanSrgb"]
        for k in ("E05_emerald", "E06_sapphire", "E07_aqua")
    }
    vp = extract_volume_params(cfg)
    grid_n = int(regions["gridSpec"]["size"])
    return cfg, ref_grids, master_rgb, vp, grid_n


_CFG, REF_GRIDS, MASTER_RGB, V32_PARAMS, GRID_N = load_generation_inputs()


def render_slice(z_idx: int) -> tuple[int, str, int]:
    buf = render_canonical_slice_rgba(
        z_idx=z_idx,
        ref_grids=REF_GRIDS,
        master_rgb=MASTER_RGB,
        vp=V32_PARAMS,
        width=WIDTH,
        height=HEIGHT,
        depth=DEPTH,
        grid_n=GRID_N,
    )

    out_path = OUT_DIR / f"fluid_color_z{z_idx:02d}.webp"
    # Pillow exposes libwebp's `exact` switch; ffmpeg's libwebp wrapper does not.
    # Without exact=True, VP8L rewrites RGB under transparent texels, breaking full-RGBA parity.
    image = Image.frombytes("RGBA", (WIDTH, HEIGHT), bytes(buf))
    image.save(out_path, format="WEBP", lossless=True, quality=100, method=4, exact=True)

    return z_idx, out_path.name, out_path.stat().st_size


def main() -> int:
    t0 = time.time()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    workers = min(max(1, os.cpu_count() or 4), 8)
    print(
        f"[generate_orb_volume] Synthesizing canonical V3.2 {WIDTH}x{HEIGHT}x{DEPTH} 3D FluidVolume "
        f"(model=canonical_fluid_volume, v32={V32_CONFIG_PATH.name}, seed={SEED}, workers={workers})..."
    )

    with mp.Pool(processes=workers) as pool:
        results = pool.map(render_slice, range(DEPTH))

    total_bytes = sum(sz for _, _, sz in results)
    elapsed = time.time() - t0
    print(
        f"[generate_orb_volume] DONE: {len(results)} Lossless RGBA WebP slices -> {OUT_DIR} "
        f"({total_bytes / 1024.0:.1f} KiB on disk, {elapsed:.2f}s)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
