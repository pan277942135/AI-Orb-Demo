#!/usr/bin/env python3
"""
AI-Orb-Demo V3 — Lossless RGBA WebP Asset Integrity Validator

Verifies every WebP file under app/src/main/assets/orb_v3/:
  - File exists & non-empty
  - Valid RIFF + WEBP container header
  - Contains VP8L chunk (strict Lossless WebP requirement!)
  - Decodes cleanly via ffmpeg to exact expected WxH RGBA bytes
  - Contains meaningful non-empty RGB and Alpha channels
"""

from __future__ import annotations

import struct
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS_ROOT = ROOT / "app" / "src" / "main" / "assets" / "orb_v3"

EXPECTED_SPECS: list[tuple[str, int, int]] = (
    [(f"volume/fluid_color_z{i:02d}.webp", 256, 256) for i in range(32)]
    + [
        ("maps/emerald_distribution.webp", 256, 256),
        ("optics/caustics.webp", 256, 256),
        ("optics/glass_environment.webp", 512, 256),
    ]
)


def inspect_webp_chunks(data: bytes) -> set[bytes]:
    if len(data) < 16 or data[0:4] != b"RIFF" or data[8:12] != b"WEBP":
        raise ValueError("Invalid RIFF/WEBP header")
    chunks: set[bytes] = set()
    pos = 12
    while pos + 8 <= len(data):
        fourcc = data[pos : pos + 4]
        chunk_size = struct.unpack("<I", data[pos + 4 : pos + 8])[0]
        chunks.add(fourcc)
        pos += 8 + chunk_size + (chunk_size & 1)
    return chunks


def decode_rgba(path: Path, width: int, height: int) -> bytes:
    cmd = [
        "ffmpeg",
        "-v",
        "error",
        "-i",
        str(path),
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgba",
        "-",
    ]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg decode failed: {proc.stderr.decode()}")
    expected_len = width * height * 4
    if len(proc.stdout) != expected_len:
        raise ValueError(f"Decoded RGBA size mismatch: got {len(proc.stdout)}, expected {expected_len}")
    return proc.stdout


def main() -> int:
    failures: list[str] = []
    passed = 0

    for rel, exp_w, exp_h in EXPECTED_SPECS:
        path = ASSETS_ROOT / rel
        if not path.exists():
            failures.append(f"MISSING: {rel}")
            continue
        raw = path.read_bytes()
        if len(raw) < 32:
            failures.append(f"TOO_SMALL: {rel} ({len(raw)} bytes)")
            continue
        try:
            chunks = inspect_webp_chunks(raw)
            if b"VP8L" not in chunks:
                failures.append(f"NOT_LOSSLESS_VP8L: {rel} (chunks={sorted(c.decode('ascii', 'ignore') for c in chunks)})")
                continue
            rgba = decode_rgba(path, exp_w, exp_h)
            # Quick statistical check on RGB + Alpha
            max_rgb = 0
            min_a = 255
            max_a = 0
            for i in range(0, len(rgba), 64):
                r, g, b, a = rgba[i], rgba[i + 1], rgba[i + 2], rgba[i + 3]
                m = max(r, g, b)
                if m > max_rgb:
                    max_rgb = m
                if a < min_a:
                    min_a = a
                if a > max_a:
                    max_a = a
            if max_rgb < 20:
                failures.append(f"NEAR_BLACK_RGB: {rel} (max_rgb={max_rgb})")
                continue
            if max_a == 0 and rel != "volume/fluid_color_z00.webp" and rel != "volume/fluid_color_z31.webp":
                failures.append(f"EMPTY_ALPHA: {rel} (max_a=0)")
                continue
            passed += 1
        except Exception as exc:
            failures.append(f"ERROR({rel}): {exc}")

    if failures:
        print(f"[validate_webp_assets] FAILED ({len(failures)} issues):")
        for f in failures:
            print(f"  - {f}")
        return 1

    print(f"[validate_webp_assets] PASS: All {passed}/{len(EXPECTED_SPECS)} Lossless RGBA WebP assets verified (RIFF/WEBP/VP8L + exact WxH RGBA decode).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
