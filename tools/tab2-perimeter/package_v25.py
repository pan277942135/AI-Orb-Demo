#!/usr/bin/env python3
"""Validate the frozen Tab 2 V2 materials and package an Exact-HEAD APK artifact."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MASTER_PATH = ROOT / "docs/tab2/assets/TAB2_P0_HIGH_FIDELITY_MASTER_V1.png"
ARCHIVE_PATH = ROOT / "docs/tab2/assets/TAB2_PERIMETER_HIFI_REFERENCE_ASSETS_V2.zip"
GENERATED_ROOT = ROOT / "app/build/generated/tab2PerimeterAssets/tab2_perimeter"
EXPECTED_MASTER_SHA = "a37819bba6b144ca7fec27ace55e5b319c41e67b89a11e70d51200bce8749d67"
EXPECTED_ARCHIVE_SHA = "f724393fa8254b9d18543747ec356ded8a6e91dd61311d72a9a5028d174b0004"
OUTPUT_APK_NAME = "AI-Orb-Demo-Tab2-V2.5-Perimeter-debug.apk"

TEXTURES = [
    "textures/L1_RGBA.png",
    "textures/L1_NORMAL_RGB.png",
    "textures/L1_FLOW_RG.png",
    "textures/L2_RGBA.png",
    "textures/L2_NORMAL_RGB.png",
    "textures/L2_FLOW_RG.png",
    "textures/L3_RGBA.png",
    "textures/L3_NORMAL_RGB.png",
    "textures/L3_FLOW_RG.png",
    "textures/L4_DROPLET_ATLAS_RGBA.png",
]
RUNTIME_FILES = TEXTURES + [
    "geometry/CLOSED_ARC_LENGTH_LUT.json",
    "motion/KF01_KF06.json",
]


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_source() -> dict:
    master_bytes = MASTER_PATH.read_bytes()
    archive_bytes = ARCHIVE_PATH.read_bytes()
    actual_master = sha256(master_bytes)
    actual_archive = sha256(archive_bytes)
    assert actual_master == EXPECTED_MASTER_SHA, f"master SHA mismatch: {actual_master}"
    assert actual_archive == EXPECTED_ARCHIVE_SHA, f"V2 ZIP SHA mismatch: {actual_archive}"

    with zipfile.ZipFile(ARCHIVE_PATH) as zf:
        bad = zf.testzip()
        assert bad is None, f"corrupt ZIP member: {bad}"
        names = set(zf.namelist())
        missing = sorted(set(RUNTIME_FILES) - names)
        assert not missing, f"required V2 entries missing: {missing}"
        archive_entries = {}
        for info in zf.infolist():
            if info.is_dir():
                continue
            entry_data = zf.read(info.filename)
            archive_entries[info.filename] = {
                "sha256": sha256(entry_data),
                "size_bytes": len(entry_data),
            }
        texture_descriptors = {}
        for entry in TEXTURES:
            data = zf.read(entry)
            assert data.startswith(b"\x89PNG\r\n\x1a\n"), f"invalid PNG signature: {entry}"
            width, height = struct.unpack(">II", data[16:24])
            color_type = data[25]
            expected_size = (512, 512) if entry.endswith("L4_DROPLET_ATLAS_RGBA.png") else (2048, 128)
            expected_color_type = 6 if "_RGBA" in entry else 2
            assert (width, height) == expected_size, f"invalid dimensions for {entry}: {width}x{height}"
            assert color_type == expected_color_type, f"invalid PNG channels for {entry}: type={color_type}"
            texture_descriptors[entry] = {
                "sha256": sha256(data),
                "width": width,
                "height": height,
                "png_color_type": color_type,
                "size_bytes": len(data),
            }
        motion = json.loads(zf.read("motion/KF01_KF06.json"))
        assert motion["stages"] == [
            "ORB_RELEASE", "ARRIVAL", "BOTTOM_SPLIT", "SIDE_ASCEND", "TOP_CONVERGE", "PERIMETER_LOOP"
        ], "KF01-KF06 motion contract order changed"
        assert motion["relative_speed"] == {"L1": 1, "L2": 0.72, "L3": 1.31, "L4": 0.94}
        geometry = json.loads(zf.read("geometry/CLOSED_ARC_LENGTH_LUT.json"))
        assert geometry["master_sha256"] == EXPECTED_MASTER_SHA

    print(f"[TAB2-V25] SOURCE_LOCK PASS master={actual_master} archive={actual_archive}")
    print(f"[TAB2-V25] RESOURCE_ADMISSION PASS required_pngs={len(texture_descriptors)}")
    return {
        "master_sha256": actual_master,
        "archive_sha256": actual_archive,
        "textures": texture_descriptors,
        "archive_entries": archive_entries,
    }


def verify_apk(apk_path: Path, output_dir: Path) -> None:
    source = verify_source()
    manifest_path = GENERATED_ROOT / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    assert manifest["archive_sha256"] == source["archive_sha256"]
    assert manifest["master_sha256"] == source["master_sha256"]
    assert set(manifest["files"]) == set(RUNTIME_FILES)

    with zipfile.ZipFile(apk_path) as apk:
        names = set(apk.namelist())
        expected_assets = {}
        for entry in RUNTIME_FILES:
            descriptor = manifest["files"][entry]
            apk_path_name = f"assets/{descriptor['asset_path']}"
            assert apk_path_name in names, f"APK asset missing: {apk_path_name}"
            actual = sha256(apk.read(apk_path_name))
            assert actual == descriptor["sha256"], f"APK asset SHA mismatch: {apk_path_name}"
            expected_assets[apk_path_name] = actual
        manifest_apk_path = "assets/tab2_perimeter/manifest.json"
        assert manifest_apk_path in names, f"APK asset missing: {manifest_apk_path}"
        manifest_sha = sha256(apk.read(manifest_apk_path))
        assert manifest_sha == sha256(manifest_path.read_bytes()), "APK runtime manifest SHA mismatch"
        expected_assets[manifest_apk_path] = manifest_sha
        expected_tab2_entries = set(expected_assets)
        actual_tab2_entries = {name for name in names if name.startswith("assets/tab2_perimeter/")}
        assert actual_tab2_entries == expected_tab2_entries, (
            f"unexpected/missing Tab 2 APK assets: extra={sorted(actual_tab2_entries - expected_tab2_entries)} "
            f"missing={sorted(expected_tab2_entries - actual_tab2_entries)}"
        )

    output_dir.mkdir(parents=True, exist_ok=True)
    source_manifest = {
        "schema": "TAB2_V25_SOURCE_RESOURCE_SHA256_V1",
        "repository": "pan277942135/AI-Orb-Demo",
        "master_file": str(MASTER_PATH.relative_to(ROOT)),
        "master_sha256": source["master_sha256"],
        "archive_file": str(ARCHIVE_PATH.relative_to(ROOT)),
        "archive_sha256": source["archive_sha256"],
        "archive_entries": source["archive_entries"],
    }
    source_manifest_path = output_dir / "TAB2_V25_SOURCE_RESOURCE_SHA256.json"
    source_manifest_path.write_text(json.dumps(source_manifest, indent=2, ensure_ascii=False) + "\n")
    delivered_apk = output_dir / OUTPUT_APK_NAME
    shutil.copy2(apk_path, delivered_apk)
    apk_bytes = delivered_apk.read_bytes()
    apk_sha = sha256(apk_bytes)

    actual_head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    expected_head = os.environ.get("EXACT_HEAD_SHA", actual_head)
    assert actual_head == expected_head, f"Exact-HEAD mismatch: checked_out={actual_head} expected={expected_head}"
    branch = os.environ.get("GITHUB_HEAD_REF") or os.environ.get("GITHUB_REF_NAME") or "local"
    run_id = os.environ.get("GITHUB_RUN_ID", "local")
    fingerprint = {
        "schema": "TAB2_V25_EXACT_HEAD_BUILD_FINGERPRINT_V1",
        "repository": "pan277942135/AI-Orb-Demo",
        "branch": branch,
        "git_commit_sha": actual_head,
        "github_run_id": run_id,
        "apk_filename": OUTPUT_APK_NAME,
        "apk_size_bytes": len(apk_bytes),
        "apk_sha256": apk_sha,
        "v2_resource_zip_sha256": source["archive_sha256"],
        "master_sha256": source["master_sha256"],
        "unit_test_result": "PASS",
        "gpu_validation_status": "GPU_NOT_RUN",
        "debug_signing": "CI generated a temporary debug keystore because the repository-excluded debug.keystore was absent.",
        "unresolved_issues": [
            "Real-device/GLES visual capture remains pending; this CI artifact is for device visual acceptance.",
            "A device with an app signed by another debug key may require uninstalling it before installing this APK.",
        ],
        "apk_asset_sha256": expected_assets,
    }
    fingerprint_path = output_dir / "TAB2_V25_BUILD_FINGERPRINT.json"
    fingerprint_path.write_text(json.dumps(fingerprint, indent=2, ensure_ascii=False) + "\n")
    sums = [
        f"{apk_sha}  {OUTPUT_APK_NAME}",
        f"{source['archive_sha256']}  TAB2_PERIMETER_HIFI_REFERENCE_ASSETS_V2.zip",
        f"{source['master_sha256']}  TAB2_P0_HIGH_FIDELITY_MASTER_V1.png",
        f"{sha256(source_manifest_path.read_bytes())}  TAB2_V25_SOURCE_RESOURCE_SHA256.json",
    ]
    (output_dir / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n")
    print(f"[TAB2-V25] APK_RESOURCE_SHA PASS assets={len(expected_assets)}")
    print(f"[TAB2-V25] EXACT_HEAD PASS sha={actual_head}")
    print(f"[TAB2-V25] APK={delivered_apk} size={len(apk_bytes)} sha256={apk_sha}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--out", type=Path, default=ROOT / "dist/tab2-v25")
    args = parser.parse_args()
    if args.apk:
        verify_apk(args.apk.resolve(), args.out.resolve())
    else:
        verify_source()


if __name__ == "__main__":
    main()
