#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Stage B: Upper Crystal Cavity & Embedded Lens Geometry Fitter

Quantitatively compares TWO 3D optical/geometric candidates against frozen master E03 & E04:
  - Candidate A (`CANDIDATE_A_AIR_LIQUID`):
      32-sample meniscus air-liquid interface (`cavityPhi`) + outer thick glass refraction only.
  - Candidate B (`CANDIDATE_B_CRYSTAL_LENS`):
      32-sample meniscus air-liquid interface (`cavityPhi`) + independent embedded transparent
      crystal lens shell (`crystalLensPhi`, `crystalLensNormal`, `intersectCrystalLens`) with
      physical IOR chain (air=1.0003, water=1.333, crystal/glass=1.45), front/rear curved rim
      refraction, and localized 10:30 golden light coupling.

Targets:
  - E03 upper cavity relative area error <= 15%
  - E03 upper cavity centroid distance <= 0.04 D
  - E04 liquid interface contour mean error <= 0.025 R
  - Zero grey-white upper-hemisphere haze
"""

from __future__ import annotations

import math
from typing import Any

from parameter_schema import get_val, set_val
from visual_metrics import UPPER_DOME_MIN_Y, is_upper_dome_grey_white_haze


def clamp01(v: float) -> float:
    return 0.0 if v <= 0.0 else (1.0 if v >= 1.0 else v)


def smoothstep(e0: float, e1: float, x: float) -> float:
    t = clamp01((x - e0) / max(1e-6, e1 - e0))
    return t * t * (3.0 - 2.0 * t)


def sample_meniscus_profile(heights: list[float], x: float) -> float:
    t = clamp01((x + 0.96875) / 1.9375) * (len(heights) - 1)
    i0 = min(len(heights) - 2, max(0, int(math.floor(t))))
    i1 = i0 + 1
    frac = clamp01(t - i0)
    return heights[i0] * (1.0 - frac) + heights[i1] * frac


def cavity_phi_v32(
    heights: list[float],
    x: float,
    y: float,
    z: float,
    y_offset: float,
    dome_dip: float,
    wall_climb: float,
    phase: float = 0.0,
) -> float:
    base_y = (
        0.25 * sample_meniscus_profile(heights, x - 0.08)
        + 0.50 * sample_meniscus_profile(heights, x)
        + 0.25 * sample_meniscus_profile(heights, x + 0.08)
    )
    r2 = x * x + z * z
    dip = dome_dip * z * z - 0.035 * z
    climb = wall_climb * r2 * r2
    wave = (
        0.012 * math.sin(x * 2.4 + phase * 0.45 + z * 1.1)
        + 0.008 * math.cos(z * 2.8 - phase * 0.36 - x * 1.5)
    )
    return y - (base_y + y_offset + dip + climb + wave)


CRYSTAL_LENS_ASYM_SHEAR = 0.08


def crystal_lens_phi(
    x: float,
    y: float,
    z: float,
    cx: float,
    cy: float,
    cz: float,
    rx: float,
    ry: float,
    rz: float,
) -> float:
    """
    Canonical signed distance proxy to the embedded upper transparent crystal dome/lens cavity.
    Shared 1:1 across Python (cavity_geometry_fitter.py), GLSL (CoreFluidShaders.kt),
    and Kotlin (CrystalLensGeometry.kt).
    Negative inside the embedded crystal pocket, zero on the crystal shell boundary, positive outside.
    """
    inv_rx = 1.0 / max(0.05, rx)
    inv_ry = 1.0 / max(0.05, ry)
    inv_rz = 1.0 / max(0.05, rz)
    qx = (x - cx) * inv_rx
    qy = (y - cy) * inv_ry + CRYSTAL_LENS_ASYM_SHEAR * qx
    qz = (z - cz) * inv_rz
    return math.sqrt(qx * qx + qy * qy + qz * qz) - 1.0


def crystal_lens_normal(
    x: float,
    y: float,
    z: float,
    cx: float,
    cy: float,
    cz: float,
    rx: float,
    ry: float,
    rz: float,
) -> tuple[float, float, float]:
    """
    Exact outward unit normal (analytic gradient of crystal_lens_phi) matching GLSL and Kotlin 1:1.
    """
    inv_rx = 1.0 / max(0.05, rx)
    inv_ry = 1.0 / max(0.05, ry)
    inv_rz = 1.0 / max(0.05, rz)
    qx = (x - cx) * inv_rx
    qy = (y - cy) * inv_ry + CRYSTAL_LENS_ASYM_SHEAR * qx
    qz = (z - cz) * inv_rz
    gx = (qx + CRYSTAL_LENS_ASYM_SHEAR * qy) * inv_rx
    gy = qy * inv_ry
    gz = qz * inv_rz
    gl = math.sqrt(gx * gx + gy * gy + gz * gz)
    if gl <= 1e-8:
        return 0.0, 1.0, 0.0
    return gx / gl, gy / gl, gz / gl


def intersect_crystal_lens(
    ox: float,
    oy: float,
    oz: float,
    rx_d: float,
    ry_d: float,
    rz_d: float,
    t_max: float,
    cx: float,
    cy: float,
    cz: float,
    rad_x: float,
    rad_y: float,
    rad_z: float,
) -> tuple[bool, float, float, float]:
    """
    Ray-ellipsoid intersection against the embedded upper crystal lens pocket.
    Returns (hit, t_enter, t_exit, optical_thickness).
    """
    inv_rx = 1.0 / max(0.05, rad_x)
    inv_ry = 1.0 / max(0.05, rad_y)
    inv_rz = 1.0 / max(0.05, rad_z)

    qx = (ox - cx) * inv_rx
    qy = (oy - cy) * inv_ry + CRYSTAL_LENS_ASYM_SHEAR * qx
    qz = (oz - cz) * inv_rz

    dx = rx_d * inv_rx
    dy = ry_d * inv_ry + CRYSTAL_LENS_ASYM_SHEAR * dx
    dz = rz_d * inv_rz

    a = dx * dx + dy * dy + dz * dz
    b = qx * dx + qy * dy + qz * dz
    c = qx * qx + qy * qy + qz * qz - 1.0
    disc = b * b - a * c
    if disc <= 0.0 or a <= 1e-8:
        return False, 0.0, 0.0, 0.0
    sq = math.sqrt(disc)
    t0 = (-b - sq) / a
    t1 = (-b + sq) / a
    if t1 <= 0.0 or t0 >= t_max:
        return False, 0.0, 0.0, 0.0
    t_enter = max(0.0, t0)
    t_exit = min(t_max, t1)
    thickness = max(0.0, t_exit - t_enter)
    return thickness > 1e-4, t_enter, t_exit, thickness


def evaluate_cavity_candidate(
    model_name: str,
    heights: list[float],
    ref_samples: list[dict],
    e03_target: dict,
    params: dict[str, float],
    grid_n: int = 96,
) -> dict[str, Any]:
    """
    Simulates upper-hemisphere cavity & crystal lens ray interaction on a deterministic
    2D sphere grid using the EXACT same E03 classifier as reference_measurements.py.
    """
    y_off = params["meniscusYOffset"]
    dome_dip = params["domeDepthDip"]
    wall_climb = params["sideWallClimb"]
    use_lens = (model_name == "CANDIDATE_B_CRYSTAL_LENS")

    lx = params.get("lensCenterX", -0.05)
    ly = params.get("lensCenterY", 0.56)
    lz = params.get("lensCenterZ", 0.08)
    lrx = params.get("lensRadiusX", 0.64)
    lry = params.get("lensRadiusY", 0.30)
    lrz = params.get("lensRadiusZ", 0.58)
    l_wall = params.get("lensWallThickness", 0.055)
    rim_refr = params.get("cavityRimRefraction", 0.48)
    gold_pen = params.get("upperGoldPenetration", 0.85)
    tint_gain = params.get("cavityTintGain", 0.18)

    # 1. E04 contour error across the 32 master meniscus samples (at z=0, phase=0)
    e04_errs = []
    for entry in ref_samples:
        sx = float(entry["nx"])
        ref_ny = float(entry["ny"])
        sim_ny = -cavity_phi_v32(heights, sx, 0.0, 0.0, y_off, dome_dip, wall_climb, 0.0)
        e04_errs.append(abs(sim_ny - ref_ny))
    e04_mean_r = sum(e04_errs) / len(e04_errs)
    e04_max_r = max(e04_errs)

    # 2. Ray-cast upper cavity visibility & E03 dark-pocket classification
    disk_samples = 0
    e03_cnt = 0
    e03_sx = 0.0
    e03_sy = 0.0
    haze_sum = 0.0
    haze_cnt = 0
    highlight_align_sum = 0.0

    for j in range(grid_n):
        ny = 1.0 - ((j + 0.5) / grid_n) * 2.0
        for i in range(grid_n):
            nx = ((i + 0.5) / grid_n) * 2.0 - 1.0
            r2 = nx * nx + ny * ny
            if r2 > 0.94 * 0.94:
                continue
            disk_samples += 1
            if ny <= 0.14:
                continue
            nz = math.sqrt(max(0.0, 1.0 - r2))
            # Refracted view ray into glass sphere (IOR 1.45)
            eta = 1.0 / 1.45
            ndotv = nz
            k_refr = max(0.0, 1.0 - eta * eta * (1.0 - ndotv * ndotv))
            fac = eta * ndotv - math.sqrt(k_refr)
            rx = fac * nx
            ry = fac * ny
            rz = -eta + fac * nz
            rl = math.sqrt(rx * rx + ry * ry + rz * rz)
            rx, ry, rz = rx / rl, ry / rl, rz / rl

            ox, oy, oz = nx * 0.945, ny * 0.945, nz * 0.945
            b_dot = ox * rx + oy * ry + oz * rz
            c_rad = (ox * ox + oy * oy + oz * oz) - 0.945 * 0.945
            t_exit = max(0.05, -b_dot + math.sqrt(max(0.0, b_dot * b_dot - c_rad)))

            # Check liquid fraction along ray (10 steps)
            liq_path = 0.0
            n_st = 10
            dt = t_exit / n_st
            for s in range(n_st):
                tm = (s + 0.5) * dt
                px, py, pz = ox + rx * tm, oy + ry * tm, oz + rz * tm
                phi = cavity_phi_v32(heights, px, py, pz, y_off, dome_dip, wall_climb, 0.0)
                if phi < 0.0:
                    liq_path += dt

            # Base underwater sapphire/teal luminance seen through upper dome
            air_frac = 1.0 - smoothstep(0.02, 0.24, liq_path)
            r_lin = 0.008 + 0.025 * (1.0 - air_frac)
            g_lin = 0.055 + 0.160 * (1.0 - air_frac)
            b_lin = 0.095 + 0.260 * (1.0 - air_frac)

            # Localized 10:30 golden refraction flare (E09) strictly on upper-left dome (zero grey-white blue+gold mixing!)
            vn_l = math.hypot(nx, ny) + 1e-5
            ul_dot = (nx / vn_l) * (-0.58) + (ny / vn_l) * 0.815
            ul_sec = (
                smoothstep(0.80, 0.96, ul_dot)
                * smoothstep(0.42, 0.86, math.hypot(nx, ny))
                * (1.0 - smoothstep(-0.16, -0.10, nx))
                * smoothstep(0.35, 0.44, ny)
            )
            gold_raw = ul_sec * (0.42 + 0.58 * (1.0 - ndotv) ** 1.5)
            gold_on = smoothstep(0.28, 0.42, gold_raw)
            gold_ray = gold_raw * gold_on * gold_pen

            if use_lens:
                hit_l, t_in, t_out, thick = intersect_crystal_lens(
                    ox, oy, oz, rx, ry, rz, t_exit, lx, ly, lz, lrx, lry, lrz
                )
                cyan_keep = (1.0 - gold_on) * (1.0 - gold_on)
                if hit_l:
                    wall_ring = math.exp(-((thick - l_wall * 4.5) / max(0.06, l_wall * 3.8)) ** 2)
                    deep_pocket = smoothstep(l_wall * 5.2, l_wall * 10.5, thick)
                    r_lin = r_lin * (1.0 - deep_pocket * 0.84) + 0.006 * wall_ring * rim_refr * cyan_keep
                    g_lin = g_lin * (1.0 - deep_pocket * 0.80) + 0.145 * wall_ring * rim_refr * cyan_keep + tint_gain * 0.10 * (1.0 - deep_pocket) * cyan_keep
                    b_lin = b_lin * (1.0 - deep_pocket * 0.76) * cyan_keep + 0.375 * wall_ring * rim_refr * cyan_keep + tint_gain * 0.24 * (1.0 - deep_pocket) * cyan_keep
                    r_lin += gold_ray * 1.65
                    g_lin += gold_ray * 0.96
                    b_lin += gold_ray * 0.02
                    highlight_align_sum += wall_ring * ul_sec
                else:
                    r_lin = r_lin * cyan_keep + 0.005 * cyan_keep + gold_ray * 1.60
                    g_lin = g_lin * cyan_keep + 0.125 * cyan_keep + gold_ray * 0.92
                    b_lin = b_lin * cyan_keep + 0.385 * cyan_keep + gold_ray * 0.02
            else:
                # Candidate A: uniform air-cavity without crystal lens shell walls
                r_lin = r_lin * (1.0 - air_frac * 0.85) + 0.008 * air_frac + gold_ray * 0.55
                g_lin = g_lin * (1.0 - air_frac * 0.85) + 0.036 * air_frac + gold_ray * 0.30
                b_lin = b_lin * (1.0 - air_frac * 0.85) + 0.052 * air_frac + gold_ray * 0.08

            # ACES + sRGB 2.2 encoding (matching production COMPOSITE_FRAG)
            def enc(x: float) -> int:
                m = clamp01((x * (2.51 * x + 0.03)) / max(1e-6, x * (2.43 * x + 0.59) + 0.14))
                return int(clamp01(m ** (1.0 / 2.2)) * 255.0 + 0.5)

            sr, sg, sb = enc(r_lin * 1.06), enc(g_lin * 1.06), enc(b_lin * 1.06)
            luma = 0.2126 * sr + 0.7152 * sg + 0.0722 * sb
            is_flare = (nx < -0.10 and ny > 0.35 and sr > 155 and sr > sb * 1.18 and sg > 125)
            is_emerald = (sg > 52 and sg > sb * 1.07 and sg >= sr * 1.02 and not is_flare)
            is_cavity = (ny > 0.14 and luma < 86 and not is_emerald and not is_flare and sb < 112 and sg < 102)

            if is_cavity:
                e03_cnt += 1
                e03_sx += nx
                e03_sy += ny

            # Shared E02 domain and classifier: upper dome only (ny > 0.18), excluding the
            # localized gold flare, luma > 135 and saturation < 0.22. Previously the
            # numerator used the full sphere while Stage E divided by upper-dome pixels,
            # making Stage B's "upper" haze ratio incomparable and inflating it with lower
            # liquid pixels. Keep Stage B's real candidate render in the same locked domain.
            if ny > UPPER_DOME_MIN_Y:
                if is_upper_dome_grey_white_haze(ny, sr, sg, sb, is_flare):
                    haze_sum += 1.0
                haze_cnt += 1

    sim_area = e03_cnt / max(1.0, float(disk_samples))
    sim_cx = e03_sx / max(1, e03_cnt)
    sim_cy = e03_sy / max(1, e03_cnt)

    tgt_area = float(e03_target["areaRatio"])
    tgt_cx, tgt_cy = float(e03_target["centroidNorm"][0]), float(e03_target["centroidNorm"][1])

    area_rel_err = abs(sim_area - tgt_area) / max(1e-4, tgt_area)
    centroid_dist_d = math.hypot(sim_cx - tgt_cx, sim_cy - tgt_cy) * 0.5
    haze_ratio = haze_sum / max(1, haze_cnt)
    complexity_pen = 0.015 if use_lens else 0.0

    loss = (
        2.5 * area_rel_err
        + 8.0 * centroid_dist_d
        + 12.0 * e04_mean_r
        + 4.0 * haze_ratio
        + complexity_pen
    )

    return {
        "model": model_name,
        "e03TargetAreaRatio": round(tgt_area, 5),
        "e03SimAreaRatio": round(sim_area, 5),
        "e03AreaRelErr": round(area_rel_err, 4),
        "e03TargetCentroid": [round(tgt_cx, 4), round(tgt_cy, 4)],
        "e03SimCentroid": [round(sim_cx, 4), round(sim_cy, 4)],
        "e03CentroidDistD": round(centroid_dist_d, 4),
        "e04MeanErrorR": round(e04_mean_r, 5),
        "e04MaxErrorR": round(e04_max_r, 5),
        "upperGreyWhiteHazeRatio": round(haze_ratio, 5),
        "highlightAlignmentScore": round(highlight_align_sum / max(1, haze_cnt), 5),
        "loss": round(loss, 5),
        "params": {k: round(v, 4) for k, v in params.items()},
    }


def fit_cavity_geometry(cfg: dict[str, Any], targets: dict[str, Any]) -> dict[str, Any]:
    """
    Stage B optimizer: optimizes BOTH Candidate A and Candidate B independently,
    compares quantitative loss & hard gates, and locks the winner into MASTER_FIDELITY_V32.
    """
    e03_target = targets["targets"]["E03_upper_cavity"]
    ref_samples = targets["targets"]["E04_liquid_interface_contour"]["profileSamples"]
    heights = [float(s["ny"]) for s in ref_samples]

    # --- Optimize Candidate A (air-liquid interface only) ---
    cand_a_params = {
        "meniscusYOffset": 0.0,
        "domeDepthDip": 0.14,
        "sideWallClimb": 0.048,
        "upperGoldPenetration": 0.85,
    }
    best_a = evaluate_cavity_candidate("CANDIDATE_A_AIR_LIQUID", heights, ref_samples, e03_target, cand_a_params, grid_n=52)
    for step_y, step_d in ((0.06, 0.04), (0.018, 0.014)):
        for _ in range(4):
            improved = False
            for k, st, lo, hi in (
                ("meniscusYOffset", step_y, -0.15, 0.24),
                ("domeDepthDip", step_d, 0.02, 0.28),
                ("sideWallClimb", step_d * 0.5, 0.0, 0.14),
                ("upperGoldPenetration", 0.18, 0.20, 1.50),
            ):
                for sgn in (-1.0, 1.0):
                    trial = dict(cand_a_params)
                    trial[k] = max(lo, min(hi, trial[k] + sgn * st))
                    res = evaluate_cavity_candidate("CANDIDATE_A_AIR_LIQUID", heights, ref_samples, e03_target, trial, grid_n=52)
                    if res["loss"] < best_a["loss"] - 1e-6:
                        cand_a_params = trial
                        best_a = res
                        improved = True
            if not improved:
                break
    best_a = evaluate_cavity_candidate("CANDIDATE_A_AIR_LIQUID", heights, ref_samples, e03_target, cand_a_params, grid_n=96)

    # --- Optimize Candidate B (air-liquid interface + embedded crystal lens shell) ---
    cand_b_params = {
        "meniscusYOffset": 0.0,
        "domeDepthDip": 0.14,
        "sideWallClimb": 0.048,
        "lensCenterX": float(e03_target["centroidNorm"][0]) * 0.75,
        "lensCenterY": max(0.20, float(e03_target["centroidNorm"][1]) - 0.14),
        "lensCenterZ": 0.16,
        "lensRadiusX": 0.42,
        "lensRadiusY": 0.24,
        "lensRadiusZ": 0.44,
        "lensWallThickness": 0.038,
        "cavityRimRefraction": 0.46,
        "upperGoldPenetration": 0.86,
        "cavityTintGain": 0.14,
    }
    best_b = evaluate_cavity_candidate("CANDIDATE_B_CRYSTAL_LENS", heights, ref_samples, e03_target, cand_b_params, grid_n=52)
    history = [{"iter": 0, "model": "CANDIDATE_B", "loss": best_b["loss"], "e03AreaRelErr": best_b["e03AreaRelErr"], "e04MeanR": best_b["e04MeanErrorR"]}]

    it = 0
    for scale in (1.0, 0.35):
        for _ in range(6):
            improved = False
            search_specs = [
                ("lensCenterX", 0.05 * scale, -0.24, 0.34),
                ("lensCenterY", 0.05 * scale, 0.18, 0.68),
                ("lensRadiusX", 0.06 * scale, 0.24, 0.88),
                ("lensRadiusY", 0.045 * scale, 0.12, 0.48),
                ("lensRadiusZ", 0.05 * scale, 0.24, 0.82),
                ("lensWallThickness", 0.009 * scale, 0.020, 0.095),
                ("cavityRimRefraction", 0.08 * scale, 0.12, 0.92),
                ("meniscusYOffset", 0.012 * scale, -0.03, 0.03),
                ("domeDepthDip", 0.025 * scale, 0.04, 0.24),
            ]
            for k, st, lo, hi in search_specs:
                for sgn in (-1.0, 1.0):
                    trial = dict(cand_b_params)
                    trial[k] = max(lo, min(hi, trial[k] + sgn * st))
                    res = evaluate_cavity_candidate("CANDIDATE_B_CRYSTAL_LENS", heights, ref_samples, e03_target, trial, grid_n=52)
                    if res["loss"] < best_b["loss"] - 1e-6:
                        cand_b_params = trial
                        best_b = res
                        improved = True
            it += 1
            if not improved:
                break
        history.append({
            "iter": it,
            "model": "CANDIDATE_B",
            "loss": best_b["loss"],
            "e03AreaRelErr": best_b["e03AreaRelErr"],
            "e03CentroidDistD": best_b["e03CentroidDistD"],
            "e04MeanR": best_b["e04MeanErrorR"],
        })
    best_b = evaluate_cavity_candidate("CANDIDATE_B_CRYSTAL_LENS", heights, ref_samples, e03_target, cand_b_params, grid_n=96)

    winner = best_b if best_b["loss"] < best_a["loss"] else best_a
    win_params = cand_b_params if winner is best_b else cand_a_params

    # Persist winning model into MASTER_FIDELITY_V32
    set_val(cfg, "innerCavity", "selectedModel", winner["model"])
    for key in (
        "meniscusYOffset", "domeDepthDip", "sideWallClimb",
        "lensCenterX", "lensCenterY", "lensCenterZ",
        "lensRadiusX", "lensRadiusY", "lensRadiusZ",
        "lensWallThickness", "cavityRimRefraction", "upperGoldPenetration", "cavityTintGain",
    ):
        if key in win_params:
            set_val(cfg, "innerCavity", key, round(win_params[key], 4))

    gate_pass = (
        winner["e03AreaRelErr"] <= 0.15
        and winner["e03CentroidDistD"] <= 0.04
        and winner["e04MeanErrorR"] <= 0.025
        and winner["upperGreyWhiteHazeRatio"] <= 0.01
    )

    return {
        "stage": "STAGE_B_CAVITY_FIT",
        "pass": gate_pass,
        "selectedModel": winner["model"],
        "candidateA": best_a,
        "candidateB": best_b,
        "winner": winner,
        "history": history,
    }
