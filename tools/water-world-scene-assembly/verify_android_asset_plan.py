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


CANONICAL_STAGES = {
    "01_BACKGROUND",
    "02_RESIDENT_BACK",
    "03_SPHERE_BACK",
    "04_WATER_INTERIOR",
    "05_FAR_ECOLOGY",
    "06_LIGHTING",
    "07_FISH",
    "08_NEAR_ECOLOGY",
    "09_SPHERE_FRONT",
    "10_RESIDENT_FRONT",
    "11_FINAL_COMPOSITE",
}


def run():
    assert sha(ARCHIVE.read_bytes()) == "6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095"
    plan = json.loads((SOURCE / "03_SCENE_GRAPH/draw_bindings.json").read_text())
    optical = json.loads((SOURCE / "02_SUPPLEMENTED_ASSETS/materials/optical_config.json").read_text())
    assert plan["canvas_size"] == 1254 and plan["time_seconds"] == 0
    for opt_key in ("glass_refraction", "water_normal_distortion", "caustics", "bloom", "bokeh"):
        assert opt_key in optical, f"Missing optical config section: {opt_key}"
    seen = set()
    stages_seen = set()
    with zipfile.ZipFile(ARCHIVE) as archive:
        members = {x.filename: x for x in archive.infolist() if not x.is_dir()}
        assert len(members) == 65
        for draw in plan["draws"]:
            ident = draw["draw_id"]
            assert ident not in seen
            seen.add(ident)
            stage_id = draw.get("pass_id", "")
            assert stage_id in CANONICAL_STAGES, f"{ident}: invalid pass_id {stage_id}"
            stages_seen.add(stage_id)
            mesh = SOURCE / draw["mesh_path"]
            assert mesh.is_file(), ident
            data = json.loads(mesh.read_text())
            assert data["vertices"] and data["indices"], ident
            if draw["texture_path"]:
                assert draw["texture_path"].startswith("01_REUSED_ASSETS/")
                assert "奇幻水晶球中的水下生态世界" not in draw["texture_path"]
                member = "WATER_WORLD_V2/" + draw["texture_path"].removeprefix("01_REUSED_ASSETS/")
                assert member in members, ident
                assert sha(archive.read(member)) == draw["texture_sha256"], ident
        for tex_key, tex_entry in optical.get("auxiliary_textures", {}).items():
            path = tex_entry["path"]
            assert path.startswith("01_REUSED_ASSETS/"), tex_key
            assert "奇幻水晶球中的水下生态世界" not in path
            member = "WATER_WORLD_V2/" + path.removeprefix("01_REUSED_ASSETS/")
            assert member in members, tex_key
            assert sha(archive.read(member)) == tex_entry["sha256"], tex_key
    assert len(stages_seen) >= 10, f"Expected >=10 drawn stages, got {sorted(stages_seen)}"
    return {"source_assets": len(members), "draw_bindings": len(seen),
            "stages_drawn": len(stages_seen), "optical_nodes": 5,
            "scene_json": "PASS", "texture_sha256": "PASS", "master_bound_as_texture": False}


if __name__ == "__main__":
    print(json.dumps(run(), indent=2))
