# AI-Orb-Demo Dual-Tab × Shared V3 AI Orb × Task Energy × 6KF Perimeter WaterLight V2 — Test Report

**Task ID:** `AI_ORB_DEMO_DUAL_TAB_TASK_PERIMETER_6KF_P0_V2`  
**Command Executed:** `gradle :app:testDebugUnitTest --console=plain`  
**Result:** **BUILD SUCCESSFUL — 67/67 Tests Passed (0 Failed, 0 Skipped)**

---

## 1. Test Suite Summary

| Suite | Category | Status | Coverage Highlights |
|---|---|---|---|
| **Existing V2/V3/V3.1 Suites (53 tests)** | Legacy & V3.1 Parity Regression | **PASS (53/53)** | 35 Lossless WebP V3 volume assets, `sampler3D uFluidVolume` raymarching, 5 frozen Residents, Cavity SDF, Motion Controller (`26°/s`), Glass Optics, and single-volume render pass manifest all untouched and passing. |
| `ExistingOrbTabRegressionTest` | G0 — Tab 1 Preservation & Shared Renderer | **PASS (3/3)** | Verifies `AIOrbConfig.Default.sceneMode == ORB_ONLY`, 5 frozen Residents intact, Tab 1 pass manifest contains zero Task/Photo/Perimeter passes, Tab 2 pass manifest reuses shared V3 core & 5 Residents, and Tab 1↔Tab 2 switching preserves customized Tab 1 parameters while keeping `gpuResourcesReused == true`. |
| `TaskDemoLayoutTest` | G1 — Unified Layout, Native WebP Photo & Lens Shift | **PASS (4/4)** | Native Robolectric `BitmapFactory` decode of `assets/task_demo/fishing_photo.webp` (verifying `>= 500×600`, canonical aspect ratio `~0.818`, non-blank RGB variation); unified bounds across KF01–KF06 on `1080×2400`, `1080×1920`, `1600×2560`, and `1200×1200` viewports; exact Compose window-to-GL local pixel conversion; off-axis lens-shift projection mapping `(0, 0, -D)` to exact `(targetNdcX, targetNdcY)` with zero vertical keystone skew. |
| `PerimeterPathGeometryTest` | G2 — Unified Rounded-Rect Arc-Length Geometry | **PASS (3/3)** | Exact analytical half-perimeter length `W + H - (4 - PI) * r`; LEFT/RIGHT symmetry starting at `BOTTOM_CENTER` and converging at `TOP_CENTER`; C1 tangent continuity (`dot > 0.998`) across all 4 quarter-arc transitions; unit outward normals verified by signed-distance probing; LUT accuracy `< 0.6px`; all L1/L2/L3 triangle-strip vertices verified strictly outside the photo interior (`signedDistancePx >= -0.6px`) with zero NaN. |
| `TaskEnergyControllerTest` | G3 & G4 — 6KF State Machine, Stability, Events & GLSL | **PASS (4/4)** | Exact verification of all 6 checkpoints (`KF01` no contact, `KF02` local bottom contact peak, `KF03` bottom split strictly before vertical sides, `KF04` ~60% vertical side climb with top unlit, `KF05` top convergence peak & `coverageComplete == true`, `KF06` softened top glint & >30s steady loop without restarting `KF01`); Pause/Resume zero-jump; 20 FPS vs 60 FPS drift `< 1e-3`; all 11 `TaskEnergyEvent`s emitted in exact order; all 5 `TaskWaterLightShaders` GLSL ES 3.00 pairs verified for `#version 300 es`, precision, outputs, and zero inverted `smoothstep`. |

---

## 2. Real Defect Caught & Fixed During Testing

During the initial test run of `TaskDemoLayoutTest` and `ExistingOrbTabRegressionTest`, our multi-viewport test caught a genuine edge-case `IllegalArgumentException` inside `OrbViewportTransform.forPhotoAndSurface`:
- **Root Cause:** On square/foldable (`1200×1200`) or pre-layout fallback (`64×64`) viewports, `w * 0.18f` exceeded `availableBottomSpace * 0.44f`, producing an inverted `coerceIn(min, max)` range.
- **Fix Applied:** Clamped `minDiam = minOf(w * 0.14f, maxDiam)` and `maxCenterY = (h - diam * 0.52f).coerceAtLeast(minCenterY)` in `OrbViewportTransform.kt`, eliminating any possibility of range inversion across portrait phones, tablets, foldables, and compact viewports.
- **Re-Verification:** Full `67/67` unit & Robolectric suite passed cleanly after the fix.

---

## 3. Visual Parity Check (Offline CPU Parity)

- Generated `docs/task6kf/renders/KF01_GPU.png` .. `KF06_GPU.png`, `TASK_6KF_GPU_OVERVIEW.png`, and `TASK_6KF_REFERENCE_VS_GPU.png` using `tools/task-6kf-extractor/generate_task_6kf_comparison.py`.
- Visually inspected `TASK_6KF_REFERENCE_VS_GPU.png` against the frozen 6KF overview (`01802259...`), confirming:
  1. Unified photo bounds, rounded corners, and bottom Orb position across KF01–KF06.
  2. Strict progression: `KF01` mid-flight release → `KF02` bottom contact starburst → `KF03` bottom split before sides → `KF04` C1 bottom corner wrap + ~60% dual-side ascent (top unlit) → `KF05` top-center warm-gold join peak → `KF06` continuous translucent 4-side loop with softened top sheen and sparse exterior droplets.
- Every offline render carries an explicit `OFFLINE CPU PARITY / GPU UNVERIFIED` banner to distinguish CPU parity inspection from physical Android GPU captures (`GPU_VISUAL_UNVERIFIED`).

---

## 4. APK Build & Bundle Verification

- **Build Command:** `gradle :app:assembleDebug --console=plain` — **BUILD SUCCESSFUL**
- **Delivered APK:** `AI-Orb-Demo-DualTab-Task6KF-V2-debug.apk`
- **APK SHA-256:** `b5b57c6de0ba8b675adea16cb4ac6eb4cd736f760c8a1a4cc8d5a72b11317fa8`
- **Bundled Assets Verified via `unzip -l`:**
  - `assets/orb_v3/`: **37 files** (all 35 Lossless RGBA WebP textures + manifest + SHA-256 preserved untouched)
  - `assets/task_demo/fishing_photo.webp`: **689,142 bytes** (single cropped demo fishing photo)
