#!/usr/bin/env python3
"""
AI-Orb-Demo V3 — Reference-Driven 2D Optical & Fluid Detail Maps Synthesizer (Lossless RGBA WebP)

Explicitly loads pixel-extracted master geometry & color metrics from:
  tools/orb-v3-generator/reference/REFERENCE_VISUAL_REGIONS.json

Generates the 3 production 2D Lossless RGBA WebP maps consumed directly by CORE_GLASS_FRAG:
  1. maps/emerald_distribution.webp (256x256, Linear Data GL_RGBA8)
     - R: Organic emerald silk fold micro-curtain modulation
     - G: High-frequency translucent silk grain
     - B: Capillary meniscus organic ripple heightfield
     - A: Beer-Lambert absorption contrast modulation
  2. optics/caustics.webp (256x256, Linear Data GL_RGBA8)
     - Razor-sharp seamless domain-warped irregular underwater caustic filaments (ZERO Voronoi!)
     - R/G/B: Multi-scale branching wave filaments
     - A: Crisp optical convergence nodes
  3. optics/glass_environment.webp (512x256, sRGB Equirectangular GL_SRGB8_ALPHA8)
     - Calibrated to extracted master background & E09 upper-left flare anchor:
       * Deep dark abyssal crystal surround (ZERO milky haze in sphere interior!)
       * Grazing rim-hugging sunlit golden crescent at 10:45-3:30 (NO circular white front headlight dot!)
       * Crisp ice-cyan grazing crescent along 4:30-8:30
"""

from __future__ import annotations

import json
import math
import random
import subprocess
import sys
import time
from pathlib import Path

SEED = 20261120
ROOT = Path(__file__).resolve().parents[2]
ASSETS_ROOT = ROOT / "app" / "src" / "main" / "assets" / "orb_v3"
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
FLARE_CENTROID = REF_DATA["interiorRegions"]["E09_upper_left_flare"]["centroidNorm"]
FLARE_SRGB = REF_DATA["interiorRegions"]["E09_upper_left_flare"]["meanSrgb"]
BG_CORNER_SRGB = REF_DATA["backgroundCornerMeanSrgb"]


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

    # Calibrate absorption contrast against master E05 vs E06 area balance
    e05_area = float(REF_DATA["interiorRegions"]["E05_emerald"]["areaRatio"])
    e06_area = float(REF_DATA["interiorRegions"]["E06_sapphire"]["areaRatio"])
    contrast_bias = clamp01(e05_area / max(0.15, e05_area + e06_area))

    for y in range(h):
        v = (y + 0.5) / h
        for x in range(w):
            u = (x + 0.5) / w
            wx = fbm_periodic_2d(g1, 12, g2, 24, u, v)
            wy = fbm_periodic_2d(g1, 12, g2, 24, u + 0.37, v + 0.61)

            # R: Sharper sculpted silk curtain folds
            curtain_raw = 0.5 + 0.5 * math.sin((u * 2.0 + wx * 0.45) * tau + math.cos((v * 2.0 + wy * 0.40) * tau))
            curtain = smoothstep(0.16, 0.86, curtain_raw * 0.74 + (0.5 + 0.5 * wx) * 0.26)

            # G: Crisp translucent silk micro-grain
            grain = clamp01(0.5 + 0.5 * sample_periodic_2d(g3, 32, u + wx * 0.08, v + wy * 0.08))

            # B: Capillary meniscus organic ripple heightfield
            ripple = clamp01(
                0.5
                + 0.26 * math.sin((u * 3.0 + v * 1.0 + wx * 0.22) * tau)
                + 0.16 * math.cos((v * 4.0 - u * 2.0 + wy * 0.18) * tau)
                + 0.08 * sample_periodic_2d(g2, 24, u * 2.0, v * 2.0)
            )

            # A: High-contrast Beer-Lambert absorption modulation
            absorb = clamp01((0.32 + 0.14 * contrast_bias) + 0.60 * smoothstep(-0.48, 0.58, wx * 0.6 + wy * 0.4))

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
            wa = fbm_periodic_2d(g1, 10, g2, 20, u, v)
            wb = fbm_periodic_2d(g1, 10, g2, 20, u + 0.43, v + 0.29)
            wc = fbm_periodic_2d(g1, 10, g2, 20, u + wa * 0.16 + 0.19, v + wb * 0.16 + 0.67)

            # Razor-sharp curved branching filaments (zero Voronoi honeycomb!)
            p1x = (u * 3.0 + wa * 0.40 + wc * 0.15) * tau
            p1y = (v * 3.0 + wb * 0.40 - wc * 0.13) * tau
            w1 = sin(p1x + 0.88 * cos(p1y + wa * 1.95)) + cos(p1y - 0.82 * sin(p1x - wb * 1.75))
            r1 = exp(-abs(w1) * 5.2)

            p2x = (u * 2.0 - v * 3.0 + wb * 0.44) * tau
            p2y = (u * 3.0 + v * 2.0 - wa * 0.42) * tau
            w2 = sin(p2x - 0.80 * sin(p2y + wc * 2.15)) + cos(p2y + 0.78 * cos(p2x - wa * 1.65))
            r2 = exp(-abs(w2) * 5.8)

            p3x = (u * 4.0 + v * 2.0 + wc * 0.36) * tau
            p3y = (-u * 2.0 + v * 5.0 + wa * 0.34) * tau
            w3 = sin(p3x + 0.72 * cos(p3y)) - cos(p3y + 0.72 * sin(p3x))
            r3 = exp(-abs(w3) * 6.5)

            breakup = smoothstep(-0.30, 0.56, wa * 0.62 + wb * 0.52)
            conv = clamp01((r1 * r2 * 3.2) ** 1.30)

            c_r = clamp01(r1 * (0.42 + 0.58 * breakup))
            c_g = clamp01(r2 * (0.42 + 0.58 * breakup))
            c_b = clamp01(r3 * (0.38 + 0.62 * breakup))
            c_a = clamp01(0.04 + 0.96 * clamp01(conv * 0.88 + r1 * r2 * 0.35))

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
    Eliminates the circular front-facing 10:45 headlight dot completely!
    Places sunlit golden & ice-cyan reflections strictly along grazing curvature arcs
    matching the master's blown-glass double rim.
    """
    w, h = 512, 256
    buf = bytearray(w * h * 4)
    off = 0
    pi = math.pi
    sin = math.sin
    cos = math.cos
    exp = math.exp

    # Grazing upper-left golden rim arc direction derived from E09_upper_left_flare centroid
    # Pushed to grazing curvature (rz <= 0.05) so it reflects ONLY near the upper-left glass rim!
    fx = float(FLARE_CENTROID[0])
    fy = float(FLARE_CENTROID[1])
    flen = math.sqrt(fx * fx + fy * fy + 0.12 * 0.12)
    gx, gy, gz = fx / flen, fy / flen, -0.12 / flen

    # Calibrate dark studio floor to master corner background (linearized)
    bg_r = (BG_CORNER_SRGB[0] ** 2.2) * 0.35
    bg_g = (BG_CORNER_SRGB[1] ** 2.2) * 0.35
    bg_b = (BG_CORNER_SRGB[2] ** 2.2) * 0.35

    flare_tint_r = clamp01(FLARE_SRGB[0]) ** 2.2
    flare_tint_g = clamp01(FLARE_SRGB[1]) ** 2.2
    flare_tint_b = clamp01(FLARE_SRGB[2]) ** 2.2

    for row in range(h):
        v = (row + 0.5) / h
        theta = (0.5 - v) * pi
        ct = cos(theta)
        ry = sin(theta)

        for col in range(w):
            u = (col + 0.5) / w
            phi = (u - 0.5) * 2.0 * pi
            rx = ct * sin(phi)
            rz = ct * cos(phi)

            # Deep dark abyssal teal studio surround — crystal-clear glass center!
            lr = 0.0014 + bg_r
            lg = 0.0110 + bg_g
            lb = 0.0165 + bg_b

            # Suppress front-center (+Z) reflections so sphere center never looks milky or dotted
            grazing_only = 1.0 - smoothstep(0.28, 0.78, rz)

            # 1. Rim-hugging sunlit golden crescent at upper-left (10:45) — elongated thin arc, NO circular dot!
            dot_g = rx * gx + ry * gy + rz * gz
            # Narrow ribbon cross-section along upper-left rim contour
            arc_ribbon = exp(-((ry - 0.62 - 0.28 * (-rx)) * 11.5) ** 2) * smoothstep(0.62, 0.96, dot_g) * grazing_only
            lr += flare_tint_r * arc_ribbon * 0.95
            lg += flare_tint_g * arc_ribbon * 0.78
            lb += flare_tint_b * arc_ribbon * 0.42

            # 2. Razor-sharp molten-gold rim band along right curvature (12:30 - 3:30)
            gold_arc = exp(-((ry - 0.22 * rx - 0.14) * 16.5) ** 2) * smoothstep(0.54, 0.94, rx) * grazing_only
            lr += 0.98 * gold_arc * 0.78
            lg += 0.56 * gold_arc * 0.78
            lb += 0.12 * gold_arc * 0.78

            # 3. Crisp ice-cyan crescent reflection along lower-left / bottom glass rim (4:30 - 8:30)
            cyan_arc = exp(-((ry + 0.38 + 0.18 * rx) * 10.0) ** 2) * (1.0 - smoothstep(-0.85, 0.12, rx)) * grazing_only
            lr += 0.12 * cyan_arc * 0.72
            lg += 0.84 * cyan_arc * 0.72
            lb += 1.00 * cyan_arc * 0.72

            # 4. Subtle cool overhead crystal crown hairline
            top_line = exp(-((ry - 0.80) * 20.0) ** 2) * grazing_only * 0.16
            lr += 0.30 * top_line
            lg += 0.65 * top_line
            lb += 0.72 * top_line

            sr = clamp01(lr) ** (1.0 / 2.2)
            sg = clamp01(lg) ** (1.0 / 2.2)
            sb = clamp01(lb) ** (1.0 / 2.2)
            sa = clamp01(0.22 + 0.78 * max(sr, sg, sb))

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
    print(f"[generate_orb_maps] Loading reference regions from {REGIONS_PATH.name}...")
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
