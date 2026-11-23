#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Unified Single-Source Runtime Parameter Schema (`MASTER_FIDELITY_V32.json`)

Every parameter carries full provenance and bounds:
  {name, unit, default, min, max, optimizable, source, fittedValue}

Used by:
  - Offline fitting modules (camera, cavity, volume, resident, optics)
  - WebP 3D volume generator (generate_orb_volume.py)
  - Android runtime (MasterFidelityV32.kt -> AIOrbScene / OrbFluidVolumeRenderer / AIOrbOrbitRibbons)
"""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

SCHEMA_VERSION = "AI_ORB_V3_2_MASTER_FIDELITY_1_0"
EXPECTED_MASTER_SHA256 = "910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122"


def param(
    name: str,
    unit: str,
    default: Any,
    min_val: Any,
    max_val: Any,
    optimizable: bool,
    source: str,
    fitted_value: Any | None = None,
) -> dict[str, Any]:
    return {
        "name": name,
        "unit": unit,
        "default": default,
        "min": min_val,
        "max": max_val,
        "optimizable": optimizable,
        "source": source,
        "fittedValue": default if fitted_value is None else fitted_value,
    }


def build_default_schema() -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "referenceMaster": {
            "path": "public/assets/aistudio/____________910d1531.png",
            "sha256": EXPECTED_MASTER_SHA256,
            "width": 1254,
            "height": 1254,
            "immutable": True,
        },
        "camera": {
            "fovYDeg": param("fovYDeg", "deg", 42.0, 35.0, 50.0, False, "FROZEN_V3_CONTRACT"),
            "coreScale": param("coreScale", "world", 1.0, 1.0, 1.0, False, "FROZEN_V3_CONTRACT"),
            "coreShellScale": param("coreShellScale", "world", 0.80, 0.80, 0.80, False, "FROZEN_V3_CONTRACT"),
            "ribbonHugScale": param("ribbonHugScale", "ratio", 0.87, 0.87, 0.87, False, "FROZEN_V3_CONTRACT"),
            "visualBoundRadius": param("visualBoundRadius", "world", 1.54, 1.30, 1.85, True, "STAGE_A_CAMERA_FIT"),
            "cameraDistance": param("cameraDistance", "world", 4.08, 3.40, 4.90, True, "STAGE_A_CAMERA_FIT"),
            "targetOffsetX": param("targetOffsetX", "world", 0.0, -0.12, 0.12, True, "STAGE_A_CAMERA_FIT"),
            "targetOffsetY": param("targetOffsetY", "world", 0.0, -0.20, 0.20, True, "STAGE_A_CAMERA_FIT"),
            "targetSphereDiameterRatio": param("targetSphereDiameterRatio", "ratio", 0.5199, 0.48, 0.56, False, "G0_MASTER_LOCK"),
        },
        "outerGlass": {
            "iorGlass": param("iorGlass", "ior", 1.45, 1.36, 1.54, True, "STAGE_E_OPTICS_FIT"),
            "glassWallThickness": param("glassWallThickness", "R", 0.078, 0.045, 0.115, True, "STAGE_E_OPTICS_FIT"),
            "fresnelPower": param("fresnelPower", "exp", 6.2, 4.0, 8.5, True, "STAGE_E_OPTICS_FIT"),
            "fresnelGain": param("fresnelGain", "gain", 0.48, 0.15, 0.85, True, "STAGE_E_OPTICS_FIT"),
            "razorRimGain": param("razorRimGain", "gain", 0.88, 0.30, 1.40, True, "STAGE_E_OPTICS_FIT"),
            "innerRingGain": param("innerRingGain", "gain", 0.32, 0.05, 0.70, True, "STAGE_E_OPTICS_FIT"),
            "grooveDarkening": param("grooveDarkening", "gain", 0.54, 0.20, 0.80, True, "STAGE_E_OPTICS_FIT"),
            "envReflectionCap": param("envReflectionCap", "gain", 0.18, 0.04, 0.28, True, "STAGE_E_OPTICS_FIT"),
            "upperHazeSuppress": param("upperHazeSuppress", "ratio", 0.94, 0.75, 1.0, True, "STAGE_E_OPTICS_FIT"),
            "crownGoldStrength": param("crownGoldStrength", "gain", 1.12, 0.40, 2.00, True, "STAGE_E_OPTICS_FIT"),
        },
        "innerCavity": {
            "selectedModel": param("selectedModel", "enum", "CANDIDATE_B_CRYSTAL_LENS", "CANDIDATE_A_AIR_LIQUID", "CANDIDATE_B_CRYSTAL_LENS", True, "STAGE_B_CAVITY_FIT"),
            "meniscusYOffset": param("meniscusYOffset", "R", 0.0, -0.18, 0.24, True, "STAGE_B_CAVITY_FIT"),
            "domeDepthDip": param("domeDepthDip", "R", 0.14, 0.02, 0.28, True, "STAGE_B_CAVITY_FIT"),
            "sideWallClimb": param("sideWallClimb", "R", 0.048, 0.0, 0.14, True, "STAGE_B_CAVITY_FIT"),
            "lensCenterX": param("lensCenterX", "R", 0.14, -0.25, 0.35, True, "STAGE_B_CAVITY_FIT"),
            "lensCenterY": param("lensCenterY", "R", 0.48, 0.24, 0.76, True, "STAGE_B_CAVITY_FIT"),
            "lensCenterZ": param("lensCenterZ", "R", 0.08, -0.20, 0.35, True, "STAGE_B_CAVITY_FIT"),
            "lensRadiusX": param("lensRadiusX", "R", 0.52, 0.28, 0.92, True, "STAGE_B_CAVITY_FIT"),
            "lensRadiusY": param("lensRadiusY", "R", 0.24, 0.12, 0.52, True, "STAGE_B_CAVITY_FIT"),
            "lensRadiusZ": param("lensRadiusZ", "R", 0.50, 0.28, 0.88, True, "STAGE_B_CAVITY_FIT"),
            "lensWallThickness": param("lensWallThickness", "R", 0.055, 0.02, 0.12, True, "STAGE_B_CAVITY_FIT"),
            "iorAir": param("iorAir", "ior", 1.0003, 1.0003, 1.0003, False, "PHYSICAL_CONSTANT"),
            "iorWater": param("iorWater", "ior", 1.333, 1.333, 1.333, False, "PHYSICAL_CONSTANT"),
            "iorCrystal": param("iorCrystal", "ior", 1.45, 1.40, 1.54, True, "STAGE_B_CAVITY_FIT"),
            "cavityTintGain": param("cavityTintGain", "gain", 0.18, 0.02, 0.55, True, "STAGE_B_CAVITY_FIT"),
            "cavityRimRefraction": param("cavityRimRefraction", "gain", 0.42, 0.10, 0.95, True, "STAGE_B_CAVITY_FIT"),
            "upperGoldPenetration": param("upperGoldPenetration", "gain", 0.78, 0.20, 1.60, True, "STAGE_B_CAVITY_FIT"),
        },
        "fluidVolume": {
            "width": param("width", "px", 256, 256, 256, False, "FROZEN_V3_CONTRACT"),
            "height": param("height", "px", 256, 256, 256, False, "FROZEN_V3_CONTRACT"),
            "depth": param("depth", "slices", 32, 32, 64, True, "STAGE_C_VOLUME_FIT"),
            "densityScale": param("densityScale", "gain", 1.55, 0.80, 2.40, True, "STAGE_C_VOLUME_FIT"),
            "baseDensity": param("baseDensity", "alpha", 0.032, 0.010, 0.080, True, "STAGE_C_VOLUME_FIT"),
            "foldCount": param("foldCount", "count", 3, 2, 5, True, "STAGE_C_VOLUME_FIT"),
            "foldIso": param("foldIso", "scalar", 0.18, -0.10, 0.45, True, "STAGE_C_VOLUME_FIT"),
            "foldThickness": param("foldThickness", "R", 0.11, 0.06, 0.24, True, "STAGE_C_VOLUME_FIT"),
            "foldNormalPerturbation": param("foldNormalPerturbation", "gain", 0.34, 0.05, 0.75, True, "STAGE_C_VOLUME_FIT"),
            "foldRefraction": param("foldRefraction", "gain", 0.22, 0.0, 0.55, True, "STAGE_C_VOLUME_FIT"),
            "foldSpecularGain": param("foldSpecularGain", "gain", 0.42, 0.05, 1.00, True, "STAGE_C_VOLUME_FIT"),
        },
        "emeraldFolds": {
            "emeraldFoldGain": param("emeraldFoldGain", "gain", 0.62, 0.25, 1.10, True, "STAGE_C_VOLUME_FIT"),
            "emeraldAnchorWeight": param("emeraldAnchorWeight", "gain", 1.55, 0.80, 2.40, True, "STAGE_C_VOLUME_FIT"),
            "emeraldGreenBoost": param("emeraldGreenBoost", "gain", 1.06, 0.75, 1.45, True, "STAGE_C_VOLUME_FIT"),
            "emeraldThreshold": param("emeraldThreshold", "scalar", 0.16, 0.04, 0.42, True, "STAGE_C_VOLUME_FIT"),
        },
        "sapphireValleys": {
            "sapphireAbsorptionGain": param("sapphireAbsorptionGain", "gain", 0.24, 0.08, 0.55, True, "STAGE_C_VOLUME_FIT"),
            "sapphireAnchorWeight": param("sapphireAnchorWeight", "gain", 1.45, 0.80, 2.40, True, "STAGE_C_VOLUME_FIT"),
            "sapphireDepthDarken": param("sapphireDepthDarken", "gain", 0.74, 0.35, 1.05, True, "STAGE_C_VOLUME_FIT"),
            "beerLambertR": param("beerLambertR", "coeff", 2.60, 1.50, 3.80, True, "STAGE_C_VOLUME_FIT"),
            "beerLambertG": param("beerLambertG", "coeff", 0.92, 0.40, 1.60, True, "STAGE_C_VOLUME_FIT"),
            "beerLambertB": param("beerLambertB", "coeff", 0.42, 0.18, 0.95, True, "STAGE_C_VOLUME_FIT"),
        },
        "aquaRegion": {
            "aquaDensityGain": param("aquaDensityGain", "gain", 0.26, 0.08, 0.60, True, "STAGE_C_VOLUME_FIT"),
            "aquaAnchorWeight": param("aquaAnchorWeight", "gain", 1.42, 0.70, 2.20, True, "STAGE_C_VOLUME_FIT"),
            "aquaBrightnessGain": param("aquaBrightnessGain", "gain", 0.94, 0.55, 1.35, True, "STAGE_C_VOLUME_FIT"),
            "aquaUpperGateY": param("aquaUpperGateY", "R", 0.04, -0.25, 0.25, True, "STAGE_C_VOLUME_FIT"),
        },
        "caustics": {
            "causticIntensity": param("causticIntensity", "gain", 0.56, 0.15, 1.20, True, "STAGE_E_OPTICS_FIT"),
            "causticDecoupleWeight": param("causticDecoupleWeight", "ratio", 0.12, 0.0, 0.22, True, "STAGE_C_VOLUME_FIT"),
            "upperGoldCausticGain": param("upperGoldCausticGain", "gain", 0.44, 0.08, 1.10, True, "STAGE_E_OPTICS_FIT"),
        },
        "residentC01": {
            "periodMs": param("periodMs", "ms", 4400.0, 4400.0, 4400.0, False, "FROZEN_V3_CONTRACT"),
            "closed": param("closed", "bool", False, False, False, False, "FROZEN_V3_CONTRACT"),
            "halfWidth": param("halfWidth", "world", 0.125, 0.08, 0.18, True, "STAGE_D_RESIDENT_FIT"),
            "opacityScale": param("opacityScale", "ratio", 0.96, 0.65, 1.15, True, "STAGE_D_RESIDENT_FIT"),
            "controlPoints": param("controlPoints", "vec3[]", [], [], [], True, "STAGE_D_RESIDENT_FIT"),
        },
        "residentA01": {
            "periodMs": param("periodMs", "ms", 3700.0, 3700.0, 3700.0, False, "FROZEN_V3_CONTRACT"),
            "closed": param("closed", "bool", False, False, False, False, "FROZEN_V3_CONTRACT"),
            "halfWidth": param("halfWidth", "world", 0.095, 0.045, 0.16, True, "STAGE_D_RESIDENT_FIT"),
            "opacityScale": param("opacityScale", "ratio", 0.94, 0.55, 1.15, True, "STAGE_D_RESIDENT_FIT"),
            "controlPoints": param("controlPoints", "vec3[]", [], [], [], True, "STAGE_D_RESIDENT_FIT"),
            "widthProfile": param("widthProfile", "float[]", [], [], [], True, "STAGE_D_RESIDENT_FIT"),
            "twistProfileDeg": param("twistProfileDeg", "deg[]", [], [], [], True, "STAGE_D_RESIDENT_FIT"),
        },
        "residentA02": {
            "periodMs": param("periodMs", "ms", 2900.0, 2900.0, 2900.0, False, "FROZEN_V3_CONTRACT"),
            "closed": param("closed", "bool", False, False, False, False, "FROZEN_V3_CONTRACT"),
            "halfWidth": param("halfWidth", "world", 0.118, 0.055, 0.18, True, "STAGE_D_RESIDENT_FIT"),
            "opacityScale": param("opacityScale", "ratio", 1.00, 0.65, 1.20, True, "STAGE_D_RESIDENT_FIT"),
            "controlPoints": param("controlPoints", "vec3[]", [], [], [], True, "STAGE_D_RESIDENT_FIT"),
            "widthProfile": param("widthProfile", "float[]", [], [], [], True, "STAGE_D_RESIDENT_FIT"),
            "twistProfileDeg": param("twistProfileDeg", "deg[]", [], [], [], True, "STAGE_D_RESIDENT_FIT"),
        },
        "residentG01": {
            "periodMs": param("periodMs", "ms", 6100.0, 6100.0, 6100.0, False, "FROZEN_V3_CONTRACT"),
            "closed": param("closed", "bool", False, False, False, False, "FROZEN_V3_CONTRACT"),
            "halfWidth": param("halfWidth", "world", 0.165, 0.10, 0.22, True, "STAGE_D_RESIDENT_FIT"),
            "opacityScale": param("opacityScale", "ratio", 0.92, 0.60, 1.10, True, "STAGE_D_RESIDENT_FIT"),
            "controlPoints": param("controlPoints", "vec3[]", [], [], [], True, "STAGE_D_RESIDENT_FIT"),
        },
        "residentG02": {
            "periodMs": param("periodMs", "ms", 5300.0, 5300.0, 5300.0, False, "FROZEN_V3_CONTRACT"),
            "closed": param("closed", "bool", False, False, False, False, "FROZEN_V3_CONTRACT"),
            "halfWidth": param("halfWidth", "world", 0.155, 0.09, 0.22, True, "STAGE_D_RESIDENT_FIT"),
            "opacityScale": param("opacityScale", "ratio", 0.90, 0.60, 1.10, True, "STAGE_D_RESIDENT_FIT"),
            "controlPoints": param("controlPoints", "vec3[]", [], [], [], True, "STAGE_D_RESIDENT_FIT"),
        },
        "lighting": {
            "goldWallsGain": param("goldWallsGain", "gain", 1.16, 0.55, 1.80, True, "STAGE_E_OPTICS_FIT"),
            "coreStreamGain": param("coreStreamGain", "gain", 1.08, 0.50, 1.75, True, "STAGE_E_OPTICS_FIT"),
            "secThreadGain": param("secThreadGain", "gain", 0.72, 0.25, 1.30, True, "STAGE_E_OPTICS_FIT"),
            "glassBellyGain": param("glassBellyGain", "gain", 0.34, 0.10, 0.70, True, "STAGE_E_OPTICS_FIT"),
            "goldSpecGain": param("goldSpecGain", "gain", 0.52, 0.15, 1.10, True, "STAGE_E_OPTICS_FIT"),
            "volumeExposure": param("volumeExposure", "gain", 1.06, 0.75, 1.45, True, "STAGE_E_OPTICS_FIT"),
        },
        "postProcessing": {
            "bloomThreshold": param("bloomThreshold", "hdr", 1.32, 1.10, 1.65, True, "STAGE_E_OPTICS_FIT"),
            "bloomStrength": param("bloomStrength", "gain", 0.62, 0.30, 1.00, True, "STAGE_E_OPTICS_FIT"),
            "acesExposure": param("acesExposure", "gain", 1.06, 0.85, 1.30, True, "STAGE_E_OPTICS_FIT"),
            "saturationBoost": param("saturationBoost", "ratio", 1.10, 1.00, 1.24, True, "STAGE_E_OPTICS_FIT"),
        },
        "quality": {
            "raymarchStepsHigh": param("raymarchStepsHigh", "steps", 36, 24, 48, False, "FROZEN_V3_CONTRACT"),
            "raymarchStepsBalanced": param("raymarchStepsBalanced", "steps", 28, 20, 36, False, "FROZEN_V3_CONTRACT"),
            "raymarchStepsEco": param("raymarchStepsEco", "steps", 20, 16, 28, False, "FROZEN_V3_CONTRACT"),
            "coreRotationSpeedDegPerSec": param("coreRotationSpeedDegPerSec", "deg/s", 26.0, 26.0, 26.0, False, "FROZEN_V3_CONTRACT"),
            "coreRotationAxis": param("coreRotationAxis", "vec3", [0.14, 0.98, -0.14], [0.14, 0.98, -0.14], [0.14, 0.98, -0.14], False, "FROZEN_V3_CONTRACT"),
        },
    }


def get_val(cfg: dict[str, Any], section: str, key: str) -> Any:
    return cfg[section][key]["fittedValue"]


def set_val(cfg: dict[str, Any], section: str, key: str, value: Any) -> None:
    entry = cfg[section][key]
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        lo, hi = entry["min"], entry["max"]
        if isinstance(lo, (int, float)) and isinstance(hi, (int, float)):
            value = max(lo, min(hi, value))
    entry["fittedValue"] = value


def validate_config(cfg: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if cfg.get("schemaVersion") != SCHEMA_VERSION:
        errors.append(f"Invalid schemaVersion: {cfg.get('schemaVersion')}")
    master = cfg.get("referenceMaster", {})
    if master.get("sha256") != EXPECTED_MASTER_SHA256:
        errors.append(f"Master sha256 mismatch: {master.get('sha256')}")

    required_sections = [
        "camera", "outerGlass", "innerCavity", "fluidVolume",
        "emeraldFolds", "sapphireValleys", "aquaRegion", "caustics",
        "residentC01", "residentA01", "residentA02", "residentG01", "residentG02",
        "lighting", "postProcessing", "quality",
    ]
    for sec in required_sections:
        if sec not in cfg:
            errors.append(f"Missing section: {sec}")
            continue
        for k, entry in cfg[sec].items():
            for req in ("name", "unit", "default", "min", "max", "optimizable", "source", "fittedValue"):
                if req not in entry:
                    errors.append(f"Missing field {req} in {sec}.{k}")
            val = entry.get("fittedValue")
            if isinstance(val, (int, float)) and not isinstance(val, bool):
                lo, hi = entry["min"], entry["max"]
                if isinstance(lo, (int, float)) and isinstance(hi, (int, float)):
                    if val < lo - 1e-6 or val > hi + 1e-6:
                        errors.append(f"{sec}.{k} out of bounds [{lo}, {hi}]: {val}")
    return errors


def flatten_runtime_values(cfg: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {
        "schemaVersion": cfg["schemaVersion"],
        "referenceMaster": copy.deepcopy(cfg["referenceMaster"]),
    }
    for sec, block in cfg.items():
        if sec in ("schemaVersion", "referenceMaster", "fitSummary", "fingerprints"):
            continue
        if isinstance(block, dict):
            out[sec] = {k: v["fittedValue"] for k, v in block.items()}
    return out


def save_config(cfg: dict[str, Any], path: Path) -> None:
    errs = validate_config(cfg)
    if errs:
        raise ValueError("Schema validation failed:\n" + "\n".join(errs))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(cfg, indent=2) + "\n", encoding="utf-8")
