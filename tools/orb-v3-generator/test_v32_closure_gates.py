#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 Final Closure Automated Gate & Negative CI Test Suite

Covers:
  1. Single-source 3D Fluid Volume Algorithm Consistency (`canonical_fluid_volume` ==
     `volume_structure_fitter` == `generate_orb_volume` == Lossless WebP on disk) across
     fixed XYZ probes (center, upper/lower, left/right, front/rear, emerald/sapphire/aqua,
     boundary) + exact RGBA8 quantization bounds + Lossless WebP decode round-trip.
  2. Unified Crystal Lens Geometry Consistency across Python (`cavity_geometry_fitter.py`),
     GLSL (`CoreFluidShaders.kt`), and Kotlin (`CrystalLensGeometry.kt`):
       - `CRYSTAL_LENS_ASYM_SHEAR = 0.08` exact parity
       - Ray enter/exit SDF residuals |phi(ro + rd * t)| < 1e-6
       - Analytic normal vs central-difference normal angle/L2 error < 1e-5
       - Grazing-angle stability and non-NaN behavior
  3. All 10 Mandatory Negative CI Gate Tests executing the real `validate_fit.py --root <tmp>`
     CLI in an isolated temporary workspace copy and asserting `returncode != 0`:
       (1) E05 area fail (> 15%)
       (2) E06 area fail (> 15%)
       (3) E06 centroid fail (> 0.04D)
       (4) E07 area fail (> 15%)
       (5) E07 centroid fail (> 0.04D)
       (6) Missing required hard-gate metric field
       (7) NaN / non-finite metric value
       (8) Missing WebP volume slice
       (9) MASTER_FIDELITY_V32.json SHA-256 mismatch
       (10) ORB_V3_ASSET_MANIFEST.json vs WebP file SHA-256 mismatch
     Plus positive baseline pass verification (`returncode == 0`).
"""

from __future__ import annotations

import json
import math
import random
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

GEN_DIR = Path(__file__).resolve().parent
FITTING_DIR = GEN_DIR / "fitting"
ROOT = GEN_DIR.parent.parent

for _p in (GEN_DIR, FITTING_DIR):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

from canonical_fluid_volume import (
    VOL_D,
    VOL_H,
    VOL_W,
    decode_webp_volume_3d,
    evaluate_canonical_voxel,
    extract_volume_params,
    render_canonical_slice_rgba,
)
from cavity_geometry_fitter import (
    CRYSTAL_LENS_ASYM_SHEAR,
    crystal_lens_normal,
    crystal_lens_phi,
    intersect_crystal_lens,
)


def assert_true(cond: bool, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)


def test_1_canonical_volume_algorithm_and_webp_roundtrip() -> None:
    cfg_path = ROOT / "app/src/main/assets/orb_v3/manifest/MASTER_FIDELITY_V32.json"
    vol_dir = ROOT / "app/src/main/assets/orb_v3/volume"
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    params = extract_volume_params(cfg)

    # Verify volume_structure_fitter and generate_orb_volume both bind to canonical_fluid_volume
    fitter_src = (FITTING_DIR / "volume_structure_fitter.py").read_text(encoding="utf-8")
    gen_src = (GEN_DIR / "generate_orb_volume.py").read_text(encoding="utf-8")
    assert_true("from canonical_fluid_volume import" in fitter_src, "Fitter must import canonical_fluid_volume")
    assert_true("from canonical_fluid_volume import" in gen_src, "Generator must import canonical_fluid_volume")

    # Decode actual 32 Lossless WebP slices from disk
    disk_slices = decode_webp_volume_3d(vol_dir)
    assert_true(len(disk_slices) == VOL_D, f"Expected {VOL_D} slices, got {len(disk_slices)}")

    # Fixed probe suite across 3D sphere coordinates (ix, iy, iz) covering center, upper/lower,
    # left/right, front/rear, emerald/sapphire/aqua pockets, and boundary
    probes = [
        ("center", 128, 128, 16),
        ("upper_crystal", 148, 60, 18),
        ("lower_aqua", 128, 198, 22),
        ("left_emerald", 72, 118, 20),
        ("right_emerald", 184, 146, 20),
        ("sapphire_valley", 156, 124, 18),
        ("front_shell", 128, 140, 27),
        ("rear_shell", 128, 140, 5),
        ("corner_boundary", 4, 4, 0),
        ("outer_rim", 232, 128, 16),
    ]

    tested_slices: dict[int, bytes] = {}
    max_quant_err = 0.0
    for label, ix, iy, iz in probes:
        x = ((ix + 0.5) / VOL_W) * 2.0 - 1.0
        y = 1.0 - ((iy + 0.5) / VOL_H) * 2.0
        z = ((iz + 0.5) / VOL_D) * 2.0 - 1.0

        # 1) Canonical analytical voxel evaluation
        rf, gf, bf, af = evaluate_canonical_voxel(x, y, z, params)
        exp_rgba = (
            max(0, min(255, int(round(rf * 255.0)))),
            max(0, min(255, int(round(gf * 255.0)))),
            max(0, min(255, int(round(bf * 255.0)))),
            max(0, min(255, int(round(af * 255.0)))),
        )

        # 2) Canonical slice renderer (used 1:1 by generate_orb_volume.py)
        if iz not in tested_slices:
            tested_slices[iz] = render_canonical_slice_rgba(iz, params)
        off = (iy * VOL_W + ix) * 4
        gen_rgba = tuple(tested_slices[iz][off : off + 4])
        assert_true(
            gen_rgba == exp_rgba,
            f"Canonical vs Generator mismatch at {label} ({ix},{iy},{iz}): {gen_rgba} != {exp_rgba}",
        )

        # 3) Lossless WebP disk slice exact pixel round-trip (0 byte error!)
        disk_rgba = tuple(disk_slices[iz][off : off + 4])
        assert_true(
            disk_rgba == exp_rgba,
            f"Lossless WebP disk round-trip mismatch at {label} ({ix},{iy},{iz}): {disk_rgba} != {exp_rgba}",
        )

        # 4) Float-to-RGBA8 quantization error <= 0.5/255 + 1e-6
        for fval, ival in zip((rf, gf, bf, af), disk_rgba):
            err = abs(fval - (ival / 255.0))
            if err > max_quant_err:
                max_quant_err = err
            assert_true(
                err <= (0.5 / 255.0) + 1e-5,
                f"RGBA8 quantization error {err:.6f} exceeded bound at {label}",
            )

    print(
        f"[test_1_canonical_volume] PASS: {len(probes)} 3D probes exact 0-byte Lossless WebP match, "
        f"maxQuantErr={max_quant_err:.6f}"
    )


def test_2_crystal_lens_geometry_consistency() -> None:
    cfg = json.loads(
        (ROOT / "app/src/main/assets/orb_v3/manifest/MASTER_FIDELITY_V32.json").read_text(encoding="utf-8")
    )
    cav = cfg["innerCavity"]
    cx = float(cav["lensCenterX"]["fittedValue"])
    cy = float(cav["lensCenterY"]["fittedValue"])
    cz = float(cav["lensCenterZ"]["fittedValue"])
    rx = float(cav["lensRadiusX"]["fittedValue"])
    ry = float(cav["lensRadiusY"]["fittedValue"])
    rz = float(cav["lensRadiusZ"]["fittedValue"])

    # Verify cross-language constant parity in GLSL and Kotlin
    shader_src = (ROOT / "app/src/main/java/com/example/orb/gl/CoreFluidShaders.kt").read_text(encoding="utf-8")
    kt_src = (ROOT / "app/src/main/java/com/example/orb/v3/CrystalLensGeometry.kt").read_text(encoding="utf-8")
    assert_true(
        "const float CRYSTAL_LENS_ASYM_SHEAR = 0.08;" in shader_src,
        "GLSL CoreFluidShaders.kt missing CRYSTAL_LENS_ASYM_SHEAR = 0.08",
    )
    assert_true(
        "const val ASYM_SHEAR = 0.08f" in kt_src,
        "Kotlin CrystalLensGeometry.kt missing ASYM_SHEAR = 0.08f",
    )
    assert_true(abs(CRYSTAL_LENS_ASYM_SHEAR - 0.08) < 1e-9, "Python CRYSTAL_LENS_ASYM_SHEAR != 0.08")

    # Verify center is inside (phi == -1.0)
    phi_center = crystal_lens_phi(cx, cy, cz, cx, cy, cz, rx, ry, rz)
    assert_true(abs(phi_center + 1.0) < 1e-9, f"Center phi expected -1.0, got {phi_center}")

    rng = random.Random(20261122)
    hit_count = 0
    max_phi_residual = 0.0
    max_normal_err = 0.0
    eps = 1e-6

    for _ in range(120):
        # Sample external ray aimed near lens center (including oblique & grazing angles)
        ox = cx + rng.uniform(-1.2, 1.2)
        oy = cy + rng.uniform(-1.2, 1.2)
        oz = cz + rng.uniform(0.9, 1.8)
        tx = cx + rng.uniform(-rx * 1.15, rx * 1.15)
        ty = cy + rng.uniform(-ry * 1.15, ry * 1.15)
        tz = cz + rng.uniform(-rz * 0.5, rz * 0.5)
        dx, dy, dz = tx - ox, ty - oy, tz - oz
        dl = math.sqrt(dx * dx + dy * dy + dz * dz)
        dx, dy, dz = dx / dl, dy / dl, dz / dl

        hit, t0, t1, thick = intersect_crystal_lens(ox, oy, oz, dx, dy, dz, 10.0, cx, cy, cz, rx, ry, rz)
        if not hit:
            continue
        hit_count += 1
        assert_true(t1 > t0 and abs((t1 - t0) - thick) < 1e-9, "Invalid intersection interval")
        for t in (t0, t1):
            px, py, pz = ox + dx * t, oy + dy * t, oz + dz * t
            res = abs(crystal_lens_phi(px, py, pz, cx, cy, cz, rx, ry, rz))
            if res > max_phi_residual:
                max_phi_residual = res
            assert_true(res < 1e-6, f"Ray surface residual too large: {res}")

            nx, ny, nz = crystal_lens_normal(px, py, pz, cx, cy, cz, rx, ry, rz)
            nlen = math.sqrt(nx * nx + ny * ny + nz * nz)
            assert_true(abs(nlen - 1.0) < 1e-6, f"Normal not unit length: {nlen}")

            # Central-difference gradient check
            gx = (
                crystal_lens_phi(px + eps, py, pz, cx, cy, cz, rx, ry, rz)
                - crystal_lens_phi(px - eps, py, pz, cx, cy, cz, rx, ry, rz)
            ) / (2.0 * eps)
            gy = (
                crystal_lens_phi(px, py + eps, pz, cx, cy, cz, rx, ry, rz)
                - crystal_lens_phi(px, py - eps, pz, cx, cy, cz, rx, ry, rz)
            ) / (2.0 * eps)
            gz = (
                crystal_lens_phi(px, py, pz + eps, cx, cy, cz, rx, ry, rz)
                - crystal_lens_phi(px, py, pz - eps, cx, cy, cz, rx, ry, rz)
            ) / (2.0 * eps)
            gl = math.sqrt(gx * gx + gy * gy + gz * gz)
            cdx, cdy, cdz = gx / gl, gy / gl, gz / gl
            nerr = math.sqrt((nx - cdx) ** 2 + (ny - cdy) ** 2 + (nz - cdz) ** 2)
            if nerr > max_normal_err:
                max_normal_err = nerr
            assert_true(nerr < 1e-5, f"Analytic normal mismatch vs central-diff: {nerr}")

    assert_true(hit_count >= 40, f"Expected >= 40 lens ray hits, got {hit_count}")
    print(
        f"[test_2_crystal_lens] PASS: {hit_count} rays verified, "
        f"maxPhiResidual={max_phi_residual:.2e}, maxNormalErr={max_normal_err:.2e}"
    )


def _create_isolated_workspace_copy(tmp_dir: Path) -> Path:
    """Copies only the exact files validate_fit.py inspects into a fast isolated workspace."""
    rel_dirs = [
        "app/src/main/assets/orb_v3",
        "docs/v3",
        "tools/orb-v3-generator/fitting",
    ]
    for rel in rel_dirs:
        src = ROOT / rel
        dst = tmp_dir / rel
        shutil.copytree(src, dst)
    return tmp_dir


def _run_validate_fit_cli(root_dir: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(FITTING_DIR / "validate_fit.py"), "--root", str(root_dir)],
        capture_output=True,
        text=True,
        check=False,
    )


def _mutate_json(path: Path, fn) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    fn(data)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def test_3_ten_negative_ci_gate_tests() -> None:
    with tempfile.TemporaryDirectory(prefix="ai_orb_v32_neg_ci_") as base_tmp:
        base_ws = _create_isolated_workspace_copy(Path(base_tmp) / "baseline")

        # 0) Positive control: unmodified isolated workspace MUST exit 0
        pos_res = _run_validate_fit_cli(base_ws)
        assert_true(
            pos_res.returncode == 0,
            f"Positive baseline failed! rc={pos_res.returncode}\nstdout={pos_res.stdout}\nstderr={pos_res.stderr}",
        )
        print("[test_3_ci_gates] Baseline isolated workspace PASS (exit 0)")

        def run_neg_case(name: str, mutator) -> None:
            case_ws = Path(base_tmp) / name
            shutil.copytree(base_ws, case_ws)
            mutator(case_ws)
            res = _run_validate_fit_cli(case_ws)
            assert_true(
                res.returncode != 0,
                f"NEGATIVE CI TEST FAILED [{name}]: validate_fit.py returned 0 instead of non-zero!\nstdout={res.stdout}",
            )
            print(f"  [PASS negative] {name} -> exit {res.returncode}")

        vl_rel = Path("docs/v3/AI_ORB_V3_2_VISUAL_LOSS.json")
        cfg_rel = Path("app/src/main/assets/orb_v3/manifest/MASTER_FIDELITY_V32.json")
        z16_rel = Path("app/src/main/assets/orb_v3/volume/fluid_color_z16.webp")

        # 1. E05 area fail (even if someone spoofed pass=True, validator must still reject!)
        run_neg_case(
            "neg_01_e05_area_fail",
            lambda ws: _mutate_json(
                ws / vl_rel,
                lambda d: d["hardGates"]["E05_emeraldAreaRelErr_le_15pct"].update({"value": 0.22, "pass": True}),
            ),
        )

        # 2. E06 area fail
        run_neg_case(
            "neg_02_e06_area_fail",
            lambda ws: _mutate_json(
                ws / vl_rel,
                lambda d: d["hardGates"]["E06_sapphireAreaRelErr_le_15pct"].update({"value": 0.19, "pass": False}),
            ),
        )

        # 3. E06 centroid fail
        run_neg_case(
            "neg_03_e06_centroid_fail",
            lambda ws: _mutate_json(
                ws / vl_rel,
                lambda d: d["hardGates"]["E06_sapphireCentroid_le_004D"].update({"value": 0.055, "pass": False}),
            ),
        )

        # 4. E07 area fail
        run_neg_case(
            "neg_04_e07_area_fail",
            lambda ws: _mutate_json(
                ws / vl_rel,
                lambda d: d["hardGates"]["E07_aquaAreaRelErr_le_15pct"].update({"value": 0.28, "pass": False}),
            ),
        )

        # 5. E07 centroid fail
        run_neg_case(
            "neg_05_e07_centroid_fail",
            lambda ws: _mutate_json(
                ws / vl_rel,
                lambda d: d["hardGates"]["E07_aquaCentroid_le_004D"].update({"value": 0.062, "pass": False}),
            ),
        )

        # 6. Missing metric field
        run_neg_case(
            "neg_06_missing_metric_field",
            lambda ws: _mutate_json(
                ws / vl_rel,
                lambda d: d["hardGates"].pop("E05_emeraldCentroid_le_004D", None),
            ),
        )

        # 7. NaN / non-finite metric value
        run_neg_case(
            "neg_07_nan_metric_value",
            lambda ws: _mutate_json(
                ws / vl_rel,
                lambda d: d["hardGates"]["E05_emeraldAreaRelErr_le_15pct"].update({"value": float("nan"), "pass": True}),
            ),
        )

        # 8. Missing WebP slice
        run_neg_case(
            "neg_08_missing_webp_slice",
            lambda ws: (ws / z16_rel).unlink(),
        )

        # 9. MASTER_FIDELITY_V32.json SHA-256 mismatch
        run_neg_case(
            "neg_09_master_fidelity_sha_mismatch",
            lambda ws: (ws / cfg_rel).write_text((ws / cfg_rel).read_text(encoding="utf-8") + " ", encoding="utf-8"),
        )

        # 10. ORB_V3_ASSET_MANIFEST.json vs WebP file SHA-256 mismatch
        def corrupt_slice(ws: Path) -> None:
            p = ws / z16_rel
            raw = bytearray(p.read_bytes())
            raw[-1] ^= 0xFF
            p.write_bytes(bytes(raw))

        run_neg_case("neg_10_webp_manifest_sha_mismatch", corrupt_slice)

    print("[test_3_ci_gates] PASS: All 10/10 negative CI gate tests rejected strictly with non-zero exit!")


def main() -> int:
    test_1_canonical_volume_algorithm_and_webp_roundtrip()
    test_2_crystal_lens_geometry_consistency()
    test_3_ten_negative_ci_gate_tests()
    print("[test_v32_closure_gates] ALL V3.2 CLOSURE GATES PASSED!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
