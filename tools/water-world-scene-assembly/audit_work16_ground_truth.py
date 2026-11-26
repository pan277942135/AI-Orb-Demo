"""Reconcile Work 1.5 candidates with frozen V2.1 evidence without inferring hidden pixels."""
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "releases/water-world-v2-v21"
OLD = BASE / "work1_5_scene_assembly/WATER_WORLD_V2_1/03_SCENE_GRAPH"
DELIVERY = BASE / "delivery"
OUT = BASE / "work1_6_structure_closure_android_gpu"
EXPECTED_MASTER = "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589"
EXPECTED_V2 = "6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095"
EXPECTED_V21 = "28f8918c39f6e875602d3a7e90775e9d455aa6c4e42783247d1104c3b4509679"


def read(name):
    return json.loads(name.read_text())


def write(relative, value):
    path = OUT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run():
    master = BASE / "奇幻水晶球中的水下生态世界 (1).png"
    archive = ROOT / "releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip"
    v21_archive = DELIVERY / "WATER_WORLD_V2_1_ASSETS.zip"
    assert sha(master) == EXPECTED_MASTER
    assert sha(archive) == EXPECTED_V2
    assert sha(v21_archive) == EXPECTED_V21
    scene = read(OLD / "scene_graph.json")
    root_causes = {o["object_id"]: o for o in read(DELIVERY / "root_cause_matrix.json")["objects"]}
    historical = read(DELIVERY / "master_occlusion_graph.json")
    assert len(scene["objects"]) == 14
    assert len(root_causes) == 14
    records = []
    with ZipFile(archive) as z:
        source_names = {n.removeprefix("WATER_WORLD_V2/"): n for n in z.namelist() if not n.endswith("/")}
        assert len(source_names) == 65
        for obj in scene["objects"]:
            ident = obj["object_id"]
            cause = root_causes[ident]
            source = obj["source_resource_path"].split("V2_ORIGINAL/", 1)[-1]
            if source in source_names:
                actual = hashlib.sha256(z.read(source_names[source])).hexdigest()
                assert actual == obj["source_sha256"] == cause["source_asset_sha256"], ident
            else:
                raise AssertionError(f"Missing V2 source asset: {source}")
            prefix = f"01_ANALYSIS/ground_truth/objects/{ident}/"
            records.append({
                "object_id": ident,
                "source_asset": obj["source_resource_path"],
                "source_sha256": obj["source_sha256"],
                "source_archive_hash_verified": actual is not None,
                "source_transform": obj["transform"],
                "geometry_confidence": obj["confidence"],
                "depth_layer": obj["layer"],
                "material_id": obj["material"],
                "metric_status": cause["status"],
                "master_region": None,
                "visible_mask": f"{prefix}source_visible_mask.png",
                "occlusion_mask": f"{prefix}occlusion_mask.png",
                "confidence_mask": f"{prefix}confidence_mask.png",
                "unknown_mask": None,
                "evidence_archive": "delivery/WATER_WORLD_V2_1_ASSETS.zip",
                "evidence": [cause["ground_truth_path"], cause["evidence"]],
                "interpretation": "Historical evidence reference; mask existence and object identity require independent review",
            })
    assert len({o["object_id"] for o in records}) == 14
    resident = historical["resident_status"]
    split = {}
    for ident in ("G01", "G02", "A01", "A02", "C01"):
        r = resident[ident]
        assert r["confirmed_occluded_samples"] == 0
        split[ident] = {
            "directly_observed_samples": r["directly_observed_samples"],
            "confirmed_occluded_samples": 0,
            "unclassified_samples": r["unclassified_unmatched_samples"],
            "front_segments": [], "back_segments": [],
            "status": "UNKNOWN_FRONT_BACK_NO_CONFIRMED_OCCLUSION",
        }
    # The historical keys describe a pair but do not define direction
    # unambiguously. Retain them verbatim, never promote to canonical edges.
    canonical = {
        "schema": "water-world-occlusion-canonical/1",
        "front_object_back_object_edges": [],
        "unresolved_historical_relations": historical["relations"],
        "resident_segments": split,
        "status": "BLOCKED_DIRECTION_AND_GROUND_TRUTH",
    }
    existing_occlusion_path = OUT / "03_SCENE_GRAPH/OCCLUSION_GRAPH_CANONICAL.json"
    if existing_occlusion_path.is_file():
        existing_occlusion = read(existing_occlusion_path)
        if "render_hypothesis_occlusion" in existing_occlusion:
            canonical["render_hypothesis_occlusion"] = existing_occlusion["render_hypothesis_occlusion"]
    graph = {
        "schema": "water-world-scene-canonical/1",
        "canvas": [1254, 1254], "time_seconds": 0,
        "nodes": [{"object_id": x["object_id"], "parent": "scene", "source_asset": x["source_asset"],
                   "source_sha256": x["source_sha256"], "transform": x["source_transform"],
                   "depth_layer": x["depth_layer"], "material_id": x["material_id"],
                   "confidence": x["geometry_confidence"], "metric_status": x["metric_status"]}
                  for x in records],
        "render_order_is_not_ground_truth_occlusion": True,
        "status": "CANDIDATE_WITH_UNRESOLVED_OCCLUSION",
    }
    write("01_SOURCE_AUDIT/SOURCE_LOCK.json", {"master_sha256": EXPECTED_MASTER,
          "v2_zip_sha256": EXPECTED_V2, "v21_zip_sha256": EXPECTED_V21,
          "original_v2_members": 65, "status": "PASS"})
    write("02_GROUND_TRUTH/GROUND_TRUTH_MANIFEST.json", {"objects": records,
          "status": "2_PASS_12_BLOCKED_OR_UNCERTAIN"})
    write("03_SCENE_GRAPH/SCENE_GRAPH_CANONICAL.json", graph)
    write("03_SCENE_GRAPH/OCCLUSION_GRAPH_CANONICAL.json", canonical)
    write("02_GROUND_TRUTH/RESIDENT_SEGMENT_STATUS.json", split)
    return records


if __name__ == "__main__":
    print(f"Audited {len(run())} objects; occlusion remains unresolved")
