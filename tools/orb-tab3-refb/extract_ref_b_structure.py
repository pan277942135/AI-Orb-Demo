#!/usr/bin/env python3
"""Extract reproducible REF_B structures without modifying frozen inputs.

The P0-3 ROI masks are treated as immutable semantic anchors. All additional
pixel features are explicitly labeled as detector output; they never replace
or rewrite the frozen masks. Static-image depth and motion remain inferred.
"""

from __future__ import annotations

import ast
import csv
import hashlib
import json
import math
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage as ndi

ROOT = Path(__file__).resolve().parents[2]
P0 = ROOT / "docs/p0_3"
OUTPUT = ROOT / "AI_ORB_DEMO_TAB3_REFB_OUTER_OPTICAL_FIELD_V1/ref_b_extraction"
CONTRACT_PATH = P0 / "contract/AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.json"
REFERENCE_PATH = P0 / "references/REF_B_FULL_SCENE_RGB.png"
MASK_MANIFEST_PATH = P0 / "ROI_MASK_FREEZE_MANIFEST.json"
MASK_ROOT = P0 / "roi_masks"
ZIP_PATH = Path("/workspace/attachments/8595e0fa-cc9f-44e1-8d2d-2835da5f0a48/AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.zip")
EXPECTED_REF_B = "910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122"
EXPECTED_CONTRACT = "aa0729887570db4a0da0d3fb7a629a1a7273666ba0c7ab129b60d70bfdee9170"
EXPECTED_ZIP = "9e4d1b3230c9404814d6f23974371f33c4c9fc770788472070108ce6ea95f3b0"
CANVAS = (1254, 1254)

REGION_COLORS = {
    "RESIDENT_FRONT_CYAN": (25, 235, 255),
    "RESIDENT_FRONT_GOLD": (255, 187, 48),
    "RESIDENT_BACK_GREEN_GOLD": (148, 226, 60),
    "BACKGROUND_BOKEH": (224, 87, 238),
    "DROPLETS_AND_PARTICLES": (255, 255, 255),
}


def sha_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha_file(path: Path) -> str:
    return sha_bytes(path.read_bytes())


def json_dump(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def pct(values: np.ndarray, qs=(10, 25, 50, 75, 90, 95)) -> dict[str, float]:
    if values.size == 0:
        return {f"p{q}": 0.0 for q in qs}
    result = np.percentile(values.astype(np.float64), qs)
    return {f"p{q}": round(float(v), 6) for q, v in zip(qs, result)}


def srgb_to_linear(rgb: np.ndarray) -> np.ndarray:
    x = rgb.astype(np.float32) / 255.0
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def otsu(values: np.ndarray, bins: int = 256) -> float:
    values = np.asarray(values, dtype=np.float64)
    values = values[np.isfinite(values)]
    if values.size == 0 or float(values.max()) <= float(values.min()):
        return float(values.min()) if values.size else 0.0
    hist, edges = np.histogram(values, bins=bins, range=(float(values.min()), float(values.max())))
    p = hist.astype(np.float64) / max(1, int(hist.sum()))
    centers = (edges[:-1] + edges[1:]) * 0.5
    omega = np.cumsum(p)
    mu = np.cumsum(p * centers)
    total = mu[-1]
    denom = omega * (1.0 - omega)
    score = np.zeros_like(denom)
    valid = denom > 1e-14
    score[valid] = (total * omega[valid] - mu[valid]) ** 2 / denom[valid]
    return float(centers[int(np.argmax(score))])


def bbox_xywh(mask: np.ndarray) -> list[int] | None:
    ys, xs = np.nonzero(mask)
    if not xs.size:
        return None
    return [int(xs.min()), int(ys.min()), int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1)]


def centroid_xy(mask: np.ndarray) -> list[float] | None:
    ys, xs = np.nonzero(mask)
    if not xs.size:
        return None
    return [round(float(xs.mean()), 4), round(float(ys.mean()), 4)]


def connected_components(mask: np.ndarray, min_area: int = 1) -> list[dict[str, Any]]:
    labels, count = ndi.label(mask, structure=np.ones((3, 3), dtype=np.uint8))
    objects: list[dict[str, Any]] = []
    for label in range(1, count + 1):
        ys, xs = np.nonzero(labels == label)
        area = int(xs.size)
        if area < min_area:
            continue
        objects.append({
            "areaPx": area,
            "bboxXYWH": [int(xs.min()), int(ys.min()), int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1)],
            "centroidPx": [round(float(xs.mean()), 3), round(float(ys.mean()), 3)],
        })
    return sorted(objects, key=lambda item: (-item["areaPx"], item["centroidPx"][1], item["centroidPx"][0]))


def mask_color_metrics(rgb: np.ndarray, linear: np.ndarray, hsv: np.ndarray, mask: np.ndarray) -> dict[str, Any]:
    pixels = rgb[mask]
    lp = linear[mask]
    hp = hsv[mask].astype(np.float32)
    if not pixels.size:
        return {"pixelCount": 0}
    luma = lp @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    hue_deg = hp[:, 0] * (360.0 / 255.0)
    hist, _ = np.histogram(hue_deg, bins=36, range=(0, 360))
    return {
        "pixelCount": int(pixels.shape[0]),
        "srgbMean": [round(float(v), 3) for v in pixels.mean(axis=0)],
        "srgbMedian": [round(float(v), 3) for v in np.median(pixels, axis=0)],
        "linearMean": [round(float(v), 6) for v in lp.mean(axis=0)],
        "linearP10P50P90ByChannel": [pct(lp[:, c], (10, 50, 90)) for c in range(3)],
        "relativeLuminanceLinear": pct(luma, (10, 25, 50, 75, 90, 95)),
        "hueDegrees": pct(hue_deg, (10, 25, 50, 75, 90)),
        "saturation255": pct(hp[:, 1], (10, 25, 50, 75, 90)),
        "value255": pct(hp[:, 2], (10, 25, 50, 75, 90)),
        "hueHistogram36Bins": [int(v) for v in hist],
    }


def trace_specs() -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """Read centerline annotations from the frozen P0-3 mask generator AST."""
    source = ROOT / "tools/orb-v3-generator/p0_3/generate_semantic_roi_masks.py"
    tree = ast.parse(source.read_text(encoding="utf-8"))
    assignments: dict[str, ast.AST] = {}
    save_nodes: dict[str, ast.Call] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name):
                    assignments[target.id] = node.value
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "save_mask" and len(node.args) >= 2:
            if isinstance(node.args[0], ast.Constant) and isinstance(node.args[0].value, str):
                save_nodes[node.args[0].value] = node

    def parse_line_expr(node: ast.AST, name: str) -> list[dict[str, Any]]:
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.BitOr):
            return parse_line_expr(node.left, name) + parse_line_expr(node.right, name)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "line_mask":
            points = ast.literal_eval(node.args[0])
            width = int(ast.literal_eval(node.args[1]))
            return [{"pointsPx": [[int(x), int(y)] for x, y in points], "annotationWidthPx": width, "sourceLine": node.lineno}]
        raise ValueError(f"Could not parse frozen trace {name}: {ast.dump(node)[:300]}")

    cyan_node = save_nodes["REF_B_RESIDENT_FRONT_CYAN"]
    cyan = parse_line_expr(cyan_node.args[1], "REF_B_RESIDENT_FRONT_CYAN")
    front_gold_expr = assignments["front_gold"]
    front_gold = parse_line_expr(front_gold_expr, "REF_B_RESIDENT_FRONT_GOLD")
    back_assign = assignments["back_paths"]
    back_raw = ast.literal_eval(back_assign)
    back = [{"pointsPx": [[int(x), int(y)] for x, y in points], "annotationWidthPx": int(width), "sourceLine": back_assign.lineno} for points, width in back_raw]
    specs = []
    for group, paths, identity_status in (
        ("RESIDENT_FRONT_CYAN", cyan, "C01 frozen semantic group"),
        ("RESIDENT_FRONT_GOLD", front_gold, "A02-like group; exact path-to-identity mapping is not separately masked"),
        ("RESIDENT_BACK_GREEN_GOLD", back, "aggregate envelope; G01/G02/A01 path attribution remains ambiguous"),
    ):
        for index, path in enumerate(paths, 1):
            coords = np.asarray(path["pointsPx"], dtype=np.float64)
            delta = np.diff(coords, axis=0)
            length = float(np.linalg.norm(delta, axis=1).sum())
            curvature = []
            for i in range(1, len(coords) - 1):
                a, b, c = coords[i - 1], coords[i], coords[i + 1]
                ab, bc, ac = np.linalg.norm(b - a), np.linalg.norm(c - b), np.linalg.norm(c - a)
                if min(ab, bc, ac) > 1e-6:
                    abv, bcv = b - a, c - b
                    cross = abs(float(abv[0] * bcv[1] - abv[1] * bcv[0]))
                    curvature.append(2.0 * cross / (ab * bc * ac))
            path.update({
                "traceId": f"{group}-TRACE-{index:02d}",
                "semanticGroup": group,
                "lengthPx": round(length, 3),
                "lengthOverReferenceD": round(length / (1254 * 0.51994), 6),
                "curvaturePerPx": pct(np.asarray(curvature, dtype=np.float64), (10, 50, 90)),
                "bboxXYWH": bbox_xywh(_rasterize_path(path["pointsPx"], path["annotationWidthPx"])),
                "identityAttribution": identity_status,
                "evidenceClass": "OBSERVED_PIXEL_SPACE_ANNOTATION; not a unique 3D reconstruction",
            })
            specs.append(path)
    return specs, {"path": str(source.relative_to(ROOT)), "sha256": sha_file(source), "parser": "Python AST literal read; no source modification", "lineMaskTraceIds": [p["traceId"] for p in specs]}


def _rasterize_path(points: list[list[int]], width: int) -> np.ndarray:
    im = Image.new("L", CANVAS, 0)
    ImageDraw.Draw(im).line([tuple(p) for p in points], fill=255, width=width, joint="curve")
    return np.asarray(im) > 0


def score_for_class(linear: np.ndarray, group: str) -> np.ndarray:
    r, g, b = linear[..., 0], linear[..., 1], linear[..., 2]
    if group == "RESIDENT_FRONT_CYAN":
        return (g + b) * 0.5 - r
    if group == "RESIDENT_FRONT_GOLD":
        return (r + g) * 0.5 - b
    return g - (r + b) * 0.5


def sample_trace_widths(path: dict[str, Any], linear: np.ndarray, semantic_mask: np.ndarray) -> dict[str, Any]:
    points = np.asarray(path["pointsPx"], dtype=np.float64)
    group = path["semanticGroup"]
    score = score_for_class(linear, group)
    threshold = otsu(score[semantic_mask])
    profile_offsets = np.arange(-64, 65, 1, dtype=np.float64)
    measured: list[float] = []
    hits = 0
    total = 0
    for i in range(len(points) - 1):
        start, end = points[i], points[i + 1]
        segment = end - start
        length = float(np.linalg.norm(segment))
        steps = max(1, int(length // 14.0))
        tangent = segment / max(1e-9, length)
        normal = np.array([-tangent[1], tangent[0]])
        for step in range(steps):
            t = (step + 0.5) / steps
            center = start * (1 - t) + end * t
            total += 1
            # A small local search compensates for pixel-grid rasterization, not for curve registration.
            best = None
            for shift in (-8, -4, 0, 4, 8):
                q = np.rint(center + normal * shift).astype(int)
                if 0 <= q[0] < CANVAS[0] and 0 <= q[1] < CANVAS[1] and semantic_mask[q[1], q[0]]:
                    v = float(score[q[1], q[0]])
                    if best is None or v > best[0]:
                        best = (v, shift)
            if best is not None and best[0] > threshold:
                hits += 1
            sample_center = center + normal * (best[1] if best else 0)
            xy = sample_center[None, :] + profile_offsets[:, None] * normal[None, :]
            xi = np.clip(np.rint(xy[:, 0]).astype(int), 0, CANVAS[0] - 1)
            yi = np.clip(np.rint(xy[:, 1]).astype(int), 0, CANVAS[1] - 1)
            profile = score[yi, xi]
            in_roi = semantic_mask[yi, xi]
            active = in_roi & (profile > threshold)
            if not active.any():
                continue
            peak = int(np.argmax(np.where(active, profile, -np.inf)))
            lo = hi = peak
            while lo > 0 and active[lo - 1]:
                lo -= 1
            while hi + 1 < profile.size and active[hi + 1]:
                hi += 1
            if hi > lo:
                measured.append(float(hi - lo + 1))
    return {
        "scoreDefinition": {"RESIDENT_FRONT_CYAN": "(linearG + linearB)/2 - linearR", "RESIDENT_FRONT_GOLD": "(linearR + linearG)/2 - linearB", "RESIDENT_BACK_GREEN_GOLD": "linearG - (linearR + linearB)/2"}[group],
        "thresholdMethod": "Otsu on pixels inside the immutable semantic group mask",
        "thresholdValueLinearColorDifference": round(float(threshold), 7),
        "sampleSpacingTargetPx": 14,
        "crossSectionHalfRangePx": 64,
        "validSignalHitRate": round(hits / max(1, total), 6),
        "crossSectionWidthPx": pct(np.asarray(measured, dtype=np.float64), (10, 25, 50, 75, 90)),
        "usableCrossSectionCount": len(measured),
        "interpretation": "width of Otsu-selected chromatic signal along the frozen trace normal, clipped to the corresponding immutable semantic envelope; it measures highlighted signal inside that envelope, not the full transparent surface boundary",
        "widthClippedToFrozenMask": True,
    }


def projected_trace_intersections(traces: list[dict[str, Any]]) -> list[dict[str, Any]]:
    def cross(a: np.ndarray, b: np.ndarray) -> float:
        return float(a[0] * b[1] - a[1] * b[0])

    candidates: list[dict[str, Any]] = []
    for i, left in enumerate(traces):
        a_points = np.asarray(left["pointsPx"], dtype=np.float64)
        for right in traces[i + 1:]:
            b_points = np.asarray(right["pointsPx"], dtype=np.float64)
            found: list[np.ndarray] = []
            for ai in range(len(a_points) - 1):
                p, r = a_points[ai], a_points[ai + 1] - a_points[ai]
                for bi in range(len(b_points) - 1):
                    q, s = b_points[bi], b_points[bi + 1] - b_points[bi]
                    denom = cross(r, s)
                    if abs(denom) < 1e-8:
                        continue
                    t = cross(q - p, s) / denom
                    u = cross(q - p, r) / denom
                    if -1e-7 <= t <= 1 + 1e-7 and -1e-7 <= u <= 1 + 1e-7:
                        point = p + t * r
                        if not any(np.linalg.norm(point - old) < 5 for old in found):
                            found.append(point)
            for point in found:
                candidates.append({
                    "nodeId": f"X{len(candidates) + 1:02d}",
                    "pointPx": [round(float(point[0]), 3), round(float(point[1]), 3)],
                    "pointNormalized": [round(float(point[0] / 1254), 6), round(float(point[1] / 1254), 6)],
                    "traceIds": [left["traceId"], right["traceId"]],
                    "nodeType": "PROJECTED_TRACE_CROSSING_CANDIDATE",
                    "evidenceClass": "OBSERVED_2D_POLYLINE_INTERSECTION; NOT_PROOF_OF_PHYSICAL_BRANCH_OR_SAME_DEPTH",
                    "depthOrder": "UNCERTAIN_FROM_SINGLE_RGB_REFERENCE",
                })
    return candidates


def signal_feature_components(mask: np.ndarray, score: np.ndarray, threshold: float, rgb: np.ndarray, minimum_area: int = 6) -> list[dict[str, Any]]:
    """Return location, scale, orientation and color for image-derived fragments."""
    labels, count = ndi.label(mask & (score > threshold), structure=np.ones((3, 3), dtype=np.uint8))
    components: list[dict[str, Any]] = []
    for label_id in range(1, count + 1):
        ys, xs = np.nonzero(labels == label_id)
        area = int(xs.size)
        if area < minimum_area:
            continue
        coords = np.column_stack([xs.astype(np.float64), ys.astype(np.float64)])
        centered = coords - coords.mean(axis=0, keepdims=True)
        covariance = centered.T @ centered / max(1, area)
        eigenvalues, eigenvectors = np.linalg.eigh(covariance)
        order = np.argsort(eigenvalues)[::-1]
        major, minor = np.sqrt(np.maximum(eigenvalues[order], 0))
        direction = eigenvectors[:, order[0]]
        pixels = rgb[ys, xs]
        components.append({
            "areaPx": area,
            "bboxXYWH": [int(xs.min()), int(ys.min()), int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1)],
            "centroidPx": [round(float(xs.mean()), 3), round(float(ys.mean()), 3)],
            "principalAxisDegreesImageXY": round(float(math.degrees(math.atan2(direction[1], direction[0]))), 3),
            "pcaMajorScalePx": round(float(major * 4), 3),
            "pcaMinorScalePx": round(float(minor * 4), 3),
            "aspectRatio": round(float(major / max(minor, 0.25)), 4),
            "meanSrgb": [round(float(v), 2) for v in pixels.mean(axis=0)],
            "meanSignal": round(float(score[ys, xs].mean()), 8),
            "evidenceClass": "OBSERVED_RGB_SIGNAL_COMPONENT; may be a highlight fragment of a larger occluded surface",
        })
    return sorted(components, key=lambda item: (-item["areaPx"], item["centroidPx"][1], item["centroidPx"][0]))


def component_summary(mask: np.ndarray, min_area: int = 1) -> dict[str, Any]:
    comps = connected_components(mask, min_area=min_area)
    return {"componentCount": len(comps), "components": comps}


def draw_density_map(rgb_image: Image.Image, residents: np.ndarray, droplets: np.ndarray, contrast: np.ndarray, gradient: np.ndarray, path: Path) -> None:
    structure = residents | droplets
    norm_c = np.clip(contrast / max(float(np.percentile(contrast[structure], 99.5)) if structure.any() else 1.0, 1e-6), 0, 1)
    norm_g = np.clip(gradient / max(float(np.percentile(gradient[structure], 99.5)) if structure.any() else 1.0, 1e-6), 0, 1)
    raw = structure.astype(np.float32) * (0.55 * norm_c + 0.45 * norm_g)
    density = ndi.gaussian_filter(raw, sigma=12.0)
    density /= max(float(density.max()), 1e-8)
    heat = np.zeros((*density.shape, 3), dtype=np.uint8)
    heat[..., 0] = np.clip(255 * np.minimum(1, density * 2.0), 0, 255).astype(np.uint8)
    heat[..., 1] = np.clip(255 * np.maximum(0, 1 - np.abs(density * 2 - 1)), 0, 255).astype(np.uint8)
    heat[..., 2] = np.clip(255 * np.maximum(0, 1 - density * 2), 0, 255).astype(np.uint8)
    base = np.asarray(rgb_image).astype(np.float32)
    alpha = (density[..., None] * 0.76).astype(np.float32)
    out = base * (1 - alpha) + heat.astype(np.float32) * alpha
    Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), "RGB").save(path)


def draw_color_chart(color_stats: dict[str, Any], path: Path) -> None:
    groups = ["RESIDENT_FRONT_CYAN", "RESIDENT_FRONT_GOLD", "RESIDENT_BACK_GREEN_GOLD", "DROPLETS_AND_PARTICLES"]
    w, row_h, margin = 1100, 115, 86
    image = Image.new("RGB", (w, margin + row_h * len(groups) + 60), (13, 22, 27))
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default()
    draw.text((14, 12), "REF_B hue histogram • 36 bins × 10 degrees • bar heights normalized per row", fill=(245, 248, 250), font=font)
    draw.text((280, 48), "Hue angle (degrees; descriptive, overlapping material classes are not applied)", fill=(190, 202, 210), font=font)
    for row, name in enumerate(groups):
        y0 = margin + row * row_h
        draw.text((14, y0 + 6), name, fill=(235, 240, 245), font=font)
        selected = color_stats[name].get("highSignalColorSelection", {})
        hist = np.asarray(selected.get("hueHistogram36Bins", color_stats[name]["hueHistogram36Bins"]), dtype=np.float64)
        peak = max(float(hist.max()), 1.0)
        chart_x0, chart_y0, chart_w, chart_h = 280, y0 + 8, 780, 78
        for i, value in enumerate(hist):
            x0 = chart_x0 + int(i * chart_w / len(hist))
            x1 = chart_x0 + int((i + 1) * chart_w / len(hist)) - 1
            height = int(float(value) / peak * chart_h)
            hue = int(i * 255 / len(hist))
            color = Image.new("HSV", (1, 1), (hue, 220, 230)).convert("RGB").getpixel((0, 0))
            draw.rectangle((x0, chart_y0 + chart_h - height, x1, chart_y0 + chart_h), fill=color)
        draw.line((chart_x0, chart_y0 + chart_h, chart_x0 + chart_w, chart_y0 + chart_h), fill=(140, 150, 160), width=1)
        if row == len(groups) - 1:
            for deg in (0, 90, 180, 270, 360):
                x = chart_x0 + int(chart_w * deg / 360)
                draw.line((x, chart_y0 + chart_h, x, chart_y0 + chart_h + 5), fill=(190, 200, 210), width=1)
                draw.text((x - 8, chart_y0 + chart_h + 8), str(deg), fill=(190, 200, 210), font=font)
    image.save(path)


def make_overlays(rgb_image: Image.Image, masks: dict[str, np.ndarray], traces: list[dict[str, Any]]) -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    base = rgb_image.convert("RGB")
    overlay = Image.new("RGBA", CANVAS, (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    for name, color in REGION_COLORS.items():
        mask = masks[name]
        mask_image = Image.fromarray(mask.astype(np.uint8) * 255, "L")
        outline = Image.fromarray(np.maximum(np.asarray(mask_image.filter(ImageFilterFallback.MAX)), 0).astype(np.uint8), "L")
        inner = Image.fromarray(np.asarray(mask_image.filter(ImageFilterFallback.MIN)), "L")
        edge = np.asarray(outline).astype(np.int16) - np.asarray(inner).astype(np.int16)
        edge_mask = edge > 0
        rgba = np.zeros((CANVAS[1], CANVAS[0], 4), dtype=np.uint8)
        rgba[edge_mask, :3] = color
        rgba[edge_mask, 3] = 225
        overlay.alpha_composite(Image.fromarray(rgba, "RGBA"))
    out = Image.alpha_composite(base.convert("RGBA"), overlay)
    draw = ImageDraw.Draw(out)
    font = ImageFont.load_default()
    for index, trace in enumerate(traces, 1):
        pts = [tuple(p) for p in trace["pointsPx"]]
        group_color = REGION_COLORS[trace["semanticGroup"]]
        draw.line(pts, fill=(*group_color, 255), width=3, joint="curve")
        draw.ellipse((pts[0][0] - 5, pts[0][1] - 5, pts[0][0] + 5, pts[0][1] + 5), fill=(255, 255, 255, 255))
        x, y = pts[len(pts) // 2]
        draw.text((x + 5, y + 4), trace["traceId"], fill=(255, 255, 255, 255), font=font, stroke_width=1, stroke_fill=(0, 0, 0, 255))
    out.convert("RGB").save(OUTPUT / "REF_B_GEOMETRY_OVERLAY.png")

    # Atlas keeps original pixel coordinates and image content; it crops only for inspection.
    tiles = []
    for name in REGION_COLORS:
        box = bbox_xywh(masks[name])
        if box is None:
            continue
        x, y, w, h = box
        pad = 24
        crop_box = (max(0, x - pad), max(0, y - pad), min(CANVAS[0], x + w + pad), min(CANVAS[1], y + h + pad))
        crop = base.crop(crop_box).convert("RGBA")
        mm = Image.fromarray((masks[name].astype(np.uint8) * 125), "L").crop(crop_box)
        tint = Image.new("RGBA", crop.size, (*REGION_COLORS[name], 0))
        tint.putalpha(mm)
        crop = Image.alpha_composite(crop, tint)
        tile = Image.new("RGB", (376, 310), (9, 15, 19))
        crop.thumbnail((360, 270), Image.Resampling.LANCZOS)
        tile.paste(crop.convert("RGB"), ((376 - crop.width) // 2, 30 + (270 - crop.height) // 2))
        ImageDraw.Draw(tile).text((8, 8), name, fill=(255, 255, 255))
        ImageDraw.Draw(tile).text((8, 294), f"source bbox x={box[0]} y={box[1]} w={box[2]} h={box[3]}", fill=(180, 195, 205))
        tiles.append(tile)
    atlas = Image.new("RGB", (3 * 376, math.ceil(len(tiles) / 3) * 310), (8, 12, 15))
    for i, tile in enumerate(tiles):
        atlas.paste(tile, ((i % 3) * 376, (i // 3) * 310))
    atlas.save(OUTPUT / "REF_B_STRUCTURE_ATLAS.png")


class ImageFilterFallback:
    """Tiny named bridge so the extraction has no OpenCV/skimage dependency."""

    from PIL import ImageFilter

    MAX = ImageFilter.MaxFilter(3)
    MIN = ImageFilter.MinFilter(3)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    p0_contract_bytes = CONTRACT_PATH.read_bytes()
    ref_bytes = REFERENCE_PATH.read_bytes()
    archive_bytes = ZIP_PATH.read_bytes()
    assert sha_bytes(p0_contract_bytes) == EXPECTED_CONTRACT
    assert sha_bytes(ref_bytes) == EXPECTED_REF_B
    assert sha_bytes(archive_bytes) == EXPECTED_ZIP
    contract = json.loads(p0_contract_bytes)
    manifest = json.loads(MASK_MANIFEST_PATH.read_text(encoding="utf-8"))
    with zipfile.ZipFile(ZIP_PATH) as zf:
        archive_names = set(zf.namelist())
        zip_contract = zf.read("AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.json")
        zip_reference = zf.read("references/REF_B_FULL_SCENE_RGB.png")
        zip_checksums = json.loads(zf.read("SHA256SUMS.json"))
        assert zip_contract == p0_contract_bytes
        assert sha_bytes(zip_reference) == EXPECTED_REF_B
        for rel, expected in zip_checksums.items():
            assert sha_bytes(zf.read(rel)) == expected["sha256"], f"Attachment checksum mismatch: {rel}"
    image = Image.open(REFERENCE_PATH)
    image.load()
    assert image.mode == "RGB" and image.size == CANVAS
    rgb_image = image.copy()
    rgb = np.asarray(rgb_image, dtype=np.uint8)
    linear = srgb_to_linear(rgb)
    hsv = np.asarray(rgb_image.convert("HSV"), dtype=np.uint8)
    luma = linear @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    gy, gx = np.gradient(luma)
    gradient = np.hypot(gx, gy)
    local_base = ndi.gaussian_filter(luma, sigma=9.0)
    local_contrast = np.abs(luma - local_base)

    region_records = {r["id"]: r for r in manifest["records"]}
    frozen_regions = contract["visual_regions"]["regions"]
    required_regions = [r["id"] for r in frozen_regions]
    assert len(required_regions) == 13 and len(set(required_regions)) == 13
    required_masks = [r for r in frozen_regions if r.get("mask_file")]
    assert len(required_masks) == 12 and len(region_records) == 12
    masks: dict[str, np.ndarray] = {}
    for region in required_masks:
        path = P0 / region["mask_file"]
        record = region_records[region["id"]]
        mask_im = Image.open(path)
        assert mask_im.mode == "L" and mask_im.size == CANVAS
        assert sha_file(path) == record["sha256"]
        assert record["sourceSha256"] == contract["source_of_truth"]["reference_images"][0 if region["reference"] == "REF_A" else 1]["sha256"]
        masks[region["id"]] = np.asarray(mask_im, dtype=np.uint8) > 0
    assert manifest["frozen"] and manifest["contractSha256"] == EXPECTED_CONTRACT
    assert manifest["selfCheck"]["contractRegionCount"] == 13
    assert manifest["selfCheck"]["requiredMaskCount"] == 12

    trace_paths, trace_source = trace_specs()
    trace_map = {item["traceId"]: item for item in trace_paths}
    for trace in trace_paths:
        group_mask = masks[trace["semanticGroup"]]
        trace["imageSignalWidth"] = sample_trace_widths(trace, linear, group_mask)

    structure_masks = [masks[n] for n in ("RESIDENT_FRONT_CYAN", "RESIDENT_FRONT_GOLD", "RESIDENT_BACK_GREEN_GOLD")]
    resident_union = np.logical_or.reduce(structure_masks)
    detected_pixels = resident_union | masks["DROPLETS_AND_PARTICLES"]
    density_path = OUTPUT / "REF_B_VISUAL_DENSITY_MAP.png"
    draw_density_map(rgb_image, resident_union, masks["DROPLETS_AND_PARTICLES"], local_contrast, gradient, density_path)

    geometric_data: dict[str, Any] = {"coordinateSystem": "pixel origin top-left, x right, y down; normalized coordinates divide by 1254", "traceSource": trace_source, "tracePaths": trace_paths, "regions": {}}
    color_data: dict[str, Any] = {"sourceColorSpace": "sRGB encoded PNG; color statistics include separately decoded linear RGB luminance", "hueConvention": "Pillow HSV H mapped to [0,360); descriptive histograms do not define exclusive material masks", "regions": {}}
    inventory: list[dict[str, Any]] = []
    group_names = ["RESIDENT_FRONT_CYAN", "RESIDENT_FRONT_GOLD", "RESIDENT_BACK_GREEN_GOLD", "BACKGROUND_BOKEH", "DROPLETS_AND_PARTICLES"]
    for name in group_names:
        mask = masks[name]
        box = bbox_xywh(mask)
        centroid = centroid_xy(mask)
        colors = mask_color_metrics(rgb, linear, hsv, mask)
        class_score = score_for_class(linear, name) if name in group_names[:3] else None
        if class_score is not None:
            class_threshold = max(0.0, otsu(class_score[mask]))
            high_signal = mask & (class_score > class_threshold)
            colors["highSignalColorSelection"] = mask_color_metrics(rgb, linear, hsv, high_signal)
            colors["highSignalColorSelection"]["scoreThreshold"] = round(float(class_threshold), 8)
            colors["highSignalColorSelection"]["thresholdMethod"] = "max(0, Otsu threshold) for the group's linear RGB dominance score"
            colors["highSignalColorSelection"]["note"] = "Descriptive selection inside the frozen ROI; this is not a replacement mask or a material acceptance gate."
        colors["descriptiveHueBandsPixelCount"] = {
            "emerald_green_80_160deg": int(np.count_nonzero(mask & (hsv[:, :, 0] >= round(80 / 360 * 255)) & (hsv[:, :, 0] < round(160 / 360 * 255)))),
            "gold_25_65deg": int(np.count_nonzero(mask & (hsv[:, :, 0] >= round(25 / 360 * 255)) & (hsv[:, :, 0] < round(65 / 360 * 255)))),
            "aqua_cyan_160_230deg": int(np.count_nonzero(mask & (hsv[:, :, 0] >= round(160 / 360 * 255)) & (hsv[:, :, 0] < round(230 / 360 * 255)))),
            "sapphire_230_280deg": int(np.count_nonzero(mask & (hsv[:, :, 0] >= round(230 / 360 * 255)) & (hsv[:, :, 0] < round(280 / 360 * 255)))),
        }
        edge = gradient[mask]
        contrast = local_contrast[mask]
        geometric_data["regions"][name] = {
            "maskFile": f"docs/p0_3/roi_masks/REF_B_{name}.png",
            "maskSha256": region_records[name]["sha256"],
            "maskPixels": int(mask.sum()),
            "areaRatioOfCanvas": round(float(mask.mean()), 8),
            "bboxXYWH": box,
            "bboxNormalizedXYWH": [round(box[0] / 1254, 6), round(box[1] / 1254, 6), round(box[2] / 1254, 6), round(box[3] / 1254, 6)] if box else None,
            "centroidPx": centroid,
            "centroidNormalized": [round(centroid[0] / 1254, 6), round(centroid[1] / 1254, 6)] if centroid else None,
            "rowCoverageWidthPx": pct(np.count_nonzero(mask, axis=1), (10, 50, 90)),
            "edgeGradientLinearLuma": pct(edge, (50, 75, 90, 95, 99)),
            "localContrastLinearLuma": pct(contrast, (50, 75, 90, 95, 99)),
            "connectedComponents8": component_summary(mask),
            "geometryInterpretation": "frozen semantic annotation envelope; not a physically segmented transparent surface boundary",
        }
        color_data["regions"][name] = colors
        inventory.append({
            "objectId": f"REFB-{name}", "objectType": name, "evidenceClass": "OBSERVED_FROZEN_SEMANTIC_MASK",
            "count": 1 if name.startswith("RESIDENT_") else len(connected_components(mask)),
            "bboxXYWH": box, "areaPx": int(mask.sum()), "centroidPx": centroid,
            "confidence": "HIGH_FOR_MASK_ALIGNMENT; mask semantics remain at group granularity",
        })

    # Bright/dark signal topology within each frozen resident group is measured at a reproducible, data-derived Otsu threshold.
    topology: dict[str, Any] = {}
    for name in group_names[:3]:
        mask = masks[name]
        score = score_for_class(linear, name)
        threshold = otsu(score[mask])
        selected = mask & (score > threshold)
        edge_scale = max(float(np.percentile(gradient[mask], 99.5)), 1e-8)
        contrast_scale = max(float(np.percentile(local_contrast[mask], 99.5)), 1e-8)
        edge_signal = 0.5 * np.clip(gradient / edge_scale, 0, 1) + 0.5 * np.clip(local_contrast / contrast_scale, 0, 1)
        edge_signal_threshold = otsu(edge_signal[mask])
        edge_fragments = signal_feature_components(mask, edge_signal, edge_signal_threshold, rgb, minimum_area=6)
        luma_threshold = float(np.quantile(luma[mask], 0.995))
        bright_nodes = signal_feature_components(mask, luma, luma_threshold, rgb, minimum_area=2)
        topology[name] = {
            "signal": "linear color-dominance score for the semantic group",
            "thresholdMethod": "Otsu computed only inside frozen group mask",
            "thresholdValue": round(float(threshold), 8),
            "aboveThresholdPixelCount": int(selected.sum()),
            "aboveThresholdAreaRatioOfFrozenMask": round(float(selected.sum() / max(1, mask.sum())), 8),
            "componentsAtThreshold8": component_summary(selected, min_area=3),
            "countMeaning": "image-signal fragments at this algorithmic threshold; not a count of physical tracks because highlights, occlusions and transparency fragment components",
            "edgeAndFilamentCandidates": {
                "signal": "0.5*gradient/p99.5 + 0.5*abs(linearLuma-Gaussian(sigma=9))/p99.5, clipped to [0,1]",
                "thresholdMethod": "Otsu inside the immutable group mask",
                "thresholdValue": round(float(edge_signal_threshold), 8),
                "minimumConnectedAreaPx": 6,
                "count": len(edge_fragments),
                "components": edge_fragments,
                "interpretation": "edge/high-frequency components that may correspond to internal filaments, paired refractive edges, or occlusion fragments; principal-axis measurements are image-space only",
            },
            "specularHighlightCandidates": {
                "thresholdMethod": "99.5th percentile of linear relative luminance inside the immutable group mask",
                "thresholdValueLinearLuminance": round(luma_threshold, 8),
                "minimumConnectedAreaPx": 2,
                "count": len(bright_nodes),
                "components": bright_nodes,
                "interpretation": "localized high-luminance nodes; not all are point light sources and no temporal pulse is observable in REF_B",
            },
        }
        for i, feature in enumerate(edge_fragments, 1):
            inventory.append({
                "objectId": f"REFB-EDGE-{name}-{i:03d}", "objectType": "EDGE_FILAMENT_OR_REFRACTION_FRAGMENT",
                **feature, "semanticGroup": name, "confidence": "MEDIUM_FOR_IMAGE_COMPONENT; physical identity uncertain",
            })
        for i, feature in enumerate(bright_nodes, 1):
            inventory.append({
                "objectId": f"REFB-HIGHLIGHT-{name}-{i:03d}", "objectType": "LOCAL_HIGHLIGHT_CANDIDATE",
                **feature, "semanticGroup": name, "confidence": "MEDIUM_FOR_LOCAL_LUMINANCE_PEAK; source of light uncertain",
            })
    geometric_data["signalTopology"] = topology

    # The disconnected mask components separate large bubble annotations from small particle markers by an objective log-area gap.
    droplet_components = connected_components(masks["DROPLETS_AND_PARTICLES"])
    areas = sorted([c["areaPx"] for c in droplet_components])
    log_areas = np.log(np.asarray(areas, dtype=np.float64))
    gaps = np.diff(log_areas)
    split_index = int(np.argmax(gaps)) if gaps.size else 0
    separating_area = math.sqrt(areas[split_index] * areas[split_index + 1]) if gaps.size else 0.0
    large_droplets = [c for c in droplet_components if c["areaPx"] > separating_area]
    small_particles = [c for c in droplet_components if c["areaPx"] <= separating_area]
    geometric_data["dropletAndParticleDetection"] = {
        "maskSha256": region_records["DROPLETS_AND_PARTICLES"]["sha256"],
        "connectedComponents8": droplet_components,
        "classificationMethod": "largest adjacent gap in log component area; no manually set area cutoff",
        "areaSplitThresholdPx": round(float(separating_area), 3),
        "largeDropletCandidates": large_droplets,
        "smallParticleCandidates": small_particles,
        "count": {"largeDropletCandidates": len(large_droplets), "smallParticleComponents": len(small_particles), "allAnnotatedComponents": len(droplet_components)},
        "confidence": "HIGH_FOR_ANNOTATED_MASK_COMPONENTS; physical droplet identity is inferred from size clusters and visual glass highlights",
    }
    geometric_data["backgroundBokehDetection"] = {
        "maskSha256": region_records["BACKGROUND_BOKEH"]["sha256"],
        "connectedComponents8": component_summary(masks["BACKGROUND_BOKEH"]),
        "count": len(connected_components(masks["BACKGROUND_BOKEH"])),
        "confidence": "HIGH_FOR_FROZEN_BOKEH_ANNOTATIONS; not a count of every out-of-ROI background glow pixel",
    }

    # Add full inventory records for annotated droplets and bokeh components.
    for i, obj in enumerate(large_droplets, 1):
        inventory.append({"objectId": f"REFB-DROP-{i:02d}", "objectType": "LARGE_DROPLET_CANDIDATE", **obj, "evidenceClass": "OBSERVED_MASK_COMPONENT_WITH_INFERRED_DROPLET_ROLE", "confidence": "MEDIUM"})
    for i, obj in enumerate(small_particles, 1):
        inventory.append({"objectId": f"REFB-PARTICLE-{i:02d}", "objectType": "SMALL_PARTICLE_OR_SPECULAR_FRAGMENT", **obj, "evidenceClass": "OBSERVED_MASK_COMPONENT", "confidence": "MEDIUM"})
    bokeh_components = connected_components(masks["BACKGROUND_BOKEH"])
    for i, obj in enumerate(bokeh_components, 1):
        inventory.append({"objectId": f"REFB-BOKEH-{i:02d}", "objectType": "BACKGROUND_BOKEH_COMPONENT", **obj, "evidenceClass": "OBSERVED_FROZEN_MASK_COMPONENT", "confidence": "HIGH_FOR_MASK_COMPONENT"})

    region_specs = {item["id"]: item for item in frozen_regions}
    resident_contract = contract["technical_baseline"]["motion"]["resident_identity_and_motion"]
    identity_to_group = {
        "C01": {"candidateGroups": ["RESIDENT_FRONT_CYAN"], "mappingConfidence": "HIGH_GROUP_MATCH; exact surface decomposition remains unresolved"},
        "A02": {"candidateGroups": ["RESIDENT_FRONT_GOLD"], "mappingConfidence": "MEDIUM_GROUP_MATCH; two recorded path traces may be internal strands of one identity"},
        "G01": {"candidateGroups": ["RESIDENT_BACK_GREEN_GOLD"], "mappingConfidence": "LOW_IDENTITY_GEOMETRY; aggregate mask cannot isolate G01"},
        "G02": {"candidateGroups": ["RESIDENT_BACK_GREEN_GOLD"], "mappingConfidence": "LOW_IDENTITY_GEOMETRY; front green identity has no dedicated frozen mask"},
        "A01": {"candidateGroups": ["RESIDENT_BACK_GREEN_GOLD"], "mappingConfidence": "LOW_IDENTITY_GEOMETRY; back gold identity shares an aggregate green/gold mask"},
    }
    identities = []
    for record in resident_contract:
        item = {"identity": record["id"], "material": record["material"], "depthRole": record["depth_role"], "periodMs": record["period_ms"], **identity_to_group[record["id"]], "evidenceClass": "DECLARED_BY_FROZEN_P0_3_CONTRACT; still image does not reveal unique trajectory/period"}
        identities.append(item)
        inventory.append({"objectId": f"REFB-{record['id']}", "objectType": "FROZEN_RESIDENT_IDENTITY", "evidenceClass": item["evidenceClass"], "count": 1, "confidence": item["mappingConfidence"], "depthRole": item["depthRole"], "periodMs": item["periodMs"], "candidateGroups": item["candidateGroups"]})

    path_mask_by_group = {
        "RESIDENT_FRONT_CYAN": "C01", "RESIDENT_FRONT_GOLD": "A02", "RESIDENT_BACK_GREEN_GOLD": "AGGREGATE_BACK_GREEN_GOLD",
    }
    for trace in trace_paths:
        inventory.append({"objectId": trace["traceId"], "objectType": "VISIBLE_TRACE_SEGMENT", "evidenceClass": "OBSERVED_FROZEN_P0_3_CENTERLINE_ANNOTATION", "count": 1, "semanticGroup": trace["semanticGroup"], "annotatedWidthPx": trace["annotationWidthPx"], "measuredSignalWidth": trace["imageSignalWidth"], "traceLengthPx": trace["lengthPx"], "confidence": "HIGH_FOR_PIXEL_TRACE; 3D depth and grouping are inferred"})

    # Overlap values are exact pixel intersections of immutable annotation masks; front/back depth comes from the frozen contract and is inference.
    group_pairs = [("RESIDENT_FRONT_CYAN", "RESIDENT_BACK_GREEN_GOLD"), ("RESIDENT_FRONT_GOLD", "RESIDENT_BACK_GREEN_GOLD"), ("RESIDENT_FRONT_CYAN", "RESIDENT_FRONT_GOLD")]
    overlap_edges = []
    for left, right in group_pairs:
        overlap = masks[left] & masks[right]
        overlap_edges.append({
            "sourceObject": left, "targetObject": right, "overlapPixels": int(overlap.sum()),
            "overlapCentroidPx": centroid_xy(overlap), "projectedRelation": "MASK_OVERLAP" if overlap.any() else "NO_MASK_OVERLAP",
            "depthRelation": "INFERRED_FROM_FROZEN_DEPTH_ROLE_ONLY; aggregate ROI cannot attribute individual crossing order",
        })
    depth_graph = {
        "sourceImageIsSingleStaticRGB": True,
        "nodes": [
            {"id": "RESIDENT_FRONT_CYAN", "nodeType": "FROZEN_SEMANTIC_GROUP", "depth": "front-associated; group match to C01 is high confidence"},
            {"id": "RESIDENT_FRONT_GOLD", "nodeType": "FROZEN_SEMANTIC_GROUP", "depth": "front-associated; group match to A02 is medium confidence"},
            {"id": "RESIDENT_BACK_GREEN_GOLD", "nodeType": "AGGREGATE_FROZEN_SEMANTIC_GROUP", "depth": "mixed/ambiguous; P0-3 identity roles include both FRONT and BACK"},
            *[{"id": i["identity"], "nodeType": "CONTRACT_RESIDENT_IDENTITY", "depthRole": i["depthRole"], "confidence": i["mappingConfidence"]} for i in identities],
            *[{"id": f"OVERLAP-{index:02d}", "nodeType": "OBSERVED_MASK_OVERLAP_NODE", "pointPx": c["overlapCentroidPx"]} for index, c in enumerate(overlap_edges, 1) if c["overlapCentroidPx"] is not None],
        ],
        "edges": [
            *[{"from": edge["sourceObject"], "to": edge["targetObject"], "relation": "OBSERVED_PROJECTED_MASK_OVERLAP", "overlapPixels": edge["overlapPixels"], "depthOrder": "UNKNOWN_AT_GROUP_LEVEL"} for edge in overlap_edges if edge["overlapPixels"] > 0],
            *[{"from": back["identity"], "to": front["identity"], "relation": "INFERRED_CONTRACT_DEPTH_ORDER", "evidence": "P0-3 declared depth_role", "pixelIntersectionAtIdentityLevel": "UNKNOWN"} for back in identities if back["depthRole"] == "BACK" for front in identities if front["depthRole"] == "FRONT"],
        ],
        "observed": {"projectedMaskOverlaps": overlap_edges, "annotatedTraceSegments": [p["traceId"] for p in trace_paths]},
        "inferred": {"residentIdentityDepthRoles": [{"id": i["identity"], "role": i["depthRole"], "source": "P0-3 technical_baseline.motion.resident_identity_and_motion"} for i in identities]},
        "uncertain": [
            "The static PNG cannot uniquely recover metric Z, surface thickness, refraction path, or which aggregate mask pixels belong to G01 versus G02 versus A01.",
            "A projected overlap does not itself prove which surface is in front; the front/back roles are taken from the frozen contract, not inferred from RGB alone.",
            "Flow direction, phase, deformation, and temporal speed cannot be measured from a single still; periods and core rotation are contract inputs only.",
        ],
    }

    geometric_data["majorTrackIdentities"] = identities
    geometric_data["primaryTraceCounts"] = {
        "frozenResidentIdentityCount": len(identities), "frozenResidentSemanticGroupCount": 3,
        "frozenPixelTraceSegmentCount": len(trace_paths),
        "byGroup": dict(Counter(p["semanticGroup"] for p in trace_paths)),
        "countInterpretation": "five identity names are frozen in P0-3; seven traced polyline pieces are annotations, not seven independent physical tracks",
    }
    intersections = projected_trace_intersections(trace_paths)
    geometric_data["branchGraph"] = {
        "nodes": intersections,
        "nodeCount": len(intersections),
        "edges": [{"traceId": trace["traceId"], "fromPointPx": trace["pointsPx"][0], "toPointPx": trace["pointsPx"][-1], "geometryRef": trace["traceId"]} for trace in trace_paths],
        "interpretation": "The graph records 2D polyline crossings from the frozen annotations. A crossing is not automatically a branch, merge, or 3D contact.",
    }
    geometric_data["fieldDensity"] = {
        "definition": "per-pixel 0.55*normalized(abs(linear-luma Gaussian sigma=9)) + 0.45*normalized(luma gradient), evaluated only in the union of 3 frozen resident masks and droplet mask, then Gaussian-smoothed sigma=12",
        "structureMaskCoveragePx": int(detected_pixels.sum()),
        "structureMaskCoverageRatio": round(float(detected_pixels.mean()), 8),
        "meanGradientInResidentMasks": round(float(gradient[resident_union].mean()), 8),
        "p90GradientInResidentMasks": round(float(np.percentile(gradient[resident_union], 90)), 8),
        "meanLocalContrastInResidentMasks": round(float(local_contrast[resident_union].mean()), 8),
    }

    color_stats = {name: color_data["regions"][name] for name in group_names}
    color_data["overallResidentColorHistogram"] = {
        "pixelCount": int(resident_union.sum()),
        "hueHistogram36Bins": mask_color_metrics(rgb, linear, hsv, resident_union)["hueHistogram36Bins"],
        "linearLuminance": pct((linear @ np.array([0.2126, 0.7152, 0.0722], dtype=np.float32))[resident_union], (10, 25, 50, 75, 90, 95)),
        "descriptiveBands": "non-exclusive hue intervals listed per frozen semantic group; all thresholds are descriptive analytics, not replacement masks",
    }
    draw_color_chart(color_stats, OUTPUT / "REF_B_COLOR_DISTRIBUTION.png")
    make_overlays(rgb_image, masks, trace_paths)

    # Source verification artifact, including the archive fact that derived masks are held in the repository manifest.
    ref_record = {r["id"]: r for r in contract["source_of_truth"]["reference_images"]}
    source_verify = {
        "schema": "REF_B_SOURCE_VERIFICATION_1_0",
        "result": "PASS",
        "refB": {"path": str(REFERENCE_PATH.relative_to(ROOT)), "sha256": sha_bytes(ref_bytes), "expectedSha256": EXPECTED_REF_B, "width": image.width, "height": image.height, "mode": image.mode, "pngInfoKeys": sorted(image.info.keys()), "iccProfilePresent": bool(image.info.get("icc_profile")), "gammaChunk": image.info.get("gamma"), "pixelArraySHA256": sha_bytes(rgb.tobytes()), "contractMetadata": ref_record["REF_B"]},
        "p0_3Contract": {"path": str(CONTRACT_PATH.relative_to(ROOT)), "sha256": sha_bytes(p0_contract_bytes), "expectedSha256": EXPECTED_CONTRACT, "status": contract["status"]},
        "uploadedContractArchive": {"path": str(ZIP_PATH), "sha256": sha_bytes(archive_bytes), "expectedSha256": EXPECTED_ZIP, "containsContractAndREF_B": True, "containsDerivedMaskCount": sum(n.startswith("roi_masks/") for n in archive_names), "note": "archive carries the frozen source contract, both reference PNGs, and REF_A alpha seed; all 12 derived masks are sourced from the checked-in frozen ROI manifest and validated by its exact hashes"},
        "maskFreeze": {"manifestSha256": sha_file(MASK_MANIFEST_PATH), "contractSha256": manifest["contractSha256"], "referenceSha256": manifest["referenceSha256"], "regionCount": len(required_regions), "maskCount": len(required_masks), "allMasksMatchFrozenManifest": True, "overlapPixels": manifest["selfCheck"]["materialOverlapPixels"]},
        "immutableInputs": [str(REFERENCE_PATH.relative_to(ROOT)), str(CONTRACT_PATH.relative_to(ROOT)), "docs/p0_3/ROI_MASK_FREEZE_MANIFEST.json", "docs/p0_3/roi_masks/*.png"],
    }
    json_dump(OUTPUT / "REF_B_SOURCE_VERIFICATION.json", source_verify)

    structure_inventory = {
        "schema": "REF_B_STRUCTURE_INVENTORY_1_0",
        "sourceRefBSha256": EXPECTED_REF_B,
        "contractSha256": EXPECTED_CONTRACT,
        "coordinateSystem": geometric_data["coordinateSystem"],
        "identityAndSegmentationCaveat": "Do not equate frozen identity count, centerline segment count, connected-component count, or 3D object count. All refer to different evidence layers.",
        "counts": {"contractResidentIdentities": 5, "frozenResidentGroups": 3, "frozenTraceSegments": len(trace_paths), "largeDropletCandidates": len(large_droplets), "smallParticleComponents": len(small_particles), "bokehMaskComponents": len(bokeh_components)},
        "objects": inventory,
    }
    json_dump(OUTPUT / "REF_B_STRUCTURE_INVENTORY.json", structure_inventory)
    with (OUTPUT / "REF_B_STRUCTURE_INVENTORY.csv").open("w", newline="", encoding="utf-8") as f:
        fields = ["objectId", "objectType", "evidenceClass", "count", "semanticGroup", "areaPx", "bboxXYWH", "centroidPx", "confidence", "depthRole", "periodMs", "candidateGroups"]
        writer = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        for row in inventory:
            writer.writerow({k: json.dumps(row.get(k), ensure_ascii=False) if isinstance(row.get(k), (list, dict)) else row.get(k) for k in fields})

    json_dump(OUTPUT / "REF_B_GEOMETRY_DATA.json", geometric_data)
    json_dump(OUTPUT / "REF_B_COLOR_MATERIAL_DATA.json", color_data)
    json_dump(OUTPUT / "REF_B_DEPTH_OCCLUSION_GRAPH.json", depth_graph)
    outer_map = {
        "schema": "REF_B_OUTER_OPTICAL_STRUCTURE_MAP_1_0",
        "sourceRefBSha256": EXPECTED_REF_B,
        "canvas": {"width": 1254, "height": 1254, "origin": "top_left", "normalizedRange": "[0,1] per axis"},
        "coreComposition": {"source": "frozen P0-3 camera_and_composition target", "targetDiameterRatio": contract["technical_baseline"]["camera_and_composition"]["full_scene_target_sphere_diameter_ratio"], "targetDiameterPx": round(contract["technical_baseline"]["camera_and_composition"]["full_scene_target_sphere_diameter_ratio"] * 1254, 3), "evidenceClass": "CONTRACT_TARGET; not re-measured by an independent core segmentation mask"},
        "regions": {name: geometric_data["regions"][name] for name in group_names},
        "traceSegments": trace_paths,
        "branchGraph": geometric_data["branchGraph"],
        "dropletsAndParticles": geometric_data["dropletAndParticleDetection"],
        "backgroundBokeh": geometric_data["backgroundBokehDetection"],
        "densityMap": {"file": "REF_B_VISUAL_DENSITY_MAP.png", "method": geometric_data["fieldDensity"]["definition"]},
    }
    json_dump(OUTPUT / "REF_B_OUTER_OPTICAL_STRUCTURE_MAP.json", outer_map)

    # Extra contract is a value snapshot from the reference, not new user thresholds.
    outer_contract = {
        "schema": "OUTER_OPTICAL_FIELD_CONTRACT_V1",
        "version": "1.0.0",
        "status": "P0_A_EXTRACTED_PENDING_INDEPENDENT_VALIDATION",
        "source": {"refB": {"sha256": EXPECTED_REF_B, "width": 1254, "height": 1254, "mode": "RGB", "colorSpace": "sRGB"}, "p0_3ContractSha256": EXPECTED_CONTRACT, "roiMaskFreezeManifestSha256": sha_file(MASK_MANIFEST_PATH)},
        "frozenRegionReferences": [{"id": r["id"], "reference": r["reference"], "maskFile": r.get("mask_file"), "maskSha256": region_records[r["id"]]["sha256"] if r.get("mask_file") else None} for r in frozen_regions],
        "measuredTargets": {"geometry": geometric_data, "colorMaterial": color_data, "depthOcclusion": depth_graph},
        "structures": [{"id": row["objectId"], "type": row["objectType"], "evidenceClass": row["evidenceClass"], "geometryRef": row.get("objectId"), "confidence": row.get("confidence", "SEE_GEOMETRY_DATA")} for row in inventory],
        "dynamicProperties": {"source": "frozen P0-3 motion contract plus Tab 1 runtime behavior; not inferable from REF_B alone", "frozenResidentIdentities": identities, "coreRotation": contract["technical_baseline"]["motion"]["core_rotation"], "singleImageMotionLimits": depth_graph["uncertain"][-1]},
        "acceptance": {"sourceP0_3ThresholdsRemainAuthoritative": True, "outerMetricTargetsAreReferenceValues": True, "outerMetricsReference": "measuredTargets.geometry and measuredTargets.colorMaterial", "noNewArbitraryThresholds": True, "comparisonMustUseSameViewportAndNoRegistration": True, "temporalMetricsRequireMultiFrameCapture": True},
        "provenance": {"extractor": "tools/orb-tab3-refb/extract_ref_b_structure.py", "version": "1.0.0", "implementation": "Pillow + NumPy + SciPy; no OpenCV model inference", "traceSource": trace_source, "generatedFromOriginalPixels": True},
    }
    json_dump(OUTPUT / "OUTER_OPTICAL_FIELD_CONTRACT_V1.json", outer_contract)

    schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "OUTER_OPTICAL_FIELD_CONTRACT_V1.schema.json",
        "title": "REF_B Outer Optical Field Contract V1",
        "type": "object",
        "required": ["schema", "version", "status", "source", "frozenRegionReferences", "measuredTargets", "structures", "dynamicProperties", "acceptance", "provenance"],
        "properties": {
            "schema": {"const": "OUTER_OPTICAL_FIELD_CONTRACT_V1"}, "version": {"const": "1.0.0"},
            "status": {"enum": ["P0_A_EXTRACTED_PENDING_INDEPENDENT_VALIDATION", "P0_A_PASS"]},
            "source": {"type": "object", "required": ["refB", "p0_3ContractSha256", "roiMaskFreezeManifestSha256"]},
            "frozenRegionReferences": {"type": "array", "minItems": 13, "maxItems": 13},
            "measuredTargets": {"type": "object", "required": ["geometry", "colorMaterial", "depthOcclusion"]},
            "structures": {"type": "array", "minItems": 1},
            "dynamicProperties": {"type": "object"}, "acceptance": {"type": "object"}, "provenance": {"type": "object"},
            "independentValidation": {"type": "object", "required": ["report", "status", "schemaSha256"]},
        },
        "additionalProperties": False,
    }
    json_dump(OUTPUT / "OUTER_OPTICAL_FIELD_CONTRACT_V1.schema.json", schema)

    report = f"""# REF_B 全量结构提取报告\n\n## 输入锁定\n\n- REF_B：1254×1254 RGB，SHA256 `{EXPECTED_REF_B}`，原图按原始字节读取，未改写。\n- P0-3 合同 SHA256：`{EXPECTED_CONTRACT}`；合同状态 `{contract['status']}`。\n- 附件 ZIP SHA256：`{EXPECTED_ZIP}`。附件 ZIP 含原始合同与 REF_B；完整 12 张派生 Mask 由仓库的冻结 Manifest 提供并逐张核验。\n- P0-3 ROI：13 项，Mask：12 张；原始阈值与 Mask 均未修改。\n\n## 提取方法与证据等级\n\n本次将冻结 Mask 作为语义锚点；同时从原始 RGB/sRGB 解码得到线性亮度、色度、局部对比度、边缘梯度、连通分量和横截面色度宽度。所有附加统计均为可重复的检测结果，不覆盖原 Mask。中心线点由冻结 P0-3 Mask 生成器的 Python AST 字面量读取，未重新绘制或重拟合。\n\n- `OBSERVED`：像素位置、Mask 交叠、冻结中心线、颜色/亮度/梯度统计、Mask 内连通区域。\n- `INFERRED`：P0-3 合同的五个 Resident 身份、材质身份和前后角色。\n- `UNCERTAIN`：三维深度距离、实际透明曲面边界、G01/G02/A01 在聚合 Mask 内的精确分离、单帧动态方向与速度。\n\n## 结构盘点\n\n- 冻结合同声明 5 个 Resident 身份、3 个外围 Resident 语义 Mask。\n- 冻结的像素路径标注由 {len(trace_paths)} 个折线段组成：按组 `{json.dumps(geometric_data['primaryTraceCounts']['byGroup'], ensure_ascii=False)}`。这些是路径段，不能当作 {len(trace_paths)} 个独立三维对象。\n- Droplets/Particles 冻结 Mask 内，按最大相邻 log-area 间距自动分成 {len(large_droplets)} 个大水滴候选与 {len(small_particles)} 个细粒/高光片段。\n- Background Bokeh 冻结 Mask 含 {len(bokeh_components)} 个连通注释区域。\n- 主体曲面、内部光丝和高光节点数量另以 Otsu 色度组件、梯度与亮度分布给出；连通片数会受遮挡、相交和算法阈值影响，不冒充物理对象数量。\n\n## 数据与审查\n\n- 几何、宽度、曲率、归一化边界：`REF_B_GEOMETRY_DATA.json`。\n- 色彩、亮度、饱和度、色相分布：`REF_B_COLOR_MATERIAL_DATA.json`。\n- 投影重叠与推断深度关系：`REF_B_DEPTH_OCCLUSION_GRAPH.json`。\n- 结构总图/密度/色彩/图集：`REF_B_OUTER_OPTICAL_STRUCTURE_MAP.json`、`REF_B_VISUAL_DENSITY_MAP.png`、`REF_B_COLOR_DISTRIBUTION.png`、`REF_B_GEOMETRY_OVERLAY.png`、`REF_B_STRUCTURE_ATLAS.png`。\n- 新合同中的目标值直接引用 REF_B 测量数据；没有新增任意视觉阈值，P0-3 原阈值仍保持权威。\n\n独立校验脚本将核对输入 SHA、12 张 Mask、13 区映射、Schema、所有可视化画布尺寸、坐标范围和合同字节一致性。静态图无法证明唯一 3D 深度或运动真值，这些字段已明确标为推断或不确定。\n"""
    (OUTPUT / "REF_B_EXTRACTION_REPORT.md").write_text(report, encoding="utf-8")
    with (OUTPUT / "REF_B_EXTRACTION_REPORT.md").open("a", encoding="utf-8") as report_file:
        report_file.write(
            f"\n### 补充的对象级检测\n\n"
            f"- Otsu 色度组件、局部梯度/高频边缘碎片及线性亮度 P99.5 局部高光候选按三组 Resident ROI 分别记录；像素位置、面积、主方向、轴向尺度和平均 RGB 在 Geometry Data 中。碎片计数不等同于物理光丝/光源数量。\n"
            f"- 七个冻结折线段中，找到 {geometric_data['branchGraph']['nodeCount']} 个二维交叉候选；它们不证明真实三维分支或遮挡顺序。\n"
            f"- 横截面信号宽度取自原图线性化色度分数，沿冻结中心线法线采样，并被原冻结语义 ROI 限定；完整透明表面边界仍不可由单帧 RGB 唯一测得。\n"
        )


if __name__ == "__main__":
    main()
