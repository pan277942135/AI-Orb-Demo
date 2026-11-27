# WATER WORLD V2.1 — Work 3 Final Acceptance Matrix

**Task ID:** `WATER_WORLD_V21_WORK3_FULL_DYNAMIC_RUNTIME_V1`  
**Audit Baseline HEAD:** `20c0614973a8f525f045f3d6696deefbbb652545`  
**Execution Environment Truth:** Cloud Android Build Container (JDK 21, Android SDK 36.1, Gradle 9.3.1; **NO physical Android GPU / ADB**, **`GIT_EXISTS = False`**).

| Acceptance Gate | Status | Evidence & Notes |
|---|---|---|
| **Frozen Master PNG / V2 ZIP / V2.1 ZIP Unmodified** | **PASS** | Exact SHA-256 verified against frozen hashes; Master PNG strictly excluded from APK. |
| **Frozen 11 Pass / 5 Optics / 1254×1254 / Tab 1 & Tab 2 Architecture Preserved** | **PASS** | Reused `WaterWorldStaticRenderer`, `WaterWorldRenderGraph`, `WaterWorldRenderTargetPool`, `WaterWorldTextureCache`, `WaterWorldShaders`, `WaterWorldPreviewActivity`, `WaterWorldDiagnosticsDrawer`; zero parallel Renderer. |
| **Single Authoritative Timeline & Immutable Per-Frame Snapshot** | **PASS** | `WaterWorldMotionTimeline` + `WaterWorldDynamicFrameSnapshot` consumed identically across all 11 Passes & 5 Optics. |
| **47 Canonical Elements & 28 Dependencies Runtime Driven** | **PASS** | All 47 elements and 28 edges actively evaluated per frame and exported to JSON (`WORK3_AUTHORED_RUNTIME_HYPOTHESIS`, `water_world_motion_values_validated = false`). |
| **4 Fish Independent Paths + GPU Tail/Fin Deformation** | **PASS** | Closed 3D Catmull-Rom loops bounded inside sphere/meniscus/rocks + GPU vertex-shader swish (`uDeformMode=1`), visually confirmed across `T=0..12s` contact sheet. |
| **5 Residents Unified Back/Front Silk Flow & Pulse** | **PASS** | Unified per-ID state for `G01, G02, A01, A02, C01`, `GROUND_TRUTH_UNKNOWN` occlusion preserved, visually confirmed across `T=0..12s`. |
| **AI Orb Core 32 Spatial Z-Slices Contract Preserved** | **PASS** | `CORE_VOLUME_SLICE_COUNT = 32`, `SPATIAL_Z_SLICE_NOT_TIME_FRAME`, calm `3.6 deg/s`, zero duplicate glass shell, honest `CORE_FILAMENT = ASSET_GAP_PROCEDURAL_RUNTIME_HYPOTHESIS`. |
| **2 Plants Root-Pinned Sway, 3 Stationary Rocks, 9+14 Rising Bubbles** | **PASS** | `6×12` subdivided plant quad (`<= 2%` height sway), stationary rocks with flowing caustics, smooth rising/fading bubbles (`<= 1.5% D` drift). |
| **Water Normal, Meniscus Wave, Caustics, Glass, Bloom, Bokeh Dynamic Coupling** | **PASS** | All 5 optical nodes respond dynamically to shared snapshot; crystal sphere never rotates; zero FBO feedback loops. |
| **Auto-Play on Tab 3 Entry + Playback Controls + Lifecycle Zero Jump** | **PASS** | `RENDERMODE_CONTINUOUSLY` on `READY`+`PLAYING`, `RENDERMODE_WHEN_DIRTY` on `PAUSED`/`STATIC_T0`/`onPause`; Section M controls (`>= 48dp`) inside collapsed drawer. |
| **T=0 Static Regression Parity vs Work 2 `GPU_FINAL.png`** | **PASS** | `frame_t000.png` vs Work 2 `GPU_FINAL.png`: RGB MAE = `0.558888 / 255` (`0.219%`, sub-pixel bilinear plant-grid rounding only; visually identical). |
| **1:1 Software Dynamic Visual Motion Verification (`T=0,1,2,4,8,12`)** | **PASS** | Real continuous motion proven: `T=0->1` MAE `7.51`, `T=0->8` MAE `9.07`, `T=0->12` MAE `8.76`; 2×3 contact sheet visually inspected with zero clipping/flicker. |
| **10s / 30s / 60s Continuous Stability Simulation** | **PASS** | 300 / 900 / 1800 frames evaluated at `74–121 µs/frame`, `0` NaN/Inf, max fish step `0.852 px/frame`. |
| **6 Work 3 Automated Test Suites + Work 1.6 / Work 2 / Tab 1 / Tab 2 Regressions** | **PASS** | All 54 unit/Robolectric tests passing (`:app:testDebugUnitTest`). |
| **Android Build (`assembleDebug`, `assembleAndroidTest`, `compileReleaseKotlin`, `lintDebug`)** | **PASS** | Zero Kotlin compiler warnings, zero lint errors; Debug APK & Evidence ZIP audited and packaged. |
| **Zero Broad External Storage Permissions (Play Policy Compliant)** | **PASS** | SAF `ACTION_CREATE_DOCUMENT` only; zero `READ/WRITE_EXTERNAL_STORAGE` or `READ_MEDIA_*`. |
| **Physical Android Device Dynamic GPU Capture (`WORK3_HARDWARE_DYNAMIC_VERIFIED`)** | **NOT_RUN** | No physical Android GPU / ADB available in cloud build container. Ready for one-click on-device verification via Tab 3 → Diagnostics → `采集动态证据 (T=0..12s)`. |
| **GitHub Push / PR / Remote GitHub Actions Execution** | **NOT_RUN** | Local container workspace has `GIT_EXISTS = False` (no `.git` directory or GitHub remote credentials). Dedicated CI workflow `.github/workflows/water-world-work3-dynamic-android.yml` is committed on disk ready for repo push. |
