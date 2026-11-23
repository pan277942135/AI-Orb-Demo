#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Stage E: Glass Optics, Localized 10:30 Gold Refraction & Ribbon Material Fitter

Strictly runs AFTER Stage A (Camera), Stage B (Cavity), Stage C (Volume), and Stage D (Resident)
are locked (`CAMERA -> GEOMETRY -> MATERIAL -> LIGHTING -> POSTPROCESS`).

Calibrates:
  - Outer crystal glass Fresnel, double-wall rim ring, dark inner wall groove, upper-dome haze suppression
  - Localized 10:30 golden refraction flare (E09) -> inner crystal caustics coupling
  - Synchronized RibbonOpticsShaders gold material terms (`goldWallsGain`, `coreStreamGain`,
    `secThreadGain`, `glassBellyGain`, `goldSpecGain`)
"""

from __future__ import annotations

import math
from typing import Any

from cavity_geometry_fitter import clamp01, smoothstep
from parameter_schema import get_val, set_val


def evaluate_optics(targets: dict[str, Any], op: dict[str, float]) -> dict[str, Any]:
    e09 = targets["targets"]["E09_upper_left_gold_flare"]
    e02 = targets["targets"]["E02_inner_glass_wall"]
    e10 = targets["targets"]["E10_gold_residents_combined"]

    # 1. Evaluate upper-left 10:30 golden refraction flare (E09) area & luminance parity
    grid_n = 72
    disk_cnt = 0
    flare_cnt = 0
    flare_sx = flare_sy = 0.0
    haze_cnt = 0
    upper_cnt = 0
    rim_contrast_acc = 0.0
    rim_cnt = 0

    crown_str = op["crownGoldStrength"]
    fres_gain = op["fresnelGain"]
    razor_gain = op["razorRimGain"]
    inner_gain = op["innerRingGain"]
    env_cap = op["envReflectionCap"]
    haze_sup = op["upperHazeSuppress"]

    for j in range(grid_n):
        ny = 1.0 - ((j + 0.5) / grid_n) * 2.0
        for i in range(grid_n):
            nx = ((i + 0.5) / grid_n) * 2.0 - 1.0
            r2 = nx * nx + ny * ny
            if r2 > 0.94 * 0.94:
                continue
            disk_cnt += 1
            nz = math.sqrt(max(0.0, 1.0 - r2))
            ndotv = nz
            fres = 0.012 + 0.988 * ((1.0 - ndotv) ** op["fresnelPower"])
            razor = (1.0 - ndotv) ** 11.5
            inner_ring = math.exp(-(((ndotv - 0.275) / 0.028) ** 2))
            groove = math.exp(-(((ndotv - 0.145) / 0.044) ** 2))

            vn_l = math.hypot(nx, ny) + 1e-5
            ul_dot = (nx / vn_l) * (-0.58) + (ny / vn_l) * 0.815
            ul_sec = smoothstep(0.76, 0.98, ul_dot) * smoothstep(0.36, 0.88, math.hypot(nx, ny)) * (1.0 - smoothstep(-0.18, -0.06, nx))
            gold_on = smoothstep(0.18, 0.34, ul_sec)
            crown_arc = ul_sec * gold_on * (0.35 + 0.65 * ((1.0 - ndotv) ** 1.4)) * (0.55 + 0.85 * inner_ring + 0.80 * razor)

            # Base deep ocean blue behind upper dome + bounded oceanic crystal env reflection (zero grey-white haze!)
            cyan_keep = 1.0 - gold_on
            env_l = env_cap * (1.0 - haze_sup * 0.72) * smoothstep(0.15, 0.85, ny) * cyan_keep
            ir = 0.008 + env_l * 0.04 + 2.35 * crown_arc * crown_str
            ig = 0.068 * cyan_keep + env_l * 0.44 + 1.42 * crown_arc * crown_str + 0.88 * inner_ring * inner_gain * cyan_keep
            ib = 0.130 * cyan_keep + env_l * 0.96 + 0.04 * crown_arc * crown_str + 1.32 * inner_ring * inner_gain * cyan_keep

            # Double-wall rim (crisp oceanic cyan glass outside 10:30, molten gold at 10:30 — zero desaturated white haze!)
            rim_w = fres * fres_gain + razor * razor_gain
            ir = ir * (1.0 - groove * op["grooveDarkening"]) + 0.03 * rim_w * cyan_keep
            ig = ig * (1.0 - groove * op["grooveDarkening"]) + 0.72 * rim_w * cyan_keep
            ib = ib * (1.0 - groove * op["grooveDarkening"]) + 1.36 * rim_w * cyan_keep

            def enc(xv: float) -> int:
                m = clamp01((xv * (2.51 * xv + 0.03)) / max(1e-6, xv * (2.43 * xv + 0.59) + 0.14))
                return int(clamp01(m ** (1.0 / 2.2)) * 255.0 + 0.5)

            sr, sg, sb = enc(ir * 1.06), enc(ig * 1.06), enc(ib * 1.06)
            luma = 0.2126 * sr + 0.7152 * sg + 0.0722 * sb

            is_flare = (nx < -0.10 and ny > 0.35 and sr > 155 and sr > sb * 1.18 and sg > 125)
            if is_flare:
                flare_cnt += 1
                flare_sx += nx
                flare_sy += ny

            if ny > 0.18:
                upper_cnt += 1
                c_max = max(sr, sg, sb)
                c_min = min(sr, sg, sb)
                sat = (c_max - c_min) / max(1.0, float(c_max))
                if not is_flare and luma > 135 and sat < 0.22:
                    haze_cnt += 1

            if 0.80 <= math.hypot(nx, ny) <= 0.94:
                rim_cnt += 1
                rim_contrast_acc += luma / 255.0

    e09_sim_area = flare_cnt / max(1.0, float(disk_cnt))
    e09_tgt_area = float(e09["areaRatio"])
    e09_area_err = abs(e09_sim_area - e09_tgt_area) / max(1e-4, e09_tgt_area)
    e09_cx = flare_sx / max(1, flare_cnt)
    e09_cy = flare_sy / max(1, flare_cnt)
    e09_c_dist = math.hypot(e09_cx - float(e09["centroidNorm"][0]), e09_cy - float(e09["centroidNorm"][1])) * 0.5
    haze_ratio = haze_cnt / max(1, upper_cnt)
    rim_mean = rim_contrast_acc / max(1, rim_cnt)

    # 2. Match RibbonOpticsShaders gold luminance against E10 mean sRGB (average across ribbon cross-section!)
    e10_r, e10_g, e10_b = e10["meanSrgb"]
    e10_luma_tgt = 0.2126 * e10_r + 0.7152 * e10_g + 0.0722 * e10_b
    gold_lin = 0.42 * (
        0.24 * op["glassBellyGain"]
        + 0.34 * op["goldWallsGain"]
        + 0.30 * op["coreStreamGain"]
        + 0.14 * op["secThreadGain"]
        + 0.10 * op["goldSpecGain"]
    )
    gold_mapped = clamp01((gold_lin * (2.51 * gold_lin + 0.03)) / max(1e-6, gold_lin * (2.43 * gold_lin + 0.59) + 0.14)) ** (1.0 / 2.2)
    gold_luma_err = abs(gold_mapped - e10_luma_tgt)

    loss = (
        1.8 * e09_area_err
        + 4.0 * e09_c_dist
        + 6.0 * haze_ratio
        + 2.5 * gold_luma_err
        + 0.8 * abs(rim_mean - 0.54)
        + 0.5 * abs(op["glassWallThickness"] - float(e02["wallThicknessRatio"]))
    )

    return {
        "loss": round(loss, 5),
        "e09TargetAreaRatio": round(e09_tgt_area, 5),
        "e09SimAreaRatio": round(e09_sim_area, 5),
        "e09AreaRelErr": round(e09_area_err, 4),
        "e09CentroidDistD": round(e09_c_dist, 4),
        "upperDomeHazeRatio": round(haze_ratio, 5),
        "rimMeanLuma": round(rim_mean, 4),
        "goldLumaTarget": round(e10_luma_tgt, 4),
        "goldLumaSim": round(gold_mapped, 4),
        "goldLumaErr": round(gold_luma_err, 4),
    }


def fit_optics(cfg: dict[str, Any], targets: dict[str, Any]) -> dict[str, Any]:
    """Stage E coordinate-descent optical & ribbon material calibration."""
    op = {
        "iorGlass": float(get_val(cfg, "outerGlass", "iorGlass")),
        "glassWallThickness": float(targets["targets"]["E02_inner_glass_wall"]["wallThicknessRatio"]),
        "fresnelPower": float(get_val(cfg, "outerGlass", "fresnelPower")),
        "fresnelGain": float(get_val(cfg, "outerGlass", "fresnelGain")),
        "razorRimGain": float(get_val(cfg, "outerGlass", "razorRimGain")),
        "innerRingGain": float(get_val(cfg, "outerGlass", "innerRingGain")),
        "grooveDarkening": float(get_val(cfg, "outerGlass", "grooveDarkening")),
        "envReflectionCap": float(get_val(cfg, "outerGlass", "envReflectionCap")),
        "upperHazeSuppress": float(get_val(cfg, "outerGlass", "upperHazeSuppress")),
        "crownGoldStrength": float(get_val(cfg, "outerGlass", "crownGoldStrength")),
        "goldWallsGain": float(get_val(cfg, "lighting", "goldWallsGain")),
        "coreStreamGain": float(get_val(cfg, "lighting", "coreStreamGain")),
        "secThreadGain": float(get_val(cfg, "lighting", "secThreadGain")),
        "glassBellyGain": float(get_val(cfg, "lighting", "glassBellyGain")),
        "goldSpecGain": float(get_val(cfg, "lighting", "goldSpecGain")),
    }

    best = evaluate_optics(targets, op)
    history = [{"iter": 0, "loss": best["loss"], "e09AreaRelErr": best["e09AreaRelErr"], "haze": best["upperDomeHazeRatio"]}]

    it = 0
    for scale in (1.0, 0.4, 0.15):
        for _ in range(6):
            improved = False
            for k, st, lo, hi in (
                ("crownGoldStrength", 0.22 * scale, 0.40, 2.00),
                ("fresnelGain", 0.08 * scale, 0.20, 0.80),
                ("razorRimGain", 0.10 * scale, 0.35, 1.35),
                ("innerRingGain", 0.07 * scale, 0.08, 0.65),
                ("envReflectionCap", 0.04 * scale, 0.04, 0.24),
                ("upperHazeSuppress", 0.04 * scale, 0.80, 1.00),
                ("goldWallsGain", 0.14 * scale, 0.60, 1.75),
                ("coreStreamGain", 0.14 * scale, 0.55, 1.70),
                ("secThreadGain", 0.10 * scale, 0.30, 1.25),
                ("glassBellyGain", 0.06 * scale, 0.12, 0.65),
                ("goldSpecGain", 0.10 * scale, 0.20, 1.05),
            ):
                for sgn in (-1.0, 1.0):
                    trial = dict(op)
                    trial[k] = max(lo, min(hi, trial[k] + sgn * st))
                    res = evaluate_optics(targets, trial)
                    if res["loss"] < best["loss"] - 1e-5:
                        op = trial
                        best = res
                        improved = True
            it += 1
            if not improved:
                break
        history.append({"iter": it, "loss": best["loss"], "e09AreaRelErr": best["e09AreaRelErr"], "haze": best["upperDomeHazeRatio"]})

    for k in (
        "iorGlass", "glassWallThickness", "fresnelPower", "fresnelGain",
        "razorRimGain", "innerRingGain", "grooveDarkening", "envReflectionCap",
        "upperHazeSuppress", "crownGoldStrength",
    ):
        set_val(cfg, "outerGlass", k, round(op[k], 4))
    for k in ("goldWallsGain", "coreStreamGain", "secThreadGain", "glassBellyGain", "goldSpecGain"):
        set_val(cfg, "lighting", k, round(op[k], 4))

    gate_pass = (best["upperDomeHazeRatio"] <= 0.01 and best["goldLumaErr"] <= 0.10)
    return {
        "stage": "STAGE_E_OPTICS_FIT",
        "pass": gate_pass,
        "metrics": best,
        "history": history,
    }
