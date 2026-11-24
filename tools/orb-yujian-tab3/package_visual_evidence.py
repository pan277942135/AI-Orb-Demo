#!/usr/bin/env python3
"""Build inspectable image comparisons from real API36 AVD captures, without claiming source parity."""

from __future__ import annotations

import argparse
import hashlib
import json
import zipfile
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageEnhance


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def label(image: Image.Image, title: str) -> Image.Image:
    result = image.convert("RGB").copy()
    draw = ImageDraw.Draw(result)
    draw.rectangle((0, 0, result.width, 36), fill=(0, 0, 0))
    draw.text((12, 10), title, fill=(255, 255, 255))
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--capture-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()
    captures = args.capture_dir
    out = args.output_dir
    visual = out / "visual"
    heatmaps = visual / "DIFF_HEATMAPS"
    round1 = out / "round_1"
    for directory in (visual, heatmaps, round1):
        directory.mkdir(parents=True, exist_ok=True)

    names = {
        "tab1": "TAB1_NATIVE_ORB_GPU.png",
        "tab3": "TAB3_HYBRID_ORB_GPU.png",
        "core": "YUJIAN_CORE_ONLY_SOURCE_SHADER_GPU.png",
        "outer1": "TAB1_OUTER_RESIDENTS_GPU.png",
        "outer3": "TAB3_OUTER_RESIDENTS_GPU.png",
    }
    images = {key: Image.open(captures / filename).convert("RGB") for key, filename in names.items()}
    sizes = {image.size for image in images.values()}
    assert len(sizes) == 1, f"Capture viewports differ: {sorted(sizes)}"
    width, height = next(iter(sizes))
    images["tab1"].save(visual / "TAB1_NATIVE_ORB.png")
    images["tab3"].save(visual / "TAB3_HYBRID_ORB.png")
    images["core"].save(visual / "YUJIAN_CORE_SOURCE_SHADER_GPU.png")
    Image.blend(images["tab1"], images["tab3"], 0.5).save(visual / "CORE_OVERLAY.png")

    pair = Image.new("RGB", (width * 2, height + 42), (12, 18, 22))
    pair.paste(label(images["tab1"], "TAB 1 native Core + native outer passes"), (0, 42))
    pair.paste(label(images["tab3"], "TAB 3 extracted YuJian SPHERE_FS + same native outer passes"), (width, 42))
    pair.save(round1 / "ROUND_1_BEFORE_AFTER.png")

    outer_pair = Image.new("RGB", (width * 2, height + 42), (12, 18, 22))
    outer_pair.paste(label(images["outer1"], "TAB 1 native outer pass diagnostic"), (0, 42))
    outer_pair.paste(label(images["outer3"], "TAB 3 native outer pass diagnostic"), (width, 42))
    outer_pair.save(visual / "OUTER_OVERLAY.png")

    def make_heatmap(left: Image.Image, right: Image.Image, target: Path) -> None:
        difference = ImageChops.difference(left, right)
        difference = ImageEnhance.Contrast(difference).enhance(4.0)
        difference.save(target)

    make_heatmap(images["tab1"], images["tab3"], heatmaps / "TAB1_VS_TAB3_FULL_FRAME_ABS_DIFF_X4.png")
    make_heatmap(images["outer1"], images["outer3"], heatmaps / "TAB1_VS_TAB3_OUTER_PASS_ABS_DIFF_X4.png")

    # The tab switcher and Tab 3 badge are outside this shared central view. The isolated
    # RIBBONS_ONLY captures should otherwise be identical because both use the same Scene,
    # paused simulation time, and renderer pass implementation.
    y0 = min(180, height // 5)
    y1 = max(y0 + 1, height - min(150, height // 6))
    outer_diff = ImageChops.difference(images["outer1"].crop((0, y0, width, y1)), images["outer3"].crop((0, y0, width, y1)))
    stats = outer_diff.getextrema()
    histogram = outer_diff.histogram()
    total_channel_values = width * (y1 - y0) * 3
    absolute_sum = sum((index % 256) * count for index, count in enumerate(histogram))
    mean_abs_delta = absolute_sum / max(1, total_channel_values)
    outer_identical = mean_abs_delta <= 0.25 and max(channel[1] for channel in stats) <= 2

    screenshot_records = {
        filename: {"sha256": digest(captures / filename), "bytes": (captures / filename).stat().st_size}
        for filename in names.values()
    }
    report = {
        "schema": "AI_ORB_DEMO_TAB3_HYBRID_VISUAL_ACCEPTANCE_1_0",
        "head": args.head,
        "captureBackend": "API36 x86_64 Android Virtual Device / software GLES (SwiftShader expected)",
        "viewport": {"width": width, "height": height},
        "sameCameraAndMotionEvidence": {
            "camera": "MASTER_CAMERA_REFERENCE_LOCKED_0_0_YAW_PITCH",
            "motion": "paused shared AIOrbScene clock; Tab1 and Tab3 locked-frame angle asserted equal in instrumentation",
            "status": "PASS" if json.loads((captures / "TAB1_NATIVE_ORB_GPU.json").read_text())["coreAngleDeg"] == json.loads((captures / "TAB3_HYBRID_ORB_GPU.json").read_text())["coreAngleDeg"] else "FAIL",
        },
        "outerPassRegression": {
            "comparison": "TAB1_OUTER_RESIDENTS_GPU vs TAB3_OUTER_RESIDENTS_GPU, RIBBONS_ONLY, same shared scene",
            "centralRegionY": [y0, y1],
            "meanAbsoluteChannelDelta8bit": mean_abs_delta,
            "maxChannelDelta8bit": max(channel[1] for channel in stats),
            "status": "PASS" if outer_identical else "FAIL",
        },
        "coreReference": {
            "asset": "YUJIAN_CORE_SOURCE_SHADER_GPU.png is the extracted source SPHERE_FS running inside Demo's production Pass 6",
            "yuJianOriginalAppGpuCapture": "NOT_CAPTURED",
            "silhouetteIou": "NOT_COMPARABLE",
            "centerError": "NOT_COMPARABLE",
            "roiSsim": "NOT_COMPARABLE",
            "materialRegionAreaError": "NOT_COMPARABLE",
            "deltaE2000": "NOT_COMPARABLE",
        },
        "screenshots": screenshot_records,
        "status": "PASS_OUTER_PASS_PARITY" if outer_identical else "FAIL_OUTER_PASS_PARITY",
    }
    report_path = out / "reports" / "TAB3_HYBRID_VISUAL_ACCEPTANCE_REPORT.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    round1_report = {
        "round": 1,
        "goal": "YuJian source SPHERE_FS core integrated as Tab 3 Pass 6 while preserving Tab 1 native outer passes",
        "sourceHead": args.head,
        "evidence": "API36 software AVD captures; see ROUND_1_BEFORE_AFTER.png and ../reports/TAB3_HYBRID_VISUAL_ACCEPTANCE_REPORT.json",
        "outerPassParity": report["outerPassRegression"],
        "coreFidelityToYuJianOriginalApp": "NOT_COMPARABLE; original app GPU capture was not produced",
        "improvementAgainstPriorRound": "NOT_MEASURED; this is the integration baseline, not an optimization claim",
        "status": "NOT_ACCEPTED",
    }
    round1_report_path = round1 / "ROUND_1_REPORT.json"
    round1_report_path.write_text(json.dumps(round1_report, indent=2) + "\n")
    with zipfile.ZipFile(round1 / "ROUND_1_EVIDENCE.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in (round1 / "ROUND_1_BEFORE_AFTER.png", round1_report_path):
            archive.write(path, path.name)
        for path in (captures / name for name in names.values()):
            archive.write(path, f"captures/{path.name}")
        for path in (captures / name for name in ("RUNTIME_TELEMETRY.json", "SCREENSHOT_SHA256SUMS.txt")):
            if path.is_file():
                archive.write(path, f"captures/{path.name}")
    print(json.dumps({"status": report["status"], "outerMeanAbsDelta": mean_abs_delta, "coreReference": "NOT_COMPARABLE"}, indent=2))
    if report["sameCameraAndMotionEvidence"]["status"] != "PASS":
        raise SystemExit("Tab 1 and Tab 3 captures do not share the asserted locked camera/motion snapshot")
    if not outer_identical:
        raise SystemExit("Tab 1 and Tab 3 outer-only GPU captures differ beyond the frozen comparison tolerance")


if __name__ == "__main__":
    main()
