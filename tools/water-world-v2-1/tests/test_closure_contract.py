from pathlib import Path
import copy
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from validate_closure_delta import (EXPECTED, OBJECTS, asset_hash_matches, master_layer_errors,
                                    object_acceptance_failures, object_contract_errors,
                                    stale_status_tokens, threshold_failures)


def valid_fixtures():
    ids = [oid for group in OBJECTS.values() for oid in group]
    thresholds = {key: {direction: target} for key, (direction, target) in EXPECTED.items()}
    metric_values = {key: {oid: target for oid in ids} for key, (_, target) in EXPECTED.items()}
    per_object = {oid: {"status": "PASS"} for oid in ids}
    metrics = {"thresholds": thresholds, "metrics": metric_values, "per_object": per_object,
               "comparison": {"master_used_as_composition_layer": False,
                              "render_type": "CPU_STATIC_RECOMPOSITION_FROM_INDEPENDENT_V2_ASSETS"}}
    objects = [{"object_id": oid, "source_sha256": "a" * 64,
                "master_region_id": "MASTER_" + oid,
                "candidate_transform": {"state": "measured"},
                "ground_truth": {"occlusion_mask": "occlusion.png", "confidence_mask": "confidence.png"}}
               for oid in ids]
    mapping = {"master_sha256": "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589", "objects": objects}
    camera = {"canvas_px": [1254, 1254], "projection": "2D orthographic source-pixel registration",
              "physical_fov": "UNRESOLVED_FROM_SINGLE_STATIC_IMAGE"}
    scene = {"objects": copy.deepcopy(objects), "static_camera": camera,
             "calibration_status": "PARTIAL_MEASURED_WITH_OBJECT_LEVEL_BLOCKERS"}
    return metrics, mapping, scene


class ClosureContractTests(unittest.TestCase):
    def test_frozen_threshold_values_are_enforced(self):
        metrics, _, _ = valid_fixtures()
        self.assertFalse(threshold_failures(metrics))
        metrics["thresholds"]["fish_iou"] = {"min": 0.80}
        self.assertIn("threshold_changed:fish_iou", threshold_failures(metrics))

    def test_below_gate_metric_fails(self):
        metrics, _, _ = valid_fixtures()
        metrics["metrics"]["fish_iou"]["WW_FISH_03"] = 0.89
        self.assertIn("fish_iou", threshold_failures(metrics))

    def test_missing_metric_fails(self):
        metrics, _, _ = valid_fixtures()
        del metrics["metrics"]["plant_iou"]
        self.assertIn("metric_missing:plant_iou", threshold_failures(metrics))

    def test_null_metric_never_passes(self):
        metrics, _, _ = valid_fixtures()
        metrics["metrics"]["resident_width_error"]["G01"] = None
        self.assertIn("metric_unmeasured:resident_width_error", threshold_failures(metrics))

    def test_missing_scene_object_fails(self):
        metrics, mapping, scene = valid_fixtures()
        scene["objects"].pop()
        self.assertIn("scene_graph_object_set", object_contract_errors(metrics, mapping, scene))

    def test_missing_occlusion_mapping_fails(self):
        metrics, mapping, scene = valid_fixtures()
        mapping["objects"][0]["ground_truth"].pop("occlusion_mask")
        self.assertTrue(any(x.startswith("occlusion_or_confidence_missing:") for x in object_contract_errors(metrics, mapping, scene)))

    def test_missing_transform_fails(self):
        metrics, mapping, scene = valid_fixtures()
        mapping["objects"][0]["candidate_transform"] = None
        self.assertTrue(any(x.startswith("transform_missing:") for x in object_contract_errors(metrics, mapping, scene)))

    def test_missing_master_region_fails(self):
        metrics, mapping, scene = valid_fixtures()
        mapping["objects"][0]["master_region_id"] = None
        self.assertTrue(any(x.startswith("master_region_missing:") for x in object_contract_errors(metrics, mapping, scene)))

    def test_invented_perspective_fails(self):
        metrics, mapping, scene = valid_fixtures()
        scene["static_camera"]["physical_fov"] = 42
        self.assertIn("invented_physical_fov", object_contract_errors(metrics, mapping, scene))

    def test_master_layer_is_rejected(self):
        metrics, _, _ = valid_fixtures()
        metrics["comparison"]["master_used_as_composition_layer"] = True
        self.assertEqual(master_layer_errors(metrics), ["master_used_as_composition_layer"])

    def test_wrong_source_asset_sha_fails(self):
        self.assertFalse(asset_hash_matches(b"changed source", "0" * 64))

    def test_stale_source_unavailable_status_fails(self):
        self.assertEqual(stale_status_tokens("status=BLOCKED_MASTER_SOURCE"), ["BLOCKED_MASTER_SOURCE"])

    def test_missing_object_source_sha_fails(self):
        metrics, mapping, scene = valid_fixtures()
        mapping["objects"][0]["source_sha256"] = ""
        self.assertTrue(any(x.startswith("source_sha_missing:") for x in object_contract_errors(metrics, mapping, scene)))

    def test_incomplete_ground_truth_cannot_pass_object_gate(self):
        metrics, _, _ = valid_fixtures()
        metrics["per_object"]["WW_FISH_03"]["status"] = "BLOCKED_GROUND_TRUTH_UNCERTAIN"
        self.assertEqual(object_acceptance_failures(metrics), [
            "object_not_accepted:WW_FISH_03:BLOCKED_GROUND_TRUTH_UNCERTAIN"
        ])

    def test_scene_mapping_divergence_fails(self):
        metrics, mapping, scene = valid_fixtures()
        scene["objects"][0]["candidate_transform"] = {"state": "different"}
        self.assertTrue(any(x.startswith("scene_mapping_mismatch:") for x in object_contract_errors(metrics, mapping, scene)))


if __name__ == "__main__":
    unittest.main()
