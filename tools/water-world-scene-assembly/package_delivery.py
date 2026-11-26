"""Build a deterministic diagnostic delivery, preserving original artwork and historical ZIPs."""
import hashlib,json,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'releases/water-world-v2-v21/work1_5_scene_assembly'
PKG=BASE/'WATER_WORLD_V2_1'
def run():
 files={}
 for p in PKG.rglob('*'):
  if p.is_file():files[p.relative_to(PKG).as_posix()]=p.read_bytes()
 with zipfile.ZipFile(ROOT/'releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip') as z:
  for n in z.namelist():
   if not n.endswith('/'):files['01_REUSED_ASSETS/'+n.removeprefix('WATER_WORLD_V2/')]=z.read(n)
 files['05_VISUAL_VALIDATION/master.png']=(ROOT/'releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png').read_bytes()
 for directory,prefix in [(ROOT/'app/src/main/java/com/example/waterworld','04_RENDERER/android'),(ROOT/'tools/water-world-scene-assembly','04_RENDERER/tools')]:
  for p in directory.rglob('*'):
   if p.is_file() and '__pycache__' not in p.parts:files[prefix+'/'+p.relative_to(directory).as_posix()]=p.read_bytes()
 files['07_MANIFEST/sha256_manifest.txt']=(''.join(hashlib.sha256(v).hexdigest()+'  WATER_WORLD_V2_1/'+k+'\n' for k,v in sorted(files.items()))).encode()
 out=BASE/'WATER_WORLD_V2_1_SCENE_ASSEMBLY_WORK2_INPUT.zip'
 with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
  for name,raw in sorted(files.items()):
   info=zipfile.ZipInfo('WATER_WORLD_V2_1/'+name,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,raw)
 with zipfile.ZipFile(out) as z:
  assert z.testzip() is None
  for line in files['07_MANIFEST/sha256_manifest.txt'].decode().splitlines():
   sha,name=line.split(None,1);assert hashlib.sha256(z.read(name.strip())).hexdigest()==sha
 result=dict(bytes=out.stat().st_size,sha256=hashlib.sha256(out.read_bytes()).hexdigest(),members=len(files),internal_sha_entries=len(files)-1,crc='PASS',readiness=False)
 (BASE/'PACKAGE_FINGERPRINT.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':run()
