# Water World V2.1 — Work 3.1 Phase 4 Software GPU Precheck Report

**Evaluation Domain:** `SOFTWARE_GPU_PRECHECK_NOT_HARDWARE_ACCEPTANCE`  
**Real Device GPU Acceptance Status:** `WORK3_1_HARDWARE_DYNAMIC_VERIFIED = NOT_RUN` (software precheck NEVER substitutes for HUAWEI CMS-AL20 hardware verification)  
**Precheck Tool:** `tools/water-world-scene-assembly/WaterWorldCore3DSoftwarePrecheck.java`  
**Metrics Artifact:** `04_SOFTWARE_PRECHECK/WORK3_1_CORE3D_PRECHECK_METRICS.json`

---

## 1. Real 32 Spatial Z-Slice Volume Decoding & Alignment Check

- **Source Assets Decoded:** All 32 lossless RGBA WebP slices `app/src/main/assets/orb_v3/volume/fluid_color_z00.webp` .. `fluid_color_z31.webp` (`256 × 256 × 32`, `SPATIAL_Z_SLICE_NOT_TIME_FRAME`, IEC 61966-2-1 sRGB RGB -> linear, linear A).
- **Software Decode Time:** `2,255 ms` (Java + ImageMagick PNG32 intermediate).
- **Off-Axis Perspective Alignment (`WaterWorldCoreMotionAdapter.buildAlignedVolumeCamera`):**
  - Target Water World sphere: `center = (626.47575, 596.34413)`, `radius = 395.75 px` on `1254 × 1254` canvas.
  - Measured `centerErrorPx`: **`0.000000 px`** (`<= 1.0 px` PASS).
  - Measured `radiusErrorPx`: **`0.000000 px`** (`<= 1.0 px` PASS).

---

## 2. Dynamic Core 3D Volume Progression (`T = 0, 1, 2, 4, 8, 12s`)

| Timestamp | Core Rotation (`3.6 deg/s`) | `det(uCoreRotMat)` | Ortho Error | Non-Core Region MAE | Core Delta vs Work 3 Base | Core 3D Interior MAE vs `T=0s` |
|---:|---:|---:|---:|---:|---:|---:|
| `T = 0s` | `0.0°` | `1.000000` | `0.00000000` | `0.000000` | `9.244 / 255` | `0.000 / 255` |
| `T = 1s` | `3.6°` | `1.000000` | `0.00000000` | `0.000000` | `9.259 / 255` | `6.231 / 255` |
| `T = 2s` | `7.2°` | `1.000000` | `0.00000000` | `0.000000` | `9.116 / 255` | `9.815 / 255` |
| `T = 4s` | `14.4°` | `1.000000` | `0.00000000` | `0.000000` | `8.632 / 255` | `14.076 / 255` |
| `T = 8s` | `28.8°` | `1.000000` | `0.00000000` | `0.000000` | `7.391 / 255` | `28.276 / 255` |
| `T = 12s` | `43.2°` | `1.000000` | `0.00000000` | `0.000000` | `6.816 / 255` | `32.157 / 255` |

---

## 3. Visual Inspection Checklist (Software Precheck Artifacts)

All artifacts inspected visually (`04_SOFTWARE_PRECHECK/`):

1. `WORK3_1_CORE3D_CONTACT_SHEET.png`:
   - Zero black circular mask, zero duplicate outer glass shell, zero white overexposed ring.
   - Four fish, five Residents, aquatic plants, rocks, and bubbles remain crisp with zero washout.
   - Bottom isolated Core strip visibly rotates the real 32 Z-slice emerald-aqua fluid body across `0.0° -> 43.2°` with continuous 3D curl advection.
2. `core3d_isolation_grid.png`:
   - All 6 GPU isolation modes (`CORE_VOLUME_ONLY`, `CORE_DENSITY`, `CORE_FLOW`, `CORE_CAUSTICS`, `CORE_OPTICAL_DEPTH`, `CORE_ROTATION`) render distinct, physically valid outputs without NaN, banding, or Voronoi honeycomb artifacts.
3. `core3d_alignment_overlay.png`:
   - Green canonical Water World sphere circle and cyan dashed Core 3D bounding sphere overlap cleanly at `(626.48, 596.34), r=395.75 px`.
4. `core3d_frame_t000.png` .. `core3d_frame_t012.png`:
   - `nonCoreRegionMae == 0.000000` proves zero leakage outside the crystal sphere.
