#!/usr/bin/env python3
"""Validate V2.1 source, object contracts, metrics and final package ZIP."""
from __future__ import annotations
import argparse, hashlib, json, math, re, sys, zipfile
from pathlib import Path
from PIL import Image

MASTER_SHA="fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589"
MASTER_REL="inputs/master/奇幻水晶球中的水下生态世界.png"
EXPECTED={"sphere_iou":("min",.98),"sphere_center_error_over_diameter":("max",.005),"sphere_diameter_error":("max",.01),
 "fish_iou":("min",.90),"fish_center_error_over_canvas":("max",.01),"resident_bidirectional_chamfer_over_radius":("max",.015),
 "resident_width_error":("max",.05),"plant_iou":("min",.85),"rock_iou":("min",.85)}
OBJECTS={"fish":[f"WW_FISH_{n:02d}" for n in range(1,5)],"resident":["G01","G02","A01","A02","C01"],
 "plant":["plant_left","plant_right"],"rock":["rock_01","rock_02","rock_03"]}
REQUIRED_MASKS=("source_full_mask.png","source_visible_mask.png","occlusion_mask.png","confidence_mask.png","boundary_overlay.png","source_roi.json","segmentation_report.json")

def sha_bytes(data:bytes)->str:return hashlib.sha256(data).hexdigest()
def load(path:Path):return json.loads(path.read_text(encoding="utf-8-sig"))
def _numbers(value):return list(value.values()) if isinstance(value,dict) else [value]

def threshold_failures(metrics:dict)->list[str]:
    failures=[]
    if metrics.get("thresholds") is None:return ["thresholds_missing"]
    for name,(direction,target) in EXPECTED.items():
        frozen=metrics["thresholds"].get(name)
        expected={direction:target}
        if frozen!=expected:failures.append(f"threshold_changed:{name}")
        values=metrics.get("metrics",{}).get(name)
        if values is None:failures.append(f"metric_missing:{name}");continue
        vals=_numbers(values)
        if not vals or any(v is None or not isinstance(v,(int,float)) or not math.isfinite(v) for v in vals):
            failures.append(f"metric_unmeasured:{name}");continue
        if direction=="min" and any(v<target for v in vals):failures.append(name)
        if direction=="max" and any(v>target for v in vals):failures.append(name)
    return failures

def object_contract_errors(metrics:dict,mapping:dict,scene:dict)->list[str]:
    errors=[]; expected=[x for rows in OBJECTS.values() for x in rows]
    ids=[o.get("object_id") for o in mapping.get("objects",[])]
    scene_ids=[o.get("object_id") for o in scene.get("objects",[])]
    if len(ids)!=14 or set(ids)!=set(expected) or len(set(ids))!=14:errors.append("asset_mapping_object_set")
    if len(scene_ids)!=14 or set(scene_ids)!=set(expected) or len(set(scene_ids))!=14:errors.append("scene_graph_object_set")
    per=metrics.get("per_object",{})
    if set(per)!=set(expected):errors.append("per_object_metric_set")
    for oid in expected:
        row=per.get(oid,{})
        if not row.get("status"):errors.append(f"object_status_missing:{oid}")
        entry=next((x for x in mapping.get("objects",[]) if x.get("object_id")==oid),{})
        if not entry.get("source_sha256"):errors.append(f"source_sha_missing:{oid}")
        gt=entry.get("ground_truth",{})
        if not gt.get("occlusion_mask") or not gt.get("confidence_mask"):errors.append(f"occlusion_or_confidence_missing:{oid}")
        if entry.get("master_region_id") is None:errors.append(f"master_region_missing:{oid}")
        if entry.get("candidate_transform") is None:errors.append(f"transform_missing:{oid}")
    camera=scene.get("static_camera",{})
    if camera.get("canvas_px")!=[1254,1254] or camera.get("projection")!="2D orthographic source-pixel registration":errors.append("static_camera_contract")
    fov=camera.get("physical_fov","UNRESOLVED")
    if not isinstance(fov,str) or not fov.startswith("UNRESOLVED"):errors.append("invented_physical_fov")
    if scene.get("calibration_status")!="PARTIAL_MEASURED_WITH_OBJECT_LEVEL_BLOCKERS":errors.append("calibration_status_missing")
    return errors

def master_layer_errors(metrics:dict)->list[str]:
    if metrics.get("comparison",{}).get("master_used_as_composition_layer") is not False:return ["master_used_as_composition_layer"]
    if metrics.get("comparison",{}).get("render_type")!="CPU_STATIC_RECOMPOSITION_FROM_INDEPENDENT_V2_ASSETS":return ["composition_source_not_independent_v2"]
    return []

def validate_package(package:Path,archive:Path,master:Path|None=None)->dict:
    checks=[]; failures=[]; visual=[]
    master=master or package/MASTER_REL
    raw=master.read_bytes() if master.is_file() else b""
    good=bool(raw) and sha_bytes(raw)==MASTER_SHA
    detail={"bytes":len(raw),"sha256":sha_bytes(raw) if raw else None}
    if raw:
        try:
            with Image.open(master) as im: im.verify()
            with Image.open(master) as im: detail.update({"dimensions":list(im.size),"mode":im.mode})
            good=good and detail["dimensions"]==[1254,1254] and detail["mode"]=="RGB"
        except Exception as exc:good=False;detail["decode_error"]=str(exc)
    checks.append({"check":"master_lock","status":"PASS" if good else "FAIL","detail":detail})
    if not good:failures.append("master_lock")
    metrics_path=package/"03_STATIC_RECOMPOSITION/visual_metrics.json"
    mapping_path=package/"08_MANIFEST/master_asset_mapping.json"
    scene_path=package/"03_SCENE_GRAPH/scene_graph_v2_1.json"
    try:
        metrics,mapping,scene=load(metrics_path),load(mapping_path),load(scene_path)
        visual=threshold_failures(metrics)
        structural=object_contract_errors(metrics,mapping,scene)+master_layer_errors(metrics)
        if metrics.get("master_sha256")!=MASTER_SHA:structural.append("metrics_master_sha")
        if mapping.get("master_sha256")!=MASTER_SHA:structural.append("mapping_master_sha")
        if metrics.get("overall_status") not in {"BLOCKED_GROUND_TRUTH_UNCERTAIN","WORK1_5_VALIDATION_FAILED"}:structural.append("false_visual_status")
        checks.append({"check":"frozen_visual_thresholds","status":"PASS" if not visual else "FAIL","detail":{"failures":visual,"gate_status":metrics.get("gate_status")}})
        checks.append({"check":"scene_and_object_contracts","status":"PASS" if not structural else "FAIL","detail":{"errors":structural}})
        if visual:failures.extend(["visual:"+x for x in visual])
        if structural:failures.extend(structural)
    except Exception as exc:
        failures.append("metrics_or_contract_read");checks.append({"check":"metrics_or_contract_read","status":"FAIL","detail":str(exc)})
        metrics,mapping,scene={},{},{}
    gt_root=package/"01_ANALYSIS/ground_truth/objects"
    gt_errors=[]
    for oid in [x for group in OBJECTS.values() for x in group]:
        root=gt_root/oid
        for name in REQUIRED_MASKS:
            path=root/name
            if not path.is_file():gt_errors.append(f"missing:{oid}/{name}");continue
            try:
                if path.suffix==".png":
                    with Image.open(path) as im:
                        im.load()
                        if im.size!=(1254,1254):gt_errors.append(f"dimensions:{oid}/{name}:{im.size}")
                elif path.name=="source_roi.json":load(path)
                elif path.name=="segmentation_report.json":
                    rep=load(path)
                    if rep.get("master_sha256")!=MASTER_SHA:gt_errors.append(f"master_sha:{oid}")
            except Exception as exc:gt_errors.append(f"decode:{oid}/{name}:{exc}")
    checks.append({"check":"ground_truth_masks_and_reports","status":"PASS" if not gt_errors else "FAIL","detail":{"object_count":14,"errors":gt_errors}})
    if gt_errors:failures.append("ground_truth_masks")
    source_copy=package/"02_CALIBRATED_ASSETS/V2_ORIGINAL"
    try:
        names=[p for p in source_copy.rglob("*") if p.is_file()]
        sums=source_copy/"11_MANIFEST/SHA256SUMS"
        errors=[];expected={}
        for line in sums.read_text(encoding="utf-8-sig").splitlines():
            m=re.match(r"^([0-9a-fA-F]{64})\s+[* ]?(.*)$",line.strip())
            if m:expected[m.group(2).strip()]=m.group(1).lower()
        verified=0
        for rel,digest in expected.items():
            path=source_copy/rel
            if not path.is_file() or sha_bytes(path.read_bytes())!=digest:errors.append(rel)
            else:verified+=1
        ok=len(names)==65 and len(expected)==63 and verified==63
        checks.append({"check":"original_v2_copy_identity","status":"PASS" if ok else "FAIL","detail":{"files":len(names),"embedded_hashes":len(expected),"verified":verified,"errors":errors}})
        if not ok:failures.append("original_v2_copy_identity")
    except Exception as exc:failures.append("original_v2_manifest");checks.append({"check":"original_v2_copy_identity","status":"FAIL","detail":str(exc)})
    motion_errors=[]
    try:
        motion=load(package/"04_MOTION/motion_element_registry.json");graph=load(package/"04_MOTION/motion_dependency_graph.json")
        if len(motion.get("elements",[]))!=47 or len(graph.get("nodes",[]))!=47 or len(graph.get("edges",[]))!=28:motion_errors.append("47_28_structure")
        for element in motion.get("elements",[]):
            for field in ("angular_velocity","phase_offset","amplitude","frequency","flow_velocity","deformation_rate"):
                if element.get(field) is not None:motion_errors.append(f"water_world_temporal_not_null:{element.get('element_id')}:{field}")
        if motion.get("source_constraints",{}).get("water_world_master_status")!="MASTER_SOURCE_READY":motion_errors.append("motion_master_state")
    except Exception as exc:motion_errors.append(str(exc))
    checks.append({"check":"motion_graph_47_28","status":"PASS" if not motion_errors else "FAIL","detail":{"errors":motion_errors}})
    if motion_errors:failures.append("motion_graph")
    stale=[]
    active=[package/"03_SCENE_GRAPH/scene_graph_v2_1.json",mapping_path,package/"04_MOTION/motion_element_registry.json",
      package/"04_MOTION/core_motion_contract.md",package/"07_GPU_CONTRACT/WORK2_GPU_STATIC_RECONSTRUCTION_EXECUTION_CONTRACT.md",
      package/"08_MANIFEST/asset_changelog.csv",package/"08_MANIFEST/source_lock_report.md",package/"FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md"]
    for path in active:
        if path.is_file():
            text=path.read_text(encoding="utf-8",errors="replace")
            for token in ("BLOCKED_MASTER_SOURCE","NOT_COMPARABLE_MASTER_BYTES_UNAVAILABLE","master_region_id=null","calibrated_transform=null"):
                if token in text:stale.append(f"{path.relative_to(package)}:{token}")
    checks.append({"check":"no_stale_source_unavailable_contracts","status":"PASS" if not stale else "FAIL","detail":{"stale":stale}})
    if stale:failures.append("stale_contracts")
    zip_errors=[];zip_detail={}
    try:
        with zipfile.ZipFile(archive) as z:
            bad=z.testzip(); names=set(z.namelist())
            if bad:zip_errors.append("crc:"+bad)
            manifest_name="WATER_WORLD_V2_1/08_MANIFEST/sha256_manifest.txt"
            if manifest_name not in names:zip_errors.append("manifest_missing")
            else:
                for line in z.read(manifest_name).decode("utf-8-sig").splitlines():
                    parts=line.split(None,1)
                    if len(parts)!=2:zip_errors.append("manifest_line_invalid");continue
                    expected,member=parts[0],parts[1].strip().lstrip("*")
                    if member not in names:zip_errors.append("member_missing:"+member)
                    elif sha_bytes(z.read(member))!=expected:zip_errors.append("sha_mismatch:"+member)
            zip_detail={"members":len(names),"crc":"PASS" if not bad else "FAIL","manifest":manifest_name}
    except Exception as exc:zip_errors.append(str(exc))
    checks.append({"check":"final_zip_crc_and_sha_manifest","status":"PASS" if not zip_errors else "FAIL","detail":{"summary":zip_detail,"errors":zip_errors}})
    if zip_errors:failures.append("final_zip_integrity")
    metric_failed=bool(visual)
    status="BLOCKED_GROUND_TRUTH_UNCERTAIN" if metrics.get("overall_status")=="BLOCKED_GROUND_TRUTH_UNCERTAIN" else "WORK1_5_VALIDATION_FAILED"
    result={"schema_version":"water-world-v2-1-closure-validation/2.0","master_source_status":"MASTER_SOURCE_READY" if good else "MASTER_SOURCE_INVALID",
      "work_status":status,"visual_acceptance_status":"PASS" if not metric_failed else "FAIL","package_integrity_status":"PASS" if not failures else "FAIL",
      "checks":checks,"visual_failures":visual,"failure_codes":failures,"exit_code":20 if failures or metric_failed else 0}
    out=package/"06_VALIDATION/validation_manifest.json";out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    return result

def main()->None:
    parser=argparse.ArgumentParser();parser.add_argument("--package-root",type=Path,required=True)
    parser.add_argument("--archive",type=Path,required=True);parser.add_argument("--master",type=Path)
    args=parser.parse_args();result=validate_package(args.package_root,args.archive,args.master)
    print(json.dumps(result,ensure_ascii=False,indent=2));sys.exit(result["exit_code"])
if __name__=="__main__":main()
