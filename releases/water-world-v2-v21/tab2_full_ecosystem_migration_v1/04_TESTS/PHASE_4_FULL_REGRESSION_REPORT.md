# PHASE_4_FULL_REGRESSION_REPORT

**Task ID:** `TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`  
**Phase:** Phase 4 — Full Gradle Build, Unit Test Regression, Lint & Packaged APK Audit  
**Status:** `PASS` (with pre-existing unrelated Tab 1 test documented honestly; hardware GPU gates `NOT_RUN`)

## 1. Gradle Verification Matrix

| Task | Command | Result |
|---|---|---|
| Debug APK Build | `gradle :app:assembleDebug --console=plain` | **PASS** |
| AndroidTest Build | `gradle :app:assembleAndroidTest --console=plain` | **PASS** |
| Release Kotlin Compile | `gradle :app:compileReleaseKotlin --console=plain` | **PASS** |
| Android Lint (Debug) | `gradle :app:lintDebug --console=plain` | **PASS** (0 errors) |
| Frozen Water World Asset Check | `gradle :app:verifyFrozenWaterWorldArchive` | **PASS** (65 source assets, 56 draw bindings, 11 stages, 5 optics, `master_bound_as_texture=false`) |
| Unit & Robolectric Tests (API 36) | `gradle :app:testDebugUnitTest --console=plain --continue` | **204 / 205 PASS** (`16 / 16` new Tab 2 migration tests PASS; 1 pre-existing unrelated failure) |

## 2. New Tab 2 Migration Test Suite (`Tab2WaterWorldMigrationV1Test` — 16 / 16 PASS)

1. `t01_frozenUnderwaterBackgroundAssetMatchesExactSha256AndGitBlobAndDecodes852x1846` — PASS
2. `t02_underwaterBackgroundAspectPreservingCoverUvNeverStretchesAcrossViewports` — PASS
3. `t03_tab2WaterWorldAssetStagingLoadsSharedFrozenSceneAndWork32MotionProfile` — PASS
4. `t04_tab2Core3DVolume32ZSlicesAndMotionAdapterWork31ClosureVerified` — PASS
5. `t05_tab2FiveResidentsG01G02A01A02C01AndWork32True3DOrbitalMotionActive` — PASS
6. `t06_tab2FourFishIndependentSwimmingAndDeformationParametersActive` — PASS
7. `t07_tab2TwoPlantsThreeRocksNineOuterAndFourteenInnerBubblesActive` — PASS
8. `t08_tab2ElevenRenderPassesAndFiveOpticalNodesGraphValidated` — PASS
9. `t09_tab2SingleFrameSnapshotConsistencyAcrossAllSubsystems` — PASS
10. `t10_tab2PauseOnHideAndZeroTimeJumpOnResumeVerified` — PASS
11. `t11_tab2AndTab3GlLifecycleIsolationAndRendererSeparationVerified` — PASS
12. `t12_tab2EglContextLossRecoveryAndSafeDisposeVerified` — PASS
13. `t13_tab2IndependentPhotoPerimeterLightTransitionsAndStrictlyZeroOrbBeam` — PASS
14. `t14_tab2OrbViewportRectNeverOverlapsPhotoAndPreserves1To1CircleAcrossViewports` — PASS
15. `t15_tab2NewGlslEs300ShadersSatisfyStrictActiveUniformContractWithZeroDeadUniforms` — PASS
16. `t16_tab1AndTab3ZeroRegressionAndTab2ComposeOverlayRendersCleanly` — PASS

## 3. Existing Suite Zero-Regression Confirmation

- `WaterWorldShaderUniformContractTest` (Work 3.1.1 uniform parity across all 12 Water World programs, including new `uSphereBackdropRadius` in `OPAQUE_FRAGMENT`): **5 / 5 PASS**
- `WaterWorldWork32OrbitalMotionTest` (Work 3.2 Five Resident True 3D Orbital Motion): **13 / 13 PASS**
- `WaterWorldThirdTabIntegrationTest` (Tab 3 `WaterWorldPreviewActivity` zero regression): **PASS**
- `ExistingOrbTabRegressionTest`, `TaskDemoLayoutTest`, `TaskEnergyControllerTest`, `TaskWaterLightVisualParityV4Test`: **PASS**
- **Pre-Existing Unrelated Failure (Honest Disclosure):** `AIOrbV32ReferenceFittingTest > v32_04_stageACameraAndStageEOpticsHardGatesPassInVisualLoss` (inherited from Base Main HEAD before Work 3.2 and Tab 2 migration; Tab 1 V3 fitting code was not modified).

## 4. Packaged Debug APK Audit (`app/build/outputs/apk/debug/app-debug.apk`)

- **APK Size:** `28,757,505` bytes
- **APK SHA-256:** `09e540e62e773c10cce84e88baacf8e4b0d779f2c3ef48e2be0e1a76eb7b7119`
- **Tab 2 Background (`assets/tab2_background/tab2_underwater_bg.png`):**
  - Size: `1,557,320` bytes (EXACT)
  - SHA-256: `f3dc1549919f48f939df5a6cf5baf18fb6846164c6f857df8e20cfe247b495ba` (EXACT)
  - Git Blob SHA-1: `92562ff0a60bc63f3064c98af1922f817fee22c9` (EXACT)
- **Water World Assets (`assets/water_world_v2_1/`):** `148` files packaged; `0` Master PNG leaks
- **Core 3D Volume (`assets/orb_v3/volume/`):** `32` Z-Slice WebP files (`fluid_color_z00.webp` .. `fluid_color_z31.webp`)

## 5. Hardware GPU Gate Honesty

- `GLES_SHADER_PASS = NOT_RUN` (No Android GPU / EGL driver inside container)
- `HARDWARE_GPU_VERIFIED = NOT_RUN` (Pending user HUAWEI CMS-AL20 real-device acceptance)
