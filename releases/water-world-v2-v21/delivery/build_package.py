#!/usr/bin/env python3
"""Build the self-contained WATER_WORLD_V2_1_ASSETS.zip and SHA manifest."""
from pathlib import Path
import hashlib, os, zipfile
PKG=Path(__file__).resolve().parents[2]
OUT=PKG.parent/'WATER_WORLD_V2_1_ASSETS.zip'
EXCLUDE={'SHA256SUMS.txt','08_MANIFEST/sha256_manifest.txt'}
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
files=sorted(p for p in PKG.rglob('*') if p.is_file() and p.relative_to(PKG).as_posix() not in EXCLUDE)
lines=[f"{sha(p)}  WATER_WORLD_V2_1/{p.relative_to(PKG).as_posix()}" for p in files]
manifest='\n'.join(lines)+'\n'
(PKG/'08_MANIFEST/sha256_manifest.txt').write_text(manifest,encoding='utf-8')
(PKG/'SHA256SUMS.txt').write_text(manifest,encoding='utf-8')
# ZIP from the package parent to preserve a stable top-level folder.
tmp=OUT.with_suffix('.zip.part')
if tmp.exists(): tmp.unlink()
with zipfile.ZipFile(tmp,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(x for x in PKG.rglob('*') if x.is_file()):
        arc='WATER_WORLD_V2_1/'+p.relative_to(PKG).as_posix()
        z.write(p,arc)
os.replace(tmp,OUT)
# Verify CRC and every member recorded by the manifest.
with zipfile.ZipFile(OUT) as z:
    bad=z.testzip()
    if bad: raise SystemExit('ZIP_CRC_FAIL:'+bad)
    names=set(z.namelist())
    for line in manifest.splitlines():
        digest,member=line.split(None,1); member=member.strip()
        if member not in names or hashlib.sha256(z.read(member)).hexdigest()!=digest:
            raise SystemExit('ZIP_MEMBER_HASH_FAIL:'+member)
raw=OUT.read_bytes()
print(f"zip={OUT}\nbytes={len(raw)}\nsha256={hashlib.sha256(raw).hexdigest()}\nfiles={len(files)+2}\nmanifest_entries={len(files)}\ncrc=PASS\nmanifest=PASS")
