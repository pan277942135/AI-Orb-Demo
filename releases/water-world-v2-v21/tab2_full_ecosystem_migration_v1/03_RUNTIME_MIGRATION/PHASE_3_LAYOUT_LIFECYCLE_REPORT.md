# PHASE_3_LAYOUT_LIFECYCLE_REPORT

**Task ID:** `TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`  
**Phase:** Phase 3 — Tab 2 Layout Fusion, Independent Photo Perimeter Light & GL Lifecycle Isolation  
**Status:** `PASS`

## 1. Dual-Region Vertical Layout (`Tab2OrbViewportRect.compute` + `TaskRecognitionDemoScreen`)

- **Layer 1 (Full-Screen Background):** `Tab2UnderwaterBackgroundRenderer` renders exact unmodified `assets/tab2_background/tab2_underwater_bg.png` (`852×1846`) with aspect-preserving cover UV mapping (top sunlit surface + top-right sun rays + bottom aquatic plants + caustics preserved without stretching).
- **Layer 2 (Lower Region — Full Water World Ecosystem Sphere):** `Tab2OrbViewportRect.compute(...)` dynamically computes a centered 1:1 square viewport strictly below `photoFrame.bottomPx + captionReservePx` and above `bottomSafeInsetPx`, guaranteeing:
  - True 1:1 circular sphere aspect ratio across 16:9, 19.5:9, 20:9, foldable, and tablet viewports.
  - Zero overlap with the upper photo frame or status caption (`正在理解照片`).
  - Zero bottom-edge clipping of the crystal sphere.
- **Layer 3 (Upper Region — Photo Recognition & Independent Perimeter Light):**
  - `PhotoTextureRenderer` draws the rounded photo card (`fishing_photo.webp` by default, or user-selected image via zero-permission Android Photo Picker `ActivityResultContracts.PickVisualMedia` button `选图`).
  - `PerimeterWaterLightRenderer` is driven exclusively by `Tab2IndependentPerimeterController` (`PHOTO_SELECTED -> PHOTO_STABLE -> PERIMETER_LIGHT_ACTIVATED`).
  - **Strictly ZERO Orb-to-photo energy beam:** `releaseProgress == 0f` and `contactIntensity == 0f` at all times; `TaskEnergyRibbonRenderer` is never invoked in Tab 2.

## 2. Strict GL Surface & Lifecycle Isolation Across Tab 1 / Tab 2 / Tab 3

| Active Tab | Mounted `GLSurfaceView` | Background / Hidden Tabs | EGL Context Ownership |
|---|---|---|---|
| **Tab 1 (`AI Orb`)** | `AIOrbCanvas` (`AIOrbScene`, `ORB_ONLY`) | `Tab2WaterWorldCanvas` unmounted & released (0 GPU); Tab 3 not started | Tab 1 isolated EGL context |
| **Tab 2 (`照片水光`)** | `Tab2WaterWorldCanvas` (`Tab2WaterWorldSceneRenderer`) | `AIOrbCanvas` unmounted (0 GPU, zero overlapping `GLSurfaceView` Z-order conflict); Tab 3 not started | Tab 2 isolated EGL context (`preserveEGLContextOnPause = true`) |
| **Tab 3 (`水下世界`)** | `WaterWorldPreviewActivity` `GLSurfaceView` | `MainActivity` receives `ON_PAUSE` -> active Tab 1/2 `GLSurfaceView.onPause()` + `motionRuntime.onActivityPause()` | Tab 3 isolated EGL context (0 lines modified in `WaterWorldPreviewActivity`) |

## 3. Lifecycle Pause / Resume / Context-Loss Guarantees

- **Activity `ON_PAUSE`:** `Tab2WaterWorldCanvas` invokes `renderer.onHostPause()` (`motionRuntime.onActivityPause()` + `perimeterController.setPaused(true)`) and `glSurfaceView.onPause()`.
- **Activity `ON_RESUME`:** `Tab2WaterWorldCanvas` invokes `renderer.onHostResume()` (`motionRuntime.onActivityResume(nowNanos)` + `perimeterController.setPaused(false)`) and `glSurfaceView.onResume()` — zero time jump or phase discontinuity.
- **Tab Exit (`onDispose`):** queues `releaseGlResourcesOnGlThread()`, pauses `GLSurfaceView`, and calls `renderer.dispose()` (shuts down staging I/O executor and recycles any pending custom photo bitmap).
- **EGL Context Loss (`onSurfaceCreated` re-entry):** calls `waterWorldRenderer?.handleCoreContextLost()`, releases stale handles, and cleanly re-initializes background, photo, perimeter, composite shader, and shared `WaterWorldStaticRenderer` + 32 Z-slice Core 3D volume.
