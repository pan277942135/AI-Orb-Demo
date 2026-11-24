#!/usr/bin/env python3
"""Build the compact GLES runtime manifest from the frozen REF_B extraction."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
EXTRACTION = ROOT / "AI_ORB_DEMO_TAB3_REFB_OUTER_OPTICAL_FIELD_V1/ref_b_extraction"
ASSET_DIR = ROOT / "app/src/main/assets/outer_optical_field"
REF_B = ROOT / "docs/p0_3/references/REF_B_FULL_SCENE_RGB.png"
REF_B_SHA = "910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def srgb_to_linear(pixel: np.ndarray) -> np.ndarray:
    x = pixel.astype(np.float32) / 255.0
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def brightest_linear_color(rgb: np.ndarray, mask: np.ndarray) -> tuple[list[float], float]:
    pixels = rgb[mask]
    if pixels.size == 0:
        return [0.1, 0.1, 0.1], 0.0
    linear = srgb_to_linear(pixels)
    luma = linear @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    cutoff = float(np.quantile(luma, 0.9))
    top = linear[luma >= cutoff]
    color = [round(float(v), 6) for v in np.mean(top, axis=0)]
    return color, round(float(np.quantile(luma, 0.95)), 7)


def main() -> None:
    source_verify = json.loads((EXTRACTION / "REF_B_SOURCE_VERIFICATION.json").read_text())
    extraction_gate = json.loads((EXTRACTION / "P0_A_PREFLIGHT_PASS.json").read_text())
    geometry = json.loads((EXTRACTION / "REF_B_GEOMETRY_DATA.json").read_text())
    colors = json.loads((EXTRACTION / "REF_B_COLOR_MATERIAL_DATA.json").read_text())
    depth = json.loads((EXTRACTION / "REF_B_DEPTH_OCCLUSION_GRAPH.json").read_text())
    assert extraction_gate["status"] == "PASS"
    assert source_verify["refB"]["sha256"] == REF_B_SHA == sha(REF_B)
    rgb = np.asarray(Image.open(REF_B).convert("RGB"), dtype=np.uint8)

    source_regions = {
        "RESIDENT_FRONT_CYAN": "CYAN_LIQUID_GLASS",
        "RESIDENT_FRONT_GOLD": "GOLD_FILAMENT_SURFACE",
        "RESIDENT_BACK_GREEN_GOLD": "EMERALD_GOLD_SILK",
    }
    period_ms = {
        "RESIDENT_FRONT_CYAN": 4400,
        "RESIDENT_FRONT_GOLD": 2900,
        "RESIDENT_BACK_GREEN_GOLD": 6100,
    }
    traces = []
    for index, trace in enumerate(geometry["tracePaths"]):
        group = trace["semanticGroup"]
        high_signal = colors["regions"][group]["highSignalColorSelection"]
        traces.append({
            "id": trace["traceId"],
            "semanticGroup": group,
            "materialClass": source_regions[group],
            "depthRole": "FRONT" if group in ("RESIDENT_FRONT_CYAN", "RESIDENT_FRONT_GOLD") else "BACK_GROUP_INFERENCE",
            "pointsPx": trace["pointsPx"],
            "annotationWidthPx": trace["annotationWidthPx"],
            "signalWidthPx": trace["imageSignalWidth"]["crossSectionWidthPx"]["p50"],
            "measuredSignalWidthPx": trace["imageSignalWidth"]["crossSectionWidthPx"],
            "baseColorLinear": high_signal["linearMean"],
            "phaseRad": round((index * 2.399963229728653) % (2 * np.pi), 6),
            "periodMs": period_ms[group],
            "sourceLengthPx": trace["lengthPx"],
            "confidence": "PIXEL_TRACE_MEASURED; material and per-identity mapping follow frozen semantic group",
        })

    bokeh = []
    bokeh_mask = np.asarray(Image.open(ROOT / "docs/p0_3/roi_masks/REF_B_BACKGROUND_BOKEH.png")) > 0
    for index, component in enumerate(geometry["backgroundBokehDetection"]["connectedComponents8"]["components"]):
        x, y, w, h = component["bboxXYWH"]
        cx, cy = component["centroidPx"]
        crop_component = np.zeros_like(bokeh_mask)
        crop_component[y:y + h, x:x + w] = bokeh_mask[y:y + h, x:x + w]
        color, peak_luma = brightest_linear_color(rgb, crop_component)
        bokeh.append({
            "id": f"BOKEH-{index + 1:02d}",
            "centerPx": [round(cx, 3), round(cy, 3)],
            "bboxXYWH": component["bboxXYWH"],
            "radiusPx": round(max(w, h) / 2, 3),
            "colorLinear": color,
            "p95LuminanceLinear": peak_luma,
            "phaseRad": round((index * 1.61803398875) % (2 * np.pi), 6),
            "confidence": "FROZEN_BOKEH_MASK_COMPONENT",
        })

    droplets = []
    for index, component in enumerate(geometry["dropletAndParticleDetection"]["largeDropletCandidates"]):
        x, y, w, h = component["bboxXYWH"]
        cx, cy = component["centroidPx"]
        component_mask = np.zeros((1254, 1254), dtype=bool)
        component_mask[y:y + h, x:x + w] = True
        color, peak_luma = brightest_linear_color(rgb, component_mask)
        droplets.append({
            "id": f"DROP-{index + 1:02d}",
            "centerPx": [round(cx, 3), round(cy, 3)],
            "bboxXYWH": component["bboxXYWH"],
            "radiusPx": round((w + h) / 4, 3),
            "colorLinear": color,
            "p95LuminanceLinear": peak_luma,
            "depthRole": "UNCERTAIN_FROM_SINGLE_IMAGE; conservative FRONT/OFF_CORE placement is runtime inference",
        })

    particles = []
    for index, component in enumerate(geometry["dropletAndParticleDetection"]["smallParticleCandidates"]):
        x, y, w, h = component["bboxXYWH"]
        cx, cy = component["centroidPx"]
        component_mask = np.zeros((1254, 1254), dtype=bool)
        component_mask[y:y + h, x:x + w] = True
        color, peak_luma = brightest_linear_color(rgb, component_mask)
        particles.append({
            "id": f"PARTICLE-{index + 1:02d}",
            "centerPx": [round(cx, 3), round(cy, 3)],
            "bboxXYWH": component["bboxXYWH"],
            "radiusPx": round(max(w, h) / 2, 3),
            "colorLinear": color,
            "p95LuminanceLinear": peak_luma,
            "phaseRad": round((index * 2.399963229728653) % (2 * np.pi), 6),
            "depthRole": "INFERRED_PARTICLE_LAYER",
        })

    highlight_nodes = []
    for group, features in geometry["signalTopology"].items():
        for index, item in enumerate(features["specularHighlightCandidates"]["components"]):
            highlight_nodes.append({
                "id": f"HL-{group}-{index + 1:02d}",
                "semanticGroup": group,
                "centerPx": item["centroidPx"],
                "bboxXYWH": item["bboxXYWH"],
                "meanColorLinear": [round(float(v), 6) for v in srgb_to_linear(np.asarray(item["meanSrgb"]))],
                "phaseRad": round((len(highlight_nodes) * 1.61803398875) % (2 * np.pi), 6),
                "depthRole": "INFERRED_FROM_SEMANTIC_GROUP",
            })

    values = {
        "schema": "AI_ORB_TAB3_OUTER_OPTICAL_RUNTIME_V1",
        "version": "1.0.0",
        "source": {
            "refBSha256": REF_B_SHA,
            "p0_3ContractSha256": source_verify["p0_3Contract"]["sha256"],
            "outerOpticalContractSha256": sha(EXTRACTION / "OUTER_OPTICAL_FIELD_CONTRACT_V1.json"),
            "geometryDataSha256": sha(EXTRACTION / "REF_B_GEOMETRY_DATA.json"),
            "extractionGate": "P0_A_PASS",
        },
        "referenceProjection": {
            "canvasPx": [1254, 1254],
            "centerPx": [626.5, 620.5],
            "coreDiameterPx": round(1254 * 0.51994, 6),
            "coordinateOrigin": "top_left",
            "note": "The scene camera and scale remain frozen; pixel positions are mapped around the projected core center without registration or warping.",
        },
        "motion": {
            "timeSource": "AIOrbMotionSnapshot.simTimeSec",
            "identityPeriodsSource": "frozen P0-3 motion contract; group-to-identity attribution is recorded per trace",
            "staticImageCannotDetermineMotion": True,
            "residentPeriodsMs": period_ms,
        },
        "traces": traces,
        "projectedCrossings": geometry["branchGraph"]["nodes"],
        "bokeh": bokeh,
        "droplets": droplets,
        "particles": particles,
        "highlightNodes": highlight_nodes,
        "depthOcclusionGraph": depth,
        "runtimeParameters": {
            "fieldDepthAxisWorldInferred": 0.34,
            "filamentLaneCountPerTrace": 2,
            "filamentLaneOffsetsAsFractionOfMeasuredBodyWidth": [-0.28, 0.28],
            "surfaceWidthSource": "frozen centerline annotation widthPx",
            "filamentWidthSource": "REF_B cross-section chromatic signal width p50",
            "note": "Field depth and internal filament lane offsets are implementation hypotheses and will be tuned only from captured AVD comparison; they are not claimed as direct single-image measurements.",
        },
    }
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    output = ASSET_DIR / "OUTER_OPTICAL_FIELD_RUNTIME_V1.json"
    output.write_text(json.dumps(values, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"runtimeManifest": str(output.relative_to(ROOT)), "sha256": sha(output), "traceCount": len(traces), "bokehCount": len(bokeh), "droplets": len(droplets), "particles": len(particles), "highlightNodes": len(highlight_nodes)}, indent=2))


if __name__ == "__main__":
    main()
