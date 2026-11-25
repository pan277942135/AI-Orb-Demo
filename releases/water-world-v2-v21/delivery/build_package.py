#!/usr/bin/env python3
"""Create a reproducible, self-contained V2.1 ZIP and verify SHA/CRC."""
import argparse
import hashlib
import os
import zipfile
from pathlib import Path

EXCLUDE={"SHA256SUMS.txt","08_MANIFEST/sha256_manifest.txt"}

def sha(path:Path)->str:return hashlib.sha256(path.read_bytes()).hexdigest()

def build(package:Path,output:Path)->dict:
    files=sorted(p for p in package.rglob("*") if p.is_file() and p.relative_to(package).as_posix() not in EXCLUDE)
    lines=[f"{sha(p)}  WATER_WORLD_V2_1/{p.relative_to(package).as_posix()}" for p in files]
    manifest="\n".join(lines)+"\n"
    (package/"08_MANIFEST/sha256_manifest.txt").write_text(manifest,encoding="utf-8")
    (package/"SHA256SUMS.txt").write_text(manifest,encoding="utf-8")
    output.parent.mkdir(parents=True,exist_ok=True)
    tmp=output.with_suffix(output.suffix+".part")
    if tmp.exists():tmp.unlink()
    with zipfile.ZipFile(tmp,"w",compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for path in sorted(x for x in package.rglob("*") if x.is_file()):
            info=zipfile.ZipInfo("WATER_WORLD_V2_1/"+path.relative_to(package).as_posix(),(2026,1,1,0,0,0))
            info.compress_type=zipfile.ZIP_DEFLATED
            info.external_attr=(0o100644 & 0xFFFF)<<16
            z.writestr(info,path.read_bytes(),compress_type=zipfile.ZIP_DEFLATED,compresslevel=6)
    os.replace(tmp,output)
    with zipfile.ZipFile(output) as z:
        bad=z.testzip()
        if bad:raise ValueError("ZIP CRC failure: "+bad)
        names=set(z.namelist())
        for line in manifest.splitlines():
            expected,member=line.split(None,1); member=member.strip()
            if member not in names or hashlib.sha256(z.read(member)).hexdigest()!=expected:
                raise ValueError("ZIP member SHA mismatch: "+member)
    raw=output.read_bytes()
    return {"path":str(output),"bytes":len(raw),"sha256":hashlib.sha256(raw).hexdigest(),
            "files_excluding_two_manifests":len(files),"archive_members":len(files)+2,"crc":"PASS","internal_sha_entries":len(lines)}

def main():
    p=argparse.ArgumentParser();p.add_argument("--package-root",type=Path,required=True);p.add_argument("--output",type=Path,required=True)
    a=p.parse_args();import json;print(json.dumps(build(a.package_root,a.output),indent=2))
if __name__=="__main__":main()
