"""Emit one candidate static scene and shared material inputs; never imply visual acceptance."""
import hashlib
import json
from pathlib import Path
import zipfile
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1'
LAYERS=['background','resident_back','sphere_back_glass','water','plants','rocks','fish','glass_front','bubbles','resident_front']
def write(rel,data):
 p=OUT/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(data,indent=2)+'\n')
def run():
 materials={}
 for name in ['glass','water','fish','resident','plant','rock','background','bubble']:
  materials[name]=dict(base_color=[1,1,1],alpha=1,roughness=.45,metallic=0,emission=0,normal_strength=1,fresnel=0,refraction_strength=0)
 materials['glass'].update(alpha=.16,roughness=.08,fresnel=.7,refraction_strength=.008)
 materials['water'].update(alpha=.85,roughness=.16,refraction_strength=.004)
 materials['resident'].update(emission=1.6,roughness=.12)
 write('02_SUPPLEMENTED_ASSETS/materials/materials.json',materials)
 write('02_SUPPLEMENTED_ASSETS/lighting/lighting.json',dict(ambient=[.22,.35,.35],key=dict(direction=[-.5,-.7,.5],color=[1,.85,.55],intensity=1),rim=dict(color=[.2,.8,1],intensity=.4),bloom=dict(enabled=True,threshold=1,strength=.2),volumetric=dict(enabled=False),time_seconds=0))
 scene=json.loads((ROOT/'releases/water-world-v2-v21/preflight/03_SCENE_GRAPH/scene_graph_v2_1.json').read_text())
 # The new prototype retains candidate transforms while historical confidence remains visible.
 candidates=scene.get('objects',[])
 if not candidates:
  raise ValueError('Missing calibrated object entries')
 objs=[]
 for item in candidates:
  oid=item['object_id']; kind=item['object_type']
  if kind=='resident':
   item=dict(item,candidate_transform=dict(kind='IMMUTABLE_SVG_PIXEL_GEOMETRY',matrix=[1,0,0,0,1,0,0,0,1]),confidence='SOURCE_PATH_REUSED_LAYOUT_NOT_VERIFIED')
  layer={'fish':'fish','plant':'plants','rock':'rocks','resident':'resident_back'}[kind]
  objs.append(dict(object_id=oid,layer=layer,source_resource_path=item.get('source_resource_path'),source_sha256=item.get('source_sha256'),transform=item.get('candidate_transform'),anchor=item.get('anchor'),depth_order=item.get('depth_order'),material=kind,confidence=item.get('confidence'),motion_binding_id=oid+'_STATIC_T0',animation_enabled=False,depth_status='CANDIDATE_REQUIRES_GPU_REVIEW'))
 assert len(objs)==14 and len({o['object_id'] for o in objs})==14
 # Back/front ribbon segment membership must be reviewed in rendered context, not invented from color.
 passes=[dict(layer_id=l,order=i,depth_test=True,depth_write=l=='background',blend='SRC_ALPHA,ONE_MINUS_SRC_ALPHA',status='CANDIDATE') for i,l in enumerate(LAYERS)]
 write('03_SCENE_GRAPH/scene_graph.json',dict(schema='water-world-assembly/1',camera=dict(canvas=[1254,1254],origin='TOP_LEFT',projection='IMAGE_SPACE_ORTHOGRAPHIC',time=0),layers=LAYERS,objects=objs,sphere_mesh='02_SUPPLEMENTED_ASSETS/geometry/sphere_mesh.json',status='CANDIDATE_NOT_GPU_VERIFIED'))
 write('03_SCENE_GRAPH/render_pass_mapping.json',dict(passes=passes,authoritative_order=LAYERS,front_back_ribbon_split='PENDING_GPU_REVIEW'))
 write('03_SCENE_GRAPH/transforms.json',{o['object_id']:o['transform'] for o in objs})
 write('03_SCENE_GRAPH/occlusion.json',dict(status='CANDIDATE',source_contract='Historical master_occlusion_graph.json',unresolved=['Resident front/back segment split','transparent ecology/glass overlap']))
 print('SCENE_CANDIDATE: 14 objects, 10 retained layers, 8 shared materials; GPU NOT_RUN')
if __name__=='__main__':run()
