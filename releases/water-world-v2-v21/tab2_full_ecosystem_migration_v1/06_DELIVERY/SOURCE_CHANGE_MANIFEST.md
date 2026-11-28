# SOURCE_CHANGE_MANIFEST

**Task ID:** `TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`

## 1. Added Production Assets (1 File)

- `app/src/main/assets/tab2_background/tab2_underwater_bg.png` — Exact byte-for-byte copy of frozen `public/assets/aistudio/ChatGPT 图像 2026年11月23日 10_33_32.png` (`1,557,320` bytes, `852×1846`, SHA-256 `f3dc1549919f48f939df5a6cf5baf18fb6846164c6f857df8e20cfe247b495ba`, Git Blob SHA-1 `92562ff0a60bc63f3064c98af1922f817fee22c9`).

## 2. Added Production Source Files (5 Files)

- `app/src/main/java/com/example/waterworld/WaterWorldAssetStaging.kt` — Thread-safe shared frozen Water World asset staging helper for Tab 2 (using dedicated `water_world_v2_1_tab2` cache directory so Tab 2 and Tab 3 never contend).
- `app/src/main/java/com/example/orb/task/Tab2UnderwaterBackgroundRenderer.kt` — GLES 3.0 full-screen aspect-preserving renderer for the exact frozen underwater PNG (`assets/tab2_background/tab2_underwater_bg.png`).
- `app/src/main/java/com/example/orb/task/Tab2IndependentPerimeterController.kt` — Deterministic state machine (`PHOTO_SELECTED -> PHOTO_STABLE -> PERIMETER_LIGHT_ACTIVATED`) driving Tab 2's independent photo perimeter water-light with strictly zero Orb-to-photo energy stream.
- `app/src/main/java/com/example/orb/task/Tab2WaterWorldSceneRenderer.kt` — Unified Tab 2 GLES 3.0 renderer reusing Tab 3's `WaterWorldStaticRenderer` + `WaterWorldMotionRuntime`, `Tab2UnderwaterBackgroundRenderer`, `PhotoTextureRenderer`, `PerimeterWaterLightRenderer`, and seamless premultiplied sphere-mask + luminous outer glow composite shader (`SPHERE_COMPOSITE_FRAG`).
- `app/src/main/java/com/example/orb/ui/Tab2WaterWorldCanvas.kt` — Isolated Compose `GLSurfaceView` host for Tab 2 with full `LifecycleEventObserver` (`ON_PAUSE` / `ON_RESUME` zero time jump) and clean GL resource disposal on tab switch.

## 3. Modified Production Source Files (5 Files — Strictly Minimal & Zero Regression)

- `app/src/main/java/com/example/waterworld/WaterWorldShaders.kt` — Added actively-read `uniform float uSphereBackdropRadius;` to `OPAQUE_FRAGMENT` (`0.0` default in Tab 3 = 100% identity; `> 0.5` in Tab 2 masks Pass 01 dark ocean backdrop cleanly to sphere interior).
- `app/src/main/java/com/example/waterworld/WaterWorldStaticRenderer.kt` — Added opt-in `BackdropMode` (`WATER_WORLD_NATIVE` default for Tab 3; `TRANSPARENT_COMPOSITE` for Tab 2), uploaded `uSphereBackdropRadius`, and exposed read-only getters `finalTargetTextureId`, `finalTargetSize`, `activeOpticalConfig`, `activeBackdropMode`.
- `app/src/main/java/com/example/orb/task/PhotoTextureRenderer.kt` — Added `uploadCustomPhotoBitmap(bitmap)` so Tab 2 supports user-selected photos via zero-permission `PickVisualMedia`.
- `app/src/main/java/com/example/orb/ui/TaskRecognitionDemoScreen.kt` — Added optional `waterWorldStatus` & `onPickPhoto` parameters, tuned photo vertical budget for generous lower Water World sphere clearance, added top-right `选图` button, and surfaced loading/error/readiness status pills cleanly.
- `app/src/main/java/com/example/MainActivity.kt` — Mounted `AIOrbCanvas` exclusively on Tab 1 and `Tab2WaterWorldCanvas` exclusively on Tab 2 (strictly one `GLSurfaceView` at a time, zero hidden-tab GPU burn, zero SurfaceView Z-order conflict) and wired zero-permission `ActivityResultContracts.PickVisualMedia`.
- `app/src/main/res/values/strings.xml` — Added 3 localized Tab 2 strings (`task_pick_photo`, `cd_task_pick_photo`, `tab2_water_world_loading`).

## 4. Untouched Protected Files Confirmed (0 Lines Changed)

- `app/src/main/java/com/example/waterworld/WaterWorldPreviewActivity.kt` — **0 lines changed** (Tab 3 untouched).
- `app/src/main/java/com/example/orb/scene/AIOrbScene.kt` — **0 lines changed** (Tab 1 untouched).
- `app/src/main/java/com/example/orb/gl/CoreFluidShaders.kt` & `OrbFluidVolumeRenderer.kt` — **0 lines changed**.
- `app/src/main/java/com/example/waterworld/WaterWorldMotionProfile.kt`, `WaterWorldMotionEvaluator.kt`, `WaterWorldMotionRuntime.kt`, `WaterWorldResidentOrbitalMath.kt` — **0 lines changed**.
- `releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip` & `public/assets/aistudio/ChatGPT 图像 2026年11月23日 10_33_32.png` — **0 bytes changed**.

## 5. Added Tests & Visual Tooling (2 Files)

- `app/src/test/java/com/example/Tab2WaterWorldMigrationV1Test.kt` — 16 mandatory Tab 2 migration & regression unit/Robolectric tests (16/16 PASS).
- `tools/water-world-scene-assembly/Tab2WaterWorldMigrationSoftwarePrecheck.java` — Phase 5 software visual precheck & comparison generator.
