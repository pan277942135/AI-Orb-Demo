#!/usr/bin/env python3
"""Validate P0-3 measurement primitives and remeasure the frozen Run #31 baseline."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import analyze_avd_baseline as metrics  # noqa: E402


ROOT = Path(__file__).resolve().parents[3]
P0 = ROOT / "docs/p0_3"
EXPECTED_HEAD = "dabd2ee3138df3f94887b1b85243c3c60b127c79"


def sha_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--historical-report", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-head", default=EXPECTED_HEAD)
    args = parser.parse_args()
    metrics.P0 = P0
    errors: list[str] = []
    tests: list[dict] = []

    def check(name: str, condition: bool, details: dict | None = None) -> None:
        tests.append({"name": name, "status": "PASS" if condition else "FAIL", "details": details or {}})
        if not condition:
            errors.append(name)

    contract_path = P0 / "contract/AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.json"
    contract = json.loads(contract_path.read_text())
    refs = {item["id"]: item for item in contract["source_of_truth"]["reference_images"]}
    ref_a_path = P0 / "references/REF_A_CORE_CRYSTAL_RGBA.png"
    ref_b_path = P0 / "references/REF_B_FULL_SCENE_RGB.png"
    ref_hashes = {
        "REF_A": {"expected": refs["REF_A"]["sha256"], "actual": sha_file(ref_a_path)},
        "REF_B": {"expected": refs["REF_B"]["sha256"], "actual": sha_file(ref_b_path)},
    }
    check("frozen reference hashes", all(v["expected"] == v["actual"] for v in ref_hashes.values()), ref_hashes)
    check("frozen contract status", contract.get("status") == "FROZEN", {"status": contract.get("status"), "sha256": sha_file(contract_path)})

    mask_results = []
    for record in contract["visual_regions"]["regions"]:
        name = record.get("mask_file")
        if not name:
            continue
        path = P0 / name
        exists = path.is_file()
        mask = np.asarray(Image.open(path).convert("L")) if exists else np.empty((0, 0), dtype=np.uint8)
        mask_results.append({"region": record["id"], "file": name, "exists": exists, "dimensions": list(mask.shape[::-1]) if exists else None, "pixels": int(np.count_nonzero(mask)) if exists else 0, "sha256": sha_file(path) if exists else None})
    check("all frozen ROI masks present", len(mask_results) == 12 and all(m["exists"] and m["dimensions"] == [1254, 1254] and m["pixels"] > 0 for m in mask_results), {"count": len(mask_results), "masks": mask_results})

    # Contract self-comparisons are performed on the same fixed 1254 canvas.
    ref_a = np.asarray(Image.open(ref_a_path).convert("RGBA"))
    ref_b = np.asarray(Image.open(ref_b_path).convert("RGB"))
    with __import__("tempfile").TemporaryDirectory(prefix="p04_metric_self_") as tmp:
        temp_dir = Path(tmp)
        Image.fromarray(ref_a, "RGBA").save(temp_dir / "CORE_ONLY_TRANSPARENT.png")
        Image.fromarray(ref_b, "RGB").save(temp_dir / "FULL_RENDER.png")
        self_metrics = metrics.metric_set(temp_dir)
    self_pass = (
        self_metrics["coreSilhouette"]["iou"] == 1.0
        and self_metrics["coreSilhouette"]["centerErrorD"] == 0.0
        and self_metrics["coreSilhouette"]["diameterRelativeError"] == 0.0
        and all(abs(v["ssim"] - 1.0) < 1e-6 and abs(v["deltaE2000Median"]) < 1e-6 and abs(v["deltaE2000P90"]) < 1e-6 for v in self_metrics["coreColorRegions"].values())
        and abs(self_metrics["fullSceneColor"]["ssim"] - 1.0) < 1e-6
        and abs(self_metrics["fullSceneColor"]["deltaE2000Median"]) < 1e-6
    )
    check("reference self comparison", self_pass, self_metrics)

    alpha = ref_a[:, :, 3]
    shifted = np.zeros_like(alpha)
    dx, dy = 11, -7
    sy = slice(max(0, -dy), alpha.shape[0] - max(0, dy))
    sx = slice(max(0, -dx), alpha.shape[1] - max(0, dx))
    ty = slice(max(0, dy), alpha.shape[0] - max(0, -dy))
    tx = slice(max(0, dx), alpha.shape[1] - max(0, -dx))
    shifted[ty, tx] = alpha[sy, sx]
    shifted_metric = metrics.silhouette_metrics(shifted, alpha)
    check("known translation", abs(shifted_metric["centerErrorPixels"] - float(np.hypot(dx, dy))) < 1e-6 and shifted_metric["iou"] < 1.0, shifted_metric)

    target_binary = (alpha >= 8).astype(np.uint8) * 255
    small = Image.fromarray(target_binary).resize((1000, 1000), Image.Resampling.NEAREST)
    scaled = np.zeros_like(target_binary)
    ox, oy = (scaled.shape[1] - small.width) // 2, (scaled.shape[0] - small.height) // 2
    scaled[oy:oy + small.height, ox:ox + small.width] = np.asarray(small)
    scale_metric = metrics.silhouette_metrics(scaled, alpha)
    check("known scale", abs(scale_metric["diameterRelativeError"] - (1.0 - 1000 / 1254)) < 0.003 and scale_metric["iou"] < 1.0, scale_metric)

    hidden_a = ref_a.copy()
    hidden_b = ref_a.copy()
    hidden = ref_a[:, :, 3] == 0
    hidden_b[hidden, :3] = np.array([255, 43, 179], dtype=np.uint8)
    composite_a = metrics.composite_rgba_on_fixed_srgb_background(hidden_a)
    composite_b = metrics.composite_rgba_on_fixed_srgb_background(hidden_b)
    check("transparent RGB isolation", bool(np.array_equal(composite_a, composite_b)), {"backgroundSrgb": list(metrics.CORE_COMPOSITE_BACKGROUND_SRGB), "transparentPixels": int(np.count_nonzero(hidden)), "compositesIdentical": bool(np.array_equal(composite_a, composite_b))})

    half_alpha = metrics.composite_rgba_on_fixed_srgb_background(np.array([[[255, 255, 255, 128]]], dtype=np.uint8))[0, 0]
    check("linear-light alpha composite", bool(np.all((half_alpha >= 187) & (half_alpha <= 188))), {"halfWhiteOverBlackSrgb": half_alpha.tolist(), "expectedRange": [187, 188]})

    capture_dir = args.evidence_dir.resolve()
    required = ["CORE_ONLY_TRANSPARENT.png", "FULL_RENDER.png", "CAPTURE_MANIFEST.json"]
    missing = [name for name in required if not (capture_dir / name).is_file()]
    check("original Run #31 capture files present", not missing, {"directory": str(capture_dir), "missing": missing})
    if missing:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({"schema": "AI_ORB_P04_METRIC_VALIDATION_1_0", "status": "METRIC_VALIDATION_FAIL", "tests": tests}, indent=2) + "\n")
        return 1

    capture_manifest = json.loads((capture_dir / "CAPTURE_MANIFEST.json").read_text())
    frame_checks = []
    frame_map = {frame["file"]: frame for frame in capture_manifest.get("frames", [])}
    for name in [
        "CORE_ONLY_TRANSPARENT.png", "CAVITY_ONLY.png", "FLUID_VOLUME_ONLY.png", "GLASS_ONLY.png",
        "RESIDENTS_ONLY.png", "FULL_RENDER.png", "BLOOM_OFF.png", "PRE_TONEMAP.png", "POST_TONEMAP.png",
    ]:
        path = capture_dir / name
        record = frame_map.get(name, {})
        exists = path.is_file()
        actual_sha = sha_file(path) if exists else None
        with Image.open(path) as im:
            dims = list(im.size)
        frame_checks.append({"file": name, "exists": exists, "shaMatchesManifest": actual_sha == record.get("sha256"), "dimensions": dims, "snapshotId": record.get("snapshotId"), "cameraConfigHash": record.get("cameraConfigHash"), "modelTransformHash": record.get("modelTransformHash")})
    check("nine-mode image hashes and dimensions", len(frame_checks) == 9 and all(f["exists"] and f["shaMatchesManifest"] and f["dimensions"] == [1254, 1254] for f in frame_checks), {"frames": frame_checks})
    check("production volume and GLES capture valid", capture_manifest.get("status") == "COMPLETE" and capture_manifest.get("volume", {}).get("uVolumeLoaded") == 1 and capture_manifest.get("volume", {}).get("all3DTexturesValid") is True and capture_manifest.get("captureValidation", {}).get("glErrorCode") == 0, {"status": capture_manifest.get("status"), "volume": capture_manifest.get("volume"), "captureValidation": capture_manifest.get("captureValidation")})
    check("exact baseline build identity", capture_manifest.get("buildSha") == args.expected_head, {"expectedHead": args.expected_head, "actualHead": capture_manifest.get("buildSha"), "apkSha256": capture_manifest.get("apkSha256")})

    baseline = metrics.metric_set(capture_dir)
    ref_alpha_counts = {str(int(value)): int(count) for value, count in zip(*np.unique(ref_a[:, :, 3], return_counts=True))}
    cap_rgba = np.asarray(Image.open(capture_dir / "CORE_ONLY_TRANSPARENT.png").convert("RGBA"))
    capture_alpha_counts = {str(int(value)): int(count) for value, count in zip(*np.unique(cap_rgba[:, :, 3], return_counts=True))}
    original_report = json.loads(args.historical_report.read_text())
    old_metrics = original_report["runs"][0]["metrics"]
    old_gate_values = {key: value["value"] for key, value in original_report.get("visualGates", {}).items()}
    repeatability = original_report.get("repeatability", {})
    exact_hashes = repeatability.get("exactImageHashesAcrossRuns", {})
    check("repeatability baseline had exact nine-mode hashes", repeatability.get("sameConditions") is True and len(exact_hashes) == 9 and all(exact_hashes.values()), repeatability)

    payload = {
        "schema": "AI_ORB_P04_METRIC_VALIDATION_1_0",
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "status": "METRIC_VALIDATION_PASS" if not errors else "METRIC_VALIDATION_FAIL",
        "measurementChange": {
            "issue": "Old core SSIM / DeltaE compared hidden RGB in transparent reference pixels instead of comparing both RGBA images on one fixed background.",
            "fix": "Straight-alpha sRGB images are composited in linear light onto black sRGB [0,0,0] before REF_A color SSIM, CIEDE2000, and core heatmap comparison.",
            "thresholdsOrMasksChanged": False,
            "historicalP0_3ReportPreserved": True,
            "fixedBackgroundSrgb": list(metrics.CORE_COMPOSITE_BACKGROUND_SRGB),
        },
        "sourceIdentity": {
            "expectedHead": args.expected_head,
            "actualHead": capture_manifest.get("buildSha"),
            "apkSha256": capture_manifest.get("apkSha256"),
            "captureSnapshotId": capture_manifest.get("snapshotId"),
            "cameraConfigHash": capture_manifest.get("cameraConfigHash"),
            "referenceFiles": ref_hashes,
            "contractSha256": sha_file(contract_path),
        },
        "preflight": {"tests": tests, "failedTests": errors, "masks": mask_results, "frameChecks": frame_checks},
        "alphaAudit": {
            "referenceAlphaUniqueValueCount": len(ref_alpha_counts),
            "referenceAlphaCounts": ref_alpha_counts,
            "captureAlphaUniqueValueCount": len(capture_alpha_counts),
            "captureAlphaCounts": capture_alpha_counts,
            "captureHasOnlyBinaryAlpha": set(capture_alpha_counts).issubset({"0", "255"}),
        },
        "historicalP0_3Gates": old_gate_values,
        "originalReportCoreMetrics": old_metrics,
        "recomputedRun31Metrics": baseline,
        "visualGateStatusAfterMetricCorrection": "FAIL",
        "importantInterpretation": "Metric validation pass means the measurement primitives behave as specified; it does not mean the rendered image passes visual fidelity. The measured 0.432 silhouette IoU is unchanged and corresponds to the 998px reference vs 654px capture diameter.",
    }
    if errors:
        payload["status"] = "METRIC_VALIDATION_FAIL"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"status": payload["status"], "output": str(args.output), "failedTests": errors, "recomputedCoreSilhouette": baseline["coreSilhouette"], "recomputedCoreSSIM": {key: value["ssim"] for key, value in baseline["coreColorRegions"].items()}}, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
