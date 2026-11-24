# AI-Orb-Demo V3.2 — Stage E Glass Optics & Ribbon Material Fit Report

**Optimization Order Enforced:** `CAMERA (A) -> CAVITY (B) -> VOLUME (C) -> RESIDENT (D) -> OPTICS (E)`  
**Real GPU Status:** `PENDING_REAL_GPU_CAPTURE`

## 1. Glass & Material Calibration Summary

| Metric | Fitted Value | Target / Contract |
|---|---:|---|
| Upper-Dome Grey-White Haze Ratio | `0.000%` | `<= 1.0%` (Zero frosted film) |
| E09 10:30 Gold Flare Area Ratio | `0.0425` (Target `0.03922`) | Localized crown refraction |
| E09 Centroid Distance | `0.0418 D` | Aligned to 10:30 rim |
| Outer Glass Rim Mean Luma | `0.3739` | Crisp double-wall contrast |
| RibbonOpticsShaders Gold Luma | `0.6297` (Target `0.6299`, err `0.0003`) | Synchronized `goldWalls/coreStream/secThread/glassBelly/goldSpec` |

## 2. Stage F Global Visual Loss Summary (`AI_ORB_V3_2_VISUAL_LOSS.json`)

| Component | Weight | Value |
|---|---:|---:|
| `L_sphere` | `0.18` | `0.00315` |
| `L_cavity` | `0.22` | `0.33685` |
| `L_volume` | `0.25` | `0.18503` |
| `L_resident` | `0.20` | `0.0992` |
| `L_optics` | `0.10` | `0.03404` |
| `L_layering` | `0.05` | `0.0` |
| **`L_total`** | **`1.00`** | **`0.14418`** |

- All Offline Hard Gates Passed: `True`
- Failed Offline Gates: `[]`
