#!/usr/bin/env python3
"""Generate the frozen V1 semantic ROI masks from the exact contract references.

The reference PNGs are inputs only and are never rewritten. Geometry annotations
are explicit pixel-space traces; RGB segmentation is deterministic Pillow HSV /
RGB gating constrained by those traces. Re-running this script regenerates every
derived mask, the overlay contact sheets, and the SHA manifest.
"""

from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[3]
P0 = ROOT / "docs/p0_3"
MASK_DIR = P0 / "roi_masks"
OVERLAY_DIR = P0 / "overlays"
MASK_OVERLAY_DIR = OVERLAY_DIR / "masks"
REF_A_PATH = P0 / "references/REF_A_CORE_CRYSTAL_RGBA.png"
REF_B_PATH = P0 / "references/REF_B_FULL_SCENE_RGB.png"
CONTRACT_PATH = P0 / "contract/AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.json"
CANVAS = (1254, 1254)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def smooth_path(points: list[tuple[int, int]], samples: int = 24) -> list[tuple[int, int]]:
    """Deterministic Catmull-Rom sampling for resident centerlines."""
    out: list[tuple[int, int]] = []
    for i in range(len(points) - 1):
        p0, p1 = points[max(i - 1, 0)], points[i]
        p2, p3 = points[i + 1], points[min(i + 2, len(points) - 1)]
        for j in range(samples):
            t = j / samples
            t2, t3 = t * t, t * t * t
            coords = []
            for axis in (0, 1):
                v = 0.5 * (
                    2 * p1[axis]
                    + (-p0[axis] + p2[axis]) * t
                    + (2 * p0[axis] - 5 * p1[axis] + 4 * p2[axis] - p3[axis]) * t2
                    + (-p0[axis] + 3 * p1[axis] - 3 * p2[axis] + p3[axis]) * t3
                )
                coords.append(round(v))
            out.append((coords[0], coords[1]))
    out.append(points[-1])
    return out


def line_mask(points: list[tuple[int, int]], width: int) -> np.ndarray:
    im = Image.new("L", CANVAS, 0)
    draw = ImageDraw.Draw(im)
    path = smooth_path(points)
    draw.line(path, fill=255, width=width, joint="curve")
    radius = width // 2
    for x, y in (path[0], path[-1]):
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=255)
    return np.asarray(im) > 0


def polygon_union(polygons: list[list[tuple[int, int]]]) -> np.ndarray:
    im = Image.new("L", CANVAS, 0)
    draw = ImageDraw.Draw(im)
    for polygon in polygons:
        draw.polygon(polygon, fill=255)
    return np.asarray(im) > 0


def save_mask(name: str, pixels: np.ndarray) -> None:
    mask = Image.fromarray(np.asarray(pixels, dtype=np.uint8) * 255, mode="L")
    if mask.size != CANVAS:
        raise ValueError(f"{name}: expected {CANVAS}, got {mask.size}")
    mask.save(MASK_DIR / f"{name}.png", optimize=False)


def mask_bounds(pixels: np.ndarray) -> list[int] | None:
    ys, xs = np.nonzero(pixels)
    if not len(xs):
        return None
    return [int(xs.min()), int(ys.min()), int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1)]


def overlay(base: Image.Image, mask_name: str, color: tuple[int, int, int]) -> Image.Image:
    out = base.convert("RGB").copy()
    mask = Image.open(MASK_DIR / f"{mask_name}.png").convert("L")
    if mask_name == "REF_A_CORE_SILHOUETTE_ALPHA_GE_008":
        # For a silhouette ROI show only its boundary, keeping the reference
        # interior readable during the annotation self-check.
        edge = mask.filter(ImageFilter.MaxFilter(9))
        inner = mask.filter(ImageFilter.MinFilter(9))
        mask = Image.fromarray(np.maximum(np.asarray(edge) - np.asarray(inner), 0).astype(np.uint8), "L")
    out.paste(Image.new("RGB", CANVAS, color), (0, 0), mask.point(lambda p: int(p * 0.48)))
    draw = ImageDraw.Draw(out)
    draw.rectangle((0, 0, 760, 27), fill=(0, 0, 0))
    draw.text((7, 7), mask_name, fill=(255, 255, 255), font=ImageFont.load_default())
    return out


def main() -> None:
    MASK_DIR.mkdir(parents=True, exist_ok=True)
    OVERLAY_DIR.mkdir(parents=True, exist_ok=True)
    contract = json.loads(CONTRACT_PATH.read_text())
    reference_a = Image.open(REF_A_PATH).convert("RGBA")
    reference_b = Image.open(REF_B_PATH).convert("RGB")
    if reference_a.size != CANVAS or reference_b.size != CANVAS:
        raise ValueError("Contract references must remain 1254x1254")

    rgba = np.asarray(reference_a)
    rgb = rgba[:, :, :3].astype(np.float32)
    alpha = rgba[:, :, 3]
    hsv = np.asarray(reference_a.convert("RGB").convert("HSV"))
    hue, saturation, value = (hsv[:, :, i].astype(np.int16) for i in range(3))
    yy, xx = np.mgrid[0 : CANVAS[1], 0 : CANVAS[0]]
    radius = np.sqrt((xx - 626.5) ** 2 + (yy - 620.5) ** 2)
    inside = (alpha >= 8) & (radius < 438)

    # Glass shell annulus: trace the visible edge of the orb against transparency.
    save_mask("REF_A_OUTER_GLASS_RIM", (alpha >= 8) & (radius >= 438) & (radius <= 506))

    # Material ROIs: independent hand-traced envelopes plus color evidence.
    # Overlap is retained where the actual reference structures meet; masks are
    # not priority-clipped into mutually exclusive classes.
    emerald_envelopes = polygon_union([
        [(164,519),(204,473),(243,462),(278,480),(311,513),(341,552),(374,584),(409,612),(445,644),(478,681),(501,719),(480,755),(440,750),(400,730),(360,711),(321,684),(284,655),(249,620),(214,581),(180,545)],
        [(724,300),(760,302),(794,324),(823,359),(849,403),(869,451),(879,500),(873,548),(855,592),(834,633),(808,667),(778,682),(755,659),(763,623),(782,584),(792,541),(788,494),(775,449),(754,406),(734,362)],
        [(248,698),(298,700),(350,720),(404,750),(459,785),(512,820),(566,852),(621,878),(679,893),(735,891),(788,873),(835,846),(871,814),(894,790),(904,835),(881,877),(845,918),(794,948),(733,963),(673,952),(614,929),(554,900),(496,866),(439,832),(382,799),(327,766),(282,735)],
    ])
    green_evidence = (
        (rgb[:, :, 1] > rgb[:, :, 0] * 1.08)
        & (rgb[:, :, 1] > rgb[:, :, 2] * 0.86)
        & (saturation > 42)
        & (value > 35)
    )
    emerald = inside & emerald_envelopes & green_evidence

    aqua_envelope = polygon_union([[
        (352,609),(417,614),(477,631),(537,659),(590,687),(650,710),(705,717),(755,701),(798,672),(832,640),(873,632),(906,665),(917,718),(898,777),(866,836),(822,889),(770,932),(708,961),(647,966),(586,944),(527,913),(474,880),(430,846),(397,806),(371,758),(354,709),
    ]])
    aqua_evidence = (hue >= 116) & (hue <= 145) & (saturation >= 42) & (value >= 75)
    aqua = inside & aqua_envelope & aqua_evidence

    sapphire_envelopes = polygon_union([
        [(405,507),(473,516),(536,542),(590,570),(647,589),(702,592),(754,578),(799,551),(837,520),(870,515),(900,549),(909,595),(899,647),(876,694),(844,733),(807,754),(771,742),(734,719),(690,699),(642,690),(592,694),(545,680),(504,656),(465,630),(433,601),(412,566)],
        [(829,389),(866,394),(898,426),(922,467),(933,514),(924,559),(906,594),(887,573),(881,534),(870,494),(852,452),(831,421)],
    ])
    dark_blue_evidence = (
        (value <= 144)
        & (hue >= 126)
        & (hue <= 178)
        & (saturation >= 65)
        & (rgb[:, :, 2] >= rgb[:, :, 1] * 0.92)
    )
    sapphire = inside & sapphire_envelopes & dark_blue_evidence
    save_mask("REF_A_EMERALD_FOLD", emerald)
    save_mask("REF_A_SAPPHIRE_VALLEY", sapphire)
    save_mask("REF_A_AQUA_LIQUID", aqua)

    lens_polygon = [
        (304,414),(320,363),(363,318),(421,284),(493,257),(570,245),(650,250),(728,268),(791,302),(837,350),(865,405),(877,461),(861,512),(823,553),(768,583),(702,604),(635,613),(566,608),(496,589),(432,560),(377,520),(338,475),
    ]
    save_mask("REF_A_EMBEDDED_CRYSTAL_LENS", polygon_union([lens_polygon]))

    gold = (alpha >= 8) & (radius < 492) & (hue <= 38) & (saturation >= 45) & (value >= 138)
    warm_white_gold = (
        (alpha >= 8) & (radius < 492) & (rgb[:, :, 0] > 210) & (rgb[:, :, 1] > 185)
        & (rgb[:, :, 0] >= rgb[:, :, 2] * 1.25) & (saturation >= 12)
    )
    save_mask("REF_A_GOLD_CAUSTIC_HIGHLIGHTS", gold | warm_white_gold)

    # REF_B residents: centerlines are hand-traced on the exact frozen full scene.
    save_mask("REF_B_RESIDENT_FRONT_CYAN", line_mask([
        (90,462),(127,492),(169,524),(220,553),(281,584),(345,616),(411,648),(478,680),(548,711),(617,742),(685,771),(754,799),(823,821),(889,831),(951,824),(1009,804),(1064,778),(1104,754),
    ], 30))
    front_gold = line_mask([
        (218,330),(271,289),(337,258),(408,236),(486,223),(560,224),(620,239),(681,270),(738,313),(789,362),(836,420),(881,483),(929,543),(982,594),(1035,636),(1080,664),
    ], 15) | line_mask([
        (252,350),(302,379),(363,408),(431,443),(498,481),(561,526),(620,571),(681,614),(742,657),(801,693),(859,717),(914,728),(964,717),(1001,691),
    ], 13)
    save_mask("REF_B_RESIDENT_FRONT_GOLD", front_gold)
    back_paths = [
        ([(303,226),(376,170),(464,137),(555,127),(647,137),(732,162),(805,196),(866,227),(921,270)], 42),
        ([(923,270),(973,331),(1005,403),(1020,483),(1006,559),(978,631),(944,694),(906,753),(863,807)], 48),
        ([(863,807),(802,861),(728,908),(645,938),(562,939),(486,914),(416,874),(358,826)], 44),
        ([(358,826),(301,766),(256,695),(227,616),(210,531),(216,451),(246,373),(274,302)], 43),
    ]
    back = np.zeros(CANVAS[::-1], dtype=bool)
    for path, width in back_paths:
        back |= line_mask(path, width)
    save_mask("REF_B_RESIDENT_BACK_GREEN_GOLD", back)

    bokeh = Image.new("L", CANVAS, 0)
    bokeh_draw = ImageDraw.Draw(bokeh)
    bokeh_discs = [
        (25,33,50,48),(110,118,31,28),(520,54,22,20),(785,50,42,37),(1172,25,67,60),(1168,156,26,30),
        (1218,438,25,33),(58,333,39,41),(64,790,39,49),(147,1100,52,58),(1112,1080,49,44),(1170,1200,34,39),
    ]
    for cx, cy, rx, ry in bokeh_discs:
        bokeh_draw.ellipse((cx-rx, cy-ry, cx+rx, cy+ry), fill=255)
    bokeh_pixels = np.asarray(bokeh) > 0
    bokeh_pixels &= np.sqrt((xx - 626.5) ** 2 + (yy - 620.5) ** 2) > 360
    save_mask("REF_B_BACKGROUND_BOKEH", bokeh_pixels)

    droplets = Image.new("L", CANVAS, 0)
    droplet_draw = ImageDraw.Draw(droplets)
    droplet_ellipses = [
        (690,213,14,17),(1025,304,17,19),(165,653,12,13),(943,895,16,17),(853,948,10,11),(173,266,5,5),
        (1116,316,4,4),(1090,403,3,3),(102,440,4,4),(1101,938,3,3),(301,1002,3,3),(633,1080,4,4),
        (780,1096,3,3),(189,553,3,3),(1058,245,3,3),
    ]
    for cx, cy, rx, ry in droplet_ellipses:
        droplet_draw.ellipse((cx-rx, cy-ry, cx+rx, cy+ry), fill=255)
    save_mask("REF_B_DROPLETS_AND_PARTICLES", np.asarray(droplets) > 0)

    colors = {
        "REF_A_CORE_SILHOUETTE_ALPHA_GE_008": (255, 255, 255),
        "REF_A_OUTER_GLASS_RIM": (255, 60, 70),
        "REF_A_EMERALD_FOLD": (30, 255, 70),
        "REF_A_SAPPHIRE_VALLEY": (35, 100, 255),
        "REF_A_AQUA_LIQUID": (0, 235, 255),
        "REF_A_EMBEDDED_CRYSTAL_LENS": (255, 0, 255),
        "REF_A_GOLD_CAUSTIC_HIGHLIGHTS": (255, 185, 0),
        "REF_B_RESIDENT_FRONT_CYAN": (0, 240, 255),
        "REF_B_RESIDENT_FRONT_GOLD": (255, 185, 0),
        "REF_B_RESIDENT_BACK_GREEN_GOLD": (90, 255, 20),
        "REF_B_BACKGROUND_BOKEH": (240, 30, 220),
        "REF_B_DROPLETS_AND_PARTICLES": (255, 255, 255),
    }
    core_mask = Image.open(MASK_DIR / "REF_A_CORE_SILHOUETTE_ALPHA_GE_008.png").convert("L")
    if core_mask.size != CANVAS or sha256(core_mask.tobytes()) != sha256((alpha >= 8).astype(np.uint8) * 255):
        raise ValueError("Frozen core silhouette must equal REF_A alpha >= 8")

    source_a_sha, source_b_sha = sha256(REF_A_PATH.read_bytes()), sha256(REF_B_PATH.read_bytes())
    actual_source_shas = {"REF_A": source_a_sha, "REF_B": source_b_sha}
    expected_source_shas = {"REF_A": "2773e4628ff6132605314693742488542c3e70fd04b581d8825dd13cea0067ee", "REF_B": "910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122"}
    if actual_source_shas != expected_source_shas:
        raise ValueError(f"Frozen reference SHA mismatch: {actual_source_shas}")

    # Save one original-image overlay per mask, then build source-level contact
    # sheets as an additional quick visual index.
    MASK_OVERLAY_DIR.mkdir(parents=True, exist_ok=True)
    for ref_id, base, names in (
        ("REF_A", reference_a, [n for n in colors if n.startswith("REF_A_")]),
        ("REF_B", reference_b, [n for n in colors if n.startswith("REF_B_")]),
    ):
        for name in names:
            out = MASK_OVERLAY_DIR / f"{name}_OVERLAY.png"
            overlay(base, name, colors[name]).save(out, optimize=False)

    # A tile for every semantic mask; source under a 48% overlay tint.
    for ref_id, base, names in (
        ("REF_A", reference_a, [n for n in colors if n.startswith("REF_A_")]),
        ("REF_B", reference_b, [n for n in colors if n.startswith("REF_B_")]),
    ):
        tiles = [overlay(base, name, colors[name]).resize((376, 376), Image.Resampling.LANCZOS) for name in names]
        columns = 3
        rows = math.ceil(len(tiles) / columns)
        contact = Image.new("RGB", (columns * 376, rows * 376), (18, 18, 18))
        for idx, tile in enumerate(tiles):
            contact.paste(tile, ((idx % columns) * 376, (idx // columns) * 376))
        contact.save(OVERLAY_DIR / f"{ref_id}_SEMANTIC_ROI_CONTACT_SHEET.png", optimize=False)

    regions = [r for r in contract["visual_regions"]["regions"] if r["source"] == "SEMANTIC_MASK"]
    records = []
    for region in regions:
        mask_path = ROOT / "docs/p0_3" / region["mask_file"]
        if not mask_path.is_file():
            raise ValueError(f"Missing required mask: {mask_path}")
        with Image.open(mask_path) as im:
            if im.size != CANVAS or im.mode != "L":
                raise ValueError(f"{region['id']}: mask must be 1254x1254 grayscale")
            pixels = np.asarray(im)
            count = int(np.count_nonzero(pixels))
            if count == 0:
                raise ValueError(f"{region['id']}: empty mask")
        ref_id = region["reference"]
        overlay_path = OVERLAY_DIR / f"{ref_id}_SEMANTIC_ROI_CONTACT_SHEET.png"
        overlay_path = MASK_OVERLAY_DIR / f"{Path(region['mask_file']).stem}_OVERLAY.png"
        contact_sheet_path = OVERLAY_DIR / f"{ref_id}_SEMANTIC_ROI_CONTACT_SHEET.png"
        records.append({
            "id": region["id"],
            "reference": ref_id,
            "file": region["mask_file"],
            "width": CANVAS[0],
            "height": CANVAS[1],
            "nonZeroPixels": count,
            "areaRatio": round(count / (CANVAS[0] * CANVAS[1]), 8),
            "boundingRectXYWH": mask_bounds(pixels > 0),
            "sha256": sha256(mask_path.read_bytes()),
            "sourceSha256": actual_source_shas[ref_id],
            "overlayFile": str(overlay_path.relative_to(P0)),
            "overlaySha256": sha256(overlay_path.read_bytes()),
            "overlayContactSheet": str(contact_sheet_path.relative_to(P0)),
            "overlayContactSheetSha256": sha256(contact_sheet_path.read_bytes()),
            "method": "Deterministic pixel-space trace and constrained segmentation; see generate_semantic_roi_masks.py",
            "generationTool": "tools/orb-v3-generator/p0_3/generate_semantic_roi_masks.py",
            "generationToolVersion": "1.1.0",
        })
    core_region = next(r for r in contract["visual_regions"]["regions"] if r["id"] == "CORE_SILHOUETTE")
    core_path = ROOT / "docs/p0_3" / core_region["mask_file"]
    core_overlay_path = MASK_OVERLAY_DIR / "REF_A_CORE_SILHOUETTE_ALPHA_GE_008_OVERLAY.png"
    core_contact_sheet_path = OVERLAY_DIR / "REF_A_SEMANTIC_ROI_CONTACT_SHEET.png"
    records.append({
        "id": "CORE_SILHOUETTE",
        "reference": "REF_A",
        "file": core_region["mask_file"],
        "width": CANVAS[0],
        "height": CANVAS[1],
        "nonZeroPixels": int(np.count_nonzero(np.asarray(core_mask))),
        "areaRatio": round(int(np.count_nonzero(np.asarray(core_mask))) / (CANVAS[0] * CANVAS[1]), 8),
        "boundingRectXYWH": mask_bounds(np.asarray(core_mask) > 0),
        "sha256": sha256(core_path.read_bytes()),
        "sourceSha256": source_a_sha,
        "overlayFile": str(core_overlay_path.relative_to(P0)),
        "overlaySha256": sha256(core_overlay_path.read_bytes()),
        "overlayContactSheet": str(core_contact_sheet_path.relative_to(P0)),
        "overlayContactSheetSha256": sha256(core_contact_sheet_path.read_bytes()),
        "method": "Frozen contract mask; exact REF_A alpha >= 8 threshold",
        "generationTool": "tools/orb-v3-generator/p0_3/generate_semantic_roi_masks.py",
        "generationToolVersion": "1.1.0",
    })
    material_overlap = {
        "EMERALD_FOLD__SAPPHIRE_VALLEY": int(np.count_nonzero(emerald & sapphire)),
        "EMERALD_FOLD__AQUA_LIQUID": int(np.count_nonzero(emerald & aqua)),
        "SAPPHIRE_VALLEY__AQUA_LIQUID": int(np.count_nonzero(sapphire & aqua)),
    }
    manifest = {
        "schema": "AI_ORB_P0_3_ROI_MASK_FREEZE_MANIFEST_1_0",
        "generationTool": "tools/orb-v3-generator/p0_3/generate_semantic_roi_masks.py",
        "generationToolVersion": "1.1.0",
        "contractSha256": sha256(CONTRACT_PATH.read_bytes()),
        "referenceSha256": actual_source_shas,
        "canvas": list(CANVAS),
        "frozen": True,
        "selfCheck": {
            "contractRegionCount": len(contract["visual_regions"]["regions"]),
            "requiredMaskCount": len(records),
            "allRequiredMasksPresent": len(records) == 12,
            "materialMaskOverlapPolicy": "INDEPENDENT_SEMANTIC_REGIONS_ALLOW_OVERLAP",
            "materialOverlapPixels": material_overlap,
            "referenceHashesVerified": True,
            "perMaskOverlaysGenerated": all((P0 / r["overlayFile"]).is_file() for r in records),
            "overlayContactSheetsGenerated": True,
        },
        "records": sorted(records, key=lambda r: r["id"]),
    }
    if not manifest["selfCheck"]["allRequiredMasksPresent"] or not manifest["selfCheck"]["perMaskOverlaysGenerated"]:
        raise ValueError("ROI semantic-mask self-check failed")
    freeze_manifest_path = P0 / "ROI_MASK_FREEZE_MANIFEST.json"
    freeze_manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    # Keep the original manifest name as a compatibility copy for existing fit scripts.
    manifest_path = P0 / "ROI_MASK_MANIFEST.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    sums = {}
    for path in sorted([*MASK_DIR.glob("*.png"), *OVERLAY_DIR.rglob("*.png"), manifest_path, freeze_manifest_path]):
        sums[str(path.relative_to(P0))] = {"bytes": path.stat().st_size, "sha256": sha256(path.read_bytes())}
    (P0 / "DERIVED_SHA256SUMS.json").write_text(json.dumps(sums, indent=2) + "\n")
    print(json.dumps(manifest["selfCheck"], indent=2))
    print(f"Frozen ROI manifest: {freeze_manifest_path}")


if __name__ == "__main__":
    main()
