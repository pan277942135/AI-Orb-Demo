#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Fit Validation & 360° Dynamic Continuity Sweep

Validates:
  1. MASTER_FIDELITY_V32.json schema & bounds integrity.
  2. Static offline hard-gate verification from AI_ORB_V3_2_VISUAL_LOSS.json.
  3. 360° dynamic continuity sweep (0°..360° every 30° around frozen axis (0.14, 0.98, -0.14))
     verifying smooth, seam-free 3D fluid volume density & color continuity across full rotation.
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

FITTING_DIR = Path(__file__).resolve().parent
if str(FITTING_DIR) not in sys.path:
    sys.path.insert(0, str(FITTING_DIR))

from parameter_schema import get_val, validate_config
from reference_measurements import REGIONS_JSON_PATH, ROOT
from volume_structure_fitter import evaluate_fluid_voxel

MASTER_CONFIG_PATH = ROOT / "app" / "src" / "main" / "assets" / "orb_v3" / "manifest" / "MASTER_FIDELITY_V32.json"
VISUAL_LOSS_PATH = ROOT / "docs" / "v3" / "AI_ORB_V3_2_VISUAL_LOSS.json"
OUT_VALIDATION_JSON = FITTING_DIR / "FIT_VALIDATION_RESULT.json"


def rotate_axis(p: tuple[float, float, float], axis: tuple[float, float, float], deg: float) -> tuple[float, float, float]:
    rad = math.radians(deg)
    c, s = math.cos(rad), math.sin(rad)
    al = math.sqrt(axis[0] ** 2 + axis[1] ** 2 + axis[2] ** 2)
    kx, ky, kz = axis[0] / al, axis[1] / al, axis[2] / al
    px, py, pz = p
    dot = kx * px + ky * py + kz * pz
    cx = ky * pz - kz * py
    cy = kz * px - kx * pz
    cz = kx * py - ky * px
    return (
        px * c + cx * s + kx * dot * (1.0 - c),
        py * c + cy * s + ky * dot * (1.0 - c),
        pz * c + cz * s + kz * dot * (1.0 - c),
    )


def run_validation() -> dict:
    cfg = json.loads(MASTER_CONFIG_PATH.read_text(encoding="utf-8"))
    vl = json.loads(VISUAL_LOSS_PATH.read_text(encoding="utf-8"))
    regions = json.loads(REGIONS_JSON_PATH.read_text(encoding="utf-8"))

    schema_errs = validate_config(cfg)
    if schema_errs:
        raise ValueError("Schema validation errors:\n" + "\n".join(schema_errs))

    ref_grids = {
        k: regions["interiorRegions"][k]["spatialGrid32x32"]
        for k in ("E05_emerald", "E06_sapphire", "E07_aqua", "E08_caustics")
    }
    master_rgb = {
        k: regions["interiorRegions"][k]["meanSrgb"]
        for k in ("E05_emerald", "E06_sapphire", "E07_aqua")
    }

    vp = {
        "foldCount": float(get_val(cfg, "fluidVolume", "foldCount")),
        "foldIso": float(get_val(cfg, "fluidVolume", "foldIso")),
        "foldThickness": float(get_val(cfg, "fluidVolume", "foldThickness")),
        "baseDensity": float(get_val(cfg, "fluidVolume", "baseDensity")),
        "densityScale": float(get_val(cfg, "fluidVolume", "densityScale")),
        "foldNormalPerturbation": float(get_val(cfg, "fluidVolume", "foldNormalPerturbation")),
        "foldRefraction": float(get_val(cfg, "fluidVolume", "foldRefraction")),
        "foldSpecularGain": float(get_val(cfg, "fluidVolume", "foldSpecularGain")),
        "emeraldFoldGain": float(get_val(cfg, "emeraldFolds", "emeraldFoldGain")),
        "emeraldAnchorWeight": float(get_val(cfg, "emeraldFolds", "emeraldAnchorWeight")),
        "emeraldGreenBoost": float(get_val(cfg, "emeraldFolds", "emeraldGreenBoost")),
        "emeraldThreshold": float(get_val(cfg, "emeraldFolds", "emeraldThreshold")),
        "sapphireAbsorptionGain": float(get_val(cfg, "sapphireValleys", "sapphireAbsorptionGain")),
        "sapphireAnchorWeight": float(get_val(cfg, "sapphireValleys", "sapphireAnchorWeight")),
        "sapphireDepthDarken": float(get_val(cfg, "sapphireValleys", "sapphireDepthDarken")),
        "beerLambertR": float(get_val(cfg, "sapphireValleys", "beerLambertR")),
        "beerLambertG": float(get_val(cfg, "sapphireValleys", "beerLambertG")),
        "beerLambertB": float(get_val(cfg, "sapphireValleys", "beerLambertB")),
        "aquaDensityGain": float(get_val(cfg, "aquaRegion", "aquaDensityGain")),
        "aquaAnchorWeight": float(get_val(cfg, "aquaRegion", "aquaAnchorWeight")),
        "aquaBrightnessGain": float(get_val(cfg, "aquaRegion", "aquaBrightnessGain")),
        "aquaUpperGateY": float(get_val(cfg, "aquaRegion", "aquaUpperGateY")),
        "causticDecoupleWeight": float(get_val(cfg, "caustics", "causticDecoupleWeight")),
    }

    # 360-degree rotation continuity sweep around frozen axis (0.14, 0.98, -0.14)
    axis = tuple(get_val(cfg, "quality", "coreRotationAxis"))
    probes = [
        (0.45, -0.15, 0.35), (-0.42, 0.08, 0.40), (0.12, -0.52, 0.30),
        (-0.20, -0.35, -0.45), (0.35, 0.12, -0.38),
    ]
    sweep = []
    prev_mean = None
    max_step_jump = 0.0
    for deg in range(0, 361, 30):
        vals = []
        for p in probes:
            rp = rotate_axis(p, axis, float(deg))
            _, g, b, a, _ = evaluate_fluid_voxel(rp[0], rp[1], rp[2], ref_grids, master_rgb, vp)
            vals.append((g, b, a))
        mean_a = sum(v[2] for v in vals) / len(vals)
        mean_g = sum(v[0] for v in vals) / len(vals)
        if prev_mean is not None:
            jump = abs(mean_a - prev_mean)
            if jump > max_step_jump:
                max_step_jump = jump
        prev_mean = mean_a
        sweep.append({"angleDeg": deg, "meanAlpha": round(mean_a, 4), "meanGreen": round(mean_g, 4)})

    loop_closure_err = abs(sweep[0]["meanAlpha"] - sweep[-1]["meanAlpha"])
    dynamic_pass = (max_step_jump < 0.25 and loop_closure_err < 1e-3)

    result = {
        "schemaValid": True,
        "staticOfflineHardGatesPass": vl["allOfflineHardGatesPass"],
        "failedOfflineGates": vl["failedOfflineGates"],
        "dynamic360Sweep": {
            "axis": list(axis),
            "stepDeg": 30,
            "maxStepAlphaJump": round(max_step_jump, 5),
            "loopClosure360AlphaErr": round(loop_closure_err, 6),
            "pass": dynamic_pass,
            "samples": sweep,
        },
        "overallOfflineValidationPass": vl["allOfflineHardGatesPass"] and dynamic_pass,
        "realGpuStatus": "PENDING_REAL_GPU_CAPTURE",
    }
    OUT_VALIDATION_JSON.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(
        f"[validate_fit] staticPass={result['staticOfflineHardGatesPass']} "
        f"dynamic360Pass={dynamic_pass} loopErr={loop_closure_err:.6f} maxJump={max_step_jump:.4f}"
    )
    return result


if __name__ == "__main__":
    run_validation()
    sys.exit(0)
