"""Derive indexed GPU meshes from immutable V2 SVG paths and measured sphere layout."""
import hashlib
import json
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT/'releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1/02_SUPPLEMENTED_ASSETS/geometry'

def emit(name, data):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT/name).write_text(json.dumps(data, separators=(',', ':'))+'\n')

def sphere(center, radius, rings=24, sectors=48):
    vertices=[]; indices=[]
    for j in range(rings+1):
        v=j/rings; a=math.pi*v
        for i in range(sectors+1):
            u=i/sectors; b=2*math.pi*u
            n=[math.sin(a)*math.cos(b), math.cos(a), math.sin(a)*math.sin(b)]
            vertices.append([center[0]+radius*n[0],center[1]-radius*n[1],radius*n[2],*n,u,v])
    for j in range(rings):
        for i in range(sectors):
            a=j*(sectors+1)+i; b=a+sectors+1
            indices.extend([a,b,a+1,a+1,b,b+1])
    return dict(vertex_layout=['x_px','y_px','z_px','nx','ny','nz','u','v'],vertices=vertices,indices=indices,primitive='TRIANGLES')

def ribbon(points,width,steps=96):
    verts=[]; inds=[]
    for i in range(steps+1):
        t=i/steps; q=1-t
        pos=[q**3*points[0][k]+3*q*q*t*points[1][k]+3*q*t*t*points[2][k]+t**3*points[3][k] for k in range(2)]
        d=[3*q*q*(points[1][k]-points[0][k])+6*q*t*(points[2][k]-points[1][k])+3*t*t*(points[3][k]-points[2][k]) for k in range(2)]
        length=math.hypot(*d)
        if length<1e-8: raise ValueError('Degenerate ribbon tangent')
        nx,ny=-d[1]/length,d[0]/length
        for side in (-1,1): verts.append([pos[0]+side*nx*width/2,pos[1]+side*ny*width/2,0,0,0,1,t,(side+1)/2])
        if i<steps:
            a=2*i; inds.extend([a,a+1,a+2,a+1,a+3,a+2])
    return dict(vertex_layout=['x_px','y_px','z_px','nx','ny','nz','u','v'],vertices=verts,indices=inds,primitive='TRIANGLES')

def run():
    geometry=json.loads((ROOT/'releases/water-world-v2-v21/delivery/master_sphere_geometry.json').read_text())
    s=geometry.get('sphere',geometry)
    mesh=sphere(s['center_px'],s['radius_px'])
    mesh.update(object_id='sphere',source='V2.1 measured layout; procedural UV sphere',source_sha256=hashlib.sha256((ROOT/'releases/water-world-v2-v21/delivery/master_sphere_geometry.json').read_bytes()).hexdigest())
    emit('sphere_mesh.json',mesh)
    with zipfile.ZipFile(ROOT/'releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip') as z:
        raw=z.read('WATER_WORLD_V2/02_RESIDENT/residents.svg')
        ids=set()
        for p in ET.fromstring(raw):
            if p.tag.split('}')[-1]!='path': continue
            nums=[float(x) for x in re.findall(r'-?\d+(?:\.\d+)?',p.attrib['d'])]
            if len(nums)!=8: raise ValueError('Expected one immutable cubic per source segment')
            pts=list(zip(nums[::2],nums[1::2])); rid=p.attrib['id']; ids.add(rid.split('_')[0])
            result=ribbon(pts,float(p.attrib['stroke-width']))
            result.update(object_id=rid.split('_')[0],segment_id=rid,source_svg_path=p.attrib['d'],source_sha256=hashlib.sha256(raw).hexdigest(),stroke_width_px=float(p.attrib['stroke-width']),color=p.attrib['stroke'],alpha=float(p.attrib['opacity']),depth_binding='SCENE_GRAPH_REQUIRED')
            emit(rid+'_mesh.json',result)
        assert ids=={'G01','G02','A01','A02','C01'}
    for path in OUT.glob('*_mesh.json'):
        d=json.loads(path.read_text()); count=len(d['vertices'])
        assert all(0<=i<count for i in d['indices'])
        assert all(math.isfinite(v) for row in d['vertices'] for v in row)
        assert all(abs(sum(v*v for v in row[3:6])-1)<1e-6 for row in d['vertices'])
    print('GEOMETRY_VALIDATED: sphere + 10 immutable SVG segments; GPU NOT_RUN')

if __name__=='__main__': run()
