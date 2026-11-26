#!/usr/bin/env python3
"""Single canonical Water World V2.1 source/asset mask metric engine."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

MASTER_SHA = "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589"
SIZE = (1254, 1254)
THRESHOLDS = {
    "sphere_iou": {"min": 0.98},
    "sphere_center_error_over_diameter": {"max": 0.005},
    "sphere_diameter_error": {"max": 0.01},
    "fish_iou": {"min": 0.90},
    "fish_center_error_over_canvas": {"max": 0.01},
    "resident_bidirectional_chamfer_over_radius": {"max": 0.015},
    "resident_width_error": {"max": 0.05},
    "plant_iou": {"min": 0.85},
    "rock_iou": {"min": 0.85},
}
OBJECTS = {
    "fish": [f"WW_FISH_{i:02d}" for i in range(1, 5)],
    "resident": ["G01", "G02", "A01", "A02", "C01"],
    "plant": ["plant_left", "plant_right"],
    "rock": ["rock_01", "rock_02", "rock_03"],
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def binary(path: Path, threshold: int = 128) -> np.ndarray:
    image = Image.open(path).convert("L")
    if image.size != SIZE:
        raise ValueError(f"mask dimensions {path}: {image.size} != {SIZE}")
    return np.asarray(image, dtype=np.uint8) >= threshold


def iou(a: np.ndarray, b: np.ndarray) -> float:
    if a.shape != b.shape:
        raise ValueError(f"IoU shape mismatch: {a.shape} vs {b.shape}")
    union = int(np.logical_or(a, b).sum())
    return float(np.logical_and(a, b).sum() / union) if union else 1.0


def centroid(mask: np.ndarray) -> tuple[float, float]:
    y, x = np.where(mask)
    if not len(x):
        raise ValueError("empty mask has no centroid")
    return float(x.mean()), float(y.mean())


def equivalent_diameter(mask: np.ndarray) -> float:
    area = int(mask.sum())
    if not area:
        raise ValueError("empty mask has no equivalent diameter")
    return 2.0 * math.sqrt(area / math.pi)


def bidirectional_chamfer(a: np.ndarray, b: np.ndarray, normalization_radius: float) -> float:
    if a.shape != b.shape:
        raise ValueError(f"Chamfer shape mismatch: {a.shape} vs {b.shape}")
    ay, ax = np.where(a)
    by, bx = np.where(b)
    if not len(ax) or not len(bx):
        return math.inf
    da = ndimage.distance_transform_edt(~a)
    db = ndimage.distance_transform_edt(~b)
    value = (float(da[by, bx].mean()) + float(db[ay, ax].mean())) * 0.5
    return value / normalization_radius


def relative_width_error(a: np.ndarray, b: np.ndarray) -> float:
    """Median local full-width difference from the medial distance ridges."""
    widths = []
    for mask in (a, b):
        if not mask.any():
            return math.inf
        edt = ndimage.distance_transform_edt(mask)
        ridge = mask & (edt >= ndimage.maximum_filter(edt, size=3))
        widths.append(2.0 * edt[ridge])
    wa, wb = float(np.median(widths[0])), float(np.median(widths[1]))
    return abs(wa - wb) / max(wa, 1e-9)


def passed(value: float | None, threshold: dict) -> bool:
    # Compare full precision. Formatting is never used for gate decisions.
    if value is None or not math.isfinite(value):
        return False
    return value >= threshold["min"] if "min" in threshold else value <= threshold["max"]


def object_status(metric_pass: bool, confidence: str | None, complete: bool) -> str:
    """A passing number cannot overrule incomplete/low-confidence source labels."""
    if not complete or confidence not in {"HIGH", "MEDIUM_HIGH"}:
        return "BLOCKED_GROUND_TRUTH_UNCERTAIN"
    return "PASS" if metric_pass else "FAIL"


def run(package: Path) -> dict:
    gt_root = package / "01_ANALYSIS/ground_truth/objects"
    out_root = package / "01_ANALYSIS/masks"
    registry_path = package / "01_ANALYSIS/ground_truth/source_object_registry.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    if registry.get("master_sha256") != MASTER_SHA or registry.get("object_count") != 14:
        raise ValueError("ground-truth registry source identity or object count mismatch")
    radius_path = package / "01_ANALYSIS/master_sphere_geometry.json"
    sphere_geometry = json.loads(radius_path.read_text(encoding="utf-8"))
    radius = float(sphere_geometry["radius_px"])
    metrics: dict = {}
    per_object: dict = {}
    confidence_blockers: list[str] = []

    master_sphere = binary(out_root / "master_sphere_outer.png")
    composition_sphere = binary(out_root / "composition_sphere_outer.png")
    scx, scy = centroid(master_sphere)
    ocx, ocy = centroid(composition_sphere)
    source_d = equivalent_diameter(master_sphere)
    output_d = equivalent_diameter(composition_sphere)
    metrics["sphere_iou"] = iou(master_sphere, composition_sphere)
    metrics["sphere_center_error_over_diameter"] = math.dist((scx, scy), (ocx, ocy)) / source_d
    metrics["sphere_diameter_error"] = abs(output_d - source_d) / source_d

    fish_iou, fish_center = {}, {}
    for object_id in OBJECTS["fish"]:
        target = binary(gt_root / object_id / "source_visible_mask.png")
        actual = binary(out_root / f"composition_fish_{object_id.lower()}.png")
        source_center, output_center = centroid(target), centroid(actual)
        value = iou(target, actual)
        center_error = math.dist(source_center, output_center) / SIZE[0]
        fish_iou[object_id] = value
        fish_center[object_id] = center_error
        report = json.loads((gt_root / object_id / "segmentation_report.json").read_text(encoding="utf-8"))
        confidence = report.get("master_confidence_label")
        status = object_status(passed(value, THRESHOLDS["fish_iou"])
                               and passed(center_error, THRESHOLDS["fish_center_error_over_canvas"]),
                               confidence, bool(report.get("full_mask_complete", False)))
        if status == "BLOCKED_GROUND_TRUTH_UNCERTAIN":
            confidence_blockers.append(object_id)
        per_object[object_id] = {"kind": "fish", "iou": value, "center_error_over_canvas": center_error,
                                 "threshold_iou": 0.90, "threshold_center_error_over_canvas": 0.01,
                                 "status": status, "source_visible_area_px": int(target.sum()),
                                 "asset_visible_area_px": int(actual.sum()),
                                 "ground_truth_confidence": confidence}
    metrics["fish_iou"] = fish_iou
    metrics["fish_center_error_over_canvas"] = fish_center

    resident_chamfer, resident_width = {}, {}
    source_v2 = package / "02_CALIBRATED_ASSETS/V2_ORIGINAL/02_RESIDENT/residents.json"
    source_widths = {}
    if source_v2.is_file():
        for row in json.loads(source_v2.read_text(encoding="utf-8" )).get("residents", []):
            source_widths[row["id"]] = float(row["width_px"])
    for object_id in OBJECTS["resident"]:
        target = binary(gt_root / object_id / "source_visible_mask.png")
        centerline_path = out_root / f"composition_resident_centerline_{object_id.lower()}.png"
        if not centerline_path.is_file():
            centerline_path = out_root / f"composition_resident_{object_id.lower()}.png"
        actual = binary(centerline_path)
        source_radius = float(sphere_geometry.get("radius_px", radius))
        chamfer = bidirectional_chamfer(target, actual, source_radius)
        report = json.loads((gt_root / object_id / "segmentation_report.json").read_text(encoding="utf-8"))
        measured_width = report.get("source_measured_median_width_px")
        source_svg_width = source_widths.get(object_id)
        # The current RGB/HSV cross-section heuristic is not identity-safe at
        # Resident intersections and clips broad/highlighted regions. Preserve
        # its exploratory estimate, but never turn it into an acceptance score.
        width_error = None
        resident_chamfer[object_id] = chamfer
        resident_width[object_id] = width_error
        direct_coverage = float(report.get("direct_trace_coverage", 0.0))
        confidence = report.get("confidence", "LOW")
        status = object_status(passed(chamfer, THRESHOLDS["resident_bidirectional_chamfer_over_radius"])
                               and passed(width_error, THRESHOLDS["resident_width_error"])
                               and direct_coverage >= 0.90, confidence,
                               direct_coverage >= 0.90)
        if status == "BLOCKED_GROUND_TRUTH_UNCERTAIN":
            status = "BLOCKED_GROUND_TRUTH_UNCERTAIN"
            confidence_blockers.append(object_id)
        per_object[object_id] = {"kind": "resident", "bidirectional_chamfer_over_radius": chamfer,
                                 "width_error": width_error, "source_median_width_px": measured_width,
                                 "width_measurement_status": "UNRESOLVED_IDENTITY_AND_OPTICAL_EDGE_AMBIGUITY",
                                 "v2_svg_width_px": source_svg_width, "direct_trace_coverage": direct_coverage,
                                 "threshold_chamfer_over_radius": 0.015, "threshold_width_error": 0.05,
                                 "status": status, "ground_truth_confidence": confidence}
    metrics["resident_bidirectional_chamfer_over_radius"] = resident_chamfer
    metrics["resident_width_error"] = resident_width

    for kind, metric_name in (("plant", "plant_iou"), ("rock", "rock_iou")):
        values = {}
        threshold = THRESHOLDS[metric_name]
        for object_id in OBJECTS[kind]:
            target = binary(gt_root / object_id / "source_visible_mask.png")
            output_name = f"composition_{kind}_{object_id}.png"
            actual = binary(out_root / output_name)
            value = iou(target, actual)
            values[object_id] = value
            report = json.loads((gt_root / object_id / "segmentation_report.json").read_text(encoding="utf-8"))
            confidence = report.get("master_confidence_label")
            status = object_status(passed(value, threshold), confidence,
                                   bool(report.get("full_mask_complete", False)))
            if status == "BLOCKED_GROUND_TRUTH_UNCERTAIN":
                confidence_blockers.append(object_id)
            per_object[object_id] = {"kind": kind, "iou": value, "threshold_iou": threshold["min"],
                                     "status": status, "source_visible_area_px": int(target.sum()),
                                     "asset_visible_area_px": int(actual.sum()),
                                     "ground_truth_confidence": confidence}
        metrics[metric_name] = values

    # Every metric family uses a single threshold table and full precision.
    gate_status = {}
    for key, threshold in THRESHOLDS.items():
        family = metrics[key]
        values = list(family.values()) if isinstance(family, dict) else [family]
        gate_status[key] = "PASS" if all(passed(x, threshold) for x in values) else "FAIL"
    blocked = sorted(set(confidence_blockers))
    overall = "PASS" if all(v == "PASS" for v in gate_status.values()) and not blocked else "BLOCKED_GROUND_TRUTH_UNCERTAIN" if blocked else "WORK1_5_VALIDATION_FAILED"
    result = {
        "schema_version": "water-world-v2-1-canonical-visual-metrics/2.0",
        "master_sha256": MASTER_SHA,
        "coordinate_contract": {"width_px": SIZE[0], "height_px": SIZE[1], "origin": "top-left pixel center", "x_axis": "right", "y_axis": "down"},
        "mask_contract": {"threshold": 128, "ground_truth": "source_visible_mask.png", "asset": "composition_* mask generated from independent V2 assets", "occlusion": "unknown/invisible regions excluded only where directly classified; uncertain ground truth blocks acceptance"},
        "comparison": {"master_dimensions_px": list(SIZE), "recomposition_dimensions_px": list(SIZE),
                       "render_type": "CPU_STATIC_RECOMPOSITION_FROM_INDEPENDENT_V2_ASSETS", "gpu_render": False,
                       "master_used_as_composition_layer": False, "color_space": "source sRGB RGB; masks binary at threshold 128"},
        "thresholds": THRESHOLDS, "metrics": metrics, "gate_status": gate_status,
        "per_object": per_object, "blocked_ground_truth_objects": blocked,
        "overall_status": overall,
        "metric_method": "tools/water-world-v2-1/canonical_metrics.py; this is the sole writer of final visual_metrics.json",
        "limitations": ["CPU static asset composition only; GPU and motion are not verified.",
                        "Resident source trace coverage below 90% is not extrapolated.",
                        "Plant/rock and some fish boundaries are affected by optical overlap or partial occlusion; flagged objects block acceptance.",
                        "Resident width error is unresolved: current cross-section estimates are contaminated by identity crossings and optical highlights; null is a failed gate, not a pass."],
    }
    out = package / "03_STATIC_RECOMPOSITION/visual_metrics.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")

    # Geometry reports reference the exact canonical object values, avoiding
    # duplicated/stale hand-copied measurements.
    fish_path = package / "01_ANALYSIS/master_fish_geometry.json"
    resident_path = package / "01_ANALYSIS/master_resident_geometry.json"
    ecology_path = package / "01_ANALYSIS/master_ecology_geometry.json"
    for path, kind in ((fish_path, "fish"), (resident_path, "resident"), (ecology_path, "ecology")):
        if not path.is_file():
            continue
        value = json.loads(path.read_text(encoding="utf-8"))
        if kind == "fish": ids = OBJECTS["fish"]
        elif kind == "resident": ids = OBJECTS["resident"]
        else: ids = OBJECTS["plant"] + OBJECTS["rock"]
        value["canonical_metrics_sha256"] = sha(out.read_bytes())
        value["canonical_metrics"] = {obj: per_object[obj] for obj in ids}
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package-root", type=Path, required=True)
    args = parser.parse_args()
    result = run(args.package_root)
    print(json.dumps({"overall_status": result["overall_status"], "gate_status": result["gate_status"],
                      "blocked_ground_truth_objects": result["blocked_ground_truth_objects"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
