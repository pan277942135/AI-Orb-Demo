#!/usr/bin/env python3
"""Deterministic per-object rigid transform calibration for V2 fish assets.

This tool changes placement transforms only. It never changes source assets or
acceptance thresholds. Low-confidence Ground Truth remains blocked regardless
of the optimizer's best score.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image
from scipy.optimize import differential_evolution

MASTER_SHA = "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589"
START_BOXES = {
    "WW_FISH_01": [676, 452, 874, 544],
    "WW_FISH_02": [478, 517, 653, 585],
    "WW_FISH_03": [606, 552, 752, 619],
    "WW_FISH_04": [530, 625, 666, 690],
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def score_transform(source: np.ndarray, target: np.ndarray, crop: tuple[int, int, int, int], params: np.ndarray) -> tuple[float, np.ndarray]:
    left, top, right, bottom = crop
    cx, cy, sx, sy, degrees = [float(x) for x in params]
    sh, sw = source.shape
    scx, scy = (sw - 1) / 2.0, (sh - 1) / 2.0
    angle = np.deg2rad(degrees)
    co, si = np.cos(angle), np.sin(angle)
    a, b, c, d = sx * co, -sy * si, sx * si, sy * co
    matrix = np.asarray([[a, b, cx - left - a * scx - b * scy],
                         [c, d, cy - top - c * scx - d * scy]], dtype=np.float32)
    rendered = cv2.warpAffine(source.astype(np.uint8) * 255, matrix, (right - left, bottom - top),
                              flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0) > 32
    ref = target[top:bottom, left:right]
    union = np.logical_or(rendered, ref).sum()
    value = float(np.logical_and(rendered, ref).sum() / union) if union else 0.0
    return value, rendered


def run(package: Path, *, maxiter: int = 35, only: set[str] | None = None) -> dict:
    original = package / "02_CALIBRATED_ASSETS/V2_ORIGINAL/05_FISH"
    gt = package / "01_ANALYSIS/ground_truth/objects"
    output_path = package / "02_CALIBRATED_ASSETS/FISH/fish_transform_fit.json"
    previous = json.loads(output_path.read_text(encoding="utf-8")) if output_path.is_file() else {}
    results = dict(previous.get("results", {}))
    for index, (object_id, box) in enumerate(START_BOXES.items(), 1):
        if only and object_id not in only:
            continue
        source_path = original / f"fish_{index:02d}.png"
        target_path = gt / object_id / "source_visible_mask.png"
        source_asset = Image.open(source_path).convert("RGBA")
        alpha = np.asarray(source_asset.getchannel("A"), dtype=np.uint8)
        yy, xx = np.where(alpha > 32)
        if not len(xx):
            raise ValueError(f"source asset alpha is empty: {source_path}")
        alpha_crop = alpha[yy.min():yy.max() + 1, xx.min():xx.max() + 1] > 32
        target = np.asarray(Image.open(target_path).convert("L"), dtype=np.uint8) >= 128
        x0, y0, x1, y1 = box
        cx0, cy0 = (x0 + x1) / 2, (y0 + y1) / 2
        width, height = x1 - x0, y1 - y0
        pad = 64
        left, top = max(0, x0 - pad), max(0, y0 - pad)
        right, bottom = min(1254, x1 + pad), min(1254, y1 + pad)
        source_h, source_w = alpha_crop.shape
        baseline_params = [cx0, cy0, width / source_w, height / source_h, 0.0]
        baseline_iou, _ = score_transform(alpha_crop, target, (left, top, right, bottom), baseline_params)

        def objective(params: np.ndarray) -> float:
            iou, _ = score_transform(alpha_crop, target, (left, top, right, bottom), params)
            return 1.0 - iou

        bounds = [
            (cx0 - 32, cx0 + 32), (cy0 - 32, cy0 + 32),
            (baseline_params[2] * 0.65, baseline_params[2] * 1.30),
            (baseline_params[3] * 0.65, baseline_params[3] * 1.30),
            (-6.0, 6.0),
        ]
        fit = differential_evolution(objective, bounds, seed=2026, maxiter=maxiter, popsize=7,
                                     tol=1e-3, polish=True, workers=1, updating="immediate")
        best = fit.x.tolist()
        best_iou, _ = score_transform(alpha_crop, target, (left, top, right, bottom), best)
        report_path = gt / object_id / "segmentation_report.json"
        source_report = json.loads(report_path.read_text(encoding="utf-8"))
        confidence = source_report.get("master_confidence_label", "LOW")
        metric_pass = best_iou >= 0.90
        status = "PASS" if metric_pass and confidence in {"MEDIUM_HIGH", "HIGH"} else "FAIL" if confidence in {"MEDIUM_HIGH", "HIGH"} else "BLOCKED_GROUND_TRUTH_UNCERTAIN"
        results[object_id] = {
            "object_id": object_id,
            "source_asset": str(source_path.relative_to(package)),
            "source_asset_sha256": sha(source_path),
            "master_sha256": MASTER_SHA,
            "ground_truth_confidence": confidence,
            "starting_box_px": box,
            "baseline_iou": baseline_iou,
            "optimized_iou": best_iou,
            "change_iou": best_iou - baseline_iou,
            "transform": {"center_px": best[:2], "scale_px_per_source_pixel_xy": best[2:4], "rotation_degrees": best[4]},
            "optimizer": {"algorithm": "SciPy differential_evolution", "seed": 2026, "maxiter": maxiter,
                          "popsize": 7, "converged": bool(fit.success), "evaluations": int(fit.nfev)},
            "threshold_iou": 0.90,
            "status": status,
            "change_type": "RECALIBRATE_TRANSFORM_ONLY",
            "source_asset_modified": False,
        }
    result = {
        "schema_version": "water-world-v2-1-fish-transform-fit/1.0",
        "master_sha256": MASTER_SHA,
        "metric_engine": "canonical_metrics.py; binary alpha threshold 128; same 1254x1254 coordinates",
        "results": results,
        "overall_status": "PASS" if all(row["status"] == "PASS" for row in results.values()) else "FAIL_OR_BLOCKED",
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package-root", type=Path, required=True)
    parser.add_argument("--maxiter", type=int, default=35)
    parser.add_argument("--only", action="append", choices=sorted(START_BOXES))
    args = parser.parse_args()
    run(args.package_root, maxiter=args.maxiter, only=set(args.only) if args.only else None)


if __name__ == "__main__":
    main()
