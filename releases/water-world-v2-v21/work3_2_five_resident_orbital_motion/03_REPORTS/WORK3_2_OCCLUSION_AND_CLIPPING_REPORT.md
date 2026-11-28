# Work 3.2 Stage F — Core Occlusion, Depth Safety & Viewport Clipping Report

- **Task ID:** `WATER_WORLD_V2_1_WORK3_2_FIVE_RESIDENT_TRUE_ORBITAL_MOTION_V2`
- **Evidence Level:** `SOFTWARE_MOTION_VERIFIED` (Hardware Android GPU: `NOT_RUN` — requires HUAWEI CMS-AL20 real device)
- **Core Center / Radius (`optical_config.json`):** `(626.47577, 596.3441, 0.0)`, `R_inner = 395.75 px`, `R_outer = 422.29868 px`

## 1. Quantitative Software Gates Summary

| Gate | Threshold | Measured | Verdict |
|---|---|---|---|
| T=0 Static Parity MAE | <= 0.60 / 255 | 0.2578 / 255 | PASS |
| T=2s 3D Displacement (all 5 Residents) | P50 >= 0.08R, P90 >= 0.18R | Min P50=0.4043R, Min P90=0.4586R | PASS |
| T=5s 3D Displacement (all 5 Residents) | P50 >= 0.18R, P90 >= 0.35R | Min P50=0.9849R, Min P90=1.1218R | PASS |
| A01 Back/Front Seam Continuity | <= 1.0 px | 0.000061 px | PASS |
| Back Depth Safety (`02_RESIDENT_BACK`) | (0, +1900) | [428.93, 565.33] | PASS |
| Front Depth Safety (`10_RESIDENT_FRONT`) | (-2048, +106) | [-560.33, -447.28] | PASS |
| All-5 Offscreen Visible Alpha Ratio | <= 6.0% | Max=0.924%, Mean=0.149% | PASS |

## 2. Per-Resident T=2s & T=5s Pure 3D Vertex Displacement & Viewport Clipping

| Resident | T=2s Angle | T=2s P50 / P90 (Inner R) | T=5s Angle | T=5s P50 / P90 (Inner R) | Max Offscreen Alpha | Mean Offscreen Alpha |
|---|---:|---|---:|---|---:|---:|
| G01 | +32.0° | 0.7076R / 0.7889R | +80.0° | 1.6491R / 1.8478R | 2.388% | 0.336% |
| G02 | -26.0° | 0.5897R / 0.6756R | -65.0° | 1.4019R / 1.6058R | 0.515% | 0.062% |
| A01 | +22.0° | 0.4610R / 0.5642R | +55.0° | 1.1134R / 1.3769R | 1.373% | 0.139% |
| A02 | -38.0° | 0.8076R / 0.9161R | -95.0° | 1.8198R / 2.0725R | 0.000% | 0.000% |
| C01 | +18.0° | 0.4043R / 0.4586R | +45.0° | 0.9849R / 1.1218R | 0.001% | 0.000% |

## 3. Core Spatial Occlusion Verification

- `02_RESIDENT_BACK` (8 segments) executes BEFORE `03_SPHERE_BACK` and `04_WATER_INTERIOR` (Core 3D Volume) and stays strictly within `Z ∈ [428.932, 565.329]`, remaining cleanly occluded behind the crystal sphere and 3D Core volume.
- `10_RESIDENT_FRONT` (5 segments) executes AFTER `09_SPHERE_FRONT` and stays strictly within `Z ∈ [-560.327, -447.277]`, orbiting cleanly in front of the crystal sphere without Z-fighting or near-plane clipping.
- Visual Evidence Contact Sheets:
  - `04_EVIDENCE/WORK3_2_FIVE_RESIDENT_ORBITAL_CONTACT_SHEET.png`
  - `04_EVIDENCE/WORK3_2_INDIVIDUAL_RESIDENT_ORBITAL_SHEET.png`
