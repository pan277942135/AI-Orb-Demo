#!/usr/bin/env python3
"""Validate source archive, motion contracts, AI Orb core assets and GPU contract files."""
import argparse, hashlib, json, re, sys, zipfile, io
from pathlib import Path
from PIL import Image
import xml.etree.ElementTree as ET

def sha(b): return hashlib.sha256(b).hexdigest()
def main():
 p=argparse.ArgumentParser(); p.add_argument('--zip',required=True); p.add_argument('--package',required=True); p.add_argument('--core-repo',required=True); p.add_argument('--master'); a=p.parse_args()
 zpath=Path(a.zip); root=Path(a.package); repo=Path(a.core_repo); results=[]
 def check(name,ok,detail): results.append({'check':name,'status':'PASS' if ok else 'FAIL','detail':detail})
 expected='6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095'
 data=zpath.read_bytes(); check('v2_archive_sha256',sha(data)==expected,f'{len(data)} bytes; {sha(data)}')
 with zipfile.ZipFile(zpath) as z:
  names=[n for n in z.namelist() if not n.endswith('/')]; c=z.read('WATER_WORLD_V2/11_MANIFEST/SHA256SUMS').decode('utf-8-sig'); sums={}
  for line in c.splitlines():
   m=re.match(r'^([0-9a-f]{64})\s+[* ]?(.*)$',line.strip())
   if m: sums[m.group(2).strip()]=m.group(1)
  verified=0; parsefails=[]
  for n in names:
   b=z.read(n); rel=n.removeprefix('WATER_WORLD_V2/'); exp=sums.get(rel) or sums.get(n)
   if exp and sha(b)==exp: verified+=1
   elif exp: parsefails.append('hash:'+n)
   suf=Path(n).suffix.lower()
   try:
    if suf in ('.png','.jpg','.jpeg','.webp'): Image.open(io.BytesIO(b)).load()
    elif suf=='.json': json.loads(b.decode('utf-8-sig'))
    elif suf=='.svg': ET.fromstring(b)
   except Exception as e: parsefails.append(n+':'+str(e))
  check('v2_archive_members',len(names)==65,f'{len(names)} files')
  check('v2_internal_checksums',verified==63,f'{verified}/{len(sums)} verified')
  check('v2_parse_decode',not parsefails,f'{len(parsefails)} errors')
 reg=json.loads((root/'04_MOTION/motion_element_registry.json').read_text()); graph=json.loads((root/'04_MOTION/motion_dependency_graph.json').read_text()); ids=[e['element_id'] for e in reg['elements']]; idset=set(ids); models=set(reg['allowed_motion_models'])
 check('motion_unique_ids',len(ids)==len(idset),f'{len(ids)} unique of {len(ids)}')
 check('motion_models',all(e['motion_model'] in models for e in reg['elements']),'all motion models are allowed')
 bad_parent=[e['element_id'] for e in reg['elements'] if e['parent_element_id'] and e['parent_element_id'] not in idset]
 check('motion_parent_refs',not bad_parent,str(bad_parent))
 bad_edge=[e for e in graph['edges'] if e['source'] not in idset or e['target'] not in idset]
 check('motion_dependency_refs',not bad_edge,f'{len(graph["edges"])} edges; dangling={len(bad_edge)}')
 unknown_values=[]
 for e in reg['elements']:
  if e['motion_status']=='NEEDS_TEMPORAL_REFERENCE' and any(e.get(k) is not None for k in ('angular_velocity','phase_offset','amplitude','frequency')): unknown_values.append(e['element_id'])
 check('unknown_motion_values_null',not unknown_values,str(unknown_values))
 core=Path(a.core_repo)/'app/src/main/assets/orb_v3'; m=json.loads((core/'manifest/ORB_V3_ASSET_MANIFEST.json').read_text()); asset_errors=[]
 for s in m['volume']['slices']+m['maps']:
  q=core/s['path']; b=q.read_bytes()
  try: im=Image.open(q); im.load(); dims=list(im.size)
  except Exception as e: asset_errors.append(s['path']+':'+str(e)); continue
  # Volume slice dimensions come from manifest volume descriptor.
  expdims=[m['volume']['width'],m['volume']['height']] if 'zIndex' in s else [s['width'],s['height']]
  if sha(b)!=s['sha256'] or len(b)!=s['bytes'] or dims!=expdims or im.mode!='RGBA': asset_errors.append(s['path'])
 check('core_35_webp_assets',not asset_errors,f'{len(m["volume"]["slices"])+len(m["maps"])} assets; errors={len(asset_errors)}')
 required=['renderer_architecture.md','asset_runtime_contract.md','camera_contract.md','texture_alpha_contract.md','scene_graph_contract.md','render_pass_contract.md','shader_uniform_contract.md','gpu_resource_lifecycle.md','gpu_diagnostics_contract.md','WORK2_GPU_STATIC_RECONSTRUCTION_EXECUTION_CONTRACT.md','CORE_MOTION_GPU_EXECUTION_CONTRACT.md']
 missing=[x for x in required if not (root/'07_GPU_CONTRACT'/x).is_file() or (root/'07_GPU_CONTRACT'/x).stat().st_size==0]
 check('gpu_contract_documents',not missing,str(missing))
 master=Path(a.master) if a.master else None
 if master and master.is_file():
  check('master_raw_source',True,f'raw bytes available: {master.stat().st_size}')
 else: results.append({'check':'master_raw_source','status':'BLOCKED','detail':'raw PNG bytes not available; pixel analysis and visual acceptance cannot run'})
 status='PASS' if all(x['status']=='PASS' for x in results) else ('BLOCKED_MASTER_SOURCE' if all(x['status'] in ('PASS','BLOCKED') for x in results) else 'FAIL')
 out={'schema_version':'water-world-v2-1-preflight-validation/1.0','overall_status':status,'checks':results}
 (root/'06_VALIDATION/validation_manifest.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(out,ensure_ascii=False,indent=2))
 return 0 if status in ('PASS','BLOCKED_MASTER_SOURCE') else 1
if __name__=='__main__': sys.exit(main())
