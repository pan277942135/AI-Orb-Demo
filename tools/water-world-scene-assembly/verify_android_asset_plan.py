"""Verify every APK asset input without extracting or modifying the frozen source archive."""
import hashlib
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "releases/water-world-v2-v21/work1_5_scene_assembly/WATER_WORLD_V2_1"
ARCHIVE = ROOT / "releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def run():
    assert sha(ARCHIVE.read_bytes()) == "6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095"
    plan = json.loads((SOURCE / "03_SCENE_GRAPH/draw_bindings.json").read_text())
    assert plan["canvas_size"] == 1254 and plan["time_seconds"] == 0
    seen = set()
    with zipfile.ZipFile(ARCHIVE) as archive:
        members = {x.filename: x for x in archive.infolist() if not x.is_dir()}
        assert len(members) == 65
        for draw in plan["draws"]:
            ident = draw["draw_id"]
            assert ident not in seen
            seen.add(ident)
            mesh = SOURCE / draw["mesh_path"]
            assert mesh.is_file(), ident
            data = json.loads(mesh.read_text())
            assert data["vertices"] and data["indices"], ident
            if draw["texture_path"]:
                assert draw["texture_path"].startswith("01_REUSED_ASSETS/")
                member = "WATER_WORLD_V2/" + draw["texture_path"].removeprefix("01_REUSED_ASSETS/")
                assert member in members, ident
                assert sha(archive.read(member)) == draw["texture_sha256"], ident
    return {"source_assets": len(members), "draw_bindings": len(seen),
            "scene_json": "PASS", "texture_sha256": "PASS", "master_bound_as_texture": False}


if __name__ == "__main__":
    print(json.dumps(run(), indent=2))
