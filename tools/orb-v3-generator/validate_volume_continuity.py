#!/usr/bin/env python3
"""
AI-Orb-Demo V3 — 3D Volume Z-Axis Continuity & Master Structure Validator

Decodes all 32 volume slices (256x256x32 RGBA) and verifies:
  1. Smooth Z-axis continuity: adjacent slice MAE (RGB & Alpha) stays well below seam thresholds.
  2. Genuine 3D depth variation: slices are NOT copies of a single 2D image.
  3. Clean spherical boundary: cube corners strictly have Alpha == 0.
  4. Clear upper glass dome cavity: top-center dome has low/zero liquid density above meniscus.
  5. Master palette presence: mid-volume slice contains emerald-green, deep sapphire, and bright aqua voxels.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VOL_DIR = ROOT / "app" / "src" / "main" / "assets" / "orb_v3" / "volume"
WIDTH = 256
HEIGHT = 256
DEPTH = 32


def load_slice(z: int) -> bytes:
    path = VOL_DIR / f"fluid_color_z{z:02d}.webp"
    cmd = ["ffmpeg", "-v", "error", "-i", str(path), "-f", "rawvideo", "-pix_fmt", "rgba", "-"]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"Decode failed for {path.name}: {proc.stderr.decode()}")
    data = proc.stdout
    if len(data) != WIDTH * HEIGHT * 4:
        raise ValueError(f"Unexpected length for {path.name}: {len(data)}")
    return data


def main() -> int:
    slices = [load_slice(z) for z in range(DEPTH)]

    max_adj_interior_rgb_mae = 0.0
    max_adj_premul_rgb_mae = 0.0
    max_adj_alpha_mae = 0.0
    worst_pair = (0, 1)

    # Sample stride 4 pixels for fast deterministic metric check (16,384 voxels per slice)
    step = 16
    for z in range(DEPTH - 1):
        s0 = slices[z]
        s1 = slices[z + 1]
        interior_rgb_diff = 0
        interior_cnt = 0
        premul_rgb_diff = 0.0
        a_diff = 0
        count = 0
        for i in range(0, len(s0), step):
            r0, g0, b0, a0 = s0[i], s0[i + 1], s0[i + 2], s0[i + 3]
            r1, g1, b1, a1 = s1[i], s1[i + 1], s1[i + 2], s1[i + 3]
            a_diff += abs(a0 - a1)
            fa0 = a0 / 255.0
            fa1 = a1 / 255.0
            premul_rgb_diff += (
                abs(r0 * fa0 - r1 * fa1)
                + abs(g0 * fa0 - g1 * fa1)
                + abs(b0 * fa0 - b1 * fa1)
            )
            if a0 > 8 and a1 > 8:
                interior_rgb_diff += abs(r0 - r1) + abs(g0 - g1) + abs(b0 - b1)
                interior_cnt += 1
            count += 1
        interior_rgb_mae = interior_rgb_diff / max(1, interior_cnt * 3.0 * 255.0)
        premul_rgb_mae = premul_rgb_diff / (count * 3.0 * 255.0)
        a_mae = a_diff / (count * 255.0)
        if interior_rgb_mae > max_adj_interior_rgb_mae:
            max_adj_interior_rgb_mae = interior_rgb_mae
            worst_pair = (z, z + 1)
        if premul_rgb_mae > max_adj_premul_rgb_mae:
            max_adj_premul_rgb_mae = premul_rgb_mae
        if a_mae > max_adj_alpha_mae:
            max_adj_alpha_mae = a_mae

    # Check genuine 3D difference between front-mid (z=8) and back-mid (z=22)
    s_front = slices[8]
    s_back = slices[22]
    diff_3d = 0
    cnt_3d = 0
    for i in range(0, len(s_front), step):
        if s_front[i + 3] > 20 and s_back[i + 3] > 20:
            diff_3d += abs(s_front[i] - s_back[i]) + abs(s_front[i + 1] - s_back[i + 1]) + abs(s_front[i + 2] - s_back[i + 2])
            cnt_3d += 1
    z_variation_mae = diff_3d / max(1, cnt_3d * 3.0 * 255.0)

    # Check spherical boundary cleanliness at cube corners across all slices
    corner_coords = [(0, 0), (WIDTH - 1, 0), (0, HEIGHT - 1), (WIDTH - 1, HEIGHT - 1)]
    max_corner_alpha = 0
    for s in slices:
        for cx, cy in corner_coords:
            a = s[(cy * WIDTH + cx) * 4 + 3]
            if a > max_corner_alpha:
                max_corner_alpha = a

    # Inspect mid-volume slice (z=16) for clear upper cavity + emerald/sapphire/aqua distribution
    mid = slices[16]
    upper_dome_alpha_sum = 0
    upper_dome_cnt = 0
    for y in range(24, 72):
        for x in range(104, 152):
            upper_dome_alpha_sum += mid[(y * WIDTH + x) * 4 + 3]
            upper_dome_cnt += 1
    upper_dome_alpha_mean = upper_dome_alpha_sum / (upper_dome_cnt * 255.0)

    emerald_voxels = 0
    sapphire_voxels = 0
    aqua_voxels = 0
    total_fluid_voxels = 0
    for y in range(HEIGHT):
        for x in range(WIDTH):
            idx = (y * WIDTH + x) * 4
            r, g, b, a = mid[idx], mid[idx + 1], mid[idx + 2], mid[idx + 3]
            if a >= 30:
                total_fluid_voxels += 1
                if g > b * 1.15 and g > 70:
                    emerald_voxels += 1
                elif b >= g and g > 130 and b > 150:
                    aqua_voxels += 1
                elif b >= g and b < 150:
                    sapphire_voxels += 1

    print(f"[validate_volume_continuity] Max adjacent Z interior RGB MAE: {max_adj_interior_rgb_mae:.4f} (pair z{worst_pair[0]:02d}->z{worst_pair[1]:02d})")
    print(f"[validate_volume_continuity] Max adjacent Z premul RGB MAE:   {max_adj_premul_rgb_mae:.4f}")
    print(f"[validate_volume_continuity] Max adjacent Z-slice Alpha MAE:  {max_adj_alpha_mae:.4f}")
    print(f"[validate_volume_continuity] True 3D Z-depth variation MAE:   {z_variation_mae:.4f} (z08 vs z22)")
    print(f"[validate_volume_continuity] Max corner Alpha across 32 Z:    {max_corner_alpha}")
    print(f"[validate_volume_continuity] Upper glass cavity mean Alpha:   {upper_dome_alpha_mean:.4f}")
    print(
        f"[validate_volume_continuity] Mid-slice z16 fluid voxels={total_fluid_voxels} "
        f"(emerald={emerald_voxels}, sapphire={sapphire_voxels}, aqua={aqua_voxels})"
    )

    errors: list[str] = []
    if max_adj_interior_rgb_mae > 0.065:
        errors.append(f"Adjacent Z interior RGB MAE too high: {max_adj_interior_rgb_mae:.4f} > 0.065")
    if max_adj_premul_rgb_mae > 0.025:
        errors.append(f"Adjacent Z premul RGB MAE too high: {max_adj_premul_rgb_mae:.4f} > 0.025")
    if max_adj_alpha_mae > 0.045:
        errors.append(f"Adjacent Z Alpha MAE too high: {max_adj_alpha_mae:.4f} > 0.045")
    if z_variation_mae < 0.025:
        errors.append(f"Insufficient 3D variation along Z: {z_variation_mae:.4f} < 0.025")
    if max_corner_alpha != 0:
        errors.append(f"Non-zero corner alpha outside sphere: {max_corner_alpha}")
    if upper_dome_alpha_mean > 0.08:
        errors.append(f"Upper cavity not clear above meniscus: mean_alpha={upper_dome_alpha_mean:.4f}")
    if emerald_voxels < 4000 or sapphire_voxels < 1500 or aqua_voxels < 1500:
        errors.append(
            f"Palette balance off in z16: emerald={emerald_voxels}, sapphire={sapphire_voxels}, aqua={aqua_voxels}"
        )

    if errors:
        print("[validate_volume_continuity] FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1

    print("[validate_volume_continuity] PASS: 256x256x32 3D FluidVolume continuity & master composition verified.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
