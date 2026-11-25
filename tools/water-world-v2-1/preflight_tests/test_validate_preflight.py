import importlib.util
import io
import json
import contextlib
import tempfile
import unittest
import zipfile
from argparse import Namespace
from pathlib import Path
from unittest import mock

from PIL import Image


SCRIPT = Path(__file__).resolve().parents[1] / "validate_preflight.py"
SPEC = importlib.util.spec_from_file_location("validate_preflight", SCRIPT)
validator = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(validator)


def write_master(path: Path, icc: bytes | None = b"fake-icc-profile") -> dict:
    image = Image.new("RGBA", (8, 6), (20, 80, 120, 255))
    image.save(path, format="PNG", icc_profile=icc)
    raw = path.read_bytes()
    with Image.open(path) as decoded:
        decoded.load()
        profile = decoded.info.get("icc_profile")
        width, height = decoded.size
        mode = decoded.mode
        has_alpha = "A" in decoded.getbands()
    digest = validator.sha256_bytes(raw)
    return {
        "sha256": digest,
        "file_bytes": len(raw),
        "width": width,
        "height": height,
        "icc_profile_sha256": validator.sha256_bytes(profile) if profile else "NONE",
        "has_alpha": has_alpha,
        "mode": mode,
    }


def core_motion_fixture() -> tuple[dict, dict]:
    elements = []
    for element_id, model in validator.CORE_MODELS.items():
        unavailable = element_id in {"CORE_FILAMENT", "CORE_PARTICLES"}
        elements.append({
            "element_id": element_id,
            "motion_model": model,
            "motion_status": "ASSET_NOT_AVAILABLE" if unavailable else "NEEDS_TEMPORAL_REFERENCE",
            "asset_status": "ASSET_NOT_AVAILABLE" if unavailable else "AVAILABLE",
            "parent_element_id": None,
            "pivot": [0, 0, 0] if element_id == "CORE_VOLUME" else None,
            "pivot_status": "SOURCE_DOMAIN_ORIGIN_VERIFIED_NOT_DENSITY_CENTROID" if element_id == "CORE_VOLUME" else "UNRESOLVED_REFERENCE_REQUIRED",
            "water_world_pivot_approved": False if element_id == "CORE_VOLUME" else None,
            "runtime_inputs": sorted({value for (source, target), values in validator.CORE_REQUIRED_EDGE_OUTPUTS.items() if target == element_id for value in values}),
            "angular_velocity": None,
            "phase_offset": None,
            "amplitude": None,
            "frequency": None,
            "flow_velocity": None,
            "deformation_rate": None,
        })
    registry = {"allowed_motion_models": sorted(validator.ALLOWED_MODELS), "elements": elements}
    nodes = [{"element_id": e["element_id"]} for e in elements]
    edges = [
        {"source": source, "target": target, "dependency_type": "TEST", "outputs": sorted(outputs)}
        for (source, target), outputs in validator.CORE_REQUIRED_EDGE_OUTPUTS.items()
    ]
    return registry, {"nodes": nodes, "edges": edges}


class MasterSourceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.master = self.root / "master.png"
        self.manifest = write_master(self.master)
        digest = self.manifest["sha256"]
        self.validation = {"master_sha256": digest, "png_decode": "PASS"}
        self.analysis = {"master_sha256": digest}

    def tearDown(self):
        self.temp.cleanup()

    def test_normal_png_passes(self):
        self.assertEqual([], validator.validate_master_source(self.master, self.manifest, self.validation, self.analysis))

    def test_missing_file_fails(self):
        self.assertEqual(["MASTER_FILE_MISSING"], validator.validate_master_source(self.root / "missing.png", self.manifest, self.validation, self.analysis))

    def test_corrupted_png_fails_decode(self):
        self.master.write_bytes(b"\x89PNG\r\n\x1a\ntruncated")
        errors = validator.validate_master_source(self.master, self.manifest, self.validation, self.analysis)
        self.assertTrue(any(error.startswith("PNG_DECODE_FAILED:") for error in errors))

    def test_sha_mismatch_fails(self):
        wrong = dict(self.manifest, sha256="0" * 64)
        errors = validator.validate_master_source(self.master, wrong, self.validation, self.analysis)
        self.assertTrue(any(error.startswith("MASTER_MANIFEST_MISMATCH:sha256") for error in errors))

    def test_icc_mismatch_fails(self):
        wrong = dict(self.manifest, icc_profile_sha256="f" * 64)
        errors = validator.validate_master_source(self.master, wrong, self.validation, self.analysis)
        self.assertTrue(any(error.startswith("MASTER_MANIFEST_MISMATCH:icc_profile_sha256") for error in errors))

    def test_master_without_analysis_fails(self):
        errors = validator.validate_master_source(self.master, self.manifest, self.validation, None)
        self.assertIn("MASTER_ANALYSIS_MISSING_OR_SHA_MISMATCHED", errors)


class ClosureArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for relative in validator.CLOSURE_ARTIFACTS:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixture")
        self.final_zip = self.root / "final.zip"

    def tearDown(self):
        self.temp.cleanup()

    def test_missing_overlay_is_reported(self):
        (self.root / "03_STATIC_RECOMPOSITION/master_overlay.png").unlink()
        self.final_zip.write_bytes(b"fixture")
        missing = validator.missing_closure_artifacts(self.root, self.final_zip)
        self.assertIn("03_STATIC_RECOMPOSITION/master_overlay.png", missing)

    def test_missing_heatmap_is_reported(self):
        (self.root / "03_STATIC_RECOMPOSITION/difference_heatmap.png").unlink()
        self.final_zip.write_bytes(b"fixture")
        missing = validator.missing_closure_artifacts(self.root, self.final_zip)
        self.assertIn("03_STATIC_RECOMPOSITION/difference_heatmap.png", missing)

    def test_missing_final_zip_is_reported(self):
        missing = validator.missing_closure_artifacts(self.root, self.final_zip)
        self.assertIn(str(self.final_zip), missing)
        self.assertEqual(["FINAL_ZIP_MISSING"], validator.validate_final_zip(self.final_zip))

    def test_final_zip_with_verified_manifest_passes(self):
        payload = b"verified payload"
        digest = validator.sha256_bytes(payload)
        with zipfile.ZipFile(self.final_zip, "w") as archive:
            archive.writestr("payload.bin", payload)
            archive.writestr("MANIFEST/sha256_manifest.txt", f"{digest}  payload.bin\n")
        self.assertEqual([], validator.validate_final_zip(self.final_zip))

    def test_final_zip_with_packaged_root_manifest_passes(self):
        payload = b"verified payload"
        digest = validator.sha256_bytes(payload)
        with zipfile.ZipFile(self.final_zip, "w") as archive:
            archive.writestr("WATER_WORLD_V2_1/payload.bin", payload)
            archive.writestr("WATER_WORLD_V2_1/08_MANIFEST/sha256_manifest.txt",
                             f"{digest}  WATER_WORLD_V2_1/payload.bin\n")
        self.assertEqual([], validator.validate_final_zip(self.final_zip))

    def test_empty_final_zip_manifest_fails(self):
        with zipfile.ZipFile(self.final_zip, "w") as archive:
            archive.writestr("payload.bin", b"payload")
            archive.writestr("MANIFEST/sha256_manifest.txt", "\n")
        self.assertIn("FINAL_ZIP_SHA256_MANIFEST_EMPTY", validator.validate_final_zip(self.final_zip))


class MotionGraphTests(unittest.TestCase):
    def setUp(self):
        self.registry, self.graph = core_motion_fixture()

    def test_graph_missing_node_fails(self):
        self.graph["nodes"].pop()
        result = validator.validate_motion_data(self.registry, self.graph)[0]
        self.assertEqual("FAIL", result["status"])
        self.assertIn("graph node set differs", " ".join(result["detail"]))

    def test_dangling_edge_fails(self):
        self.graph["edges"].append({"source": "CORE_VOLUME", "target": "MISSING", "dependency_type": "TEST", "outputs": ["x"]})
        result = validator.validate_motion_data(self.registry, self.graph)[0]
        self.assertEqual("FAIL", result["status"])
        self.assertIn("dangling", " ".join(result["detail"]))

    def test_filled_unknown_motion_value_fails(self):
        self.registry["elements"][1]["phase_offset"] = 0.25
        result = validator.validate_motion_data(self.registry, self.graph)[0]
        self.assertEqual("FAIL", result["status"])
        self.assertIn("unknown temporal values must be null", " ".join(result["detail"]))

    def test_dependency_cycle_fails(self):
        self.graph["edges"].append({"source": "CORE_GLASS", "target": "CORE_VOLUME", "dependency_type": "TEST", "outputs": ["cycle"]})
        result = validator.validate_motion_data(self.registry, self.graph)[0]
        self.assertEqual("FAIL", result["status"])
        self.assertIn("dependency cycle", " ".join(result["detail"]))

    def test_missing_core_input_output_contract_fails(self):
        edge = next(e for e in self.graph["edges"] if e["source"] == "CORE_FLUID" and e["target"] == "CORE_DENSITY")
        edge["outputs"] = []
        result = validator.validate_motion_data(self.registry, self.graph)[0]
        self.assertEqual("FAIL", result["status"])
        self.assertIn("dependency outputs must be non-empty", " ".join(result["detail"]))

    def test_unavailable_asset_cannot_be_marked_ready(self):
        element = next(e for e in self.registry["elements"] if e["element_id"] == "CORE_FILAMENT")
        element["motion_status"] = "READY"
        result = validator.validate_motion_data(self.registry, self.graph)[0]
        self.assertEqual("FAIL", result["status"])
        self.assertIn("unavailable asset is marked ready", " ".join(result["detail"]))

    def test_malformed_runtime_input_fails_without_crashing(self):
        element = next(e for e in self.registry["elements"] if e["element_id"] == "CORE_DENSITY")
        element["runtime_inputs"] = [{"bad": "value"}]
        result = validator.validate_motion_data(self.registry, self.graph)[0]
        self.assertEqual("FAIL", result["status"])
        self.assertIn("runtime_inputs", " ".join(result["detail"]))


class ExitCodeTests(unittest.TestCase):
    def _run(self, archive_status: str):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        package = Path(temp.name) / "package"
        (package / "04_MOTION").mkdir(parents=True)
        (package / "07_GPU_CONTRACT").mkdir(parents=True)
        registry, graph = core_motion_fixture()
        (package / "04_MOTION/motion_element_registry.json").write_text(json.dumps(registry))
        (package / "04_MOTION/motion_dependency_graph.json").write_text(json.dumps(graph))
        for filename in validator.GPU_CONTRACT_FILES:
            (package / "07_GPU_CONTRACT" / filename).write_text("contract")
        args = Namespace(zip="unused.zip", package=str(package), core_repo="unused", master=str(package / "missing-master.png"), final_zip=None, scope="closure")
        with mock.patch.object(validator, "validate_archive", return_value=([{"check": "archive", "status": archive_status, "detail": "test"}], {})), \
             mock.patch.object(validator, "validate_core_assets", return_value=([{"check": "core", "status": "PASS", "detail": "test"}], {})):
            return validator.run_validation(args)

    def test_blocked_master_returns_exit_10(self):
        result, code = self._run("PASS")
        self.assertEqual(validator.EXIT_BLOCKED, code)
        self.assertEqual("WORK1_5_BLOCKED_MASTER_SOURCE", result["work1_5_status"])

    def test_failed_check_returns_exit_20(self):
        result, code = self._run("FAIL")
        self.assertEqual(validator.EXIT_FAILED, code)
        self.assertEqual("WORK1_5_VALIDATION_FAILED", result["work1_5_status"])

    def test_internal_error_returns_exit_30(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        package = Path(temp.name) / "package"
        package.mkdir()
        output = io.StringIO()
        with mock.patch.object(validator, "run_validation", side_effect=RuntimeError("simulated internal error")), contextlib.redirect_stdout(output):
            code = validator.main(["--zip", str(package / "missing.zip"), "--package", str(package), "--core-repo", str(package / "missing-core")])
        result = json.loads(output.getvalue())
        self.assertEqual(validator.EXIT_INTERNAL, code)
        self.assertEqual(validator.EXIT_INTERNAL, result["exit_code"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
