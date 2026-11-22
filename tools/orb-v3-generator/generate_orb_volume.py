#!/usr/bin/env python3
"""
AI-Orb-Demo V3 — Reference-Driven Continuous 3D Fluid Volume Synthesizer (Lossless RGBA WebP)

G1 / P0-1 / P0-2 Architecture:
  1. Explicitly loads pixel-extracted master geometry & spatial probability maps from:
       tools/orb-v3-generator/reference/REFERENCE_VISUAL_REGIONS.json
     (derived directly from sole frozen master public/assets/aistudio/____________910d1531.png).
  2. P0-1 Single-Source Meniscus Compliance:
     Contains ZERO baked meniscus density clipping (`meniscus_y` / `below_meniscus` removed!).
     The 256x256x32 3D volume represents continuous full-sphere fluid structure (`r <= 0.985`,
     strict zero alpha at corners) so GPU `meniscusHeight()` in `CORE_GLASS_FRAG` is the
     SOLE authoritative liquid boundary during 3D core rotation.
  3. P0-2 High-Contrast 3D Thin-Sheet Liquid Sculpture:
     - Projects extracted `E05_emerald`, `E06_sapphire`, `E07_aqua` spatial fields into sculpted
       3D thin folded liquid curtains (left 7-10 & right 1:30-4:30 emerald silk sheets framing a
       deep dark abyssal sapphire central valley + focused lower-center/right 5-7 aqua belly).
     - Seamlessly continues the folded 3D ribbons into the rear hemisphere (`z <= 0`) so full
       360-degree core self-rotation preserves dramatic 3D depth contrast from every angle.

Outputs 32 Lossless RGBA WebP Z-slices into:
  app/src/main/assets/orb_v3/volume/fluid_color_z00.webp .. fluid_color_z31.webp
"""

from __future__ import annotations

import json
import math
import multiprocessing as mp
import os
import random
import subprocess
import sys
import time
from pathlib import Path

SEED = 20261120
WIDTH = 256
HEIGHT = 256
DEPTH = 32

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "orb_v3" / "volume"
REGIONS_PATH = Path(__file__).resolve().parent / "reference" / "REFERENCE_VISUAL_REGIONS.json"


def load_reference_regions() -> dict:
    if not REGIONS_PATH.exists():
        raise FileNotFoundError(
            f"Missing G1 reference regions file: {REGIONS_PATH}. "
            "Run tools/orb-v3-generator/extract_reference_regions.py first."
        )
    data = json.loads(REGIONS_PATH.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != "AI_ORB_V3_REFERENCE_VISUAL_REGIONS_1_0":
        raise ValueError(f"Unexpected REFERENCE_VISUAL_REGIONS schema: {data.get('schemaVersion')}")
    return data


REF_DATA = load_reference_regions()
GRID_N = int(REF_DATA["gridSpec"]["size"])
REF_E05_GRID: list[float] = REF_DATA["interiorRegions"]["E05_emerald"]["spatialGrid32x32"]
REF_E06_GRID: list[float] = REF_DATA["interiorRegions"]["E06_sapphire"]["spatialGrid32x32"]
REF_E07_GRID: list[float] = REF_DATA["interiorRegions"]["E07_aqua"]["spatialGrid32x32"]
REF_E08_GRID: list[float] = REF_DATA["interiorRegions"]["E08_caustics"]["spatialGrid32x32"]

# Master measured mean sRGB anchors (normalized 0..1)
MASTER_EMERALD_RGB = REF_DATA["interiorRegions"]["E05_emerald"]["meanSrgb"]
MASTER_SAPPHIRE_RGB = REF_DATA["interiorRegions"]["E06_sapphire"]["meanSrgb"]
MASTER_AQUA_RGB = REF_DATA["interiorRegions"]["E07_aqua"]["meanSrgb"]


def build_lattice(n: int, seed: int) -> list[float]:
    rng = random.Random(seed)
    return [rng.uniform(-1.0, 1.0) for _ in range(n * n * n)]


GRID_A_N = 16
GRID_B_N = 32
GRID_A = build_lattice(GRID_A_N, SEED + 101)
GRID_B = build_lattice(GRID_B_N, SEED + 202)
GRID_C = build_lattice(GRID_A_N, SEED + 303)


def clamp01(v: float) -> float:
    if v <= 0.0:
        return 0.0
    if v >= 1.0:
        return 1.0
    return v


def smoothstep(e0: float, e1: float, x: float) -> float:
    t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3.0 - 2.0 * t)


def mix(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def sample_ref_grid(grid: list[float], nx: float, ny: float) -> float:
    """Bilinearly samples a 32x32 pixel-extracted master region grid (nx in [-1,1], ny in [+1,-1])."""
    fx = clamp01((nx + 1.0) * 0.5) * (GRID_N - 1)
    fy = clamp01((1.0 - ny) * 0.5) * (GRID_N - 1)
    x0 = int(fx)
    y0 = int(fy)
    x1 = min(GRID_N - 1, x0 + 1)
    y1 = min(GRID_N - 1, y0 + 1)
    tx = fx - x0
    ty = fy - y0
    c00 = grid[y0 * GRID_N + x0]
    c10 = grid[y0 * GRID_N + x1]
    c01 = grid[y1 * GRID_N + x0]
    c11 = grid[y1 * GRID_N + x1]
    top = c00 + (c10 - c00) * tx
    bot = c01 + (c11 - c01) * tx
    return top + (bot - top) * ty


def sample_grid(grid: list[float], n: int, x: float, y: float, z: float) -> float:
    fx = (x * n) % n
    fy = (y * n) % n
    fz = (z * n) % n

    ix0 = int(fx)
    iy0 = int(fy)
    iz0 = int(fz)
    tx = fx - ix0
    ty = fy - iy0
    tz = fz - iz0

    ux = tx * tx * tx * (tx * (tx * 6.0 - 15.0) + 10.0)
    uy = ty * ty * ty * (ty * (ty * 6.0 - 15.0) + 10.0)
    uz = tz * tz * tz * (tz * (tz * 6.0 - 15.0) + 10.0)

    ix1 = (ix0 + 1) % n
    iy1 = (iy0 + 1) % n
    iz1 = (iz0 + 1) % n

    n2 = n * n
    z0_off = iz0 * n2
    z1_off = iz1 * n2
    y0_off = iy0 * n
    y1_off = iy1 * n

    c000 = grid[z0_off + y0_off + ix0]
    c100 = grid[z0_off + y0_off + ix1]
    c010 = grid[z0_off + y1_off + ix0]
    c110 = grid[z0_off + y1_off + ix1]
    c001 = grid[z1_off + y0_off + ix0]
    c101 = grid[z1_off + y0_off + ix1]
    c011 = grid[z1_off + y1_off + ix0]
    c111 = grid[z1_off + y1_off + ix1]

    x00 = c000 + (c100 - c000) * ux
    x10 = c010 + (c110 - c010) * ux
    x01 = c001 + (c101 - c001) * ux
    x11 = c011 + (c111 - c011) * ux

    y0 = x00 + (x10 - x00) * uy
    y1 = x01 + (x11 - x01) * uy
    return y0 + (y1 - y0) * uz


def render_slice(z_idx: int) -> tuple[int, str, int]:
    z = ((z_idx + 0.5) / DEPTH) * 2.0 - 1.0
    buf = bytearray(WIDTH * HEIGHT * 4)

    sin = math.sin
    cos = math.cos
    sqrt = math.sqrt
    atan2 = math.atan2
    exp = math.exp

    z_n = (z + 1.0) * 0.5
    # Front-hemisphere projection weight: master 2D regions anchor front/mid depth cleanly,
    # smoothly blending into continuous 3D helical sculpture in the rear hemisphere for 360° spin!
    front_anchor = smoothstep(-0.45, 0.35, z)
    rear_anchor = 1.0 - front_anchor

    # Calibrated deep-sapphire, emerald-jade, and aqua-cyan base hues anchored to master measurements
    sapph_dark = (0.008, 0.068, 0.145)
    sapph_mid = (
        max(0.015, MASTER_SAPPHIRE_RGB[0] * 0.58),
        max(0.16, MASTER_SAPPHIRE_RGB[1] * 0.78),
        max(0.28, MASTER_SAPPHIRE_RGB[2] * 1.15),
    )
    em_deep = (
        max(0.030, MASTER_EMERALD_RGB[0] * 0.24),
        max(0.29, MASTER_EMERALD_RGB[1] * 0.72),
        max(0.10, MASTER_EMERALD_RGB[2] * 0.36),
    )
    em_vivid = (
        min(0.42, MASTER_EMERALD_RGB[0] * 0.92),
        min(0.76, MASTER_EMERALD_RGB[1] * 1.28),
        min(0.32, MASTER_EMERALD_RGB[2] * 0.72),
    )
    aqua_core = (
        min(0.38, MASTER_AQUA_RGB[0] * 0.85),
        min(0.92, MASTER_AQUA_RGB[1] * 1.18),
        min(0.98, MASTER_AQUA_RGB[2] * 1.26),
    )

    off = 0
    for row in range(HEIGHT):
        # Row 0 = top (+1.0), Row 255 = bottom (-1.0)
        y = 1.0 - ((row + 0.5) / HEIGHT) * 2.0
        y_n = (y + 1.0) * 0.5
        y2_z2 = y * y + z * z

        for col in range(WIDTH):
            x = ((col + 0.5) / WIDTH) * 2.0 - 1.0
            r2 = x * x + y2_z2

            # Clean spherical containment ONLY (ZERO baked meniscus clipping — P0-1!)
            if r2 >= 0.970225:
                buf[off] = 4
                buf[off + 1] = 20
                buf[off + 2] = 36
                buf[off + 3] = 0
                off += 4
                continue

            r = sqrt(r2)
            sphere_mask = 1.0 - smoothstep(0.86, 0.985, r)
            x_n = (x + 1.0) * 0.5

            # 1. Continuous 3D domain-warped organic flow fields (zero Z seams)
            n_coarse = sample_grid(GRID_A, GRID_A_N, x_n * 0.42 + 0.13, y_n * 0.42 + 0.27, z_n * 0.42 + 0.09)
            n_warp = sample_grid(GRID_C, GRID_A_N, x_n * 0.48 + 0.51, y_n * 0.48 + 0.19, z_n * 0.48 + 0.37)

            wx = x + 0.20 * n_coarse + 0.09 * sin(y * 3.2 + z * 2.4)
            wy = y + 0.18 * n_warp + 0.08 * cos(x * 2.8 - z * 2.5)
            wz = z + 0.20 * (n_coarse * 0.6 - n_warp * 0.7) + 0.09 * sin(x * 2.5 + y * 3.0)

            n_detail = sample_grid(
                GRID_B,
                GRID_B_N,
                (wx + 1.0) * 0.36 + 0.11,
                (wy + 1.0) * 0.36 + 0.23,
                (wz + 1.0) * 0.20 + 0.31,
            )
            n_silk = sample_grid(
                GRID_B,
                GRID_B_N,
                (wx + 1.0) * 0.58 + 0.43,
                (wy + 1.0) * 0.58 + 0.07,
                (wz + 1.0) * 0.18 + 0.61,
            )

            # 2. Sample real pixel-extracted master region maps (projected with mild 3D flow warp)
            sx_ref = clamp01((x + 0.08 * n_coarse + 0.05 * z + 1.0) * 0.5) * 2.0 - 1.0
            sy_ref = clamp01((y + 0.07 * n_warp - 0.04 * z + 1.0) * 0.5) * 2.0 - 1.0
            ref_e05 = sample_ref_grid(REF_E05_GRID, sx_ref, sy_ref)
            ref_e06 = sample_ref_grid(REF_E06_GRID, sx_ref, sy_ref)
            ref_e07 = sample_ref_grid(REF_E07_GRID, sx_ref, sy_ref)
            ref_e08 = sample_ref_grid(REF_E08_GRID, sx_ref, sy_ref)

            # 3. 3D Thin-Sheet Helical Fold SDF (creates crisp silk curtains & deep dark valleys in 3D!)
            az = atan2(wz, wx)
            r_xz = sqrt(wx * wx + wz * wz)
            axial_w = smoothstep(0.06, 0.36, r_xz)

            helix_a = axial_w * sin(az * 2.0 + wy * 2.25 + r_xz * 1.30 + n_coarse * 2.10) + (1.0 - axial_w) * n_coarse
            helix_b = axial_w * cos(az * 3.0 - wy * 1.95 + n_warp * 2.15 + n_detail * 0.95) + (1.0 - axial_w) * n_warp
            fold_field = 0.58 * helix_a + 0.42 * helix_b

            # Thin sculpted silk sheet ridge (high contrast narrow folds instead of blurry clouds!)
            sheet_ridge = exp(-((fold_field - 0.18) ** 2) * 5.2)
            sheet_lobe = smoothstep(-0.08, 0.68, fold_field + 0.28 * n_detail)

            # Sculpted emerald curtains: anchored to master E05 (left 7-10 & right 1:30-4:30 flanks)
            # in front hemisphere, seamlessly wrapped onto 3D helical curtains in rear hemisphere
            em_front = clamp01(ref_e05 * 1.28 * (0.58 + 0.42 * sheet_lobe + 0.24 * sheet_ridge))
            em_rear = clamp01((0.62 * sheet_lobe + 0.48 * sheet_ridge) * smoothstep(0.18, 0.88, r_xz + 0.25 * n_coarse))
            emerald_w = clamp01(mix(em_rear, em_front, front_anchor))
            emerald_crest = clamp01(emerald_w * (0.45 * sheet_ridge + 0.55 * smoothstep(0.12, 0.78, 0.5 + 0.5 * n_silk)))

            # Deep abyssal sapphire contrast valleys (master E06 central core + dark inter-fold pockets)
            sapph_front = clamp01(ref_e06 * 1.35 * (1.0 - 0.72 * emerald_w))
            sapph_rear = clamp01(smoothstep(-0.35, 0.62, -fold_field - 0.20 * n_warp) * (1.0 - 0.70 * emerald_w))
            sapphire_w = clamp01(mix(sapph_rear, sapph_front, front_anchor))
            deep_valley = clamp01(smoothstep(0.25, 0.85, sapphire_w) * (1.0 - 0.80 * emerald_w))

            # Focused luminous aqua-cyan lower belly (master E07 + E08 core, 5-7 o'clock!)
            aqua_front = clamp01((ref_e07 * 1.15 + ref_e08 * 0.95) * (1.0 - 0.55 * emerald_w))
            lower_3d = (1.0 - smoothstep(-0.85, 0.08, y)) * smoothstep(-0.35, 0.65, -y * 0.85 + 0.35 * helix_b)
            aqua_rear = clamp01(lower_3d * (0.52 + 0.48 * smoothstep(-0.25, 0.62, n_detail)) * (1.0 - 0.62 * emerald_w))
            aqua_w = clamp01(mix(aqua_rear, aqua_front, front_anchor))

            # 4. High-Contrast Jewel Color Synthesis (un-premultiplied sRGB)
            # Start from genuine deep dark abyssal sapphire so contrast pops like the master!
            cr = mix(sapph_dark[0], sapph_mid[0], 0.35 + 0.65 * (1.0 - deep_valley))
            cg = mix(sapph_dark[1], sapph_mid[1], 0.35 + 0.65 * (1.0 - deep_valley))
            cb = mix(sapph_dark[2], sapph_mid[2], 0.35 + 0.65 * (1.0 - deep_valley))

            # Subtle oceanic teal swirl ribbons inside mid-sapphire
            teal_vein = sheet_ridge * (1.0 - emerald_w * 0.7) * (1.0 - deep_valley * 0.6)
            cr = mix(cr, 0.045, teal_vein * 0.45)
            cg = mix(cg, 0.440, teal_vein * 0.45)
            cb = mix(cb, 0.580, teal_vein * 0.45)

            # Layer focused lower-belly luminous aqua-cyan (NEVER muddy uniform fog!)
            aq_bright = smoothstep(-0.10, 0.72, n_silk + 0.35 * ref_e08)
            ar = mix(aqua_core[0] * 0.55, min(0.68, aqua_core[0] * 1.65), aq_bright)
            ag = mix(aqua_core[1] * 0.78, min(0.97, aqua_core[1] * 1.12), aq_bright)
            ab = mix(aqua_core[2] * 0.84, 1.00, aq_bright)
            cr = mix(cr, ar, aqua_w * 0.92)
            cg = mix(cg, ag, aqua_w * 0.92)
            cb = mix(cb, ab, aqua_w * 0.92)

            # Layer sculpted lush emerald-jade silk curtains (deep moss -> vivid emerald -> sunlit chartreuse edge)
            em_t = smoothstep(-0.32, 0.58, n_detail)
            er = mix(em_deep[0], em_vivid[0], em_t)
            eg = mix(em_deep[1], em_vivid[1], em_t)
            eb = mix(em_deep[2], em_vivid[2], em_t)
            # Crisp sunlit chartreuse-emerald silk crest highlights
            er = mix(er, 0.54, emerald_crest * 0.58)
            eg = mix(eg, 0.84, emerald_crest * 0.58)
            eb = mix(eb, 0.34, emerald_crest * 0.58)

            cr = mix(cr, er, emerald_w * 0.94)
            cg = mix(cg, eg, emerald_w * 0.94)
            cb = mix(cb, eb, emerald_w * 0.94)

            # 5. Structured Physical Optical Density (Linear Alpha, ZERO baked meniscus cut!)
            # High-contrast density: crisp denser emerald sheets & deep dark sapphire pockets,
            # clearer optical channels between folds so light & caustics transmit cleanly!
            sheet_density = (
                0.18
                + 0.44 * emerald_w
                + 0.24 * sapphire_w
                + 0.26 * aqua_w
                + 0.16 * sheet_ridge
            )
            silk_mod = 0.76 + 0.32 * smoothstep(-0.45, 0.65, n_silk)
            # Gently denser inside inner fluid body, crisp fade toward inner glass wall
            radial_core = 0.78 + 0.24 * (1.0 - r)
            density = clamp01(sheet_density * silk_mod * radial_core * sphere_mask)

            buf[off] = int(clamp01(cr) * 255.0 + 0.5)
            buf[off + 1] = int(clamp01(cg) * 255.0 + 0.5)
            buf[off + 2] = int(clamp01(cb) * 255.0 + 0.5)
            buf[off + 3] = int(density * 255.0 + 0.5)
            off += 4

    out_path = OUT_DIR / f"fluid_color_z{z_idx:02d}.webp"
    cmd = [
        "ffmpeg",
        "-y",
        "-loglevel",
        "error",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgba",
        "-s",
        f"{WIDTH}x{HEIGHT}",
        "-i",
        "-",
        "-c:v",
        "libwebp",
        "-lossless",
        "1",
        "-compression_level",
        "4",
        str(out_path),
    ]
    proc = subprocess.run(cmd, input=bytes(buf), capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg failed for {out_path.name}: {proc.stderr.decode()}")

    return z_idx, out_path.name, out_path.stat().st_size


def main() -> int:
    t0 = time.time()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    workers = min(max(1, os.cpu_count() or 4), 8)
    print(
        f"[generate_orb_volume] Synthesizing reference-driven {WIDTH}x{HEIGHT}x{DEPTH} 3D FluidVolume "
        f"(regions={REGIONS_PATH.name}, single-source GPU meniscus, seed={SEED}, workers={workers})..."
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
