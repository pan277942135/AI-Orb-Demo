#!/usr/bin/env python3
"""Create source-bound V2.1 object masks with explicit uncertainty.

The only image input is the frozen master. Object masks use documented ROI,
polygon and foreground seeds with OpenCV GrabCut. Resident masks are measured
from the current source traces and remain unresolved where direct evidence is
missing; this tool never fills those gaps with a guessed path.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

MASTER_SHA = "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589"
SIZE = (1254, 1254)

# Pixel coordinates are hand-registered seed geometry in the immutable source.
# GrabCut refines the boundary; polygons are priors and never reported as the
# final annotation by themselves.
OBJECTS = {
    "WW_FISH_01": {
        "kind": "fish", "roi": [668, 448, 880, 547],
        "polygon": [[676,500],[681,487],[701,484],[720,490],[742,480],[758,474],[764,459],[780,462],[797,476],[821,475],[842,467],[861,472],[873,482],[869,490],[850,496],[837,508],[818,515],[804,525],[790,518],[781,505],[756,518],[741,514],[734,502],[714,506],[690,514]],
        "fg_seeds": [[690,499],[733,495],[765,464],[790,493],[803,516],[833,488]], "confidence": "MEDIUM_HIGH",
        "occlusion": "No discrete foreground occluder crosses the fish silhouette at the binary edge threshold.",
    },
    "WW_FISH_02": {
        "kind": "fish", "roi": [483, 516, 660, 587],
        "polygon": [[494,551],[499,539],[512,531],[526,536],[547,528],[566,527],[577,520],[589,525],[601,533],[624,531],[639,537],[649,546],[647,554],[634,560],[624,569],[609,578],[596,574],[587,565],[565,574],[550,574],[540,581],[530,570],[514,569],[502,574]],
        "fg_seeds": [[510,552],[558,549],[574,526],[598,548],[610,573],[628,548]], "confidence": "MEDIUM_HIGH",
        "occlusion": "No discrete foreground occluder crosses the fish silhouette at the binary edge threshold.",
    },
    "WW_FISH_03": {
        "kind": "fish", "roi": [610, 555, 758, 621],
        "polygon": [[617,591],[624,581],[638,578],[651,581],[673,571],[690,568],[698,560],[711,562],[723,570],[740,575],[751,582],[751,590],[740,596],[729,600],[720,609],[703,614],[687,608],[676,615],[664,608],[657,597],[643,599],[628,604]],
        "fg_seeds": [[624,591],[675,587],[700,563],[709,586],[715,605],[735,585]], "confidence": "MEDIUM",
        "occlusion": "Fish 02 is near the upper-left ROI edge; overlap is excluded by the registered silhouette prior and remains a review flag.",
    },
    "WW_FISH_04": {
        "kind": "fish", "roi": [529, 630, 670, 698],
        "polygon": [[535,666],[539,655],[551,648],[562,653],[580,645],[601,641],[616,638],[629,644],[641,643],[655,650],[663,657],[659,665],[648,671],[637,681],[618,687],[603,682],[592,690],[579,682],[569,671],[556,677],[542,674]],
        "fg_seeds": [[543,665],[584,660],[624,645],[615,680],[643,658]], "confidence": "MEDIUM",
        "occlusion": "Plant and lower water detail approach the tail; the outer edge is tagged as ambiguous.",
    },
    "plant_left": {
        "kind": "plant", "roi": [220, 410, 493, 1000],
        "polygon": [[236,992],[238,846],[231,735],[238,622],[251,540],[275,470],[310,427],[333,436],[337,508],[354,483],[390,448],[426,420],[454,445],[475,492],[489,574],[478,632],[463,705],[466,792],[447,883],[419,956],[385,985],[320,1000]],
        "fg_seeds": [[306,600],[345,660],[384,570],[418,518],[352,821]], "confidence": "LOW",
        "occlusion": "Glass arcs, cyan Resident strands, and rock edges cross foliage; hidden leaf contours are not inferred.",
    },
    "plant_right": {
        "kind": "plant", "roi": [819, 510, 993, 895],
        "polygon": [[842,874],[830,819],[843,759],[844,680],[850,604],[862,545],[879,525],[895,566],[910,615],[924,570],[945,531],[958,537],[963,600],[976,647],[981,711],[973,771],[963,824],[944,863],[906,887],[871,887]],
        "fg_seeds": [[861,696],[900,683],[941,640],[919,783],[876,820]], "confidence": "MEDIUM_LOW",
        "occlusion": "Rock, glass rim and caustic highlights overlap the lower stems; those pixels remain ambiguous.",
    },
    "rock_01": {
        "kind": "rock", "roi": [348, 700, 647, 858],
        "polygon": [[359,779],[364,750],[386,727],[416,718],[447,721],[477,713],[511,718],[543,731],[570,746],[596,765],[614,789],[608,811],[585,829],[550,842],[516,846],[478,840],[448,832],[415,824],[386,808],[368,796]],
        "fg_seeds": [[425,756],[480,765],[536,770]], "confidence": "LOW",
        "occlusion": "Plant leaves and the foreground cracked rock overlap this back rock; complete hidden geometry cannot be recovered.",
    },
    "rock_02": {
        "kind": "rock", "roi": [724, 705, 891, 853],
        "polygon": [[735,780],[740,751],[755,731],[781,716],[809,710],[838,716],[861,735],[878,760],[885,787],[872,811],[850,830],[822,843],[790,842],[761,829],[742,809]],
        "fg_seeds": [[785,764],[820,773],[849,777]], "confidence": "MEDIUM_LOW",
        "occlusion": "Plant stems and attached moss overlap the stone boundary; rock-to-moss separation is not fully identifiable.",
    },
    "rock_03": {
        "kind": "rock", "roi": [470, 786, 770, 927],
        "polygon": [[479,849],[488,827],[507,808],[533,797],[559,792],[586,797],[610,807],[635,802],[662,793],[690,794],[716,801],[738,819],[748,841],[741,863],[721,883],[694,900],[664,912],[633,918],[600,911],[570,899],[539,886],[511,871],[491,860]],
        "fg_seeds": [[531,843],[574,835],[632,846],[686,836]], "confidence": "LOW",
        "occlusion": "Moss, lighting and rock 01 overlap the silhouette; the master does not expose a complete unoccluded outline.",
    },
}

RESIDENTS = {
    "G01": ((45, 125), [147, 80, 1144, 730]),
    "G02": ((10, 70), [198, 145, 1230, 775]),
    "A01": ((135, 225), [20, 425, 935, 1170]),
    "A02": ((135, 225), [205, 710, 1060, 1160]),
    "C01": ((10, 70), [155, 745, 1230, 1208]),
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def mask_bbox(mask: np.ndarray) -> list[int]:
    y, x = np.where(mask)
    if not len(x):
        return [0, 0, 0, 0]
    return [int(x.min()), int(y.min()), int(x.max()) + 1, int(y.max()) + 1]


def polygon_mask(shape: tuple[int, int], polygon: list[list[int]]) -> np.ndarray:
    out = np.zeros(shape, np.uint8)
    cv2.fillPoly(out, [np.asarray(polygon, np.int32)], 1)
    return out.astype(bool)


def grabcut_object(rgb: np.ndarray, spec: dict) -> tuple[np.ndarray, np.ndarray, dict]:
    x0, y0, x1, y1 = spec["roi"]
    crop = rgb[y0:y1, x0:x1].copy()
    h, w = crop.shape[:2]
    kind = spec["kind"]
    poly = [[x - x0, y - y0] for x, y in spec["polygon"]]
    polygon_prior = polygon_mask((h, w), poly)
    if kind == "fish":
        # The fish silhouettes are compact and distinct. A tight seeded ROI
        # preserves transparent fins that a manually drawn filled polygon
        # would truncate; the 1-pixel ROI border supplies definite background.
        support = np.ones((h, w), bool)
        support[[0, -1], :] = False
        support[:, [0, -1]] = False
    else:
        support = polygon_prior
    hsv = cv2.cvtColor(crop, cv2.COLOR_RGB2HSV)
    # OpenCV HSV H is 0..179. Color priors help GrabCut find the named object,
    # while the independent polygon limits cross-object contamination.
    hh, ss, vv = hsv[:, :, 0], hsv[:, :, 1], hsv[:, :, 2]
    if kind == "fish":
        cue = (ss > 34) & (vv > 54)
    elif kind == "plant":
        cue = ((hh >= 24) & (hh <= 92) & (ss > 42) & (vv > 30)) | ((hh >= 92) & (hh <= 112) & (ss > 60) & (vv > 25))
    else:
        luma = 0.2126 * crop[:, :, 0] + 0.7152 * crop[:, :, 1] + 0.0722 * crop[:, :, 2]
        cue = (luma < 190) & (vv > 18) & (ss > 12)
    labels = np.full((h, w), cv2.GC_BGD, np.uint8)
    labels[support] = cv2.GC_PR_FGD
    # Color is supporting evidence, not certainty: glass, caustics and moss
    # can share the same hue as the named object.
    labels[support & cue] = cv2.GC_PR_FGD
    # Keep hand-placed seed neighborhoods as definite foreground even if color
    # is neutral (e.g. translucent fins or low-saturation foliage).
    for gx, gy in spec["fg_seeds"]:
        sx, sy = int(gx - x0), int(gy - y0)
        cv2.circle(labels, (sx, sy), 3 if kind == "fish" else 4, cv2.GC_FGD, -1)
    if not np.any(labels == cv2.GC_BGD):
        labels[~support] = cv2.GC_BGD
    bg_model = np.zeros((1, 65), np.float64)
    fg_model = np.zeros((1, 65), np.float64)
    cv2.grabCut(crop[:, :, ::-1].copy(), labels, None, bg_model, fg_model, 8, cv2.GC_INIT_WITH_MASK)
    local = (labels == cv2.GC_FGD) | (labels == cv2.GC_PR_FGD)
    # Restrict the final support to the reviewed source ROI prior. GrabCut can
    # classify bubbles, caustics or neighboring objects as foreground.
    local &= support
    if spec.get("exclude_polygons"):
        for exclusion in spec["exclude_polygons"]:
            excluded = np.zeros((h, w), np.uint8)
            cv2.fillPoly(excluded, [np.asarray([[x - x0, y - y0] for x, y in exclusion], np.int32)], 1)
            local[excluded > 0] = False
    # Remove tiny detached noise but retain all connected fins and leaves.
    n, lab, stats, _ = cv2.connectedComponentsWithStats(local.astype(np.uint8), 8)
    if n > 1:
        keep = np.zeros_like(local)
        min_area = 4 if kind == "fish" else 8
        for k in range(1, n):
            if stats[k, cv2.CC_STAT_AREA] >= min_area:
                keep |= lab == k
        local = keep
    full = np.zeros(rgb.shape[:2], bool)
    full[y0:y1, x0:x1] = local
    # Confidence is 0 outside; inside it reflects GrabCut's definite vs
    # probable class and is lowered at the contour where optical overlaps are.
    conf = np.zeros(rgb.shape[:2], np.uint8)
    definite = local & (labels == cv2.GC_FGD)
    probable = local & ~definite
    conf[y0:y1, x0:x1][definite] = 232
    conf[y0:y1, x0:x1][probable] = 176
    edge = cv2.morphologyEx(local.astype(np.uint8), cv2.MORPH_GRADIENT, np.ones((3, 3), np.uint8)) > 0
    edge_full = np.zeros_like(full)
    edge_full[y0:y1, x0:x1] = edge
    conf[cv2.dilate(edge_full.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0] = 96
    # User-visible review overlay; red = candidate boundary, yellow = ROI.
    overlay = rgb.copy()
    contours, _ = cv2.findContours(local.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cv2.drawContours(overlay[y0:y1, x0:x1], contours, -1, (255, 50, 50), 1)
    cv2.rectangle(overlay, (x0, y0), (x1 - 1, y1 - 1), (255, 220, 20), 1)
    details = {
        "method": "OpenCV GrabCut (8 iterations), hand-registered ROI/polygon priors and foreground seeds; no learned model",
        "opencv_version": cv2.__version__,
        "roi_px_xyxy": spec["roi"],
        "seed_polygon_global_px": spec["polygon"],
        "sure_foreground_seed_centers_px": spec["fg_seeds"],
        "color_cue": "fish: saturation/value; plant: green hue/saturation/value; rock: dark textured RGB/value prior",
        "master_confidence_label": spec["confidence"],
        "uncertainty": spec["occlusion"],
        "source_visible_area_px": int(full.sum()),
        "source_visible_bbox_px": mask_bbox(full),
        "complete_unoccluded_object_boundary": kind == "fish" and spec["confidence"] in {"HIGH", "MEDIUM_HIGH"},
        "segmentation_confidence_status": "LOW_CONFIDENCE_REQUIRES_REVIEW" if spec["confidence"] == "LOW" else "MEDIUM_CONFIDENCE_REQUIRES_INDEPENDENT_REVIEW",
    }
    return full, conf, {"details": details, "overlay": overlay}


def resident_source_masks(rgb: np.ndarray, package: Path) -> dict[str, dict]:
    geometry_path = package / "01_ANALYSIS/master_resident_geometry.json"
    data = json.loads(geometry_path.read_text(encoding="utf-8")) if geometry_path.is_file() else {"residents": []}
    rows = {r["id"]: r for r in data.get("residents", [])}
    result = {}
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    h = hsv[:, :, 0].astype(float) * 2.0
    s = hsv[:, :, 1].astype(float) / 255.0
    v = hsv[:, :, 2].astype(float) / 255.0
    yy, xx = np.indices(rgb.shape[:2])
    for rid, (hue, roi) in RESIDENTS.items():
        row = rows.get(rid)
        direct_mask = np.zeros(rgb.shape[:2], bool)
        confidences = []
        width_samples = []
        class_mask = (h >= hue[0]) & (h <= hue[1]) & (s > 0.12) & (v > 0.18)
        traces = []
        sample_n = 0
        matched = 0
        if row:
            for segment in row.get("segments", []):
                points = np.asarray(segment.get("polyline_px", []), dtype=float)
                if not len(points):
                    continue
                # Retain samples only if a source-colored ribbon pixel is close
                # to the source curve. No missing samples are linearly filled.
                traced, valid, dvals = [], [], []
                for sample_index, (px, py) in enumerate(points):
                    x0, y0 = int(round(px)), int(round(py))
                    xa, xb = max(0, x0 - 16), min(rgb.shape[1], x0 + 17)
                    ya, yb = max(0, y0 - 16), min(rgb.shape[0], y0 + 17)
                    local_h, local_s, local_v = h[ya:yb, xa:xb], s[ya:yb, xa:xb], v[ya:yb, xa:xb]
                    if hue[0] <= hue[1]:
                        candidate = (local_h >= hue[0]) & (local_h <= hue[1])
                    else:
                        candidate = (local_h >= hue[0]) | (local_h <= hue[1])
                    candidate &= (local_s > 0.12) & (local_v > 0.18)
                    cy, cx = np.where(candidate)
                    sample_n += 1
                    if not len(cx):
                        traced.append(None); valid.append(False); dvals.append(None); continue
                    gx, gy = cx + xa, cy + ya
                    dist = np.hypot(gx - px, gy - py)
                    k = int(np.argmin(dist))
                    d = float(dist[k])
                    ok = d <= 8.0
                    if ok:
                        direct_mask[gy[k], gx[k]] = True
                        matched += 1
                        confidences.append(float(max(0.0, 1.0 - d / 8.0) * local_s[cy[k], cx[k]]))
                        tangent = points[min(sample_index + 1, len(points) - 1)] - points[max(sample_index - 1, 0)]
                        tangent_norm = float(np.linalg.norm(tangent))
                        if tangent_norm > 1e-6:
                            normal = np.array([-tangent[1], tangent[0]], dtype=float) / tangent_norm
                            left_extent = right_extent = 0.0
                            for direction, target in ((-1.0, "left"), (1.0, "right")):
                                extent = 0.0
                                for offset in np.arange(0.5, 25.1, 0.5):
                                    sx = int(round(gx[k] + normal[0] * offset * direction))
                                    sy = int(round(gy[k] + normal[1] * offset * direction))
                                    if sx < 0 or sx >= rgb.shape[1] or sy < 0 or sy >= rgb.shape[0] or not class_mask[sy, sx]:
                                        break
                                    extent = float(offset)
                                if target == "left": left_extent = extent
                                else: right_extent = extent
                            width = left_extent + right_extent + 1.0
                            if width > 1.0:
                                width_samples.append(width)
                        traced.append([int(gx[k]), int(gy[k])])
                    else:
                        traced.append(None)
                    valid.append(ok); dvals.append(d)
                # Connect only neighboring directly observed samples. A gap is
                # never bridged; this merely rasterizes sub-pixel spacing of
                # two adjacent measurements.
                layer = np.zeros(rgb.shape[:2], np.uint8)
                for index in range(len(traced) - 1):
                    if valid[index] and valid[index + 1]:
                        p0, p1 = traced[index], traced[index + 1]
                        if math.dist(p0, p1) <= 16.0:
                            cv2.line(layer, tuple(p0), tuple(p1), 1, 1, cv2.LINE_8)
                direct_mask |= layer > 0
                traces.append({"polyline_px": traced, "direct_source_sample_valid": valid, "nearest_chroma_distance_px": dvals})
        result[rid] = {
            "mask": direct_mask,
            "roi": roi,
            "details": {
                "method": "direct source sampling within 8 px of the preceding source-trace polyline; HSV hue/value/saturation; no gap interpolation",
                "hue_deg": list(hue), "minimum_saturation": 0.12, "minimum_value": 0.18,
                "direct_trace_coverage": matched / sample_n if sample_n else 0.0,
                "sample_count": sample_n, "direct_source_matches": matched,
                "confidence_mean": float(np.mean(confidences)) if confidences else 0.0,
                "source_width_sample_count": len(width_samples),
                "source_measured_median_width_px": float(np.median(width_samples)) if width_samples else None,
                "source_width_profile_px": [float(x) for x in width_samples],
                "resident_width_measurement_status": "UNRESOLVED_IDENTITY_AND_OPTICAL_EDGE_AMBIGUITY",
                "resident_width_measurement_limit": "HSV cross-sections are not identity-isolated; hue crossings, highlights and nearby strands can inflate or clip widths. This profile is exploratory and must not be used for width PASS/FAIL.",
                "status": "PASS" if sample_n and matched / sample_n >= 0.9 else "BLOCKED_GROUND_TRUTH_UNCERTAIN",
                "unmatched_samples_are_interpolated": False,
                "identity_limit": "G02/C01 gold identity and A01/A02 cyan identity remain ambiguous at same-color crossings.",
                "depth_limit": "A single static composite cannot determine whether untraced path pixels are occluded or missed by the trace.",
                "confidence": "LOW" if not sample_n or matched / sample_n < 0.6 else "MEDIUM",
            },
            "traces": traces,
        }
    return result


def save_l(path: Path, mask: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(np.asarray(mask, dtype=np.uint8) * 255, "L").save(path, optimize=True)


def export_object(package: Path, master: Image.Image, rgb: np.ndarray, object_id: str,
                  full: np.ndarray, confidence: np.ndarray, roi: list[int], details: dict,
                  overlay: np.ndarray | None = None, traces: list | None = None) -> dict:
    root = package / "01_ANALYSIS/ground_truth/objects" / object_id
    root.mkdir(parents=True, exist_ok=True)
    visible = full.copy()
    occlusion = np.zeros_like(full, dtype=np.uint8)
    save_l(root / "source_full_mask.png", full)
    save_l(root / "source_visible_mask.png", visible)
    Image.fromarray(occlusion, "L").save(root / "occlusion_mask.png", optimize=True)
    Image.fromarray(confidence.astype(np.uint8), "L").save(root / "confidence_mask.png", optimize=True)
    if overlay is None:
        overlay = rgb.copy()
        ys, xs = np.where(full)
        if len(xs):
            overlay[ys, xs] = (0.68 * overlay[ys, xs] + 0.32 * np.array([255, 50, 50])).astype(np.uint8)
    Image.fromarray(overlay, "RGB").save(root / "boundary_overlay.png", optimize=True)
    report = {
        "schema_version": "water-world-v2-1-ground-truth-object/1.0",
        "object_id": object_id, "master_sha256": MASTER_SHA,
        "coordinate_space": "1254x1254 source pixel coordinates; origin top-left; x-right/y-down",
        "source_roi": {"x0": roi[0], "y0": roi[1], "x1_exclusive": roi[2], "y1_exclusive": roi[3]},
        "visible_bbox_px": mask_bbox(visible), "visible_area_px": int(visible.sum()),
        "full_mask_complete": bool(details.get("complete_unoccluded_object_boundary", False)),
        "occlusion_mask_status": "NO_CONFIRMED_OCCLUDED_OBJECT_PIXELS; UNKNOWN_REGIONS_REMAIN_UNKNOWN",
        "full_vs_visible_note": "Full mask includes only directly observed source pixels. Unseen/occluded continuation is not fabricated.",
        "metrics_use_visible_mask": True,
        **details,
    }
    write_json(root / "source_roi.json", report["source_roi"] | {"master_sha256": MASTER_SHA, "method": details.get("method")})
    write_json(root / "segmentation_report.json", report)
    if traces is not None:
        write_json(root / "direct_source_trace.json", {"object_id": object_id, "traces": traces, "master_sha256": MASTER_SHA})
    # A source-derived, separately alpha-masked reference cutout. RGB at alpha=0
    # is cleared to prevent fringes. It is NOT a de-lit production albedo; the
    # static master bakes in optics and therefore stays a reference-only asset.
    box = mask_bbox(visible)
    if box[2] > box[0] and box[3] > box[1] and object_id.startswith(("WW_FISH", "plant_", "rock_")):
        crop_rgb = np.asarray(master.crop(tuple(box)).convert("RGB"))
        alpha = np.asarray(Image.fromarray((visible.astype(np.uint8) * 255), "L").crop(tuple(box)))
        alpha = cv2.GaussianBlur(alpha, (3, 3), 0.55)
        rgba = np.dstack([crop_rgb, alpha])
        rgba[alpha == 0, :3] = 0
        Image.fromarray(rgba, "RGBA").save(root / "source_appearance_cutout_reference.png", optimize=True)
    return report


def run(master_path: Path, package: Path) -> dict:
    raw = master_path.read_bytes()
    if sha(raw) != MASTER_SHA:
        raise SystemExit(f"MASTER_SHA_MISMATCH expected={MASTER_SHA} actual={sha(raw)}")
    master = Image.open(master_path)
    master.load()
    if master.size != SIZE or master.mode != "RGB" or "A" in master.getbands():
        raise SystemExit(f"MASTER_METADATA_MISMATCH size={master.size} mode={master.mode}")
    rgb = np.asarray(master, dtype=np.uint8)
    reports = {}
    for object_id, spec in OBJECTS.items():
        mask, confidence, extra = grabcut_object(rgb, spec)
        reports[object_id] = export_object(package, master, rgb, object_id, mask, confidence,
                                           spec["roi"], extra["details"], extra["overlay"])
    resident_data = resident_source_masks(rgb, package)
    for rid, value in resident_data.items():
        mask = value["mask"]
        # Expose source-traced strands only. The 1-px trace mask does not claim
        # to encode full optical width or hidden continuation.
        confidence = np.where(mask, np.uint8(160), np.uint8(0))
        reports[rid] = export_object(package, master, rgb, rid, mask, confidence,
                                     value["roi"], value["details"], traces=value["traces"])
    status_counts = {}
    for row in reports.values():
        state = row.get("status", row.get("master_confidence_label", "UNKNOWN"))
        status_counts[state] = status_counts.get(state, 0) + 1
    summary = {
        "schema_version": "water-world-v2-1-ground-truth/1.0",
        "master_sha256": MASTER_SHA, "master_bytes": len(raw), "dimensions_px": list(SIZE),
        "format": "PNG", "mode": "RGB", "has_alpha": False,
        "segmentation_model": "NONE; OpenCV GrabCut with recorded manual ROI/polygon/color seeds",
        "opencv_version": cv2.__version__, "object_count": len(reports),
        "object_ids": sorted(reports), "objects": reports,
        "quality_gate": "BLOCKED_GROUND_TRUTH_UNCERTAIN" if any(
            (row.get("status") == "BLOCKED_GROUND_TRUTH_UNCERTAIN") or
            (row.get("master_confidence_label") == "LOW") for row in reports.values()) else "REQUIRES_INDEPENDENT_REVIEW",
        "quality_gate_reason": "Resident trace coverage and same-color identities remain below the frozen ≥90% direct-trace requirement; foliage/rock boundaries overlap optics and occluders.",
        "status_counts": status_counts,
    }
    write_json(package / "01_ANALYSIS/ground_truth/source_object_registry.json", summary)
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--master", type=Path, required=True)
    parser.add_argument("--package-root", type=Path, required=True)
    args = parser.parse_args()
    summary = run(args.master, args.package_root)
    print(json.dumps({"master_sha256": summary["master_sha256"], "object_count": summary["object_count"],
                      "quality_gate": summary["quality_gate"],
                      "resident_coverage": {rid: summary["objects"][rid]["direct_trace_coverage"] for rid in RESIDENTS}},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
