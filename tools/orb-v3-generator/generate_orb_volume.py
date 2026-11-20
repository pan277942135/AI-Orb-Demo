#!/usr/bin/env python3
"""
AI-Orb-Demo V3 — Continuous 3D Fluid Volume Synthesizer (Lossless RGBA WebP)

Deterministically synthesizes FluidVolume(x, y, z) on [-1, 1]^3 at 256x256x32
calibrated directly to the sole frozen visual master:
  public/assets/aistudio/____________910d1531.png
  SHA-256: 910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122

Outputs 32 Lossless RGBA WebP Z-slices into:
  app/src/main/assets/orb_v3/volume/fluid_color_z00.webp .. fluid_color_z31.webp

Convention:
  - RGB: Un-premultiplied sRGB fluid color (uploaded as GL_SRGB8_ALPHA8)
  - A:   Linear volumetric optical density [0..255] (0 in clear upper glass cavity & outside r>0.96)
  - Image row 0 corresponds to y = +1.0 (top) so slices are human-inspectable right-side-up;
    CORE_GLASS_FRAG maps volumeUv.y = 0.5 - pos.y * 0.5 consistently.
"""

from __future__ import annotations

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


def build_lattice(n: int, seed: int) -> list[float]:
    rng = random.Random(seed)
    return [rng.uniform(-1.0, 1.0) for _ in range(n * n * n)]


# Precomputed deterministic 3D value-noise grids for fast continuous 3D FBM
GRID_A_N = 16
GRID_B_N = 32
GRID_A = build_lattice(GRID_A_N, SEED + 101)
GRID_B = build_lattice(GRID_B_N, SEED + 202)
GRID_C = build_lattice(GRID_A_N, SEED + 303)


def sample_grid(grid: list[float], n: int, x: float, y: float, z: float) -> float:
    # Periodic smoothstep-interpolated 3D lattice lookup
    fx = (x * n) % n
    fy = (y * n) % n
    fz = (z * n) % n

    ix0 = int(fx)
    iy0 = int(fy)
    iz0 = int(fz)
    tx = fx - ix0
    ty = fy - iy0
    tz = fz - iz0

    # Quintic C2 smoothstep for zero derivative seams across Z slices
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


def render_slice(z_idx: int) -> tuple[int, str, int]:
    # Normalized Z in [-1, 1] centered on texel centers for exact GL_TEXTURE_3D alignment
    z = ((z_idx + 0.5) / DEPTH) * 2.0 - 1.0
    buf = bytearray(WIDTH * HEIGHT * 4)

    sin = math.sin
    cos = math.cos
    sqrt = math.sqrt
    atan2 = math.atan2
    exp = math.exp

    z_n = (z + 1.0) * 0.5
    off = 0

    for row in range(HEIGHT):
        # Row 0 = top (+1.0), Row 255 = bottom (-1.0)
        y = 1.0 - ((row + 0.5) / HEIGHT) * 2.0
        y_n = (y + 1.0) * 0.5
        y2_z2 = y * y + z * z

        for col in range(WIDTH):
            x = ((col + 0.5) / WIDTH) * 2.0 - 1.0
            r2 = x * x + y2_z2

            # Fast spherical boundary cull (strictly zero density outside r = 0.985, capturing smooth pole caps at z00/z31)
            if r2 >= 0.970225:
                buf[off] = 6
                buf[off + 1] = 28
                buf[off + 2] = 44
                buf[off + 3] = 0
                off += 4
                continue

            r = sqrt(r2)
            sphere_mask = 1.0 - smoothstep(0.86, 0.985, r)

            x_n = (x + 1.0) * 0.5

            # Continuous 3D domain-warped organic flow fields
            n_coarse = sample_grid(GRID_A, GRID_A_N, x_n * 0.42 + 0.13, y_n * 0.42 + 0.27, z_n * 0.42 + 0.09)
            n_warp = sample_grid(GRID_C, GRID_A_N, x_n * 0.48 + 0.51, y_n * 0.48 + 0.19, z_n * 0.48 + 0.37)

            wx = x + 0.24 * n_coarse + 0.11 * sin(y * 3.1 + z * 2.3)
            wy = y + 0.20 * n_warp + 0.09 * cos(x * 2.7 - z * 2.5)
            wz = z + 0.24 * (n_coarse * 0.6 - n_warp * 0.7) + 0.10 * sin(x * 2.4 + y * 2.9)

            n_detail = sample_grid(
                GRID_B,
                GRID_B_N,
                (wx + 1.0) * 0.34 + 0.11,
                (wy + 1.0) * 0.34 + 0.23,
                (wz + 1.0) * 0.18 + 0.31,
            )
            n_silk = sample_grid(
                GRID_B,
                GRID_B_N,
                (wx + 1.0) * 0.56 + 0.43,
                (wy + 1.0) * 0.56 + 0.07,
                (wz + 1.0) * 0.16 + 0.61,
            )

            # 1. Sculpted concave tilted upper meniscus (clear upper glass cavity above!)
            # Matches master E03/E04: lower dip mid-right, rising along left & outer walls
            r_xz2 = x * x + z * z
            meniscus_y = (
                0.15
                + 0.25 * r_xz2
                - 0.16 * x
                + 0.06 * z
                + 0.09 * n_coarse
                + 0.045 * n_detail
            )
            below_meniscus = 1.0 - smoothstep(meniscus_y - 0.10, meniscus_y + 0.075, y)

            if below_meniscus <= 0.002:
                # Clear upper glass dome cavity: zero volumetric alpha
                buf[off] = 10
                buf[off + 1] = 44
                buf[off + 2] = 60
                buf[off + 3] = 0
                off += 4
                continue

            # 2. 3D helical liquid silk curtains (seamless 360° structure across front & rear hemispheres!)
            az = atan2(wz, wx)
            r_xz = sqrt(wx * wx + wz * wz)
            axial_w = smoothstep(0.06, 0.38, r_xz)

            # Organic multi-lobe helical fold sheets (axial_w removes any atan2 pinch along Y axis!)
            helix1 = axial_w * sin(az * 2.0 + wy * 2.35 + r_xz * 1.25 + n_coarse * 2.25) + (1.0 - axial_w) * n_coarse
            helix2 = axial_w * cos(az * 3.0 - wy * 1.85 + n_warp * 2.35 + n_detail * 1.05) + (1.0 - axial_w) * n_warp
            fold_sheet = 0.56 * helix1 + 0.44 * helix2

            # Primary Emerald-Green Translucent Curtains (E05):
            # Calibrated to master: strong lush curtain on left flank (x < 0.05) + curving right-center fold
            # + continuous rear-hemisphere lobes so 360° core rotation is richly sculpted from all angles!
            left_curtain_bias = smoothstep(-0.24, 0.72, -x * 0.72 + z * 0.22 - y * 0.18 + 0.24 * n_coarse)
            right_curtain_ribbon = exp(-((x - 0.34 - 0.16 * sin(y * 3.0 + z * 1.6)) ** 2) * 5.0) * smoothstep(-0.65, 0.35, y)
            rear_helical_curtain = smoothstep(-0.18, 0.68, fold_sheet) * smoothstep(-0.42, 0.72, -z + 0.26 * axial_w * sin(az * 2.0))

            emerald_structural = clamp01(
                0.56 * left_curtain_bias * (0.48 + 0.52 * smoothstep(-0.45, 0.55, fold_sheet))
                + 0.48 * right_curtain_ribbon * (0.55 + 0.45 * smoothstep(-0.3, 0.6, n_detail))
                + 0.46 * rear_helical_curtain
                + 0.32 * smoothstep(0.08, 0.78, fold_sheet + 0.35 * n_detail)
            )
            # Crisp organic veil crests inside emerald folds
            emerald_crest = smoothstep(0.15, 0.82, 0.5 + 0.5 * n_silk) * emerald_structural

            # 3. Deep Sapphire-Teal Contrast Valleys (E06):
            # Creates deep dark midnight-blue pockets between emerald folds for true 3D depth contrast
            sapphire_valley = clamp01(
                smoothstep(-0.55, 0.55, -fold_sheet + 0.35 * x - 0.25 * n_warp)
                * (1.0 - 0.68 * emerald_structural)
            )

            # 4. Lower-Belly Luminous Aqua-Cyan Core (E07):
            # Concentrated in lower-center & lower-right interior belly (5-7 o'clock) + wraps softly in 3D
            lower_belly = (1.0 - smoothstep(-0.88, 0.12, y)) * smoothstep(-0.68, 0.58, x * 0.46 - y * 0.78 + 0.28 * z)
            aqua_pocket = clamp01(
                lower_belly
                * (0.55 + 0.45 * smoothstep(-0.35, 0.58, n_detail + 0.5 * helix2))
                * (1.0 - 0.48 * emerald_structural)
            )

            # 5. Volumetric Color Synthesis (calibrated sRGB matching measured master pixel centroids!)
            # Base deep abyssal sapphire-teal
            cr = mix(0.016, 0.048, sapphire_valley)
            cg = mix(0.115, 0.295, sapphire_valley)
            cb = mix(0.215, 0.455, sapphire_valley)

            # Mid-tone oceanic cyan-blue swirl ribbons
            cyan_ribbon = smoothstep(0.05, 0.75, 0.5 + 0.5 * sin(az * 3.0 + wy * 3.4 + n_detail * 2.2))
            cr = mix(cr, 0.065, cyan_ribbon * 0.42)
            cg = mix(cg, 0.455, cyan_ribbon * 0.42)
            cb = mix(cb, 0.585, cyan_ribbon * 0.42)

            # Layer luminous lower-belly aqua-cyan
            aqua_r = mix(0.11, 0.35, smoothstep(-0.15, 0.65, n_silk))
            aqua_g = mix(0.64, 0.89, smoothstep(-0.15, 0.65, n_silk))
            aqua_b = mix(0.76, 0.96, smoothstep(-0.15, 0.65, n_silk))
            cr = mix(cr, aqua_r, aqua_pocket * 0.88)
            cg = mix(cg, aqua_g, aqua_pocket * 0.88)
            cb = mix(cb, aqua_b, aqua_pocket * 0.88)

            # Layer lush emerald-jade translucent curtains (deep moss -> vivid jade -> sunlit chartreuse crest)
            em_r = mix(0.045, 0.255, smoothstep(-0.35, 0.55, n_detail))
            em_g = mix(0.355, 0.595, smoothstep(-0.35, 0.55, n_detail))
            em_b = mix(0.165, 0.340, smoothstep(-0.35, 0.55, n_detail))
            # Sunlit chartreuse-emerald fold highlights (matches master left & right-mid bright leaf-water edges)
            em_r = mix(em_r, 0.520, emerald_crest * 0.62)
            em_g = mix(em_g, 0.805, emerald_crest * 0.62)
            em_b = mix(em_b, 0.365, emerald_crest * 0.62)

            cr = mix(cr, em_r, emerald_structural * 0.92)
            cg = mix(cg, em_g, emerald_structural * 0.92)
            cb = mix(cb, em_b, emerald_structural * 0.92)

            # 6. Physical Volumetric Density (Alpha channel — strictly linear, non-premultiplied!)
            # Structured variation: denser inside emerald curtains & sapphire core, clearer in gaps so light transmits!
            filament_density = 0.26 + 0.34 * emerald_structural + 0.22 * sapphire_valley + 0.18 * aqua_pocket
            silk_mod = 0.82 + 0.28 * smoothstep(-0.45, 0.65, n_silk)
            # Subtle core-depth fullness so rotating sphere reads as solid 3D liquid volume
            radial_core = 0.80 + 0.24 * (1.0 - r)
            density = clamp01(filament_density * silk_mod * radial_core * below_meniscus * sphere_mask)

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
    print(f"[generate_orb_volume] Synthesizing {WIDTH}x{HEIGHT}x{DEPTH} 3D FluidVolume (seed={SEED}, workers={workers})...")

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
