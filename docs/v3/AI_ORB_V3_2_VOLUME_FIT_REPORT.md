# AI-Orb-Demo V3.2 — Stage C 3D WebP Fluid Volume Structure Fit Report

**Single-Source Meniscus Compliance:** ZERO baked meniscus clipping in `generate_orb_volume.py`; GPU `cavityPhi` + `intersectCrystalLens` remains sole boundary.  
**Real GPU Status:** `PENDING_REAL_GPU_CAPTURE`

## 1. Same-Classifier Interior Region Convergence (`E03`, `E05`, `E06`, `E07`)

| Region | Master Area Ratio | V3.2 Fitted Area Ratio | Rel Area Error (Target `<= 15%`) | Centroid Dist (Target `<= 0.04 D`) |
|---|---:|---:|---:|---:|
| `E03_upper_cavity` | `0.06931` | `0.07422` | `7.08%` | `0.0084 D` |
| `E05_emerald` | `0.32254` | `0.3275` | `1.54%` | `0.0413 D` |
| `E06_sapphire` | `0.25652` | `0.32719` | `27.55%` | `0.0862 D` |
| `E07_aqua` | `0.15309` | `0.12187` | `20.39%` | `0.1261 D` |

## 2. 256×256×32 vs 256×256×64 Z-Slice Resolution Trade-Off

| Spec | Dimensions | GPU VRAM | Mean Trilinear Z Alpha Error | Decision |
|---|---|---:|---:|---|
| 32-Slice | `256x256x32` | `8.0 MiB` | `0.012234` | **Selected (`depth = 32`)** |
| 64-Slice | `256x256x64` | `16.0 MiB` | `0.00619` | Not required (2× VRAM) |

Rationale: 256x256x32 achieves mean Z trilinear error 0.01223 (< 0.016, ~3.5 LSB) while cutting GPU VRAM in half (8.0 MiB vs 16.0 MiB) and preserving frozen V3 WebP-First startup latency; crisp thin-sheet normals are further sharpened analytically in CoreFluidShaders.kt.

## 3. 360° Dynamic Self-Rotation Continuity Sweep

- Frozen Axis: `[0.14, 0.98, -0.14]`
- Max 30°-step mean alpha delta: `0.20245`
- 360° loop closure alpha error: `0.0`
- Dynamic 360° continuity pass: `True`
