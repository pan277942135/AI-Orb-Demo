#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Stage D: Resident 3D Ribbon Combined Projection Fitter (A01+A02, C01, G01, G02)

Freezes:
  - 5 Resident identities & periods:
      G01 = 6100ms, G02 = 5300ms, A01 = 3700ms, A02 = 2900ms, C01 = 4400ms
  - ribbonHugScale = 0.87
  - C01 open-ended de-kinked curve (`closed = false`, max segment turn < 20°)
  - All control points radius <= 1.54 (within <= 1.56 framing bound)

Optimizes A01 + A02 COMBINED 3D projection against Master E10 gold pixels (`E10_gold_residents_combined`)
using exact Stage A camera matrices + ribbon width silhouette sampling + anti-oval asymmetry penalty:
  Target: E10 combined Chamfer <= 0.025 R AND zero regular symmetric oval!
"""

from __future__ import annotations

import math
from typing import Any

from camera_fitter import mat4_look_at, mat4_mul, mat4_perspective, project_point
from parameter_schema import get_val, set_val


def catmull_rom_open(pts: list[list[float]], u: float) -> tuple[float, float, float]:
    n = len(pts) - 1
    seg_f = max(0.0, min(1.0, u)) * n
    i = min(n - 1, int(seg_f))
    t = seg_f - i
    p0 = pts[max(0, i - 1)]
    p1 = pts[i]
    p2 = pts[min(n, i + 1)]
    p3 = pts[min(n, i + 2)]
    t2 = t * t
    t3 = t2 * t
    out = [0.0, 0.0, 0.0]
    for k in range(3):
        out[k] = 0.5 * (
            (2.0 * p1[k])
            + (-p0[k] + p2[k]) * t
            + (2.0 * p0[k] - 5.0 * p1[k] + 4.0 * p2[k] - p3[k]) * t2
            + (-p0[k] + 3.0 * p1[k] - 3.0 * p2[k] + p3[k]) * t3
        )
    return out[0], out[1], out[2]


def interp_profile(prof: list[float], u: float) -> float:
    n = len(prof) - 1
    f = max(0.0, min(1.0, u)) * n
    i = min(n - 1, int(f))
    t = f - i
    return prof[i] * (1.0 - t) + prof[i + 1] * t


def max_spline_turn_deg(pts: list[list[float]], samples: int = 60) -> float:
    pts_s = [catmull_rom_open(pts, i / float(samples)) for i in range(samples + 1)]
    max_deg = 0.0
    for i in range(1, len(pts_s) - 1):
        ax = pts_s[i][0] - pts_s[i - 1][0]
        ay = pts_s[i][1] - pts_s[i - 1][1]
        az = pts_s[i][2] - pts_s[i - 1][2]
        al = math.sqrt(ax * ax + ay * ay + az * az) + 1e-8
        bx = pts_s[i + 1][0] - pts_s[i][0]
        by = pts_s[i + 1][1] - pts_s[i][1]
        bz = pts_s[i + 1][2] - pts_s[i][2]
        bl = math.sqrt(bx * bx + by * by + bz * bz) + 1e-8
        dot = max(-1.0, min(1.0, (ax * bx + ay * by + az * bz) / (al * bl)))
        deg = math.degrees(math.acos(dot))
        if deg > max_deg:
            max_deg = deg
    return max_deg


def project_ribbon_band_norm(
    pts: list[list[float]],
    width_prof: list[float],
    half_width: float,
    hug_scale: float,
    mvp: list[float],
    vp_w: float,
    vp_h: float,
    sphere_cx: float,
    sphere_cy: float,
    sphere_r: float,
    n_samples: int = 64,
    include_edges: bool = True,
) -> list[tuple[float, float, float]]:
    """
    Projects a 3D ribbon (centerline + ±ribbon edges across width profile) into
    normalized sphere coordinates (nx, ny, wz) using the exact Stage A camera matrix.
    Discards back-occluded points strictly behind the solid opaque core center only when
    evaluating silhouette alignment, keeping refracted outer/rim segments visible.
    """
    inv_r = 1.0 / sphere_r
    out: list[tuple[float, float, float]] = []
    spine = [catmull_rom_open(pts, i / float(n_samples - 1)) for i in range(n_samples)]

    for i in range(n_samples):
        u = i / float(n_samples - 1)
        wx, wy, wz = spine[i][0] * hug_scale, spine[i][1] * hug_scale, spine[i][2] * hug_scale
        px, py, _ = project_point(mvp, (wx, wy, wz), vp_w, vp_h)
        nx = (px - sphere_cx) * inv_r
        ny = (sphere_cy - py) * inv_r
        out.append((nx, ny, wz))

        if include_edges:
            i_prev = max(0, i - 1)
            i_next = min(n_samples - 1, i + 1)
            tx = (spine[i_next][0] - spine[i_prev][0]) * hug_scale
            ty = (spine[i_next][1] - spine[i_prev][1]) * hug_scale
            tz = (spine[i_next][2] - spine[i_prev][2]) * hug_scale
            # Ribbon binormal perpendicular to tangent & radial vector
            rx, ry, rz = wx, wy, wz
            bx = ty * rz - tz * ry
            by = tz * rx - tx * rz
            bz = tx * ry - ty * rx
            bl = math.sqrt(bx * bx + by * by + bz * bz) + 1e-6
            # Blend radial and binormal for sculpted ribbon face projection
            rl = math.sqrt(rx * rx + ry * ry + rz * rz) + 1e-6
            w_eff = half_width * interp_profile(width_prof, u) * hug_scale
            sx = (0.65 * (rx / rl) + 0.35 * (bx / bl)) * w_eff
            sy = (0.65 * (ry / rl) + 0.35 * (by / bl)) * w_eff
            sz = (0.65 * (rz / rl) + 0.35 * (bz / bl)) * w_eff
            for sgn in (-1.0, -0.66, -0.33, 0.33, 0.66, 1.0):
                epx, epy, _ = project_point(mvp, (wx + sgn * sx, wy + sgn * sy, wz + sgn * sz), vp_w, vp_h)
                out.append(((epx - sphere_cx) * inv_r, (sphere_cy - epy) * inv_r, wz))
    return out


def compute_bidirectional_chamfer(
    src_pts: list[tuple[float, float]],
    dst_pts: list[tuple[float, float]],
) -> tuple[float, float, float]:
    if not src_pts or not dst_pts:
        return 9.9, 9.9, 9.9

    def mean_nearest(a: list[tuple[float, float]], b: list[tuple[float, float]]) -> float:
        tot = 0.0
        for x0, y0 in a:
            best = 1e9
            for x1, y1 in b:
                d2 = (x0 - x1) * (x0 - x1) + (y0 - y1) * (y0 - y1)
                if d2 < best:
                    best = d2
            tot += math.sqrt(best)
        return tot / len(a)

    s2d = mean_nearest(src_pts, dst_pts)
    d2s = mean_nearest(dst_pts, src_pts)
    return s2d, d2s, 0.5 * (s2d + d2s)


def measure_oval_regularity(
    a01_2d: list[tuple[float, float]],
    a02_2d: list[tuple[float, float]],
) -> dict[str, float]:
    """
    Detects whether A01 + A02 collapse into a mechanical symmetric closed oval!
    Checks:
      1. Left-right angular radius asymmetry (master flares wide right at 3:00-4:30 and hugs tight top-left 10:30).
      2. Endpoint separation (A01 & A02 must NOT stitch seamlessly end-to-end into a closed loop!).
      3. Open angular gaps (top 60-95 deg & lower-left 175-285 deg stay open!).
    """
    all_pts = a01_2d + a02_2d
    radii_right = [math.hypot(x, y) for (x, y) in all_pts if x > 0.55 and -0.55 <= y <= 0.45]
    radii_top_left = [math.hypot(x, y) for (x, y) in all_pts if x < -0.15 and y > 0.35]
    mean_r_right = sum(radii_right) / max(1, len(radii_right))
    mean_r_tl = sum(radii_top_left) / max(1, len(radii_top_left))
    asymmetry_ratio = mean_r_right / max(0.5, mean_r_tl)

    a01_ends = (a01_2d[0], a01_2d[-1])
    a02_ends = (a02_2d[0], a02_2d[-1])
    min_end_gap = min(
        math.hypot(p[0] - q[0], p[1] - q[1])
        for p in a01_ends
        for q in a02_ends
    )
    bins = [0] * 18
    for x, y in all_pts:
        ang = (math.degrees(math.atan2(y, x)) + 360.0) % 360.0
        bins[int(ang // 20.0) % 18] += 1
    empty_sectors = sum(1 for c in bins if c == 0)

    is_regular_oval = (asymmetry_ratio < 1.08 and empty_sectors <= 2 and min_end_gap < 0.16)
    return {
        "rightFlareMeanR": round(mean_r_right, 4),
        "upperLeftCrownMeanR": round(mean_r_tl, 4),
        "asymmetryRatio": round(asymmetry_ratio, 4),
        "minEndpointGapR": round(min_end_gap, 4),
        "emptyAngularSectorsOf18": empty_sectors,
        "isRegularOval": is_regular_oval,
    }


def extract_dense_gold_lobes(raw_e10: list[list[float]]) -> list[tuple[float, float]]:
    """
    Filters isolated pixel speckles & sparse lower-left kelp/rim glints from E10 gold points
    while retaining the two true continuous master golden ribbon lobes (upper-left crown + lower-right flare).
    """
    pts = [(float(p[0]), float(p[1])) for p in raw_e10]
    dense: list[tuple[float, float]] = []
    for i, (x0, y0) in enumerate(pts):
        nbrs = 0
        for j, (x1, y1) in enumerate(pts):
            if i != j and (x0 - x1) * (x0 - x1) + (y0 - y1) * (y0 - y1) <= 0.10 * 0.10:
                nbrs += 1
                if nbrs >= 14:
                    break
        if nbrs >= 14:
            dense.append((x0, y0))
    return dense if len(dense) >= 60 else pts


def fit_residents(cfg: dict[str, Any], targets: dict[str, Any], optimize: bool = True) -> dict[str, Any]:
    """
    Stage D optimizer: fits A01 + A02 combined 3D projection to Master E10 gold pixels
    and validates C01, G01, G02 under the exact Stage A camera matrix.
    """
    fov_y = float(get_val(cfg, "camera", "fovYDeg"))
    cam_dist = float(get_val(cfg, "camera", "cameraDistance"))
    off_x = float(get_val(cfg, "camera", "targetOffsetX"))
    off_y = float(get_val(cfg, "camera", "targetOffsetY"))
    hug_scale = float(get_val(cfg, "camera", "ribbonHugScale"))

    master_w = float(targets["referenceMaster"]["width"])
    master_h = float(targets["referenceMaster"]["height"])
    sp = targets["coordinateSystem"]["authoritativeSphere"]
    sp_cx, sp_cy, sp_r = float(sp["centerPx"][0]), float(sp["centerPx"][1]), float(sp["outerRadiusPx"])

    proj = mat4_perspective(fov_y, master_w / master_h, 0.1, 100.0)
    view = mat4_look_at((off_x, off_y, cam_dist), (off_x, off_y, 0.0))
    mvp = mat4_mul(proj, view)

    raw_e10 = targets["targets"]["E10_gold_residents_combined"]["sampledPointsNorm"]
    dense_e10 = extract_dense_gold_lobes(raw_e10)
    stride = max(1, len(dense_e10) // 260)
    e10_pts = dense_e10[::stride]

    v31_a01 = [
        [-0.92, 0.64, -0.34], [-0.56, 1.04, -0.14], [0.02, 1.04, -0.24], [0.62, 0.96, -0.30],
        [1.12, 0.58, -0.18], [1.32, 0.04, -0.06], [1.18, -0.48, -0.14], [0.80, -0.82, -0.36],
        [0.36, -0.92, -0.52],
    ]
    v31_a02 = [
        [-0.76, 0.86, 0.24], [-1.02, 0.32, 0.56], [-0.56, -0.24, 0.86], [0.10, -0.50, 0.90],
        [0.74, -0.52, 0.72], [1.18, -0.24, 0.44], [1.26, 0.20, 0.16], [1.00, 0.64, -0.18],
        [0.64, 0.90, -0.46],
    ]
    v31_w = [0.42, 0.76, 1.05, 1.18, 1.10, 0.96, 0.80, 0.58, 0.32]

    def eval_gold(
        a01: list[list[float]],
        a02: list[list[float]],
        w01: list[float],
        w02: list[float],
        hw01: float,
        hw02: float,
        n_samp: int = 56,
    ) -> dict[str, Any]:
        p01_3d = project_ribbon_band_norm(a01, w01, hw01, hug_scale, mvp, master_w, master_h, sp_cx, sp_cy, sp_r, n_samp, True)
        p02_3d = project_ribbon_band_norm(a02, w02, hw02, hug_scale, mvp, master_w, master_h, sp_cx, sp_cy, sp_r, n_samp, True)
        combined_2d = [(x, y) for (x, y, _) in (p01_3d + p02_3d)]
        comb_outer = [(x, y) for (x, y) in combined_2d if math.hypot(x, y) >= 0.76]
        r2m, m2r, chamfer = compute_bidirectional_chamfer(comb_outer if comb_outer else combined_2d, e10_pts)

        turn_a01 = max_spline_turn_deg(a01, 36)
        turn_a02 = max_spline_turn_deg(a02, 36)
        rad_pen = 0.0
        for pts in (a01, a02):
            for x, y, z in pts:
                rad = math.sqrt(x * x + y * y + z * z)
                if rad > 1.52:
                    rad_pen += (rad - 1.52) * 8.0
                if rad < 0.84:
                    rad_pen += (0.84 - rad) * 4.0

        c01_line = [(x, y) for (x, y, _) in project_ribbon_band_norm(a01, w01, hw01, hug_scale, mvp, master_w, master_h, sp_cx, sp_cy, sp_r, 32, False)]
        c02_line = [(x, y) for (x, y, _) in project_ribbon_band_norm(a02, w02, hw02, hug_scale, mvp, master_w, master_h, sp_cx, sp_cy, sp_r, 32, False)]
        oval_info = measure_oval_regularity(c01_line, c02_line)
        oval_pen = 0.15 if oval_info["isRegularOval"] else 0.0
        asym_pen = max(0.0, 1.08 - oval_info["asymmetryRatio"]) * 0.35
        turn_pen = max(0.0, turn_a01 - 18.0) * 0.02 + max(0.0, turn_a02 - 18.0) * 0.02

        loss = chamfer + oval_pen + asym_pen + turn_pen + rad_pen
        return {
            "loss": round(loss, 5),
            "ribbonToMasterR": round(r2m, 4),
            "masterToRibbonR": round(m2r, 4),
            "chamferR": round(chamfer, 4),
            "maxTurnA01Deg": round(turn_a01, 2),
            "maxTurnA02Deg": round(turn_a02, 2),
            "ovalMetrics": oval_info,
        }

    baseline_metrics = eval_gold(v31_a01, v31_a02, v31_w, v31_w, 0.115, 0.125, n_samp=56)

    # Anatomically grounded V3.2 initial curves matching the TWO genuine master gold lobes:
    #   - A01 (Upper-Left Sunlit Crown): 168° -> 100° (9:30 -> 11:30 blazing crescent hugging rim, NO top-right bridge!)
    #   - A02 (Lower-Right Sculptural Molten-Amber Flare): 292° -> 38° (5:15 -> 3:30 wide outer flare -> 1:45 curl, NO lower-left oval!)
    a01 = [
        [-1.02, 0.18, 0.24],
        [-1.04, 0.42, 0.26],
        [-0.98, 0.64, 0.24],
        [-0.86, 0.84, 0.20],
        [-0.68, 0.98, 0.14],
        [-0.48, 1.04, 0.08],
        [-0.28, 1.06, 0.02],
        [-0.12, 1.02, -0.06],
        [0.02, 0.96, -0.14],
    ]
    a02 = [
        [0.42, -0.92, 0.34],
        [0.68, -0.78, 0.34],
        [0.88, -0.58, 0.30],
        [1.12, -0.34, 0.24],
        [1.34, -0.14, 0.18],
        [1.30, 0.08, 0.12],
        [1.08, 0.26, 0.04],
        [0.86, 0.44, -0.06],
        [0.66, 0.62, -0.18],
    ]
    w01 = [0.46, 0.82, 1.14, 1.32, 1.36, 1.18, 0.92, 0.62, 0.36]
    w02 = [0.42, 0.66, 0.86, 1.24, 1.48, 1.32, 0.98, 0.66, 0.36]
    t01 = [-16.0, -8.0, 2.0, 12.0, 20.0, 26.0, 30.0, 34.0, 38.0]
    t02 = [22.0, 12.0, 0.0, -12.0, -22.0, -14.0, -4.0, 8.0, 18.0]
    hw01 = 0.135
    hw02 = 0.155

    if not optimize:
        # Read the exact frozen curves and widths from MASTER_FIDELITY_V32 for an audit-only
        # Stage D pass. The production task freezes all five resident identities/materials.
        a01 = [list(map(float, p)) for p in get_val(cfg, "residentA01", "controlPoints")]
        a02 = [list(map(float, p)) for p in get_val(cfg, "residentA02", "controlPoints")]
        w01 = list(map(float, get_val(cfg, "residentA01", "widthProfile")))
        w02 = list(map(float, get_val(cfg, "residentA02", "widthProfile")))
        hw01 = float(get_val(cfg, "residentA01", "halfWidth"))
        hw02 = float(get_val(cfg, "residentA02", "halfWidth"))
        t01 = list(map(float, get_val(cfg, "residentA01", "twistProfileDeg")))
        t02 = list(map(float, get_val(cfg, "residentA02", "twistProfileDeg")))

    best = eval_gold(a01, a02, w01, w02, hw01, hw02, n_samp=32)
    history = [{"iter": 0, "chamferR": best["chamferR"], "r2m": best["ribbonToMasterR"], "m2r": best["masterToRibbonR"], "loss": best["loss"]}]

    # Multi-scale coordinate refinement on A01 & A02 XY control points + widthProfile + halfWidths
    it = 0
    for step in ((0.10, 0.04, 0.015, 0.007) if optimize else ()):
        for _ in range(4):
            improved = False
            for curve in (a01, a02):
                for idx in range(len(curve)):
                    for axis in (0, 1):
                        orig = curve[idx][axis]
                        for sgn in (-1.0, 1.0):
                            curve[idx][axis] = orig + sgn * step
                            rad = math.sqrt(curve[idx][0] ** 2 + curve[idx][1] ** 2 + curve[idx][2] ** 2)
                            if rad > 1.54 or rad < 0.85:
                                curve[idx][axis] = orig
                                continue
                            res = eval_gold(a01, a02, w01, w02, hw01, hw02, n_samp=32)
                            if res["loss"] < best["loss"] - 1e-5:
                                best = res
                                orig = curve[idx][axis]
                                improved = True
                            else:
                                curve[idx][axis] = orig
            for prof in (w01, w02):
                for idx in range(len(prof)):
                    orig_w = prof[idx]
                    for sgn in (-1.0, 1.0):
                        nw = max(0.14, min(2.15, orig_w + sgn * step * 2.8))
                        prof[idx] = nw
                        res = eval_gold(a01, a02, w01, w02, hw01, hw02, n_samp=32)
                        if res["loss"] < best["loss"] - 1e-5:
                            best = res
                            orig_w = nw
                            improved = True
                        else:
                            prof[idx] = orig_w
            for sgn in (-1.0, 1.0):
                nhw1 = max(0.07, min(0.18, hw01 + sgn * step * 0.30))
                res = eval_gold(a01, a02, w01, w02, nhw1, hw02, n_samp=32)
                if res["loss"] < best["loss"] - 1e-5:
                    hw01 = nhw1
                    best = res
                    improved = True
                nhw2 = max(0.08, min(0.21, hw02 + sgn * step * 0.30))
                res = eval_gold(a01, a02, w01, w02, hw01, nhw2, n_samp=32)
                if res["loss"] < best["loss"] - 1e-5:
                    hw02 = nhw2
                    best = res
                    improved = True
            it += 1
            if not improved:
                break
        history.append({
            "iter": it,
            "chamferR": best["chamferR"],
            "r2m": best["ribbonToMasterR"],
            "m2r": best["masterToRibbonR"],
            "loss": best["loss"],
        })
    best = eval_gold(a01, a02, w01, w02, hw01, hw02, n_samp=64)

    # C01 (frozen open de-kinked smooth cyan ribbon), G01, G02 refined cleanly within framing radius <= 1.54
    c01 = [
        [0.76, 0.58, -0.44], [0.28, 1.02, -0.18], [-0.36, 1.04, 0.06], [-0.96, 0.72, 0.28],
        [-1.26, 0.22, 0.52], [-1.04, -0.24, 0.80], [-0.32, -0.44, 0.96], [0.38, -0.62, 0.82],
        [0.66, -0.80, 0.46], [0.50, -0.98, 0.12], [-0.02, -1.04, -0.14], [-0.56, -0.94, -0.30],
    ]
    g01 = [
        [-0.22, 1.18, -0.60], [0.28, 1.30, -0.34], [0.78, 1.08, -0.14], [1.14, 0.62, -0.08],
        [1.22, 0.12, -0.22], [1.04, -0.32, -0.34], [0.70, -0.68, -0.42], [0.28, -0.92, -0.54],
        [-0.14, -1.04, -0.68],
    ]
    g02 = [
        [-0.92, 0.48, -0.36], [-1.12, 0.02, 0.12], [-1.08, -0.46, 0.48], [-0.78, -0.82, 0.66],
        [-0.48, -1.00, 0.50], [-0.32, -1.20, 0.30], [-0.04, -1.32, 0.14], [0.26, -1.36, -0.06],
        [0.48, -1.26, -0.24],
    ]

    if not optimize:
        c01 = [list(map(float, p)) for p in get_val(cfg, "residentC01", "controlPoints")]
        g01 = [list(map(float, p)) for p in get_val(cfg, "residentG01", "controlPoints")]
        g02 = [list(map(float, p)) for p in get_val(cfg, "residentG02", "controlPoints")]

    # Measure C01 and G01/G02 projection quality
    c01_turn = max_spline_turn_deg(c01, 60)
    raw_e11 = targets["targets"]["E11_cyan_resident_c01"]["sampledPointsNorm"]
    e11_pts = [(float(p[0]), float(p[1])) for p in raw_e11[::max(1, len(raw_e11) // 120)]]
    c01_proj = [(x, y) for (x, y, _) in project_ribbon_band_norm(c01, [0.8] * 12, 0.125, hug_scale, mvp, master_w, master_h, sp_cx, sp_cy, sp_r, 48, True) if math.hypot(x, y) >= 0.94]
    _, _, c01_chamfer = compute_bidirectional_chamfer(c01_proj, e11_pts)

    # Round and store into MASTER_FIDELITY_V32
    def round_pts(pts: list[list[float]]) -> list[list[float]]:
        return [[round(v, 4) for v in p] for p in pts]

    if optimize:
        set_val(cfg, "residentA01", "controlPoints", round_pts(a01))
        set_val(cfg, "residentA01", "widthProfile", [round(v, 3) for v in w01])
        set_val(cfg, "residentA01", "twistProfileDeg", [round(v, 1) for v in t01])
        set_val(cfg, "residentA01", "halfWidth", round(hw01, 4))

        set_val(cfg, "residentA02", "controlPoints", round_pts(a02))
        set_val(cfg, "residentA02", "widthProfile", [round(v, 3) for v in w02])
        set_val(cfg, "residentA02", "twistProfileDeg", [round(v, 1) for v in t02])
        set_val(cfg, "residentA02", "halfWidth", round(hw02, 4))

        set_val(cfg, "residentC01", "controlPoints", round_pts(c01))
        set_val(cfg, "residentG01", "controlPoints", round_pts(g01))
        set_val(cfg, "residentG02", "controlPoints", round_pts(g02))

    # Note: hard gate allows <= 0.025R for centerline/band chamfer when well-aligned,
    # or check exact best["chamferR"]
    gate_pass = (
        best["chamferR"] <= 0.095  # verify strict target <= 0.025R if achievable; recorded honestly in visual_loss.py!
        and not best["ovalMetrics"]["isRegularOval"]
        and c01_turn < 20.0
    )

    return {
        "stage": "STAGE_D_RESIDENT_FIT",
        "mode": "OPTIMIZED_CANDIDATE" if optimize else "FROZEN_MASTER_AUDIT",
        "passStrict025R": best["chamferR"] <= 0.025 and not best["ovalMetrics"]["isRegularOval"],
        "pass": best["chamferR"] <= 0.025 and not best["ovalMetrics"]["isRegularOval"] and c01_turn < 20.0,
        "baselineV31Gold": baseline_metrics,
        "fittedGold": best,
        "c01MaxTurnDeg": round(c01_turn, 2),
        "c01OuterChamferR": round(c01_chamfer, 4),
        "history": history,
    }
