#!/usr/bin/env python3
"""Validate the frozen contract inputs and derived P0-3 ROI mask package."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
P0 = ROOT / "docs/p0_3"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    checksum_manifest = json.loads((P0 / "contract/SHA256SUMS.json").read_text())
    for relative, expected in checksum_manifest.items():
        base = P0 if relative.startswith(("references/", "roi_masks/")) else P0 / "contract"
        path = base / relative
        actual = {"bytes": path.stat().st_size, "sha256": sha(path)}
        assert actual == expected, f"Frozen source-package mismatch: {relative}: {actual} != {expected}"

    contract = json.loads((P0 / "contract/AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.json").read_text())
    schema_path = P0 / "contract/AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.schema.json"
    try:
        import jsonschema
    except ImportError:
        print("[WARN] jsonschema package unavailable; schema validation skipped")
    else:
        jsonschema.validate(contract, json.loads(schema_path.read_text()))

    manifest_path = P0 / "ROI_MASK_FREEZE_MANIFEST.json"
    manifest = json.loads(manifest_path.read_text())
    expected_region_masks = [r for r in contract["visual_regions"]["regions"] if r["mask_file"]]
    records = {r["id"]: r for r in manifest["records"]}
    assert len(records) == len(expected_region_masks) == 12
    for region in expected_region_masks:
        mask_path = P0 / region["mask_file"]
        record = records[region["id"]]
        assert sha(mask_path) == record["sha256"], f"Mask SHA changed: {region['id']}"
        with Image.open(mask_path) as image:
            assert image.mode == "L" and image.size == (1254, 1254), f"Invalid mask: {region['id']}"
            pixels = np.asarray(image) > 0
            count = int(np.count_nonzero(pixels))
            ys, xs = np.nonzero(pixels)
            bounds = [int(xs.min()), int(ys.min()), int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1)]
            assert count == record["nonZeroPixels"], f"Pixel count changed: {region['id']}"
            assert abs(count / (1254 * 1254) - record["areaRatio"]) < 1e-8, f"Area ratio changed: {region['id']}"
            assert bounds == record["boundingRectXYWH"], f"Bounds changed: {region['id']}"
        overlay = P0 / record["overlayFile"]
        assert overlay.is_file() and sha(overlay) == record["overlaySha256"], f"Per-mask overlay missing/changed: {region['id']}"
        contact_sheet = P0 / record["overlayContactSheet"]
        assert contact_sheet.is_file() and sha(contact_sheet) == record["overlayContactSheetSha256"], f"Overlay contact sheet missing/changed: {region['id']}"

    ref_a = np.asarray(Image.open(P0 / "references/REF_A_CORE_CRYSTAL_RGBA.png").convert("RGBA"))
    core_mask = np.asarray(Image.open(P0 / "roi_masks/REF_A_CORE_SILHOUETTE_ALPHA_GE_008.png").convert("L")) > 0
    assert np.array_equal(core_mask, ref_a[:, :, 3] >= 8), "Core silhouette mask does not equal alpha >= 8"
    materials = [
        np.asarray(Image.open(P0 / f"roi_masks/REF_A_{name}.png").convert("L")) > 0
        for name in ("EMERALD_FOLD", "SAPPHIRE_VALLEY", "AQUA_LIQUID")
    ]
    overlap = {
        "EMERALD_FOLD__SAPPHIRE_VALLEY": int(np.count_nonzero(materials[0] & materials[1])),
        "EMERALD_FOLD__AQUA_LIQUID": int(np.count_nonzero(materials[0] & materials[2])),
        "SAPPHIRE_VALLEY__AQUA_LIQUID": int(np.count_nonzero(materials[1] & materials[2])),
    }
    assert overlap == manifest["selfCheck"]["materialOverlapPixels"], "Semantic overlap counts differ from the freeze manifest"
    assert manifest["selfCheck"]["materialMaskOverlapPolicy"] == "INDEPENDENT_SEMANTIC_REGIONS_ALLOW_OVERLAP"
    legacy_manifest = json.loads((P0 / "ROI_MASK_MANIFEST.json").read_text())
    assert legacy_manifest == manifest, "Compatibility ROI manifest differs from frozen manifest"
    assert manifest["selfCheck"]["referenceHashesVerified"]
    assert manifest["selfCheck"]["overlayContactSheetsGenerated"]
    print("[P0_3_PF01 PASS] frozen inputs, 12 masks, overlap-preserving semantics, overlays, dimensions and hashes verified")


if __name__ == "__main__":
    main()
