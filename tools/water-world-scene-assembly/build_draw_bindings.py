"""Emit the prototype's explicit draw sequence; unfinished optical passes stay declared."""
import hashlib,json,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1'
IDENTITY=[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]
def run():
 draws=[]
 with zipfile.ZipFile(ROOT/'releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip') as z:
  def add(oid,mesh,material,texture=None,depth=0,tint=None,ambient=.35):
   model=IDENTITY.copy();model[14]=depth
   raw=z.read('WATER_WORLD_V2/'+texture) if texture else None
   d=dict(draw_id=oid,mesh_path=mesh,material=material,texture_path='01_REUSED_ASSETS/'+texture if texture else None,texture_sha256=hashlib.sha256(raw).hexdigest() if raw else None,model=model,depth_write=oid=='background',ambient=ambient)
   if tint is not None:d['tint']=tint
   draws.append(d)
  # Full canvas environment quad: source artwork remains an independent background.
  quad=dict(vertices=[[0,0,0,0,0,1,0,0],[1254,0,0,0,0,1,1,0],[0,1254,0,0,0,1,0,1],[1254,1254,0,0,0,1,1,1]],indices=[0,1,2,2,1,3])
  q='02_SUPPLEMENTED_ASSETS/geometry/environment_quad.json';(OUT/q).write_text(json.dumps(quad)+'\n')
  add('background',q,'background','01_BACKGROUND/background.png',1900,ambient=1)
  for rid in ['G01','G02','A01','A02','C01']:
   for segment in [1,2]:
    mesh=f'02_SUPPLEMENTED_ASSETS/geometry/{rid}_{segment}_mesh.json'; data=json.loads((OUT/mesh).read_text());c=data['color'].lstrip('#');color=[int(c[i:i+2],16)/255 for i in (0,2,4)]+[data['alpha']]
    add(f'{rid}_{segment}',mesh,'resident',depth=500,tint=color)
  add('sphere_back','02_SUPPLEMENTED_ASSETS/geometry/sphere_mesh.json','glass',depth=450)
  for b in json.loads((OUT/'03_SCENE_GRAPH/ecology_bindings.json').read_text())['bindings']:
   add(b['object_id'],b['mesh_path'],b['material'],b['source_asset'],depth=200-b['depth_order'])
  add('sphere_front','02_SUPPLEMENTED_ASSETS/geometry/sphere_mesh.json','glass',depth=-450)
  # Do not claim missing water/refraction/bubble passes have been rendered.
 result=dict(canvas_size=1254,time_seconds=0,draws=draws,status='PARTIAL_PROTOTYPE',missing_passes=['water','bubbles','bokeh','front_back_resident_split','glass_refraction','bloom'],gpu_validation='NOT_RUN')
 (OUT/'03_SCENE_GRAPH/draw_bindings.json').write_text(json.dumps(result,indent=2)+'\n')
 assert len(draws)==22
 print('DRAW_BINDINGS=22; PARTIAL_PROTOTYPE; GPU NOT_RUN')
if __name__=='__main__':run()
