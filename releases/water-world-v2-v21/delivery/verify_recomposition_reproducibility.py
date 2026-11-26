#!/usr/bin/env python3
"""Run the CPU composition twice from clean package copies and compare outputs."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ARTIFACTS = (
    "03_STATIC_RECOMPOSITION/static_recomposition.png",
    "03_STATIC_RECOMPOSITION/master_reference.png",
    "03_STATIC_RECOMPOSITION/master_overlay.png",
    "03_STATIC_RECOMPOSITION/difference_heatmap.png",
    "03_STATIC_RECOMPOSITION/region_comparison.png",
    "03_STATIC_RECOMPOSITION/visual_metrics.json",
)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(package: Path) -> dict:
    repo = Path(__file__).resolve().parents[2]
    outputs: dict[str, dict[str, str]] = {}
    with tempfile.TemporaryDirectory(prefix="water-world-v21-recompose-") as temp:
        root = Path(temp)
        for label in ("run_a", "run_b"):
            target = root / label / "WATER_WORLD_V2_1"
            target.parent.mkdir(parents=True)
            shutil.copytree(package, target, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
            for script in ("analyze_recompose_master.py", "canonical_metrics.py"):
                command = [sys.executable, str(repo / "tools/water-world-v2-1" / script),
                           "--package-root", str(target)]
                subprocess.run(command, cwd=repo, check=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True)
            outputs[label] = {}
            for rel in ARTIFACTS:
                path = target / rel
                if not path.is_file():
                    raise FileNotFoundError(path)
                outputs[label][rel] = sha(path)
    mismatches = [rel for rel in ARTIFACTS if outputs["run_a"][rel] != outputs["run_b"][rel]]
    result = {
        "schema_version": "water-world-v2-1-cpu-recomposition-reproducibility/1.0",
        "status": "PASS" if not mismatches else "FAIL",
        "render_type": "CPU_STATIC_RECOMPOSITION_FROM_INDEPENDENT_V2_ASSETS",
        "master_sha256": "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589",
        "runs": outputs,
        "mismatched_artifacts": mismatches,
        "gpu_validation": "NOT_RUN",
    }
    out = package / "06_VALIDATION/recomposition_reproducibility.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if mismatches:
        raise SystemExit(1)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package-root", type=Path, required=True)
    args = parser.parse_args()
    run(args.package_root.resolve())


if __name__ == "__main__":
    main()
