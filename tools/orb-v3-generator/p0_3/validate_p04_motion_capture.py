#!/usr/bin/env python3
"""Measure encoded motion-video evidence without frame-rate duplication.

FFmpeg's default output synchronization may repeat sparse input frames to fill
an inferred constant-rate timeline. This validator counts the source stream's
actual decoded frames and decodes with passthrough timing instead.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import numpy as np


def evaluate_video_gates(
    *,
    duration_seconds: float,
    decoded_frame_count: int,
    pts_intervals_seconds: list[float],
    consecutive_duplicate_ratio: float,
    max_frame_difference: float,
    passthrough_frame_count: int,
    minimum_seconds: float = 20.0,
    minimum_frames: int = 400,
    minimum_effective_fps: float = 20.0,
    maximum_pts_gap_seconds: float = 0.5,
    maximum_consecutive_duplicate_ratio: float = 0.05,
) -> dict[str, bool]:
    effective_fps = decoded_frame_count / duration_seconds if duration_seconds > 0 else 0.0
    maximum_gap = max(pts_intervals_seconds, default=None)
    return {
        "sourceVideoExists": True,
        "minimumDuration": duration_seconds >= minimum_seconds,
        "minimumEncodedDecodedFrameCount": decoded_frame_count >= minimum_frames,
        "minimumEffectiveFrameRate": effective_fps >= minimum_effective_fps,
        "passthroughFrameCountMatchesProbe": passthrough_frame_count == decoded_frame_count,
        "noFreezeOverMaximumPtsGap": maximum_gap is not None and maximum_gap <= maximum_pts_gap_seconds,
        "noDuplicatedStaticFrames": consecutive_duplicate_ratio <= maximum_consecutive_duplicate_ratio,
        "frameContentChanges": max_frame_difference > 0.0,
    }


def run_json(args: list[str]) -> dict:
    result = subprocess.run(args, check=True, text=True, capture_output=True)
    return json.loads(result.stdout)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--telemetry", type=Path)
    parser.add_argument("--minimum-seconds", type=float, default=20.0)
    parser.add_argument("--minimum-frames", type=int, default=400)
    parser.add_argument("--minimum-effective-fps", type=float, default=20.0)
    parser.add_argument("--maximum-pts-gap-seconds", type=float, default=0.5)
    parser.add_argument("--maximum-consecutive-duplicate-ratio", type=float, default=0.05)
    parser.add_argument("--strict", action="store_true", help="return nonzero when the evidence fails")
    args = parser.parse_args()

    video = args.video.resolve()
    output = args.output.resolve()
    if not video.is_file() or video.stat().st_size == 0:
        result = {
            "schema": "AI_ORB_P04_VIDEO_CAPTURE_REPORT_1_0",
            "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
            "status": "FAIL",
            "video": str(video),
            "error": "VIDEO_MISSING_OR_EMPTY",
            "gates": {"sourceVideoExists": False},
        }
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, indent=2) + "\n")
        return 1 if args.strict else 0

    probe = run_json([
        "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
        "-show_entries",
        "stream=codec_name,width,height,pix_fmt,r_frame_rate,avg_frame_rate,nb_frames,nb_read_frames,duration,bit_rate:format=duration",
        "-show_entries", "frame=best_effort_timestamp_time,pkt_duration_time",
        "-of", "json", str(video),
    ])
    streams = probe.get("streams", [])
    frames = probe.get("frames", [])
    if not streams:
        raise SystemExit("ffprobe found no video stream")
    stream = streams[0]
    width, height = int(stream["width"]), int(stream["height"])
    timestamps = [float(frame["best_effort_timestamp_time"]) for frame in frames if "best_effort_timestamp_time" in frame]
    intervals = [b - a for a, b in zip(timestamps, timestamps[1:])]
    duration = float(probe.get("format", {}).get("duration") or stream.get("duration") or 0.0)
    decoded_frames = int(stream.get("nb_read_frames") or len(timestamps))
    effective_fps = decoded_frames / duration if duration > 0 else 0.0

    # Preserve source frame cadence. Do not let FFmpeg synthesize duplicate frames.
    decoder = subprocess.Popen([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(video),
        "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert decoder.stdout is not None
    frame_bytes = width * height * 3
    frame_hashes: list[str] = []
    mean_abs_diffs: list[float] = []
    previous: np.ndarray | None = None
    while True:
        raw = decoder.stdout.read(frame_bytes)
        if not raw:
            break
        if len(raw) != frame_bytes:
            decoder.kill()
            raise SystemExit(f"Truncated RGB frame: {len(raw)} bytes, expected {frame_bytes}")
        frame_hashes.append(hashlib.sha256(raw).hexdigest())
        if previous is not None:
            current = np.frombuffer(raw, dtype=np.uint8).astype(np.int16)
            mean_abs_diffs.append(float(np.abs(current - previous).mean()))
        previous = np.frombuffer(raw, dtype=np.uint8).astype(np.int16)
    decoder.stdout.close()
    decoder_stderr = decoder.stderr.read().decode("utf-8", "replace") if decoder.stderr else ""
    decoder_rc = decoder.wait()
    if decoder_rc != 0:
        raise SystemExit(f"ffmpeg decode failed ({decoder_rc}): {decoder_stderr[-2000:]}")

    passthrough_decoded_frames = len(frame_hashes)
    consecutive_duplicates = sum(a == b for a, b in zip(frame_hashes, frame_hashes[1:]))
    duplicate_ratio = consecutive_duplicates / max(passthrough_decoded_frames - 1, 1)
    max_gap = max(intervals, default=None)
    telemetry: dict = {}
    if args.telemetry and args.telemetry.is_file():
        telemetry = json.loads(args.telemetry.read_text())

    gates = evaluate_video_gates(
        duration_seconds=duration,
        decoded_frame_count=decoded_frames,
        pts_intervals_seconds=intervals,
        consecutive_duplicate_ratio=duplicate_ratio,
        max_frame_difference=max(mean_abs_diffs, default=0.0),
        passthrough_frame_count=passthrough_decoded_frames,
        minimum_seconds=args.minimum_seconds,
        minimum_frames=args.minimum_frames,
        minimum_effective_fps=args.minimum_effective_fps,
        maximum_pts_gap_seconds=args.maximum_pts_gap_seconds,
        maximum_consecutive_duplicate_ratio=args.maximum_consecutive_duplicate_ratio,
    )
    status = "PASS" if all(gates.values()) else "FAIL"
    report = {
        "schema": "AI_ORB_P04_VIDEO_CAPTURE_REPORT_1_0",
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "evidenceClass": telemetry.get("renderingBackend", "UNKNOWN"),
        "video": str(video),
        "videoSha256": hashlib.sha256(video.read_bytes()).hexdigest(),
        "videoBytes": video.stat().st_size,
        "stream": {
            "codec": stream.get("codec_name"),
            "width": width,
            "height": height,
            "pixelFormat": stream.get("pix_fmt"),
            "reportedFrameRate": stream.get("r_frame_rate"),
            "averageFrameRate": stream.get("avg_frame_rate"),
            "durationSeconds": duration,
            "declaredFrameCount": int(stream["nb_frames"]) if str(stream.get("nb_frames", "")).isdigit() else None,
            "ffprobeDecodedFrameCount": decoded_frames,
            "passthroughDecodedFrameCount": passthrough_decoded_frames,
            "effectiveDecodedFramesPerSecond": effective_fps,
            "ptsSeconds": timestamps,
            "ptsIntervalsSeconds": intervals,
            "maximumPtsIntervalSeconds": max_gap,
            "uniqueDecodedFrameCount": len(set(frame_hashes)),
            "consecutiveDuplicateFrameCount": consecutive_duplicates,
            "consecutiveDuplicateFrameRatio": duplicate_ratio,
            "meanAbsoluteRgbDifferenceByConsecutiveFrame": mean_abs_diffs,
            "ffmpegFrameRateDuplicationUsedForCounting": False,
        },
        "runtimeTelemetryContext": {
            "source": str(args.telemetry.resolve()) if args.telemetry else None,
            "buildSha": telemetry.get("buildSha"),
            "apkSha256": telemetry.get("apkSha256"),
            "glRenderer": telemetry.get("glRenderer"),
            "renderingBackend": telemetry.get("renderingBackend"),
            "coreAngleDeg": telemetry.get("coreAngleDeg"),
            "coreAngularVelDegPerSec": telemetry.get("coreAngularVelDegPerSec"),
            "note": "This is capture-time context, not a per-video-frame motion trace.",
        },
        "limitations": [
            "Core rotation and five Resident trajectories require a frame-dense video or a per-frame runtime telemetry trace.",
            "Software AVD evidence does not establish hardware GPU or physical-device performance.",
        ],
        "thresholds": {
            "minimumSeconds": args.minimum_seconds,
            "minimumDecodedFrames": args.minimum_frames,
            "minimumEffectiveFps": args.minimum_effective_fps,
            "maximumPtsGapSeconds": args.maximum_pts_gap_seconds,
            "maximumConsecutiveDuplicateRatio": args.maximum_consecutive_duplicate_ratio,
        },
        "gates": gates,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps({"status": status, "video": str(video), "decodedFrames": decoded_frames, "durationSeconds": duration, "effectiveFps": effective_fps, "maximumPtsGapSeconds": max_gap, "duplicateRatio": duplicate_ratio, "gates": gates}, indent=2))
    return 1 if args.strict and status != "PASS" else 0


if __name__ == "__main__":
    raise SystemExit(main())
