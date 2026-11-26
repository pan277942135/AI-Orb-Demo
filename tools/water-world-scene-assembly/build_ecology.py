"""Bind original fish mesh UVs and aspect-preserving ecology geometry into pixel space."""
import hashlib
import io
import json
import math
from pathlib import Path
import zipfile
from PIL import Image
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1'

def run():
 scene=json.loads((OUT/'03_SCENE_GRAPH/scene_graph.json').read_text()); bindings=[]
 with zipfile.ZipFile(ROOT/'releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip') as z:
  for o in scene['objects']:
   if o['material']=='resident': continue
   rel=o['source_resource_path'].removeprefix('02_CALIBRATED_ASSETS/V2_ORIGINAL/')
   raw=z.read('WATER_WORLD_V2/'+rel); assert hashlib.sha256(raw).hexdigest()==o['source_sha256']
   with Image.open(io.BytesIO(raw)) as im: w,h=im.size
   t=o['transform']; box=t['source_alpha_bbox']; cx=(box[0]+box[2])/2; cy=(box[1]+box[3])/2
   if o['material']=='fish':
    meshrel=f"05_FISH/meshes/fish_{o['object_id'][-2:]}_mesh.json"
    src=z.read('WATER_WORLD_V2/'+meshrel); m=json.loads(src)
    uv=m['vertices_uv']; triangles=m['triangles']; scale=t['scale_px_per_source_pixel_xy']; center=t['center_px']; angle=math.radians(t['rotation_degrees'])
   else:
    src=None; uv=[[0,0],[1,0],[0,1],[1,1]];triangles=[[0,1,2],[2,1,3]]
    target=t['target_bbox_px'];scale_value=min((target[2]-target[0])/(box[2]-box[0]),(target[3]-target[1])/(box[3]-box[1]))
    scale=[scale_value,scale_value];center=[(target[0]+target[2])/2,(target[1]+target[3])/2];angle=0
   vertices=[]
   for u,v in uv:
    x=(u*w-cx)*scale[0]; y=(v*h-cy)*scale[1]
    vertices.append([center[0]+math.cos(angle)*x-math.sin(angle)*y,center[1]+math.sin(angle)*x+math.cos(angle)*y,0,0,0,1,u,v])
   indices=[i for tri in triangles for i in tri] if isinstance(triangles[0],list) else triangles
   assert all(0<=i<len(vertices) for i in indices)
   path='02_SUPPLEMENTED_ASSETS/geometry/'+o['object_id']+'_mesh.json'
   data=dict(vertices=vertices,indices=indices,source_asset_sha256=o['source_sha256'],source_mesh_sha256=hashlib.sha256(src).hexdigest() if src else None,method='SOURCE_MESH_UV_WITH_PIXEL_TRANSFORM' if src else 'ASPECT_PRESERVING_RGBA_PLANE',scale_xy=scale,center_px=center,rotation_radians=angle)
   (OUT/path).write_text(json.dumps(data,separators=(',',':'))+'\n')
   bindings.append(dict(object_id=o['object_id'],mesh_path=path,source_asset=rel,source_sha256=o['source_sha256'],layer=o['layer'],material=o['material'],depth_order=o['depth_order']))
 (OUT/'03_SCENE_GRAPH/ecology_bindings.json').write_text(json.dumps(dict(bindings=bindings,gpu_validation='NOT_RUN'),indent=2)+'\n')
 assert len(bindings)==9
 print('ECOLOGY_GEOMETRY_PASS: 4 original fish meshes, 2 plants, 3 rocks; GPU NOT_RUN')
if __name__=='__main__':run()
