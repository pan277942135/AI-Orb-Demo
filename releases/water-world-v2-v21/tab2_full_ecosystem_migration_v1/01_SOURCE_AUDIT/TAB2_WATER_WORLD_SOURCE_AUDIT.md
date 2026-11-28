# TAB2_WATER_WORLD_SOURCE_AUDIT

**Task ID:** `TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`  
**Phase:** Phase 0 — Source Audit & Lock  
**Audit Status:** `PASS` (Zero code modified during audit)

## 1. Current Workspace Baseline (Verified On Disk)

- **Repository Target:** `pan277942135/AI-Orb-Demo` (Note: AI Studio workspace does not carry `.git` metadata; baseline is verified directly from checked-in production sources, frozen archives, and test suites on disk).
- **Verified Production Baseline Features Present:**
  1. **Work 3 Full Dynamic Runtime:** `WaterWorldMotionProfile.kt`, `WaterWorldMotionTimeline.kt`, `WaterWorldMotionEvaluator.kt`, `WaterWorldMotionRuntime.kt`, `WaterWorldDynamicFrameSnapshot.kt`, `work3_motion_profile.json`.
  2. **Work 3.1 Core 3D Volume Closure:** 32 Z-slices (`app/src/main/assets/orb_v3/volume/fluid_color_z00.webp` .. `fluid_color_z31.webp`), `OrbFluidVolumeRenderer`, `CoreFluidShaders`, `WaterWorldCoreMotionAdapter.kt` integrated inside Pass `04_WATER_INTERIOR`.
  3. **Work 3.1.1 Hotfix Verified:** `uWaterFlowPhase` is completely absent from `WaterWorldShaders.kt` and `WaterWorldStaticRenderer.kt`.
  4. **Five Resident Filaments V1 + Work 3.2 True 3D Orbital Motion:** `WaterWorldResidentContractV1.kt`, `WaterWorldResidentOrbitalMath.kt`, `uResidentOrbitMat` / `uResidentOrbitEnabled` in `RESIDENT_RIBBON_VERTEX` for G01 / G02 / A01 / A02 / C01.
  5. **Frozen Water World V2 Archive:** `releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip` (`65` members, SHA-256 `6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095`, master PNG excluded from APK).

## 2. Tab 1 / Tab 2 / Tab 3 Entry & Rendering Architecture Audit

| Tab | Current Entry | Current Renderer | Current Lifecycle & GL Surface |
|---|---|---|---|
| **Tab 1 (`AI Orb`)** | `MainActivity.kt` (`DemoTab.AI_ORB`) | `AIOrbCanvas` -> `AIOrbScene` (`OrbSceneMode.ORB_ONLY`) | Single `GLSurfaceView` continuously running V3 AI Orb. |
| **Tab 2 (`照片水光`)** | `MainActivity.kt` (`DemoTab.TASK_PERIMETER`) | Previously shared `AIOrbCanvas` (`OrbSceneMode.TASK_PERIMETER_DEMO`) with old V3 Orb + `TaskEnergyRibbonRenderer` beam + procedural cyan background + `PhotoTextureRenderer` + `PerimeterWaterLightRenderer` | Overlaid by `TaskRecognitionDemoScreen` Compose UI; previously emitted an energy beam from Orb to photo and used old V3 Orb instead of Water World V2.1 ecosystem. |
| **Tab 3 (`水下世界`)** | `MainActivity.kt` -> `WaterWorldPreviewActivity` (Debug 3rd tab) | `WaterWorldStaticRenderer` + `WaterWorldMotionRuntime` (11 Render Passes, 5 Optical Nodes, 32 Z-Slice Core, 5 Residents, 4 Fish, 2 Plants, 3 Rocks, 9 Outer + 14 Inner Bubbles) | Separate `Activity` with its own isolated `GLSurfaceView`, staging assets into `cacheDir/water_world_v2_1`. |

## 3. Existing Regression Contracts Identified (Must Stay 100% Green)

- `ExistingOrbTabRegressionTest`: validates `AIOrbConfig.Default`, `AIOrbScene.activeRenderPassesV3`, `AIOrbScene.activeRenderPassesTaskDemo`, and `AIOrbViewModel` Tab 1 <-> Tab 2 config preservation. Therefore `AIOrbScene` and `TaskEnergyController` remain intact while Tab 2 mounts the new dedicated **Water World Full Ecosystem + Frozen Underwater Background + Independent Photo Perimeter Light** runtime!
- `TaskDemoLayoutTest`, `TaskEnergyControllerTest`: validate `fishing_photo.webp`, `TaskSceneLayoutSnapshot`, `TaskEnergyController`, and `TaskWaterLightShaders`. All preserved untouched.
- `WaterWorldThirdTabIntegrationTest`, `WaterWorldWork32OrbitalMotionTest`, `WaterWorldWork31CoreVolumeTest`, `WaterWorldWork3DynamicRuntimeTest`, `WaterWorldFiveResidentFilamentsV1Test`: validate Tab 3 `WaterWorldPreviewActivity` and every frozen Water World subsystem. All preserved with zero regression.

## 4. Target Migration Architecture (Zero Code Duplication)

1. **Frozen Background Integration:** Copy `public/assets/aistudio/ChatGPT 图像 2026年11月23日 10_33_32.png` byte-for-byte into `app/src/main/assets/tab2_background/tab2_underwater_bg.png` (bypassing AAPT2 PNG crunching so the packaged APK contains the exact SHA-256 and Git Blob SHA-1).
2. **Shared Water World Scene Engine (Reuse, Never Fork):** Extract `WaterWorldAssetStaging.kt` and add an opt-in `BackdropMode.TRANSPARENT_COMPOSITE` + `presentCompositeIntoViewport(...)` to `WaterWorldStaticRenderer` (default `BackdropMode.WATER_WORLD_NATIVE` keeps Tab 3 100% bit-for-bit identical!).
3. **Tab 2 Dedicated Unified GLES 3.0 Renderer (`Tab2WaterWorldSceneRenderer.kt`):**
   - **Layer 1 (Bottom):** Full-screen aspect-preserving frozen underwater PNG (`assets/tab2_background/tab2_underwater_bg.png`).
   - **Layer 2 (Lower Centered Square):** Shared `WaterWorldStaticRenderer.renderDynamic()` (all 11 Render Passes, 5 Optical Nodes, Core 3D 32 Z-slices, 5 Residents Work 3.2 true 3D orbital motion, 4 fish, 2 plants, 3 rocks, 23 bubbles) composited seamlessly over the underwater background with zero dark square borders and zero background leak inside the crystal sphere.
   - **Layer 3 (Upper Region):** Existing `PhotoTextureRenderer` (default `fishing_photo.webp` + optional zero-permission Android Photo Picker `PickVisualMedia` replacement) + existing `PerimeterWaterLightRenderer` driven by new `Tab2IndependentPerimeterController` (`PHOTO_SELECTED -> PHOTO_STABLE -> PERIMETER_LIGHT_ACTIVATED`, strictly **ZERO** Orb-to-photo energy beam!).
4. **GL Surface & Lifecycle Isolation:**
   - When Tab 1 is active: `AIOrbCanvas` renders (`RENDERMODE_CONTINUOUSLY`); Tab 2 `Tab2WaterWorldCanvas` is unmounted/paused (0 GPU usage).
   - When Tab 2 is active: `AIOrbCanvas` is paused (`onPause()` + `RENDERMODE_WHEN_DIRTY`, 0 GPU usage, EGL preserved); `Tab2WaterWorldCanvas` owns its isolated EGL context, auto-plays once `FINAL_SCENE` + `CORE_VOLUME_READY`, pauses cleanly on `ON_PAUSE` / tab exit, resumes without time jump, and recovers cleanly from EGL context loss.
   - When Tab 3 opens: `MainActivity` pauses, halting Tab 1 / Tab 2 rendering; Tab 3 runs completely independently.
