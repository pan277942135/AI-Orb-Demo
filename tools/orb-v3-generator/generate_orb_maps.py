#!/usr/bin/env python3
"""
AI-Orb-Demo V3 — 2D Optical & Fluid Detail Maps Synthesizer (Lossless RGBA WebP)

Deterministically generates the 3 production 2D Lossless RGBA WebP maps consumed
directly by CORE_GLASS_FRAG in OpenGL ES 3.0:
  1. maps/emerald_distribution.webp (256x256, Linear Data GL_RGBA8)
     - R: Seamless organic emerald silk curtain modulation
     - G: High-frequency translucent fluid silk micro-grain
     - B: Capillary meniscus organic ripple heightfield
     - A: Beer-Lambert volumetric absorption modulation
  2. optics/caustics.webp (256x256, Linear Data GL_RGBA8)
     - Seamless tileable 2D domain-warped irregular curved-wave underwater caustics (ZERO Voronoi!)
     - R: Primary curved caustic filaments
     - G: Secondary irrational-angle curved filaments
     - B: Fine shimmer filaments
     - A: Bright optical convergence nodes
  3. optics/glass_environment.webp (512x256, sRGB Equirectangular GL_SRGB8_ALPHA8)
     - Calibrated to sole frozen master public/assets/aistudio/____________910d1531.png:
       * Deep dark-teal studio crystal surround (crystal-clear glass center)
       * Tight sunlit golden flare at upper-left (10:45)
       * Molten-gold rim arc (12:30 - 3:30)
       * Crisp ice-cyan crescent rim (4:30 - 8:30)
       * ZERO broad front-right round beige blob!
"""

from __future__ import annotations

import math
import random
import subprocess
import sys
import time
from pathlib import Path

SEED = 20261120
ROOT = Path(__file__).resolve().parents[2]
ASSETS_ROOT = ROOT / "app" / "src" / "main" / "assets" / "orb_v3"


def clamp01(v: float) -> float:
    return 0.0 if v <= 0.0 else (1.0 if v >= 1.0 else v)


def smoothstep(e0: float, e1: float, x: float) -> float:
    t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3.0 - 2.0 * t)


def build_grid_2d(n: int, seed: int) -> list[float]:
    rng = random.Random(seed)
    return [rng.uniform(-1.0, 1.0) for _ in range(n * n)]


def sample_periodic_2d(grid: list[float], n: int, u: float, v: float) -> float:
    fx = (u * n) % n
    fy = (v * n) % n
    ix0 = int(fx)
    iy0 = int(fy)
    tx = fx - ix0
    ty = fy - iy0
    ux = tx * tx * tx * (tx * (tx * 6.0 - 15.0) + 10.0)
    uy = ty * ty * ty * (ty * (ty * 6.0 - 15.0) + 10.0)
    ix1 = (ix0 + 1) % n
    iy1 = (iy0 + 1) % n

    c00 = grid[iy0 * n + ix0]
    c10 = grid[iy0 * n + ix1]
    c01 = grid[iy1 * n + ix0]
    c11 = grid[iy1 * n + ix1]
    x0 = c00 + (c10 - c00) * ux
    x1 = c01 + (c11 - c01) * ux
    return x0 + (x1 - x0) * uy


def fbm_periodic_2d(g1: list[float], n1: int, g2: list[float], n2: int, u: float, v: float) -> float:
    a = sample_periodic_2d(g1, n1, u, v)
    b = sample_periodic_2d(g2, n2, u * 2.0 + 0.17, v * 2.0 + 0.31)
    c = sample_periodic_2d(g2, n2, u * 4.0 + 0.53, v * 4.0 + 0.71)
    return 0.56 * a + 0.30 * b + 0.14 * c


def encode_lossless_webp(rgba: bytes, width: int, height: int, out_path: Path) -> int:
    out_path.parent.mkdir(parents=True, exist_ok=True)
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
        f"{width}x{height}",
        "-i",
        "-",
        "-c:v",
        "libwebp",
        "-lossless",
        "1",
        "-compression_level",
        "5",
        str(out_path),
    ]
    proc = subprocess.run(cmd, input=rgba, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg failed for {out_path}: {proc.stderr.decode()}")
    return out_path.stat().st_size


def generate_emerald_distribution() -> tuple[str, int]:
    w = h = 256
    g1 = build_grid_2d(12, SEED + 401)
    g2 = build_grid_2d(24, SEED + 402)
    g3 = build_grid_2d(32, SEED + 403)
    buf = bytearray(w * h * 4)
    off = 0
    tau = 2.0 * math.pi

    for y in range(h):
        v = (y + 0.5) / h
        for x in range(w):
            u = (x + 0.5) / w
            # Domain-warped seamless coordinates
            wx = fbm_periodic_2d(g1, 12, g2, 24, u, v)
            wy = fbm_periodic_2d(g1, 12, g2, 24, u + 0.37, v + 0.61)

            # R: Organic emerald silk curtain folds
            curtain_raw = 0.5 + 0.5 * math.sin((u * 2.0 + wx * 0.42) * tau + math.cos((v * 2.0 + wy * 0.38) * tau))
            curtain = smoothstep(0.12, 0.88, curtain_raw * 0.72 + (0.5 + 0.5 * wx) * 0.28)

            # G: Fine silk micro-grain
            grain = clamp01(0.5 + 0.5 * sample_periodic_2d(g3, 32, u + wx * 0.08, v + wy * 0.08))

            # B: Capillary meniscus ripple heightfield
            ripple = clamp01(
                0.5
                + 0.26 * math.sin((u * 3.0 + v * 1.0 + wx * 0.22) * tau)
                + 0.16 * math.cos((v * 4.0 - u * 2.0 + wy * 0.18) * tau)
                + 0.08 * sample_periodic_2d(g2, 24, u * 2.0, v * 2.0)
            )

            # A: Absorption modulation (always non-zero [0.35..1.0])
            absorb = clamp01(0.38 + 0.62 * smoothstep(-0.55, 0.65, wx * 0.6 + wy * 0.4))

            buf[off] = int(curtain * 255.0 + 0.5)
            buf[off + 1] = int(grain * 255.0 + 0.5)
            buf[off + 2] = int(ripple * 255.0 + 0.5)
            buf[off + 3] = int(absorb * 255.0 + 0.5)
            off += 4

    out_path = ASSETS_ROOT / "maps" / "emerald_distribution.webp"
    sz = encode_lossless_webp(bytes(buf), w, h, out_path)
    return str(out_path.relative_to(ASSETS_ROOT)), sz


def generate_caustics_map() -> tuple[str, int]:
    w = h = 256
    g1 = build_grid_2d(10, SEED + 501)
    g2 = build_grid_2d(20, SEED + 502)
    buf = bytearray(w * h * 4)
    off = 0
    tau = 2.0 * math.pi
    sin = math.sin
    cos = math.cos
    exp = math.exp

    for y in range(h):
        v = (y + 0.5) / h
        for x in range(w):
            u = (x + 0.5) / w
            # 2-stage periodic domain warp (completely seamless on [0,1]^2, ZERO Voronoi cells!)
            wa = fbm_periodic_2d(g1, 10, g2, 20, u, v)
            wb = fbm_periodic_2d(g1, 10, g2, 20, u + 0.43, v + 0.29)
            wc = fbm_periodic_2d(g1, 10, g2, 20, u + wa * 0.16 + 0.19, v + wb * 0.16 + 0.67)

            # Primary irregular curved wave network
            p1x = (u * 3.0 + wa * 0.38 + wc * 0.14) * tau
            p1y = (v * 3.0 + wb * 0.38 - wc * 0.12) * tau
            w1 = sin(p1x + 0.85 * cos(p1y + wa * 1.9)) + cos(p1y - 0.80 * sin(p1x - wb * 1.7))
            r1 = exp(-abs(w1) * 4.4)

            # Secondary rotated integer-periodic branching network (strictly tileable on torus!)
            p2x = (u * 2.0 - v * 3.0 + wb * 0.42) * tau
            p2y = (u * 3.0 + v * 2.0 - wa * 0.40) * tau
            w2 = sin(p2x - 0.78 * sin(p2y + wc * 2.1)) + cos(p2y + 0.76 * cos(p2x - wa * 1.6))
            r2 = exp(-abs(w2) * 5.0)

            # Tertiary fine organic filaments
            p3x = (u * 4.0 + v * 2.0 + wc * 0.35) * tau
            p3y = (-u * 2.0 + v * 5.0 + wa * 0.32) * tau
            w3 = sin(p3x + 0.70 * cos(p3y)) - cos(p3y + 0.70 * sin(p3x))
            r3 = exp(-abs(w3) * 5.8)

            # Non-uniform organic intensity breakup + convergence sparkles
            breakup = smoothstep(-0.35, 0.58, wa * 0.62 + wb * 0.52)
            conv = clamp01((r1 * r2 * 2.8) ** 1.25)

            c_r = clamp01(r1 * (0.45 + 0.55 * breakup))
            c_g = clamp01(r2 * (0.45 + 0.55 * breakup))
            c_b = clamp01(r3 * (0.40 + 0.60 * breakup))
            c_a = clamp01(0.08 + 0.92 * clamp01(conv * 0.85 + r1 * r2 * 0.35))

            buf[off] = int(c_r * 255.0 + 0.5)
            buf[off + 1] = int(c_g * 255.0 + 0.5)
            buf[off + 2] = int(c_b * 255.0 + 0.5)
            buf[off + 3] = int(c_a * 255.0 + 0.5)
            off += 4

    out_path = ASSETS_ROOT / "optics" / "caustics.webp"
    sz = encode_lossless_webp(bytes(buf), w, h, out_path)
    return str(out_path.relative_to(ASSETS_ROOT)), sz


def generate_glass_environment() -> tuple[str, int]:
    """
    Equirectangular studio crystal reflection map (512x256, sRGB).
    u in [0,1] -> azimuth phi in [-pi, +pi] (u=0.5 -> +Z front camera direction)
    v in [0,1] -> elevation theta in [+pi/2 (top), -pi/2 (bottom)]
    Calibrated strictly to ____________910d1531.png:
      - Dark abyssal teal surround (ensures clear center with zero milky veil)
      - Tight sunlit champagne-gold key window at 10:45 upper-left
      - Crisp thin molten-gold rim band along right curvature (12:30 - 3:30)
      - Crisp ice-cyan crescent along lower-left curvature (4:30 - 8:30)
      - ZERO broad front-right round blob!
    """
    w, h = 512, 256
    buf = bytearray(w * h * 4)
    off = 0
    pi = math.pi
    sin = math.sin
    cos = math.cos
    exp = math.exp

    # Key light vector matching 10:45 upper-left flare in master PNG
    kx, ky, kz = -0.52, 0.72, 0.46
    klen = math.sqrt(kx * kx + ky * ky + kz * kz)
    kx, ky, kz = kx / klen, ky / klen, kz / klen

    for row in range(h):
        v = (row + 0.5) / h
        theta = (0.5 - v) * pi  # +pi/2 at top, -pi/2 at bottom
        ct = cos(theta)
        ry = sin(theta)

        for col in range(w):
            u = (col + 0.5) / w
            phi = (u - 0.5) * 2.0 * pi  # 0 at +Z, +pi/2 at +X, -pi/2 at -X
            rx = ct * sin(phi)
            rz = ct * cos(phi)

            # Deep dark abyssal teal studio base (linear -> converted to sRGB at end)
            lr = 0.0022
            lg = 0.0155
            lb = 0.0220

            # Subtle deep emerald zenith & cyan nadir ambient bounce
            zenith = clamp01(ry)
            nadir = clamp01(-ry)
            lr += 0.003 * zenith + 0.004 * nadir
            lg += 0.016 * zenith + 0.036 * nadir
            lb += 0.018 * zenith + 0.048 * nadir

            # 1. Focused sunlit champagne-gold key window at 10:45 (tight studio softbox, NO wash!)
            dot_key = rx * kx + ry * ky + rz * kz
            key_core = smoothstep(0.955, 0.996, dot_key)
            key_halo = smoothstep(0.895, 0.985, dot_key) * 0.28
            key_e = key_core * 1.10 + key_halo
            lr += 1.00 * key_e
            lg += 0.80 * key_e
            lb += 0.42 * key_e

            # 2. Razor-sharp molten-gold arc band along grazing right curvature (12:30 - 3:30)
            # Only active at high +X grazing angles (rx > 0.55) so NEVER creates a front-right blob!
            gold_arc = exp(-((ry - 0.22 * rx - 0.14) * 16.0) ** 2) * smoothstep(0.56, 0.94, rx) * smoothstep(-0.35, 0.45, -rz + 0.4)
            lr += 0.98 * gold_arc * 0.72
            lg += 0.58 * gold_arc * 0.72
            lb += 0.14 * gold_arc * 0.72

            # 3. Crisp ice-cyan crescent reflection along lower-left / bottom glass rim (4:30 - 8:30)
            cyan_arc = exp(-((ry + 0.36 + 0.18 * rx) * 9.5) ** 2) * (1.0 - smoothstep(-0.85, 0.08, rx))
            lr += 0.14 * cyan_arc * 0.68
            lg += 0.82 * cyan_arc * 0.68
            lb += 1.00 * cyan_arc * 0.68

            # 4. Subtle cool studio overhead hairline glint
            top_line = exp(-((ry - 0.78) * 18.0) ** 2) * smoothstep(-0.4, 0.3, -rx) * 0.18
            lr += 0.35 * top_line
            lg += 0.68 * top_line
            lb += 0.72 * top_line

            # Convert linear radiance [0..1] to sRGB for GL_SRGB8_ALPHA8 precision!
            sr = clamp01(lr) ** (1.0 / 2.2)
            sg = clamp01(lg) ** (1.0 / 2.2)
            sb = clamp01(lb) ** (1.0 / 2.2)
            # Alpha encodes specular intensity weight for diagnostics/blending
            sa = clamp01(0.25 + 0.75 * max(sr, sg, sb))

            buf[off] = int(sr * 255.0 + 0.5)
            buf[off + 1] = int(sg * 255.0 + 0.5)
            buf[off + 2] = int(sb * 255.0 + 0.5)
            buf[off + 3] = int(sa * 255.0 + 0.5)
            off += 4

    out_path = ASSETS_ROOT / "optics" / "glass_environment.webp"
    sz = encode_lossless_webp(bytes(buf), w, h, out_path)
    return str(out_path.relative_to(ASSETS_ROOT)), sz


def main() -> int:
    t0 = time.time()
    r1 = generate_emerald_distribution()
    r2 = generate_caustics_map()
    r3 = generate_glass_environment()
    elapsed = time.time() - t0
    for rel, sz in (r1, r2, r3):
        print(f"[generate_orb_maps] Generated {rel} ({sz / 1024.0:.1f} KiB)")
    print(f"[generate_orb_maps] DONE in {elapsed:.2f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
