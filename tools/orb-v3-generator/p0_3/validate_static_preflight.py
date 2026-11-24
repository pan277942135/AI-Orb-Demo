#!/usr/bin/env python3
"""Check P0-3 source contracts that do not require a running GLES device."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    kotlin_config_path = ROOT / "app/src/main/java/com/example/orb/config/AIOrbConfig.kt"
    renderer_path = ROOT / "app/src/main/java/com/example/orb/scene/OrbFluidVolumeRenderer.kt"
    scene_path = ROOT / "app/src/main/java/com/example/orb/scene/AIOrbScene.kt"
    geometry_path = ROOT / "app/src/main/java/com/example/orb/geometry/OrbGeometryBuilder.kt"
    capture_path = ROOT / "app/src/main/java/com/example/orb/v3/OrbGpuReferenceCapture.kt"
    master_path = ROOT / "app/src/main/assets/orb_v3/manifest/MASTER_FIDELITY_V32.json"
    schema_path = ROOT / "tools/orb-v3-generator/fitting/parameter_schema.py"
    loss_path = ROOT / "docs/v3/AI_ORB_V3_2_VISUAL_LOSS.json"

    config_src = kotlin_config_path.read_text()
    high_tier = re.search(r"HIGH\((.*?)^  \),", config_src, re.MULTILINE | re.DOTALL)
    assert high_tier, "Could not locate QualityTier.HIGH"
    hardware_steps = int(re.search(r"raymarchSteps\s*=\s*(\d+)", high_tier.group(1)).group(1))
    high_subdivisions = int(re.search(r"icosphereSubdivisions\s*=\s*(\d+)", high_tier.group(1)).group(1))

    renderer = renderer_path.read_text()
    assert "minOf(config.quality.raymarchSteps, 20)" in renderer
    assert "prog.setInt(\"uRaymarchSteps\", effectiveSteps)" in renderer
    assert "geometrySubdivisions = sphere.subdivisionLevel" in renderer
    assert "geometryFingerprint = sphere.geometryFingerprint" in renderer
    assert "draw(" in renderer and "rebuildMesh(subdivisions)" in renderer

    geometry = geometry_path.read_text()
    assert "geometryFingerprint = geometryFingerprint" in geometry
    assert "vertexCount = vertices.size" in geometry
    assert "triangleCount = faces.size" in geometry

    scene = scene_path.read_text()
    assert "core.init(cfg.quality.icosphereSubdivisions" in scene
    assert "core.ensureQuality(effectiveOrbCfg.quality.icosphereSubdivisions)" in scene

    master = json.loads(master_path.read_text())
    master_steps = master["quality"]["raymarchStepsHigh"]["fittedValue"]
    assert master_steps == hardware_steps == 48, f"HIGH raymarch mismatch: Master={master_steps} source={hardware_steps}"
    assert master["quality"]["raymarchStepsHigh"]["source"] == "PRODUCTION_QUALITY_TIER_HIGH"
    assert 'param("raymarchStepsHigh", "steps", 48, 24, 48, False, "PRODUCTION_QUALITY_TIER_HIGH")' in schema_path.read_text()
    assert json.loads(loss_path.read_text())["masterFidelityConfigSha256"] == sha(master_path)

    capture_src = capture_path.read_text()
    required_modes = json.loads((ROOT / "docs/p0_3/contract/AI_ORB_P0_3_ACCEPTANCE_CONTRACT_V1.json").read_text())["capture_contract"]["required_render_modes"]
    capture_modes = re.findall(r"GpuCaptureStep\(\"[^\"]+\", RenderDebugMode\.(\w+)\)", capture_src)
    assert capture_modes[: len(required_modes)] == required_modes, f"Required capture order differs from frozen contract: {capture_modes[:len(required_modes)]}"
    assert '"CAPTURE_MANIFEST.json"' in capture_src and '"RUNTIME_TELEMETRY.json"' in capture_src
    renderer = renderer_path.read_text()
    assert 'prog.getIntUniform("uRaymarchSteps")' in renderer, "PF02 requires reading the linked program's actual uRaymarchSteps uniform"
    assert 'prog.getIntUniform("uDebugMode")' in renderer, "Mode uniform readback is required for per-mode evidence"
    assert "geometryStateValid" in renderer and "sphere.subdivisionLevel == currentSubdivisions" in renderer

    roi_freeze = json.loads((ROOT / "docs/p0_3/ROI_MASK_FREEZE_MANIFEST.json").read_text())
    assert roi_freeze["selfCheck"]["contractRegionCount"] == 13
    assert roi_freeze["selfCheck"]["requiredMaskCount"] == 12
    assert roi_freeze["selfCheck"]["materialMaskOverlapPolicy"] == "INDEPENDENT_SEMANTIC_REGIONS_ALLOW_OVERLAP"

    output = ROOT / "docs/p0_3/P0_3_PREFLIGHT_REPORT.json"
    report = {
        "schema": "AI_ORB_P0_3_PREFLIGHT_REPORT_1_0",
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "sourceHead": "PENDING_COMMIT",
        "sourceChecks": {
            "PF01_SEMANTIC_ROI_MASKS": "SEE_VALIDATE_SEMANTIC_ROI_MASKS",
            "PF02_RAYMARCH_STEPS_CONFLICT": {
                "status": "PASS_STATIC_CONTRACT",
                "masterFittedValue": master_steps,
                "hardwareHighTierSourceValue": hardware_steps,
                "softwareRendererCap": 20,
                "runtimeFullRenderValue": "PENDING_API36_CAPTURE",
            },
            "PF03_RUNTIME_GEOMETRY_SUBDIVISION": {
                "status": "PASS_CAPTURE_INSTRUMENTED_PENDING_RUNTIME",
                "productionHighTierSubdivisionSource": high_subdivisions,
                "expectedVertexCount": 10 * (4 ** high_subdivisions) + 2,
                "expectedTriangleCount": 20 * (4 ** high_subdivisions),
                "runtimeGeometryFingerprint": "PENDING_API36_CAPTURE",
            },
            "PF04_BASELINE_ANDROID_AVD_CAPTURE": "PENDING_API36_AVD_RUN",
            "PF05_REPEATABILITY_NOISE": "PENDING_THREE_BASELINE_RUNS",
        },
        "preflightReadyForRound1": False,
        "preflightReadyForOptimization": False,
        "optimizationRoundsStarted": False,
        "deviceGpuStatus": "PENDING_USER_DEVICE_QA",
    }
    output.write_text(json.dumps(report, indent=2) + "\n")
    print("[P0_3_STATIC_PREFLIGHT PASS] PF02 actual-uniform readback and PF03 runtime geometry readback wired")
    print("[P0_3_PREFLIGHT BLOCKED] API 36 runtime capture and repeatability runs are still required")


if __name__ == "__main__":
    main()
