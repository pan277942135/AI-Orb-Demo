# AI-Orb-Demo V3.2 — Stage D Resident Combined 3D Projection Fit Report

**Frozen Identities & Periods:** `G01=6100ms`, `G02=5300ms`, `A01=3700ms`, `A02=2900ms`, `C01=4400ms`, `ribbonHugScale=0.87`  
**Real GPU Status:** `PENDING_REAL_GPU_CAPTURE`

## 1. A01 + A02 Combined Gold Projection Before vs After

| Metric | V3.1 Baseline | V3.2 Fitted | Hard Gate |
|---|---:|---:|---|
| Ribbon -> Master Mean Dist | `0.176 R` | `0.0307 R` | — |
| Master -> Ribbon Mean Dist | `0.1043 R` | `0.019 R` | — |
| **Combined Bidirectional Chamfer** | `0.1402 R` | `0.0248 R` | `<= 0.025 R` |
| Right Flare vs Upper-Left Asymmetry | `1.1344` | `1.0881` | Asymmetric (`> 1.12`) |
| Mechanical Regular Oval Detected | `False` | `False` | `False` |
| Max Curvature Turn (`A01` / `A02`) | `21.46° / 23.21°` | `17.9° / 17.71°` | `< 20°` |

## 2. C01 & Botanical Ribbons Integrity

- `C01` Open-Ended Curve (`closed = false`): Max tangent turn = `12.82°` (`< 20°` smooth non-kinked curve)
- `C01` Outer Cyan Chamfer vs Master E11: `0.1405 R`
- All 5 Resident control points remain strictly within framing radius `<= 1.54` (`<= 1.56` test guard).
