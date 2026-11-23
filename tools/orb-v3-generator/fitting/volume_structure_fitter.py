#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Stage C: 3D WebP Fluid Volume Structure & Fold Surface Fitter

Uses the SINGLE authoritative `canonical_fluid_volume.evaluate_canonical_voxel` shared 1:1
with `generate_orb_volume.py` — eliminating all algorithmic drift between Stage C optimization
and the generated 256x256x32 Lossless RGBA WebP assets.

Also provides `evaluate_decoded_webp_projection()` to independently project and score the
actual decoded 32 WebP slices via trilinear 3D texture sampling (mirroring GLES 3.0
GL_TEXTURE_3D + CoreFluidShaders.kt) under the EXACT SAME unified pixel classifier as
`reference_measurements.py`.
"""

from __future__ import annotations

import math
import itertools
import sys
from pathlib import Path
from typing import Any

GEN_DIR = Path(__file__).resolve().parents[1]
if str(GEN_DIR) not in sys.path:
    sys.path.insert(0, str(GEN_DIR))

from canonical_fluid_volume import (
    SEED as CANONICAL_SEED,
    clamp01,
    evaluate_canonical_voxel,
    extract_volume_params,
    sample_trilinear_volume_3d,
    smoothstep,
)
from cavity_geometry_fitter import cavity_phi_v32, intersect_crystal_lens
from parameter_schema import get_val, set_val


# Preserve backward-compatible symbol name pointing directly to the single canonical evaluator
evaluate_fluid_voxel = evaluate_canonical_voxel


def _encode_aces_srgb_u8(x_v: float) -> int:
    m = clamp01((x_v * (2.51 * x_v + 0.03)) / max(1e-6, x_v * (2.43 * x_v + 0.59) + 0.14))
    return int(clamp01(m ** (1.0 / 2.2)) * 255.0 + 0.5)


def _evaluate_raymarch_core(
    cfg: dict[str, Any],
    targets: dict[str, Any],
    vp: dict[str, float],
    sample_fn: Any,
    grid_n: int = 96,
    steps: int = 16,
    evaluation_source: str = "CANONICAL_ANALYTICAL_FIELD",
) -> dict[str, Any]:
    """
    Shared deterministic 3D raymarch projection mirroring CoreFluidShaders.kt (including locked
    Stage B cavity/crystal lens!) and scoring E03, E05, E06, E07 with the EXACT SAME unified
    pixel classifier as `reference_measurements.py`.
    """
    heights = [float(s["ny"]) for s in targets["targets"]["E04_liquid_interface_contour"]["profileSamples"]]

    # Locked Stage B cavity parameters
    y_off = float(get_val(cfg, "innerCavity", "meniscusYOffset"))
    dome_dip = float(get_val(cfg, "innerCavity", "domeDepthDip"))
    wall_climb = float(get_val(cfg, "innerCavity", "sideWallClimb"))
    use_lens = (get_val(cfg, "innerCavity", "selectedModel") == "CANDIDATE_B_CRYSTAL_LENS")
    lx = float(get_val(cfg, "innerCavity", "lensCenterX"))
    ly = float(get_val(cfg, "innerCavity", "lensCenterY"))
    lz = float(get_val(cfg, "innerCavity", "lensCenterZ"))
    lrx = float(get_val(cfg, "innerCavity", "lensRadiusX"))
    lry = float(get_val(cfg, "innerCavity", "lensRadiusY"))
    lrz = float(get_val(cfg, "innerCavity", "lensRadiusZ"))
    l_wall = float(get_val(cfg, "innerCavity", "lensWallThickness"))
    rim_refr = float(get_val(cfg, "innerCavity", "cavityRimRefraction"))
    gold_pen = float(get_val(cfg, "innerCavity", "upperGoldPenetration"))
    tint_gain = float(get_val(cfg, "innerCavity", "cavityTintGain"))

    dens_scale = vp["densityScale"]
    bl_r, bl_g, bl_b = vp["beerLambertR"], vp["beerLambertG"], vp["beerLambertB"]
    fold_spec = vp["foldSpecularGain"]
    caustic_gain = float(get_val(cfg, "caustics", "causticIntensity"))

    acc = {
        "E03_upper_cavity": [0.0, 0.0, 0.0],
        "E05_emerald": [0.0, 0.0, 0.0],
        "E06_sapphire": [0.0, 0.0, 0.0],
        "E07_aqua": [0.0, 0.0, 0.0],
    }
    disk_cnt = 0
    overlap_e03_e06 = 0
    overlap_e06_e07 = 0
    overlap_e05_e07 = 0
    unclassified_cnt = 0
    flare_cnt = 0

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
            eta = 1.0 / 1.45
            k_refr = max(0.0, 1.0 - eta * eta * (1.0 - ndotv * ndotv))
            fac = eta * ndotv - math.sqrt(k_refr)
            rx, ry, rz = fac * nx, fac * ny, -eta + fac * nz
            rl = math.sqrt(rx * rx + ry * ry + rz * rz)
            rx, ry, rz = rx / rl, ry / rl, rz / rl

            ox, oy, oz = nx * 0.945, ny * 0.945, nz * 0.945
            b_dot = ox * rx + oy * ry + oz * rz
            c_rad = (ox * ox + oy * oy + oz * oz) - 0.945 * 0.945
            t_exit = max(0.05, -b_dot + math.sqrt(max(0.0, b_dot * b_dot - c_rad)))
            dt = t_exit / steps

            acc_r = acc_g = acc_b = acc_a = 0.0
            opt_d = 0.0
            liq_path = 0.0

            for s in range(steps):
                if acc_a > 0.95:
                    break
                tm = (s + 0.5) * dt
                mx, my, mz = ox + rx * tm, oy + ry * tm, oz + rz * tm
                phi = cavity_phi_v32(heights, mx, my, mz, y_off, dome_dip, wall_climb, 0.0)
                sub = 1.0 - smoothstep(-0.015, 0.015, phi)
                if sub > 0.01:
                    seg = dt * sub
                    liq_path += seg
                    vr_s, vg_s, vb_s, va, ridge = sample_fn(mx, my, mz)
                    ld = va * sub
                    if ld > 0.004:
                        sigma = ld * dens_scale * seg
                        sa = 1.0 - math.exp(-sigma)
                        vr, vg, vb = vr_s ** 2.2, vg_s ** 2.2, vb_s ** 2.2
                        key_p = clamp01((-0.48 * mx + 0.76 * my + 0.44 * mz) * 0.5 + 0.5)
                        atten = math.exp(-opt_d * 0.70)
                        fold_hi = (ridge * ridge) * fold_spec * key_p * 0.22
                        lit = 0.66 + 0.62 * key_p * atten
                        w_step = (1.0 - acc_a) * sa
                        acc_r += w_step * (vr * lit + 0.05 * fold_hi)
                        acc_g += w_step * (vg * lit + 0.19 * fold_hi)
                        acc_b += w_step * (vb * lit + 0.14 * fold_hi)
                        acc_a += w_step
                        opt_d += sigma

            bg_r, bg_g, bg_b = 0.006, 0.032, 0.036
            wt_r = math.exp(-liq_path * bl_r * 0.85)
            wt_g = math.exp(-liq_path * bl_g * 0.85)
            wt_b = math.exp(-liq_path * bl_b * 0.85)
            bg_w = 1.0 - acc_a * 0.86

            # Decoupled optical underwater caustics in lower basin (subtle cyan sheen without dragging E07 centroid downward)
            fx, fy, fz = ox + rx * (t_exit * 0.74), oy + ry * (t_exit * 0.74), oz + rz * (t_exit * 0.74)
            f_sub = 1.0 - smoothstep(-0.08, 0.06, cavity_phi_v32(heights, fx, fy, fz, y_off, dome_dip, wall_climb, 0.0))
            lower_m = (1.0 - smoothstep(-0.45, 0.16, ny)) * (1.0 - smoothstep(0.20, 0.56, abs(nx - 0.02)))
            c_wave = (0.5 + 0.5 * math.sin(fx * 11.0 + fz * 8.0)) * (0.5 + 0.5 * math.cos(fx * 7.5 - fz * 10.5))
            c_str = (0.35 + 0.65 * (c_wave ** 1.6)) * lower_m * f_sub * smoothstep(0.04, 0.36, liq_path) * caustic_gain * 0.12

            ir = bg_r * wt_r * bg_w + acc_r + 0.032 * c_str
            ig = bg_g * wt_g * bg_w + acc_g + 0.250 * c_str
            ib = bg_b * wt_b * bg_w + acc_b + 0.380 * c_str

            # Localized 10:30 gold crown refraction (strictly upper-left dome, synchronized with Stage B & E)
            vn_l = math.hypot(nx, ny) + 1e-5
            ul_dot = (nx / vn_l) * (-0.58) + (ny / vn_l) * 0.815
            ul_sec = (
                smoothstep(0.80, 0.96, ul_dot)
                * smoothstep(0.42, 0.86, math.hypot(nx, ny))
                * (1.0 - smoothstep(-0.16, -0.10, nx))
                * smoothstep(0.35, 0.44, ny)
            )
            gold_raw = ul_sec * (0.42 + 0.58 * ((1.0 - ndotv) ** 1.5))
            gold_on = smoothstep(0.28, 0.42, gold_raw)
            cyan_keep = (1.0 - gold_on) * (1.0 - gold_on)

            # Embedded crystal lens + outer upper-dome crystal transmission (mirrors CoreFluidShaders.kt)
            if use_lens and ny > 0.14:
                air_blend = clamp01(1.0 - smoothstep(0.04, 0.28, liq_path))
                hit_l, _, _, thick = intersect_crystal_lens(
                    ox, oy, oz, rx, ry, rz, t_exit, lx, ly, lz, lrx, lry, lrz
                )
                if hit_l:
                    wall_ring = math.exp(-((thick - l_wall * 4.5) / max(0.06, l_wall * 3.8)) ** 2)
                    deep_pocket = smoothstep(l_wall * 5.2, l_wall * 10.5, thick)
                    dp = deep_pocket * (0.74 + 0.26 * air_blend)
                    ir = ir * (1.0 - dp * 0.88) + 0.006 * wall_ring * rim_refr * cyan_keep
                    ig = ig * (1.0 - dp * 0.84) + 0.135 * wall_ring * rim_refr * cyan_keep + tint_gain * 0.09 * (1.0 - dp) * cyan_keep
                    ib = ib * (1.0 - dp * 0.80) * cyan_keep + 0.365 * wall_ring * rim_refr * cyan_keep + tint_gain * 0.22 * (1.0 - dp) * cyan_keep
                elif ig <= ib * 0.98:
                    # Upper crystal dome outside lens:
                    # - Transparent air dome OR low-density clear crystal window (`acc_a` low) -> luminous crystal glass
                    #   (luma > 122, neither false E03 cavity nor false upper E06 sapphire!).
                    # - Dense submerged sapphire (`acc_a` high) -> preserves cobalt sb >= 115 (E06, not dark E03 cavity).
                    clear_crystal = max(air_blend, 1.0 - smoothstep(0.10, 0.36, acc_a))
                    if clear_crystal > 0.28:
                        cry = smoothstep(0.28, 0.62, clear_crystal) * cyan_keep
                        ir = max(ir, 0.46 * cry)
                        ig = max(ig, 0.47 * cry)
                        ib = max(ib, 0.45 * cry)
                    else:
                        ib = max(ib, 0.215 * cyan_keep)
                        ig = max(ig, 0.095 * cyan_keep)
                        ir = max(ir, 0.012 * cyan_keep)

            # Outer double-wall glass Fresnel rim & luminous crystal specular (Master specular tint ratio ~[0.90, 0.96, 0.88])
            fresnel_p = float(get_val(cfg, "outerGlass", "fresnelPower"))
            fresnel_g = float(get_val(cfg, "outerGlass", "fresnelGain"))
            razor_g = float(get_val(cfg, "outerGlass", "razorRimGain"))
            fres_rim = ((1.0 - ndotv) ** max(3.5, fresnel_p * 0.72)) * fresnel_g
            razor_rim = smoothstep(0.82, 0.94, math.sqrt(r2)) * razor_g * 0.44
            rim_luma = fres_rim + razor_rim
            ir += rim_luma * 0.90
            ig += rim_luma * 0.96
            ib += rim_luma * 0.88

            gold_ray = gold_raw * gold_on * gold_pen
            ir += gold_ray * 1.65
            ig += gold_ray * 0.96
            ib = ib * cyan_keep + gold_ray * 0.02

            sr, sg, sb = _encode_aces_srgb_u8(ir * 1.06), _encode_aces_srgb_u8(ig * 1.06), _encode_aces_srgb_u8(ib * 1.06)
            luma = 0.2126 * sr + 0.7152 * sg + 0.0722 * sb

            # EXACT unified pixel classifier identical to reference_measurements.py
            is_flare = (nx < -0.10 and ny > 0.35 and sr > 155 and sr > sb * 1.18 and sg > 125)
            is_emerald = (sg > 52 and sg > sb * 1.07 and sg >= sr * 1.02 and not is_flare)
            is_aqua = (sg > 118 and sb > 142 and sb >= sg * 0.90 and sr < 150)
            is_sapphire = (sb >= sg * 0.92 and sb > sr + 18 and luma < 122 and not is_emerald)
            is_cavity = (ny > 0.14 and luma < 86 and not is_emerald and not is_flare and sb < 112 and sg < 102)

            if is_flare:
                flare_cnt += 1
            if is_cavity:
                acc["E03_upper_cavity"][0] += 1.0
                acc["E03_upper_cavity"][1] += nx
                acc["E03_upper_cavity"][2] += ny
            if is_emerald:
                acc["E05_emerald"][0] += 1.0
                acc["E05_emerald"][1] += nx
                acc["E05_emerald"][2] += ny
            if is_sapphire:
                acc["E06_sapphire"][0] += 1.0
                acc["E06_sapphire"][1] += nx
                acc["E06_sapphire"][2] += ny
            if is_aqua:
                acc["E07_aqua"][0] += 1.0
                acc["E07_aqua"][1] += nx
                acc["E07_aqua"][2] += ny

            if is_cavity and is_sapphire:
                overlap_e03_e06 += 1
            if is_sapphire and is_aqua:
                overlap_e06_e07 += 1
            if is_emerald and is_aqua:
                overlap_e05_e07 += 1
            if not (is_flare or is_cavity or is_emerald or is_sapphire or is_aqua):
                unclassified_cnt += 1

    tgt_map = {
        "E03_upper_cavity": targets["targets"]["E03_upper_cavity"],
        "E05_emerald": targets["targets"]["E05_emerald_folds"],
        "E06_sapphire": targets["targets"]["E06_sapphire_valleys"],
        "E07_aqua": targets["targets"]["E07_aqua_pocket"],
    }

    metrics: dict[str, Any] = {}
    loss = 0.0
    for k, (cnt, sx, sy) in acc.items():
        sim_a = cnt / max(1.0, float(disk_cnt))
        sim_cx = sx / max(1.0, cnt)
        sim_cy = sy / max(1.0, cnt)
        tgt_a = float(tgt_map[k]["areaRatio"])
        tgt_cx, tgt_cy = float(tgt_map[k]["centroidNorm"][0]), float(tgt_map[k]["centroidNorm"][1])
        a_err = abs(sim_a - tgt_a) / max(1e-4, tgt_a)
        c_dist = math.hypot(sim_cx - tgt_cx, sim_cy - tgt_cy) * 0.5
        metrics[k] = {
            "targetAreaRatio": round(tgt_a, 5),
            "simAreaRatio": round(sim_a, 5),
            "relativeAreaError": round(a_err, 4),
            "targetCentroid": [round(tgt_cx, 4), round(tgt_cy, 4)],
            "simCentroid": [round(sim_cx, 4), round(sim_cy, 4)],
            "centroidDistD": round(c_dist, 4),
        }
        w_k = 1.35 if k in ("E05_emerald", "E06_sapphire", "E07_aqua") else 1.00
        # Barrier penalty strongly pushes any violated hard gate strictly inside (<= 0.15, <= 0.04D) with margin
        area_excess = max(0.0, a_err - 0.10)
        cent_excess = max(0.0, c_dist - 0.030)
        loss += w_k * (2.5 * a_err + 6.5 * c_dist + 28.0 * area_excess * area_excess + 160.0 * cent_excess * cent_excess)

    return {
        "evaluationSource": evaluation_source,
        "gridN": grid_n,
        "steps": steps,
        "diskSampleCount": disk_cnt,
        "loss": round(loss, 5),
        "regions": metrics,
        "classifierStats": {
            "flarePixels": flare_cnt,
            "overlapE03AndE06": overlap_e03_e06,
            "overlapE06AndE07": overlap_e06_e07,
            "overlapE05AndE07": overlap_e05_e07,
            "unclassifiedDiskPixels": unclassified_cnt,
            "unclassifiedRatio": round(unclassified_cnt / max(1, disk_cnt), 4),
        },
    }


def evaluate_volume_projection(
    cfg: dict[str, Any],
    targets: dict[str, Any],
    regions: dict[str, Any],
    vp: dict[str, float],
    grid_n: int = 84,
    steps: int = 16,
) -> dict[str, Any]:
    ref_grids = {
        k: regions["interiorRegions"][k]["spatialGrid32x32"]
        for k in ("E05_emerald", "E06_sapphire", "E07_aqua", "E08_caustics")
    }
    master_rgb = {
        k: regions["interiorRegions"][k]["meanSrgb"]
        for k in ("E05_emerald", "E06_sapphire", "E07_aqua")
    }

    def sample_analytical(mx: float, my: float, mz: float) -> tuple[float, float, float, float, float]:
        return evaluate_canonical_voxel(mx, my, mz, ref_grids, master_rgb, vp)

    return _evaluate_raymarch_core(
        cfg=cfg,
        targets=targets,
        vp=vp,
        sample_fn=sample_analytical,
        grid_n=grid_n,
        steps=steps,
        evaluation_source="CANONICAL_ANALYTICAL_FIELD",
    )


def evaluate_decoded_webp_projection(
    cfg: dict[str, Any],
    targets: dict[str, Any],
    decoded_slices: list[bytes],
    grid_n: int = 96,
    steps: int = 16,
) -> dict[str, Any]:
    """
    Independent verifier: projects ONLY the actual decoded 32 Lossless RGBA WebP files
    via trilinear 3D texture sampling (mirroring GL_TEXTURE_3D in CoreFluidShaders.kt).
    Does NOT call evaluate_canonical_voxel.
    """
    vp = extract_volume_params(cfg)

    def sample_webp(mx: float, my: float, mz: float) -> tuple[float, float, float, float, float]:
        r, g, b, a = sample_trilinear_volume_3d(decoded_slices, mx, my, mz)
        # Estimate local thin-sheet fold ridge from 3D density contrast (matches CoreFluidShaders gradient cue)
        ridge = clamp01((a - 0.14) * 1.4)
        return r, g, b, a, ridge

    return _evaluate_raymarch_core(
        cfg=cfg,
        targets=targets,
        vp=vp,
        sample_fn=sample_webp,
        grid_n=grid_n,
        steps=steps,
        evaluation_source="DECODED_WEBP_32_SLICES_TRILINEAR",
    )


def benchmark_slice_depth_32_vs_64(
    regions: dict[str, Any],
    vp: dict[str, float],
) -> dict[str, Any]:
    ref_grids = {
        k: regions["interiorRegions"][k]["spatialGrid32x32"]
        for k in ("E05_emerald", "E06_sapphire", "E07_aqua", "E08_caustics")
    }
    master_rgb = {
        k: regions["interiorRegions"][k]["meanSrgb"]
        for k in ("E05_emerald", "E06_sapphire", "E07_aqua")
    }

    def trilinear_z_error(depth: int) -> float:
        err_sum = 0.0
        n = 0
        for x in (-0.52, -0.18, 0.22, 0.56):
            for y in (-0.54, -0.12, 0.26):
                for s in range(31):
                    z = -0.82 + (s / 30.0) * 1.64
                    if x * x + y * y + z * z > 0.88:
                        continue
                    _, _, _, a_exact, _ = evaluate_canonical_voxel(x, y, z, ref_grids, master_rgb, vp)
                    fz = ((z + 1.0) * 0.5) * (depth - 1)
                    i0 = max(0, min(depth - 2, int(math.floor(fz))))
                    t = fz - i0
                    z0 = ((i0 + 0.5) / depth) * 2.0 - 1.0
                    z1 = ((i0 + 1.5) / depth) * 2.0 - 1.0
                    _, _, _, a0, _ = evaluate_canonical_voxel(x, y, z0, ref_grids, master_rgb, vp)
                    _, _, _, a1, _ = evaluate_canonical_voxel(x, y, z1, ref_grids, master_rgb, vp)
                    a_interp = a0 + (a1 - a0) * t
                    err_sum += abs(a_exact - a_interp)
                    n += 1
        return err_sum / max(1, n)

    err32 = trilinear_z_error(32)
    err64 = trilinear_z_error(64)
    # V3.2's runtime and release contract fixes the canonical texture at 256x256x32.
    # Keep the 64-slice result as a quality comparison, but never emit an asset/runtime
    # depth that the generator, manifest contract, and Android loader do not support.
    selected_depth = 32
    return {
        "depth32": {
            "dimensions": "256x256x32",
            "gpuVramMiB": 8.0,
            "meanTrilinearAlphaError": round(err32, 6),
        },
        "depth64": {
            "dimensions": "256x256x64",
            "gpuVramMiB": 16.0,
            "meanTrilinearAlphaError": round(err64, 6),
        },
        "selectedDepth": selected_depth,
        "rationale": (
            f"The release contract fixes 256x256x32 (8.0 MiB) and the canonical generator emits exactly 32 slices. "
            f"The measured mean Z interpolation errors are {err32:.5f} at 32 slices and {err64:.5f} at 64 slices; "
            "the 64-slice value is retained for comparison only and is not selected because it would violate the "
            "manifest, loader, and APK asset contract."
        ),
    }


def fit_volume_structure(
    cfg: dict[str, Any],
    targets: dict[str, Any],
    regions: dict[str, Any],
) -> dict[str, Any]:
    """
    Stage C optimizer: solves FluidFoldParameters using the canonical 3D fluid volume model so
    E03, E05, E06, E07 simultaneously converge strictly within <= 15% relative area error and
    <= 0.04D centroid error under the exact unified pixel classifier.
    """
    vp = extract_volume_params(cfg)
    initial_params = {k: round(v, 4) for k, v in vp.items()}

    # Deterministic coupled coarse search for the region competition that previously trapped
    # coordinate descent: aqua density/anchor/brightness compete with emerald green response
    # and threshold. Keep this grid small, fixed, and reproducible, then independently refine
    # at 32/8, 44/12 and 56/16 before the required 96/16 verification.
    coupled_grid = {
        "aquaDensityGain": (0.08, 0.16, 0.24),
        "aquaAnchorWeight": (0.70, 1.00),
        "aquaBrightnessGain": (0.55, 0.70),
        "emeraldGreenBoost": (0.85, 1.06),
        "emeraldThreshold": (0.12, 0.20),
    }
    coupled_keys = tuple(coupled_grid)
    coarse_candidates: list[tuple[float, dict[str, float], dict[str, Any]]] = []
    for values in itertools.product(*(coupled_grid[key] for key in coupled_keys)):
        trial = dict(vp)
        trial.update({key: float(value) for key, value in zip(coupled_keys, values)})
        result = evaluate_volume_projection(cfg, targets, regions, trial, grid_n=32, steps=8)
        coarse_candidates.append((float(result["loss"]), trial, result))
    coarse_candidates.sort(key=lambda item: (item[0], tuple(item[1][key] for key in coupled_keys)))
    _, vp, best = coarse_candidates[0]

    def history_item(iteration: int, result: dict[str, Any]) -> dict[str, Any]:
        regs = result["regions"]
        return {
            "iter": iteration,
            "loss": result["loss"],
            "E03": regs["E03_upper_cavity"]["relativeAreaError"],
            "E03_c": regs["E03_upper_cavity"]["centroidDistD"],
            "E05": regs["E05_emerald"]["relativeAreaError"],
            "E05_c": regs["E05_emerald"]["centroidDistD"],
            "E06": regs["E06_sapphire"]["relativeAreaError"],
            "E06_c": regs["E06_sapphire"]["centroidDistD"],
            "E07": regs["E07_aqua"]["relativeAreaError"],
            "E07_c": regs["E07_aqua"]["centroidDistD"],
        }

    history = [
        {"stage": "COUPLED_COARSE_GRID", "candidateCount": len(coarse_candidates), **history_item(0, best)}
    ]

    it = 0
    for scale, g_n, n_st, max_iters in ((0.55, 32, 8, 2), (0.24, 44, 12, 2), (0.10, 56, 16, 1)):
        best = evaluate_volume_projection(cfg, targets, regions, vp, grid_n=g_n, steps=n_st)
        for _ in range(max_iters):
            improved = False
            search_specs = [
                ("baseDensity", 0.012 * scale, 0.01, 0.08),
                ("foldIso", 0.055 * scale, -0.10, 0.45),
                ("foldThickness", 0.025 * scale, 0.06, 0.24),
                ("emeraldAnchorWeight", 0.18 * scale, 0.80, 2.40),
                ("emeraldThreshold", 0.045 * scale, 0.04, 0.42),
                ("emeraldGreenBoost", 0.08 * scale, 0.75, 1.45),
                ("emeraldFoldGain", 0.08 * scale, 0.25, 1.10),
                ("sapphireDepthDarken", 0.10 * scale, 0.35, 1.05),
                ("sapphireAnchorWeight", 0.18 * scale, 0.80, 2.40),
                ("sapphireAbsorptionGain", 0.06 * scale, 0.08, 0.55),
                ("aquaBrightnessGain", 0.10 * scale, 0.55, 1.35),
                ("aquaAnchorWeight", 0.16 * scale, 0.70, 2.20),
                ("aquaDensityGain", 0.06 * scale, 0.08, 0.60),
                ("aquaUpperGateY", 0.05 * scale, -0.25, 0.25),
                ("densityScale", 0.14 * scale, 0.80, 2.40),
                ("causticDecoupleWeight", 0.03 * scale, 0.0, 0.22),
            ]
            for k, st, lo, hi in search_specs:
                for sgn in (-1.0, 1.0):
                    trial = dict(vp)
                    trial[k] = max(lo, min(hi, trial[k] + sgn * st))
                    res = evaluate_volume_projection(cfg, targets, regions, trial, grid_n=g_n, steps=n_st)
                    if res["loss"] < best["loss"] - 1e-5:
                        vp = trial
                        best = res
                        improved = True
            it += 1
            if not improved:
                break
        history.append(history_item(it, best))

    # Final high-resolution replay for the refined solution and several best coarse coupled
    # vectors. This resolves low-resolution classifier quantization and selects against the
    # actual required 96x96 / 16-step metric set.
    high_resolution_candidates: list[dict[str, float]] = [dict(vp)]
    for _coarse_loss, coarse_vp, _coarse_result in coarse_candidates[:4]:
        candidate = dict(vp)
        candidate.update({key: coarse_vp[key] for key in coupled_keys})
        if candidate not in high_resolution_candidates:
            high_resolution_candidates.append(candidate)
    high_resolution_audit: list[dict[str, Any]] = []
    final_eval: dict[str, Any] | None = None
    for candidate in high_resolution_candidates:
        result = evaluate_volume_projection(cfg, targets, regions, candidate, grid_n=96, steps=16)
        high_resolution_audit.append({
            "loss": result["loss"],
            "coupledParams": {key: round(candidate[key], 4) for key in coupled_keys},
            "regions": result["regions"],
        })
        if final_eval is None or result["loss"] < final_eval["loss"]:
            vp = candidate
            final_eval = result
    assert final_eval is not None
    depth_bench = benchmark_slice_depth_32_vs_64(regions, vp)

    # Save fitted parameters into MASTER_FIDELITY_V32
    set_val(cfg, "fluidVolume", "depth", int(depth_bench["selectedDepth"]))
    for k in ("foldIso", "foldThickness", "baseDensity", "densityScale", "foldNormalPerturbation", "foldRefraction", "foldSpecularGain"):
        set_val(cfg, "fluidVolume", k, round(vp[k], 4))
    set_val(cfg, "fluidVolume", "foldCount", int(round(vp["foldCount"])))
    for k in ("emeraldFoldGain", "emeraldAnchorWeight", "emeraldGreenBoost", "emeraldThreshold"):
        set_val(cfg, "emeraldFolds", k, round(vp[k], 4))
    for k in ("sapphireAbsorptionGain", "sapphireAnchorWeight", "sapphireDepthDarken", "beerLambertR", "beerLambertG", "beerLambertB"):
        set_val(cfg, "sapphireValleys", k, round(vp[k], 4))
    for k in ("aquaDensityGain", "aquaAnchorWeight", "aquaBrightnessGain", "aquaUpperGateY"):
        set_val(cfg, "aquaRegion", k, round(vp[k], 4))
    set_val(cfg, "caustics", "causticDecoupleWeight", round(vp["causticDecoupleWeight"], 4))

    regs = final_eval["regions"]
    failed_items: list[str] = []
    for k in ("E03_upper_cavity", "E05_emerald", "E06_sapphire", "E07_aqua"):
        if regs[k]["relativeAreaError"] > 0.15:
            failed_items.append(f"{k}.relativeAreaError={regs[k]['relativeAreaError']:.4f} > 0.15")
        if regs[k]["centroidDistD"] > 0.04:
            failed_items.append(f"{k}.centroidDistD={regs[k]['centroidDistD']:.4f} > 0.04")
    gate_pass = len(failed_items) == 0

    return {
        "stage": "STAGE_C_VOLUME_FIT",
        "pass": gate_pass,
        "canonicalModelSeed": CANONICAL_SEED,
        "lossFinal": final_eval["loss"],
        "regions": regs,
        "classifierStats": final_eval["classifierStats"],
        "failedItems": failed_items,
        "initialParams": initial_params,
        "finalParams": {k: round(v, 4) for k, v in vp.items()},
        "sliceDepthComparison": depth_bench,
        "history": history,
        "coarseJointSearch": {
            "deterministic": True,
            "gridN": 32,
            "steps": 8,
            "candidateCount": len(coarse_candidates),
            "winnerParameters": {key: round(vp[key], 4) for key in coupled_keys},
        },
        "highResolutionCandidateAudit": high_resolution_audit,
    }
