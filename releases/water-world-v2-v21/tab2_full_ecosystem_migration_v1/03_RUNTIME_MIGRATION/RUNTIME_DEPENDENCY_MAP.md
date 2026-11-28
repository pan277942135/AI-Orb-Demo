# RUNTIME_DEPENDENCY_MAP

**Task ID:** `TAB2_WATER_WORLD_FULL_ECOSYSTEM_MIGRATION_V1`

```
MainActivity (Compose Host)
 ├── DemoTab.AI_ORB (Tab 1 — 100% Untouched)
 │    └── AIOrbCanvas (Isolated GLSurfaceView)
 │         └── AIOrbScene (ORB_ONLY · V3 Core + 5 Residents)
 │
 ├── DemoTab.TASK_PERIMETER (Tab 2 — Migrated Full Ecosystem)
 │    ├── Tab2WaterWorldCanvas (Isolated GLSurfaceView + LifecycleObserver)
 │    │    └── Tab2WaterWorldSceneRenderer (Unified GLES 3.0 Pipeline)
 │    │         ├── [Layer 1] Tab2UnderwaterBackgroundRenderer
 │    │         │    └── assets/tab2_background/tab2_underwater_bg.png (Exact Frozen PNG)
 │    │         ├── [Layer 2] WaterWorldStaticRenderer (REUSED FROM TAB 3, BackdropMode.TRANSPARENT_COMPOSITE)
 │    │         │    ├── WaterWorldAssetStaging -> assets/water_world_v2_1/ (65 frozen V2 + Work 1.5 + Work 3/3.2)
 │    │         │    ├── WaterWorldMotionRuntime / Timeline / Profile / Evaluator (14 objs · 47 elems · 28 deps)
 │    │         │    ├── WaterWorldRenderGraph (11 Render Passes · 5 Optical Nodes)
 │    │         │    ├── WaterWorldShaders (12 GLSL ES 3.00 programs · Work 3.1.1 uWaterFlowPhase removed)
 │    │         │    ├── OrbFluidVolumeRenderer + CoreFluidShaders + WaterWorldCoreMotionAdapter (32 Z-Slices)
 │    │         │    └── WaterWorldResidentContractV1 + WaterWorldResidentOrbitalMath (Work 3.2 5 Residents 3D Orbit)
 │    │         ├── [Composite] Tab2WaterWorldSphereComposite (Premultiplied Sphere Mask + Outer Luminous Glow)
 │    │         └── [Layer 3] Upper Photo + Independent Perimeter Light (ZERO Orb->Photo Beam)
 │    │              ├── PhotoTextureRenderer (fishing_photo.webp + PickVisualMedia custom bitmap)
 │    │              ├── Tab2IndependentPerimeterController (PHOTO_SELECTED -> PHOTO_STABLE -> PERIMETER_LIGHT_ACTIVATED)
 │    │              └── PerimeterWaterLightRenderer (Reused L1-L4 perimeter light, releaseProgress=0)
 │    └── TaskRecognitionDemoScreen (Transparent Compose Overlay + PickVisualMedia Button + Diagnostics Pill)
 │
 └── Debug Tab 3 (`水下世界` — 100% Untouched)
      └── WaterWorldPreviewActivity (Separate Activity + Isolated GLSurfaceView)
           └── WaterWorldStaticRenderer (BackdropMode.WATER_WORLD_NATIVE) + WaterWorldMotionRuntime
```
