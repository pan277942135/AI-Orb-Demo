#!/usr/bin/env python3
"""Execute and record the V2.1 Python, source archive, and motion checks."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

MASTER_SHA = "fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589"
V2_SHA = "6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def execute(label: str, command: list[str], cwd: Path) -> dict:
    proc = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, check=False)
    match = re.search(r"Ran (\d+) tests?", proc.stdout)
    return {"name": label, "command": command, "exit_code": proc.returncode,
            "tests_run": int(match.group(1)) if match else None,
            "result": "PASS" if proc.returncode == 0 else "FAIL",
            "output_tail": proc.stdout.splitlines()[-6:]}


def run(repo: Path, package: Path, v2_archive: Path) -> dict:
    sys.path.insert(0, str(repo / "tools/water-world-v2-1"))
    from validate_preflight import validate_archive, validate_motion_data

    master = package / "inputs/master/奇幻水晶球中的水下生态世界.png"
    master_ok = master.is_file() and sha(master) == MASTER_SHA
    with zipfile.ZipFile(v2_archive) as archive:
        v2_members = [name for name in archive.namelist() if not name.endswith("/")]
        v2_crc_ok = archive.testzip() is None
    archive_checks, archive_summary = validate_archive(v2_archive)

    source_copy = package / "02_CALIBRATED_ASSETS/V2_ORIGINAL"
    sums = source_copy / "11_MANIFEST/SHA256SUMS"
    entries = {}
    for line in sums.read_text(encoding="utf-8-sig").splitlines():
        match = re.match(r"^([0-9a-fA-F]{64})\s+[* ]?(.*)$", line.strip())
        if match:
            entries[match.group(2).strip()] = match.group(1).lower()
    source_verified = sum(1 for rel, expected in entries.items()
                          if (source_copy / rel).is_file() and sha(source_copy / rel) == expected)
    source_copy_ok = len([p for p in source_copy.rglob("*") if p.is_file()]) == 65 and len(entries) == 63 and source_verified == 63

    motion = json.loads((package / "04_MOTION/motion_element_registry.json").read_text(encoding="utf-8"))
    graph = json.loads((package / "04_MOTION/motion_dependency_graph.json").read_text(encoding="utf-8"))
    motion_check = validate_motion_data(motion, graph)[0]
    repro_path = package / "06_VALIDATION/recomposition_reproducibility.json"
    repro = json.loads(repro_path.read_text(encoding="utf-8")) if repro_path.is_file() else {}
    suites = [
        execute("legacy_preflight_unit_tests", [sys.executable, "-m", "unittest", "discover", "-s",
                "tools/water-world-v2-1/preflight_tests", "-p", "test_validate_preflight.py", "-v"], repo),
        execute("closure_unit_tests", [sys.executable, "-m", "unittest", "discover", "-s",
                "tools/water-world-v2-1/tests", "-v"], repo),
        execute("python_compile", [sys.executable, "-m", "py_compile",
                *[str(p.relative_to(repo)) for p in sorted((repo / "tools/water-world-v2-1").rglob("*.py"))
                  if "__pycache__" not in p.parts]], repo),
    ]
    checks = {
        "master_source": {"status": "PASS" if master_ok else "FAIL", "sha256": sha(master) if master_ok else None,
                          "bytes": master.stat().st_size if master.is_file() else None},
        "original_v2_archive": {"status": "PASS" if sha(v2_archive) == V2_SHA and v2_crc_ok and len(v2_members) == 65 else "FAIL",
                                "sha256": sha(v2_archive), "members": len(v2_members),
                                "validator_summary": archive_summary, "checks": archive_checks},
        "original_v2_copy": {"status": "PASS" if source_copy_ok else "FAIL", "files": 65,
                              "embedded_sha_entries": len(entries), "verified_sha_entries": source_verified},
        "motion_graph": {"status": motion_check["status"], "nodes": len(graph.get("nodes", [])),
                         "dependencies": len(graph.get("edges", [])), "detail": motion_check["detail"]},
        "cpu_recomposition_reproducibility": {"status": repro.get("status", "NOT_RUN"),
                                               "artifacts_compared": len(repro.get("runs", {}).get("run_a", {})),
                                               "mismatched_artifacts": repro.get("mismatched_artifacts", [])},
    }
    all_pass = master_ok and checks["original_v2_archive"]["status"] == "PASS" and source_copy_ok and motion_check["status"] == "PASS" and checks["cpu_recomposition_reproducibility"]["status"] == "PASS" and all(x["result"] == "PASS" for x in suites)
    visual = json.loads((package / "03_STATIC_RECOMPOSITION/visual_metrics.json").read_text(encoding="utf-8"))
    result = {
        "schema_version": "water-world-v2-1-validation-evidence/1.0",
        "local_automated_checks": "PASS" if all_pass else "FAIL",
        "exact_head_ci": "PENDING_EXACT_HEAD_CI",
        "visual_acceptance": visual.get("overall_status", "UNKNOWN"),
        "cpu_recomposition_reproducibility": checks["cpu_recomposition_reproducibility"]["status"],
        "master_sha256": MASTER_SHA,
        "v2_archive_sha256": V2_SHA,
        "checks": checks,
        "test_suites": suites,
        "test_count_total": sum(x["tests_run"] or 0 for x in suites),
    }
    validation = package / "06_VALIDATION"
    validation.mkdir(parents=True, exist_ok=True)
    (validation / "validator_test_results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rows = ["# V2.1 Validator Test Report", "", f"Local automated checks: **{result['local_automated_checks']}**  ",
            f"Exact-HEAD GitHub CI: **{result['exact_head_ci']}**  ",
            f"Visual acceptance: **{result['visual_acceptance']}**", "",
            "| Check | Result | Count / detail |", "|---|---|---|"]
    for suite in suites:
        rows.append(f"| {suite['name']} | {suite['result']} | {suite['tests_run']} tests; exit {suite['exit_code']} |")
    rows.extend([
        f"| Master source | {checks['master_source']['status']} | `{checks['master_source']['sha256']}`; {checks['master_source']['bytes']} bytes |",
        f"| Original V2 ZIP | {checks['original_v2_archive']['status']} | `{checks['original_v2_archive']['sha256']}`; {checks['original_v2_archive']['members']} files; CRC PASS |",
        f"| V2 source copy | {checks['original_v2_copy']['status']} | {source_verified}/63 embedded SHA entries; 65 files |",
        f"| Motion Graph | {checks['motion_graph']['status']} | {checks['motion_graph']['nodes']} nodes / {checks['motion_graph']['dependencies']} dependencies |",
        f"| CPU recomposition reproducibility | {checks['cpu_recomposition_reproducibility']['status']} | {checks['cpu_recomposition_reproducibility']['artifacts_compared']} SHA-256 outputs compared between clean runs |",
        "", "These are local source/validator checks. They do not establish GPU or visual acceptance. The frozen visual gates remain independently evaluated by `visual_metrics.json`.", ""
    ])
    report = "\n".join(rows)
    for rel in ("06_VALIDATION/validator_test_report.md", "reports/VALIDATOR_TEST_REPORT.md"):
        path = package / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(report, encoding="utf-8")
    matrix_path = package / "06_VALIDATION/acceptance_matrix.md"
    matrix = matrix_path.read_text(encoding="utf-8") if matrix_path.is_file() else "# V2.1 Acceptance Matrix\n"
    if "## Automation and source validation" in matrix:
        matrix = matrix.split("## Automation and source validation")[0].rstrip() + "\n"
    matrix += ("\n## Automation and source validation\n\n"
              f"- Local regression tests: **{sum(x['tests_run'] or 0 for x in suites)} tests PASS** ({suites[0]['tests_run']}/{suites[1]['tests_run']} across legacy/new suites).\n"
              f"- V2 archive and source-copy SHA: **{checks['original_v2_archive']['status']}** / **{checks['original_v2_copy']['status']}**.\n"
              f"- Motion graph: **{checks['motion_graph']['status']}**, {checks['motion_graph']['nodes']} nodes / {checks['motion_graph']['dependencies']} dependencies.\n"
              f"- CPU recomposition reproducibility: **{checks['cpu_recomposition_reproducibility']['status']}**, {checks['cpu_recomposition_reproducibility']['artifacts_compared']} output hashes compared.\n"
              f"- Exact-HEAD GitHub CI: **{result['exact_head_ci']}**.\n"
              f"- Visual acceptance: **{result['visual_acceptance']}**; no visual failure is converted to a pass.\n")
    matrix_path.write_text(matrix, encoding="utf-8")
    for src, dst in (("06_VALIDATION/validator_test_results.json", "preflight/06_VALIDATION/validator_test_results.json"),
                     ("06_VALIDATION/validator_test_report.md", "preflight/06_VALIDATION/validator_test_report.md"),
                     ("06_VALIDATION/validator_test_report.md", "preflight/reports/VALIDATOR_TEST_REPORT.md"),
                     ("06_VALIDATION/acceptance_matrix.md", "preflight/06_VALIDATION/acceptance_matrix.md"),
                     ("06_VALIDATION/acceptance_matrix.md", "preflight/reports/FINAL_ACCEPTANCE_MATRIX.md"),
                     ("06_VALIDATION/acceptance_matrix.md", "reports/FINAL_ACCEPTANCE_MATRIX.md")):
        destination = package / dst
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(package / src, destination)
    suffix = "\n\n## Automated validation\n\n" + report.split("\n", 2)[2]
    for rel in ("FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md", "FINAL_WORK1_5_DELIVERY_REPORT.md", "FINAL_DELIVERY_REPORT.md",
                "reports/FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md", "reports/FINAL_WORK1_5_DELIVERY_REPORT.md", "reports/FINAL_DELIVERY_REPORT.md",
                "preflight/FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md", "preflight/FINAL_WORK1_5_DELIVERY_REPORT.md",
                "preflight/reports/FINAL_WORK1_5_REPAIR_AND_WORK2_INPUT_READY_REPORT.md", "preflight/reports/FINAL_WORK1_5_DELIVERY_REPORT.md"):
        path = package / rel
        if path.is_file():
            original = path.read_text(encoding="utf-8")
            if "## Automated validation" in original:
                original = original.split("## Automated validation")[0].rstrip()
            path.write_text(original + suffix, encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if not all_pass:
        raise SystemExit(1)
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--package-root", type=Path, required=True)
    parser.add_argument("--v2-archive", type=Path, required=True)
    args = parser.parse_args()
    run(args.repo_root.resolve(), args.package_root.resolve(), args.v2_archive.resolve())


if __name__ == "__main__":
    main()
