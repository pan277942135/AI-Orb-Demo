# AI-Orb-Demo V3.2 — Stage A Camera Projection Fit Report

**Evaluation Domain:** Exact 3D Subdivided Icosphere Matrix Projection (`Viewport * PerspectiveDivide(Proj * View * Model * v)`)  
**Frozen Constants:** `fovYDeg = 42.0°`, `coreScale = 1.0`, `coreShellScale = 0.8`  
**Real GPU Status:** `PENDING_REAL_GPU_CAPTURE` (offline projection pass: `True`)

## 1. Root Cause of V3.1 Size Discrepancy (`58.2%` Live vs `51.99%` Master)

| Configuration | `visualBoundRadius` | Square `1:1` `camDist` | Square `D/W` | Portrait `1080×2400` `camDist` | Portrait `D/W` | Diameter Rel Err |
|---|---:|---:|---:|---:|---:|---:|
| **V3.1 Baseline** | `1.36` | `3.65` | `58.44%` | `7.8732` | `58.99%` | `12.39%` |
| **V3.2 Fitted** | `1.5661` | `4.0798` | `52.00%` | `9.0663` | `51.15%` | `0.00%` |

## 2. Hard-Gate Check

- Square Diameter Relative Error: `0.003%` (Target `<= 2.0%`)
- Portrait `1080×2400` Diameter Relative Error: `1.624%` (Target `<= 2.0%`)
- Square Sphere Center Error: `0.009% W` (Target `<= 1.0% W`, `targetOffset = (0.0265, -0.125)`)
- Outer Sphere Silhouette IoU: `0.99958` (Target `>= 0.95`)
