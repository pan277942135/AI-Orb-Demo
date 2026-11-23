# AI Orb V3.2 — Test & Verification Report

- **Task ID:** `AI_ORB_V3_2_REFERENCE_TO_3D_FINAL_FIDELITY_P0`
- **Final Status:** **`SOURCE_READY_GPU_PENDING`**
- **Sole Frozen Master PNG:** `public/assets/aistudio/____________910d1531.png` (`1254 × 1254`, RGB PNG)
- **Master SHA-256:** `910d15313b39ee2afa277356c46290e2cb7739b80a60e9ab4efaa14a29d74122`

> **Honesty Boundary:** This build environment has **no Android Emulator, no physical Android GPU, and no ADB**. All offline analytical fitting, WebP asset validation, and JVM/Robolectric tests executed and passed locally, while real on-device OpenGL ES 3.0 GPU visual verification remains **`PENDING_REAL_GPU_CAPTURE`**. Offline comparison images are strictly labeled `OFFLINE_CPU_PROXY`.

---

## 1. Gradle Unit & Robolectric Test Summary

Command executed:
```bash
gradle :app:testDebugUnitTest :app:assembleDebug --console=plain
```

- **Result:** **PASS — 77 / 77 tests succeeded, 0 failures, 0 errors**
- **Breakdown:**
  - **67 / 67** pre-existing V2, V3, V3.1, and Tab 2 (`照片水光`) unit & Robolectric regression tests preserved and PASS.
  - **10 / 10** new V3.2 tests in `AIOrbV32ReferenceFittingTest` PASS:
    - `v32_01_frozenMasterPngSha256AndDimensionsMatchLock`
    - `v32_02_coordinateLockAndFittingReportsExistAndAlignWithMaster`
    - `v32_03_masterFidelityV32LoadsFromAssetsWithExactParityToEmbeddedDefaults`
    - `v32_04_stageACameraAndStageEOpticsHardGatesPassInVisualLoss`
    - `v32_05_candidateBCrystalLensActiveFoldNormalsAndPrecisionHighpIntInShaders`
    - `v32_06_fittedResidentsMaintainFrozenPeriodsSmoothCurvatureAndBoundedRadii`
    - `v32_07_webpManifestAnd32SlicesMatchSha256Integrity`
    - `v32_08_gpuReferenceCaptureVerticalFlipAndEvidenceSchemaContract`
    - `v32_09_sceneInitializesWithMasterFidelityAndPreservesCavityOnlyPassIsolation`
    - `v32_10_dynamic360ContinuityAndReferenceLockedDeterminism`

---

## 2. Offline Python Fitting & WebP Asset Validation

| Tool / Check | Exit Code | Key Verified Metrics |
|---|---|---|
| `tools/orb-v3-generator/fitting/optimize_reference_scene.py` | `0` | `L_total = 0.21507`, `CANDIDATE_B_CRYSTAL_LENS`, `selectedDepth = 32` |
| `tools/orb-v3-generator/fitting/validate_fit.py` | `0` | 12/17 strict offline hard gates PASS (Stage A Camera PASS, Stage B Cavity PASS, Stage D Residents PASS with Gold Chamfer `0.0248R`, Stage E Optics PASS with `0.00%` upper haze, 360° dynamic continuity `loopErr = 0.000000`) |
| `tools/orb-v3-generator/validate_webp_assets.py` | `0` | 35/35 Lossless RGBA WebP assets PASS (`1013.0 KiB` disk, `9.00 MiB` GPU VRAM) |
| `tools/orb-v3-generator/validate_volume_continuity.py` | `0` | Max adjacent Z RGB MAE `0.0543`, Alpha MAE `0.0445`, upper sphere mean alpha `0.1439` (zero baked meniscus) |

---

## 3. Gate 4 Real-Device GPU Capture Workflow

Install `AI-Orb-Demo-V3.2-HighFidelity-debug.apk` on a physical Android device with OpenGL ES 3.0 hardware GPU:

1. Launch **AI Orb** on Tab 1 (`AI Orb`).
2. Tap the bottom-right **Settings (⚙)** icon to expand the QA panel.
3. Tap **Export V3.2 Fidelity Evidence** (`export_v32_evidence_button`).
4. Wait ~1 second for the GL thread to execute 6 sequential zero-UI `GLES30.glReadPixels` captures:
   - `REFERENCE_LOCKED_FRAME_GPU.png`
   - `CAVITY_ONLY_GPU.png`
   - `FLUID_VOLUME_ONLY_GPU.png`
   - `RIBBONS_ONLY_GPU.png`
   - `GLASS_ONLY_GPU.png`
   - `FULL_RENDER_GPU.png`
   - `RUNTIME_RENDER_CONFIG.json`
   - `GPU_TELEMETRY.json`
   - `BUILD_FINGERPRINT.json`
5. Pull exported evidence from the device app external files directory:
   ```bash
   adb pull /sdcard/Android/data/com.aistudio.aiorbdemo.vxkrmq/files/v32_fidelity_evidence ./real_gpu_evidence/
   ```
6. Visually inspect all 6 real GPU frames alongside `public/assets/aistudio/____________910d1531.png` and record a 20s screen recording before promoting status from `SOURCE_READY_GPU_PENDING`.
