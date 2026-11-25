#!/usr/bin/env python3
"""Strict V2.1 preflight/closure validator. BLOCKED is never a success exit code."""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import re
import sys
import zipfile
from collections import defaultdict, deque
from pathlib import Path
from typing import Any
from PIL import Image
import xml.etree.ElementTree as ET

EXIT_PASS = 0
EXIT_BLOCKED = 10
EXIT_FAILED = 20
EXIT_INTERNAL = 30
ALLOWED_MODELS = {
    "STATIC", "RIGID_TRANSFORM", "AXIS_ROTATION", "SPLINE_PATH", "RIBBON_FLOW",
    "DEFORMABLE_MESH", "FLOW_FIELD", "VOLUME_TRANSFORM", "PARTICLE_SYSTEM",
    "MATERIAL_ANIMATION", "OPTICAL_RESPONSE",
}
TEMPORAL_FIELDS = ("angular_velocity", "phase_offset", "amplitude", "frequency", "flow_velocity", "deformation_rate")
CORE_MODELS = {
    "CORE_VOLUME": "VOLUME_TRANSFORM", "CORE_FLUID": "FLOW_FIELD",
    "CORE_FILAMENT": "RIBBON_FLOW", "CORE_PARTICLES": "PARTICLE_SYSTEM",
    "CORE_DENSITY": "FLOW_FIELD", "CORE_COLOR": "MATERIAL_ANIMATION",
    "CORE_CAUSTICS": "MATERIAL_ANIMATION", "CORE_GLASS": "OPTICAL_RESPONSE",
}
CORE_REQUIRED_EDGE_OUTPUTS = {
    ("CORE_VOLUME", "CORE_FLUID"): {"volume_sample_position", "flow_direction"},
    ("CORE_VOLUME", "CORE_DENSITY"): {"sampled_density"},
    ("CORE_VOLUME", "CORE_COLOR"): {"sampled_color"},
    ("CORE_VOLUME", "CORE_CAUSTICS"): {"light_field"},
    ("CORE_VOLUME", "CORE_GLASS"): {"refraction_input"},
    ("CORE_FLUID", "CORE_DENSITY"): {"density_field"},
    ("CORE_FLUID", "CORE_COLOR"): {"material_sample"},
    ("CORE_FLUID", "CORE_CAUSTICS"): {"density_and_normal"},
    ("CORE_FLUID", "CORE_GLASS"): {"refractive_index_input"},
    ("CORE_DENSITY", "CORE_CAUSTICS"): {"caustic_attenuation"},
    ("CORE_DENSITY", "CORE_GLASS"): {"absorption_path"},
    ("CORE_COLOR", "CORE_GLASS"): {"transmission_color"},
    ("CORE_CAUSTICS", "CORE_GLASS"): {"internal_caustic_radiance"},
    ("CORE_FILAMENT", "CORE_GLASS"): {"filament_depth"},
    ("CORE_PARTICLES", "CORE_GLASS"): {"particle_depth"},
}
CLOSURE_ARTIFACTS = (
    "01_ANALYSIS/master_sphere_geometry.json",
    "01_ANALYSIS/master_fish_geometry.json",
    "01_ANALYSIS/master_resident_geometry.json",
    "01_ANALYSIS/master_ecology_geometry.json",
    "01_ANALYSIS/master_occlusion_graph.json",
    "03_STATIC_RECOMPOSITION/static_recomposition.png",
    "03_STATIC_RECOMPOSITION/master_reference.png",
    "03_STATIC_RECOMPOSITION/master_overlay.png",
    "03_STATIC_RECOMPOSITION/difference_heatmap.png",
    "03_STATIC_RECOMPOSITION/region_comparison.png",
    "03_STATIC_RECOMPOSITION/visual_metrics.json",
)
GPU_CONTRACT_FILES = (
    "renderer_architecture.md", "asset_runtime_contract.md", "camera_contract.md",
    "texture_alpha_contract.md", "scene_graph_contract.md", "render_pass_contract.md",
    "shader_uniform_contract.md", "gpu_resource_lifecycle.md", "gpu_diagnostics_contract.md",
    "WORK2_GPU_STATIC_RECONSTRUCTION_EXECUTION_CONTRACT.md", "CORE_MOTION_GPU_EXECUTION_CONTRACT.md",
)
EXPECTED_V2_SHA256 = "6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return value


def validate_archive(path: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    checks: list[dict[str, Any]] = []
    if not path.is_file():
        return [{"check": "v2_archive", "status": "FAIL", "detail": "archive file missing"}], {}
    raw = path.read_bytes()
    checks.append({"check": "v2_archive_sha256", "status": "PASS" if sha256_bytes(raw) == EXPECTED_V2_SHA256 else "FAIL", "detail": f"{len(raw)} bytes; {sha256_bytes(raw)}"})
    try:
        with zipfile.ZipFile(path) as archive:
            bad_member = archive.testzip()
            names = [n for n in archive.namelist() if not n.endswith("/")]
            checks.append({"check": "v2_zip_crc", "status": "PASS" if bad_member is None else "FAIL", "detail": bad_member or "all CRCs passed"})
            checks.append({"check": "v2_member_count", "status": "PASS" if len(names) == 65 else "FAIL", "detail": f"{len(names)} files"})
            sums_path = "WATER_WORLD_V2/11_MANIFEST/SHA256SUMS"
            sums: dict[str, str] = {}
            for line in archive.read(sums_path).decode("utf-8-sig").splitlines():
                match = re.match(r"^([0-9a-fA-F]{64})\s+[* ]?(.*)$", line.strip())
                if match:
                    sums[match.group(2).strip()] = match.group(1).lower()
            verified = 0
            problems: list[str] = []
            decode_count = 0
            for name in names:
                data = archive.read(name)
                rel = name.removeprefix("WATER_WORLD_V2/")
                expected = sums.get(rel) or sums.get(name)
                if expected:
                    if sha256_bytes(data) == expected:
                        verified += 1
                    else:
                        problems.append(f"SHA256:{name}")
                suffix = Path(name).suffix.lower()
                try:
                    if suffix in (".png", ".jpg", ".jpeg", ".webp"):
                        image = Image.open(__import__("io").BytesIO(data))
                        image.load()
                        decode_count += 1
                    elif suffix == ".json":
                        json.loads(data.decode("utf-8-sig"))
                    elif suffix == ".svg":
                        ET.fromstring(data)
                except Exception as exc:
                    problems.append(f"DECODE:{name}:{exc}")
            checks.append({"check": "v2_internal_sha256", "status": "PASS" if len(sums) == 63 and verified == 63 else "FAIL", "detail": f"{verified}/{len(sums)} verified"})
            checks.append({"check": "v2_parse_decode", "status": "PASS" if not problems else "FAIL", "detail": {"raster_decoded": decode_count, "errors": problems}})
            return checks, {"members": len(names), "internal_sha_count": len(sums), "verified_sha_count": verified, "raster_decoded": decode_count}
    except Exception as exc:
        checks.append({"check": "v2_zip_read", "status": "FAIL", "detail": str(exc)})
        return checks, {}


def validate_core_assets(repo: Path) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    checks: list[dict[str, Any]] = []
    root = repo / "app/src/main/assets/orb_v3"
    try:
        manifest = load_json(root / "manifest/ORB_V3_ASSET_MANIFEST.json")
        volume = manifest["volume"]
        errors: list[str] = []
        assets = list(volume["slices"]) + list(manifest["maps"])
        for asset in assets:
            file_path = root / asset["path"]
            if not file_path.is_file():
                errors.append(f"missing:{asset['path']}")
                continue
            raw = file_path.read_bytes()
            try:
                image = Image.open(file_path)
                image.load()
                expected_dims = [volume["width"], volume["height"]] if "zIndex" in asset else [asset["width"], asset["height"]]
                if list(image.size) != expected_dims or image.mode != "RGBA":
                    errors.append(f"decode_contract:{asset['path']}:{image.size}:{image.mode}")
            except Exception as exc:
                errors.append(f"decode:{asset['path']}:{exc}")
            if sha256_bytes(raw) != asset["sha256"] or len(raw) != asset["bytes"]:
                errors.append(f"fingerprint:{asset['path']}")
        count_ok = len(volume["slices"]) == 32 and len(manifest["maps"]) == 3 and len(assets) == 35
        checks.append({"check": "core_asset_count", "status": "PASS" if count_ok else "FAIL", "detail": f"{len(assets)} files; 32 spatial slices + {len(manifest['maps'])} maps"})
        checks.append({"check": "core_asset_hash_decode", "status": "PASS" if not errors else "FAIL", "detail": errors})
        return checks, {"asset_count": len(assets), "errors": errors, "manifest_sha256": sha256_bytes((root / "manifest/ORB_V3_ASSET_MANIFEST.json").read_bytes())}
    except Exception as exc:
        return [{"check": "core_asset_manifest", "status": "FAIL", "detail": str(exc)}], {}


def _cycle_nodes(nodes: set[str], edges: list[dict[str, Any]]) -> list[str]:
    adjacency: dict[str, list[str]] = defaultdict(list)
    indegree = {node: 0 for node in nodes}
    for edge in edges:
        src, dst = edge.get("source"), edge.get("target")
        if src in nodes and dst in nodes:
            adjacency[src].append(dst)
            indegree[dst] += 1
    queue = deque(node for node, degree in indegree.items() if degree == 0)
    visited = 0
    while queue:
        node = queue.popleft()
        visited += 1
        for nxt in adjacency[node]:
            indegree[nxt] -= 1
            if indegree[nxt] == 0:
                queue.append(nxt)
    return sorted(node for node, degree in indegree.items() if degree > 0) if visited != len(nodes) else []


def validate_motion_data(registry: dict[str, Any], graph: dict[str, Any]) -> list[dict[str, Any]]:
    errors: list[str] = []
    elements = registry.get("elements")
    graph_nodes = graph.get("nodes")
    edges = graph.get("edges")
    if not isinstance(elements, list) or not isinstance(graph_nodes, list) or not isinstance(edges, list):
        return [{"check": "motion_schema", "status": "FAIL", "detail": "registry.elements / graph.nodes / graph.edges must be arrays"}]
    registry_objects = [e for e in elements if isinstance(e, dict)]
    ids = [e.get("element_id") for e in registry_objects]
    valid_ids = [i for i in ids if isinstance(i, str) and i]
    id_set = set(valid_ids)
    if len(registry_objects) != len(elements) or len(valid_ids) != len(elements) or len(valid_ids) != len(id_set):
        errors.append("registry IDs must be unique, non-null strings")
    if len(valid_ids) != len(ids):
        errors.append("registry contains an invalid element_id")
    graph_objects = [n for n in graph_nodes if isinstance(n, dict)]
    raw_node_ids = [n.get("element_id") for n in graph_objects]
    node_ids = [i for i in raw_node_ids if isinstance(i, str) and i]
    if len(graph_objects) != len(graph_nodes) or len(node_ids) != len(graph_nodes):
        errors.append("graph node entries must be objects with a non-empty string element_id")
    if len(node_ids) != len(set(node_ids)):
        errors.append("duplicate graph nodes")
    if set(node_ids) != id_set:
        errors.append(f"graph node set differs from registry (missing={sorted(id_set-set(node_ids))}, extra={sorted(set(node_ids)-id_set)})")
    raw_allowed = registry.get("allowed_motion_models", [])
    allowed = set(v for v in raw_allowed if isinstance(v, str)) if isinstance(raw_allowed, list) else set()
    if not isinstance(raw_allowed, list) or len(allowed) != len(raw_allowed) or allowed != ALLOWED_MODELS:
        errors.append("allowed_motion_models differs from frozen model set")
    ready_states = {"READY", "PASS", "MOTION_ASSET_READY", "STATIC_RESOURCE_READY"}
    for e in registry_objects:
        eid = e.get("element_id", "<unknown>")
        motion_model = e.get("motion_model")
        if not isinstance(motion_model, str) or motion_model not in allowed:
            errors.append(f"{eid}: illegal motion model {e.get('motion_model')}")
        parent = e.get("parent_element_id")
        if parent is not None and (not isinstance(parent, str) or parent not in id_set):
            errors.append(f"{eid}: invalid parent {parent}")
        status = e.get("motion_status")
        asset_status = e.get("asset_status", "")
        if isinstance(asset_status, str) and asset_status.startswith("ASSET_NOT_AVAILABLE") and isinstance(status, str) and status in ready_states:
            errors.append(f"{eid}: unavailable asset is marked ready")
        if status in ("NEEDS_TEMPORAL_REFERENCE", "ASSET_NOT_AVAILABLE"):
            filled = [field for field in TEMPORAL_FIELDS if field in e and e[field] is not None]
            if filled:
                errors.append(f"{eid}: unknown temporal values must be null: {filled}")
        pivot_status = e.get("pivot_status")
        if e.get("motion_model") != "STATIC" and e.get("pivot") is None and not pivot_status:
            errors.append(f"{eid}: missing pivot must have an explicit unresolved status")
    bad_edges = [
        e for e in edges
        if not isinstance(e, dict)
        or not isinstance(e.get("source"), str) or e.get("source") not in id_set
        or not isinstance(e.get("target"), str) or e.get("target") not in id_set
    ]
    if bad_edges:
        errors.append(f"dependency graph has {len(bad_edges)} dangling or malformed edges")
    valid_edges = [e for e in edges if isinstance(e, dict) and isinstance(e.get("source"), str) and isinstance(e.get("target"), str)]
    typed_edges = []
    for edge in valid_edges:
        dependency_type = edge.get("dependency_type")
        if not isinstance(dependency_type, str) or not dependency_type:
            errors.append(f"{edge.get('source')}->{edge.get('target')}: dependency_type must be a non-empty string")
            continue
        typed_edges.append(edge)
    edge_keys = [(e.get("source"), e.get("target"), e.get("dependency_type")) for e in typed_edges]
    if len(edge_keys) != len(set(edge_keys)):
        errors.append("duplicate dependency edges")
    edge_outputs: dict[tuple[str, str], set[str]] = defaultdict(set)
    for edge in typed_edges:
        outputs = edge.get("outputs")
        if not isinstance(outputs, list) or not outputs or any(not isinstance(v, str) or not v for v in outputs):
            errors.append(f"{edge.get('source')}->{edge.get('target')}: dependency outputs must be non-empty strings")
            continue
        if len(outputs) != len(set(outputs)):
            errors.append(f"{edge.get('source')}->{edge.get('target')}: duplicate dependency outputs")
        edge_outputs[(edge.get("source"), edge.get("target"))].update(outputs)
    cycles = _cycle_nodes(id_set, edges) if not bad_edges else []
    if cycles:
        errors.append(f"undeclared dependency cycle involving {cycles}")
    for eid, model in CORE_MODELS.items():
        match = next((e for e in elements if e.get("element_id") == eid), None)
        if match is None:
            errors.append(f"missing required Core Motion element {eid}")
        elif match.get("motion_model") != model:
            errors.append(f"{eid}: expected motion model {model}")
    required_edges = {
        ("CORE_VOLUME", "CORE_FLUID"), ("CORE_VOLUME", "CORE_DENSITY"),
        ("CORE_VOLUME", "CORE_COLOR"), ("CORE_VOLUME", "CORE_CAUSTICS"),
        ("CORE_VOLUME", "CORE_GLASS"), ("CORE_FLUID", "CORE_DENSITY"),
        ("CORE_FLUID", "CORE_COLOR"), ("CORE_FLUID", "CORE_CAUSTICS"),
        ("CORE_FLUID", "CORE_GLASS"), ("CORE_DENSITY", "CORE_CAUSTICS"),
        ("CORE_DENSITY", "CORE_GLASS"), ("CORE_CAUSTICS", "CORE_GLASS"),
    }
    existing_edges = {(e.get("source"), e.get("target")) for e in typed_edges}
    missing_core_edges = required_edges - existing_edges
    if missing_core_edges:
        errors.append(f"Core Motion dependency contract incomplete: {sorted(missing_core_edges)}")
    for edge_key, required_outputs in CORE_REQUIRED_EDGE_OUTPUTS.items():
        actual_outputs = edge_outputs.get(edge_key, set())
        missing_outputs = required_outputs - actual_outputs
        if missing_outputs:
            errors.append(f"{edge_key[0]}->{edge_key[1]} is missing required outputs: {sorted(missing_outputs)}")
        target = next((e for e in elements if e.get("element_id") == edge_key[1]), None)
        if target is not None:
            declared_inputs = target.get("runtime_inputs")
            if not isinstance(declared_inputs, list) or any(not isinstance(v, str) or not v for v in declared_inputs) or not required_outputs.issubset(set(declared_inputs)):
                errors.append(f"{edge_key[1]} runtime_inputs do not consume {sorted(required_outputs)} from {edge_key[0]}")
    core_volume = next((e for e in elements if e.get("element_id") == "CORE_VOLUME"), None)
    if core_volume:
        pivot = core_volume.get("pivot")
        if pivot != [0, 0, 0] or core_volume.get("pivot_status") != "SOURCE_DOMAIN_ORIGIN_VERIFIED_NOT_DENSITY_CENTROID":
            errors.append("CORE_VOLUME pivot semantics must distinguish the source-domain origin from the density centroid")
        if core_volume.get("water_world_pivot_approved") is not False:
            errors.append("AI Orb source pivot must not be promoted to a Water World target pivot")
    for eid, expected in CORE_MODELS.items():
        element = next((e for e in elements if e.get("element_id") == eid), None)
        if element is None:
            continue
        if not isinstance(element.get("runtime_inputs"), list) or any(not isinstance(v, str) or not v for v in element.get("runtime_inputs", [])):
            errors.append(f"{eid}: runtime_inputs must be declared as a list")
        elif len(element["runtime_inputs"]) != len(set(element["runtime_inputs"])):
            errors.append(f"{eid}: runtime_inputs contains duplicates")
    return [{"check": "motion_contract", "status": "PASS" if not errors else "FAIL", "detail": errors or {"element_count": len(elements), "node_count": len(graph_nodes), "dependency_count": len(edges), "cycle_check": "PASS"}}]


def validate_master_source(path: Path, manifest: dict[str, Any] | None, validation: dict[str, Any] | None, analysis: dict[str, Any] | None) -> list[str]:
    errors: list[str] = []
    if not path.is_file():
        return ["MASTER_FILE_MISSING"]
    raw = path.read_bytes()
    if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
        errors.append("PNG_SIGNATURE_INVALID")
        return errors
    try:
        with Image.open(path) as image:
            if image.format != "PNG":
                errors.append("PNG_FORMAT_INVALID")
            image.verify()
        with Image.open(path) as image:
            image.load()
            width, height = image.size
            mode = image.mode
            has_alpha = "A" in image.getbands()
            icc_bytes = image.info.get("icc_profile")
            actual_icc = sha256_bytes(icc_bytes) if icc_bytes else "NONE"
    except Exception as exc:
        return errors + [f"PNG_DECODE_FAILED:{exc}"]
    digest = sha256_bytes(raw)
    actual = {"sha256": digest, "file_bytes": len(raw), "width": width, "height": height, "icc_profile_sha256": actual_icc, "has_alpha": has_alpha, "mode": mode}
    if width <= 0 or height <= 0:
        errors.append("PNG_DIMENSIONS_INVALID")
    if not isinstance(manifest, dict):
        errors.append("MASTER_MANIFEST_MISSING")
    else:
        compare = {
            "sha256": (manifest.get("sha256") or manifest.get("source_sha256"), digest),
            "file_bytes": (manifest.get("file_bytes") or manifest.get("bytes"), len(raw)),
            "width": (manifest.get("width") or manifest.get("pixel_width"), width),
            "height": (manifest.get("height") or manifest.get("pixel_height"), height),
            "icc_profile_sha256": (manifest.get("icc_profile_sha256", "NONE"), actual_icc),
            "has_alpha": (manifest.get("has_alpha"), has_alpha),
            "mode": (manifest.get("mode"), mode),
        }
        for key, (expected, got) in compare.items():
            if expected is None or expected != got:
                errors.append(f"MASTER_MANIFEST_MISMATCH:{key}:expected={expected}:actual={got}")
    if not isinstance(validation, dict) or validation.get("master_sha256") != digest or validation.get("png_decode") != "PASS":
        errors.append("MASTER_VALIDATION_EVIDENCE_MISSING_OR_MISMATCHED")
    analysis_sha = analysis.get("master_sha256") if isinstance(analysis, dict) else None
    if analysis_sha != digest:
        errors.append("MASTER_ANALYSIS_MISSING_OR_SHA_MISMATCHED")
    return errors


def missing_closure_artifacts(package: Path, final_zip: Path) -> list[str]:
    missing = [p for p in CLOSURE_ARTIFACTS if not (package / p).is_file() or (package / p).stat().st_size == 0]
    if not final_zip.is_file() or final_zip.stat().st_size == 0:
        missing.append(str(final_zip))
    return missing


def validate_final_zip(path: Path) -> list[str]:
    if not path.is_file():
        return ["FINAL_ZIP_MISSING"]
    try:
        with zipfile.ZipFile(path) as archive:
            bad = archive.testzip()
            if bad:
                return [f"FINAL_ZIP_CRC_FAILED:{bad}"]
            names = set(archive.namelist())
            manifest_name = next((n for n in ("MANIFEST/sha256_manifest.txt", "WATER_WORLD_V2_1/MANIFEST/sha256_manifest.txt", "08_MANIFEST/sha256_manifest.txt") if n in names), None)
            if not manifest_name:
                return ["FINAL_ZIP_SHA256_MANIFEST_MISSING"]
            failures = []
            manifest_lines = archive.read(manifest_name).decode("utf-8-sig").splitlines()
            seen_members: set[str] = set()
            valid_entries = 0
            for line in manifest_lines:
                match = re.match(r"^([0-9a-fA-F]{64})\s+[* ]?(.*)$", line.strip())
                if not match:
                    if line.strip():
                        failures.append(f"INVALID_MANIFEST_LINE:{line}")
                    continue
                expected, member = match.group(1).lower(), match.group(2).strip()
                if not member or member in seen_members:
                    failures.append(f"INVALID_OR_DUPLICATE_MANIFEST_MEMBER:{member}")
                    continue
                seen_members.add(member)
                valid_entries += 1
                if member not in names:
                    failures.append(f"MANIFEST_MEMBER_MISSING:{member}")
                elif sha256_bytes(archive.read(member)) != expected:
                    failures.append(f"MANIFEST_SHA_MISMATCH:{member}")
            if valid_entries == 0:
                failures.append("FINAL_ZIP_SHA256_MANIFEST_EMPTY")
            return failures
    except Exception as exc:
        return [f"FINAL_ZIP_READ_FAILED:{exc}"]


def expected_visual_metric_failures(path: Path) -> list[str]:
    if not path.is_file():
        return ["VISUAL_METRICS_MISSING"]
    try:
        data = load_json(path)
        metrics = data.get("metrics", data)
    except Exception as exc:
        return [f"VISUAL_METRICS_INVALID:{exc}"]
    failures: list[str] = []
    limits = {
        "sphere_iou": (0.98, "min"), "sphere_center_error_over_diameter": (0.005, "max"),
        "sphere_diameter_error": (0.01, "max"), "fish_iou": (0.90, "min"),
        "fish_center_error_over_canvas": (0.01, "max"),
        "resident_bidirectional_chamfer_over_radius": (0.015, "max"),
        "resident_width_error": (0.05, "max"), "plant_iou": (0.85, "min"), "rock_iou": (0.85, "min"),
    }
    for name, (threshold, direction) in limits.items():
        value = metrics.get(name)
        values = list(value.values()) if isinstance(value, dict) else (value if isinstance(value, list) else [value])
        if not values or any(not isinstance(v, (int, float)) or not math.isfinite(v) for v in values):
            failures.append(f"METRIC_MISSING_OR_INVALID:{name}")
            continue
        if direction == "min" and min(values) < threshold:
            failures.append(f"METRIC_BELOW_THRESHOLD:{name}")
        if direction == "max" and max(values) > threshold:
            failures.append(f"METRIC_ABOVE_THRESHOLD:{name}")
    return failures


def classify_exit(status: str) -> int:
    if status in ("PREFLIGHT_PASS", "WORK1_5_CLOSED", "WORK1_5_CLOSED_WITH_DECLARED_MOTION_GAPS", "MASTER_SOURCE_READY", "STATIC_RESOURCE_READY", "MOTION_CONTRACT_READY"):
        return EXIT_PASS
    if status == "WORK1_5_BLOCKED_MASTER_SOURCE":
        return EXIT_BLOCKED
    if status == "WORK1_5_VALIDATION_FAILED":
        return EXIT_FAILED
    return EXIT_INTERNAL


def run_validation(args: argparse.Namespace) -> tuple[dict[str, Any], int]:
    package = Path(args.package)
    checks: list[dict[str, Any]] = []
    archive_checks, archive_summary = validate_archive(Path(args.zip))
    checks.extend(archive_checks)
    registry = load_json(package / "04_MOTION/motion_element_registry.json")
    graph = load_json(package / "04_MOTION/motion_dependency_graph.json")
    checks.extend(validate_motion_data(registry, graph))
    core_checks, core_summary = validate_core_assets(Path(args.core_repo))
    checks.extend(core_checks)
    missing_contracts = [f for f in GPU_CONTRACT_FILES if not (package / "07_GPU_CONTRACT" / f).is_file() or (package / "07_GPU_CONTRACT" / f).stat().st_size == 0]
    checks.append({"check": "gpu_contract_files", "status": "PASS" if not missing_contracts else "FAIL", "detail": missing_contracts})
    preflight_failed = any(c["status"] == "FAIL" for c in checks)
    result: dict[str, Any] = {
        "schema_version": "water-world-v2-1-validator/2.0",
        "validation_scope": args.scope,
        "preflight_status": "WORK1_5_VALIDATION_FAILED" if preflight_failed else "PREFLIGHT_PASS",
        "master_source_status": "NOT_CHECKED",
        "static_resource_status": "BLOCKED_MASTER_SOURCE",
        "motion_contract_status": "MOTION_CONTRACT_READY" if not any(c["check"] == "motion_contract" and c["status"] == "FAIL" for c in checks) else "WORK1_5_VALIDATION_FAILED",
        "overall_status": None,
        "work1_5_status": None,
        "archive": archive_summary,
        "core_assets": core_summary,
        "checks": checks,
    }
    if preflight_failed:
        result["work1_5_status"] = "WORK1_5_VALIDATION_FAILED"
        result["overall_status"] = result["work1_5_status"]
        result["exit_code"] = EXIT_FAILED
        return result, EXIT_FAILED
    if args.scope == "preflight":
        result["work1_5_status"] = "PREFLIGHT_PASS"
        result["overall_status"] = result["work1_5_status"]
        result["exit_code"] = EXIT_PASS
        return result, EXIT_PASS
    master = Path(args.master) if args.master else package / "inputs/master/奇幻水晶球中的水下生态世界.png"
    if not master.is_file():
        result["master_source_status"] = "BLOCKED_MASTER_SOURCE"
        checks.append({"check": "master_source", "status": "BLOCKED", "detail": "raw PNG file bytes are unavailable"})
        result["work1_5_status"] = "WORK1_5_BLOCKED_MASTER_SOURCE"
        result["overall_status"] = result["work1_5_status"]
        result["exit_code"] = EXIT_BLOCKED
        return result, EXIT_BLOCKED
    metadata_dir = package / "00_MASTER_METADATA"
    try:
        master_errors = validate_master_source(
            master,
            load_json(metadata_dir / "master_source_manifest.json") if (metadata_dir / "master_source_manifest.json").is_file() else None,
            load_json(metadata_dir / "master_source_validation.json") if (metadata_dir / "master_source_validation.json").is_file() else None,
            load_json(package / "01_ANALYSIS/master_analysis.json") if (package / "01_ANALYSIS/master_analysis.json").is_file() else None,
        )
    except Exception as exc:
        master_errors = [f"MASTER_EVIDENCE_READ_FAILED:{exc}"]
    checks.append({"check": "master_source", "status": "PASS" if not master_errors else "FAIL", "detail": master_errors})
    if master_errors:
        result["master_source_status"] = "WORK1_5_VALIDATION_FAILED"
        result["work1_5_status"] = "WORK1_5_VALIDATION_FAILED"
        result["overall_status"] = result["work1_5_status"]
        result["exit_code"] = EXIT_FAILED
        return result, EXIT_FAILED
    result["master_source_status"] = "MASTER_SOURCE_READY"
    final_zip = Path(args.final_zip) if args.final_zip else package.parent / "WATER_WORLD_V2_1_ASSETS.zip"
    missing = missing_closure_artifacts(package, final_zip)
    metric_path = package / "03_STATIC_RECOMPOSITION/visual_metrics.json"
    metric_errors = expected_visual_metric_failures(metric_path)
    zip_errors = validate_final_zip(final_zip)
    checks.extend([
        {"check": "static_closure_artifacts", "status": "PASS" if not missing else "FAIL", "detail": missing},
        {"check": "visual_metrics", "status": "PASS" if not metric_errors else "FAIL", "detail": metric_errors},
        {"check": "final_package", "status": "PASS" if not zip_errors else "FAIL", "detail": zip_errors},
    ])
    static_failed = bool(missing or metric_errors or zip_errors)
    result["static_resource_status"] = "WORK1_5_VALIDATION_FAILED" if static_failed else "STATIC_RESOURCE_READY"
    if static_failed:
        result["work1_5_status"] = "WORK1_5_VALIDATION_FAILED"
        result["overall_status"] = result["work1_5_status"]
        result["exit_code"] = EXIT_FAILED
        return result, EXIT_FAILED
    gaps = sum(e.get("motion_status") == "NEEDS_TEMPORAL_REFERENCE" for e in registry["elements"])
    final_status = "WORK1_5_CLOSED_WITH_DECLARED_MOTION_GAPS" if gaps else "WORK1_5_CLOSED"
    result["work1_5_status"] = final_status
    result["overall_status"] = final_status
    result["exit_code"] = EXIT_PASS
    return result, EXIT_PASS


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--core-repo", required=True)
    parser.add_argument("--master")
    parser.add_argument("--final-zip")
    parser.add_argument("--scope", choices=("preflight", "closure"), default="closure")
    args = parser.parse_args(argv)
    package = Path(args.package)
    output_path = package / "06_VALIDATION/validation_manifest.json"
    try:
        result, exit_code = run_validation(args)
    except Exception as exc:
        result = {"schema_version": "water-world-v2-1-validator/2.0", "validation_scope": args.scope, "overall_status": "WORK1_5_VALIDATION_FAILED", "work1_5_status": "WORK1_5_VALIDATION_FAILED", "exit_code": EXIT_INTERNAL, "internal_error": repr(exc), "checks": []}
        exit_code = EXIT_INTERNAL
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
