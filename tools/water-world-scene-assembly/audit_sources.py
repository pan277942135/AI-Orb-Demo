"""Inventory immutable source ZIPs without extracting or altering original artwork."""
import hashlib
import io
import json
from pathlib import Path
import zipfile
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1/07_MANIFEST'
LOCKS = {
    'releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip': '6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095',
    'releases/water-world-v2-v21/delivery/WATER_WORLD_V2_1_ASSETS.zip': '28f8918c39f6e875602d3a7e90775e9d455aa6c4e42783247d1104c3b4509679',
    'releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png': 'fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589',
}

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def run():
    locks = []
    for rel, expected in LOCKS.items():
        raw = (ROOT / rel).read_bytes()
        actual = sha(raw)
        if actual != expected:
            raise ValueError(f'Source identity mismatch: {rel}')
        locks.append(dict(path=rel, bytes=len(raw), sha256=actual, status='PASS'))
    rows = []
    with zipfile.ZipFile(ROOT / next(iter(LOCKS))) as archive:
        if archive.testzip() is not None:
            raise ValueError('Source ZIP CRC failed')
        for member in archive.infolist():
            if member.is_dir():
                continue
            rel = member.filename.removeprefix('WATER_WORLD_V2/')
            raw = archive.read(member)
            row = dict(source_path=rel, source_sha256=sha(raw), bytes=len(raw), original_modified=False)
            if rel.startswith(('00_MASTER/', '11_MANIFEST/', '12_PREVIEW/')) or rel.endswith('.md'):
                decision, reason = 'NOT_REQUIRED', 'Retain provenance or historical preview; never bind preview as scene albedo.'
            elif rel.startswith(('05_FISH/', '06_PLANTS/', '07_ROCKS/')):
                decision, reason = 'REUSE_WITH_TRANSFORM', 'Reuse source artwork/mesh/alpha; bind explicit scene transform and depth.'
            elif rel.startswith('02_RESIDENT/'):
                decision, reason = 'REUSE_WITH_MATERIAL', 'Preserve five path identities and original widths; tessellate into ribbon geometry.'
            elif rel.endswith('sphere_geometry.json'):
                decision, reason = 'SUPPLEMENT_REQUIRED', 'Source contains layout and masks, no indexed sphere mesh; supplement UV sphere using measured V2.1 layout.'
            elif rel.startswith(('03_SPHERE/', '04_WATER/', '08_LIGHTING/')):
                decision, reason = 'REUSE_WITH_MATERIAL', 'Use as input to independent glass/water/light material with explicit GPU binding.'
            else:
                decision, reason = 'REUSE_DIRECT', 'Reuse existing environment/auxiliary resource with provenance.'
            row.update(decision=decision, reason=reason)
            if rel.lower().endswith(('.png', '.jpg', '.webp')):
                with Image.open(io.BytesIO(raw)) as image:
                    image.load()
                    row.update(dimensions=list(image.size), mode=image.mode, alpha_mode='STRAIGHT_ALPHA' if 'A' in image.mode else 'OPAQUE')
            rows.append(row)
    assert len(rows) == 65
    OUT.mkdir(parents=True, exist_ok=True)
    for name, data in [('source_lock.json', {'sources':locks}), ('ASSET_REUSE_AND_SUPPLEMENT_REGISTRY.json', {'schema':'water-world-scene-reuse/1', 'source_count':65, 'assets':rows, 'gpu_validation':'NOT_RUN', 'visual_score':None, 'work2_static_input_ready':False})]:
        (OUT/name).write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps({'source_lock':'PASS','original_assets':len(rows),'gpu_validation':'NOT_RUN'}))

if __name__ == '__main__':
    run()
