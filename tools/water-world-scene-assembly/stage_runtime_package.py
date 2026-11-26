"""Stage a fresh runnable prototype directory; never overwrite historical deliveries."""
import argparse,hashlib,json,shutil,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1'
def run(target):
 if target.exists(): raise ValueError('Target must be new; preserve existing artifacts')
 shutil.copytree(SOURCE,target)
 with zipfile.ZipFile(ROOT/'releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip') as z:
  for member in z.infolist():
   if member.is_dir():continue
   rel=member.filename.removeprefix('WATER_WORLD_V2/')
   dst=target/'01_REUSED_ASSETS'/rel
   dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes(z.read(member))
 bindings=json.loads((target/'03_SCENE_GRAPH/draw_bindings.json').read_text())
 for row in bindings['draws']:
  assert (target/row['mesh_path']).is_file()
  if row['texture_path']:
   assert hashlib.sha256((target/row['texture_path']).read_bytes()).hexdigest()==row['texture_sha256']
 sums=[]
 for path in sorted(target.rglob('*')):
  if path.is_file():sums.append(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.relative_to(target).as_posix())
 (target/'07_MANIFEST/runtime_sha256.txt').write_text('\n'.join(sums)+'\n')
 print(json.dumps(dict(draw_count=len(bindings['draws']),source_assets=65,integrity='PASS',status=bindings['status'],gpu='NOT_RUN')))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--target',type=Path,required=True);run(p.parse_args().target)
