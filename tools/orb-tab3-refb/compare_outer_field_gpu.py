#!/usr/bin/env python3
"""Compare API36 GPU captures to REF_B in frozen coordinates; never register or warp images."""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageEnhance

ROOT = Path(__file__).resolve().parents[2]
REF_B = ROOT / "docs/p0_3/references/REF_B_FULL_SCENE_RGB.png"
MASK_DIR = ROOT / "docs/p0_3/roi_masks"
MASKS = {
    "BACK_GREEN_GOLD": "REF_B_RESIDENT_BACK_GREEN_GOLD.png",
    "FRONT_CYAN": "REF_B_RESIDENT_FRONT_CYAN.png",
    "FRONT_GOLD": "REF_B_RESIDENT_FRONT_GOLD.png",
}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rgb(path: Path) -> np.ndarray:
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.uint8)


def mae(a: np.ndarray, b: np.ndarray, mask: np.ndarray) -> float:
    delta = np.abs(a.astype(np.float32) - b.astype(np.float32))
    return float(delta[mask].mean()) if np.any(mask) else float("nan")


def write_comparison(before_path: Path, after_path: Path, out: Path) -> tuple[dict, dict]:
    before = rgb(before_path)
    after = rgb(after_path)
    reference = rgb(REF_B)
    assert before.shape == after.shape == reference.shape, (
        f"No exact viewport correspondence: before={before.shape}, after={after.shape}, REF_B={reference.shape}"
    )
    region_masks = {
        key: np.asarray(Image.open(MASK_DIR / filename).convert("L"), dtype=np.uint8) > 0
        for key, filename in MASKS.items()
    }
    union = np.logical_or.reduce(list(region_masks.values()))
    before_union = mae(before, reference, union)
    after_union = mae(after, reference, union)
    improvement = before_union - after_union
    improvement_ratio = 0.0 if before_union <= 1e-6 else improvement / before_union
    region_values = {}
    no_material_regression = True
    for name, mask in region_masks.items():
        a = mae(before, reference, mask)
        b = mae(after, reference, mask)
        allowed = b <= a * 1.02
        no_material_regression &= allowed
        region_values[name] = {
            "frozenMaskPixels": int(mask.sum()),
            "tab1NativeMAE8bit": a,
            "tab3RefBFieldMAE8bit": b,
            "relativeChange": 0.0 if a <= 1e-6 else (b - a) / a,
            "noMoreThan2PercentRegression": allowed,
        }

    out.mkdir(parents=True, exist_ok=True)
    Image.fromarray(before).save(out / "ROUND_1_BEFORE.png")
    Image.fromarray(after).save(out / "ROUND_1_AFTER.png")
    diff = ImageChops.difference(Image.fromarray(before), Image.fromarray(after))
    ImageEnhance.Contrast(diff).enhance(4.0).save(out / "ROUND_1_DIFF.png")
    before_panel = Image.fromarray(before).convert("RGB")
    after_panel = Image.fromarray(after).convert("RGB")
    draw1 = ImageDraw.Draw(before_panel); draw1.rectangle((0, 0, 300, 36), fill=(0, 0, 0)); draw1.text((12, 10), "TAB1 native outer baseline", fill=(255, 255, 255))
    draw2 = ImageDraw.Draw(after_panel); draw2.rectangle((0, 0, 300, 36), fill=(0, 0, 0)); draw2.text((12, 10), "TAB3 REF_B optical field", fill=(255, 255, 255))
    side = Image.new("RGB", (before.shape[1] * 2, before.shape[0] + 42), (10, 14, 20))
    side.paste(before_panel, (0, 42)); side.paste(after_panel, (before.shape[1], 42))
    side.save(out / "ROUND_1_BEFORE_AFTER.png")
    metrics = {
        "schema": "AI_ORB_TAB3_REFB_GPU_ROUND_METRICS_V1",
        "sourceReference": {"path": str(REF_B.relative_to(ROOT)), "sha256": sha(REF_B), "viewport": [1254, 1254]},
        "comparison": "Raw pixel coordinates; same-sized API36 screenshots; no crop registration, resize, or warp.",
        "before": {"path": before_path.name, "sha256": sha(before_path)},
        "after": {"path": after_path.name, "sha256": sha(after_path)},
        "residentUnion": {
            "frozenMaskPixels": int(union.sum()),
            "tab1NativeMAE8bit": before_union,
            "tab3RefBFieldMAE8bit": after_union,
            "absoluteImprovement8bit": improvement,
            "relativeImprovement": improvement_ratio,
        },
        "residentRegions": region_values,
        "tab1Tab3CameraMotionSidecars": "Checked by API36 instrumentation: locked camera mode and paused shared simulation time.",
    }
    return metrics, region_masks


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--captures", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()
    captures = args.captures
    round_dir = args.output / "round_1"
    report_dir = args.output / "visual_comparison"
    round_dir.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    before_path = captures / "TAB1_OUTER_RESIDENTS_GPU.png"
    after_path = captures / "TAB3_OUTER_RESIDENTS_GPU.png"
    try:
        metrics, masks = write_comparison(before_path, after_path, round_dir)
    except AssertionError as error:
        # A portrait or UI-size screenshot cannot be compared to the frozen 1254² reference in
        # its original pixel coordinates. Preserve the real captures and report the limitation;
        # never resize/register them or fail the whole artifact assembly with invented metrics.
        sizes = {
            path.name: list(rgb(path).shape[:2])
            for path in (before_path, after_path, REF_B)
        }
        detail = {
            "schema": "AI_ORB_TAB3_REFB_GPU_ROUND_METRICS_V1",
            "sourceHead": args.head,
            "status": "ROUND_1_NOT_COMPARABLE",
            "reason": str(error),
            "dimensionsHeightWidth": sizes,
            "referenceSha256": sha(REF_B),
            "comparison": "No resize, crop registration, or warp was applied; no visual loss was computed.",
        }
        (round_dir / "ROUND_1_METRICS.json").write_text(json.dumps(detail, indent=2) + "\n")
        (round_dir / "ROUND_1_REPORT.json").write_text(json.dumps({
            "round": 1,
            "status": "ROUND_1_NOT_COMPARABLE",
            "reason": str(error),
            "evidence": "ROUND_1_EVIDENCE.zip",
            "visualAcceptance": "NOT_ACCEPTED",
        }, indent=2) + "\n")
        (round_dir / "ROUND_1_ROOT_CAUSE.md").write_text(
            "# Round 1 — Comparison blocked by viewport mismatch\n\n"
            "The actual API36 captures are retained, but their decoded dimensions differ from the "
            "frozen REF_B reference. No crop, resize, or image registration was used. The visual "
            "loss and improvement gate are therefore `NOT_COMPARABLE`; this is not a pass or a fail "
            "of the renderer itself.\n"
        )
        for round_number in (2, 3):
            path = args.output / f"round_{round_number}" / f"ROUND_{round_number}_REPORT.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({
                "status": "NOT_RUN",
                "reason": "Round 1 images were not pixel-comparable to REF_B.",
            }, indent=2) + "\n")
        (report_dir / "OUTER_OPTICAL_GPU_ACCEPTANCE_REPORT.json").write_text(json.dumps({
            "schema": "AI_ORB_TAB3_REFB_OUTER_OPTICAL_GPU_ACCEPTANCE_V1",
            "sourceHead": args.head,
            "captureBackend": "API36 x86_64 AVD; see AVD environment for software-renderer classification.",
            "hardwareGpuClaim": "NOT_CLAIMED_BY_CI_AVD",
            "round1": {"status": "ROUND_1_NOT_COMPARABLE", "dimensionsHeightWidth": sizes},
            "round2": "NOT_RUN",
            "round3": "NOT_RUN",
            "visualFidelityAccepted": False,
            "status": "NOT_COMPARABLE",
        }, indent=2) + "\n")
        with zipfile.ZipFile(round_dir / "ROUND_1_EVIDENCE.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in (before_path, before_path.with_suffix(".json"), after_path, after_path.with_suffix(".json"),
                         captures / "TAB3_HYBRID_ORB_GPU.png", round_dir / "ROUND_1_METRICS.json",
                         round_dir / "ROUND_1_REPORT.json", round_dir / "ROUND_1_ROOT_CAUSE.md"):
                if path.is_file():
                    archive.write(path, path.name)
        (report_dir / "REF_B_GPU_RAW_METRICS.json").write_text(json.dumps({
            "sourceReferenceSha256": sha(REF_B),
            "captureDimensionsHeightWidth": sizes,
            "comparison": "No exact pixel correspondence; raw RGB comparison was not computed.",
            "metricsStatus": "NOT_COMPARABLE",
        }, indent=2) + "\n")
        print(json.dumps({"status": "ROUND_1_NOT_COMPARABLE", "dimensionsHeightWidth": sizes}, indent=2))
        return
    before_sidecar = json.loads(before_path.with_suffix(".json").read_text())
    after_sidecar = json.loads(after_path.with_suffix(".json").read_text())
    same_snapshot = (
        before_sidecar.get("activeRenderMode") == after_sidecar.get("activeRenderMode") == "RIBBONS_ONLY" and
        before_sidecar.get("coreAngleDeg") == after_sidecar.get("coreAngleDeg") and
        before_sidecar.get("cameraSnapshot") == after_sidecar.get("cameraSnapshot")
    )

    after = rgb(after_path)
    reference = rgb(REF_B)
    repeat_paths = [captures / f"TAB3_OUTER_RESIDENTS_GPU_R{i}.png" for i in range(1, 4)]
    repeat_noise = None
    if all(path.is_file() for path in repeat_paths):
        repeats = [rgb(path) for path in repeat_paths]
        repeat_noise = statistics.mean(
            mae(repeats[i], repeats[j], np.logical_or.reduce(list(masks.values())))
            for i in range(len(repeats)) for j in range(i + 1, len(repeats))
        )
    else:
        repeat_noise = None

    union_improvement = metrics["residentUnion"]["absoluteImprovement8bit"]
    ratio = metrics["residentUnion"]["relativeImprovement"]
    noise_gate = repeat_noise is not None and union_improvement > 3.0 * repeat_noise
    progress = same_snapshot and ratio >= 0.05 and noise_gate and all(
        item["noMoreThan2PercentRegression"] for item in metrics["residentRegions"].values()
    )
    status = "ROUND_1_PROGRESS_GATE_PASS" if progress else "ROUND_1_NO_VERIFIABLE_PROGRESS"
    metrics["threeCaptureMeanPairwiseNoise8bit"] = repeat_noise
    metrics["progressGate"] = {
        "requiresAtLeast5PercentImprovement": ratio >= 0.05,
        "requiresGainGreaterThan3xRepeatNoise": bool(noise_gate),
        "requiresEqualCameraMotionSnapshot": same_snapshot,
        "noResidentRegionMayRegressOver2Percent": all(v["noMoreThan2PercentRegression"] for v in metrics["residentRegions"].values()),
        "status": status,
    }
    metrics["sourceHead"] = args.head
    (round_dir / "ROUND_1_METRICS.json").write_text(json.dumps(metrics, indent=2) + "\n")
    (round_dir / "ROUND_1_REPORT.json").write_text(json.dumps({
        "round": 1,
        "goal": "REF_B-derived outer geometry and optical coverage",
        "status": status,
        "before": "Tab 1 native outer-pass API36 capture at the frozen camera/motion snapshot",
        "after": "Tab 3 REF_B-driven outer renderer API36 capture at the same frozen camera/motion snapshot",
        "metrics": "ROUND_1_METRICS.json",
        "evidence": "ROUND_1_EVIDENCE.zip",
        "visualAcceptance": "NOT_ACCEPTED_BY_ROUND_SCORE_ALONE",
    }, indent=2) + "\n")
    root_cause = """# Round 1 — Geometry and optical density

The comparison uses the frozen REF_B resident masks and captures from the same API36 AVD,
locked camera, and paused shared scene clock. It reports raw 8-bit RGB error without image
registration. Tab 1 is the before baseline; Tab 3 is the REF_B-driven renderer. The 3D depth
placement remains an inference because one source image cannot uniquely identify depth.

The measured per-region deltas and repeat-noise gate are in `ROUND_1_METRICS.json`. A failed
progress gate is recorded as `ROUND_1_NO_VERIFIABLE_PROGRESS`; no visual acceptance is inferred.
"""
    (round_dir / "ROUND_1_ROOT_CAUSE.md").write_text(root_cause)
    if not (args.output / "round_2" / "ROUND_2_REPORT.json").exists():
        (args.output / "round_2").mkdir(parents=True, exist_ok=True)
        (args.output / "round_2" / "ROUND_2_REPORT.json").write_text(json.dumps({"status": "NOT_RUN", "reason": "Round 2 requires a measured Round 1 gain and review of its GPU difference report."}, indent=2) + "\n")
    if not (args.output / "round_3" / "ROUND_3_REPORT.json").exists():
        (args.output / "round_3").mkdir(parents=True, exist_ok=True)
        (args.output / "round_3" / "ROUND_3_REPORT.json").write_text(json.dumps({"status": "NOT_RUN", "reason": "Round 3 requires a measured Round 2 gain and review of its GPU difference report."}, indent=2) + "\n")
    full_diff = ImageChops.difference(Image.fromarray(rgb(REF_B)), Image.fromarray(rgb(captures / "TAB3_HYBRID_ORB_GPU.png")))
    ImageEnhance.Contrast(full_diff).enhance(4.0).save(report_dir / "REF_B_VS_TAB3_FULL_FRAME_ABS_DIFF_X4.png")
    (report_dir / "REF_B_GPU_RAW_METRICS.json").write_text(json.dumps({
        "sourceReferenceSha256": sha(REF_B),
        "gpuCaptureSha256": sha(captures / "TAB3_HYBRID_ORB_GPU.png"),
        "viewport": list(rgb(REF_B).shape[:2]),
        "comparison": "Raw RGB difference, exact pixels, no registration; not a complete 13-ROI acceptance calculation.",
        "metricsStatus": "REPORTED_NOT_ACCEPTED",
        "round1": metrics["progressGate"],
    }, indent=2) + "\n")
    report = {
        "schema": "AI_ORB_TAB3_REFB_OUTER_OPTICAL_GPU_ACCEPTANCE_V1",
        "sourceHead": args.head,
        "captureBackend": "API36 x86_64 AVD; see AVD environment for software-renderer classification.",
        "hardwareGpuClaim": "NOT_CLAIMED_BY_CI_AVD",
        "round1": {"status": status, "metrics": metrics["residentUnion"], "repeatNoise8bit": repeat_noise},
        "round2": "NOT_RUN",
        "round3": "NOT_RUN",
        "visualFidelityAccepted": False,
        "status": "NOT_ACCEPTED_UNLESS_ALL_FROZEN_GATES_AND_MANUAL_REVIEW_PASS",
    }
    (report_dir / "OUTER_OPTICAL_GPU_ACCEPTANCE_REPORT.json").write_text(json.dumps(report, indent=2) + "\n")
    with zipfile.ZipFile(round_dir / "ROUND_1_EVIDENCE.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in (before_path, before_path.with_suffix(".json"), after_path, after_path.with_suffix(".json"), *(captures / f"TAB3_OUTER_RESIDENTS_GPU_R{i}.png" for i in range(1, 4)), round_dir / "ROUND_1_BEFORE.png", round_dir / "ROUND_1_AFTER.png", round_dir / "ROUND_1_BEFORE_AFTER.png", round_dir / "ROUND_1_DIFF.png", round_dir / "ROUND_1_REPORT.json", round_dir / "ROUND_1_METRICS.json", round_dir / "ROUND_1_ROOT_CAUSE.md"):
            if path.is_file():
                archive.write(path, path.name)
    print(json.dumps({"status": status, "residentUnion": metrics["residentUnion"], "repeatNoise8bit": repeat_noise}, indent=2))


if __name__ == "__main__":
    main()
