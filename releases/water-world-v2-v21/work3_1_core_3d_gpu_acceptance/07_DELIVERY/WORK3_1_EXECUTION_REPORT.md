# Water World V2.1 — Work 3.1 Core 3D Volume GPU Closure Execution Report

**Task ID:** `WATER_WORLD_V2_1_WORK3_1_CORE_3D_VOLUME_GPU_CLOSURE_V1`  
**Repository Target:** `pan277942135/AI-Orb-Demo` (Baseline Main HEAD `6d53c42d4b42f674ebf1cae6137f03ec8f74e7aa`)  
**Target Device for Final Hardware Acceptance:** HUAWEI CMS-AL20 / Android 16 / API 36 (Expected GPU: Maleoon 935, read from real device on run)

---

## 1. Honest Execution & Closure Status Split

| Gate | Status | Basis |
|---|---|---|
| `WORK3_1_CODE_COMPLETE` | **PASS** | Real 32 Z-slice `GL_TEXTURE_3D` + 3D Volume Raymarching + 3D Curl Advection + 3D Core Rotation integrated into `PassId.WATER_INTERIOR`; fake 2D core swirl removed; 6 Core 3D GPU isolation modes wired in collapsed drawer. |
| `WORK3_1_LOCAL_GRADLE_AND_TESTS` | **PASS** | `:app:assembleDebug`, `:app:assembleAndroidTest`, `:app:compileReleaseKotlin`, `:app:lintDebug`, `compile_applet`, and **16 suites / 83 unit+Robolectric tests** all PASS (0 failures). |
| `WORK3_1_REMOTE_GITHUB_CI` | **NOT_RUN_NO_GIT_IN_CONTAINER** | `GIT_EXISTS = False` in AI Studio container; workflow `.github/workflows/water-world-work3-1-core3d-android.yml` is written & locally verified, ready for GitHub branch `work/water-world-v2-1-work3-1-core-3d-gpu-closure` PR execution. |
| `WORK3_1_SOFTWARE_PRECHECK` | **PASS (`SOFTWARE_GPU_PRECHECK_NOT_HARDWARE_ACCEPTANCE`)** | All 32 real `fluid_color_z00..z31.webp` slices decoded, `0.000000 px` off-axis alignment error, `0.000000` non-Core MAE, Core 3D motion MAE `0.00 -> 32.16` across `T=0..12s`. |
| `WORK3_1_APK_DELIVERED` | **PASS** | `AI-Orb-Demo-WaterWorld-V2.1-Work3.1-Core3D-debug.apk` (`26,956,902 bytes`, SHA-256 `9a13be476f854376d7d8789a817e5c33854dc751100d251938eb7b992c7b6041`) audited & delivered with `.sha256`. |
| `WORK3_1_HARDWARE_DYNAMIC_VERIFIED` | **NOT_RUN** | Awaits user installation and real-device inspection on HUAWEI CMS-AL20. |

---

## 2. Production Code Changes Summary

1. `app/src/main/java/com/example/orb/gl/CoreFluidShaders.kt`
   - Added production `uCoreRenderMode` (`0 = FULL_ORB` Tab 1/Tab 2 untouched, `1 = CORE_VOLUME_ONLY` Tab 3), `uCoreDiagMode` (`0..6`), `uCoreColorShift`.
   - Rigid unit sphere in `CORE_GLASS_VERT` for `uCoreRenderMode == 1`.
   - Single-pass in-shader premultiplied composite over `originalBg` (`uRefractionTex` = Water Base snapshot) in `CORE_GLASS_FRAG` (`GL_BLEND` disabled, opaque FBO alpha = `1.0`), plus all 6 Core 3D GPU isolation views (`CORE_VOLUME_ONLY`, `CORE_DENSITY`, `CORE_FLOW`, `CORE_CAUSTICS`, `CORE_OPTICAL_DEPTH`, `CORE_ROTATION`).
2. `app/src/main/java/com/example/orb/v3/OrbVolumeTexture3D.kt`
   - Added 5-state `CoreVolumeLifecycleState` (`CORE_VOLUME_LOADING`, `CORE_VOLUME_READY`, `CORE_VOLUME_ERROR`, `CORE_VOLUME_CONTEXT_LOST`, `CORE_VOLUME_REUPLOADING`), `uploadGlStatus`, `lastGlError`, `failedAssetPath`, `markContextLost()`, and fast EGL context-loss re-upload from cached RGBA buffers.
3. `app/src/main/java/com/example/orb/scene/OrbFluidVolumeRenderer.kt`
   - Added `drawVolumeOnly(...)`, `handleContextLost()`, `CoreGpuInspection` (with real `glGetUniformLocation` checks for `uFluidVolume`, `uCoreRotMat`, `uFluidPhase`, `uCausticsPhase`, `uDensityScale`, `uCoreRenderMode`, `uCoreColorShift`), strict throw on `CORE_VOLUME_ERROR` (zero silent 2D fallback), and untouched `draw(...)` for Tab 1 / Tab 2.
4. `app/src/main/java/com/example/waterworld/WaterWorldCoreMotionAdapter.kt`
   - Added `toAIOrbMotionSnapshot(...)`, `validateRotationMatrix(...)`, and exact off-axis perspective camera `buildAlignedVolumeCamera(...)` (`<= 0.001 px` error on `1254 × 1254`).
5. `app/src/main/java/com/example/waterworld/WaterWorldShaders.kt`
   - Removed duplicate fake 2D core swirl/filament & `uCoreFluidParams` from `WATER_FRAGMENT`.
6. `app/src/main/java/com/example/waterworld/WaterWorldRenderGraph.kt` & `WaterWorldStaticRenderer.kt`
   - Preserved all 11 Passes & 5 Optical Nodes intact; inserted `coreVol.drawVolumeOnly(...)` inside `PassId.WATER_INTERIOR` directly after Water Base (`pool.scene -> pool.snapshot -> coreVol.drawVolumeOnly`).
   - Added all 6 Core 3D GPU isolation modes, honest `GPU_TIMER_UNAVAILABLE` reporting, Java/Native heap telemetry, and full `core3dGpuInspection` JSON export.
7. `app/src/main/java/com/example/waterworld/WaterWorldDiagnosticsDrawer.kt` & `WaterWorldPreviewActivity.kt`
   - Kept diagnostics drawer collapsed by default; added `Section E: Core 3D Volume GPU Isolation (32 Z-Slices)` with 6 `>= 48dp` buttons + live Core 3D status badge & GL telemetry.
   - Enforced `CORE_VOLUME_READY` gate before `PageState.READY`, explicit `CORE_VOLUME_ERROR -> PageState.RESOURCE_FAILED` surfacing, and `handleCoreContextLost()` on EGL surface recreation.

---

## 3. Frozen Resource Integrity Audit

| Frozen Resource | Expected SHA-256 | Actual Verification |
|---|---|---|
| Frozen Master PNG (`releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png`) | `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589` | **EXACT MATCH** (and verified NOT inside APK) |
| Frozen V2 ZIP (`releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip`) | `6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095` | **EXACT MATCH** |
| Frozen V2.1 ZIP Lock (`releases/water-world-v2-v21/delivery/WATER_WORLD_V2_1_ASSETS.zip.sha256`) | `28f8918c39f6e875602d3a7e90775e9d455aa6c4e42783247d1104c3b4509679` | **EXACT MATCH** |
| 32 Spatial Z-Slices (`app/src/main/assets/orb_v3/volume/fluid_color_z00..z31.webp`) | Manifest `ORB_V3_ASSET_MANIFEST.json` (32/32 SHA-256) | **32/32 EXACT MATCH** |

---

## 4. Deliverables Packaged

- **Debug APK:** `AI-Orb-Demo-WaterWorld-V2.1-Work3.1-Core3D-debug.apk` (`26,956,902 bytes`, SHA-256 `9a13be476f854376d7d8789a817e5c33854dc751100d251938eb7b992c7b6041`) in both `07_DELIVERY/` and workspace root `/`.
- **Software Dynamic Evidence ZIP:** `WATER_WORLD_V2_1_WORK3_1_CORE3D_SOFTWARE_EVIDENCE.zip` (plus `.sha256`) in both `07_DELIVERY/` and workspace root `/`.
- **Hardware QA Guide & Acceptance Matrix:** `07_DELIVERY/WORK3_1_HARDWARE_QA_GUIDE.md`, `07_DELIVERY/CORE_3D_GPU_ACCEPTANCE_MATRIX.md`, `07_DELIVERY/SHA256SUMS.txt`.
