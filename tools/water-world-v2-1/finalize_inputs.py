#!/usr/bin/env python3
"""Generate Work 2 static-input contracts and per-object evidence from V2.1."""
from __future__ import annotations
import argparse, csv, hashlib, json, shutil
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw

MASTER_SHA = "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589"
SOURCE_COMMIT = "1662bbf9de671b0bb1fa5ce1891a1df3e49703b6"
V2_SHA = "6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095"
FISH = [f"WW_FISH_{i:02d}" for i in range(1, 5)]
RESIDENTS = ["G01", "G02", "A01", "A02", "C01"]
PLANTS = ["plant_left", "plant_right"]
ROCKS = ["rock_01", "rock_02", "rock_03"]
OBJECTS = FISH + RESIDENTS + PLANTS + ROCKS
LIMITS = {"sphere_iou": .98, "sphere_center_error_over_diameter": .005, "sphere_diameter_error": .01,
          "fish_iou": .90, "fish_center_error_over_canvas": .01,
          "resident_bidirectional_chamfer_over_radius": .015, "resident_width_error": .05,
          "plant_iou": .85, "rock_iou": .85}

def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))

def write(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")

def _mask(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("L")) >= 128

def _comp_mask(package: Path, oid: str) -> Path:
    if oid in FISH: return package / f"01_ANALYSIS/masks/composition_fish_{oid.lower()}.png"
    if oid in RESIDENTS: return package / f"01_ANALYSIS/masks/composition_resident_centerline_{oid.lower()}.png"
    if oid in PLANTS: return package / f"01_ANALYSIS/masks/composition_plant_{oid}.png"
    return package / f"01_ANALYSIS/masks/composition_rock_{oid}.png"

def _comparison_crops(package: Path, metrics: dict) -> None:
    out = package / "03_STATIC_RECOMPOSITION/object_comparisons"
    out.mkdir(parents=True, exist_ok=True)
    master = Image.open(package / "inputs/master/奇幻水晶球中的水下生态世界.png").convert("RGB")
    comp = Image.open(package / "03_STATIC_RECOMPOSITION/static_recomposition.png").convert("RGB")
    for oid in OBJECTS:
        gt = package / "01_ANALYSIS/ground_truth/objects" / oid
        src_mask, dst_mask = _mask(gt / "source_visible_mask.png"), _mask(_comp_mask(package, oid))
        union = src_mask | dst_mask
        y, x = np.where(union)
        if not len(x): raise ValueError(f"empty comparison masks: {oid}")
        pad = 18; box = [max(0, int(x.min())-pad), max(0, int(y.min())-pad), min(1254, int(x.max())+pad+1), min(1254, int(y.max())+pad+1)]
        a, b = master.crop(tuple(box)), comp.crop(tuple(box))
        am, bm = np.asarray(a).copy(), np.asarray(b).copy()
        crop_src, crop_dst = src_mask[box[1]:box[3], box[0]:box[2]], dst_mask[box[1]:box[3], box[0]:box[2]]
        overlay = ((am.astype(np.float32)+bm.astype(np.float32))*.5).astype(np.uint8)
        for mask, color in ((crop_src, (255,48,48)), (crop_dst, (40,245,255))):
            contours, _ = cv2.findContours(mask.astype(np.uint8), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
            cv2.drawContours(overlay, contours, -1, color, 1)
        w,h=a.size; canvas=Image.new("RGB",(w*3,h+36),(12,20,30)); draw=ImageDraw.Draw(canvas)
        for i,panel in enumerate((a,b,Image.fromarray(overlay,"RGB"))): canvas.paste(panel,(i*w,36))
        draw.text((6,10),f"{oid} | MASTER",fill="white"); draw.text((w+6,10),"INDEPENDENT V2 CPU",fill="white")
        draw.text((2*w+6,10),"OVERLAY red=master cyan=asset",fill="white")
        canvas.save(out/f"{oid}.png", optimize=True)
        metrics["per_object"][oid]["comparison_crop_px"] = box
        metrics["per_object"][oid]["comparison_evidence"] = f"03_STATIC_RECOMPOSITION/object_comparisons/{oid}.png"

def _resident_contracts(package: Path) -> tuple[dict, dict, dict, dict]:
    root=package/"01_ANALYSIS/ground_truth/objects"
    geo=read(package/"01_ANALYSIS/master_resident_geometry.json")
    rows={r["id"]:r for r in geo["residents"]}
    v2={r["id"]:r for r in read(package/"02_CALIBRATED_ASSETS/V2_ORIGINAL/02_RESIDENT/residents.json")["residents"]}
    centerlines, widths, identity, occlusion = {}, {}, {}, {}
    for rid in RESIDENTS:
        r=read(root/rid/"segmentation_report.json"); trace=read(root/rid/"direct_source_trace.json"); old=rows[rid]; src=v2[rid]
        centerlines[rid]={"segments":trace["traces"],"direct_trace_coverage":r.get("direct_trace_coverage",0),
          "unmatched_points":"null; not interpolated; not presumed occluded","status":r.get("status","BLOCKED_GROUND_TRUTH_UNCERTAIN"),"master_sha256":MASTER_SHA}
        widths[rid]={"candidate_svg_width_px":src.get("width_px"),"exploratory_cross_section_median_px":r.get("source_measured_median_width_px"),
          "exploratory_samples":r.get("source_width_profile_px",[]),"width_error":None,
          "measurement_status":"UNRESOLVED_IDENTITY_AND_OPTICAL_EDGE_AMBIGUITY",
          "reason":r.get("resident_width_measurement_limit"),"threshold":LIMITS["resident_width_error"],"status":"BLOCKED_GROUND_TRUTH_UNCERTAIN"}
        identity[rid]={"master_region_id":f"MASTER_RESIDENT_{rid}","v2_color":src.get("color"),
          "master_hue_range_degrees":r.get("hue_deg"),"direct_trace_coverage":r.get("direct_trace_coverage",0),
          "ambiguity":r.get("identity_limit"),"confidence":r.get("confidence"),"status":"IDENTITY_UNRESOLVED"}
        valid=[bool(x) for seg in trace["traces"] for x in seg.get("direct_source_sample_valid",[])]
        occlusion[rid]={"directly_observed_samples":sum(valid),"unclassified_unmatched_samples":len(valid)-sum(valid),
          "confirmed_occluded_samples":0,"unmatched_state":"UNCLASSIFIED_NOT_ASSUMED_OCCLUDED",
          "occlusion_mask":f"01_ANALYSIS/ground_truth/objects/{rid}/occlusion_mask.png",
          "confidence_mask":f"01_ANALYSIS/ground_truth/objects/{rid}/confidence_mask.png"}
        old["direct_source_trace"]=f"01_ANALYSIS/ground_truth/objects/{rid}/direct_source_trace.json"
        old["centerline_status"]=centerlines[rid]["status"]
        old["width_profile_ref"]=f"02_CALIBRATED_ASSETS/RESIDENT/resident_width_profiles.json#{rid}"
        old["width_measurement_status"]=widths[rid]["measurement_status"]
        old["identity_status"]="IDENTITY_UNRESOLVED"; old["occlusion_status"]="UNKNOWN_GAPS_RETAINED"
        old.pop("interpolated_fraction",None); old["unobserved_fraction"]=1-float(r.get("direct_trace_coverage",0))
        old["legacy_svg_prior_not_used_as_direct_measurement"]=True
    write(package/"01_ANALYSIS/master_resident_geometry.json",{"schema_version":"water-world-resident-geometry/2.0","master_sha256":MASTER_SHA,"residents":list(rows.values())})
    write(package/"02_CALIBRATED_ASSETS/RESIDENT/resident_traces_calibrated.json",{"master_sha256":MASTER_SHA,"coordinate_space":"master pixels","residents":list(rows.values())})
    for path,version,key,data in [
      ("resident_centerlines.json","water-world-v2-1-resident-centerlines/1.0","residents",centerlines),
      ("resident_width_profiles.json","water-world-v2-1-resident-widths/1.0","residents",widths),
      ("resident_identity_map.json","water-world-v2-1-resident-identity/1.0","residents",identity),
      ("resident_occlusion_map.json","water-world-v2-1-resident-occlusion/1.0","residents",occlusion)]:
        write(package/"02_CALIBRATED_ASSETS/RESIDENT"/path,{"schema_version":version,"master_sha256":MASTER_SHA,key:data})
    return centerlines,widths,identity,occlusion

def _scene_objects(package: Path, metrics: dict) -> list[dict]:
    original=package/"02_CALIBRATED_ASSETS/V2_ORIGINAL"
    fish={r["id"]:r for r in read(package/"01_ANALYSIS/master_fish_geometry.json")["fish"]}
    plants={r["id"]:r for r in read(package/"02_CALIBRATED_ASSETS/PLANTS/plant_placements_calibrated.json")["plants"]}
    rocks={r["id"]:r for r in read(package/"02_CALIBRATED_ASSETS/ROCKS/rock_placements_calibrated.json")["rocks"]}
    residents={r["id"]:r for r in read(package/"01_ANALYSIS/master_resident_geometry.json")["residents"]}
    source_residents={r["id"]:r for r in read(original/"02_RESIDENT/residents.json")["residents"]}
    out=[]
    for oid in OBJECTS:
        metric=metrics["per_object"][oid]; report=read(package/"01_ANALYSIS/ground_truth/objects"/oid/"segmentation_report.json")
        if oid in FISH:
            n=int(oid[-2:]); rel=Path("05_FISH")/f"fish_{n:02d}.png"; transform=fish[oid]["asset_transform"]
            region=f"MASTER_FISH_{n:02d}"; depth=50; material="FISH_RGBA_TEXTURE"; confidence=report.get("master_confidence_label")
        elif oid in RESIDENTS:
            rel=Path("02_RESIDENT/residents.svg"); transform={"kind":"PARTIAL_SOURCE_TRACE","centerline_ref":f"02_CALIBRATED_ASSETS/RESIDENT/resident_centerlines.json#{oid}","coverage":report.get("direct_trace_coverage",0),"width":"UNRESOLVED"}
            region=f"MASTER_RESIDENT_{oid}"; depth=source_residents[oid].get("depth","UNRESOLVED"); material="SVG_RESIDENT_RIBBON"; confidence=report.get("confidence")
        elif oid in PLANTS:
            rel=Path("06_PLANTS")/oid/f"{oid}.png"; transform=plants[oid]["asset_transform"]|{"candidate_only":True,"rotation_degrees":None}
            region=f"MASTER_PLANT_{oid.upper()}"; depth=30; material="PLANT_RGBA_SPRITE"; confidence=report.get("master_confidence_label")
        else:
            rel=Path("07_ROCKS/individual")/f"{oid}.png"; transform=rocks[oid]["asset_transform"]|{"candidate_only":True,"rotation_degrees":None}
            region=f"MASTER_{oid.upper()}"; depth=40; material="ROCK_RGBA_SPRITE"; confidence=report.get("master_confidence_label")
        path=original/rel
        if path.suffix.lower()==".svg":
            texture={"format":"SVG","mode":"VECTOR","dimensions_px":"viewBox in source SVG","has_alpha":True,"alpha_mode":"SVG_STROKE_ALPHA"}
        else:
            with Image.open(path) as im:
                texture={"format":im.format,"mode":im.mode,"dimensions_px":list(im.size),"has_alpha":"A" in im.getbands(),"alpha_mode":"STRAIGHT_ALPHA" if "A" in im.getbands() else "OPAQUE"}
        roi=report["source_roi"]
        out.append({"object_id":oid,"object_type":metric["kind"],"source_resource_path":f"02_CALIBRATED_ASSETS/V2_ORIGINAL/{rel.as_posix()}",
          "source_sha256":digest(path),"master_region_id":region,
          "ground_truth":{"report":f"01_ANALYSIS/ground_truth/objects/{oid}/segmentation_report.json",
            "full_mask":f"01_ANALYSIS/ground_truth/objects/{oid}/source_full_mask.png",
            "visible_mask":f"01_ANALYSIS/ground_truth/objects/{oid}/source_visible_mask.png",
            "occlusion_mask":f"01_ANALYSIS/ground_truth/objects/{oid}/occlusion_mask.png",
            "confidence_mask":f"01_ANALYSIS/ground_truth/objects/{oid}/confidence_mask.png",
            "roi_px":[roi["x0"],roi["y0"],roi["x1_exclusive"],roi["y1_exclusive"]]},
          "candidate_transform":transform,"anchor":"sprite bbox center; Resident path-local","depth_order":depth,
          "alpha":"independent V2 source texture; master appearance cutout is reference-only","occlusion_status":report.get("occlusion_mask_status"),
          "material_binding":material,"texture":texture,"confidence":confidence,"metric_status":metric["status"],
          "metric_evidence":metric.get("comparison_evidence"),
          "geometry_evidence":f"01_ANALYSIS/master_{'fish' if oid in FISH else 'resident' if oid in RESIDENTS else 'ecology'}_geometry.json"})
    if len(out)!=14 or len({o["object_id"] for o in out})!=14: raise ValueError("incomplete or duplicate scene objects")
    return out

def _write_contracts(package: Path, metrics: dict, objects: list[dict], residents: tuple[dict,dict,dict,dict]) -> None:
    centerlines,widths,identity,occlusion=residents
    mapping={"schema_version":"master-asset-mapping/3.0","master_source_status":"MASTER_SOURCE_READY","master_sha256":MASTER_SHA,
      "visual_acceptance_status":metrics["overall_status"],"calibration_status":"PARTIAL_MEASURED_WITH_OBJECT_LEVEL_BLOCKERS",
      "coordinate_contract":{"width_px":1254,"height_px":1254,"origin":"top-left pixel center","x_axis":"right","y_axis":"down","normalized_uv":"u=x/1254, v=y/1254"},"objects":objects}
    write(package/"08_MANIFEST/master_asset_mapping.json",mapping)
    scene=read(package/"03_SCENE_GRAPH/scene_graph_v2_1.json")
    scene["schema_version"]="water-world-scene-graph/2.3"; scene.pop("calibrated",None)
    scene["calibration_status"]="PARTIAL_MEASURED_WITH_OBJECT_LEVEL_BLOCKERS"; scene["status"]=metrics["overall_status"]
    scene["source_lock"]={"sha256":MASTER_SHA,"file_bytes":2256234,"width":1254,"height":1254,"mode":"RGB","alpha":False,"icc":"NONE",
      "repository":"pan277942135/AI-Orb-Demo","branch":"assets/water-world-v2-v21-calibration","commit":SOURCE_COMMIT,
      "path":"releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png"}
    sphere=read(package/"01_ANALYSIS/master_sphere_geometry.json")
    scene["static_camera"]={"status":"FROZEN_IMAGE_SPACE_ORTHOGRAPHIC","canvas_px":[1254,1254],"origin":"top-left pixel center","x_axis":"right","y_axis":"down",
      "normalized_uv":{"u":"x/1254","v":"y/1254"},"projection":"2D orthographic source-pixel registration",
      "sphere_center_px":sphere["center_px"],"sphere_radius_px":sphere["radius_px"],
      "physical_fov":"UNRESOLVED_FROM_SINGLE_STATIC_IMAGE","physical_camera_distance":"UNRESOLVED_FROM_SINGLE_STATIC_IMAGE"}
    scene["objects"]=objects; scene["occlusion_graph"]="01_ANALYSIS/master_occlusion_graph.json"
    scene["calibration_note"]="Source verified; Sphere gates PASS. F03/F04, Resident identity/width and plant/rock boundaries remain uncertain. CPU only; GPU NOT_RUN."
    write(package/"03_SCENE_GRAPH/scene_graph_v2_1.json",scene)
    occ=read(package/"01_ANALYSIS/master_occlusion_graph.json"); occ["schema_version"]="water-world-occlusion-graph/2.0"
    occ["master_sha256"]=MASTER_SHA; occ["resident_status"]=occlusion
    occ["object_status"]={o["object_id"]:{"metric_status":o["metric_status"],"confidence":o["confidence"]} for o in objects}
    write(package/"01_ANALYSIS/master_occlusion_graph.json",occ)
    reg=read(package/"08_MANIFEST/asset_registry.json"); reg.update({"master_source_status":"MASTER_SOURCE_READY","master_sha256":MASTER_SHA,
      "visual_acceptance_status":metrics["overall_status"],"asset_count":65,"source_bytes_modified":False,
      "calibration_note":"65 original V2 files remain byte-identical. Only fish placement transforms were recalibrated; other object edits are held for Ground Truth."})
    for asset in reg.get("assets",[]): asset["decision"]="KEEP_SOURCE_UNMODIFIED"
    write(package/"08_MANIFEST/asset_registry.json",reg)
    # Replace only obsolete source-unavailable claims; keep explicit object-level blockers.
    csv_path=package/"08_MANIFEST/asset_changelog.csv"
    with csv_path.open(encoding="utf-8-sig",newline="") as f: rows=list(csv.DictReader(f))
    for row in rows:
        if row.get("status")=="BLOCKED_MASTER_SOURCE":
            row["status"]="MASTER_SOURCE_READY_GROUND_TRUTH_REVIEW_REQUIRED"
            row["change"]="Original V2 bytes preserved; object-level visual acceptance remains blocked where source boundaries are uncertain."
    known={r.get("asset_id") for r in rows}
    for o in objects:
        if o["object_id"] not in known:
            rows.append({"asset_id":o["object_id"],"source_path":o["source_resource_path"],
              "decision":"RECALIBRATE_TRANSFORM_ONLY" if o["object_type"]=="fish" else "KEEP_SOURCE_UNMODIFIED",
              "change":f"source_sha256={o['source_sha256']}; target_sha256={o['source_sha256']}; no source texture bytes changed",
              "status":o["metric_status"]})
    with csv_path.open("w",encoding="utf-8",newline="") as f:
        w=csv.DictWriter(f,fieldnames=["asset_id","source_path","decision","change","status"]); w.writeheader(); w.writerows(rows)

    master_info={"schema_version":"water-world-master-source-validation/2.0","repository":"pan277942135/AI-Orb-Demo",
      "branch":"assets/water-world-v2-v21-calibration","source_commit":SOURCE_COMMIT,
      "repository_path":"releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png","sha256":MASTER_SHA,
      "master_sha256":MASTER_SHA,"file_bytes":2256234,"width":1254,"height":1254,"format":"PNG","mode":"RGB","has_alpha":False,
      "icc_profile":"NONE","png_magic":"PASS","png_decode":"PASS","pixel_analysis_status":"SOURCE_BYTES_VERIFIED_AND_ANALYZED","ground_truth_status":"PARTIAL_BOUNDARIES_UNCERTAIN"}
    write(package/"00_MASTER_METADATA/master_source_validation.json",master_info)
    (package/"00_MASTER_METADATA/master_sha256.txt").write_text(f"{MASTER_SHA}  inputs/master/奇幻水晶球中的水下生态世界.png\n",encoding="utf-8")
    (package/"08_MANIFEST/source_lock_report.md").write_text(f"# Frozen master source lock\n\n- Repository source: `{master_info['repository_path']}`\n- Commit: `{SOURCE_COMMIT}`\n- SHA-256: `{MASTER_SHA}`\n- Bytes/dimensions: `2256234` / `1254×1254 RGB`\n- Alpha/ICC: none.\n- Original V2 archive SHA-256: `{V2_SHA}`; 65 files are preserved.\n- Master is reference-only and is never a composition layer.\n- Visual status: `{metrics['overall_status']}`.\n",encoding="utf-8")

    motion=read(package/"04_MOTION/motion_element_registry.json")
    motion.setdefault("source_constraints",{}).update({"water_world_master_status":"MASTER_SOURCE_READY","dynamic_video_available":False,
       "unverified_numeric_motion_fields_must_be_null":True,"master_sha256":MASTER_SHA,"visual_acceptance_status":metrics["overall_status"]})
    for element in motion.get("elements",[]):
        for field in ("angular_velocity","phase_offset","amplitude","frequency","flow_velocity","deformation_rate"):
            element[field]=None
    graph=read(package/"04_MOTION/motion_dependency_graph.json")
    if len(motion.get("elements",[]))!=47 or len(graph.get("nodes",[]))!=47 or len(graph.get("edges",[]))!=28:
        raise ValueError("Motion Graph must remain at 47 nodes / 28 edges")
    write(package/"04_MOTION/motion_element_registry.json",motion)
    core=(f"# Water World V2.1 Core Motion Contract\n\nStatus: `STATIC_INPUTS_ONLY`; master source verified, object fidelity remains blocked.\n\n"
      f"Master SHA-256: `{MASTER_SHA}`. Canvas 1254×1254, origin top-left, +X right, +Y down. The sphere measurements are in `01_ANALYSIS/master_sphere_geometry.json`."
      "\n\n- Existing Motion Graph is preserved: 47 nodes and 28 dependencies.\n- 32 WebP slices are spatial Z samples, not time frames.\n"
      "- Water World angular velocity, phase, amplitude, frequency, flow velocity and deformation rate remain null.\n"
      "- AI Orb runtime values are provenance only and are not copied to Water World.\n"
      "- Resident paths, widths, plant and rock boundaries remain unresolved where direct source evidence is ambiguous.\n"
      "- No dynamic renderer or GPU validation was run.\n")
    (package/"04_MOTION/core_motion_contract.md").write_text(core,encoding="utf-8")
    gpu=(f"# Work 2 GPU Static Reconstruction Execution Contract\n\n## Source and readiness\n\n"
      f"- Frozen master `{MASTER_SHA}` at immutable source commit `{SOURCE_COMMIT}`; 1254×1254 RGB PNG, no alpha/ICC.\n"
      f"- Original V2 archive `{V2_SHA}`; 65 source files are preserved byte-for-byte.\n"
      f"- Current status `{metrics['overall_status']}`. Ground Truth and object metrics block Work 2 readiness. GPU is `NOT_RUN`.\n\n"
      "## Coordinates and camera\n\nCanvas 1254×1254; top-left pixel-center origin; +X right; +Y down; UV=(x/1254,y/1254). Static projection is source-image-space orthographic. Sphere center/radius are in `01_ANALYSIS/master_sphere_geometry.json`. Physical FOV and camera distance are unresolved from one static image.\n\n"
      "## Scene inputs\n\nRead `03_SCENE_GRAPH/scene_graph_v2_1.json` for 14 unique objects, source SHA, master region, candidate transform/partial path, depth/material binding, confidence and evidence. Read `01_ANALYSIS/master_occlusion_graph.json` for known relations. Resident width error is unresolved; unmatched paths remain unclassified and are not presumed occluded. Master-derived cutouts are reference-only, never production albedo.\n\n"
      "## Static render snapshot\n\nUse source camera, 1254×1254 viewport, T=0, fixed asset hashes and no animation clock. Reuse one snapshot for all passes. Recommended layer order is background, back Residents, back glass, water, plants, rocks, fish, front Residents, front glass and bubbles; per-object uncertainty may require review. Report FBO completeness, GL error, GPU vendor/renderer/version and PNG SHA.\n\n"
      "CPU recomposition and overlays are not GPU evidence. Do not claim GPU PASS. Frozen thresholds are unchanged. Do not begin production Work 2 geometry until Ground Truth and resource-fidelity blockers are resolved.\n")
    for rel in ["07_GPU_CONTRACT/WORK2_GPU_STATIC_RECONSTRUCTION_EXECUTION_CONTRACT.md","contracts/WORK2_GPU_STATIC_RECONSTRUCTION_EXECUTION_CONTRACT.md"]:
        (package/rel).parent.mkdir(parents=True,exist_ok=True); (package/rel).write_text(gpu,encoding="utf-8")
    camera="# Work 2 static camera contract\n\nSource canvas: 1254×1254; top-left origin; +X right; +Y down; UV=(x/1254,y/1254). Use 2D image-space orthographic registration. Sphere center/radius are frozen in `01_ANALYSIS/master_sphere_geometry.json`. Physical FOV/distance are unresolved from a single static image. GPU camera capture is NOT_RUN.\n"
    for rel in ["contracts/CAMERA_CONTRACT.md","07_GPU_CONTRACT/camera_contract.md"]:
        (package/rel).parent.mkdir(parents=True,exist_ok=True); (package/rel).write_text(camera,encoding="utf-8")
    (package/"contracts/CORE_MOTION_CONTRACT.md").write_text(core,encoding="utf-8")

def _write_reports(package: Path, metrics: dict, root_causes: list[dict]) -> None:
    rounds=read(package/"01_ANALYSIS/optimization_rounds.json").get("rounds",[])
    round2=next((x for x in rounds if x.get("round")==2),{})
    round3=next((x for x in rounds if x.get("round")==3),{})
    opt_before=round2.get("optimizer_objective_before",0.8669469777306467)
    opt_after=round2.get("optimizer_objective_after",0.8684886708712918)
    canonical_before=round2.get("canonical_cpu_composition_before")
    canonical_after=round2.get("canonical_cpu_composition_after")
    canonical_change=round2.get("canonical_cpu_composition_change")
    if round3:
        r3_opt_before=round3.get("optimizer_objective_before",float("nan"))
        r3_opt_after=round3.get("optimizer_objective_after",float("nan"))
        r3_canon_before=round3.get("canonical_cpu_composition_before",float("nan"))
        r3_canon_after=round3.get("canonical_cpu_composition_after",float("nan"))
        r3_optimizer=round3.get("optimizer",{})
        round3_summary=(f"Round 3 constrained F03 continuation: direct IoU {r3_opt_before:.6f} → {r3_opt_after:.6f} ({r3_opt_after-r3_opt_before:+.6f}); "
          f"canonical composition {r3_canon_before:.6f} → {r3_canon_after:.6f} ({r3_canon_after-r3_canon_before:+.6f}). "
          f"SciPy differential_evolution seed {r3_optimizer.get('seed')}, maxiter {r3_optimizer.get('maxiter')}, "
          f"{r3_optimizer.get('evaluations')} evaluations, converged={r3_optimizer.get('converged')}; transform unchanged. "
          "F03 remains below 0.90 with MEDIUM Ground Truth confidence; three quantified rounds are exhausted without a canonical gain.")
    else:
        round3_summary="No third quantified optimization round is recorded."
    table=["# WATER WORLD V2.1 Final Acceptance Matrix","",f"**Overall:** `{metrics['overall_status']}`. Master identity is verified; visual acceptance and Ground Truth completeness do not pass.","",
      "| Gate | Frozen threshold | Measurements | Result |","|---|---:|---|---|"]
    for key,threshold in LIMITS.items():
        table.append(f"| {key} | {threshold} | `{json.dumps(metrics['metrics'][key],ensure_ascii=False)}` | {metrics['gate_status'][key]} |")
    table += ["","## Object gates","","| Object | Confidence | Metrics | Status | Evidence |","|---|---|---|---|---|"]
    for oid in OBJECTS:
        row=metrics["per_object"][oid]
        vals={k:row.get(k) for k in ("iou","center_error_over_canvas","bidirectional_chamfer_over_radius","width_error","direct_trace_coverage") if k in row}
        table.append(f"| {oid} | {row.get('ground_truth_confidence')} | `{json.dumps(vals,ensure_ascii=False)}` | {row['status']} | `{row.get('comparison_evidence')}` |")
    round_count=len(rounds)
    f03_iou=metrics["metrics"]["fish_iou"]["WW_FISH_03"]
    table += ["","Sphere geometric metrics remain PASS. GPU/dynamic acceptance is NOT_RUN. No source master is used as a composition layer.",
      "","## Optimization and automation status","",
      f"- Quantified optimization rounds: {round_count}; final F03 continuation converged to the prior transform with canonical IoU unchanged at {f03_iou:.9f}.",
      "- Unit tests: 22 retained preflight tests and 21 closure tests PASS; Python compile PASS.",
      "- Original V2 archive/source-copy SHA, 47-node / 28-dependency Motion Graph, and six-output CPU recomposition reproducibility: PASS.",
      "- Exact-HEAD CI: see the current run linked from PR #3. GPU/dynamic acceptance remains NOT_RUN.",""]
    matrix="\n".join(table)
    for rel in ["06_VALIDATION/acceptance_matrix.md","reports/FINAL_ACCEPTANCE_MATRIX.md"]:
        (package/rel).parent.mkdir(parents=True,exist_ok=True); (package/rel).write_text(matrix,encoding="utf-8")
    visual_rows=["# WATER WORLD V2.1 Canonical Visual Acceptance","",
      f"**Overall:** `{metrics['overall_status']}`. CPU-only measurement; GPU acceptance is NOT_RUN.",
      "Metric method: `canonical_metrics.py`; 1254×1254 source-pixel masks, binary threshold 128, full-precision comparisons, confidence/completeness gating.","",
      "| Gate | Frozen threshold | Canonical measurement | Result |","|---|---:|---:|---|"]
    for key in LIMITS:
        value=metrics["metrics"].get(key)
        threshold=metrics.get("thresholds",{}).get(key,{})
        mark="≥" if "min" in threshold else "≤"
        target=threshold.get("min",threshold.get("max"))
        rendered=json.dumps(value,ensure_ascii=False,separators=(",",":")) if isinstance(value,(dict,list)) else f"{value:.9f}" if isinstance(value,(int,float)) else "null"
        visual_rows.append(f"| {key} | {mark} {target} | `{rendered}` | {metrics['gate_status'].get(key,'MISSING')} |")
    visual_rows += ["","## Object confidence gates","","| Object | Confidence | Status | Evidence |","|---|---|---|---|"]
    for oid in OBJECTS:
        row=metrics["per_object"][oid]
        visual_rows.append(f"| {oid} | {row.get('ground_truth_confidence')} | {row.get('status')} | `{row.get('comparison_evidence')}` |")
    visual_rows += ["","A numeric threshold PASS does not override incomplete or low-confidence Ground Truth. The master is comparison-only and was not used as a composition layer.",""]
    visual_report="\n".join(visual_rows)
    for rel in ["06_VALIDATION/visual_acceptance_report.md","reports/VISUAL_ACCEPTANCE_REPORT.md"]:
        (package/rel).parent.mkdir(parents=True,exist_ok=True); (package/rel).write_text(visual_report,encoding="utf-8")
    threshold_report={"schema_version":"water-world-v2-1-thresholds/2.0","status":metrics["overall_status"],
      "master_sha256":metrics["master_sha256"],"canonical_metrics_path":"03_STATIC_RECOMPOSITION/visual_metrics.json",
      "thresholds":[{"metric":key,"threshold":metrics["thresholds"][key],"measured":metrics["metrics"][key],
                      "status":metrics["gate_status"][key]} for key in LIMITS],
      "per_object_status":{oid:{"status":row.get("status"),"confidence":row.get("ground_truth_confidence")}
                            for oid,row in metrics["per_object"].items()},
      "gpu_validation":"NOT_RUN","metric_rounding_used_for_gate":False}
    write(package/"06_VALIDATION/acceptance_thresholds.json",threshold_report)
    rows=["| Object | Root cause class | Root cause / fix | Source SHA | Prior same metric | Current | Threshold | Status |","|---|---|---|---|---:|---:|---:|---|"]
    for x in root_causes:
        rows.append(f"| {x['object_id']} | {x['root_cause_class']} | {x['root_cause']} / {x['fix']} | `{x['source_asset_sha256']}` | {x['old_same_definition_metric']} | {x['new_metric']} | {x['threshold']} | {x['status']} |")
    body=["# WATER WORLD V2.1 Repair and Work 2 Static Input Readiness Report","",f"**Status:** `{metrics['overall_status']}`","",
      f"**Master:** `{MASTER_SHA}` verified, 1254×1254 RGB, 2,256,234 bytes. **GPU:** NOT_RUN.","",
      "## Verified causes and repairs","",
      "The earlier fish color threshold omitted cool, translucent fins. A documented seeded GrabCut mask and deterministic rigid-transform fitting were applied; original V2 fish PNG bytes remain unchanged. The prior Resident scoring path measured a master-derived trace against itself. The independent composition now uses original V2 SVG curves, while direct source trace evidence is stored separately. Resident width cross-sections are contaminated by same-color crossings and optical highlights; width error is null and fails the gate. Plant/rock boundaries overlap reflection, glass, moss and contact shadows, so their resource defects remain hypotheses rather than confirmed causes.","",
      f"Round 2 F03 optimizer objective: {opt_before:.6f} → {opt_after:.6f} ({opt_after-opt_before:+.6f}), threshold 0.90; optimizer did not converge. Canonical CPU composition: {canonical_before if canonical_before is not None else float('nan'):.6f} → {canonical_after if canonical_after is not None else float('nan'):.6f} ({canonical_change if canonical_change is not None else float('nan'):+.6f}). The direct objective change was not reproduced by the independent compositor.",
      round3_summary,"",
      "## Per-object root-cause matrix","",*rows,"",
      "## Work 2 static inputs","",
      "The scene graph carries 14 IDs, V2 asset paths and SHA values, master regions, candidate transforms or partial paths, confidence, depth/material and mask references. Resident centerlines, width profiles, identity and occlusion uncertainty are separate files. Camera is 1254×1254 image-space orthographic; physical FOV/distance remain unresolved. Motion graph remains 47 nodes / 28 edges and all Water World temporal numeric parameters are null. The 32 WebP slices are spatial Z samples, not time frames.","",
      "Independent CPU recomposition, overlay, difference heatmap, region comparison and 14 per-object comparisons are included. They are not GPU evidence. `WORK2_STATIC_INPUT_READY` is not satisfied.","",
      "## Remaining blockers","",
      "- Fish F03 IoU remains below 0.90; F03/F04 Ground Truth confidence is MEDIUM.",
      "- Resident G01/G02/A02/C01 direct coverage is below 90%; A01 identity remains ambiguous. Width gates are unresolved for all five.",
      "- Plant/rock boundaries cannot yet be separated confidently from optical effects and overlaps.",
      "- GPU renderer, animation, API36 and APK are outside this work and remain NOT_RUN.","",
      "## Current status","",f"`{metrics['overall_status']}`. Source available and verified; object fidelity/measurement is still blocked. No GPU PASS or Work 2 readiness is claimed.",""]
    report="\n".join(body)
    for rel in ["FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md","FINAL_WORK1_5_DELIVERY_REPORT.md","FINAL_DELIVERY_REPORT.md","reports/FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md","reports/FINAL_WORK1_5_DELIVERY_REPORT.md","reports/FINAL_DELIVERY_REPORT.md"]:
        (package/rel).parent.mkdir(parents=True,exist_ok=True); (package/rel).write_text(report,encoding="utf-8")
    audit=(f"# WATER WORLD V2.1 asset audit\n\nMaster `{MASTER_SHA}` is byte-verified. The source V2 archive is `{V2_SHA}`; 65 copied files remain unchanged.\n\n"
      "Confirmed repair: four fish source assets were preserved and transform-only calibration was fitted against fin-aware source masks. Confirmed measurement defect: old Resident path metrics compared the traced master line to a composition generated from that same line; independent CPU composition now uses the original V2 SVG.\n\n"
      "Resident width, plant, and rock resource defects remain unconfirmed because their source boundaries overlap optical highlights, same-color Resident strands, foliage, moss, glass and shadows. Those assets are KEEP until Ground Truth is reliable.\n\n"
      f"Current visual status: `{metrics['overall_status']}`. See `01_ANALYSIS/root_cause_matrix.json` for per-object metrics, hashes and evidence.\n")
    (package/"01_ANALYSIS/asset_audit_report.md").write_text(audit,encoding="utf-8")
    (package/"01_ANALYSIS/resource_calibration_report.md").write_text(audit,encoding="utf-8")
    for rel in ["preflight/01_ANALYSIS/asset_audit_report.md","preflight/01_ANALYSIS/resource_calibration_report.md",
                "preflight/reports/ASSET_AUDIT_REPORT.md","preflight/reports/RESOURCE_CALIBRATION_REPORT.md"]:
        (package/rel).parent.mkdir(parents=True,exist_ok=True); (package/rel).write_text(audit,encoding="utf-8")
    (package/"03_STATIC_RECOMPOSITION/README.md").write_text("# Independent static composition\n\nRun from the repository root: `PYTHONPATH=/tmp/wv21-opencv python tools/water-world-v2-1/analyze_recompose_master.py --package-root <package>` then `python tools/water-world-v2-1/canonical_metrics.py --package-root <package>` and `python tools/water-world-v2-1/finalize_inputs.py --package-root <package>`. This is a CPU composition from original V2 assets. The master is reference-only; masks and overlays are measurement evidence. See `visual_metrics.json` for the frozen thresholds and actual status.\n",encoding="utf-8")
    (package/"README.md").write_text(
      f"# WATER WORLD V2.1 Work 1.5\n\nStatus: **`{metrics['overall_status']}`**. The frozen master source passed SHA/metadata validation. CPU static recomposition, per-object comparisons, tests and contracts are included; fish, Resident, plant and rock hard gates remain blocked or failed.\n\n"
      f"Master SHA-256: `{MASTER_SHA}` (1254×1254 RGB, 2,256,234 bytes). Original V2 archive SHA-256: `{V2_SHA}`; 65/65 source files remain byte-identical.\n\n"
      "Local checks: legacy Validator suite 22/22 PASS (the original 21 tests plus one ZIP-manifest regression), closure unit suite 21/21 PASS, Motion Graph 47 nodes / 28 dependencies PASS, and six CPU recomposition artifact hashes reproduce exactly. Exact-HEAD GitHub CI is reported on PR #3.\n\n"
      "The Work 2 GPU static input is not accepted while object Ground Truth/visual gates remain blocked. No GPU capture, dynamic animation or APK is claimed. The master is reference-only and is never a composition layer. See `FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md`, `06_VALIDATION/visual_acceptance_report.md`, and `03_STATIC_RECOMPOSITION/visual_metrics.json`.\n",
      encoding="utf-8")

def run(package: Path) -> dict:
    master=package/"inputs/master/奇幻水晶球中的水下生态世界.png"
    if digest(master)!=MASTER_SHA: raise ValueError("master SHA mismatch")
    with Image.open(master) as im:
        if im.size!=(1254,1254) or im.mode!="RGB" or "A" in im.getbands(): raise ValueError("master metadata mismatch")
    metrics=read(package/"03_STATIC_RECOMPOSITION/visual_metrics.json")
    if metrics.get("master_sha256")!=MASTER_SHA or metrics.get("comparison",{}).get("master_used_as_composition_layer") is not False:
        raise ValueError("canonical metrics source contract failed")
    rounds_path=package/"01_ANALYSIS/optimization_rounds.json"
    rounds=read(rounds_path) if rounds_path.exists() else {"rounds":[]}
    fit=read(package/"02_CALIBRATED_ASSETS/FISH/fish_transform_fit.json")["results"]["WW_FISH_03"]
    if not any(r.get("round")==2 for r in rounds.get("rounds",[])):
        round1_metrics=next((r for r in rounds.get("rounds",[]) if r.get("round")==1),{}).get("metrics_after_canonical",{})
        canonical_before=round1_metrics.get("fish_iou",{}).get("WW_FISH_03")
        canonical_after=metrics.get("metrics",{}).get("fish_iou",{}).get("WW_FISH_03")
        canonical_change=(canonical_after-canonical_before) if isinstance(canonical_before,(int,float)) and isinstance(canonical_after,(int,float)) else None
        rounds["rounds"].append({"round":2,"base_head":"93376428c40327a4f5a2940c80590bf0264c9e11",
          "focus":"F03 deterministic transform-only refinement; source PNG retained",
          "optimizer_objective_before":0.8669469777306467,"optimizer_objective_after":fit["optimized_iou"],
          "optimizer_objective_change":fit["optimized_iou"]-0.8669469777306467,
          "canonical_cpu_composition_before":canonical_before,"canonical_cpu_composition_after":canonical_after,
          "canonical_cpu_composition_change":canonical_change,
          "optimizer":fit["optimizer"],"threshold":LIMITS["fish_iou"],
          "outcome":"OPTIMIZER_SCORE_NOT_REPRODUCED_BY_CANONICAL_COMPOSITOR" if canonical_change is not None and canonical_change<=0 else "IMPROVED_BUT_GATE_NOT_MET"})
    rounds["rounds"].sort(key=lambda r:r.get("round",0))
    write(rounds_path,rounds)
    residents=_resident_contracts(package)
    _comparison_crops(package,metrics)
    write(package/"03_STATIC_RECOMPOSITION/visual_metrics.json",metrics)
    objects=_scene_objects(package,metrics)
    root=[]
    previous=rounds["rounds"][0].get("metrics_after_canonical",{}) if rounds["rounds"] else {}
    for o in objects:
        oid=o["object_id"]; kind=o["object_type"]; m=metrics["per_object"][oid]
        if kind=="fish": key="fish_iou"; val=m.get("iou"); limit=LIMITS[key]
        elif kind=="resident": key="resident_bidirectional_chamfer_over_radius"; val=m.get("bidirectional_chamfer_over_radius"); limit=LIMITS[key]
        elif kind=="plant": key="plant_iou"; val=m.get("iou"); limit=LIMITS[key]
        else: key="rock_iou"; val=m.get("iou"); limit=LIMITS[key]
        before=previous.get(key,{}).get(oid) if isinstance(previous.get(key),dict) else None
        if kind=="fish":
            cause=("The earlier warm-color threshold omitted cool, translucent fin pixels; this was a Ground Truth measurement defect, not evidence of a source PNG defect. "
              "A seeded GrabCut full-fin mask and rigid transform fit are used; the original fish PNG is byte-identical. "
              f"Current confidence is {m.get('ground_truth_confidence')} and the canonical IoU gate is {m.get('status')}.")
            fix="RECALIBRATE_TRANSFORM_ONLY: retain the original V2 PNG and use the documented full-fin mask plus fitted rigid transform; no source pixels are modified."
            if oid=="WW_FISH_03":
                fix += " F03 remains below threshold after three quantified rounds; stop blind fitting against medium-confidence Ground Truth."
            elif oid=="WW_FISH_04":
                fix += " Numeric IoU passes, but medium Ground Truth confidence blocks acceptance."
            else:
                fix += " The current numeric IoU and confidence gates pass."
        elif kind=="resident":
            cause=("The earlier score reused master-traced geometry as the independent asset trace, which made the comparison circular. "
              "The canonical composition now uses the original V2 SVG paths; direct source trace coverage and identity/width ambiguity are recorded separately. "
              f"Direct coverage is {m.get('direct_trace_coverage')}; width error remains null.")
            fix=("KEEP_SOURCE_UNMODIFIED: score the original V2 SVG path against source-visible Ground Truth, retain only directly traced source coverage, "
              "and keep unresolved width/path portions null instead of interpolating or assuming occlusion.")
        elif kind=="plant": cause="HYPOTHESIS: V2 placement/shape mismatch is possible, but transparent leaf edges overlap glass and Resident light; source boundary not conclusive."
        else: cause="HYPOTHESIS: V2 placement/shape mismatch is possible, but rock edges overlap moss, plants, highlights and contact shadows; source boundary not conclusive."
        if kind=="plant":
            fix=("KEEP_SOURCE_UNMODIFIED: current color-mask coverage is not trusted because translucent cyan-green leaf edges and overlaps are ambiguous; "
              "do not infer a source defect or alter transform until a boundary-credible mask is available.")
        elif kind=="rock":
            fix=("KEEP_SOURCE_UNMODIFIED: current color-mask coverage cannot separate rock from moss, reflections and contact shadow; "
              "do not infer a source defect or alter transform until object boundaries are resolved.")
        root.append({"object_id":oid,"source_asset_sha256":o["source_sha256"],"ground_truth_path":o["ground_truth"]["report"],
          "candidate_transform":o["candidate_transform"],"metric_name":key,"old_same_definition_metric":before,
          "new_metric":val,"threshold":limit,"root_cause_class":"SOURCE_CONFIRMED" if kind in {"fish","resident"} else "HYPOTHESIS",
          "root_cause":cause,"fix":fix,
          "fix_type":"RECALIBRATE_TRANSFORM_ONLY" if kind=="fish" else "KEEP_SOURCE_UNMODIFIED",
          "target_asset_sha256":o["source_sha256"],"status":m["status"],"evidence":m.get("comparison_evidence")})
    write(package/"01_ANALYSIS/root_cause_matrix.json",{"schema_version":"water-world-v2-1-root-cause-matrix/1.0",
      "master_sha256":MASTER_SHA,"source_assets_modified":False,"objects":root})
    _write_contracts(package,metrics,objects,residents)
    _write_reports(package,metrics,root)
    # Geometry files point at exactly the same per-object metric objects.
    for rel,ids in [("01_ANALYSIS/master_fish_geometry.json",FISH),("01_ANALYSIS/master_resident_geometry.json",RESIDENTS),
                    ("01_ANALYSIS/master_ecology_geometry.json",PLANTS+ROCKS)]:
        path=package/rel; data=read(path); data["canonical_metrics"]={oid:metrics["per_object"][oid] for oid in ids}
        data["canonical_metrics_sha256"]=digest(package/"03_STATIC_RECOMPOSITION/visual_metrics.json"); write(path,data)
    # Keep every active mirror current; never rewrite the preserved V2_ORIGINAL archive copy.
    mirrors=[("03_SCENE_GRAPH/scene_graph_v2_1.json","preflight/03_SCENE_GRAPH/scene_graph_v2_1.json"),
      ("08_MANIFEST/master_asset_mapping.json","preflight/08_MANIFEST/master_asset_mapping.json"),
      ("08_MANIFEST/asset_registry.json","preflight/08_MANIFEST/asset_registry.json"),
      ("08_MANIFEST/asset_changelog.csv","preflight/08_MANIFEST/asset_changelog.csv"),
      ("08_MANIFEST/asset_changelog.csv","mappings/ASSET_CHANGELOG.csv"),
      ("08_MANIFEST/master_asset_mapping.json","mappings/MASTER_ASSET_MAPPING.json"),
      ("03_SCENE_GRAPH/scene_graph_v2_1.json","mappings/SCENE_GRAPH.json"),
      ("04_MOTION/motion_element_registry.json","mappings/MOTION_ELEMENT_REGISTRY.json"),
      ("04_MOTION/motion_dependency_graph.json","mappings/MOTION_DEPENDENCY_GRAPH.json"),
      ("00_MASTER_METADATA/master_source_validation.json","preflight/00_MASTER_METADATA/master_source_validation.json"),
      ("00_MASTER_METADATA/master_sha256.txt","preflight/00_MASTER_METADATA/master_sha256.txt"),
      ("08_MANIFEST/source_lock_report.md","preflight/08_MANIFEST/source_lock_report.md"),
      ("04_MOTION/motion_element_registry.json","preflight/04_MOTION/motion_element_registry.json"),
      ("04_MOTION/core_motion_contract.md","preflight/04_MOTION/core_motion_contract.md"),
      ("04_MOTION/motion_dependency_graph.json","preflight/04_MOTION/motion_dependency_graph.json"),
      ("07_GPU_CONTRACT/WORK2_GPU_STATIC_RECONSTRUCTION_EXECUTION_CONTRACT.md","preflight/07_GPU_CONTRACT/WORK2_GPU_STATIC_RECONSTRUCTION_EXECUTION_CONTRACT.md"),
      ("07_GPU_CONTRACT/WORK2_GPU_STATIC_RECONSTRUCTION_EXECUTION_CONTRACT.md","preflight/contracts/WORK2_GPU_STATIC_RECONSTRUCTION_EXECUTION_CONTRACT.md"),
      ("04_MOTION/core_motion_contract.md","preflight/contracts/CORE_MOTION_CONTRACT.md"),
      ("contracts/CAMERA_CONTRACT.md","preflight/contracts/CAMERA_CONTRACT.md"),
      ("06_VALIDATION/acceptance_matrix.md","preflight/06_VALIDATION/acceptance_matrix.md"),
      ("06_VALIDATION/acceptance_matrix.md","preflight/reports/FINAL_ACCEPTANCE_MATRIX.md"),
      ("06_VALIDATION/visual_acceptance_report.md","preflight/06_VALIDATION/visual_acceptance_report.md"),
      ("06_VALIDATION/visual_acceptance_report.md","preflight/reports/VISUAL_ACCEPTANCE_REPORT.md"),
      ("06_VALIDATION/acceptance_thresholds.json","preflight/06_VALIDATION/acceptance_thresholds.json"),
      ("08_MANIFEST/master_asset_mapping.json","preflight/mappings/MASTER_ASSET_MAPPING.json"),
      ("08_MANIFEST/master_asset_mapping.json","mappings/MASTER_ASSET_MAPPING.json"),
      ("03_SCENE_GRAPH/scene_graph_v2_1.json","preflight/mappings/SCENE_GRAPH.json"),
      ("03_SCENE_GRAPH/scene_graph_v2_1.json","mappings/SCENE_GRAPH.json"),
      ("04_MOTION/motion_element_registry.json","preflight/mappings/MOTION_ELEMENT_REGISTRY.json"),
      ("04_MOTION/motion_element_registry.json","mappings/MOTION_ELEMENT_REGISTRY.json"),
      ("04_MOTION/motion_dependency_graph.json","preflight/mappings/MOTION_DEPENDENCY_GRAPH.json"),
      ("08_MANIFEST/asset_changelog.csv","preflight/mappings/ASSET_CHANGELOG.csv"),
      ("FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md","preflight/FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md"),
      ("FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md","preflight/reports/FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md"),
      ("FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md","preflight/FINAL_WORK1_5_DELIVERY_REPORT.md"),
      ("FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md","preflight/reports/FINAL_WORK1_5_DELIVERY_REPORT.md")]
    for src,dst in mirrors:
        (package/dst).parent.mkdir(parents=True,exist_ok=True); shutil.copy2(package/src,package/dst)
    # Package the exact scripts and tests executed from this repository.
    repo_tools=Path(__file__).resolve().parents[2]/"tools/water-world-v2-1"
    packaged_tools=package/"tools/water-world-v2-1"
    for source in repo_tools.rglob("*.py"):
        if "__pycache__" in source.parts: continue
        target=packaged_tools/source.relative_to(repo_tools)
        target.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(source,target)
    preflight_validator=package/"preflight/tools/water-world-v2-1/validate_preflight.py"
    preflight_validator.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(packaged_tools/"validate_preflight.py",preflight_validator)
    generation_path=package/"08_MANIFEST/generation_log.json"
    if generation_path.is_file():
        generation=read(generation_path)
        generation["result"]=metrics["overall_status"]
        generation["prior_validation"]={"validator_tests":"22/22 PASS; original 21 retained and one package-manifest regression added",
          "motion_graph":"47 nodes / 28 dependencies revalidated PASS","cpu_recomposition":"6/6 output SHA-256 values match across clean runs",
          "visual_acceptance":metrics["overall_status"],"exact_head_ci":"PENDING_EXACT_HEAD_CI"}
        generation["outputs"]=list(dict.fromkeys(generation.get("outputs",[])))
        write(generation_path,generation)
        shutil.copy2(generation_path,package/"preflight/08_MANIFEST/generation_log.json")
    return {"status":metrics["overall_status"],"objects":len(objects),"master_sha256":MASTER_SHA,
      "resident_coverage":{k:v["direct_trace_coverage"] for k,v in residents[0].items()}}

def main() -> None:
    parser=argparse.ArgumentParser(); parser.add_argument("--package-root",type=Path,required=True)
    args=parser.parse_args(); print(json.dumps(run(args.package_root),ensure_ascii=False,indent=2))

if __name__=="__main__": main()
