#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Canonical 3D Fluid Volume Model (Single Source of Truth)

Shared 1:1 across:
  1. Stage C offline fitting (`fitting/volume_structure_fitter.py`)
  2. Lossless RGBA WebP 3D slice synthesis (`generate_orb_volume.py`)
  3. Final decoded-WebP independent verification (`fitting/validate_fit.py`)
  4. Automated algorithm consistency & round-trip tests (`test_v32_closure_gates.py`)

Design guarantees:
  - Pure Python 3.11 stdlib + ffmpeg (zero numpy/PIL/scipy).
  - Exact identical voxel field evaluation (`evaluate_canonical_voxel`) for both
    analytical fitting and 256x256x32 WebP slice generation (ZERO drift).
  - ZERO baked meniscus clipping: full spherical containment only (`r <= 0.985`),
    keeping GPU `cavityPhi()` + `intersectCrystalLens()` as the sole liquid boundary.
  - Symmetrical 1.45 IOR spherical refraction compensation without artificial vertical bias,
    preserving crisp 3D emerald folds (E05), deep sapphire valleys (E06), luminous aqua
    pocket (E07), and smooth 360-degree rear-hemisphere rotation continuity.
"""

from __future__ import annotations

import math
import random
import subprocess
from pathlib import Path
from typing import Any

SEED = 20261120
WIDTH = 256
HEIGHT = 256
DEPTH = 32
GRID_A_N = 16
GRID_B_N = 32


def _build_lattice(n: int, seed: int) -> list[float]:
    rng = random.Random(seed)
    return [rng.uniform(-1.0, 1.0) for _ in range(n * n * n)]


GRID_A: list[float] = _build_lattice(GRID_A_N, SEED + 101)
GRID_B: list[float] = _build_lattice(GRID_B_N, SEED + 202)
GRID_C: list[float] = _build_lattice(GRID_A_N, SEED + 303)


def clamp01(v: float) -> float:
    if v <= 0.0:
        return 0.0
    if v >= 1.0:
        return 1.0
    return v


def smoothstep(e0: float, e1: float, x: float) -> float:
    if not (math.isfinite(e0) and math.isfinite(e1) and math.isfinite(x)):
        return 0.0
    if e0 >= e1:
        raise ValueError(f"smoothstep requires ordered edges e0 < e1, got {e0} >= {e1}")
    t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3.0 - 2.0 * t)


def inverse_smoothstep(lo: float, hi: float, x: float) -> float:
    """Monotonically decreasing smoothstep with explicit, ordered bounds."""
    return 1.0 - smoothstep(lo, hi, x)


def mix(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def sample_ref_grid(grid: list[float], nx: float, ny: float, grid_n: int = 32) -> float:
    """Bilinearly samples a 32x32 pixel-extracted master region grid (nx in [-1,1], ny in [+1,-1])."""
    max_i = grid_n - 1
    fx = clamp01((nx + 1.0) * 0.5) * max_i
    fy = clamp01((1.0 - ny) * 0.5) * max_i
    x0 = int(fx)
    y0 = int(fy)
    x1 = min(max_i, x0 + 1)
    y1 = min(max_i, y0 + 1)
    tx = fx - x0
    ty = fy - y0
    c00 = grid[y0 * grid_n + x0]
    c10 = grid[y0 * grid_n + x1]
    c01 = grid[y1 * grid_n + x0]
    c11 = grid[y1 * grid_n + x1]
    top = c00 + (c10 - c00) * tx
    bot = c01 + (c11 - c01) * tx
    return top + (bot - top) * ty


def sample_lattice_3d(grid: list[float], n: int, x: float, y: float, z: float) -> float:
    """Periodic quintic-interpolated 3D noise lattice sample."""
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


def extract_volume_params(cfg: dict[str, Any]) -> dict[str, float]:
    """Extracts the canonical Stage C / WebP generator parameter dictionary from MASTER_FIDELITY_V32."""
    def read_sec(sec: str, key: str) -> float:
        entry = cfg[sec][key]
        if isinstance(entry, dict):
            return float(entry["fittedValue"])
        return float(entry)

    return {
        "foldCount": read_sec("fluidVolume", "foldCount"),
        "foldIso": read_sec("fluidVolume", "foldIso"),
        "foldThickness": read_sec("fluidVolume", "foldThickness"),
        "baseDensity": read_sec("fluidVolume", "baseDensity"),
        "densityScale": read_sec("fluidVolume", "densityScale"),
        "foldNormalPerturbation": read_sec("fluidVolume", "foldNormalPerturbation"),
        "foldRefraction": read_sec("fluidVolume", "foldRefraction"),
        "foldSpecularGain": read_sec("fluidVolume", "foldSpecularGain"),
        "emeraldFoldGain": read_sec("emeraldFolds", "emeraldFoldGain"),
        "emeraldAnchorWeight": read_sec("emeraldFolds", "emeraldAnchorWeight"),
        "emeraldGreenBoost": read_sec("emeraldFolds", "emeraldGreenBoost"),
        "emeraldThreshold": read_sec("emeraldFolds", "emeraldThreshold"),
        "sapphireAbsorptionGain": read_sec("sapphireValleys", "sapphireAbsorptionGain"),
        "sapphireAnchorWeight": read_sec("sapphireValleys", "sapphireAnchorWeight"),
        "sapphireDepthDarken": read_sec("sapphireValleys", "sapphireDepthDarken"),
        "beerLambertR": read_sec("sapphireValleys", "beerLambertR"),
        "beerLambertG": read_sec("sapphireValleys", "beerLambertG"),
        "beerLambertB": read_sec("sapphireValleys", "beerLambertB"),
        "aquaDensityGain": read_sec("aquaRegion", "aquaDensityGain"),
        "aquaAnchorWeight": read_sec("aquaRegion", "aquaAnchorWeight"),
        "aquaBrightnessGain": read_sec("aquaRegion", "aquaBrightnessGain"),
        "aquaUpperGateY": read_sec("aquaRegion", "aquaUpperGateY"),
        "causticDecoupleWeight": read_sec("caustics", "causticDecoupleWeight"),
    }


def evaluate_canonical_voxel(
    x: float,
    y: float,
    z: float,
    ref_grids: dict[str, list[float]],
    master_rgb: dict[str, list[float]],
    vp: dict[str, float],
    grid_n: int = 32,
) -> tuple[float, float, float, float, float]:
    """
    Single authoritative 3D fluid voxel synthesizer shared bit-for-bit between
    Stage C fitting and Lossless RGBA WebP slice generation.

    Returns (srgb_r, srgb_g, srgb_b, linear_alpha_density, sheet_ridge).
    """
    r2 = x * x + y * y + z * z
    if r2 >= 0.970225:
        return 4.0 / 255.0, 20.0 / 255.0, 36.0 / 255.0, 0.0, 0.0

    r = math.sqrt(r2)
    sphere_mask = 1.0 - smoothstep(0.80, 0.985, r)

    x_n = (x + 1.0) * 0.5
    y_n = (y + 1.0) * 0.5
    z_n = (z + 1.0) * 0.5

    # 1. Continuous 3D domain-warped organic flow fields (gentle Z gradient preserves 32-slice continuity)
    n_coarse = sample_lattice_3d(GRID_A, GRID_A_N, x_n * 0.36 + 0.13, y_n * 0.36 + 0.27, z_n * 0.22 + 0.09)
    n_warp = sample_lattice_3d(GRID_C, GRID_A_N, x_n * 0.40 + 0.51, y_n * 0.40 + 0.19, z_n * 0.22 + 0.37)

    wx = x + 0.075 * n_coarse + 0.060 * math.sin(y * 3.0 + z * 1.15)
    wy = y + 0.065 * n_warp + 0.050 * math.cos(x * 2.6 - z * 1.15)
    wz = z + 0.065 * (n_coarse * 0.5 - n_warp * 0.6) + 0.050 * math.sin(x * 2.2 + y * 2.6)

    n_detail = sample_lattice_3d(
        GRID_B,
        GRID_B_N,
        (wx + 1.0) * 0.32 + 0.11,
        (wy + 1.0) * 0.32 + 0.23,
        (wz + 1.0) * 0.13 + 0.31,
    )
    n_silk = sample_lattice_3d(
        GRID_B,
        GRID_B_N,
        (wx + 1.0) * 0.48 + 0.43,
        (wy + 1.0) * 0.48 + 0.07,
        (wz + 1.0) * 0.13 + 0.61,
    )

    # 2. Exact signed-Z 1.45 IOR spherical refraction inverse scaling:
    #    At z=+0.945 -> scale=0.945; at z=0 -> scale=0.68; at z=-0.55 -> scale=0.526.
    #    Allows rays entering upper dome and bending into z<0 to sample exact upper E05/E06/E07!
    front_anchor = smoothstep(-0.85, -0.35, z)
    lens_scale = max(0.44, min(0.96, 0.68 + 0.28 * z))
    sx_ref = clamp01(((x + 0.016 * n_coarse) / lens_scale + 1.0) * 0.5) * 2.0 - 1.0
    sy_ref = clamp01(((y + 0.016 * n_warp) / lens_scale + 1.0) * 0.5) * 2.0 - 1.0

    ref_e05 = sample_ref_grid(ref_grids["E05_emerald"], sx_ref, sy_ref, grid_n)
    ref_e06 = sample_ref_grid(ref_grids["E06_sapphire"], sx_ref, sy_ref, grid_n)
    ref_e07 = sample_ref_grid(ref_grids["E07_aqua"], sx_ref, sy_ref, grid_n)
    ref_e08 = sample_ref_grid(ref_grids["E08_caustics"], sx_ref, sy_ref, grid_n)

    # 3. Thin-sheet 3D helical liquid fold field (crisp XY sculpture + genuine 3D Z variation + 360° wrap)
    az = math.atan2(wz, wx)
    r_xz = math.hypot(wx, wz)
    axial_w = smoothstep(0.08, 0.38, r_xz)
    fc = int(round(vp["foldCount"]))
    helix_a = axial_w * math.sin(az * max(2, fc - 1) + wy * 2.15 + r_xz * 1.10 + 0.45 * n_coarse) + (1.0 - axial_w) * 0.20 * math.sin(wy * 2.8)
    helix_b = axial_w * math.cos(az * fc - wy * 1.85 + r_xz * 0.75 + 0.45 * n_warp) + (1.0 - axial_w) * 0.20 * math.cos(wx * 2.8)
    fold_field = 0.58 * helix_a + 0.42 * helix_b

    fold_iso = vp["foldIso"]
    fold_sigma = max(0.08, vp["foldThickness"])
    sheet_ridge = math.exp(-(((fold_field - fold_iso) / fold_sigma) ** 2))
    sheet_lobe = smoothstep(-0.08, 0.65, fold_field + 0.12 * n_detail)

    # Depth-varying 3D fold modulation (preserves rich front-vs-back Z variation for 3D realism & continuity checks)
    depth_fold = 0.55 + 0.45 * smoothstep(-0.35, 0.55, fold_field + 0.35 * z)

    # 4. Region weights anchored strictly to frozen master E05 / E06 / E07
    #    Competitive dominance prevents high-density emerald from stomping on adjacent lower sapphire/aqua!
    em_thresh = vp["emeraldThreshold"]
    dom_e05 = smoothstep(-0.18, 0.18, ref_e05 - 0.85 * max(ref_e06, ref_e07))
    ref_e05_sharp = smoothstep(em_thresh, min(0.96, em_thresh + 0.22), ref_e05) * (0.25 + 0.75 * dom_e05)
    em_path_bal = 0.76 + 0.54 * smoothstep(-0.35, 0.45, y)
    em_front = clamp01(ref_e05_sharp * vp["emeraldAnchorWeight"] * em_path_bal * (0.68 + 0.16 * sheet_lobe + 0.26 * sheet_ridge))
    em_rear = clamp01((0.34 * sheet_ridge * sheet_ridge) * smoothstep(0.28, 0.86, r_xz) * (1.0 - 0.35 * smoothstep(0.10, 0.68, x)))
    emerald_w = clamp01(mix(em_rear, em_front, front_anchor))
    emerald_crest = clamp01(emerald_w * (0.65 * sheet_ridge + 0.35 * smoothstep(0.14, 0.80, 0.5 + 0.5 * n_silk)))

    # Clear luminous aqua pocket (E07) — balanced for lower-basin radiance + crisp upper gate
    decouple = vp["causticDecoupleWeight"]
    aqua_gate = 1.0 - smoothstep(0.06 + vp["aquaUpperGateY"], 0.46 + vp["aquaUpperGateY"], y)
    dom_e07 = smoothstep(-0.16, 0.16, ref_e07 - 0.75 * ref_e05)
    ref_e07_sharp = smoothstep(0.14, 0.48, ref_e07) * (0.30 + 0.70 * dom_e07)
    lower_aqua_boost = 1.0 + 0.48 * inverse_smoothstep(-0.58, 0.05, y)
    aqua_front = clamp01(
        (ref_e07_sharp * vp["aquaAnchorWeight"] * lower_aqua_boost + ref_e08 * decouple * 0.45)
        * (1.0 - 0.82 * emerald_w)
        * (0.34 + 0.66 * aqua_gate)
    )
    aqua_rear = clamp01(smoothstep(-0.20, 0.55, 0.18 - abs(y + 0.14) + 0.24 * helix_b) * (1.0 - 0.78 * emerald_w) * 0.28)
    aqua_w = clamp01(mix(aqua_rear, aqua_front, front_anchor))

    # Deep oceanic sapphire contrast valleys (E06) — stronger lower presence, crisp upper boundary
    dom_e06 = smoothstep(-0.16, 0.16, ref_e06 - 0.72 * max(ref_e05, ref_e07))
    ref_e06_sharp = smoothstep(0.14, 0.50, ref_e06) * (0.30 + 0.70 * dom_e06)
    sapph_path_bal = 1.48 - 0.62 * smoothstep(-0.30, 0.48, y)
    sapph_front = clamp01(ref_e06_sharp * vp["sapphireAnchorWeight"] * sapph_path_bal * (1.0 - 0.85 * emerald_w) * (1.0 - 0.82 * aqua_w))
    sapph_rear = clamp01(smoothstep(-0.08, 0.68, -fold_field - 0.14 * n_warp) * (1.0 - 0.82 * emerald_w) * (1.0 - 0.55 * aqua_w) * 0.72)
    sapphire_w = clamp01(mix(sapph_rear, sapph_front, front_anchor))
    deep_valley = clamp01(smoothstep(0.16, 0.72, sapphire_w) * (1.0 - 0.88 * emerald_w))

    # 5. Calibrated sRGB Jewel Color Synthesis (un-premultiplied sRGB)
    m_em = master_rgb["E05_emerald"]
    m_sp = master_rgb["E06_sapphire"]
    m_aq = master_rgb["E07_aqua"]

    dark_k = vp["sapphireDepthDarken"]
    # Clear neutral crystal water base where none of E05/E06/E07 dominate; rich royal sapphire strictly inside E06!
    sapph_dark = (0.012 * dark_k, 0.095 * dark_k, 0.295 * dark_k)
    sapph_mid = (
        max(0.025, min(0.14, m_sp[0] * 0.85)),
        max(0.20, min(0.40, m_sp[1] * 1.02)),
        max(0.46, min(0.70, m_sp[2] * 1.48)),
    )
    t_s = 0.18 + 0.82 * (1.0 - deep_valley * (0.75 + 0.25 * depth_fold))
    sp_r = mix(sapph_dark[0], sapph_mid[0], t_s)
    sp_g = mix(sapph_dark[1], sapph_mid[1], t_s)
    sp_b = mix(sapph_dark[2], sapph_mid[2], t_s)

    # Clear crystal highlight tint for non-E05/E06/E07 optical windows (matches Master PNG luminous crystal regions)
    clear_w = clamp01(1.0 - max(emerald_w, max(sapphire_w, aqua_w)))
    cr = mix(sp_r, 0.72, clear_w * 0.82)
    cg = mix(sp_g, 0.78, clear_w * 0.82)
    cb = mix(sp_b, 0.72, clear_w * 0.82)

    # Subtle oceanic teal fold ribbons inside mid-sapphire (staying within sapphire luminance band)
    teal_vein = sheet_ridge * sapphire_w * (1.0 - emerald_w * 0.85) * (1.0 - deep_valley * 0.70) * (1.0 - aqua_w * 0.70)
    cr = mix(cr, 0.036, teal_vein * 0.22)
    cg = mix(cg, 0.350, teal_vein * 0.22)
    cb = mix(cb, 0.540, teal_vein * 0.22)

    # Layer focused luminous clear aqua (high cyan luminance survives Beer-Lambert + ACES cleanly even in lower basin)
    aq_b = vp["aquaBrightnessGain"]
    aq_silk = smoothstep(-0.22, 0.68, n_silk + 0.25 * ref_e08)
    lower_luma_comp = 1.0 + 0.14 * inverse_smoothstep(-0.65, 0.0, y)
    ar_base = clamp01(min(0.44, m_aq[0] * 0.88 * aq_b))
    ag_base = clamp01(min(0.99, m_aq[1] * 1.50 * aq_b * lower_luma_comp))
    ab_base = clamp01(min(1.00, m_aq[2] * 1.48 * aq_b * lower_luma_comp))
    ar = mix(ar_base * 0.86, min(0.50, ar_base * 1.10), aq_silk)
    ag = mix(ag_base * 0.94, 0.99, aq_silk)
    ab = mix(ab_base * 0.96, 1.00, aq_silk)
    cr = mix(cr, ar, aqua_w * 0.97)
    cg = mix(cg, ag, aqua_w * 0.97)
    cb = mix(cb, ab, aqua_w * 0.97)

    # Layer sculpted lush emerald-jade 3D fold sheets
    em_g = vp["emeraldGreenBoost"]
    er = min(0.44, m_em[0] * 0.76 + 0.13 * sheet_ridge + 0.07 * emerald_crest)
    eg = min(0.88, m_em[1] * 1.06 * em_g + 0.14 * sheet_ridge + 0.06 * emerald_crest)
    eb = min(0.30, m_em[2] * 0.64 + 0.04 * sheet_ridge)
    cr = mix(cr, er, emerald_w * 0.96)
    cg = mix(cg, eg, emerald_w * 0.96)
    cb = mix(cb, eb, emerald_w * 0.96)

    # 6. Structured Low-Fog Bimodal Optical Density (Linear Alpha, ZERO baked meniscus cut!)
    sapph_pocket = sapphire_w * sapphire_w
    em_fold = emerald_w * (0.44 + 0.64 * sheet_ridge)
    sheet_density = (
        vp["baseDensity"]
        + vp["emeraldFoldGain"] * em_fold
        + vp["sapphireAbsorptionGain"] * sapph_pocket
        + vp["aquaDensityGain"] * aqua_w
    )
    silk_mod = 0.88 + 0.18 * smoothstep(-0.36, 0.68, n_silk)
    radial_core = 0.84 + 0.18 * (1.0 - r)
    density = clamp01(sheet_density * silk_mod * radial_core * sphere_mask)

    return clamp01(cr), clamp01(cg), clamp01(cb), density, sheet_ridge


def quantize_rgba_u8(r: float, g: float, b: float, a: float) -> tuple[int, int, int, int]:
    return (
        int(clamp01(r) * 255.0 + 0.5),
        int(clamp01(g) * 255.0 + 0.5),
        int(clamp01(b) * 255.0 + 0.5),
        int(clamp01(a) * 255.0 + 0.5),
    )


def render_canonical_slice_rgba(
    z_idx: int,
    ref_grids: dict[str, list[float]],
    master_rgb: dict[str, list[float]],
    vp: dict[str, float],
    width: int = WIDTH,
    height: int = HEIGHT,
    depth: int = DEPTH,
    grid_n: int = 32,
) -> bytearray:
    """Renders one RGBA8888 Z-slice using evaluate_canonical_voxel 1:1."""
    z = ((z_idx + 0.5) / depth) * 2.0 - 1.0
    buf = bytearray(width * height * 4)
    off = 0
    for row in range(height):
        # Row 0 = top (+1.0), Row height-1 = bottom (-1.0)
        y = 1.0 - ((row + 0.5) / height) * 2.0
        for col in range(width):
            x = ((col + 0.5) / width) * 2.0 - 1.0
            cr, cg, cb, density, _ = evaluate_canonical_voxel(x, y, z, ref_grids, master_rgb, vp, grid_n)
            buf[off] = int(cr * 255.0 + 0.5)
            buf[off + 1] = int(cg * 255.0 + 0.5)
            buf[off + 2] = int(cb * 255.0 + 0.5)
            buf[off + 3] = int(density * 255.0 + 0.5)
            off += 4
    return buf


def decode_webp_volume_3d(
    vol_dir: Path,
    width: int = WIDTH,
    height: int = HEIGHT,
    depth: int = DEPTH,
) -> list[bytes]:
    """Decodes all 32 Lossless RGBA WebP slices on disk back into raw RGBA8888 byte buffers."""
    expected_len = width * height * 4
    slices: list[bytes] = []
    for z in range(depth):
        path = vol_dir / f"fluid_color_z{z:02d}.webp"
        if not path.exists():
            raise FileNotFoundError(f"Missing WebP volume slice: {path}")
        cmd = ["ffmpeg", "-v", "error", "-i", str(path), "-f", "rawvideo", "-pix_fmt", "rgba", "-"]
        proc = subprocess.run(cmd, capture_output=True)
        if proc.returncode != 0:
            raise RuntimeError(f"ffmpeg decode failed for {path.name}: {proc.stderr.decode()}")
        raw = proc.stdout
        if len(raw) != expected_len:
            raise ValueError(f"Decoded slice {path.name} has length {len(raw)}, expected {expected_len}")
        slices.append(raw)
    return slices


def sample_trilinear_volume_3d(
    slices: list[bytes],
    x: float,
    y: float,
    z: float,
    width: int = WIDTH,
    height: int = HEIGHT,
    depth: int = DEPTH,
) -> tuple[float, float, float, float]:
    """
    Trilinearly samples decoded 3D WebP RGBA slices matching OpenGL ES 3.0 GL_TEXTURE_3D
    with GL_LINEAR + GL_CLAMP_TO_EDGE and OrbFluidVolumeRenderer UV mapping:
      u = x * 0.5 + 0.5, v = 0.5 - y * 0.5, w = z * 0.5 + 0.5
    """
    u = clamp01(x * 0.5 + 0.5)
    v = clamp01(0.5 - y * 0.5)
    w = clamp01(z * 0.5 + 0.5)

    fx = u * width - 0.5
    fy = v * height - 0.5
    fz = w * depth - 0.5

    x0 = max(0, min(width - 1, int(math.floor(fx))))
    y0 = max(0, min(height - 1, int(math.floor(fy))))
    z0 = max(0, min(depth - 1, int(math.floor(fz))))
    x1 = min(width - 1, x0 + 1)
    y1 = min(height - 1, y0 + 1)
    z1 = min(depth - 1, z0 + 1)

    tx = clamp01(fx - x0)
    ty = clamp01(fy - y0)
    tz = clamp01(fz - z0)

    s0 = slices[z0]
    s1 = slices[z1]
    row0 = y0 * width
    row1 = y1 * width

    i000 = (row0 + x0) * 4
    i100 = (row0 + x1) * 4
    i010 = (row1 + x0) * 4
    i110 = (row1 + x1) * 4

    inv255 = 1.0 / 255.0
    out = [0.0, 0.0, 0.0, 0.0]
    for c in range(4):
        c000 = s0[i000 + c]
        c100 = s0[i100 + c]
        c010 = s0[i010 + c]
        c110 = s0[i110 + c]
        c001 = s1[i000 + c]
        c101 = s1[i100 + c]
        c011 = s1[i010 + c]
        c111 = s1[i110 + c]

        x00 = c000 + (c100 - c000) * tx
        x10 = c010 + (c110 - c010) * tx
        x01 = c001 + (c101 - c001) * tx
        x11 = c011 + (c111 - c011) * tx

        y0_v = x00 + (x10 - x00) * ty
        y1_v = x01 + (x11 - x01) * ty
        out[c] = (y0_v + (y1_v - y0_v) * tz) * inv255

    return out[0], out[1], out[2], out[3]
