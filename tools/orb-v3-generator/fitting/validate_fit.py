#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Strict Offline Fit, Decoded-WebP & Asset Integrity Gate Validator

Validates (and exits strictly non-zero on ANY violation):
  1. MASTER_FIDELITY_V32.json schema, bounds, and SHA-256 parity with AI_ORB_V3_2_VISUAL_LOSS.json.
  2. All 17 offline hard gates present, finite (no NaN/Infinity/None), and independently verified
     against frozen thresholds (NEVER blindly trusts `"pass": true`).
  3. All 35 WebP assets listed in ORB_V3_ASSET_MANIFEST.json exist on disk and match exact SHA-256.
  4. Independent decoded-WebP 3D projection (`evaluate_decoded_webp_projection` on the actual 32
     decoded Lossless RGBA WebP slices on disk) passes ALL interior hard gates (`E03`, `E05`,
     `E06`, `E07`: relative area error <= 15%, centroid distance <= 0.04D).
  5. 360° dynamic continuity sweep (0°..360° every 30° around frozen axis (0.14, 0.98, -0.14))
     evaluated directly on the decoded 32 WebP volume slices.

Supports `--root <path>` for isolated negative CI gate testing without modifying workspace files.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path
from typing import Any

FITTING_DIR = Path(__file__).resolve().parent
GEN_DIR = FITTING_DIR.parent
for _p in (FITTING_DIR, GEN_DIR):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

from canonical_fluid_volume import (
    decode_webp_volume_3d,
    sample_trilinear_volume_3d,
)
from parameter_schema import get_val, validate_config
from reference_measurements import ROOT
from volume_structure_fitter import evaluate_decoded_webp_projection

# Frozen 17 offline hard-gate definitions: (comparator, threshold)
REQUIRED_HARD_GATES: dict[str, tuple[str, Any]] = {
    "E01_diameterRelErr_le_2pct": ("le", 0.02),
    "E01_portraitDiamRelErr_le_2pct": ("le", 0.02),
    "E01_centerErrW_le_1pct": ("le", 0.01),
    "E01_silhouetteIoU_ge_095": ("ge", 0.95),
    "E03_cavityAreaRelErr_le_15pct": ("le", 0.15),
    "E03_cavityCentroid_le_004D": ("le", 0.04),
    "E04_meniscusMeanErr_le_0025R": ("le", 0.025),
    "E05_emeraldAreaRelErr_le_15pct": ("le", 0.15),
    "E05_emeraldCentroid_le_004D": ("le", 0.04),
    "E06_sapphireAreaRelErr_le_15pct": ("le", 0.15),
    "E06_sapphireCentroid_le_004D": ("le", 0.04),
    "E07_aquaAreaRelErr_le_15pct": ("le", 0.15),
    "E07_aquaCentroid_le_004D": ("le", 0.04),
    "E10_goldCombinedChamfer_le_0025R": ("le", 0.025),
    "E10_goldNonRegularOval": ("eq", True),
    "E11_c01SmoothTurn_lt_20deg": ("lt", 20.0),
    "E02_upperDomeHaze_le_1pct": ("le", 0.01),
}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


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


def _is_valid_finite_number(v: Any) -> bool:
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return False
    return math.isfinite(float(v))


def validate_visual_loss_gates(vl: dict[str, Any], actual_cfg_sha: str) -> list[str]:
    errors: list[str] = []
    recorded_sha = vl.get("masterFidelityConfigSha256")
    if not recorded_sha or recorded_sha != actual_cfg_sha:
        errors.append(
            f"MASTER_FIDELITY_V32 SHA-256 mismatch: visualLoss={recorded_sha} vs actual={actual_cfg_sha}"
        )

    hard_gates = vl.get("hardGates")
    if not isinstance(hard_gates, dict):
        errors.append("Missing or invalid 'hardGates' object in AI_ORB_V3_2_VISUAL_LOSS.json")
        return errors

    for gate_name, (op, expected_thresh) in REQUIRED_HARD_GATES.items():
        if gate_name not in hard_gates:
            errors.append(f"Missing required hard gate: {gate_name}")
            continue
        entry = hard_gates[gate_name]
        if not isinstance(entry, dict) or "value" not in entry or "pass" not in entry:
            errors.append(f"Malformed hard gate entry: {gate_name}")
            continue
        val = entry["value"]
        declared_pass = entry["pass"]
        if op == "eq":
            if val is not expected_thresh or declared_pass is not True:
                errors.append(f"Gate failed [{gate_name}]: value={val}, expected={expected_thresh}, pass={declared_pass}")
        else:
            if not _is_valid_finite_number(val):
                errors.append(f"Gate has non-finite/invalid numeric value [{gate_name}]: {val}")
                continue
            fv = float(val)
            actual_pass = (
                (fv <= expected_thresh) if op == "le"
                else (fv >= expected_thresh) if op == "ge"
                else (fv < expected_thresh)
            )
            if not actual_pass or declared_pass is not True:
                errors.append(
                    f"Gate failed [{gate_name}]: value={fv:.6f} (op={op} {expected_thresh}), declaredPass={declared_pass}"
                )

    if vl.get("allOfflineHardGatesPass") is not True:
        errors.append(f"allOfflineHardGatesPass is not true (failed={vl.get('failedOfflineGates')})")
    if vl.get("failedOfflineGates"):
        errors.append(f"failedOfflineGates non-empty: {vl.get('failedOfflineGates')}")

    comps = vl.get("components", {})
    if not isinstance(comps, dict) or not _is_valid_finite_number(comps.get("L_total")):
        errors.append(f"Missing or non-finite components.L_total: {comps.get('L_total') if isinstance(comps, dict) else None}")

    return errors


def validate_webp_manifest_and_files(root: Path) -> tuple[list[str], int]:
    errors: list[str] = []
    manifest_path = root / "app" / "src" / "main" / "assets" / "orb_v3" / "manifest" / "ORB_V3_ASSET_MANIFEST.json"
    assets_base = root / "app" / "src" / "main" / "assets" / "orb_v3"
    if not manifest_path.exists():
        return [f"Missing asset manifest: {manifest_path}"], 0

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    volume_slices = manifest.get("volume", {}).get("slices", [])
    maps = manifest.get("maps", [])
    assets = list(volume_slices) + list(maps)
    if len(volume_slices) != 32:
        errors.append(f"Expected 32 volume WebP slices in ORB_V3_ASSET_MANIFEST.json, found {len(volume_slices)}")
    if len(maps) != 3:
        errors.append(f"Expected 3 2D WebP maps in ORB_V3_ASSET_MANIFEST.json, found {len(maps)}")
    if len(assets) != 35:
        errors.append(f"Expected 35 WebP assets in ORB_V3_ASSET_MANIFEST.json, found {len(assets)}")

    verified = 0
    for item in assets:
        rel_path = item.get("path", "")
        expected_sha = item.get("sha256", "")
        full_path = assets_base / rel_path
        if not rel_path or not full_path.exists():
            errors.append(f"Missing WebP asset file: {rel_path}")
            continue
        actual_sha = sha256_file(full_path)
        if actual_sha != expected_sha:
            errors.append(f"WebP SHA-256 mismatch for {rel_path}: manifest={expected_sha[:12]}.. vs disk={actual_sha[:12]}..")
            continue
        verified += 1
    return errors, verified


def run_validation(root_override: Path | None = None, write_report: bool = True) -> tuple[dict[str, Any], bool]:
    base_root = root_override.resolve() if root_override is not None else ROOT
    master_cfg_path = base_root / "app" / "src" / "main" / "assets" / "orb_v3" / "manifest" / "MASTER_FIDELITY_V32.json"
    visual_loss_path = base_root / "docs" / "v3" / "AI_ORB_V3_2_VISUAL_LOSS.json"
    targets_path = base_root / "tools" / "orb-v3-generator" / "fitting" / "REFERENCE_FITTING_TARGETS.json"
    vol_dir = base_root / "app" / "src" / "main" / "assets" / "orb_v3" / "volume"
    out_json_path = base_root / "tools" / "orb-v3-generator" / "fitting" / "FIT_VALIDATION_RESULT.json"

    errors: list[str] = []

    if not master_cfg_path.exists():
        errors.append(f"Missing MASTER_FIDELITY_V32.json: {master_cfg_path}")
    if not visual_loss_path.exists():
        errors.append(f"Missing AI_ORB_V3_2_VISUAL_LOSS.json: {visual_loss_path}")
    if not targets_path.exists():
        errors.append(f"Missing REFERENCE_FITTING_TARGETS.json: {targets_path}")

    if errors:
        result = {
            "status": "OFFLINE_FIDELITY_GATE_FAILED",
            "schemaValid": False,
            "errors": errors,
            "overallOfflineValidationPass": False,
            "realGpuStatus": "PENDING_REAL_GPU_CAPTURE",
        }
        return result, False

    cfg_text = master_cfg_path.read_text(encoding="utf-8")
    actual_cfg_sha = sha256_bytes(cfg_text.encode("utf-8"))
    cfg = json.loads(cfg_text)
    vl = json.loads(visual_loss_path.read_text(encoding="utf-8"))
    targets = json.loads(targets_path.read_text(encoding="utf-8"))

    schema_errs = validate_config(cfg)
    errors.extend(schema_errs)

    gate_errs = validate_visual_loss_gates(vl, actual_cfg_sha)
    errors.extend(gate_errs)

    manifest_errs, verified_assets = validate_webp_manifest_and_files(base_root)
    errors.extend(manifest_errs)

    decoded_proj: dict[str, Any] | None = None
    decoded_proj_pass = False
    dynamic_pass = False
    sweep: list[dict[str, Any]] = []
    max_step_jump = 1.0
    loop_closure_err = 1.0

    # Independent verification directly against decoded 32 Lossless RGBA WebP slices on disk
    try:
        slices = decode_webp_volume_3d(vol_dir)
        decoded_proj = evaluate_decoded_webp_projection(cfg, targets, slices, grid_n=96, steps=16)
        regs = decoded_proj["regions"]
        webp_proj_errs: list[str] = []
        for k in ("E03_upper_cavity", "E05_emerald", "E06_sapphire", "E07_aqua"):
            if regs[k]["relativeAreaError"] > 0.15:
                webp_proj_errs.append(
                    f"Decoded-WebP {k}.relativeAreaError={regs[k]['relativeAreaError']:.4f} > 0.15"
                )
            if regs[k]["centroidDistD"] > 0.04:
                webp_proj_errs.append(
                    f"Decoded-WebP {k}.centroidDistD={regs[k]['centroidDistD']:.4f} > 0.04"
                )
        decoded_proj_pass = len(webp_proj_errs) == 0
        errors.extend(webp_proj_errs)

        # 360-degree rotation continuity sweep around frozen axis (0.14, 0.98, -0.14) using DECODED WebP 3D tensor
        axis = tuple(get_val(cfg, "quality", "coreRotationAxis"))
        probes = [
            (0.45, -0.15, 0.35),
            (-0.42, 0.08, 0.40),
            (0.12, -0.52, 0.30),
            (-0.20, -0.35, -0.45),
            (0.35, 0.12, -0.38),
        ]
        prev_mean = None
        max_step_jump = 0.0
        for deg in range(0, 361, 30):
            vals = []
            for p in probes:
                rp = rotate_axis(p, axis, float(deg))
                _, g, b, a = sample_trilinear_volume_3d(slices, rp[0], rp[1], rp[2])
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
        if not dynamic_pass:
            errors.append(
                f"Decoded-WebP 360° continuity failed: maxStepAlphaJump={max_step_jump:.5f}, loopClosureErr={loop_closure_err:.6f}"
            )
    except Exception as exc:
        errors.append(f"Decoded-WebP 3D volume verification error: {exc}")

    overall_pass = len(errors) == 0
    result = {
        "status": "OFFLINE_FIDELITY_GATE_PASSED" if overall_pass else "OFFLINE_FIDELITY_GATE_FAILED",
        "schemaValid": len(schema_errs) == 0,
        "masterFidelityConfigSha256": actual_cfg_sha,
        "staticOfflineHardGatesPass": len(gate_errs) == 0,
        "webpManifestVerifiedCount": verified_assets,
        "webpManifestPass": len(manifest_errs) == 0,
        "decodedWebpProjectionPass": decoded_proj_pass,
        "decodedWebpProjection": decoded_proj,
        "dynamic360Sweep": {
            "evaluationSource": "DECODED_WEBP_32_SLICES_TRILINEAR",
            "stepDeg": 30,
            "maxStepAlphaJump": round(max_step_jump, 5),
            "loopClosure360AlphaErr": round(loop_closure_err, 6),
            "pass": dynamic_pass,
            "samples": sweep,
        },
        "failedOfflineGates": vl.get("failedOfflineGates", []),
        "errors": errors,
        "overallOfflineValidationPass": overall_pass,
        "realGpuStatus": "PENDING_REAL_GPU_CAPTURE",
    }

    if write_report and root_override is None:
        out_json_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

    if overall_pass:
        dr = decoded_proj["regions"] if decoded_proj else {}
        print(
            f"[validate_fit] OFFLINE_FIDELITY_GATE_PASSED: 17/17 hardGates=PASS, "
            f"webpAssets={verified_assets}/35, decodedWebp(E03={dr['E03_upper_cavity']['relativeAreaError']*100:.1f}%, "
            f"E05={dr['E05_emerald']['relativeAreaError']*100:.1f}%, "
            f"E06={dr['E06_sapphire']['relativeAreaError']*100:.1f}%, "
            f"E07={dr['E07_aqua']['relativeAreaError']*100:.1f}%), "
            f"360°loopErr={loop_closure_err:.6f}"
        )
    else:
        print(f"[validate_fit] OFFLINE_FIDELITY_GATE_FAILED ({len(errors)} error(s)):", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)

    return result, overall_pass


def main() -> int:
    parser = argparse.ArgumentParser(description="AI-Orb-Demo V3.2 Strict Offline Gate Validator")
    parser.add_argument("--root", type=Path, default=None, help="Optional workspace root override for isolated tests")
    args = parser.parse_args()
    _, ok = run_validation(root_override=args.root, write_report=(args.root is None))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
