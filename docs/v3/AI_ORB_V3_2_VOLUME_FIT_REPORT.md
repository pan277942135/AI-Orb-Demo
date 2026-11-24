# AI-Orb-Demo V3.2 — Stage C 3D WebP Fluid Volume Structure Fit Report

**Single-Source Meniscus Compliance:** ZERO baked meniscus clipping in `generate_orb_volume.py`; GPU `cavityPhi` + `intersectCrystalLens` remains sole boundary.  
**Real GPU Status:** `PENDING_REAL_GPU_CAPTURE`

## 1. Same-Classifier Interior Region Convergence (`E03`, `E05`, `E06`, `E07`)

| Region | Master Area Ratio | V3.2 Fitted Area Ratio | Rel Area Error (Target `<= 15%`) | Centroid Dist (Target `<= 0.04 D`) |
|---|---:|---:|---:|---:|
| `E03_upper_cavity` | `0.06931` | `0.06625` | `4.41%` | `0.0216 D` |
| `E05_emerald` | `0.32254` | `0.34906` | `8.22%` | `0.0367 D` |
| `E06_sapphire` | `0.25652` | `0.28469` | `10.98%` | `0.0173 D` |
| `E07_aqua` | `0.15309` | `0.15828` | `3.39%` | `0.0283 D` |

## 2. Independent Decoded-WebP Projection (`96×96`, `16` ray steps)

The verifier reads the 32 encoded WebP files back to RGBA and samples only the decoded 3D texture; it does not call the Canonical Analytical Field.

| Region | Decoded-WebP Relative Area Error | Target | Centroid Dist | Target |
|---|---:|---:|---:|---:|
| `E03_upper_cavity` | `2.84%` | `<= 15.0%` | `0.0194 D` | `<= 0.04 D` |
| `E05_emerald` | `6.67%` | `<= 15.0%` | `0.0367 D` | `<= 0.04 D` |
| `E06_sapphire` | `12.32%` | `<= 15.0%` | `0.0168 D` | `<= 0.04 D` |
| `E07_aqua` | `4.82%` | `<= 15.0%` | `0.0306 D` | `<= 0.04 D` |

- Decoded projection pass: `True` (`DECODED_WEBP_32_SLICES_TRILINEAR`)
- Exact 32-slice RGBA evidence: `tools/orb-v3-generator/fitting/CANONICAL_VOLUME_PIXEL_CLOSURE.json` (`2,097,152` voxel bytes compared per channel).

## 3. 256×256×32 vs 256×256×64 Z-Slice Resolution Trade-Off

| Spec | Dimensions | GPU VRAM | Mean Trilinear Z Alpha Error | Decision |
|---|---|---:|---:|---|
| 32-Slice | `256x256x32` | `8.0 MiB` | `0.016686` | **Selected (`depth = 32`)** |
| 64-Slice | `256x256x64` | `16.0 MiB` | `0.006178` | Not required (2× VRAM) |

Rationale: The release contract fixes 256x256x32 (8.0 MiB) and the canonical generator emits exactly 32 slices. The measured mean Z interpolation errors are 0.01669 at 32 slices and 0.00618 at 64 slices; the 64-slice value is retained for comparison only and is not selected because it would violate the manifest, loader, and APK asset contract.

## 4. 360° Dynamic Self-Rotation Continuity Sweep

- Frozen Axis: `[0.14, 0.98, -0.14]`
- Max 30°-step mean alpha delta: `0.13746`
- 360° loop closure alpha error: `0.0`
- Dynamic 360° continuity pass: `True`
