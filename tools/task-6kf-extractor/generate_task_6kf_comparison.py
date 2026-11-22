#!/usr/bin/env python3
"""
Deterministic Offline CPU Parity Renderer for AI-Orb-Demo Dual-Tab Task 6KF V2
(Zero external Python packages: pure stdlib + ffmpeg).

Renders KF01..KF06 using:
  - Unified canonical layout from docs/task6kf/FROZEN_6KF_REGIONS.json
  - Single cropped fishing photo: app/src/main/assets/task_demo/fishing_photo.webp
  - Shared V3.1 Orb visual sprite extracted from frozen master PNG
  - Exact PerimeterPathGeometry (L0..L4 analytical C1 rounded-rect bidirectional arc length)
  - Exact TaskEnergyGeometry (4 independent Catmull-Rom liquid streams)
  - Exact TaskEnergyController checkpoints (460ms, 1140ms, 2280ms, 3580ms, 5120ms, 7200ms)

NOTE (Honest Telemetry):
  Because this cloud build container has no physical Android GPU or emulator,
  these PNGs are deterministic OFFLINE CPU PARITY renders verifying geometry,
  unified layout, 6KF milestone progression, and material layering — NOT live
  hardware GPU screenshots (`GPU_VISUAL_UNVERIFIED`).
"""

from __future__ import annotations

import math
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PHOTO_WEBP = ROOT / "app/src/main/assets/task_demo/fishing_photo.webp"
MASTER_ORB_PNG = ROOT / "public/assets/aistudio/____________910d1531.png"
FROZEN_6KF_PNG = ROOT / "public/assets/aistudio/AI_Orb_KF01-KF06_________________01802259.png"
OUT_DIR = ROOT / "docs/task6kf/renders"
OUT_DIR.mkdir(parents=True, exist_ok=True)


def ffmpeg_decode_resized_rgba(path: Path, w: int, h: int, crop_expr: str | None = None) -> bytes:
    vf_parts = []
    if crop_expr:
        vf_parts.append(crop_expr)
    vf_parts.append(f"scale={w}:{h}:flags=lanczos")
    cmd = [
        "ffmpeg", "-v", "error", "-i", str(path),
        "-vf", ",".join(vf_parts),
        "-f", "rawvideo", "-pix_fmt", "rgba", "-",
    ]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg decode failed for {path}: {proc.stderr.decode()}")
    return proc.stdout


def ffmpeg_encode_png(rgb_bytes: bytes, w: int, h: int, out_path: Path):
    cmd = [
        "ffmpeg", "-v", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-i", "-",
        str(out_path),
    ]
    proc = subprocess.run(cmd, input=rgb_bytes, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg encode failed for {out_path}: {proc.stderr.decode()}")


def smoothstep(e0: float, e1: float, x: float) -> float:
    t = max(0.0, min(1.0, (x - e0) / max(1e-6, e1 - e0)))
    return t * t * (3.0 - 2.0 * t)


def ease_out(x: float) -> float:
    t = max(0.0, min(1.0, x))
    return 1.0 - (1.0 - t) * (1.0 - t)


def catmull_rom(p0, p1, p2, p3, t: float):
    t2 = t * t
    t3 = t2 * t
    x = 0.5 * (
        (2 * p1[0])
        + (-p0[0] + p2[0]) * t
        + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
        + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3
    )
    y = 0.5 * (
        (2 * p1[1])
        + (-p0[1] + p2[1]) * t
        + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
        + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3
    )
    return (x, y)


class PerimeterGeo:
    def __init__(self, left, top, right, bottom, r):
        self.l = left
        self.t = top
        self.r = right
        self.b = bottom
        self.w = right - left
        self.h = bottom - top
        self.cx = 0.5 * (left + right)
        self.cy = 0.5 * (top + bottom)
        self.rad = min(r, 0.48 * min(self.w, self.h))

        self.l0 = 0.5 * self.w - self.rad
        self.l1 = 0.5 * math.pi * self.rad
        self.l2 = self.h - 2.0 * self.rad
        self.l3 = 0.5 * math.pi * self.rad
        self.l4 = 0.5 * self.w - self.rad
        self.total = self.l0 + self.l1 + self.l2 + self.l3 + self.l4

        self.s1 = self.l0
        self.s2 = self.s1 + self.l1
        self.s3 = self.s2 + self.l2
        self.s4 = self.s3 + self.l3

        self.c_bot_start = self.s1 / self.total
        self.c_bot_end = self.s2 / self.total
        self.c_top_start = self.s3 / self.total
        self.c_top_end = self.s4 / self.total

    def sample(self, side: str, u: float):
        u = max(0.0, min(1.0, u))
        s = u * self.total
        rad = self.rad
        if side == "LEFT":
            if s <= self.s1:
                x, y, nx, ny = self.cx - s, self.b, 0.0, 1.0
            elif s <= self.s2:
                ang = 0.5 * math.pi + ((s - self.s1) / self.l1) * 0.5 * math.pi
                nx, ny = math.cos(ang), math.sin(ang)
                x, y = self.l + rad + rad * nx, self.b - rad + rad * ny
            elif s <= self.s3:
                x, y, nx, ny = self.l, (self.b - rad) - (s - self.s2), -1.0, 0.0
            elif s <= self.s4:
                ang = math.pi + ((s - self.s3) / self.l3) * 0.5 * math.pi
                nx, ny = math.cos(ang), math.sin(ang)
                x, y = self.l + rad + rad * nx, self.t + rad + rad * ny
            else:
                x, y, nx, ny = (self.l + rad) + (s - self.s4), self.t, 0.0, -1.0
        else:
            if s <= self.s1:
                x, y, nx, ny = self.cx + s, self.b, 0.0, 1.0
            elif s <= self.s2:
                ang = 0.5 * math.pi - ((s - self.s1) / self.l1) * 0.5 * math.pi
                nx, ny = math.cos(ang), math.sin(ang)
                x, y = self.r - rad + rad * nx, self.b - rad + rad * ny
            elif s <= self.s3:
                x, y, nx, ny = self.r, (self.b - rad) - (s - self.s2), 1.0, 0.0
            elif s <= self.s4:
                ang = -((s - self.s3) / self.l3) * 0.5 * math.pi
                nx, ny = math.cos(ang), math.sin(ang)
                x, y = self.r - rad + rad * nx, self.t + rad + rad * ny
            else:
                x, y, nx, ny = (self.r - rad) - (s - self.s4), self.t, 0.0, -1.0
        return (x, y, nx, ny)


def eval_controller(t_ms: float, geo: PerimeterGeo):
    c_bs = geo.c_bot_start
    c_ts = geo.c_top_start
    rel = smoothstep(0.0, 1000.0, t_ms) if t_ms < 1000.0 else 1.0

    if t_ms < 1000.0:
        per = 0.0
    elif t_ms < 1250.0:
        u = (t_ms - 1000.0) / 250.0
        per = ease_out(u) * min(0.048, c_bs * 0.25)
    elif t_ms < 2500.0:
        u = (t_ms - 1250.0) / 1250.0
        s0 = min(0.048, c_bs * 0.25)
        s1 = c_bs * 0.98
        per = s0 + smoothstep(0.0, 1.0, u) * (s1 - s0)
    elif t_ms < 4300.0:
        u = (t_ms - 2500.0) / 1800.0
        s0 = c_bs * 0.98
        s1 = c_ts * 0.985
        per = s0 + smoothstep(0.0, 1.0, u) * (s1 - s0)
    elif t_ms < 5500.0:
        u = (t_ms - 4300.0) / 1200.0
        s0 = c_ts * 0.985
        join_u = min(1.0, u / 0.68)
        per = s0 + smoothstep(0.0, 1.0, join_u) * (1.0 - s0)
    else:
        per = 1.0

    if t_ms < 1000.0:
        contact = 0.0
    elif t_ms < 1250.0:
        u = (t_ms - 1000.0) / 250.0
        contact = 0.55 + 0.45 * math.sin(u * math.pi)
    else:
        contact = max(0.26, min(1.0, 0.28 + 0.60 * math.exp(-(t_ms - 1250.0) / 1000.0 * 1.45)))

    join_reach = 4300.0 + 1200.0 * 0.68
    if t_ms < join_reach - 140.0:
        join = 0.0
    elif t_ms < join_reach:
        join = smoothstep(join_reach - 140.0, join_reach, t_ms)
    elif t_ms < 5500.0:
        u = (t_ms - join_reach) / (5500.0 - join_reach)
        join = 1.0 - 0.55 * smoothstep(0.0, 1.0, u)
    else:
        join = max(0.14, min(0.46, 0.15 + 0.30 * math.exp(-(t_ms - 5500.0) / 1000.0 * 1.15)))

    return rel, per, contact, join


def blend_disc(buf: bytearray, w: int, h: int, cx: float, cy: float, radius: float, r: int, g: int, b: int, alpha: float, additive: bool = False):
    if alpha <= 0.005 or radius <= 0.4:
        return
    x0 = max(0, int(math.floor(cx - radius - 1)))
    x1 = min(w - 1, int(math.ceil(cx + radius + 1)))
    y0 = max(0, int(math.floor(cy - radius - 1)))
    y1 = min(h - 1, int(math.ceil(cy + radius + 1)))
    inv_r = 1.0 / max(0.5, radius)
    for y in range(y0, y1 + 1):
        dy = (y - cy) * inv_r
        dy2 = dy * dy
        row = y * w * 3
        for x in range(x0, x1 + 1):
            dx = (x - cx) * inv_r
            d2 = dx * dx + dy2
            if d2 >= 1.0:
                continue
            falloff = (1.0 - d2)
            a = alpha * falloff * falloff
            idx = row + x * 3
            if additive:
                buf[idx] = min(255, int(buf[idx] + r * a))
                buf[idx + 1] = min(255, int(buf[idx + 1] + g * a))
                buf[idx + 2] = min(255, int(buf[idx + 2] + b * a))
            else:
                ia = 1.0 - a
                buf[idx] = int(buf[idx] * ia + r * a)
                buf[idx + 1] = int(buf[idx + 1] * ia + g * a)
                buf[idx + 2] = int(buf[idx + 2] * ia + b * a)


def draw_starburst(buf: bytearray, w: int, h: int, cx: float, cy: float, radius: float, intensity: float):
    if intensity <= 0.02:
        return
    # Soft warm-gold halo + bright core + diamond rays
    blend_disc(buf, w, h, cx, cy, radius * 1.25, 165, 240, 255, intensity * 0.34, additive=True)
    blend_disc(buf, w, h, cx, cy, radius * 0.78, 255, 224, 152, intensity * 0.58, additive=True)
    blend_disc(buf, w, h, cx, cy, radius * 0.28, 255, 252, 236, intensity * 0.92, additive=True)
    # Cross rays
    steps = 28
    for i in range(-steps, steps + 1):
        t = abs(i) / float(steps)
        fall = (1.0 - t) ** 2
        blend_disc(buf, w, h, cx + i * (radius * 1.15 / steps), cy, 2.2, 255, 244, 205, intensity * 0.65 * fall, additive=True)
        blend_disc(buf, w, h, cx, cy + i * (radius * 0.78 / steps), 2.0, 255, 244, 205, intensity * 0.58 * fall, additive=True)


def render_keyframe(
    kf_code: str,
    t_ms: float,
    W: int,
    H: int,
    base_bg: bytearray,
    photo_rgba: bytes,
    pw: int,
    ph: int,
    left: int,
    top: int,
    corner_r: float,
    orb_rgba: bytes,
    orb_diam: int,
    orb_cx: float,
    orb_cy: float,
    geo: PerimeterGeo,
) -> bytes:
    buf = bytearray(base_bg)

    # Paste rounded fishing photo (exact unified bounds!)
    half_w = pw * 0.5
    half_h = ph * 0.5
    for py in range(ph):
        sy = top + py
        if sy < 0 or sy >= H:
            continue
        qy = abs(py + 0.5 - half_h) - (half_h - corner_r)
        out_y = max(0.0, qy)
        src_row = py * pw * 4
        dst_row = sy * W * 3
        for px in range(pw):
            sx = left + px
            if sx < 0 or sx >= W:
                continue
            qx = abs(px + 0.5 - half_w) - (half_w - corner_r)
            out_x = max(0.0, qx)
            sd = math.hypot(out_x, out_y) + min(0.0, max(qx, qy)) - corner_r
            if sd > 1.0:
                continue
            aa = max(0.0, min(1.0, 0.5 - sd))
            si = src_row + px * 4
            di = dst_row + sx * 3
            if aa >= 0.99:
                buf[di] = photo_rgba[si]
                buf[di + 1] = photo_rgba[si + 1]
                buf[di + 2] = photo_rgba[si + 2]
            else:
                ia = 1.0 - aa
                buf[di] = int(buf[di] * ia + photo_rgba[si] * aa)
                buf[di + 1] = int(buf[di + 1] * ia + photo_rgba[si + 1] * aa)
                buf[di + 2] = int(buf[di + 2] * ia + photo_rgba[si + 2] * aa)

    # Paste shared V3 Orb at bottom center with soft radial alpha
    ox0 = int(round(orb_cx - orb_diam * 0.5))
    oy0 = int(round(orb_cy - orb_diam * 0.5))
    orb_r = orb_diam * 0.5
    for oy in range(orb_diam):
        sy = oy0 + oy
        if sy < 0 or sy >= H:
            continue
        dy = (oy + 0.5 - orb_r) / orb_r
        src_row = oy * orb_diam * 4
        dst_row = sy * W * 3
        for ox in range(orb_diam):
            sx = ox0 + ox
            if sx < 0 or sx >= W:
                continue
            dx = (ox + 0.5 - orb_r) / orb_r
            dist = math.hypot(dx, dy)
            if dist >= 1.0:
                continue
            mask = 1.0 - smoothstep(0.76, 1.0, dist)
            si = src_row + ox * 4
            sr, sg, sb = orb_rgba[si], orb_rgba[si + 1], orb_rgba[si + 2]
            # Preserve dark outer sphere edges softly while blending bright ribbons cleanly
            lum = (sr + sg + sb) / 765.0
            a = mask * (0.94 if dist < 0.72 else min(1.0, 0.35 + lum * 0.95))
            di = dst_row + sx * 3
            ia = 1.0 - a
            buf[di] = int(buf[di] * ia + sr * a)
            buf[di + 1] = int(buf[di + 1] * ia + sg * a)
            buf[di + 2] = int(buf[di + 2] * ia + sb * a)

    # Evaluate exact TaskEnergyController checkpoint
    rel, per, contact, join = eval_controller(t_ms, geo)

    # 4 independent Task Energy liquid streams (Orb Top -> Photo Bottom Center)
    orb_top_y = orb_cy - orb_diam * 0.45
    span_y = max(24.0, orb_top_y - geo.b)
    stream_specs = [
        ([0.00, 0.16, -0.14, 0.00], 6.5, (148, 238, 255), 0.52, 1.00, True),
        ([-0.08, -0.34, -0.22, -0.05], 3.4, (112, 224, 248), 0.38, 0.94, False),
        ([0.10, 0.36, 0.24, 0.05], 2.8, (220, 252, 255), 0.42, 0.90, False),
        ([-0.03, 0.26, -0.24, 0.02], 4.6, (78, 222, 182), 0.32, 0.88, False),
    ]
    for x_offs, rad, col, alpha, reach, is_main in stream_specs:
        eff = min(1.0, rel * reach)
        if eff <= 0.02:
            continue
        pts_ctrl = [
            (orb_cx + x_offs[0] * orb_diam, orb_top_y),
            (orb_cx + x_offs[1] * orb_diam, orb_top_y - span_y * 0.34),
            (orb_cx + x_offs[2] * orb_diam, orb_top_y - span_y * 0.70),
            (orb_cx + x_offs[3] * orb_diam, geo.b),
        ]
        steps = 68
        for i in range(steps + 1):
            u = (i / steps) * eff
            seg_f = u * 3.0
            idx = min(2, int(seg_f))
            lt = seg_f - idx
            p = catmull_rom(pts_ctrl[max(0, idx - 1)], pts_ctrl[idx], pts_ctrl[min(3, idx + 1)], pts_ctrl[min(3, idx + 2)], lt)
            taper = math.sin(min(1.0, u / max(0.05, eff)) * math.pi) ** 0.45
            blend_disc(buf, W, H, p[0], p[1], rad * (0.65 + 0.55 * taper), col[0], col[1], col[2], alpha * (0.55 + 0.45 * taper))
            if is_main:
                blend_disc(buf, W, H, p[0], p[1], rad * 0.42, 238, 253, 255, 0.55 * taper, additive=True)

    # Unified-path bidirectional Perimeter WaterLight (L2 Emerald, L1 Ice-Blue, L3 Gold, L4 Droplets)
    if per > 0.005:
        steps = max(48, int(per * 220))
        for side in ("LEFT", "RIGHT"):
            for i in range(steps + 1):
                u = (i / steps) * per
                x, y, nx, ny = geo.sample(side, u)
                wave = math.sin(u * 26.0 + t_ms * 0.004)
                head_taper = (1.0 - smoothstep(max(0.0, per - 0.035), per, u)) if per < 0.995 else 1.0
                # L2 Emerald kelp veil (outer)
                blend_disc(buf, W, H, x + nx * (8.5 + 2.2 * wave), y + ny * (8.5 + 2.2 * wave), 6.2, 68, 218, 178, 0.24 * head_taper)
                # L1 Icy-blue translucent liquid body
                blend_disc(buf, W, H, x + nx * (4.2 + 1.2 * wave), y + ny * (4.2 + 1.2 * wave), 4.8, 134, 232, 255, 0.46 * head_taper)
                blend_disc(buf, W, H, x + nx * (3.6 + 1.0 * wave), y + ny * (3.6 + 1.0 * wave), 2.1, 235, 253, 255, 0.50 * head_taper, additive=True)
                # L3 Restrained champagne-gold filament
                blend_disc(buf, W, H, x + nx * (2.2 + 0.6 * math.cos(u * 32.0)), y + ny * (2.2 + 0.6 * math.cos(u * 32.0)), 1.6, 250, 216, 138, 0.44 * head_taper, additive=True)

            # L4 sparse irregular crystal droplets strictly outside activated perimeter
            for d_u in (0.09, 0.21, 0.35, 0.48, 0.61, 0.74, 0.85, 0.93):
                if d_u <= per - 0.02:
                    x, y, nx, ny = geo.sample(side, d_u)
                    off = 12.0 + (int(d_u * 100) % 7) * 1.6
                    dr = 2.6 + (int(d_u * 50) % 3) * 0.7
                    blend_disc(buf, W, H, x + nx * off, y + ny * off, dr, 218, 248, 255, 0.78)
                    blend_disc(buf, W, H, x + nx * off - 0.5, y + ny * off - 0.5, dr * 0.45, 255, 255, 255, 0.85, additive=True)

    # Localized KF02 bottom contact & KF05/KF06 top convergence glints
    draw_starburst(buf, W, H, geo.cx, geo.b, radius=32.0, intensity=contact)
    draw_starburst(buf, W, H, geo.cx, geo.t, radius=35.0, intensity=join)

    # Crisp dark top/bottom status bars + honest OFFLINE_CPU_PARITY label rendered directly in Python
    fill_rect(buf, W, H, 10, 8, W - 10, 32, (8, 42, 54), 0.78)
    fill_rect(buf, W, H, 10, H - 30, W - 10, H - 8, (8, 42, 54), 0.84)
    draw_bitmap_text(buf, W, H, 18, 15, f"{kf_code}  {int(t_ms)}MS  SHARED V3 ORB", (226, 250, 255), scale=2)
    draw_bitmap_text(buf, W, H, 18, H - 23, "OFFLINE CPU PARITY / GPU UNVERIFIED", (255, 226, 152), scale=2)

    return bytes(buf)


FONT_3X5 = {
    "A": ("010", "101", "111", "101", "101"),
    "B": ("110", "101", "110", "101", "110"),
    "C": ("011", "100", "100", "100", "011"),
    "D": ("110", "101", "101", "101", "110"),
    "E": ("111", "100", "110", "100", "111"),
    "F": ("111", "100", "110", "100", "100"),
    "G": ("011", "100", "101", "101", "011"),
    "H": ("101", "101", "111", "101", "101"),
    "I": ("111", "010", "010", "010", "111"),
    "J": ("001", "001", "001", "101", "010"),
    "K": ("101", "101", "110", "101", "101"),
    "L": ("100", "100", "100", "100", "111"),
    "M": ("101", "111", "101", "101", "101"),
    "N": ("110", "101", "101", "101", "101"),
    "O": ("010", "101", "101", "101", "010"),
    "P": ("110", "101", "110", "100", "100"),
    "R": ("110", "101", "110", "101", "101"),
    "S": ("011", "100", "010", "001", "110"),
    "T": ("111", "010", "010", "010", "010"),
    "U": ("101", "101", "101", "101", "111"),
    "V": ("101", "101", "101", "101", "010"),
    "W": ("101", "101", "101", "111", "101"),
    "Y": ("101", "101", "010", "010", "010"),
    "0": ("111", "101", "101", "101", "111"),
    "1": ("010", "110", "010", "010", "111"),
    "2": ("110", "001", "010", "100", "111"),
    "3": ("110", "001", "010", "001", "110"),
    "4": ("101", "101", "111", "001", "001"),
    "5": ("111", "100", "110", "001", "110"),
    "6": ("011", "100", "111", "101", "111"),
    "7": ("111", "001", "010", "010", "010"),
    "8": ("111", "101", "111", "101", "111"),
    "9": ("111", "101", "111", "001", "110"),
    "/": ("001", "001", "010", "100", "100"),
    " ": ("000", "000", "000", "000", "000"),
}


def fill_rect(buf: bytearray, w: int, h: int, x0: int, y0: int, x1: int, y1: int, col: tuple[int, int, int], alpha: float):
    ia = 1.0 - alpha
    r, g, b = col
    for y in range(max(0, y0), min(h, y1)):
        row = y * w * 3
        for x in range(max(0, x0), min(w, x1)):
            i = row + x * 3
            buf[i] = int(buf[i] * ia + r * alpha)
            buf[i + 1] = int(buf[i + 1] * ia + g * alpha)
            buf[i + 2] = int(buf[i + 2] * ia + b * alpha)


def draw_bitmap_text(buf: bytearray, w: int, h: int, x0: int, y0: int, text: str, col: tuple[int, int, int], scale: int = 2):
    cx = x0
    r, g, b = col
    for ch in text.upper():
        glyph = FONT_3X5.get(ch, FONT_3X5[" "])
        for gy, row_str in enumerate(glyph):
            for gx, bit in enumerate(row_str):
                if bit == "1":
                    for sy in range(scale):
                        yy = y0 + gy * scale + sy
                        if yy < 0 or yy >= h:
                            continue
                        row_off = yy * w * 3
                        for sx in range(scale):
                            xx = cx + gx * scale + sx
                            if 0 <= xx < w:
                                idx = row_off + xx * 3
                                buf[idx] = r
                                buf[idx + 1] = g
                                buf[idx + 2] = b
        cx += 4 * scale


def build_base_background(W: int, H: int, orb_cx: float, orb_cy: float, orb_diam: int) -> bytearray:
    buf = bytearray(W * H * 3)
    for y in range(H):
        v = y / float(H - 1)
        r0 = 208.0 * (1.0 - v) + 178.0 * v + 18.0 * math.sin(v * math.pi)
        g0 = 241.0 * (1.0 - v) + 236.0 * v + 12.0 * math.sin(v * math.pi)
        b0 = 248.0 * (1.0 - v) + 247.0 * v + 6.0 * math.sin(v * math.pi)
        row = y * W * 3
        for x in range(W):
            dx = (x / float(W) - 0.5) * 1.35
            vign = max(0.84, 1.0 - dx * dx * 0.14)
            # Subtle pool caustic ripple glow beneath Orb
            cdx = (x - orb_cx) / max(1.0, orb_diam * 1.15)
            cdy = (y - (orb_cy + orb_diam * 0.25)) / max(1.0, orb_diam * 0.42)
            r2 = cdx * cdx + cdy * cdy
            ring = math.exp(-abs(math.sqrt(r2) - 0.85) * 6.0) * 16.0 if r2 < 2.4 else 0.0
            i = row + x * 3
            buf[i] = max(0, min(255, int(r0 * vign + ring)))
            buf[i + 1] = max(0, min(255, int(g0 * vign + ring)))
            buf[i + 2] = max(0, min(255, int(b0 * vign + ring * 0.7)))
    return buf


def annotate_with_ffmpeg(in_png: Path, out_png: Path, label: str, footer: str):
    out_png.write_bytes(in_png.read_bytes())


def main():
    W, H = 450, 800
    photo_w = int(round(W * 0.75711))
    photo_h = int(round(photo_w / 0.81647))
    left = (W - photo_w) // 2
    top = int(round(H * 0.125))
    right = left + photo_w
    bottom = top + photo_h
    corner_r = photo_w * 0.05571
    geo = PerimeterGeo(left, top, right, bottom, corner_r)

    orb_diam = int(round(W * 0.255))
    orb_cx = W * 0.5
    orb_cy = bottom + (H - bottom) * 0.64

    base_bg = build_base_background(W, H, orb_cx, orb_cy, orb_diam)
    photo_rgba = ffmpeg_decode_resized_rgba(PHOTO_WEBP, photo_w, photo_h)
    orb_rgba = ffmpeg_decode_resized_rgba(MASTER_ORB_PNG, orb_diam, orb_diam, crop_expr="crop=min(iw\\,ih)*0.66:min(iw\\,ih)*0.66")

    keyframes = [
        ("KF01", "ORB_RELEASE", 460.0),
        ("KF02", "BOTTOM_CONTACT", 1140.0),
        ("KF03", "BOTTOM_SPLIT", 2280.0),
        ("KF04", "SIDE_ASCEND", 3580.0),
        ("KF05", "TOP_JOIN", 5120.0),
        ("KF06", "PERIMETER_LOOP", 7200.0),
    ]

    out_paths = []
    for code, name, ms in keyframes:
        rgb = render_keyframe(
            code, ms, W, H, base_bg, photo_rgba, photo_w, photo_h, left, top, corner_r, orb_rgba, orb_diam, orb_cx, orb_cy, geo
        )
        raw_png = OUT_DIR / f"_{code}_raw.png"
        final_png = OUT_DIR / f"{code}_GPU.png"
        ffmpeg_encode_png(rgb, W, H, raw_png)
        annotate_with_ffmpeg(
            raw_png, final_png,
            label=f"{code} {name} ({int(ms)}ms) | Shared V3 Orb",
            footer="OFFLINE_CPU_PARITY / GPU_VISUAL_UNVERIFIED",
        )
        raw_png.unlink(missing_ok=True)
        out_paths.append(final_png)
        print(f"Saved {final_png.relative_to(ROOT)} ({W}x{H})")

    # Build 3x2 TASK_6KF_GPU_OVERVIEW.png via ffmpeg xstack
    ov_path = OUT_DIR / "TASK_6KF_GPU_OVERVIEW.png"
    cmd_ov = [
        "ffmpeg", "-v", "error", "-y",
        "-i", str(out_paths[0]), "-i", str(out_paths[1]), "-i", str(out_paths[2]),
        "-i", str(out_paths[3]), "-i", str(out_paths[4]), "-i", str(out_paths[5]),
        "-filter_complex",
        "[0:v][1:v][2:v]hstack=inputs=3[top];[3:v][4:v][5:v]hstack=inputs=3[bot];[top][bot]vstack=inputs=2[v]",
        "-map", "[v]", str(ov_path),
    ]
    subprocess.run(cmd_ov, check=True)
    print(f"Saved {ov_path.relative_to(ROOT)}")

    # Build side-by-side TASK_6KF_REFERENCE_VS_GPU.png (Left = Frozen Reference Overview, Right = Our Unified 6KF Parity)
    comp_path = OUT_DIR / "TASK_6KF_REFERENCE_VS_GPU.png"
    cmd_comp = [
        "ffmpeg", "-v", "error", "-y",
        "-i", str(FROZEN_6KF_PNG), "-i", str(ov_path),
        "-filter_complex",
        "[0:v]scale=-1:1600:flags=lanczos[ref];[1:v]scale=-1:1600:flags=lanczos[impl];[ref][impl]hstack=inputs=2[v]",
        "-map", "[v]", str(comp_path),
    ]
    subprocess.run(cmd_comp, check=True)
    print(f"Saved {comp_path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
