# WATER WORLD V2.1 — Work 3 · Code Audit & Runtime Call Graph

**Task ID:** `WATER_WORLD_V21_WORK3_FULL_DYNAMIC_RUNTIME_V1`  
**Audit Baseline HEAD:** `20c0614973a8f525f045f3d6696deefbbb652545`  
**Status:** `WORK3_AUDIT_COMPLETE`

---

## 1. Frozen Asset & Boundary Audit

| Frozen Asset | Expected SHA-256 | Policy |
|---|---|---|
| Master PNG (`releases/water-world-v2-v21/奇幻水晶球中的水下生态世界 (1).png`) | `fef8225a7322aa9edf77d2c753efe51af2189384e757eb0e974a68a88f44c589` | Read-only reference; strictly excluded from APK and shaders |
| V2 Assets ZIP (`releases/water-world-v2/WATER_WORLD_V2_ASSETS.zip`) | `6b85708fc5eccf8f5c130b1d9c48bfd59abc829b178db9dd0729b4ef7b065095` | Byte-frozen; 65 textures verified at build time |
| V2.1 Pack ZIP (`releases/water-world-v2-v21/WATER_WORLD_V2_1_ANDROID_GPU_PACK.zip`) | `28f8918c39f6e875602d3a7e90775e9d455aa6c4e42783247d1104c3b4509679` | Byte-frozen |
| `motion_element_registry.json` (in V2.1 / Work 1.5) | Unmodified | Retains `water_world_motion_values_validated = false` and `null` GT |
| Work 1.6 / Work 2 6-axis static scoring algorithm | Unmodified | Preserved strictly for T=0 regression verification |

---

## 2. Existing Water World Code Audit (`app/src/main/java/com/example/waterworld/`)

### 2.1 Pre-Work-3 Static Render Call Graph

```
MainActivity (Tab 3 button)
  └─> WaterWorldPreviewActivity.onCreate()
        ├─> validatePackagedAssetsPresent()
        ├─> startBackgroundScenePreparation() -> WaterWorldSceneBindings.loadScene()
        └─> GLSurfaceView (RENDERMODE_WHEN_DIRTY) -> Preview (GLSurfaceView.Renderer)
              └─> onDrawFrame()
                    ├─> WaterWorldStaticRenderer.initialize(scene)
                    │     ├─> WaterWorldRenderTargetPool.initialize(1254, 1254)
                    │     ├─> WaterWorldTextureCache.load(scene)
                    │     ├─> WaterWorldShaders() [8 groups / 12 GLSL ES 3.0 programs]
                    │     └─> WaterWorldMesh.upload(meshJson) [56 draws]
                    ├─> WaterWorldStaticRenderer.render(mode, SharedFrameSnapshot.staticZero())
                    │     └─> 11 Passes + 5 Optical Nodes (all static T=0)
                    └─> WaterWorldStaticRenderer.present(width, height)
```

### 2.2 Pre-Work-3 Findings (Gaps Closed in Work 3)

| Area | Pre-Work-3 State | Work 3 Upgrade |
|---|---|---|
| Render Loop | `GLSurfaceView.RENDERMODE_WHEN_DIRTY`, renders only 1 static frame | Unified `WaterWorldMotionRuntime` + Choreographer/continuous playback auto-starting on Tab 3 entry, switching cleanly to `WHEN_DIRTY` on `PAUSED` / `onPause` |
| Time Snapshot | Only `SharedFrameSnapshot.staticZero()` (`timeSeconds = 0.0`) | Immutable `WaterWorldDynamicFrameSnapshot` covering all 47 canonical elements & 28 dependency edges, evaluated from one monotonic `WaterWorldMotionTimeline` |
| Shader Uniforms | Zero time/motion/deformation uniforms across all 8 shader groups | Time-driven vertex & fragment uniforms defaulting to `0.0` / identity at `T=0` (`staticReferenceMode = true`) for exact Work 2 T=0 parity |
| Fish Meshes (`WW_FISH_01..04`) | 17×7 vertex grids (`119` vertices), facing `+X` (`u=0` tail tip, `u=1` nose), rigid static draw | Closed 3D Catmull-Rom swim loops inside crystal sphere avoiding rocks + GPU vertex-shader head-to-tail sinusoidal swish & dorsal/pelvic fin flutter |
| Resident Meshes (`13` ribbons across Pass 02 & Pass 10) | High-res Catmull-Rom ribbon strips (`158..366` vertices) with `u ∈ [0,1]` arc-length and `v ∈ [0,1]` width | Unified per-Resident (`G01, G02, A01, A02, C01`) directional arc-length filament flow, non-uniform wave, width pulse (`±3–6%`), and brightness pulse (`±4–8%`) across both back and front layers |
| Plant Meshes (`plant_left`, `plant_right`, `plant_mid_sprout`, `far_plant_*`) | 4-vertex rigid quads (`2×2`) | Non-destructive runtime grid subdivision (`6×12` vertices) in `WaterWorldMesh.kt` + root-pinned (`vUv.y = 1.0`) vertex-shader sway coupled to water flow (`<= 2%` height) |
| Rock Meshes (`rock_01..03`, `far_rock_*`) | 4-vertex quads with static caustics | Physically stationary (`dx=0, dy=0, angle=0`); surface caustic UV animates continuously with `uCausticsPhase` + `uWaterNormalOffset` |
| Bubbles (`B01..B09` + `IB01..IB14`) | Static quads | Deterministic per-bubble rise, horizontal drift (`<= 1.5% D`), top fade-out, bottom re-entry, exact `T=0` anchor |
| AI Orb Core Integration | Disconnected from Water World; 32 WebP spatial Z-slices exist in `assets/orb_v3/volume/` | `WaterWorldCoreMotionAdapter` binds all 8 `CORE_*` elements to Water World's timeline (`2.0..6.0 deg/s`, `SPATIAL_Z_SLICE_NOT_TIME_FRAME` strictly preserved), modulates interior fluid/caustics/glass optics without duplicating glass shells |

---

## 3. AI Orb Core Audit (`app/src/main/java/com/example/orb/scene/`)

- `AIOrbCore.kt` delegates to `OrbFluidVolumeRenderer.kt`, which renders **both** the 3D volume (`GL_TEXTURE_3D`, 32 spatial Z-slices from `assets/orb_v3/volume/slice_00..31.webp`) **and** AI Orb's own double-wall crystal glass shell (`CoreFluidShaders.CORE_GLASS_FRAG`) with `AIOrbMotionController.BASE_CORE_ROTATION_DEG_PER_SEC = 26.0 deg/s`.
- **Critical Integration Rule:** Water World already owns dedicated `03_SPHERE_BACK` (`WW_GLASS_BACK`) and `09_SPHERE_FRONT` (`WW_GLASS_FRONT`) glass passes. Rendering `OrbFluidVolumeRenderer` directly on top would cause **duplicate glass shells** and conflict with Work 2's frozen glass silhouette.
- **Work 3 Architecture Decision:** `WaterWorldCoreMotionAdapter` reuses `AIOrbMotionController.buildAxisAngleMat3()` math and the exact 32-slice spatial Z-slice contract (`CORE_VOLUME_SLICE_COUNT = 32`, `CORE_VOLUME_SLICE_SEMANTICS = SPATIAL_Z_SLICE_NOT_TIME_FRAME`), driven at Water World's calm underwater speed (`3.6 deg/s`), feeding unified volumetric fluid/density/color/caustics/glass optical parameters into Pass `04_WATER_INTERIOR`, Pass `06_LIGHTING`, Pass `03_SPHERE_BACK`, and Pass `09_SPHERE_FRONT`.
- **`CORE_FILAMENT` Honest Disclosure:** No standalone filament texture exists in V2/V2.1 frozen assets. `CORE_FILAMENT` is explicitly marked `ASSET_GAP_PROCEDURAL_RUNTIME_HYPOTHESIS` and driven procedurally as subtle interior underwater silk currents coupled to `CORE_FLUID` and `WW_WATER_FLOW` (zero amplitude at `T=0`).

---

## 4. Target Work 3 Dynamic Call Graph

```
WaterWorldPreviewActivity (Tab 3)
  ├─> WaterWorldMotionRuntime (Single Authoritative Motion Controller)
  │     ├─> WaterWorldMotionTimeline (PLAYING / PAUSED / RESUMED / RESET / SPEED_SCALE)
  │     ├─> WaterWorldMotionProfile (assets/water_world_v2_1/04_MOTION/work3_motion_profile.json)
  │     ├─> WaterWorldMotionEvaluator (Pure deterministic 47-element & 28-dependency evaluator)
  │     └─> WaterWorldCoreMotionAdapter (AI Orb 32-Z-slice spatial core adapter, zero double glass)
  ├─> Choreographer / GLSurfaceView Continuous Playback Driver
  │     └─> Preview.onDrawFrame()
  │           ├─> snapshot = motionRuntime.stepFrame(System.nanoTime())
  │           ├─> WaterWorldStaticRenderer.renderDynamic(snapshot, activeMode)
  │           │     └─> All 11 Passes + 5 Optical Nodes consume THE SAME immutable snapshot
  │           └─> WaterWorldStaticRenderer.present(width, height) [+ GL Fence Sync]
  └─> WaterWorldDynamicEvidenceRecorder (Deterministic T=0,1,2,4,8,12 + telemetry + manifest)
```
