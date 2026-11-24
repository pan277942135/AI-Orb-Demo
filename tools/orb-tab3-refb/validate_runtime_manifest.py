#!/usr/bin/env python3
"""Validate the compact Tab 3 GPU asset against the frozen P0-A REF_B extraction."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "AI_ORB_DEMO_TAB3_REFB_OUTER_OPTICAL_FIELD_V1/ref_b_extraction"
ASSET = ROOT / "app/src/main/assets/outer_optical_field/OUTER_OPTICAL_FIELD_RUNTIME_V1.json"
REF_B = ROOT / "docs/p0_3/references/REF_B_FULL_SCENE_RGB.png"
EXPECTED_REF_SHA = "910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    extraction = json.loads((BASE / "P0_A_PREFLIGHT_PASS.json").read_text())
    source = json.loads((BASE / "REF_B_SOURCE_VERIFICATION.json").read_text())
    contract = json.loads((BASE / "OUTER_OPTICAL_FIELD_CONTRACT_V1.json").read_text())
    geometry = json.loads((BASE / "REF_B_GEOMETRY_DATA.json").read_text())
    runtime_bytes = ASSET.read_bytes()
    runtime = json.loads(runtime_bytes)
    assert extraction["status"] == "PASS"
    assert sha(REF_B.read_bytes()) == EXPECTED_REF_SHA
    assert source["refB"]["sha256"] == EXPECTED_REF_SHA
    assert runtime["schema"] == "AI_ORB_TAB3_OUTER_OPTICAL_RUNTIME_V1"
    assert runtime["source"]["refBSha256"] == EXPECTED_REF_SHA
    assert runtime["source"]["outerOpticalContractSha256"] == sha((BASE / "OUTER_OPTICAL_FIELD_CONTRACT_V1.json").read_bytes())
    assert runtime["referenceProjection"]["canvasPx"] == [1254, 1254]
    assert runtime["referenceProjection"]["centerPx"] == [626.5, 620.5]
    assert len(runtime["traces"]) == len(geometry["tracePaths"])
    assert contract["status"] == "P0_A_PASS"
    assert len(runtime["bokeh"]) == 12
    assert len(runtime["highlightNodes"]) == 51
    assert len(runtime["droplets"]) == 5
    assert len(runtime["particles"]) == 10
    for trace in runtime["traces"]:
        assert len(trace["pointsPx"]) >= 2
        assert 0 < trace["signalWidthPx"] < trace["annotationWidthPx"]
        assert trace["depthRole"] in ("FRONT", "BACK_GROUP_INFERENCE")
        assert trace["confidence"].startswith("PIXEL_TRACE_MEASURED")
        assert trace["periodMs"] > 0
    assert "hypotheses" in runtime["runtimeParameters"]["note"]
    print(json.dumps({
        "status": "TAB3_REFB_RUNTIME_ASSET_PASS",
        "refBSha256": EXPECTED_REF_SHA,
        "runtimeManifestSha256": sha(runtime_bytes),
        "traceCount": len(runtime["traces"]),
        "surfaceCount": len(runtime["traces"]),
        "filamentCount": len(runtime["traces"]) * 2,
        "bokehCount": len(runtime["bokeh"]),
        "highlightCount": len(runtime["highlightNodes"]),
        "dropletCount": len(runtime["droplets"]),
        "particleCount": len(runtime["particles"]),
    }, indent=2))


if __name__ == "__main__":
    main()
