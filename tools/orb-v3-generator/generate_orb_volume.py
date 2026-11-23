#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Reference-Driven Continuous 3D Fluid Volume Synthesizer (Lossless RGBA WebP)

G2 / G3 Single-Source-of-Truth Architecture:
  1. Explicitly loads BOTH:
       - tools/orb-v3-generator/reference/REFERENCE_VISUAL_REGIONS.json
         (pixel-extracted master spatial maps from sole frozen master public/assets/aistudio/____________910d1531.png)
       - app/src/main/assets/orb_v3/manifest/MASTER_FIDELITY_V32.json
         (Stage C fitted 3D fluid fold & optical parameters shared 1:1 with Android runtime).
  2. P0-1 Single-Source Meniscus Compliance:
     Contains ZERO baked meniscus density clipping. The 256x256x32 3D volume represents
     continuous full-sphere fluid structure (r <= 0.985, strict zero alpha at corners) so
     GPU cavityPhi() + intersectCrystalLens() in CORE_GLASS_FRAG is the sole authoritative
     liquid/crystal boundary during 3D core rotation.
  3. P0-2 / V3.2 High-Contrast 3D Thin-Sheet Liquid Sculpture:
     - Compensates for 1.45 IOR spherical glass lens refraction so E05_emerald, E06_sapphire,
       and E07_aqua project cleanly onto their frozen master locations.
     - Seamlessly continues thin sculpted 3D folds into the rear hemisphere for smooth
       360-degree core self-rotation around frozen axis (0.14, 0.98, -0.14).

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
V32_CONFIG_PATH = ROOT / "app" / "src" / "main" / "assets" / "orb_v3" / "manifest" / "MASTER_FIDELITY_V32.json"


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


def load_v32_params() -> dict[str, float]:
    defaults = {
        "foldCount": 3.0,
        "foldIso": 0.18,
        "foldThickness": 0.13,
        "baseDensity": 0.032,
        "emeraldFoldGain": 0.72,
        "emeraldAnchorWeight": 1.65,
        "emeraldGreenBoost": 1.08,
        "emeraldThreshold": 0.16,
        "sapphireAbsorptionGain": 0.24,
        "sapphireAnchorWeight": 1.45,
        "sapphireDepthDarken": 0.68,
        "aquaDensityGain": 0.34,
        "aquaAnchorWeight": 1.38,
        "aquaBrightnessGain": 0.96,
        "aquaUpperGateY": 0.0,
        "causticDecoupleWeight": 0.16,
    }
    if not V32_CONFIG_PATH.exists():
        return defaults
    cfg = json.loads(V32_CONFIG_PATH.read_text(encoding="utf-8"))

    def read_sec(sec: str, key: str) -> float:
        entry = cfg.get(sec, {}).get(key)
        if isinstance(entry, dict):
            return float(entry.get("fittedValue", entry.get("default", defaults[key])))
        if entry is not None:
            return float(entry)
        return defaults[key]

    return {
        "foldCount": read_sec("fluidVolume", "foldCount"),
        "foldIso": read_sec("fluidVolume", "foldIso"),
        "foldThickness": read_sec("fluidVolume", "foldThickness"),
        "baseDensity": read_sec("fluidVolume", "baseDensity"),
        "emeraldFoldGain": read_sec("emeraldFolds", "emeraldFoldGain"),
        "emeraldAnchorWeight": read_sec("emeraldFolds", "emeraldAnchorWeight"),
        "emeraldGreenBoost": read_sec("emeraldFolds", "emeraldGreenBoost"),
        "emeraldThreshold": read_sec("emeraldFolds", "emeraldThreshold"),
        "sapphireAbsorptionGain": read_sec("sapphireValleys", "sapphireAbsorptionGain"),
        "sapphireAnchorWeight": read_sec("sapphireValleys", "sapphireAnchorWeight"),
        "sapphireDepthDarken": read_sec("sapphireValleys", "sapphireDepthDarken"),
        "aquaDensityGain": read_sec("aquaRegion", "aquaDensityGain"),
        "aquaAnchorWeight": read_sec("aquaRegion", "aquaAnchorWeight"),
        "aquaBrightnessGain": read_sec("aquaRegion", "aquaBrightnessGain"),
        "aquaUpperGateY": read_sec("aquaRegion", "aquaUpperGateY"),
        "causticDecoupleWeight": read_sec("caustics", "causticDecoupleWeight"),
    }


REF_DATA = load_reference_regions()
V32_PARAMS = load_v32_params()
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
    t = clamp01((x - e0) / max(1e-6, e1 - e0))
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

    vp = V32_PARAMS
    fc = int(round(vp["foldCount"]))
    fold_iso = vp["foldIso"]
    fold_sigma = max(0.09, vp["foldThickness"])
    em_thresh = vp["emeraldThreshold"]
    em_anchor = vp["emeraldAnchorWeight"]
    em_boost = vp["emeraldGreenBoost"]
    sapph_anchor = vp["sapphireAnchorWeight"]
    dark_k = vp["sapphireDepthDarken"]
    aq_anchor = vp["aquaAnchorWeight"]
    aq_b = vp["aquaBrightnessGain"]
    aq_gate_y = vp["aquaUpperGateY"]
    decouple = vp["causticDecoupleWeight"]
    base_density = vp["baseDensity"]
    emerald_fold_gain = vp["emeraldFoldGain"]
    sapphire_absorption_gain = vp["sapphireAbsorptionGain"]
    aqua_density_gain = vp["aquaDensityGain"]

    z_n = (z + 1.0) * 0.5
    front_anchor = smoothstep(-0.22, 0.34, z)
    lens_scale = 0.66 + 0.34 * max(0.0, z)

    sapph_dark = (0.008 * dark_k, 0.058 * dark_k, 0.145 * dark_k)
    sapph_mid = (
        max(0.020, MASTER_SAPPHIRE_RGB[0] * 0.85),
        max(0.18, MASTER_SAPPHIRE_RGB[1] * 1.15),
        max(0.32, MASTER_SAPPHIRE_RGB[2] * 1.30),
    )
    ar_base = min(0.58, MASTER_AQUA_RGB[0] * 0.92 * aq_b)
    ag_base = min(0.99, MASTER_AQUA_RGB[1] * 1.24 * aq_b)
    ab_base = min(1.00, MASTER_AQUA_RGB[2] * 1.24 * aq_b)

    off = 0
    for row in range(HEIGHT):
        # Row 0 = top (+1.0), Row 255 = bottom (-1.0)
        y = 1.0 - ((row + 0.5) / HEIGHT) * 2.0
        y_n = (y + 1.0) * 0.5
        y2_z2 = y * y + z * z

        for col in range(WIDTH):
            x = ((col + 0.5) / WIDTH) * 2.0 - 1.0
            r2 = x * x + y2_z2

            # Clean spherical containment ONLY (ZERO baked meniscus clipping — single-source GPU meniscus!)
            if r2 >= 0.970225:
                buf[off] = 4
                buf[off + 1] = 20
                buf[off + 2] = 36
                buf[off + 3] = 0
                off += 4
                continue

            r = sqrt(r2)
            sphere_mask = 1.0 - smoothstep(0.80, 0.985, r)
            x_n = (x + 1.0) * 0.5

            # 1. Continuous 3D domain-warped organic flow fields (gentle Z gradient for 32-slice continuity!)
            n_coarse = sample_grid(GRID_A, GRID_A_N, x_n * 0.38 + 0.13, y_n * 0.38 + 0.27, z_n * 0.24 + 0.09)
            n_warp = sample_grid(GRID_C, GRID_A_N, x_n * 0.42 + 0.51, y_n * 0.42 + 0.19, z_n * 0.24 + 0.37)

            wx = x + 0.09 * n_coarse + 0.07 * sin(y * 3.0 + z * 1.2)
            wy = y + 0.08 * n_warp + 0.06 * cos(x * 2.6 - z * 1.2)
            wz = z + 0.08 * (n_coarse * 0.5 - n_warp * 0.6) + 0.06 * sin(x * 2.2 + y * 2.6)

            n_detail = sample_grid(
                GRID_B,
                GRID_B_N,
                (wx + 1.0) * 0.34 + 0.11,
                (wy + 1.0) * 0.34 + 0.23,
                (wz + 1.0) * 0.14 + 0.31,
            )
            n_silk = sample_grid(
                GRID_B,
                GRID_B_N,
                (wx + 1.0) * 0.52 + 0.43,
                (wy + 1.0) * 0.52 + 0.07,
                (wz + 1.0) * 0.14 + 0.61,
            )

            # 2. Sample pixel-extracted master region maps with Stage C inverse-glass-lens refraction compensation
            sx_ref = clamp01(((x + 0.03 * n_coarse) / lens_scale + 1.0) * 0.5) * 2.0 - 1.0
            sy_ref = clamp01((((y - 0.09) + 0.03 * n_warp) / lens_scale + 1.0) * 0.5) * 2.0 - 1.0
            ref_e05 = sample_ref_grid(REF_E05_GRID, sx_ref, sy_ref)
            ref_e06 = sample_ref_grid(REF_E06_GRID, sx_ref, sy_ref)
            ref_e07 = sample_ref_grid(REF_E07_GRID, sx_ref, sy_ref)
            ref_e08 = sample_ref_grid(REF_E08_GRID, sx_ref, sy_ref)

            # 3. V3.2 Fitted Thin-Sheet 3D Liquid Fold Sculpture (matching evaluate_fluid_voxel 1:1!)
            az = atan2(wz, wx)
            r_xz = sqrt(wx * wx + wz * wz)
            axial_w = smoothstep(0.08, 0.38, r_xz)
            helix_a = axial_w * sin(az * max(2, fc - 1) + wy * 2.15 + r_xz * 1.10 + 0.45 * n_coarse) + (1.0 - axial_w) * 0.20 * sin(wy * 2.8)
            helix_b = axial_w * cos(az * fc - wy * 1.85 + r_xz * 0.75 + 0.45 * n_warp) + (1.0 - axial_w) * 0.20 * cos(wx * 2.8)
            fold_field = 0.58 * helix_a + 0.42 * helix_b

            sheet_ridge = exp(-(((fold_field - fold_iso) / fold_sigma) ** 2))
            sheet_lobe = smoothstep(-0.08, 0.65, fold_field + 0.12 * n_detail)

            # Sculpted emerald curtains (E05_emerald)
            ref_e05_sharp = smoothstep(em_thresh, min(0.96, em_thresh + 0.38), ref_e05)
            em_front = clamp01(ref_e05_sharp * em_anchor * (0.68 + 0.16 * sheet_lobe + 0.36 * sheet_ridge))
            em_rear = clamp01((0.32 * sheet_ridge * sheet_ridge) * smoothstep(0.32, 0.88, r_xz) * (1.0 - 0.50 * smoothstep(0.05, 0.65, x)))
            emerald_w = clamp01(mix(em_rear, em_front, front_anchor))
            emerald_crest = clamp01(emerald_w * (0.65 * sheet_ridge + 0.35 * smoothstep(0.14, 0.80, 0.5 + 0.5 * n_silk)))

            # Deep abyssal sapphire contrast valleys (E06_sapphire)
            sapph_front = clamp01(smoothstep(0.16, 0.76, ref_e06) * sapph_anchor * (1.0 - 0.90 * emerald_w))
            sapph_rear = clamp01(smoothstep(-0.08, 0.68, -fold_field - 0.14 * n_warp) * (1.0 - 0.80 * emerald_w) * 0.68)
            sapphire_w = clamp01(mix(sapph_rear, sapph_front, front_anchor))
            deep_valley = clamp01(smoothstep(0.22, 0.82, sapphire_w) * (1.0 - 0.90 * emerald_w))

            # Decoupled luminous clear aqua pocket (E07_aqua)
            aqua_gate = 1.0 - smoothstep(0.50 + aq_gate_y, 0.86 + aq_gate_y, y)
            aqua_front = clamp01((smoothstep(0.10, 0.68, ref_e07) * aq_anchor + ref_e08 * decouple) * (1.0 - 0.24 * emerald_w) * (0.55 + 0.45 * aqua_gate))
            aqua_rear = clamp01(smoothstep(-0.22, 0.55, 0.18 - abs(y + 0.16) + 0.22 * helix_b) * (1.0 - 0.72 * emerald_w) * 0.22)
            aqua_w = clamp01(mix(aqua_rear, aqua_front, front_anchor))

            # 4. High-Contrast Jewel Color Synthesis (un-premultiplied sRGB)
            t_s = 0.12 + 0.88 * (1.0 - deep_valley)
            cr = mix(sapph_dark[0], sapph_mid[0], t_s)
            cg = mix(sapph_dark[1], sapph_mid[1], t_s)
            cb = mix(sapph_dark[2], sapph_mid[2], t_s)

            # Subtle oceanic teal fold ribbons inside mid-sapphire
            teal_vein = sheet_ridge * (1.0 - emerald_w * 0.78) * (1.0 - deep_valley * 0.65)
            cr = mix(cr, 0.040, teal_vein * 0.28)
            cg = mix(cg, 0.420, teal_vein * 0.28)
            cb = mix(cb, 0.580, teal_vein * 0.28)

            # Layer focused clear aqua
            aq_silk = smoothstep(-0.22, 0.68, n_silk + 0.28 * ref_e08)
            ar = mix(ar_base * 0.84, min(0.64, ar_base * 1.15), aq_silk)
            ag = mix(ag_base * 0.94, 0.99, aq_silk)
            ab = mix(ab_base * 0.96, 1.00, aq_silk)
            cr = mix(cr, ar, aqua_w * 0.96)
            cg = mix(cg, ag, aqua_w * 0.96)
            cb = mix(cb, ab, aqua_w * 0.96)

            # Layer sculpted lush emerald-jade 3D fold sheets
            er = min(0.48, MASTER_EMERALD_RGB[0] * 0.76 + 0.14 * sheet_ridge + 0.08 * emerald_crest)
            eg = min(0.88, MASTER_EMERALD_RGB[1] * 1.04 * em_boost + 0.14 * sheet_ridge + 0.06 * emerald_crest)
            eb = min(0.32, MASTER_EMERALD_RGB[2] * 0.66 + 0.04 * sheet_ridge)
            cr = mix(cr, er, emerald_w * 0.95)
            cg = mix(cg, eg, emerald_w * 0.95)
            cb = mix(cb, eb, emerald_w * 0.95)

            # 5. Structured Low-Fog Bimodal Optical Density (Linear Alpha, ZERO baked meniscus cut!)
            sapph_pocket = sapphire_w * sapphire_w
            em_fold = emerald_w * (0.42 + 0.66 * sheet_ridge)
            sheet_density = (
                base_density
                + emerald_fold_gain * em_fold
                + sapphire_absorption_gain * sapph_pocket
                + aqua_density_gain * aqua_w
            )
            silk_mod = 0.88 + 0.18 * smoothstep(-0.36, 0.68, n_silk)
            radial_core = 0.84 + 0.18 * (1.0 - r)
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
        f"[generate_orb_volume] Synthesizing V3.2 MASTER_FIDELITY_V32-driven {WIDTH}x{HEIGHT}x{DEPTH} 3D FluidVolume "
        f"(regions={REGIONS_PATH.name}, v32={V32_CONFIG_PATH.name}, single-source GPU meniscus, seed={SEED}, workers={workers})..."
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
