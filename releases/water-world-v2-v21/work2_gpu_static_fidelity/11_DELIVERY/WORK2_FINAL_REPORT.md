# WATER WORLD V2.1 — Work 2 Final Report

**Task ID:** `WATER_WORLD_V21_WORK2_GPU_STATIC_FIDELITY_CONVERGENCE_V1`  
**Audit Baseline HEAD:** `ae7ad16448ca4cc15f02641f26294bf54ad52cd6`  
**Gate Status:** `WORK2_SOFTWARE_CLOSURE_DEVICE_QA_PENDING`  
**Hardware GPU Verified:** `false` (container lacks ADB/Android GPU device; honest status preserved)  
**Master Bound as Texture:** `false` (verified by Python asset audit, Robolectric test, and APK zip scan)  

---

## 1. Frozen Sources & Immutability Verification

| Artifact | Path | Verified SHA-256 | Status |
|---|---|---|---|
| Frozen Master PNG (1254×1254, 2,256,234 B) | `releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png` | `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589` | UNMODIFIED |
| Frozen V2 ZIP (65 members) | `releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip` | `6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095` | UNMODIFIED |
| Frozen V2.1 ZIP | `releases/water-world-v2-v21/delivery/WATER_WORLD_V2_1_ASSETS.zip` | `28f8918c39f6e875602d3a7e90775e9d455aa6c4e42783247d1104c3b4509679` | UNMODIFIED |
| Work 1.6 Evidence Dir | `releases/water-world-v2-v21/work1_6_structure_closure_android_gpu/` | Preserved untouched | IMMUTABLE |

---

## 2. CI & Android Architecture Upgrades

1. **CI Upgraded to Temurin JDK 21:** `.github/workflows/water-world-work16-android.yml` switched from JDK 17 to Temurin JDK 21 (required by Robolectric API 36) while retaining Android SDK 36.1, Gradle compilation, Robolectric tests, Android Lint, and APK internal asset checks.
2. **Reused Production GLES 3.0 Pipeline (11 Passes, 5 Optical Nodes, 56 Draws, T=0 Static Only):**
   - `WaterWorldShaders.kt` synced 1:1 with `tools/water-world-scene-assembly/WaterWorldSoftwareRenderer.java`.
   - `WaterWorldRenderTargetPool.kt` + `WaterWorldAssetLoader.kt` + `WaterWorldStaticRenderer.kt`: added FBO/texture VRAM telemetry (`plannedFboMemoryBytes`, `estimatedGpuMemoryBytes`, `uploadedTextureBytes`), `PresentViewport` reporting, frame IDs, and `glErrorCount`.
   - `WaterWorldPreviewActivity.kt` + `WaterWorldDiagnosticsDrawer.kt`: added viewport-accurate GL-to-Android `PixelCopy` crop mapping (`computeAndroidCropRect`), honest frame-sync metadata in `SCREEN_VS_FBO_DIFF.json` (`sameSceneSnapshotVerified=true`, `sameFrameSynchronized=false`, `strictHardwareFenceVerified=false`), and zero-permission Android SAF (`ACTION_CREATE_DOCUMENT`) Evidence ZIP export button (48dp touch target).
   - `MainActivity` / `AIOrbScene.kt`: deferred GL initialization to Frame 2 to eliminate the 1000ms `SurfaceSyncGroup` timeout.

---

## 3. Five-Round Visual Optimization Progression (Unmodified 6-Axis Evaluator)

All rounds were evaluated against the frozen Master PNG at 1254×1254 using the **unmodified Work 1.6 six-axis equal-weight algorithm** via the 1:1 CPU software reference pipeline (`hardware_gpu_verified=false`).

| Round | Optimization Focus | Overall | Δ vs R0 | Comp | Geo | Res | Mat | Light | Depth | Full MAE | Meniscus MAE | Outer Res Sim |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **R0** | Work 1.6 Baseline | 83.23 | +0.00 | 86.49 | 78.07 | 82.83 | 82.40 | 84.05 | 85.54 | 0.1366 | 0.1958 | 0.8207 |
| **R1** | Sphere/Ecology Geometry & Droplet Scale | 83.24 | +0.01 | 86.51 | 77.97 | 82.92 | 82.47 | 84.01 | 85.55 | 0.1370 | 0.1958 | 0.8220 |
| **R2** | Crystal Glass Front/Back Separation, TIR Groove, Fresnel & Flares | 84.30 | +1.07 | 87.24 | 77.62 | 84.69 | 83.56 | 86.64 | 86.07 | 0.1366 | 0.1920 | 0.8412 |
| **R3** | Four Fish Dorsal Shading, Foliage Tints & Water-Warped Rock Caustics | 84.47 | +1.24 | 87.49 | 77.92 | 84.64 | 83.72 | 86.74 | 86.31 | 0.1368 | 0.1920 | 0.8413 |
| **R4** | Five Residents Gossamer Silk Filaments & Top-Strip Lum Alignment | 84.45 | +1.22 | 87.73 | 77.84 | 84.26 | 83.71 | 86.88 | 86.28 | 0.1347 | 0.1914 | 0.8422 |
| **R5** | Air-Dome Sunlight, Golden-Cyan Meniscus & 5-Optics Convergence | **84.44** | **+1.21** | **87.81** | 77.75 | 83.98 | 83.68 | **87.11** | **86.29** | **0.1346** | **0.1893** | **0.8424** |

- **Pass Threshold (`>= 80.0`):** PASS (`84.44`, `+1.21` over baseline, all-time best `Comp=87.81`, `Light=87.11`, `Depth=86.29`, `fullMAE=0.1346`, `meniscusMAE=0.1893`, `outer_ring_similarity=0.8424`, `top_strip_mean_lum=0.2080` vs Master `0.2116`, `clipped_white=0.0164 < Master 0.0222`).

---

## 4. Disclosed Residual Structural Defects & Ground Truth Honesty

Per Section 4.3, residual local defects are explicitly documented even though the overall score exceeds 80:
1. **Left Plant Silhouette (`left_plant rgb_mae=0.2088`):** Reuses frozen V2 `plant_left.png` without regenerating the asset pack; upper blade curvature differs locally from Master.
2. **Foreground Rock Caustics (`rocks rgb_mae=0.1986`):** Uses water-normal-warped V2 `caustics_height.png` rather than Master's large cellular Voronoi caustic web on `rock_01`.
3. **Resident Occlusion Remains Hypothesis:** Canonical Resident occlusion Ground Truth remains empty (`UNKNOWN_FRONT_BACK_NO_CONFIRMED_OCCLUSION`). All 13 front/back segments are labeled `RENDER_HYPOTHESIS_WORK2_*` and have residual fine-loop position offsets on upper-left `G02` and lower-right `C01`.
4. **Fish GT Preservation:** `WW_FISH_01` and `WW_FISH_02` retain `GROUND_TRUTH_VERIFIED` vertices; `WW_FISH_03` (`rgb_mae=0.0966`) and `WW_FISH_04` (`rgb_mae=0.0967`) retain `RENDER_HYPOTHESIS_RETAINING_UNKNOWN_GT`.
5. **Hardware GPU QA Pending:** Container has no Android device/ADB; `hardware_gpu_verified=false` until verified on a physical GLES 3.0 Android device.

---

## 5. Build, Test, Lint & APK Delivery

- **Gradle Verification (`JDK 21`):**
  - `:app:assembleDebug` — PASS
  - `:app:assembleAndroidTest` — PASS
  - `:app:compileReleaseKotlin` — PASS
  - `:app:testDebugUnitTest` (`WaterWorld*`, `ExistingOrbTabRegressionTest`, `AIOrbIntegrationRobolectricTest`, including 7 new `WaterWorldWork2StaticFidelityTest` Robolectric API 36 tests) — PASS
  - `:app:lintDebug` — PASS
- **Delivered Debug APK:**
  - Delivery Path: `releases/water-world-v2-v21/work2_gpu_static_fidelity/11_DELIVERY/AI-Orb-Demo-WaterWorld-V2.1-Work2-debug.apk`
  - Root Path: `AI-Orb-Demo-WaterWorld-V2.1-Work2-debug.apk`
  - Size: `26,856,737` bytes
  - SHA-256: `62470f1bc54b276c45dbffd357e2e5bfd096b889d19d4f71dfe459bbc93a4ca8`
  - APK Internal Checks: 65/65 byte-exact frozen V2 textures, `work2_round=5` in `draw_bindings.json` & `optical_config.json`, 11 passes, 5 optical nodes, 56 draws, zero Master PNG, zero broad storage permissions.

---

## 6. Real-Device Android GPU QA Checklist (Next Physical Step)

1. Install `AI-Orb-Demo-WaterWorld-V2.1-Work2-debug.apk` on a physical GLES 3.0 Android device.
2. Open **Tab 3 (Water World V2.1)** and verify T=0 static rendering renders cleanly with zero black/neon/blown-out artifacts.
3. Open the Diagnostics Drawer → tap **导出证据包**, then tap **保存 Evidence ZIP 到系统文件 (SAF)** to save the ZIP without storage permissions.
4. Run `WaterWorldWork2Evaluator` on the device's `final_fbo.png` and inspect `ANDROID_GPU_TELEMETRY.json` (`glVendor`, `glRenderer`, `glVersion`, `glErrorCount == 0`, FBO/texture VRAM MB) + `SCREEN_VS_FBO_DIFF.json` to close hardware GPU acceptance.
