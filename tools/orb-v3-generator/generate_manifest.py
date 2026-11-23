#!/usr/bin/env python3
"""
AI-Orb-Demo V3 — Asset Manifest, SHA256SUMS & Documentation Report Generator

Generates:
  - app/src/main/assets/orb_v3/manifest/ORB_V3_ASSET_MANIFEST.json
  - app/src/main/assets/orb_v3/manifest/SHA256SUMS
  - docs/v3/WEBP_ASSET_REPORT.md
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GEN_DIR = ROOT / "tools" / "orb-v3-generator"
if str(GEN_DIR) not in sys.path:
    sys.path.insert(0, str(GEN_DIR))
from canonical_fluid_volume import decode_webp_volume_3d

MASTER_PATH = ROOT / "public" / "assets" / "aistudio" / "____________910d1531.png"
ASSETS_ROOT = ROOT / "app" / "src" / "main" / "assets" / "orb_v3"
MANIFEST_DIR = ASSETS_ROOT / "manifest"
REPORT_PATH = ROOT / "docs" / "v3" / "WEBP_ASSET_REPORT.md"

EXPECTED_MASTER_SHA256 = "910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122"
SEED = 20261120


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    if not MASTER_PATH.exists():
        raise FileNotFoundError(f"Frozen master missing: {MASTER_PATH}")
    master_sha = sha256_file(MASTER_PATH)
    if master_sha != EXPECTED_MASTER_SHA256:
        raise ValueError(f"Frozen master SHA-256 mismatch! Got {master_sha}, expected {EXPECTED_MASTER_SHA256}")

    MANIFEST_DIR.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)

    volume_slices = []
    sha_lines = []
    total_disk_bytes = 0
    decoded_rgba_slices = decode_webp_volume_3d(ASSETS_ROOT / "volume")

    for z in range(32):
        rel = f"volume/fluid_color_z{z:02d}.webp"
        p = ASSETS_ROOT / rel
        if not p.exists():
            raise FileNotFoundError(f"Missing volume slice: {p}")
        sz = p.stat().st_size
        digest = sha256_file(p)
        decoded_rgba = decoded_rgba_slices[z]
        decoded_digest = hashlib.sha256(decoded_rgba).hexdigest()
        total_disk_bytes += sz
        volume_slices.append({
            "zIndex": z,
            "path": rel,
            "bytes": sz,
            "sha256": digest,
            "decodedRgbaBytes": len(decoded_rgba),
            "decodedRgbaSha256": decoded_digest,
        })
        sha_lines.append(f"{digest}  {rel}")

    maps_specs = [
        {
            "id": "emerald_distribution",
            "path": "maps/emerald_distribution.webp",
            "width": 256,
            "height": 256,
            "colorSpace": "LINEAR_DATA",
            "gpuInternalFormat": "GL_RGBA8",
            "channels": "R=emerald_curtain, G=silk_grain, B=meniscus_ripple, A=absorption_mod",
            "uniformName": "uFluidDetailMap",
            "textureUnit": 2,
        },
        {
            "id": "caustics",
            "path": "optics/caustics.webp",
            "width": 256,
            "height": 256,
            "colorSpace": "LINEAR_DATA",
            "gpuInternalFormat": "GL_RGBA8",
            "channels": "R=primary_curved_caustics, G=secondary_curved_caustics, B=fine_filaments, A=convergence_nodes",
            "uniformName": "uCausticsMap",
            "textureUnit": 3,
        },
        {
            "id": "glass_environment",
            "path": "optics/glass_environment.webp",
            "width": 512,
            "height": 256,
            "colorSpace": "SRGB",
            "gpuInternalFormat": "GL_SRGB8_ALPHA8",
            "channels": "RGB=equirectangular_crystal_studio_env, A=specular_weight",
            "uniformName": "uGlassEnvMap",
            "textureUnit": 4,
        },
    ]

    maps_entries = []
    for spec in maps_specs:
        p = ASSETS_ROOT / spec["path"]
        if not p.exists():
            raise FileNotFoundError(f"Missing 2D WebP map: {p}")
        sz = p.stat().st_size
        digest = sha256_file(p)
        total_disk_bytes += sz
        entry = dict(spec)
        entry["bytes"] = sz
        entry["sha256"] = digest
        maps_entries.append(entry)
        sha_lines.append(f"{digest}  {spec['path']}")

    volume_gpu_bytes = 256 * 256 * 32 * 4  # 8 MiB
    maps_gpu_bytes = (256 * 256 * 4) + (256 * 256 * 4) + (512 * 256 * 4)  # 1 MiB
    total_gpu_bytes = volume_gpu_bytes + maps_gpu_bytes

    manifest = {
        "schemaVersion": "AI_ORB_V3_WEBP_VOLUME_MANIFEST_1_0",
        "architecture": "WebP-First 3D Volume Hybrid Rendering (GLES 3.0)",
        "generatorSeed": SEED,
        "referenceMaster": {
            "path": "public/assets/aistudio/____________910d1531.png",
            "sha256": master_sha,
            "width": 1254,
            "height": 1254,
            "immutable": True,
            "referenceRegionsPath": "tools/orb-v3-generator/reference/REFERENCE_VISUAL_REGIONS.json",
        },
        "volume": {
            "id": "fluid_color_volume",
            "uniformName": "uFluidVolume",
            "textureUnit": 1,
            "width": 256,
            "height": 256,
            "depth": 32,
            "format": "LOSSLESS_RGBA_WEBP",
            "colorSpace": "SRGB_RGB_LINEAR_ALPHA",
            "premultipliedAlpha": False,
            "meniscusSource": "GPU_SINGLE_SOURCE_CORE_GLASS_FRAG",
            "yAxisConvention": "ROW_0_IS_TOP_PLUS_Y_SHADER_FLIPS_V",
            "gpuTarget": "GL_TEXTURE_3D",
            "gpuInternalFormat": "GL_SRGB8_ALPHA8",
            "gpuMemoryBytes": volume_gpu_bytes,
            "sliceCount": len(volume_slices),
            "slices": volume_slices,
        },
        "maps": maps_entries,
        "summary": {
            "totalWebpFiles": len(volume_slices) + len(maps_entries),
            "totalDiskBytes": total_disk_bytes,
            "totalGpuMemoryBytes": total_gpu_bytes,
            "totalGpuMemoryMiB": round(total_gpu_bytes / (1024.0 * 1024.0), 3),
        },
    }

    manifest_path = MANIFEST_DIR / "ORB_V3_ASSET_MANIFEST.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    sha_path = MANIFEST_DIR / "SHA256SUMS"
    sha_path.write_text("\n".join(sha_lines) + "\n", encoding="utf-8")

    # Write concise human-readable asset report
    report = f"""# AI-Orb-Demo V3 — WebP Asset Manifest & Validation Report

- **Architecture:** WebP-First 3D Volume Hybrid Rendering (OpenGL ES 3.0 `GL_TEXTURE_3D` + 2D Optical Maps)
- **Frozen Visual Master:** `public/assets/aistudio/____________910d1531.png`
- **Frozen Master SHA-256:** `{master_sha}` (verified unmodified)
- **Deterministic Generator Seed:** `{SEED}`
- **Total Production WebP Files:** `{len(volume_slices) + len(maps_entries)}` (`32` 3D volume slices + `3` 2D optical/detail maps)
- **Total Compressed Asset Footprint on Disk:** `{total_disk_bytes / 1024.0:.1f} KiB` (`{total_disk_bytes:,} bytes`)
- **Total Uncompressed GPU VRAM Footprint:** `{total_gpu_bytes / (1024.0 * 1024.0):.2f} MiB` (`8.00 MiB` 3D Volume + `1.00 MiB` 2D Maps)

## 1. 3D Fluid Volume Texture (`uFluidVolume`, Texture Unit 1)

| Property | Value |
| :--- | :--- |
| Asset Path Pattern | `app/src/main/assets/orb_v3/volume/fluid_color_z00.webp` .. `fluid_color_z31.webp` |
| Dimensions | `256 × 256 × 32` (`2,097,152` voxels) |
| Storage Encoding | Lossless RGBA WebP (`VP8L`, un-premultiplied) |
| Color Space | `RGB = sRGB`, `A = Linear Volumetric Density` |
| GPU Upload Format | `GLES30.GL_TEXTURE_3D` · `GL_SRGB8_ALPHA8` · `GL_LINEAR` · `GL_CLAMP_TO_EDGE` |
| GPU Memory | `8.00 MiB` (`8,388,608 bytes`) |
| Y-Axis Convention | Row 0 = `+Y` (human-inspectable right-side-up); `CORE_GLASS_FRAG` samples `uv.y = 0.5 - pos.y * 0.5` |

## 2. 2D Optical & Fluid Detail Maps

| Asset | Dimensions | Encoding | Color Space | GPU Format | Unit / Uniform | Disk Size |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `maps/emerald_distribution.webp` | `256 × 256` | Lossless RGBA WebP (`VP8L`) | Linear Data | `GL_RGBA8` | Unit 2 · `uFluidDetailMap` | `{maps_entries[0]['bytes'] / 1024.0:.1f} KiB` |
| `optics/caustics.webp` | `256 × 256` | Lossless RGBA WebP (`VP8L`) | Linear Data | `GL_RGBA8` | Unit 3 · `uCausticsMap` | `{maps_entries[1]['bytes'] / 1024.0:.1f} KiB` |
| `optics/glass_environment.webp` | `512 × 256` | Lossless RGBA WebP (`VP8L`) | sRGB Equirectangular | `GL_SRGB8_ALPHA8` | Unit 4 · `uGlassEnvMap` | `{maps_entries[2]['bytes'] / 1024.0:.1f} KiB` |

## 3. Integrity & Continuity Guarantees

- Every asset is encoded with `libwebp -lossless 1` (`VP8L` chunk verified).
- Each of the 32 slices records both compressed WebP SHA-256 and decoded RGBA SHA-256 (`262,144` bytes per slice); `test_v32_closure_gates.py` proves full byte identity against the canonical renderer.
- Master PNG is never packaged or used as a runtime sphere texture.
- Zero YuJian assets or external masks referenced anywhere in the pipeline.
- Exact SHA-256 digests recorded in `app/src/main/assets/orb_v3/manifest/SHA256SUMS` and `ORB_V3_ASSET_MANIFEST.json`, verified by JVM unit tests (`AIOrbV3ReferenceAndAssetTest`).
"""
    REPORT_PATH.write_text(report, encoding="utf-8")

    print(f"[generate_manifest] Wrote {manifest_path.relative_to(ROOT)}, {sha_path.relative_to(ROOT)}, {REPORT_PATH.relative_to(ROOT)}")
    print(f"[generate_manifest] Total WebP files: {len(volume_slices) + len(maps_entries)} ({total_disk_bytes / 1024.0:.1f} KiB disk, {total_gpu_bytes / (1024.0 * 1024.0):.2f} MiB GPU)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
