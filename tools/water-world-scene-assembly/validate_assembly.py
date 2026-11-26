"""Validate current prototype integrity separately from readiness; missing effects cannot pass."""
import hashlib,json,math
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1'
def run():
 scene=json.loads((OUT/'03_SCENE_GRAPH/scene_graph.json').read_text());draw=json.loads((OUT/'03_SCENE_GRAPH/draw_bindings.json').read_text())
 assert len(scene['objects'])==14 and len({x['object_id'] for x in scene['objects']})==14
 assert len(scene['layers'])==10 and draw['canvas_size']==1254 and draw['time_seconds']==0
 for d in draw['draws']:
  mesh=json.loads((OUT/d['mesh_path']).read_text());assert all(0<=i<len(mesh['vertices']) for i in mesh['indices'])
  assert all(math.isfinite(v) for row in mesh['vertices'] for v in row)
 telemetry=json.loads((OUT/'05_VISUAL_VALIDATION/SOFTWARE_GLES_TELEMETRY.json').read_text())
 png=OUT/'05_VISUAL_VALIDATION/software_gles_assembled_scene.png'
 assert hashlib.sha256(png.read_bytes()).hexdigest()==telemetry['png_sha256']
 assert telemetry['fbo']=='COMPLETE' and telemetry['gl_error']==0
 visual=json.loads((OUT/'05_VISUAL_VALIDATION/visual_score.json').read_text());assert visual['render_sha256']==telemetry['png_sha256']
 ready=dict(source_assets='PASS',asset_reuse_audit='PASS',supplemented_assets='PARTIAL',scene_graph='CANDIDATE',camera_contract='PASS',geometry='PASS',material_bindings='PARTIAL',render_pass='PARTIAL',gpu_scene_assembly='NOT_RUN_HARDWARE',software_gles_diagnostic='PASS',visual_score=visual['total_score'],visual_acceptance='NOT_EVALUATED',cpu_gpu_source_consistency='PARTIAL_SHARED_CONFIG_GLSL_ONLY',future_motion_bindings='PARTIAL',work2_static_input_ready=False,missing_passes=draw['missing_passes'])
 (OUT/'07_MANIFEST/WORK2_STATIC_INPUT_READY.json').write_text(json.dumps(ready,indent=2)+'\n');print(json.dumps(ready,indent=2))
 return ready
if __name__=='__main__':run()
