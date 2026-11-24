#!/usr/bin/env python3
"""Validate real API36 AVD screenshots, runtime readbacks, and a continuous Tab 3 recording."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import statistics
import subprocess
from pathlib import Path

EXPECTED_SHADER = "eff0af75967117b501be6409009dfa62d9fb336065e458464a19d3e5221bbc05"
EXPECTED_WEBP = "daa85a1fc13fac4a7ca562959c2d19172ee0f1e94277c3eeb32ef1f09a1a7b09"
EXPECTED_SCREENSHOTS = (
    "TAB1_NATIVE_ORB_GPU.png",
    "TAB3_HYBRID_ORB_GPU.png",
    "YUJIAN_CORE_ONLY_SOURCE_SHADER_GPU.png",
    "TAB1_OUTER_RESIDENTS_GPU.png",
    "TAB3_OUTER_RESIDENTS_GPU.png",
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def command(*args: str) -> str:
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def video_facts(path: Path) -> dict:
    probe = json.loads(command(
        "ffprobe", "-v", "error", "-show_streams", "-show_format",
        "-show_frames", "-select_streams", "v:0",
        "-show_entries", "format=duration:stream=width,height,avg_frame_rate,nb_frames:frame=best_effort_timestamp_time",
        "-of", "json", str(path),
    ))
    stream = probe["streams"][0]
    frames = probe.get("frames", [])
    pts = [float(frame["best_effort_timestamp_time"]) for frame in frames if "best_effort_timestamp_time" in frame]
    intervals = [b - a for a, b in zip(pts, pts[1:]) if b >= a]
    digest_output = command("ffmpeg", "-v", "error", "-i", str(path), "-f", "framemd5", "-")
    frame_hashes = [line.split(",")[-1].strip() for line in digest_output.splitlines() if line and not line.startswith("#")]
    duration = float(probe.get("format", {}).get("duration", 0.0))
    return {
        "width": int(stream["width"]),
        "height": int(stream["height"]),
        "durationSeconds": duration,
        "averageFrameRate": stream.get("avg_frame_rate", "UNKNOWN"),
        "decodedFrameCount": len(pts),
        "uniqueFrameHashCount": len(set(frame_hashes)),
        "repeatedFrameCount": max(0, len(frame_hashes) - len(set(frame_hashes))),
        "repeatedFrameRatio": 0.0 if not frame_hashes else 1.0 - len(set(frame_hashes)) / len(frame_hashes),
        "averageObservedFps": 0.0 if len(pts) < 2 or pts[-1] <= pts[0] else (len(pts) - 1) / (pts[-1] - pts[0]),
        "p95FrameIntervalSeconds": 0.0 if not intervals else statistics.quantiles(intervals, n=20)[18] if len(intervals) >= 2 else max(intervals),
        "maxFrameIntervalSeconds": max(intervals, default=0.0),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = args.evidence_dir
    telemetry_path = root / "RUNTIME_TELEMETRY.json"
    motion_path = root / "TAB3_MOTION_20S_RUNTIME.json"
    assert telemetry_path.is_file(), f"Missing runtime telemetry: {telemetry_path}"
    assert motion_path.is_file(), f"Missing 20s motion telemetry: {motion_path}"
    telemetry = json.loads(telemetry_path.read_text())
    motion = json.loads(motion_path.read_text())
    assert telemetry["androidApiLevel"] == 36, "Runtime evidence is not from API36"
    assert telemetry["isSoftwareRenderer"] is True, "This CI API36 AVD job must be explicitly classified as software GLES"
    assert telemetry["buildSha"] == args.expected_head, "Installed app build SHA differs from expected HEAD"
    assert telemetry["coreRendererMode"] == "YUJIAN_CORE"
    assert telemetry["yujianCoreStatus"] == "RENDERED"
    assert telemetry["yujianShaderProgramId"] > 0
    assert telemetry["sourceSphereShaderSha256"] == EXPECTED_SHADER
    assert telemetry["sourceCoreWebpSha256"] == EXPECTED_WEBP
    assert telemetry["sourceCoreWebpUsage"] == "PACKAGED_NOT_SAMPLED_BY_SOURCE_SPHERE_FS"
    assert telemetry["uniformsReadBackValid"] is True
    assert telemetry["drawFramebufferComplete"] is True
    assert telemetry["noFramebufferFeedbackLoop"] is True
    assert telemetry["passStateReadBackValid"] is True
    assert telemetry["refractionTextureId"] > 0
    assert telemetry["refractionTextureId"] == telemetry["boundTextureId"]
    assert telemetry["samplerTextureUnit"] == 0
    assert telemetry["glError"] == "GL_NO_ERROR"
    assert telemetry["tabSwitchContextCountBeforeAndAfter"] == telemetry["contextRebuildCount"]
    assert motion["status"] == "PASS"
    assert motion["elapsedWallClockMs"] >= 20_000
    assert motion["frameCountAfter"] > motion["frameCountBefore"]
    assert motion["coreAngleAfterDeg"] != motion["coreAngleBeforeDeg"]

    screenshots = {}
    for name in EXPECTED_SCREENSHOTS:
        path = root / name
        assert path.is_file() and path.stat().st_size > 8, f"Missing GPU screenshot: {path}"
        data = path.read_bytes()
        assert data.startswith(b"\x89PNG\r\n\x1a\n"), f"Not a PNG file: {path}"
        from PIL import Image

        with Image.open(path) as image:
            image.verify()
        with Image.open(path) as image:
            width, height = image.size
        assert width >= 400 and height >= 400, f"GPU capture unexpectedly small: {path} {width}x{height}"
        sidecar = path.with_suffix(".json")
        # Instrumentation can add per-image camera and motion facts; absent fields are explicit.
        snapshots = json.loads(sidecar.read_text()) if sidecar.is_file() else None
        screenshots[name] = {
            "sha256": sha256(data),
            "bytes": len(data),
            "width": width,
            "height": height,
            "snapshotSidecar": None if snapshots is None else snapshots,
            "status": "PASS",
        }

    video = video_facts(args.video)
    assert video["durationSeconds"] >= 20.0, f"20s video is too short: {video['durationSeconds']:.3f}s"
    assert video["decodedFrameCount"] >= 10, f"Too few decoded frames: {video['decodedFrameCount']}"
    assert video["uniqueFrameHashCount"] >= 5, "Video does not contain enough distinct decoded frames"

    report = {
        "schema": "AI_ORB_YUJIAN_TAB3_API36_RUNTIME_EVIDENCE_1_0",
        "expectedHead": args.expected_head,
        "rendererClassification": "SOFTWARE_RENDERER_VALIDATED" if telemetry["isSoftwareRenderer"] else "RENDERER_CLASSIFICATION_REQUIRES_REVIEW",
        "hardwareGpuClaim": "NOT_CLAIMED",
        "runtimeFacts": telemetry,
        "motionRuntime": motion,
        "screenshots": screenshots,
        "motionVideo": {
            "path": args.video.name,
            "sha256": sha256(args.video.read_bytes()),
            "bytes": args.video.stat().st_size,
            **video,
            "status": "PASS",
        },
        "yuJianOriginalAppGpuReference": "NOT_CAPTURED; metrics against original app remain NOT_COMPARABLE",
        "status": "PASS_SOFTWARE_AVD_RUNTIME_AND_VIDEO",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "renderer": report["rendererClassification"], "video": video}, indent=2))


if __name__ == "__main__":
    main()
