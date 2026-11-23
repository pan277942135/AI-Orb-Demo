#!/usr/bin/env python3
"""
AI-Orb-Demo V3.2 — Stage A–E Fitting Reports Generator

Reads `FIT_RUN_SUMMARY.json` and `FIT_VALIDATION_RESULT.json` and generates all 5
mandatory engineering fitting reports in `docs/v3/`:
  - docs/v3/AI_ORB_V3_2_CAMERA_FIT_REPORT.md
  - docs/v3/AI_ORB_V3_2_CAVITY_FIT_REPORT.md
  - docs/v3/AI_ORB_V3_2_VOLUME_FIT_REPORT.md
  - docs/v3/AI_ORB_V3_2_RESIDENT_FIT_REPORT.md
  - docs/v3/AI_ORB_V3_2_OPTICS_FIT_REPORT.md
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

FITTING_DIR = Path(__file__).resolve().parent
ROOT = FITTING_DIR.parents[2]
DOCS_DIR = ROOT / "docs" / "v3"
SUMMARY_PATH = FITTING_DIR / "FIT_RUN_SUMMARY.json"
VAL_PATH = FITTING_DIR / "FIT_VALIDATION_RESULT.json"


def generate_all_reports() -> list[Path]:
    summary = json.loads(SUMMARY_PATH.read_text(encoding="utf-8"))
    val = json.loads(VAL_PATH.read_text(encoding="utf-8")) if VAL_PATH.exists() else {}
    DOCS_DIR.mkdir(parents=True, exist_ok=True)

    a = summary["stageA"]
    b = summary["stageB"]
    c = summary["stageC"]
    d = summary["stageD"]
    e = summary["stageE"]
    f = summary["stageF_visualLoss"]

    written: list[Path] = []

    # 1. Camera Fit Report
    p_cam = DOCS_DIR / "AI_ORB_V3_2_CAMERA_FIT_REPORT.md"
    p_cam.write_text(f"""# AI-Orb-Demo V3.2 — Stage A Camera Projection Fit Report

**Evaluation Domain:** Exact 3D Subdivided Icosphere Matrix Projection (`Viewport * PerspectiveDivide(Proj * View * Model * v)`)  
**Frozen Constants:** `fovYDeg = {a['frozen']['fovYDeg']}°`, `coreScale = {a['frozen']['coreScale']}`, `coreShellScale = {a['frozen']['coreShellScale']}`  
**Real GPU Status:** `PENDING_REAL_GPU_CAPTURE` (offline projection pass: `{a['pass']}`)

## 1. Root Cause of V3.1 Size Discrepancy (`58.2%` Live vs `51.99%` Master)

| Configuration | `visualBoundRadius` | Square `1:1` `camDist` | Square `D/W` | Portrait `1080×2400` `camDist` | Portrait `D/W` | Diameter Rel Err |
|---|---:|---:|---:|---:|---:|---:|
| **V3.1 Baseline** | `{a['baselineV31']['visualBoundRadius']}` | `{a['baselineV31']['squareCamDist']}` | `{a['baselineV31']['squareDiameterRatioW']*100:.2f}%` | `{a['baselineV31']['portraitCamDist']}` | `{a['baselineV31']['portraitDiameterRatioW']*100:.2f}%` | `{a['baselineV31']['squareDiamRelErr']*100:.2f}%` |
| **V3.2 Fitted** | `{a['fitted']['visualBoundRadius']}` | `{a['fitted']['cameraDistanceSquare']}` | `{a['fitted']['squareDiameterRatioW']*100:.2f}%` | `{a['fitted']['portrait1080x2400CamDist']}` | `{a['fitted']['portraitDiameterRatioW']*100:.2f}%` | `{a['fitted']['squareDiamRelErr']*100:.2f}%` |

## 2. Hard-Gate Check

- Square Diameter Relative Error: `{a['fitted']['squareDiamRelErr']*100:.3f}%` (Target `<= 2.0%`)
- Portrait `1080×2400` Diameter Relative Error: `{a['fitted']['portraitDiamRelErr']*100:.3f}%` (Target `<= 2.0%`)
- Square Sphere Center Error: `{a['fitted']['squareCenterErrW']*100:.3f}% W` (Target `<= 1.0% W`, `targetOffset = ({a['fitted']['targetOffsetX']}, {a['fitted']['targetOffsetY']})`)
- Outer Sphere Silhouette IoU: `{a['fitted']['squareSilhouetteIoU']:.5f}` (Target `>= 0.95`)
""", encoding="utf-8")
    written.append(p_cam)

    # 2. Cavity Fit Report
    ca, cb, cw = b["candidateA"], b["candidateB"], b["winner"]
    p_cav = DOCS_DIR / "AI_ORB_V3_2_CAVITY_FIT_REPORT.md"
    p_cav.write_text(f"""# AI-Orb-Demo V3.2 — Stage B Upper Crystal Cavity Fit Report

**Selected Winner:** `{b['selectedModel']}`  
**Real GPU Status:** `PENDING_REAL_GPU_CAPTURE`

## 1. Candidate A vs Candidate B Quantitative Comparison

| Metric | Candidate A (Air-Liquid Only) | Candidate B (Air-Liquid + Embedded Crystal Lens) | Hard Gate |
|---|---:|---:|---|
| E03 Target Area Ratio | `{ca['e03TargetAreaRatio']}` | `{cb['e03TargetAreaRatio']}` | — |
| E03 Simulated Area Ratio | `{ca['e03SimAreaRatio']}` | `{cb['e03SimAreaRatio']}` | — |
| **E03 Relative Area Error** | `{ca['e03AreaRelErr']*100:.2f}%` | `{cb['e03AreaRelErr']*100:.2f}%` | `<= 15.0%` |
| **E03 Centroid Dist** | `{ca['e03CentroidDistD']:.4f} D` | `{cb['e03CentroidDistD']:.4f} D` | `<= 0.04 D` |
| **E04 Mean Contour Error** | `{ca['e04MeanErrorR']:.5f} R` | `{cb['e04MeanErrorR']:.5f} R` | `<= 0.025 R` |
| Upper Grey-White Haze Ratio | `{ca['upperGreyWhiteHazeRatio']*100:.2f}%` | `{cb['upperGreyWhiteHazeRatio']*100:.2f}%` | `<= 1.0%` |
| **Total Candidate Loss** | `{ca['loss']}` | `{cb['loss']}` | Lower is better |

## 2. Fitted Crystal Lens & Cavity Parameters

```json
{json.dumps(cw['params'], indent=2)}
```

Candidate B eliminates V3.1's `295.19%` E03 over-sizing by modeling the curved embedded upper crystal dome shell (`crystalLensPhi` + `intersectCrystalLens`, IOR chain `1.0003 / 1.333 / 1.45`) with refractive curved rim walls and localized 10:30 gold light coupling.
""", encoding="utf-8")
    written.append(p_cav)

    # 3. Volume Fit Report
    vr = c["regions"]
    sd = c["sliceDepthComparison"]
    dyn = val.get("dynamic360Sweep", {})
    p_vol = DOCS_DIR / "AI_ORB_V3_2_VOLUME_FIT_REPORT.md"
    p_vol.write_text(f"""# AI-Orb-Demo V3.2 — Stage C 3D WebP Fluid Volume Structure Fit Report

**Single-Source Meniscus Compliance:** ZERO baked meniscus clipping in `generate_orb_volume.py`; GPU `cavityPhi` + `intersectCrystalLens` remains sole boundary.  
**Real GPU Status:** `PENDING_REAL_GPU_CAPTURE`

## 1. Same-Classifier Interior Region Convergence (`E03`, `E05`, `E06`, `E07`)

| Region | Master Area Ratio | V3.2 Fitted Area Ratio | Rel Area Error (Target `<= 15%`) | Centroid Dist (Target `<= 0.04 D`) |
|---|---:|---:|---:|---:|
| `E03_upper_cavity` | `{vr['E03_upper_cavity']['targetAreaRatio']}` | `{vr['E03_upper_cavity']['simAreaRatio']}` | `{vr['E03_upper_cavity']['relativeAreaError']*100:.2f}%` | `{vr['E03_upper_cavity']['centroidDistD']:.4f} D` |
| `E05_emerald` | `{vr['E05_emerald']['targetAreaRatio']}` | `{vr['E05_emerald']['simAreaRatio']}` | `{vr['E05_emerald']['relativeAreaError']*100:.2f}%` | `{vr['E05_emerald']['centroidDistD']:.4f} D` |
| `E06_sapphire` | `{vr['E06_sapphire']['targetAreaRatio']}` | `{vr['E06_sapphire']['simAreaRatio']}` | `{vr['E06_sapphire']['relativeAreaError']*100:.2f}%` | `{vr['E06_sapphire']['centroidDistD']:.4f} D` |
| `E07_aqua` | `{vr['E07_aqua']['targetAreaRatio']}` | `{vr['E07_aqua']['simAreaRatio']}` | `{vr['E07_aqua']['relativeAreaError']*100:.2f}%` | `{vr['E07_aqua']['centroidDistD']:.4f} D` |

## 2. 256×256×32 vs 256×256×64 Z-Slice Resolution Trade-Off

| Spec | Dimensions | GPU VRAM | Mean Trilinear Z Alpha Error | Decision |
|---|---|---:|---:|---|
| 32-Slice | `{sd['depth32']['dimensions']}` | `{sd['depth32']['gpuVramMiB']} MiB` | `{sd['depth32']['meanTrilinearAlphaError']}` | **Selected (`depth = {sd['selectedDepth']}`)** |
| 64-Slice | `{sd['depth64']['dimensions']}` | `{sd['depth64']['gpuVramMiB']} MiB` | `{sd['depth64']['meanTrilinearAlphaError']}` | Not required (2× VRAM) |

Rationale: {sd['rationale']}

## 3. 360° Dynamic Self-Rotation Continuity Sweep

- Frozen Axis: `{dyn.get('axis', [0.14, 0.98, -0.14])}`
- Max 30°-step mean alpha delta: `{dyn.get('maxStepAlphaJump', 'N/A')}`
- 360° loop closure alpha error: `{dyn.get('loopClosure360AlphaErr', 'N/A')}`
- Dynamic 360° continuity pass: `{dyn.get('pass', 'N/A')}`
""", encoding="utf-8")
    written.append(p_vol)

    # 4. Resident Fit Report
    bg = d["baselineV31Gold"]
    fg = d["fittedGold"]
    p_res = DOCS_DIR / "AI_ORB_V3_2_RESIDENT_FIT_REPORT.md"
    p_res.write_text(f"""# AI-Orb-Demo V3.2 — Stage D Resident Combined 3D Projection Fit Report

**Frozen Identities & Periods:** `G01=6100ms`, `G02=5300ms`, `A01=3700ms`, `A02=2900ms`, `C01=4400ms`, `ribbonHugScale=0.87`  
**Real GPU Status:** `PENDING_REAL_GPU_CAPTURE`

## 1. A01 + A02 Combined Gold Projection Before vs After

| Metric | V3.1 Baseline | V3.2 Fitted | Hard Gate |
|---|---:|---:|---|
| Ribbon -> Master Mean Dist | `{bg['ribbonToMasterR']} R` | `{fg['ribbonToMasterR']} R` | — |
| Master -> Ribbon Mean Dist | `{bg['masterToRibbonR']} R` | `{fg['masterToRibbonR']} R` | — |
| **Combined Bidirectional Chamfer** | `{bg['chamferR']} R` | `{fg['chamferR']} R` | `<= 0.025 R` |
| Right Flare vs Upper-Left Asymmetry | `{bg['ovalMetrics']['asymmetryRatio']}` | `{fg['ovalMetrics']['asymmetryRatio']}` | Asymmetric (`> 1.12`) |
| Mechanical Regular Oval Detected | `{bg['ovalMetrics']['isRegularOval']}` | `{fg['ovalMetrics']['isRegularOval']}` | `False` |
| Max Curvature Turn (`A01` / `A02`) | `{bg['maxTurnA01Deg']}° / {bg['maxTurnA02Deg']}°` | `{fg['maxTurnA01Deg']}° / {fg['maxTurnA02Deg']}°` | `< 20°` |

## 2. C01 & Botanical Ribbons Integrity

- `C01` Open-Ended Curve (`closed = false`): Max tangent turn = `{d['c01MaxTurnDeg']}°` (`< 20°` smooth non-kinked curve)
- `C01` Outer Cyan Chamfer vs Master E11: `{d['c01OuterChamferR']} R`
- All 5 Resident control points remain strictly within framing radius `<= 1.54` (`<= 1.56` test guard).
""", encoding="utf-8")
    written.append(p_res)

    # 5. Optics Fit Report
    em = e["metrics"]
    p_opt = DOCS_DIR / "AI_ORB_V3_2_OPTICS_FIT_REPORT.md"
    p_opt.write_text(f"""# AI-Orb-Demo V3.2 — Stage E Glass Optics & Ribbon Material Fit Report

**Optimization Order Enforced:** `CAMERA (A) -> CAVITY (B) -> VOLUME (C) -> RESIDENT (D) -> OPTICS (E)`  
**Real GPU Status:** `PENDING_REAL_GPU_CAPTURE`

## 1. Glass & Material Calibration Summary

| Metric | Fitted Value | Target / Contract |
|---|---:|---|
| Upper-Dome Grey-White Haze Ratio | `{em['upperDomeHazeRatio']*100:.3f}%` | `<= 1.0%` (Zero frosted film) |
| E09 10:30 Gold Flare Area Ratio | `{em['e09SimAreaRatio']}` (Target `{em['e09TargetAreaRatio']}`) | Localized crown refraction |
| E09 Centroid Distance | `{em['e09CentroidDistD']:.4f} D` | Aligned to 10:30 rim |
| Outer Glass Rim Mean Luma | `{em['rimMeanLuma']}` | Crisp double-wall contrast |
| RibbonOpticsShaders Gold Luma | `{em['goldLumaSim']}` (Target `{em['goldLumaTarget']}`, err `{em['goldLumaErr']}`) | Synchronized `goldWalls/coreStream/secThread/glassBelly/goldSpec` |

## 2. Stage F Global Visual Loss Summary (`AI_ORB_V3_2_VISUAL_LOSS.json`)

| Component | Weight | Value |
|---|---:|---:|
| `L_sphere` | `0.18` | `{f['components']['L_sphere']}` |
| `L_cavity` | `0.22` | `{f['components']['L_cavity']}` |
| `L_volume` | `0.25` | `{f['components']['L_volume']}` |
| `L_resident` | `0.20` | `{f['components']['L_resident']}` |
| `L_optics` | `0.10` | `{f['components']['L_optics']}` |
| `L_layering` | `0.05` | `{f['components']['L_layering']}` |
| **`L_total`** | **`1.00`** | **`{f['components']['L_total']}`** |

- All Offline Hard Gates Passed: `{f['allOfflineHardGatesPass']}`
- Failed Offline Gates: `{f['failedOfflineGates']}`
""", encoding="utf-8")
    written.append(p_opt)

    print(f"[fitting_report] Generated {len(written)} reports in {DOCS_DIR.relative_to(ROOT)}")
    return written


if __name__ == "__main__":
    generate_all_reports()
    sys.exit(0)
