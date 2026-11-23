#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Gate 3 Closed-Loop Staged Reference-to-3D Optimizer

Executes strictly in order:
  G0  Master Lock & E01–E14 Target Extraction
  A   Stage A: Camera & 3D Icosphere Projection Fit (Square 1:1 + Portrait 9:20)
  B   Stage B: Cavity Geometry Fit (Candidate A vs Candidate B Embedded Crystal Lens)
  C   Stage C: Canonical 3D Fluid Volume Structure & Fold Surface Fit (+ 32 vs 64 slice comparison)
  D   Stage D: Resident Combined 3D Projection Fit (A01+A02 gold non-oval + C01/G01/G02)
  E   Stage E: Glass Optics & Synchronized Ribbon Material Fit
  F   Stage F: Joint Global Visual Loss Validation & Single-Source Config Export

Strict Exit Policy (P0-2):
  Exits with status 0 ONLY when ALL stages (A–E) and ALL 17 offline hard gates pass.
  Exits with status 1 if ANY stage or ANY hard gate fails (never masks failure with exit 0).
"""

from __future__ import annotations

import hashlib
import json
import sys
import time
from pathlib import Path

FITTING_DIR = Path(__file__).resolve().parent
if str(FITTING_DIR) not in sys.path:
    sys.path.insert(0, str(FITTING_DIR))

from camera_fitter import fit_camera
from cavity_geometry_fitter import fit_cavity_geometry
from optics_fitter import fit_optics
from parameter_schema import build_default_schema, save_config
from reference_measurements import (
    EXPECTED_SHA256,
    REGIONS_JSON_PATH,
    ROOT,
    TARGETS_JSON_PATH,
    run_measurement_lock,
    sha256_file,
)
from resident_projection_fitter import fit_residents
from visual_loss import compute_visual_loss_and_gates
from volume_structure_fitter import fit_volume_structure

OUT_MASTER_CONFIG = ROOT / "app" / "src" / "main" / "assets" / "orb_v3" / "manifest" / "MASTER_FIDELITY_V32.json"
OUT_VISUAL_LOSS = ROOT / "docs" / "v3" / "AI_ORB_V3_2_VISUAL_LOSS.json"
OUT_RUN_SUMMARY = FITTING_DIR / "FIT_RUN_SUMMARY.json"


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def verify_master_from_config(cfg: dict) -> str:
    master_rel = cfg.get("referenceMaster", {}).get("path", "")
    expected_sha = cfg.get("referenceMaster", {}).get("sha256", EXPECTED_SHA256)
    master_path = ROOT / master_rel
    if not master_rel or not master_path.exists():
        raise FileNotFoundError(f"Frozen master path missing: {master_path}")
    actual_sha = sha256_file(master_path)
    if actual_sha != expected_sha:
        raise ValueError(f"Frozen master SHA-256 mismatch: got {actual_sha}, expected {expected_sha}")
    return actual_sha


def run_staged_optimization() -> tuple[dict, bool]:
    t0 = time.time()
    cfg = build_default_schema()
    if OUT_MASTER_CONFIG.exists():
        existing_cfg = json.loads(OUT_MASTER_CONFIG.read_text(encoding="utf-8"))
        if "referenceMaster" in existing_cfg and "path" in existing_cfg["referenceMaster"]:
            cfg["referenceMaster"]["path"] = existing_cfg["referenceMaster"]["path"]
    master_sha = verify_master_from_config(cfg)

    print(f"[optimize_reference_scene] === G0: Master Lock & E01-E14 Extraction (sha={master_sha[:12]}..) ===")
    targets = run_measurement_lock()
    regions = json.loads(REGIONS_JSON_PATH.read_text(encoding="utf-8"))

    print("[optimize_reference_scene] === Stage A: Camera Projection Fit ===")
    stage_a = fit_camera(cfg, targets)
    print(
        f"  [Stage A] camDist={stage_a['fitted']['cameraDistanceSquare']} vbr={stage_a['fitted']['visualBoundRadius']} "
        f"diamErr={stage_a['fitted']['squareDiamRelErr']*100:.2f}% centerErr={stage_a['fitted']['squareCenterErrW']*100:.2f}% "
        f"IoU={stage_a['fitted']['squareSilhouetteIoU']:.4f} pass={stage_a['pass']}"
    )

    print("[optimize_reference_scene] === Stage B: Cavity Geometry Fit (Candidate A vs B) ===")
    stage_b = fit_cavity_geometry(cfg, targets)
    bw = stage_b["winner"]
    print(
        f"  [Stage B] winner={stage_b['selectedModel']} (A_loss={stage_b['candidateA']['loss']} vs B_loss={stage_b['candidateB']['loss']}) "
        f"E03_err={bw['e03AreaRelErr']*100:.1f}% E03_c={bw['e03CentroidDistD']:.4f}D E04={bw['e04MeanErrorR']:.5f}R "
        f"haze={bw['upperGreyWhiteHazeRatio']*100:.2f}% pass={stage_b['pass']}"
    )

    print("[optimize_reference_scene] === Stage C: Canonical 3D Fluid Volume Structure Fit ===")
    stage_c = fit_volume_structure(cfg, targets, regions)
    cr = stage_c["regions"]
    print(
        f"  [Stage C] E03={cr['E03_upper_cavity']['relativeAreaError']*100:.1f}%({cr['E03_upper_cavity']['centroidDistD']:.4f}D) "
        f"E05={cr['E05_emerald']['relativeAreaError']*100:.1f}%({cr['E05_emerald']['centroidDistD']:.4f}D) "
        f"E06={cr['E06_sapphire']['relativeAreaError']*100:.1f}%({cr['E06_sapphire']['centroidDistD']:.4f}D) "
        f"E07={cr['E07_aqua']['relativeAreaError']*100:.1f}%({cr['E07_aqua']['centroidDistD']:.4f}D) "
        f"depth={stage_c['sliceDepthComparison']['selectedDepth']} pass={stage_c['pass']}"
    )

    print("[optimize_reference_scene] === Stage D: Resident Combined 3D Projection Fit ===")
    stage_d = fit_residents(cfg, targets)
    fg = stage_d["fittedGold"]
    print(
        f"  [Stage D] Gold Chamfer={fg['chamferR']:.4f}R (V3.1={stage_d['baselineV31Gold']['chamferR']:.4f}R) "
        f"r2m={fg['ribbonToMasterR']:.4f}R m2r={fg['masterToRibbonR']:.4f}R "
        f"regularOval={fg['ovalMetrics']['isRegularOval']} C01_turn={stage_d['c01MaxTurnDeg']}° pass={stage_d['pass']}"
    )

    print("[optimize_reference_scene] === Stage E: Glass Optics & Ribbon Material Fit ===")
    stage_e = fit_optics(cfg, targets)
    em = stage_e["metrics"]
    print(
        f"  [Stage E] haze={em['upperDomeHazeRatio']*100:.2f}% E09_err={em['e09AreaRelErr']*100:.1f}% "
        f"goldLumaErr={em['goldLumaErr']:.4f} pass={stage_e['pass']}"
    )

    print("[optimize_reference_scene] === Stage F: Global Visual Loss & Hard-Gate Evaluation ===")
    vl = compute_visual_loss_and_gates(stage_a, stage_b, stage_c, stage_d, stage_e)
    all_stages_pass = all(
        bool(st["pass"]) for st in (stage_a, stage_b, stage_c, stage_d, stage_e)
    )
    overall_pass = bool(all_stages_pass and vl["allOfflineHardGatesPass"])
    failed_stages = [
        name for name, st in (
            ("StageA", stage_a),
            ("StageB", stage_b),
            ("StageC", stage_c),
            ("StageD", stage_d),
            ("StageE", stage_e),
        )
        if not st["pass"]
    ]
    print(
        f"  [Stage F] L_total={vl['components']['L_total']} allStagesPass={all_stages_pass} "
        f"allOfflineHardGatesPass={vl['allOfflineHardGatesPass']} failedGates={vl['failedOfflineGates']} "
        f"failedStages={failed_stages}"
    )

    # Attach audit metadata & save unified runtime config
    cfg["fitSummary"] = {
        "evaluationDomain": "OFFLINE_CPU_PROXY_ANALYTICAL_FITTING",
        "realGpuStatus": "PENDING_REAL_GPU_CAPTURE",
        "selectedCavityModel": stage_b["selectedModel"],
        "selectedVolumeDepth": stage_c["sliceDepthComparison"]["selectedDepth"],
        "L_total": vl["components"]["L_total"],
        "allStagesPass": all_stages_pass,
        "failedStages": failed_stages,
        "allOfflineHardGatesPass": vl["allOfflineHardGatesPass"],
        "failedOfflineGates": vl["failedOfflineGates"],
        "elapsedSec": round(time.time() - t0, 2),
    }

    save_config(cfg, OUT_MASTER_CONFIG)
    cfg_sha = sha256_text(OUT_MASTER_CONFIG.read_text(encoding="utf-8"))

    vl["masterFidelityConfigPath"] = str(OUT_MASTER_CONFIG.relative_to(ROOT))
    vl["masterFidelityConfigSha256"] = cfg_sha
    vl["allStagesPass"] = all_stages_pass
    vl["failedStages"] = failed_stages
    OUT_VISUAL_LOSS.parent.mkdir(parents=True, exist_ok=True)
    OUT_VISUAL_LOSS.write_text(json.dumps(vl, indent=2) + "\n", encoding="utf-8")

    summary = {
        "schemaVersion": "AI_ORB_V3_2_FIT_RUN_SUMMARY_1_0",
        "elapsedSec": round(time.time() - t0, 2),
        "masterPath": cfg["referenceMaster"]["path"],
        "masterSha256": master_sha,
        "masterFidelityConfigSha256": cfg_sha,
        "allStagesPass": all_stages_pass,
        "failedStages": failed_stages,
        "overallPass": overall_pass,
        "stageA": stage_a,
        "stageB": stage_b,
        "stageC": stage_c,
        "stageD": stage_d,
        "stageE": stage_e,
        "stageF_visualLoss": vl,
    }
    OUT_RUN_SUMMARY.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    if overall_pass:
        print(f"[optimize_reference_scene] PASS in {summary['elapsedSec']}s -> {OUT_MASTER_CONFIG.relative_to(ROOT)}")
    else:
        print(
            f"[optimize_reference_scene] FAILED in {summary['elapsedSec']}s: "
            f"failedStages={failed_stages}, failedOfflineGates={vl['failedOfflineGates']}",
            file=sys.stderr,
        )
    return summary, overall_pass


if __name__ == "__main__":
    _, ok = run_staged_optimization()
    sys.exit(0 if ok else 1)
