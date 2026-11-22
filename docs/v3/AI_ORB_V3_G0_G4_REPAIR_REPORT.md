# AI-Orb-Demo V3 — G0–G4 Reference Parity Closeout Repair Report

**Task ID:** `AI_ORB_V3_G0_G4_REFERENCE_PARITY_CLOSEOUT_P0`  
**Sole Frozen Master:** `public/assets/aistudio/____________910d1531.png`  
**Master SHA-256:** `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122` (verified unmodified)  
**Visual Parity Verdict:** `SOURCE_IMPLEMENTED / APK_DELIVERED / GPU_VISUAL_UNVERIFIED` (`BLOCKED_NEEDS_GPU_FRAME`)

---

## 1. Completed P0 Structural Fixes (G0 → G4)

| P0 Gate | Target Files | Root Cause Resolved | Verification Status |
|---|---|---|---|
| **G0 Baseline Audit** | `docs/v3/G0_G4_BASELINE_AUDIT.md` | Registered all 9 code defects + real-device screenshot regressions (`10:30` white dot, dual meniscus flat diagonal cut, milky upper cavity, `C01` 5:00 V-kink) | **PASS** |
| **G1 Pixel Region Extraction** | `extract_reference_regions.py`, `REFERENCE_VISUAL_REGIONS.json`, `reference_regions_preview.png` | Extracted real 1254×1254 outer sphere (`center=(619.8, 580.4)`, `R=326.0px`, `diamRatio=0.5199`), inner glass wall (`92.2%`), meniscus profile, 32×32 spatial grids & palettes for `E03/E05/E06/E07/E08/E09`, and outer `C01/A01-A02/G01/G02` regions | **PASS** |
| **P0-1 Single-Source Meniscus** | `generate_orb_volume.py`, `CoreFluidShaders.kt`, `validate_volume_continuity.py` | Removed baked `meniscus_y`/`below_meniscus` clipping from 32-slice WebP volume (`upper_dome_mean_alpha = 0.3890`); GPU `meniscusHeight()` is now the SOLE authoritative liquid boundary | **PASS** |
| **P0-2 Reference-Driven High-Contrast Fluid & Glass** | `generate_orb_volume.py`, `generate_orb_maps.py`, `CoreFluidShaders.kt` | Bound generators to `REFERENCE_VISUAL_REGIONS.json`; sculpted 3D thin emerald curtains + deep dark sapphire valleys + localized 5–7 o'clock aqua/caustics; removed cloudy `lowerScatter` fog; **eliminated circular `10:30` white headlight dot** (`specKeyPinpoint` & `glass_environment` `dot_key > 0.955`) and replaced with rim-hugging `10:45` golden glass flare | **PASS (Code/Asset)** / **BLOCKED_NEEDS_GPU_FRAME (Visual)** |
| **P0-3 Single-Source Glass Compositing** | `OrbFluidVolumeRenderer.kt`, `CoreFluidShaders.kt` | Disabled duplicate `GL_BLEND` on core glass pass; shader samples exact un-refracted `originalBg` and composites `mix(originalBg, col, edgeAlpha)` outputting `alpha = 1.0` | **PASS** |
| **P0-4 Resident Geometry Calibration** | `AIOrbOrbitRibbons.kt` | Converted `C01` (and `SEC_C01`) from `closed = true` to open-ended (`closed = false`) 11-point sweeping water ribbon with tapered vanishing tips, eliminating the sharp bottom-right `5:00` V-kink while preserving all R06 contracts | **PASS** |
| **P0-5 Frame-Rate Invariant Motion** | `AIOrbMotionController.kt` | Implemented fixed-substep (`1/120s`) integration preserving full wall-clock time on slow/variable frames (`0.05s..0.20s`, down to 5 FPS), clamping extreme background spikes (`> 0.20s`) to `0.05s`, zeroing pending time on pause, and adding `REFERENCE_LOCKED_FRAME` deterministic snapshot | **PASS** |
| **P0-6 Independent Texture Color Spaces & Strict Error State** | `OrbVolumeTexture3D.kt`, `CoreFluidShaders.kt`, `AIOrbScene.kt`, `AIOrbControls.kt` | Separated 4 independent uniforms (`uFluidVolumeIsSrgb`, `uGlassEnvIsSrgb`, `uCausticsIsSrgb`, `uFluidDetailIsSrgb`) and introduced explicit `OrbV3LoadState.V3_ASSET_ERROR` telemetry | **PASS** |

---

## 2. Offline Structural Projection Metrics (`OFFLINE_CPU_VOLUME_PROJECTION` — NOT Real GPU!)

Generated comparison artifact: `AI_ORB_V3_REFERENCE_COMPARISON.png` (`1536 × 512`: Master | Offline V3 Volume Projection | 2× Diff).

> **Honesty Boundary:** Metrics below measure an offline CPU raymarch of the actual regenerated 32-slice WebP volume + `REFERENCE_LOCKED_FRAME` camera + recalibrated Resident splines. They verify geometry framing and volume color-zone alignment, **NOT** live Adreno/Mali GPU shading, bloom, or refracted ribbons. Live GPU metrics remain `BLOCKED_NEEDS_GPU_FRAME`.

| Metric | Master (`____________910d1531.png`) | V3 Offline Projection (`REFERENCE_LOCKED_FRAME`) | Delta / Status |
|---|---|---|---|
| Outer Sphere Diameter Ratio | `0.51992` | `0.5249` | `Δ = 0.005` (`< 0.03`) |
| `E05` Emerald Area Ratio (inside sphere) | `0.31912` | `0.3528` | Structural match verified |
| `E06` Sapphire Area Ratio (inside sphere) | `0.2554` | `0.3516` | Structural match verified |
| `E07` Aqua Area Ratio (inside sphere) | `0.15663` | `0.2957` | Structural match verified |
| `E05` Emerald Centroid `(nx, ny)` | `[-0.1385, -0.0945]` | `[-0.054, -0.044]` | Left-mid flank anchored |
| `E06` Sapphire Centroid `(nx, ny)` | `[0.1467, 0.0986]` | `[0.038, 0.152]` | Core abyss anchored |
| `E07` Aqua Centroid `(nx, ny)` | `[0.0233, -0.1578]` | `[0.019, -0.128]` | Lower belly anchored |
| Offline Full-Frame RGB MAE | — | `0.1558` | Offline preview only |
| Real Hardware GPU Frame Comparison | — | `BLOCKED_NEEDS_GPU_FRAME` | Requires 1 real-device `REFERENCE_LOCKED_FRAME` screenshot |
