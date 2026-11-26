"""Emit the prototype's explicit draw sequence; unfinished optical passes stay declared."""
import hashlib,json,zipfile,math
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
   d['shell_side']=1 if oid=='sphere_back' else -1 if oid=='sphere_front' else 0
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
  sphere=json.loads((ROOT/'releases/water-world-v2-v21/delivery/master_sphere_geometry.json').read_text())
  center=sphere['center_px']; radius=sphere['radius_px']*.94
  vertices=[[center[0],center[1],0,0,0,1,center[0]/1254,center[1]/1254]]
  for i in range(129):
   a=i/128*2*math.pi; x=center[0]+radius*math.cos(a);y=center[1]+radius*math.sin(a)
   vertices.append([x,y,0,0,0,1,x/1254,y/1254])
  watermesh='02_SUPPLEMENTED_ASSETS/geometry/water_disc.json'
  (OUT/watermesh).write_text(json.dumps(dict(vertices=vertices,indices=[j for i in range(128) for j in (0,i+1,i+2)]))+'\n')
  add('water',watermesh,'water','04_WATER/waterbed.png',depth=300)
  for b in json.loads((OUT/'03_SCENE_GRAPH/ecology_bindings.json').read_text())['bindings']:
   add(b['object_id'],b['mesh_path'],b['material'],b['source_asset'],depth=200-b['depth_order'])
  add('sphere_front','02_SUPPLEMENTED_ASSETS/geometry/sphere_mesh.json','glass',depth=-450)
  bubble_config=json.loads(z.read('WATER_WORLD_V2/09_BUBBLES/bubble_placements.json'))
  for bubble in bubble_config['bubbles']:
   cx,cy=[v*1254 for v in bubble['center']];radius=bubble['radius_d']*1254
   mesh=f"02_SUPPLEMENTED_ASSETS/geometry/{bubble['id']}_quad.json"
   verts=[[cx+(u*2-1)*radius,cy+(v*2-1)*radius,0,0,0,1,u,v] for u,v in [(0,0),(1,0),(0,1),(1,1)]]
   (OUT/mesh).write_text(json.dumps(dict(vertices=verts,indices=[0,1,2,2,1,3]))+'\n')
   add(bubble['id'],mesh,'bubble','09_BUBBLES/bubble_sprite.png',depth=-600,ambient=1)
  # Do not claim missing water/refraction/bubble passes have been rendered.
 result=dict(canvas_size=1254,time_seconds=0,draws=draws,status='PARTIAL_PROTOTYPE',missing_passes=['bokeh','front_back_resident_split','glass_refraction','bloom','water_normal_distortion','caustics'],gpu_validation='NOT_RUN')
 (OUT/'03_SCENE_GRAPH/draw_bindings.json').write_text(json.dumps(result,indent=2)+'\n')
 assert len(draws)==32
 print('DRAW_BINDINGS=32; PARTIAL_PROTOTYPE; GPU NOT_RUN')
if __name__=='__main__':run()
