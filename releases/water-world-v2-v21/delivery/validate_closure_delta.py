#!/usr/bin/env python3
"""Validate only V2.1 closure deltas; prior V2/Validator/Motion gates are carried forward."""
from __future__ import annotations
import hashlib, json, math, sys, zipfile
from pathlib import Path
from PIL import Image

PKG=Path(__file__).resolve().parents[2]
ZIP=PKG.parent/"WATER_WORLD_V2_1_ASSETS.zip"
MASTER=PKG/"inputs/master/奇幻水晶球中的水下生态世界.png"
EXPECTED="fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589"
ARTIFACTS=["01_ANALYSIS/master_sphere_geometry.json","01_ANALYSIS/master_fish_geometry.json","01_ANALYSIS/master_resident_geometry.json",
"01_ANALYSIS/master_ecology_geometry.json","01_ANALYSIS/master_occlusion_graph.json","03_STATIC_RECOMPOSITION/static_recomposition.png",
"03_STATIC_RECOMPOSITION/master_reference.png","03_STATIC_RECOMPOSITION/master_overlay.png","03_STATIC_RECOMPOSITION/difference_heatmap.png",
"03_STATIC_RECOMPOSITION/region_comparison.png","03_STATIC_RECOMPOSITION/visual_metrics.json"]
def sh(b):return hashlib.sha256(b).hexdigest()
def load(p):return json.loads(p.read_text(encoding="utf-8-sig"))
checks=[]
raw=MASTER.read_bytes() if MASTER.is_file() else b""
sha=sh(raw) if raw else None
checks.append({"check":"master_byte_identity","status":"PASS" if sha==EXPECTED else "FAIL","detail":{"bytes":len(raw),"sha256":sha}})
if raw:
 try:
  with Image.open(MASTER) as im: im.verify()
  im=Image.open(MASTER); good=im.size==(1254,1254) and im.mode=="RGB"
  checks.append({"check":"master_png_decode_metadata","status":"PASS" if good else "FAIL","detail":{"dimensions":im.size,"mode":im.mode}})
 except Exception as e:checks.append({"check":"master_png_decode_metadata","status":"FAIL","detail":str(e)})
else: checks.append({"check":"master_png_decode_metadata","status":"FAIL","detail":"missing"})
for rel in ARTIFACTS:
 p=PKG/rel
 if not p.is_file() or p.stat().st_size==0:
  checks.append({"check":"artifact:"+rel,"status":"FAIL","detail":"missing or empty"});continue
 try:
  if p.suffix==".png":
   im=Image.open(p); im.load(); good=im.size==(1254,1254) if "region_comparison" not in rel else True
  elif p.suffix==".json":
   data=load(p); good=(data.get("master_sha256")==EXPECTED or data.get("source_sha256")==EXPECTED)
  else: good=True
  checks.append({"check":"artifact:"+rel,"status":"PASS" if good else "FAIL","detail":{"bytes":p.stat().st_size}})
 except Exception as e:checks.append({"check":"artifact:"+rel,"status":"FAIL","detail":str(e)})
metrics_path=PKG/"03_STATIC_RECOMPOSITION/visual_metrics.json"
visual={}
try:
 visual=load(metrics_path); metrics=visual["metrics"]
 limits={"sphere_iou":(.98,"min"),"sphere_center_error_over_diameter":(.005,"max"),"sphere_diameter_error":(.01,"max"),
 "fish_iou":(.90,"min"),"fish_center_error_over_canvas":(.01,"max"),"resident_bidirectional_chamfer_over_radius":(.015,"max"),
 "resident_width_error":(.05,"max"),"plant_iou":(.85,"min"),"rock_iou":(.85,"min")}
 failures=[]
 for key,(limit,direction) in limits.items():
  val=metrics[key]; vals=list(val.values()) if isinstance(val,dict) else [val]
  if direction=="min" and min(vals)<limit: failures.append(key)
  if direction=="max" and max(vals)>limit: failures.append(key)
 checks.append({"check":"visual_thresholds","status":"PASS" if not failures else "FAIL","detail":{"failed":failures,"measured_gate_status":visual.get("gate_status")}})
except Exception as e:
 failures=["visual_metrics_invalid"]
 checks.append({"check":"visual_thresholds","status":"FAIL","detail":str(e)})
# Verify copied V2 files against the already-audited extracted source tree, not by repeating archive/image audits.
source=Path("/workspace/WATER_WORLD_WORK/V2_EXTRACTED/full/WATER_WORLD_V2")
copy=PKG/"02_CALIBRATED_ASSETS/V2_ORIGINAL"
src_files=sorted(p for p in source.rglob("*") if p.is_file()) if source.is_dir() else []
copy_files=sorted(p for p in copy.rglob("*") if p.is_file()) if copy.is_dir() else []
copy_fail=[]
if len(src_files)!=65 or len(copy_files)!=65:copy_fail.append(f"counts:{len(src_files)}:{len(copy_files)}")
for src in src_files:
 dst=copy/src.relative_to(source)
 if not dst.is_file() or sh(src.read_bytes())!=sh(dst.read_bytes()):copy_fail.append(str(src.relative_to(source)))
checks.append({"check":"inherited_v2_file_copy_identity","status":"PASS" if not copy_fail else "FAIL","detail":{"files":len(copy_files),"mismatches":copy_fail}})
# Full V2.1 archive: only package manifest members are checked here; source V2 archive audit is carried forward.
zip_errors=[]; zip_summary={}
if not ZIP.is_file(): zip_errors.append("FINAL_ZIP_MISSING")
else:
 try:
  with zipfile.ZipFile(ZIP) as z:
   bad=z.testzip()
   if bad:zip_errors.append("ZIP_CRC:"+bad)
   names=set(z.namelist()); mn=next((n for n in ("WATER_WORLD_V2_1/08_MANIFEST/sha256_manifest.txt","08_MANIFEST/sha256_manifest.txt") if n in names),None)
   if not mn:zip_errors.append("MANIFEST_MISSING")
   else:
    for line in z.read(mn).decode("utf-8-sig").splitlines():
     parts=line.split(None,1)
     if len(parts)!=2:continue
     expected,member=parts[0],parts[1].strip().lstrip("*")
     if member not in names:zip_errors.append("MEMBER_MISSING:"+member)
     elif sh(z.read(member))!=expected:zip_errors.append("HASH_MISMATCH:"+member)
   missing=[p for p in ARTIFACTS if f"WATER_WORLD_V2_1/{p}" not in names]
   if missing:zip_errors.append("CLOSURE_MEMBERS_MISSING:"+repr(missing))
   zip_summary={"member_count":len(names),"crc":"PASS" if not bad else "FAIL","manifest":mn}
 except Exception as e:zip_errors.append(repr(e))
checks.append({"check":"final_v2_1_zip","status":"PASS" if not zip_errors else "FAIL","detail":{"summary":zip_summary,"errors":zip_errors}})
# Prior gates are explicitly carried forward because user requested no repeat.
checks += [
 {"check":"prior_v2_archive_audit","status":"CARRIED_FORWARD_PASS","detail":{"sha256":"6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095","members":65,"embedded_hashes":"63/63","source_commit":"d53492d055c69af346bf188d4fe3c07bc8c220c7"}},
 {"check":"prior_validator_regression","status":"CARRIED_FORWARD_PASS","detail":{"passed":21,"total":21,"rerun":False}},
 {"check":"prior_motion_graph","status":"CARRIED_FORWARD_PASS","detail":{"elements":47,"nodes":47,"dependencies":28,"rerun":False}},
]
fail=[c for c in checks if c["status"]=="FAIL"]
visual_fail=[c for c in checks if c["check"]=="visual_thresholds" and c["status"]=="FAIL"]
status="WORK1_5_VALIDATION_FAILED" if fail else ("WORK1_5_CLOSED_WITH_DECLARED_MOTION_GAPS" if True else "WORK1_5_CLOSED")
# A valid package may still fail the visual gate; that is the explicit requested final state, never a source blocker.
if not fail and visual_fail:status="WORK1_5_VALIDATION_FAILED"
result={"schema_version":"water-world-v2-1-delta-validation/1.0","validation_scope":"master/static/package closure delta only",
 "work1_5_status":status,"master_source_status":"MASTER_SOURCE_READY" if sha==EXPECTED else "WORK1_5_VALIDATION_FAILED",
 "visual_acceptance_status":"PASS" if not visual_fail else "FAIL","final_zip_status":"PASS" if not zip_errors else "FAIL",
 "prior_gate_policy":"V2 archive integrity, 21/21 tests, and 47/28 Motion Graph results carried forward without rerun as instructed.",
 "checks":checks,"visual_metrics_gate_failures":failures if 'failures' in locals() else ["visual_metrics_invalid"],"exit_code":20 if fail else 0}
(PKG/"06_VALIDATION/validation_manifest.json").write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print(json.dumps(result,ensure_ascii=False,indent=2))
sys.exit(result["exit_code"])
